package no.lionflow.app.plan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
    private var chargersFor: RouteData? = null
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
                val r = PlanApi.route(a, b, via)
                edits = emptyList(); excluded = emptySet(); chargers = emptyList(); chargersFor = null
                route = r; planFrom = a; planTo = b; planVias = via; planStays = stay
                prefetch?.cancel(); prefetch = null; prefetchFor = null
                if (r.totalKm > 120) { prefetchFor = r; prefetch = scope.async(Dispatchers.IO) { runCatching { PlanApi.chargers(r, nobilKey()) } } }
                priceCountry = if (a.country.isNotBlank() && a.country.equals(b.country, ignoreCase = true)) a.country else ""
                remember(b); from?.let { remember(it) }; via.forEach { remember(it) }
                compute()
                editing = false
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
            try { compute() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                error = "Couldn't find chargers — ${Http.describe(e)}"
            } finally { busy = null }
        }
    }

    private suspend fun compute() {
        val r = route ?: return
        val kwh = batteryKwh()
        val m = Consumption.fit(trips().mapNotNull { t ->
            val used = t.kwh ?: t.avgKwhPer100?.let { it * t.distanceKm / 100 }
            val v = t.avgSpeedKmh
            if (used != null && v != null) Consumption.TripSample(t.distanceKm, used, v) else null
        }, kwh)
        model = m
        val o = Options(startNow().toDouble(), minPct, targetPct, kwh, if (kwh >= 68) 160.0 else 100.0)
        val energy = withContext(Dispatchers.Default) { r.energy(m.factor) }
        var p = withContext(Dispatchers.Default) { TripPlanner.plan(r, energy, chargers, o, edits, excluded, m) }
        if ((!p.ok || planStays.any { it > 0 }) && chargersFor !== r) {
            // The car can't make it in one go, or there is a stay to suggest a charger for: look up the fast chargers along the way (once per route).
            busy = "Finding chargers along the route…"
            val raw = if (prefetchFor === r && prefetch != null) prefetch!!.await().getOrThrow() else PlanApi.chargers(r, nobilKey())
            chargers = withContext(Dispatchers.Default) { place(raw, r) }
            chargersFor = r
            p = withContext(Dispatchers.Default) { TripPlanner.plan(r, energy, chargers, o, edits, excluded, m) }
        }
        plan = p.copy(totalMin = p.totalMin + planStays.sum())
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

    fun setStart(pct: Int?) { startPct = pct?.coerceIn(1, 100); recompute() }
    fun setMin(pct: Int) { minPct = pct.coerceIn(5, 50); app.settings.setRaw("planMin", minPct.toString()); recompute() }
    fun setTarget(pct: Int) { targetPct = pct.coerceIn(50, 100); app.settings.setRaw("planTarget", targetPct.toString()); recompute() }

    private fun put(e: StopEdit) { edits = edits.filterNot { it.chargerId == e.chargerId } + e; recompute() }
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
        planJob?.cancel(); searchJob?.cancel()
        open = false; editing = true; busy = null; error = null
        prefetch?.cancel(); prefetch = null; prefetchFor = null
        route = null; plan = null; chargers = emptyList(); chargersFor = null; edits = emptyList(); excluded = emptySet()
        results = emptyList()
    }
}
