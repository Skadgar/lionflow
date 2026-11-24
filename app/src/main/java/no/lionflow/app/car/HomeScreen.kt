package no.lionflow.app.car

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import no.lionflow.app.R
import no.lionflow.app.plan.SavedRoutes
import no.lionflow.app.ui.fmtDistance
import no.lionflow.app.ui.l10n.tr
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The start screen in the car, drawn by LionFlow itself: a battery gauge and big, clear tiles, in a dark or a
 * light look (the sun / moon button switches). Tiles are tapped on the screen; the grid button opens the plain list
 * for cars without a touch screen.
 */
class HomeScreen(ctx: CarContext, private val guard: ParkGuard) : Screen(ctx), SurfaceCallback, DefaultLifecycleObserver {
    private val main = Handler(Looper.getMainLooper())
    private var surface: SurfaceContainer? = null
    private var visible: Rect? = null
    private class Hit(val r: RectF, val key: String, val go: () -> Unit)
    private var hits: List<Hit> = emptyList()
    private var pressed: String? = null

    init {
        lifecycle.addObserver(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(CarHub.status, CarHub.message, CarHub.busy, CarHub.trips) { _, _, _, _ -> Unit }.collect { main.post { draw() } }
                }
                launch { guard.parked.collect { invalidate() } }
                launch { while (true) { delay(20_000L); main.post { draw() } } }   // keeps "updated … ago" fresh
                launch { while (true) { delay(60_000L); CarHub.refresh(carContext, force = true) } }   // fresh numbers while it shows
            }
        }
    }

    override fun onCreate(owner: LifecycleOwner) { carContext.getCarService(AppManager::class.java).setSurfaceCallback(this) }
    override fun onStart(owner: LifecycleOwner) { carContext.getCarService(AppManager::class.java).setSurfaceCallback(this); main.post { draw() } }
    override fun onDestroy(owner: LifecycleOwner) { try { carContext.getCarService(AppManager::class.java).setSurfaceCallback(null) } catch (_: Exception) { } }

    override fun onSurfaceAvailable(c: SurfaceContainer) { surface = c; main.post { draw() } }
    override fun onSurfaceDestroyed(c: SurfaceContainer) { surface = null }
    override fun onVisibleAreaChanged(visibleArea: Rect) { visible = Rect(visibleArea); main.post { draw() } }
    override fun onStableAreaChanged(stableArea: Rect) { if (visible == null) { visible = Rect(stableArea); main.post { draw() } } }

    override fun onClick(x: Float, y: Float) {
        main.post {
            val h = hits.firstOrNull { it.r.contains(x, y) } ?: return@post
            // Shown pressed for a moment, but the screen opens straight away.
            pressed = h.key; draw(); h.go()
            main.postDelayed({ pressed = null; draw() }, 250)
        }
    }

    private fun refreshAll() { CarHub.refresh(carContext, force = true); CarHub.loadTrips(carContext, force = true); CarHub.say("Updating…") }

    private fun openVideo(site: VideoSite) {
        guard.update()
        if (guard.parked.value) screenManager.push(YouTubeScreen(carContext, guard, site))
        else CarToast.makeText(carContext, tr(guard.reason), CarToast.LENGTH_LONG).show()
    }

    override fun onGetTemplate(): Template {
        val pal = CarTheme.refresh(carContext)
        val s = CarHub.app(carContext).settings.value
        val strip = ActionStrip.Builder()
            // Sun in the dark look (switch to light), moon in the light look.
            .addAction(Action.Builder().setIcon(carContext.icon(if (pal.dark) R.drawable.ic_car_sun else R.drawable.ic_car_moon)).setOnClickListener {
                CarTheme.toggle(carContext); invalidate(); main.post { draw() }
            }.build())
            .addAction(Action.Builder().setIcon(carContext.icon(R.drawable.ic_car_refresh)).setOnClickListener { refreshAll() }.build())
        if (s.carVideo) strip.addAction(Action.Builder().setIcon(carContext.icon(R.drawable.ic_car_play, CarColor.RED)).setOnClickListener { openVideo(VideoSite.YouTube) }.build())
        if (s.carVideo && Where.inNorway(carContext)) strip.addAction(Action.Builder().setIcon(nrkBadge()).setOnClickListener { openVideo(VideoSite.NrkTv) }.build())
        return NavigationTemplate.Builder().setActionStrip(strip.build()).build()
    }

    private fun nrkBadge(): androidx.car.app.model.CarIcon {
        val n = 96
        val b = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFFFFFFFF.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = n * 0.07f
        c.drawCircle(n / 2f, n / 2f, n / 2f - p.strokeWidth, p)
        p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER
        p.typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD); p.textSize = n * 0.30f
        c.drawText("NRK", n / 2f, n / 2f + p.textSize * 0.36f, p)
        return androidx.car.app.model.CarIcon.Builder(androidx.core.graphics.drawable.IconCompat.createWithBitmap(b)).build()
    }

    // ---------------- drawing ----------------

    private fun draw() {
        val sc = surface ?: return
        val sf = sc.surface ?: return
        if (!sf.isValid) return
        val canvas = try { sf.lockHardwareCanvas() } catch (_: Exception) { try { sf.lockCanvas(null) } catch (_: Exception) { return } }
        try { paint(canvas, sc) } catch (e: Exception) {
            android.util.Log.e("LionFlowCar", "home draw failed", e)
        } finally { try { sf.unlockCanvasAndPost(canvas) } catch (_: Exception) { } }
    }

    private class Tile(val key: String, val title: String, val sub: String, val icon: Int, val lit: Boolean, val go: () -> Unit)

    private fun paint(c: Canvas, sc: SurfaceContainer) {
        val pal = CarTheme.refresh(carContext)
        val w = sc.width.toFloat(); val h = sc.height.toFloat()
        // Everything is laid out inside the part of the screen Android Auto leaves free (not under the media card).
        val v = visible?.let { RectF(it) }?.takeIf { it.width() > 100 && it.height() > 100 } ?: RectF(0f, 0f, w, h)
        val ink = Ink(maxOf(sc.dpi / 160f * 1.1f, v.height() / 420f), pal)
        fun dp(x: Float) = ink.dp(x)
        c.drawColor(pal.bg)

        val app = CarHub.app(carContext)
        val s = app.settings.value
        val st = CarHub.status.value
        val area = RectF(v.left + dp(14f), v.top + dp(12f), v.right - dp(14f), v.bottom - dp(12f))

        // Header: the car's name and how fresh the numbers are (the car's buttons sit to the right).
        val t2 = ink.text(22f, pal.text, ink.bold)
        c.drawText(ink.ellipsize(CarHub.message.value?.let { tr(it) } ?: s.carName, t2, area.width() * 0.55f), area.left + dp(2f), area.top + dp(24f), t2)
        val t1 = ink.text(13f, pal.muted, ink.medium)
        c.drawText(ink.ellipsize(tr(CarHub.updatedLine()), t1, area.width() * 0.55f), area.left + dp(2f), area.top + dp(44f), t1)

        val body = RectF(area.left, area.top + dp(60f), area.right, area.bottom)
        val wide = body.width() > body.height() * 1.25f
        val hero = if (wide) RectF(body.left, body.top, body.left + min(body.width() * 0.32f, body.height() * 1.0f), body.bottom)
        else RectF(body.left, body.top, body.right, body.top + body.height() * 0.40f)
        val grid = if (wide) RectF(hero.right + dp(12f), body.top, body.right, body.bottom) else RectF(body.left, hero.bottom + dp(12f), body.right, body.bottom)

        drawHero(c, ink, hero, st, s)

        val tiles = ArrayList<Tile>()
        val fuelOnly = st?.energyType == "Fuel"
        tiles += Tile("plan", tr("Plan a trip"), tr("Battery % on arrival"), R.drawable.ic_car_search, true) { screenManager.push(TripSearchScreen(carContext)) }
        tiles += Tile("chargers", tr(if (fuelOnly) "Fuel stations" else if (st?.energyType == "Hybrid") "Chargers & fuel" else "Chargers"), tr("Nearest first"),
            if (fuelOnly) R.drawable.ic_car_fuel else R.drawable.ic_car_charger, true) { screenManager.push(ChargersScreen(carContext)) }
        val saved = if (SavedRoutes.fromPlay(carContext)) SavedRoutes.all(carContext) else emptyList()
        if (saved.isNotEmpty()) tiles += Tile("saved", tr("Saved routes"), if (saved.size == 1) saved[0].name else tr("${saved.size} routes"), R.drawable.ic_car_route, false) {
            screenManager.push(SavedRoutesScreen(carContext))
        }
        val trips = CarHub.trips.value
        val week = trips?.filter { (it.startMillis ?: 0L) >= System.currentTimeMillis() - 7 * 86_400_000L }
        tiles += Tile("trips", tr("Previous trips"), if (trips == null) tr("Loading…") else tr("7 days: ${fmtDistance(week?.sumOf { it.distanceKm } ?: 0.0, s)}"),
            R.drawable.ic_car_route, false) { screenManager.push(TripsScreen(carContext)) }
        tiles += Tile("info", tr("Car Info"), st?.let { carInfoLine(it, s) } ?: tr("Loading…"), R.drawable.ic_car_gauge, false) { screenManager.push(CarInfoScreen(carContext)) }
        if (no.lionflow.app.model.Garage.hasSeveral(app)) tiles += Tile("switch", tr("Switch car"), s.carName, R.drawable.ic_car_switch, false) { screenManager.push(CarsScreen(carContext)) }

        // As many columns as fit with readable tiles.
        val gap = dp(10f)
        val cols = ((grid.width() + gap) / (dp(200f) + gap)).toInt().coerceIn(1, 3)
        val rows = (tiles.size + cols - 1) / cols
        val tw = (grid.width() - gap * (cols - 1)) / cols
        val th = ((grid.height() - gap * (rows - 1)) / rows).coerceAtMost(dp(120f))
        val out = ArrayList<Hit>()
        tiles.forEachIndexed { i, t ->
            val col = i % cols; val row = i / cols
            val r = RectF(grid.left + col * (tw + gap), grid.top + row * (th + gap), grid.left + col * (tw + gap) + tw, grid.top + row * (th + gap) + th)
            drawTile(c, ink, r, t, pressed == t.key)
            out += Hit(r, t.key, t.go)
        }
        hits = out
    }

    private fun carInfoLine(st: no.lionflow.app.model.VehicleStatus, s: no.lionflow.app.data.AppSettings): String =
        listOfNotNull(st.odometerKm?.let { fmtDistance(it, s) }, st.batteryHealthPct?.let { tr("Health {0} %").replace("{0}", "${it.roundToInt()}") }).joinToString(" · ").ifEmpty { tr("Details from the car") }

    private fun drawHero(c: Canvas, ink: Ink, r: RectF, st: no.lionflow.app.model.VehicleStatus?, s: no.lionflow.app.data.AppSettings) {
        val pal = ink.pal
        fun dp(v: Float) = ink.dp(v)
        ink.card(c, r, dp(22f))
        if (st == null) {
            c.drawText(tr("Getting the car's status…"), r.centerX(), r.centerY(), ink.text(15f, pal.muted, ink.medium, Paint.Align.CENTER)); return
        }
        val fuelOnly = st.energyType == "Fuel"
        val pct = (if (fuelOnly) st.fuelPct ?: st.batteryPct else st.batteryPct) ?: 0.0
        val range = if (fuelOnly) st.fuelRangeKm ?: st.rangeKm else st.rangeKm
        val color = when { pct < 10 -> pal.red; pct < 20 -> pal.orange; else -> pal.accent }

        // Gauge: a plain ring, coloured as far as the battery goes, with the % and range inside.
        val stacked = r.width() < r.height() * 1.3f
        val ringR = if (stacked) min(r.width() * 0.34f, r.height() * 0.30f) else min(r.height() * 0.36f, r.width() * 0.2f)
        val cx = if (stacked) r.centerX() else r.left + ringR + dp(26f)
        val cy = if (stacked) r.top + dp(18f) + ringR + dp(6f) else r.centerY()
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ringR * 0.16f; strokeCap = Paint.Cap.ROUND; this.color = pal.raised }
        c.drawCircle(cx, cy, ringR, ring)
        val sweep = (pct.coerceIn(0.0, 100.0) * 3.6).toFloat()
        if (sweep > 0.5f) { ring.color = color; c.drawArc(RectF(cx - ringR, cy - ringR, cx + ringR, cy + ringR), -90f, sweep, false, ring) }
        val big = ink.text(0f, pal.text, ink.bold, Paint.Align.CENTER).apply { textSize = ringR * 0.52f }
        val num = "${pct.roundToInt()}"
        c.drawText(num, cx - ringR * 0.07f, cy + big.textSize * 0.30f, big)
        val unit = ink.text(0f, pal.muted, ink.medium).apply { textSize = ringR * 0.2f }
        c.drawText("%", cx - ringR * 0.07f + big.measureText(num) / 2 + dp(2f), cy + big.textSize * 0.30f, unit)
        val sub = ink.text(0f, pal.muted, ink.medium, Paint.Align.CENTER).apply { textSize = ringR * 0.17f }
        c.drawText(range?.let { fmtDistance(it, s) } ?: "—", cx, cy + big.textSize * 0.30f + sub.textSize * 1.4f, sub)

        val lines = ArrayList<Pair<String, Int>>()
        when {
            st.isCharging -> lines += tr("Charging") + (st.remainingMinutes?.let { " · " + tr("full in {0}").replace("{0}", if (it >= 60) "${it / 60} h ${it % 60} min" else "$it min") } ?: "") to pal.accentText
            st.plugged -> lines += tr("Plugged in") to pal.accentText
            else -> lines += tr(CarHub.doorsLine(st)) to (if (st.openDoors.isNotEmpty() || st.locked == false) pal.orange else pal.text)
        }
        st.outsideTempC?.let { lines += "${it.roundToInt()}°C " + tr("outside") to pal.muted }
        if (st.preconditioningOn) lines += tr("Pre-heating") to pal.orange
        val tx: Float; var ty: Float; val align: Paint.Align; val maxW: Float
        if (stacked) { tx = r.centerX(); ty = cy + ringR + dp(30f); align = Paint.Align.CENTER; maxW = r.width() - dp(24f) }
        else { tx = cx + ringR + dp(22f); ty = r.centerY() - dp(12f) * (lines.size - 1); align = Paint.Align.LEFT; maxW = r.right - dp(16f) - tx }
        lines.forEachIndexed { i, (txt, col) ->
            if (ty > r.bottom - dp(10f)) return@forEachIndexed
            val p = ink.text(if (i == 0) 16f else 13.5f, col, if (i == 0) ink.bold else ink.medium, align)
            c.drawText(ink.ellipsize(txt, p, maxW), tx, ty, p)
            ty += dp(if (i == 0) 24f else 20f)
        }
    }

    private fun drawTile(c: Canvas, ink: Ink, r: RectF, t: Tile, pressed: Boolean) {
        val pal = ink.pal
        fun dp(v: Float) = ink.dp(v)
        ink.card(c, r, dp(20f), pressed = pressed)
        // Icon on the left, title and a short line beside it.
        val br = min(r.height() * 0.24f, dp(21f))
        val pad = dp(14f)
        ink.badge(c, carContext, r.left + pad + br, r.centerY(), br, t.icon, t.lit)
        val textX = r.left + pad + br * 2 + dp(12f)
        val maxW = r.right - dp(12f) - textX
        val tp = ink.text(17f, pal.text, ink.bold)
        c.drawText(ink.ellipsize(t.title, tp, maxW), textX, r.centerY() - dp(3f), tp)
        val sp = ink.text(13.5f, pal.muted, ink.medium)
        c.drawText(ink.ellipsize(t.sub, sp, maxW), textX, r.centerY() + dp(17f), sp)
    }

    private fun serviceLine(st: no.lionflow.app.model.VehicleStatus, s: no.lionflow.app.data.AppSettings): String {
        st.kmToService?.let { return "in ${fmtDistance(it, s)}" }
        st.daysToService?.let { d -> return if (d >= 60) "in ${(d / 30).toInt()} months" else "in ${d.toInt()} days" }
        return "Next service unknown"
    }
}
