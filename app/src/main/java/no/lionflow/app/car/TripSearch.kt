package no.lionflow.app.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.lionflow.app.R
import no.lionflow.app.plan.PlanApi
import no.lionflow.app.plan.Place
import no.lionflow.app.plan.Planner
import no.lionflow.app.plan.SavedRoutes
import no.lionflow.app.ui.fmtDistance
import no.lionflow.app.ui.l10n.L10n
import no.lionflow.app.ui.l10n.TimeFmt
import no.lionflow.app.ui.l10n.tr
import kotlin.math.roundToInt

/**
 * Search for a destination in the car, by the on-screen keyboard or the keyboard's microphone (as in Google Maps).
 * Picking a place plans the trip from where the car is, with its battery now.
 */
class TripSearchScreen(ctx: CarContext) : Screen(ctx) {
    private var query = ""
    private var results: List<Place> = emptyList()
    private var loading = false
    private var job: Job? = null

    private fun search(q: String, now: Boolean) {
        query = q
        job?.cancel()
        if (q.trim().length < 2) { results = emptyList(); loading = false; invalidate(); return }
        job = lifecycleScope.launch {
            if (!now) delay(400)
            loading = true; invalidate()
            val st = CarHub.status.value
            results = try { PlanApi.search(q.trim(), st?.lat, st?.lon, L10n.lang) } catch (_: Exception) { emptyList() }
            loading = false; invalidate()
        }
    }

    /** Places starred in the trip planner on the phone. */
    private fun savedPlaces(): List<Place> = try {
        val a = org.json.JSONArray(CarHub.app(carContext).settings.raw("planSaved").ifBlank { "[]" })
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o -> Place(o.optString("n"), o.optString("d"), o.getDouble("la"), o.getDouble("lo"), o.optString("c")) } }
    } catch (_: Exception) { emptyList() }

    private var listening = false

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        val typing = query.trim().length >= 2
        if (results.isEmpty() && typing && !loading) list.setNoItemsMessage(tr("No places found"))
        // With nothing typed: home and the known chargers (from charging costs on the phone), then the starred places.
        val shown: List<Pair<Place, Int>> = if (typing) results.map { it to R.drawable.ic_car_route } else {
            val cfg = no.lionflow.app.model.ChargeLog.places.value
            val chargePlaces = (listOfNotNull(cfg.home?.let { it.copy(name = it.name.ifBlank { tr("Home") }) }) + cfg.known)
                .filter { it.lat != null && it.lon != null }
                .map { Place(it.name.ifBlank { tr("Known charger") }, it.address, it.lat!!, it.lon!!) to R.drawable.ic_car_charger }
            val starred = savedPlaces().filter { sp -> chargePlaces.none { (cp, _) -> no.lionflow.app.plan.Geo.km(cp.lat, cp.lon, sp.lat, sp.lon) < 0.1 } }
            chargePlaces + starred.map { it to R.drawable.ic_car_star }
        }
        if (!typing && shown.isEmpty()) list.setNoItemsMessage(tr("Star places in the trip planner on your phone to see them here."))
        for ((p, ic) in shown.take(12)) {
            list.addItem(Row.Builder().setTitle(p.name.ifBlank { "—" }).apply { if (p.detail.isNotBlank()) addText(p.detail) }
                .setImage(CarIcon.Builder(carContext.icon(ic)).build())
                .setOnClickListener { screenManager.push(TripPlanScreen(carContext, p)) }.build())
        }
        val b = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) = search(searchText, false)
            override fun onSearchSubmitted(searchText: String) = search(searchText, true)
        }).setHeaderAction(Action.BACK).setSearchHint(tr(if (listening) "Listening…" else "Where to?")).setShowKeyboardByDefault(false)
            .setInitialSearchText(query)
        // Our own microphone button: listens in the language of the country the car is in.
        if (CarVoice.possible(carContext)) b.setActionStrip(androidx.car.app.model.ActionStrip.Builder().addAction(
            Action.Builder().setIcon(carContext.icon(R.drawable.ic_car_mic)).setOnClickListener {
                if (listening) return@setOnClickListener
                listening = true; invalidate()
                CarVoice.listen(carContext) { text ->
                    listening = false
                    if (text != null) search(text, true)
                    else { androidx.car.app.CarToast.makeText(carContext, tr("Didn't catch that. Try again, or type."), androidx.car.app.CarToast.LENGTH_LONG).show(); invalidate() }
                }
            }.build()).build())
        return if (loading && results.isEmpty()) b.setLoading(true).build() else b.setItemList(list.build()).build()
    }
}

