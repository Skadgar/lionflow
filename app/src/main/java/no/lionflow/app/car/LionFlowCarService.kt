package no.lionflow.app.car

import no.lionflow.app.ui.l10n.tr

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import no.lionflow.app.R
import no.lionflow.app.model.ChargePower
import no.lionflow.app.ui.fmtDistance
import no.lionflow.app.ui.fmtTemp

/**
 * LionFlow in Android Auto: a simple overview of the car (battery, charging power, range, climate,
 * doors, service, trips) with a few remote actions. No map.
 */
class LionFlowCarService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        // Only Android Auto / Google's car hosts may connect (or anything, in a debug build).
        return if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()
    }

    override fun onCreateSession(sessionInfo: androidx.car.app.SessionInfo): Session =
        if (sessionInfo.displayType == androidx.car.app.SessionInfo.DISPLAY_TYPE_CLUSTER) ClusterSession() else MainSession()

    @Deprecated("Older hosts")
    override fun onCreateSession(): Session = MainSession()
}

private fun CarContext.icon(res: Int, tint: CarColor? = null): CarIcon =
    CarIcon.Builder(IconCompat.createWithResource(this, res)).apply { tint?.let { setTint(it) } }.build()

private fun CarContext.listLimit(): Int = try {
    getCarService(androidx.car.app.constraints.ConstraintManager::class.java)
        .getContentLimit(androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
} catch (_: Exception) { 6 }

class MainSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        val guard = ParkGuard(carContext)
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onCreate(owner: androidx.lifecycle.LifecycleOwner) = guard.start()
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) = guard.stop()
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CarHub.notices.collect { m -> CarToast.makeText(carContext, tr(m), CarToast.LENGTH_LONG).show() }
            }
        }
        CarHub.refresh(carContext)
        CarHub.loadTrips(carContext)
        offerDriverDisplay()
        return OverviewScreen(carContext, guard)
    }

    /** Asks the car to give LionFlow's map to the driver display (the cluster behind the wheel). */
    private fun offerDriverDisplay() {
        val app = CarHub.app(carContext)
        if (!app.settings.value.carCluster || !app.api.isSignedIn) return
        try {
            val nav = carContext.getCarService(androidx.car.app.navigation.NavigationManager::class.java)
            nav.setNavigationManagerCallback(object : androidx.car.app.navigation.NavigationManagerCallback {
                override fun onStopNavigation() {}   // another app (e.g. Google Maps) took the driver display
                override fun onAutoDriveEnabled() {}
            })
            nav.navigationStarted()
            lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) { try { nav.navigationEnded() } catch (_: Exception) { } }
            })
        } catch (e: Exception) {
            android.util.Log.w("LionFlowCar", "driver display not available", e)
        }
    }
}

/** The driver display: just the map with the 3D car. The cluster has no touch input. */
class ClusterSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        CarMapRenderer(carContext, this)
        if (CarMapRenderer.hasLocation(carContext)) {
            CarLocationService.start(carContext)
            lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) { CarLocationService.stop(carContext) }
            })
        }
        return object : Screen(carContext) {
            override fun onGetTemplate(): Template = androidx.car.app.navigation.model.NavigationTemplate.Builder()
                .setActionStrip(ActionStrip.Builder().addAction(Action.Builder().setTitle("LionFlow").setOnClickListener { }.build()).build())
                .build()
        }
    }
}

/** Base screen that redraws whenever the shared car state changes. */
abstract class LiveScreen(ctx: CarContext) : Screen(ctx) {
    abstract fun build(): Template

    /** Never let a bad template take the whole app down: show what went wrong instead. */
    final override fun onGetTemplate(): Template = try { build() } catch (e: Exception) {
        android.util.Log.e("LionFlowCar", "template failed", e)
        try { CarHub.app(carContext).remote.addLog("Android Auto: ${e.javaClass.simpleName}: ${e.message}") } catch (_: Exception) {}
        MessageTemplate.Builder(tr("Something went wrong showing this screen.") + "\n" + (e.message ?: e.javaClass.simpleName).take(200))
            .setTitle(tr("LionFlow")).setHeaderAction(Action.APP_ICON).build()
    }

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(CarHub.status, CarHub.message, CarHub.busy, CarHub.chargers) { _, _, _, _ -> Unit }
                    .collect { invalidate() }
            }
        }
    }
}

// ---------------------------------------------------------------- overview

