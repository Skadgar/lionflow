package no.lionflow.app.plan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.lionflow.app.LionFlowApp
import no.lionflow.app.api.Http
import no.lionflow.app.model.ChargePower
import no.lionflow.app.model.Trip
import no.lionflow.app.model.VehicleStatus
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** The trip planner's state and actions. Lives in the view model, so a plan survives switching tabs. */
class Planner(
    private val scope: CoroutineScope,
    private val app: LionFlowApp,
    private val status: () -> VehicleStatus?,
    private val trips: () -> List<Trip>,
    private val myPos: () -> Pair<Double, Double>?,
) {
    var open by mutableStateOf(false)
    /** The address form is showing (phones: full screen). */
    var editing by mutableStateOf(true)
    /** null = where the car is. */
    var from by mutableStateOf<Place?>(null)
    var to by mutableStateOf<Place?>(null)
    /** Up to 3 places the trip passes through, in order; null = a slot the user is still filling in. */
    var vias by mutableStateOf<List<Place?>>(emptyList())
    /** Minutes to stay at each via stop (same order as [vias]). */
    var stays by mutableStateOf<List<Int>>(emptyList())
    /** null = the car's current charge. */
    var startPct by mutableStateOf<Int?>(null)
    var minPct by mutableStateOf(app.settings.raw("planMin").toIntOrNull()?.coerceIn(5, 50) ?: 20)
    var targetPct by mutableStateOf(app.settings.raw("planTarget").toIntOrNull()?.coerceIn(50, 100) ?: 80)

    var route by mutableStateOf<RouteData?>(null)
        private set
    var chargers by mutableStateOf<List<PlanCharger>>(emptyList())
        private set
    var plan by mutableStateOf<Plan?>(null)
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var model by mutableStateOf(Consumption.Model(1.0, 0))
        private set
    var edits by mutableStateOf<List<StopEdit>>(emptyList())
        private set
    var excluded by mutableStateOf<Set<String>>(emptySet())
        private set
    /** The start and end the current plan was made for. */
    var planFrom by mutableStateOf<Place?>(null)
        private set
    var planTo by mutableStateOf<Place?>(null)
        private set
    var planVias by mutableStateOf<List<Place>>(emptyList())
        private set
    var planStays by mutableStateOf<List<Int>>(emptyList())
        private set

    /** When the trip starts; null = now. A time that has passed counts as now. */
    var departAt by mutableStateOf<Long?>(null)
        private set
    fun departMillis(): Long = departAt?.takeIf { it > System.currentTimeMillis() } ?: System.currentTimeMillis()

    /** A car ferry on the route with the wait for it: when it leaves (if the timetable is known) and minutes to wait. */
    class FerryInfo(val ferry: Ferry, val waitMin: Int, val departMs: Long?)
    var ferryInfo by mutableStateOf<List<FerryInfo>>(emptyList())
        private set

    /** One of the routes found: the road, and its plan once that is worked out (null until then). */
    class AltRoute(val route: RouteData, val plan: Plan?, val raw: List<RawCharger>?)
    var alts by mutableStateOf<List<AltRoute>>(emptyList())
        private set
    var altIndex by mutableStateOf(0)
        private set
    private var altJob: Job? = null

    var saved by mutableStateOf(loadPlaces("planSaved"))
        private set
    var recent by mutableStateOf(loadPlaces("planRecent"))
        private set
    var results by mutableStateOf<List<Place>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set

    private var searchJob: Job? = null
    private var planJob: Job? = null
    private var corridorFor: RouteData? = null
    private var corridorRaw: List<RawCharger> = emptyList()
    private var viaRawKey = ""
    private var viaRaw: List<RawCharger> = emptyList()
    /** The car's top charging power used for the plan, and how many of its own fast charges that was fitted to (0 = a typical figure). */
    var carKw by mutableStateOf(0.0)
        private set
    var fitCount by mutableStateOf(0)
        private set
    /** The charger lookup, started as soon as a long route is known so it runs while the first plan is worked out. */
    private var prefetch: kotlinx.coroutines.Deferred<Result<List<RawCharger>>>? = null
    private var prefetchFor: RouteData? = null
    private var priceCountry = ""
    private val prices by lazy {
        Prices(try { app.assets.open("charge_prices.json").bufferedReader().use { it.readText() } } catch (_: Exception) { "{}" })
    }
    val pricesAsOf: String get() = prices.asOf

    fun carPct(): Int = status()?.batteryPct?.roundToInt()?.coerceIn(1, 100) ?: 80
    fun startNow(): Int = startPct ?: carPct()
    fun batteryKwh(): Double = status()?.let { ChargePower.usableKwh(it) } ?: 73.0

    // ---------------- addresses ----------------

    /** Suggestions while typing; waits for a short pause first. */
    fun search(q: String) {
        searchJob?.cancel()
        if (q.trim().length < 2) { results = emptyList(); searching = false; return }
        searchJob = scope.launch {
            delay(350)
            searching = true
            try {
                val near = myPos() ?: status()?.let { st -> st.lat?.let { la -> st.lon?.let { lo -> la to lo } } }
                results = PlanApi.search(q.trim(), near?.first, near?.second, no.lionflow.app.ui.l10n.L10n.lang)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { results = emptyList() }
            searching = false
        }
    }

    fun clearSearch() { searchJob?.cancel(); results = emptyList(); searching = false }

    private fun same(a: Place, b: Place) = a.name == b.name && Geo.km(a.lat, a.lon, b.lat, b.lon) < 0.05
    fun isSaved(p: Place) = saved.any { same(it, p) }
    fun toggleSaved(p: Place) {
        saved = if (isSaved(p)) saved.filterNot { same(it, p) } else (listOf(p) + saved).take(20)
        storePlaces("planSaved", saved)
    }

    /** Takes a place off the list of recent destinations. */
    fun removeRecent(p: Place) {
        recent = recent.filterNot { same(it, p) }
        storePlaces("planRecent", recent)
    }

    private fun remember(p: Place) {
        recent = (listOf(p) + recent.filterNot { same(it, p) }).take(6)
        storePlaces("planRecent", recent)
    }

    private fun loadPlaces(key: String): List<Place> = try {
        val a = JSONArray(app.settings.raw(key).ifBlank { "[]" })
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o -> Place(o.optString("n"), o.optString("d"), o.getDouble("la"), o.getDouble("lo"), o.optString("c")) } }
    } catch (_: Exception) { emptyList() }

    private fun storePlaces(key: String, list: List<Place>) =
        app.settings.setRaw(key, JSONArray(list.map { JSONObject().put("n", it.name).put("d", it.detail).put("la", it.lat).put("lo", it.lon).put("c", it.country) }).toString())

    // ---------------- planning ----------------

    /** NOBIL key: the one built into the app, else the one typed in Settings. */
    private fun nobilKey(): String = no.lionflow.app.BuildConfig.NOBIL_KEY.ifBlank { app.settings.value.nobilKey }

    /** Where the trip starts: the chosen address, else the car, else the phone. */
    private fun origin(): Place? {
        from?.let { return it }
        val st = status()
        val country = app.settings.value.country
        if (st?.lat != null && st.lon != null) return Place("Car's position", "", st.lat, st.lon, country)
        return myPos()?.let { Place("My position", "", it.first, it.second, country) }
    }

    /** Finds the route and plans the stops. */
    fun go() {
        val b = to ?: return
        planJob?.cancel()
        planJob = scope.launch {
            error = null
            val a = origin()
            if (a == null) { error = "Choose where the trip starts"; return@launch }
            try {
                busy = "Finding the route…"
                val via = vias.filterNotNull().take(3)
                val stay = vias.indices.filter { vias[it] != null }.take(3).map { stays.getOrElse(it) { 0 } }
                val rs = PlanApi.routes(a, b, via)
                val r = rs[0]
                altJob?.cancel(); alts = rs.map { AltRoute(it, null, null) }; altIndex = 0; ferryInfo = emptyList()
                edits = emptyList(); excluded = emptySet(); chargers = emptyList(); corridorFor = null; corridorRaw = emptyList(); viaRawKey = ""; viaRaw = emptyList()
                route = r; planFrom = a; planTo = b; planVias = via; planStays = stay
                prefetch?.cancel(); prefetch = null; prefetchFor = null
                if (r.totalKm > 120) { prefetchFor = r; prefetch = scope.async(Dispatchers.IO) { runCatching { PlanApi.chargers(r, nobilKey()) } } }
                priceCountry = if (a.country.isNotBlank() && a.country.equals(b.country, ignoreCase = true)) a.country else ""
                remember(b); from?.let { remember(it) }; via.forEach { remember(it) }
                compute()
                editing = false
                startAlts()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                error = "Couldn't plan the trip — ${Http.describe(e)}"
            } finally { busy = null }
        }
    }

    /** Plans again after a change to the numbers or the stops (no new route). */
    fun recompute() {
        if (route == null) return
        planJob?.cancel()
        planJob = scope.launch {
            error = null
            try { compute(); if (alts.any { it.plan == null }) startAlts() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                error = "Couldn't find chargers — ${Http.describe(e)}"
            } finally { busy = null }
        }
    }

    /** Battery use and charging speed for this car (matched to its own trips and fast charges when there are enough). */
    private fun context(): Pair<Options, Consumption.Model> {
        val kwh = batteryKwh()
        val m = Consumption.fit(trips().mapNotNull { t ->
            val used = t.kwh ?: t.avgKwhPer100?.let { it * t.distanceKm / 100 }
            val v = t.avgSpeedKmh
            if (used != null && v != null) Consumption.TripSample(t.distanceKm, used, v) else null
        }, kwh)
        model = m
        val fitSamples = no.lionflow.app.model.ChargeLog.sessions.value
            .filter { (it.mode?.contains("Quick", true) == true || (it.avgKw ?: 0.0) >= 40.0) && it.endPct - it.startPct >= 8 && it.hours * 60 >= 8 && it.kwh > 0 }
            .sortedByDescending { it.start }.take(8).map { Triple(it.startPct, it.endPct, it.hours * 60) }
        val fitted = ChargeCurve.fitMaxKw(fitSamples, kwh)
        val maxKw = fitted ?: if (kwh >= 68) 110.0 else 80.0
        carKw = maxKw; fitCount = if (fitted != null) fitSamples.size else 0
        return Options(startNow().toDouble(), minPct, targetPct, kwh, maxKw) to m
    }

    /**
     * Works out the stops for [r], then adds what the plan itself doesn't know about: stays at the places the user
     * passes through, and the wait for car ferries (from the timetable where there is one, else a typical wait).
     */
    private suspend fun solve(
        r: RouteData, o: Options, m: Consumption.Model, ch: List<PlanCharger>, ed: List<StopEdit>, ex: Set<String>,
        prefer: Set<String>, vias: List<Place>, stays: List<Int>,
    ): Pair<Plan, List<FerryInfo>> {
        ensureConditions(r)
        val energy = withContext(Dispatchers.Default) { r.energy(m.factor) }
        val p = withContext(Dispatchers.Default) { TripPlanner.plan(r, energy, ch, o, ed, ex, m, prefer) }
        // A charging stop right at a via stop is the same wait: count the overlap once.
        val overlap = vias.indices.sumOf { i ->
            val v = vias[i]; val st = stays.getOrElse(i) { 0 }
            if (st <= 0) 0 else p.stops.filter { Geo.km(v.lat, v.lon, it.charger.lat, it.charger.lon) < 1.0 }.maxOfOrNull { minOf(it.minutes, st) } ?: 0
        }
        val start = departMillis()
        var extra = 0.0
        val infos = ArrayList<FerryInfo>()
        for (f in r.ferries.sortedBy { it.idx }) {
            val km = r.km[f.idx]
            val charging = p.stops.filter { it.charger.km <= km && it.kwh > 0.01 }.sumOf { it.minutes + 5 }
            val stayed = vias.indices.sumOf { i -> if (r.km[Geo.project(r, vias[i].lat, vias[i].lon).first] <= km) stays.getOrElse(i) { 0 } else 0 }
            val eta = start + ((r.min[f.idx] + charging + stayed + extra) * 60_000.0).toLong()
            val dep = try { kotlinx.coroutines.withTimeoutOrNull(8_000L) { PlanApi.ferryDeparture(f, eta) } } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            val wait = if (dep != null) ((dep - eta) / 60_000.0).coerceIn(0.0, 240.0) else 20.0
            infos += FerryInfo(f, wait.roundToInt(), dep)
            extra += wait
        }
        return p.copy(totalMin = p.totalMin + stays.sum() - overlap + extra) to infos
    }

    /** The stops the via places call for: a charger within 1 km of a via stop with a stay is used for that stay, and any charger that close is preferred. */
    private fun viaPlan(): Pair<List<StopEdit>, Set<String>> {
        if (planVias.isEmpty()) return emptyList<StopEdit>() to emptySet()
        val prefer = HashSet<String>(); val implicit = ArrayList<StopEdit>()
        planVias.forEachIndexed { i, v ->
            val near = chargers.map { it to Geo.km(v.lat, v.lon, it.lat, it.lon) }.filter { it.second < 1.0 }
            near.forEach { prefer += it.first.id }
            val stay = planStays.getOrElse(i) { 0 }
            val best = near.filter { it.first.id !in excluded && edits.none { e -> e.chargerId == it.first.id } }.maxByOrNull { it.first.kw * 10 - it.second }
            if (stay > 0 && best != null && implicit.none { it.chargerId == best.first.id }) implicit += StopEdit(best.first.id, minutes = stay)
        }
        return implicit to prefer
    }

    /** Hills, wind and temperature along the route, for the hour of departure (looked up once per route and hour; a failed lookup is left out). */
    private suspend fun ensureConditions(r: RouteData) {
        val key = departMillis() / 3_600_000L
        if (r.conditionsKey == key) return
        r.conditionsKey = key
        r.conditions = try { kotlinx.coroutines.withTimeoutOrNull(10_000L) { ConditionsApi.load(r, departMillis()) } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { null }
    }

    private suspend fun solveHere(r: RouteData, o: Options, m: Consumption.Model): Pair<Plan, List<FerryInfo>> {
        val (implicit, prefer) = viaPlan()
        return solve(r, o, m, chargers, edits + implicit, excluded, prefer, planVias, planStays)
    }

    private suspend fun compute() {
        val r = route ?: return
        val (o, m) = context()
        var res = solveHere(r, o, m)
        var changed = false
        if (!res.first.ok && corridorFor !== r) {
            // The car can't make it in one go: look up the fast chargers along the way (once per route).
            busy = "Finding chargers along the route…"
            corridorRaw = if (prefetchFor === r && prefetch != null) prefetch!!.await().getOrThrow() else PlanApi.chargers(r, nobilKey())
            corridorFor = r; changed = true
        }
        // Chargers right by every stop on the way (best effort): fewer separate stops, and a place to charge during a stay.
        val key = planVias.joinToString("|") { "${it.lat},${it.lon}" }
        if (key != viaRawKey) {
            viaRaw = if (planVias.isEmpty()) emptyList() else kotlinx.coroutines.coroutineScope {
                planVias.map { v -> async { try { PlanApi.chargersNear(v.lat, v.lon, nobilKey()) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() } } }.awaitAll().flatten()
            }
            viaRawKey = key; changed = true
        }
        if (changed) {
            chargers = withContext(Dispatchers.Default) { place(corridorRaw + viaRaw, r) }
            res = solveHere(r, o, m)
        }
        plan = res.first; ferryInfo = res.second
        alts = alts.mapIndexed { i, a -> if (i == altIndex) AltRoute(a.route, res.first, if (corridorFor === r) corridorRaw else null) else a }
    }

    /** Works out the other routes in the background, so they can be compared with the chosen one. */
    private fun startAlts() {
        altJob?.cancel()
        if (alts.size < 2) return
        altJob = scope.launch {
            for (i in alts.indices) {
                val a = alts[i]
                if (i == altIndex || a.plan != null) continue
                try {
                    val (o, m) = context()
                    val raw = a.raw ?: kotlinx.coroutines.withTimeoutOrNull(25_000L) { PlanApi.chargers(a.route, nobilKey()) }
                    val ch = withContext(Dispatchers.Default) { place(raw.orEmpty(), a.route) }
                    val res = solve(a.route, o, m, ch, emptyList(), emptySet(), emptySet(), emptyList(), emptyList())
                    alts = alts.mapIndexed { k, x -> if (k == i) AltRoute(a.route, res.first, raw) else x }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { }
            }
        }
    }

    /** The other routes' plans are out of date after a change to the numbers. */
    private fun staleAlts() { alts = alts.mapIndexed { i, a -> if (i == altIndex) a else AltRoute(a.route, null, a.raw) } }

    /** Switches to another of the routes found. */
    fun selectRoute(i: Int) {
        if (i == altIndex || i !in alts.indices) return
        val a = alts[i]
        planJob?.cancel(); altJob?.cancel()
        altIndex = i; route = a.route; edits = emptyList(); excluded = emptySet()
        corridorRaw = a.raw ?: emptyList(); corridorFor = if (a.raw != null) a.route else null
        viaRawKey = ""; viaRaw = emptyList(); ferryInfo = emptyList()
        prefetch?.cancel(); prefetch = null; prefetchFor = null
        chargers = place(corridorRaw, a.route)
        planJob = scope.launch {
            error = null
            try { compute(); startAlts() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                error = "Couldn't find chargers — ${Http.describe(e)}"
            } finally { busy = null }
        }
    }

    /** Moves the start time to the next or the previous quarter of an hour (back to "now" at the earliest). */
    fun shiftDepart(dir: Int) {
        val now = System.currentTimeMillis()
        val base = departAt?.takeIf { it > now } ?: now
        val q = 15 * 60_000L
        val t = if (dir > 0) (base / q + 1) * q else ((base - 1) / q) * q
        departAt = if (t <= now + 60_000L) null else t
        staleAlts(); recompute()
    }

    /** Puts chargers on the route, drops doubles and far-off ones, and looks up prices. */
    private fun place(raw: List<RawCharger>, r: RouteData): List<PlanCharger> {
        val out = ArrayList<PlanCharger>()
        for (c in raw.sortedByDescending { it.kw * 100 + it.points }) {
            if (out.any { Geo.km(it.lat, it.lon, c.lat, c.lon) < 0.15 }) continue
            val (idx, off) = Geo.project(r, c.lat, c.lon)
            if (off > 2.5) continue
            val price = prices.find(priceCountry, c.name + " " + c.operator, c.chargeTag)
            out.add(PlanCharger(c.id, c.name, c.operator, c.lat, c.lon, c.kw, c.points, idx, r.km[idx], off, price?.first, price?.second.orEmpty(), c.free))
        }
        return out.sortedBy { it.km }
    }

    // ---------------- changes to the plan ----------------

    fun setStart(pct: Int?) { startPct = pct?.coerceIn(1, 100); staleAlts(); recompute() }
    fun setMin(pct: Int) { minPct = pct.coerceIn(5, 50); app.settings.setRaw("planMin", minPct.toString()); staleAlts(); recompute() }
    fun setTarget(pct: Int) { targetPct = pct.coerceIn(50, 100); app.settings.setRaw("planTarget", targetPct.toString()); staleAlts(); recompute() }

    private fun put(e: StopEdit) {
        val old = edits.firstOrNull { it.chargerId == e.chargerId }
        // Keep what was already set for this stop (its own "charge to" and its break go together).
        edits = edits.filterNot { it.chargerId == e.chargerId } + StopEdit(e.chargerId, e.targetPct ?: old?.targetPct, e.minutes ?: old?.minutes)
        recompute()
    }
    /** Charge to this much at a stop. */
    fun chargeTo(id: String, pct: Int) = put(StopEdit(id, targetPct = pct.coerceIn(5, 100)))
    /** Stay this long at a stop. */
    fun stayFor(id: String, minutes: Int) = put(StopEdit(id, minutes = minutes.coerceIn(5, 180)))
    /** Charge at this charger for as long as the stay lasts (the tip under a via stop). */
    fun chargeDuringStay(id: String, minutes: Int) { excluded = excluded - id; put(StopEdit(id, minutes = minutes.coerceIn(5, 180))) }
    /** Stop at a charger the user picked. */
    fun addStop(id: String) { excluded = excluded - id; put(StopEdit(id)) }
    /** Don't stop here; another charger is found if one is needed. */
    fun removeStop(id: String) { edits = edits.filterNot { it.chargerId == id }; excluded = excluded + id; recompute() }
    /** Stop at another charger instead of this one. */
    fun swapStop(oldId: String, newId: String) {
        val old = edits.firstOrNull { it.chargerId == oldId }
        edits = edits.filterNot { it.chargerId == oldId || it.chargerId == newId } + StopEdit(newId, old?.targetPct, old?.minutes)
        excluded = excluded + oldId - newId
        recompute()
    }
    val changed: Boolean get() = edits.isNotEmpty() || excluded.isNotEmpty()
    fun resetStops() { edits = emptyList(); excluded = emptySet(); recompute() }

    fun addVia() { if (vias.size < 3) { vias = vias + null; stays = stays.take(vias.size - 1).let { it + List(vias.size - it.size) { 0 } } } }
    fun setVia(i: Int, place: Place?) { if (i in vias.indices) vias = vias.toMutableList().also { it[i] = place } }
    fun removeVia(i: Int) {
        if (i !in vias.indices) return
        vias = vias.filterIndexed { j, _ -> j != i }
        stays = stays.filterIndexed { j, _ -> j != i }
    }

    /** How long to stay at a via stop. A plan that is showing is worked out again at once (no new route needed). */
    fun setStay(i: Int, minutes: Int) {
        if (i !in vias.indices) return
        val list = stays.toMutableList(); while (list.size < vias.size) list.add(0)
        list[i] = minutes.coerceIn(0, 480); stays = list
        val shown = vias.indices.filter { vias[it] != null }
        if (route != null && shown.size == planVias.size) { planStays = shown.map { stays.getOrElse(it) { 0 } }; recompute() }
    }

    fun swapEnds() {
        vias = vias.reversed(); stays = stays.reversed()
        val a = from; val b = to
        if (b == null) return
        to = a ?: origin()
        from = b
    }

    /** Closes the planner and takes the route off the map. */
    fun close() {
        planJob?.cancel(); searchJob?.cancel(); altJob?.cancel(); alts = emptyList(); altIndex = 0; ferryInfo = emptyList()
        open = false; editing = true; busy = null; error = null
        prefetch?.cancel(); prefetch = null; prefetchFor = null
        route = null; plan = null; chargers = emptyList(); corridorFor = null; corridorRaw = emptyList(); viaRawKey = ""; viaRaw = emptyList(); edits = emptyList(); excluded = emptySet()
        results = emptyList()
    }
}