/** A trip planned in the car to [dest]: time, battery % on arrival and the charging stops, with Navigate and Save trip. */
class TripPlanScreen(ctx: CarContext, private val dest: Place) : Screen(ctx) {
    private val app = CarHub.app(carContext)
    private val planner = Planner(lifecycleScope, app, { CarHub.status.value }, { CarHub.trips.value.orEmpty() }, { null })
    private var lastKey: Any? = null

    init {
        planner.to = dest
        planner.go()
        // The planner's state is watched here: the car screen is redrawn when it changes.
        lifecycleScope.launch {
            while (true) {
                val key = Triple(planner.busy, planner.plan, planner.error)
                if (key != lastKey) { lastKey = key; invalidate() }
                delay(300)
            }
        }
    }

    private fun dur(m: Double): String { val t = m.roundToInt().coerceAtLeast(0); return if (t >= 60) "${t / 60} h ${t % 60} min" else "$t min" }

    override fun onGetTemplate(): Template {
        val s = app.settings.value
        val plan = planner.plan
        planner.error?.let { e ->
            if (plan == null) return MessageTemplate.Builder(tr(e)).setTitle(dest.name).setHeaderAction(Action.BACK)
                .addAction(Action.Builder().setTitle(tr("Try again")).setOnClickListener { planner.go() }.build()).build()
        }
        if (plan == null || planner.busy != null) {
            return PaneTemplate.Builder(Pane.Builder().setLoading(true).build()).setTitle(tr(planner.busy ?: "Planning…")).setHeaderAction(Action.BACK).build()
        }
        val pane = Pane.Builder()
        val arrive = java.text.SimpleDateFormat(TimeFmt.pattern("HH:mm"), L10n.locale).format(java.util.Date(planner.departMillis() + (plan.totalMin * 60_000).toLong()))
        pane.addRow(Row.Builder().setTitle(dur(plan.totalMin) + " · " + fmtDistance(plan.totalKm, s))
            .addText(tr("Arrive {0}").replace("{0}", arrive)).build())
        val start = planner.startNow()
        pane.addRow(Row.Builder().setTitle(tr("About {0} % when you arrive").replace("{0}", "${plan.arrivePct.coerceAtLeast(0.0).roundToInt()}"))
            .addText(tr("From the battery now: {0} %").replace("{0}", "$start")).build())
        val stops = plan.stops.filter { it.kwh > 0.01 }
        if (stops.isEmpty()) pane.addRow(Row.Builder().setTitle(tr("No charging needed")).build())
        else stops.take(2).forEachIndexed { i, st ->
            pane.addRow(Row.Builder().setTitle((if (stops.size > 1) "${i + 1}/${stops.size} · " else "") + st.charger.name)
                .addText(tr("Charge {0} min · {1} % → {2} %").replace("{0}", "${st.minutes}")
                    .replace("{1}", "${st.arrivePct.roundToInt()}").replace("{2}", "${st.leavePct.roundToInt()}")).build())
        }
        pane.addAction(Action.Builder().setTitle(tr("Navigate")).setOnClickListener {
            val here = { lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
            val first = stops.firstOrNull()
            if (first != null) openInGoogleMaps(carContext, first.charger.lat, first.charger.lon, first.charger.name, stillHere = here)
            else openInGoogleMaps(carContext, dest.lat, dest.lon, dest.name, stillHere = here)
        }.build())
        // Opens the trip in the live view (map, battery on the way) without saving it.
        pane.addAction(Action.Builder().setTitle(tr("See trip")).setOnClickListener {
            if (planner.saveTrip(true, keep = false)) planner.lastBuilt?.let { screenManager.push(RouteLiveScreen(carContext, it.id, it)) }
        }.build())
        return PaneTemplate.Builder(pane.build()).setTitle(dest.name).setHeaderAction(Action.BACK).build()
    }
}