class OverviewScreen(ctx: CarContext, private val guard: ParkGuard) : LiveScreen(ctx) {
    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { CarHub.trips.collect { invalidate() } }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { guard.parked.collect { invalidate() } }
        }
    }

    override fun build(): Template {
        val app = CarHub.app(carContext)
        if (!app.api.isSignedIn) {
            return MessageTemplate.Builder(tr("Open LionFlow on your phone and sign in with your Peugeot account."))
                .setTitle(tr("LionFlow")).setHeaderAction(Action.APP_ICON).build()
        }
        val s = app.settings.value
        val st = CarHub.status.value
        // Only one button in the strip may have text; the rest must be icons.
        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setIcon(carContext.icon(R.drawable.ic_car_refresh)).setOnClickListener {
                CarHub.refresh(carContext, force = true); CarHub.loadTrips(carContext, force = true); CarHub.say("Updating…")
            }.build())
        val b = GridTemplate.Builder().setTitle(tr(CarHub.message.value ?: s.carName)).setHeaderAction(Action.APP_ICON).setActionStrip(strip.build())
        if (st == null) return b.setLoading(true).build()

        val items = mutableListOf<GridItem>()
        fun tile(title: String, text: String?, icon: Int, tint: CarColor? = null, onClick: (() -> Unit)? = null) {
            items += GridItem.Builder().setTitle(tr(title).ifBlank { "—" })
                .apply { text?.takeIf { it.isNotBlank() }?.let { setText(tr(it)) } }
                .setImage(carContext.icon(icon, tint), GridItem.IMAGE_TYPE_ICON)
                .apply { onClick?.let { setOnClickListener(it) } }
                .build()
        }

        tile("${st.batteryPct?.toInt() ?: "—"} %", "≈ ${fmtDistance(st.rangeKm, s)}" + if (st.rangeEstimated) " (est.)" else "", R.drawable.ic_car_battery)
        tile(if (st.isCharging) (ChargePower.label(st) ?: "Charging") else if (st.plugged) "Plugged in" else "Not plugged in",
            if (st.isCharging) st.remainingMinutes?.let { m -> "${m / 60} h ${m % 60} min left" } ?: "Charging" else CarHub.chargingLine(st),
            R.drawable.ic_car_bolt, if (st.isCharging) CarColor.GREEN else null)
        val trips = CarHub.trips.value
        val week = trips?.filter { (it.startMillis ?: 0L) >= System.currentTimeMillis() - 7 * 86_400_000L }
        val km = week?.sumOf { it.distanceKm } ?: 0.0
        tile(if (trips == null) "Trips" else "7 days: ${fmtDistance(km, s)}", if (trips == null) "Loading…" else "${week?.size ?: 0} trips",
            R.drawable.ic_car_route) { screenManager.push(TripsScreen(carContext)) }
        tile("Chargers", "Nearest first", R.drawable.ic_car_charger) { screenManager.push(ChargersScreen(carContext)) }
        tile(if (st.preconditioningOn) "Pre-heating on" else "Climate",
            st.outsideTempC?.let { "${fmtTemp(it, s)} outside" },
            R.drawable.ic_car_climate, if (st.preconditioningOn) CarColor.GREEN else null)
        tile(CarHub.doorsLine(st), CarHub.updatedLine(), R.drawable.ic_car_lock)
        if (s.carVideo) {
            guard.update()
            val parked = guard.parked.value
            tile("YouTube", if (parked) "Tap to watch" else "Only while parked", R.drawable.ic_car_play) {
                guard.update()
                if (guard.parked.value) screenManager.push(YouTubeScreen(carContext, guard))
                else CarToast.makeText(carContext, tr(guard.reason), CarToast.LENGTH_LONG).show()
            }
        }
        tile(st.odometerKm?.let { fmtDistance(it, s) } ?: "Odometer", serviceLine(st, s), R.drawable.ic_car_refresh)

        val max = try {
            carContext.getCarService(androidx.car.app.constraints.ConstraintManager::class.java)
                .getContentLimit(androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_GRID)
        } catch (_: Exception) { 6 }.coerceAtLeast(6)
        val list = ItemList.Builder()
        items.take(max).forEach { list.addItem(it) }
        return b.setSingleList(list.build()).build()
    }

    private fun serviceLine(st: no.lionflow.app.model.VehicleStatus, s: no.lionflow.app.data.AppSettings): String {
        val parts = mutableListOf<String>()
        st.kmToService?.let { parts += "service in ${fmtDistance(it, s)}" }
        st.daysToService?.let { d -> parts += if (d >= 60) "${(d / 30).toInt()} months" else "${d.toInt()} days" }
        return if (parts.isEmpty()) "Next service unknown" else "Next " + parts.joinToString(" or ")
    }

    private fun consumption(kwhPer100Km: Double, s: no.lionflow.app.data.AppSettings) =
        if (s.distanceUnit == "mi") String.format(java.util.Locale.US, "%.1f mi/kWh", 100 / kwhPer100Km / 1.609344)
        else String.format(java.util.Locale.US, "%.1f kWh/100 km", kwhPer100Km)
}

// ---------------------------------------------------------------- chargers (list only)

class ChargersScreen(ctx: CarContext) : LiveScreen(ctx) {
    init {
        CarHub.status.value?.let { st -> st.lat?.let { la -> st.lon?.let { lo -> CarHub.loadChargers(ctx, la, lo) } } }
    }

