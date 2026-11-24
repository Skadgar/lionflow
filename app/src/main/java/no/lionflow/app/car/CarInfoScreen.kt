package no.lionflow.app.car

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.lionflow.app.R
import no.lionflow.app.model.VehicleStatus
import no.lionflow.app.ui.fmtDistance
import no.lionflow.app.ui.fmtTemp
import no.lionflow.app.ui.l10n.tr
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Everything the car reports about itself, as a grid of small cards: battery and energy, health, charging,
 * the 12 V battery, odometer and service, temperature, doors, ignition and speed, privacy mode.
 * (The car's connected services share these values; live engine data from the OBD port is not available to apps.)
 */
class CarInfoScreen(ctx: CarContext) : Screen(ctx), SurfaceCallback, DefaultLifecycleObserver {
    private val main = Handler(Looper.getMainLooper())
    private var surface: SurfaceContainer? = null
    private var visible: Rect? = null

    init {
        lifecycle.addObserver(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { CarHub.status.collect { main.post { draw() } } }
                launch { while (true) { CarHub.refresh(carContext, force = true); delay(30_000L) } }
            }
        }
    }

    override fun onCreate(owner: LifecycleOwner) { carContext.getCarService(AppManager::class.java).setSurfaceCallback(this) }
    override fun onStart(owner: LifecycleOwner) { carContext.getCarService(AppManager::class.java).setSurfaceCallback(this); main.post { draw() } }
    override fun onSurfaceAvailable(c: SurfaceContainer) { surface = c; main.post { draw() } }
    override fun onSurfaceDestroyed(c: SurfaceContainer) { surface = null }
    override fun onVisibleAreaChanged(visibleArea: Rect) { visible = Rect(visibleArea); main.post { draw() } }
    override fun onStableAreaChanged(stableArea: Rect) { if (visible == null) { visible = Rect(stableArea); main.post { draw() } } }

    override fun onGetTemplate(): Template = NavigationTemplate.Builder().setActionStrip(ActionStrip.Builder()
        .addAction(Action.Builder().setIcon(carContext.icon(R.drawable.ic_car_back)).setOnClickListener { screenManager.pop() }.build())
        .addAction(Action.Builder().setIcon(carContext.icon(R.drawable.ic_car_refresh)).setOnClickListener { CarHub.refresh(carContext, force = true); CarHub.say("Updating…") }.build())
        .build()).build()

    private fun path(o: JSONObject?, vararg keys: String): Any? {
        var cur: Any? = o
        for (k in keys) cur = (cur as? JSONObject)?.opt(k) ?: return null
        return cur
    }
    private fun num(o: JSONObject?, vararg keys: String): Double? = (path(o, *keys) as? Number)?.toDouble()
    private fun str(o: JSONObject?, vararg keys: String): String? = (path(o, *keys) as? String)?.takeIf { it.isNotBlank() }

    /** The cells: label, value, and whether it is a "good" (green) value. */
    private fun cells(st: VehicleStatus): List<Triple<String, String, Int>> {
        val s = CarHub.app(carContext).settings.value
        val pal = CarTheme.p
        val raw = st.raw
        val out = ArrayList<Triple<String, String, Int>>()
        fun add(label: String, value: String?, color: Int = pal.text) { if (!value.isNullOrBlank()) out += Triple(tr(label), value, color) }
        val fuelOnly = st.energyType == "Fuel"
        if (!fuelOnly) {
            add("Battery", st.batteryPct?.let { "${it.roundToInt()} %" }, pal.accentText)
            add("Range", st.rangeKm?.let { fmtDistance(it, s) })
            add("Energy in the battery", if (st.residualKwh != null && st.capacityKwh != null) "%.1f / %.1f kWh".format(st.residualKwh, st.capacityKwh) else st.capacityKwh?.let { "%.1f kWh".format(it) })
            add("Battery health", st.batteryHealthPct?.let { "${it.roundToInt()} %" })
        }
        if (st.fuelPct != null) { add("Fuel", "${st.fuelPct.roundToInt()} %", pal.accentText); add("Fuel range", st.fuelRangeKm?.let { fmtDistance(it, s) }) }
        if (!fuelOnly) {
            add("Charging", tr(when { st.isCharging -> "Charging"; st.plugged -> "Plugged in"; else -> "Not plugged in" }), if (st.isCharging || st.plugged) pal.accentText else pal.text)
            if (st.isCharging) {
                add("Charge speed", st.chargingRateKmh?.let { "${it.roundToInt()} km/h" })
                add("Full in", st.remainingMinutes?.let { if (it >= 60) "${it / 60} h ${it % 60} min" else "$it min" })
                add("Charging mode", st.chargingMode?.let { tr(it) })
            }
            if (st.nextDelayedHour != null) add("Scheduled charging", "%02d:%02d".format(st.nextDelayedHour, st.nextDelayedMinute ?: 0))
        }
        // The 12 V battery, when the car shares it.
        val v12 = num(raw, "battery", "voltage")
        add("12 V battery", v12?.let { if (it > 30) "${it.roundToInt()} %" else "%.1f V".format(it) })
        add("Odometer", st.odometerKm?.let { fmtDistance(it, s) })
        add("Next service", listOfNotNull(st.kmToService?.let { fmtDistance(it, s) }, st.daysToService?.let { tr("{0} days").replace("{0}", "${it.roundToInt()}") }).joinToString(" · ").ifEmpty { null })
        add("Outside", st.outsideTempC?.let { fmtTemp(it, s) })
        add("Pre-heating", tr(if (st.preconditioningOn) "On" else "Off"), if (st.preconditioningOn) pal.orange else pal.text)
        add("Doors", tr(CarHub.doorsLine(st)), if (st.openDoors.isNotEmpty() || st.locked == false) pal.orange else pal.text)
        add("Ignition", st.ignition?.let { tr(it) })
        val speed = num(raw, "kinetic", "speed")
        add("Speed", if (st.moving || (speed ?: 0.0) > 0.5) "${(speed ?: 0.0).roundToInt()} km/h" else tr("Parked"))
        add("Privacy mode", str(raw, "privacy", "state")?.let { tr(it) })
        add("Last report", tr(CarHub.updatedLine()))
        return out
    }

    private fun draw() {
        val sc = surface ?: return
        val sf = sc.surface ?: return
        if (!sf.isValid) return
        val c = try { sf.lockHardwareCanvas() } catch (_: Exception) { try { sf.lockCanvas(null) } catch (_: Exception) { return } }
        try { paint(c, sc) } catch (e: Exception) { android.util.Log.e("LionFlowCar", "car info draw failed", e) }
        finally { try { sf.unlockCanvasAndPost(c) } catch (_: Exception) { } }
    }

    private fun paint(c: Canvas, sc: SurfaceContainer) {
        val pal = CarTheme.refresh(carContext)
        val w = sc.width.toFloat(); val h = sc.height.toFloat()
        val v = visible?.let { RectF(it) }?.takeIf { it.width() > 100 && it.height() > 100 } ?: RectF(0f, 0f, w, h)
        val ink = Ink(maxOf(sc.dpi / 160f * 1.1f, v.height() / 420f), pal)
        fun dp(x: Float) = ink.dp(x)
        c.drawColor(pal.bg)
        val area = RectF(v.left + dp(14f), v.top + dp(12f), v.right - dp(14f), v.bottom - dp(12f))
        val title = ink.text(22f, pal.text, ink.bold)
        c.drawText(tr("Car Info"), area.left + dp(2f), area.top + dp(26f), title)
        val st = CarHub.status.value
        if (st == null) { c.drawText(tr("Getting the car's status…"), area.centerX(), area.centerY(), ink.text(15f, pal.muted, ink.medium, Paint.Align.CENTER)); return }
        val list = cells(st)
        val grid = RectF(area.left, area.top + dp(44f), area.right, area.bottom)
        val gap = dp(8f)
        val cols = ((grid.width() + gap) / (dp(170f) + gap)).toInt().coerceIn(2, 5)
        val rows = (list.size + cols - 1) / cols
        val cw = (grid.width() - gap * (cols - 1)) / cols
        val ch = ((grid.height() - gap * (rows - 1)) / rows).coerceIn(dp(40f), dp(76f))
        // Smaller text when there are many rows on a low screen.
        val f = (ch / dp(64f)).coerceIn(0.72f, 1f)
        list.forEachIndexed { i, (label, value, color) ->
            val col = i % cols; val row = i / cols
            val r = RectF(grid.left + col * (cw + gap), grid.top + row * (ch + gap), grid.left + col * (cw + gap) + cw, grid.top + row * (ch + gap) + ch)
            if (r.bottom > grid.bottom + dp(2f)) return@forEachIndexed
            ink.card(c, r, dp(14f))
            val lp = ink.text(12.5f * f, pal.muted, ink.medium)
            c.drawText(ink.ellipsize(label, lp, cw - dp(20f)), r.left + dp(12f), r.top + r.height() * 0.40f, lp)
            val vp = ink.text(17f * f, color, ink.bold)
            c.drawText(ink.ellipsize(value, vp, cw - dp(20f)), r.left + dp(12f), r.top + r.height() * 0.40f + dp(22f * f), vp)
        }
    }
}