    override fun build(): Template {
        val s = CarHub.app(carContext).settings.value
        val all = CarHub.chargers.value
        val b = ListTemplate.Builder().setTitle(tr("Chargers near the car")).setHeaderAction(Action.BACK)
        if (all.isEmpty()) return b.setLoading(true).build()
        val list = ItemList.Builder()
        all.take(carContext.listLimit()).forEach { ch ->
            val bits = mutableListOf<String>()
            ch.distanceM?.let { m -> bits += if (s.distanceUnit != "mi" && m < 1000) "$m m" else if (s.distanceUnit != "mi" && m < 10_000) String.format(java.util.Locale.US, "%.1f km", m / 1000.0) else fmtDistance(m / 1000.0, s) }
            if (ch.maxKw > 0) bits += "${ch.maxKw.toInt()} kW"
            if (ch.points > 0) bits += "${ch.points} points"
            list.addItem(Row.Builder().setTitle(tr(ch.name)).addText(tr(bits.joinToString(" · ").ifBlank { ch.address }))
                .setImage(carContext.icon(R.drawable.ic_car_charger, if (ch.maxKw >= 50) CarColor.GREEN else null)).build())
        }
        return b.setSingleList(list.build()).build()
    }
}

// ---------------------------------------------------------------- driving history

class TripsScreen(ctx: CarContext) : Screen(ctx) {
    init {
        CarHub.loadTrips(ctx)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { CarHub.trips.collect { invalidate() } }
        }
    }

    override fun onGetTemplate(): Template {
        val s = CarHub.app(carContext).settings.value
        val trips = CarHub.trips.value
        val b = ListTemplate.Builder().setTitle(tr("Driving history")).setHeaderAction(Action.BACK)
            .setActionStrip(ActionStrip.Builder().addAction(Action.Builder().setTitle(tr("Refresh"))
                .setOnClickListener { CarHub.loadTrips(carContext, force = true) }.build()).build())
        if (trips == null) return b.setLoading(true).build()
        val list = ItemList.Builder()
        if (trips.isEmpty()) {
            list.setNoItemsMessage(tr("No trips in the last 30 days"))
            return b.setSingleList(list.build()).build()
        }
        val max = try {
            carContext.getCarService(androidx.car.app.constraints.ConstraintManager::class.java)
                .getContentLimit(androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        } catch (_: Exception) { 6 }

        // Summary of the last 7 days first.
        val weekAgo = System.currentTimeMillis() - 7 * 86_400_000L
        val week = trips.filter { (it.startMillis ?: 0L) >= weekAgo }
        val km = week.sumOf { it.distanceKm }
        val kwh = week.mapNotNull { it.kwh }.sum()
        val avg = if (km > 1 && kwh > 0) kwh / km * 100 else null
        list.addItem(Row.Builder()
            .setTitle(tr("Last 7 days: ${fmtDistance(km, s)} · ${week.size} trips"))
            .addText(tr(avg?.let { consumption(it, s) } ?: "Consumption not reported"))
            .setImage(carContext.icon(R.drawable.ic_car_route)).build())

        val day = java.text.SimpleDateFormat("EEE d MMM", no.lionflow.app.ui.l10n.L10n.locale)
        val time = java.text.SimpleDateFormat(no.lionflow.app.ui.l10n.TimeFmt.pattern("HH:mm"), no.lionflow.app.ui.l10n.L10n.locale)
        val today = day.format(java.util.Date())
        trips.take((max - 1).coerceAtLeast(1)).forEach { t ->
            val start = t.startMillis?.let { java.util.Date(it) }
            val whenTxt = start?.let { d -> (if (day.format(d) == today) "Today" else day.format(d)) + " " + time.format(d) } ?: "Trip"
            val bits = mutableListOf<String>()
            t.durationSec?.takeIf { it > 0 }?.let { sec -> val m = (sec / 60).toInt(); bits += if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min" }
            t.avgKwhPer100?.let { bits += consumption(it, s) }
            t.avgSpeedKmh?.takeIf { it > 0 }?.let { v -> bits += if (s.distanceUnit == "mi") "${Math.round(v * 0.621371)} mph avg" else "${Math.round(v)} km/h avg" }
            list.addItem(Row.Builder().setTitle(tr("$whenTxt · ${fmtDistance(t.distanceKm, s)}"))
                .addText(tr(bits.joinToString(" · ").ifBlank { "No details" })).build())
        }
        return b.setSingleList(list.build()).build()
    }

    private fun consumption(kwhPer100Km: Double, s: no.lionflow.app.data.AppSettings) =
        if (s.distanceUnit == "mi") String.format(java.util.Locale.US, "%.1f mi/kWh", 100 / kwhPer100Km / 1.609344)
        else String.format(java.util.Locale.US, "%.1f kWh/100 km", kwhPer100Km)
}

