package no.lionflow.app.car

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
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
            pressed = h.key; draw()
            main.postDelayed({ pressed = null; draw(); h.go() }, 140)
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
        return NavigationTemplate.Builder().setActionStrip(strip.build())
            .setMapActionStrip(ActionStrip.Builder().addAction(Action.Builder().setIcon(carContext.icon(R.drawable.ic_car_grid)).setOnClickListener {
                screenManager.push(OverviewScreen(carContext, guard))
            }.build()).build())
            .build()
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
        val ink = Ink(maxOf(sc.dpi / 160f * 1.15f, h / 400f), pal)
        fun dp(v: Float) = ink.dp(v)
        // Background: a soft vertical gradient with a faint glow behind the gauge.
        val bgp = Paint(Paint.ANTI_ALIAS_FLAG)
        bgp.shader = LinearGradient(0f, 0f, 0f, h, pal.bg2, pal.bg, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, bgp)
        bgp.shader = null

        val app = CarHub.app(carContext)
        val s = app.settings.value
        val st = CarHub.status.value
        val v = visible?.let { RectF(it) } ?: RectF(0f, 0f, w, h)
        val area = RectF(dp(16f), dp(14f), w - dp(16f), h - dp(14f))
        val topInset = (v.top).coerceIn(dp(52f), dp(110f))   // the car's buttons sit at the top right

        // Header: app name and the car's name, top left.
        val t1 = ink.text(13f, pal.muted, ink.medium)
        c.drawText(tr("LionFlow").uppercase(), area.left + dp(4f), area.top + dp(16f), t1)
        val t2 = ink.text(24f, pal.text, ink.bold)
        val headW = area.width() * 0.55f
        c.drawText(ink.ellipsize(CarHub.message.value?.let { tr(it) } ?: s.carName, t2, headW), area.left + dp(4f), area.top + dp(44f), t2)

        val body = RectF(area.left, area.top + maxOf(dp(60f), topInset), area.right, area.bottom)
        val wide = body.width() > body.height() * 1.25f
        val hero = if (wide) RectF(body.left, body.top, body.left + min(body.width() * 0.36f, body.height() * 1.0f), body.bottom)
        else RectF(body.left, body.top, body.right, body.top + body.height() * 0.42f)
        // Room on the right for the car's own grid button (the map buttons), so it never covers a tile.
        val mapStrip = (w - v.right).coerceIn(dp(56f), dp(96f))
        val grid = if (wide) RectF(hero.right + dp(18f), body.top, body.right - mapStrip, body.bottom) else RectF(body.left, hero.bottom + dp(16f), body.right - mapStrip, body.bottom)

        drawHero(c, ink, hero, st, s)

        // Tiles.
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
        tiles += Tile("trips", tr("Trips"), if (trips == null) tr("Loading…") else tr("7 days: ${fmtDistance(week?.sumOf { it.distanceKm } ?: 0.0, s)}"),
            R.drawable.ic_car_route, false) { screenManager.push(TripsScreen(carContext)) }
        tiles += Tile("doors", tr(st?.let { CarHub.doorsLine(it) } ?: "Doors"), tr(CarHub.updatedLine()), R.drawable.ic_car_lock, false) { refreshAll() }
        if (no.lionflow.app.model.Garage.hasSeveral(app)) tiles += Tile("switch", tr("Switch car"), s.carName, R.drawable.ic_car_switch, false) { screenManager.push(CarsScreen(carContext)) }
        else if (st != null) tiles += Tile("service", tr("Service"), tr(serviceLine(st, s)), R.drawable.ic_car_gauge, false) { }

        val cols = if (wide) (if (grid.width() / grid.height() > 1.9f) 3 else 2) else 2
        val shown = tiles.take(if (cols == 3) 6 else if (wide) 6 else 6)
        val rows = (shown.size + cols - 1) / cols
        val gap = dp(16f)
        val tw = (grid.width() - gap * (cols - 1)) / cols
        val th = ((grid.height() - gap * (rows - 1)) / rows).coerceAtMost(dp(150f))
        val out = ArrayList<Hit>()
        shown.forEachIndexed { i, t ->
            val col = i % cols; val row = i / cols
            val r = RectF(grid.left + col * (tw + gap), grid.top + row * (th + gap), grid.left + col * (tw + gap) + tw, grid.top + row * (th + gap) + th)
            drawTile(c, ink, r, t, pressed == t.key)
            out += Hit(r, t.key, t.go)
        }
        hits = out
    }

    private fun drawHero(c: Canvas, ink: Ink, r: RectF, st: no.lionflow.app.model.VehicleStatus?, s: no.lionflow.app.data.AppSettings) {
        val pal = ink.pal
        fun dp(v: Float) = ink.dp(v)
        ink.card(c, r, dp(30f))
        if (st == null) {
            c.drawText(tr("Getting the car's status…"), r.centerX(), r.centerY(), ink.text(15f, pal.muted, ink.medium, Paint.Align.CENTER)); return
        }
        val fuelOnly = st.energyType == "Fuel"
        val pct = (if (fuelOnly) st.fuelPct ?: st.batteryPct else st.batteryPct) ?: 0.0
        val range = if (fuelOnly) st.fuelRangeKm ?: st.rangeKm else st.rangeKm
        val color = when { pct < 10 -> pal.red; pct < 20 -> pal.orange; else -> pal.accent }

        // Gauge: a recessed track with a glowing arc, and a raised disc in the middle.
        val wide = r.width() < r.height() * 1.3f
        val ringR = if (wide) min(r.width(), r.height() * 0.78f) * 0.36f else min(r.height() * 0.40f, r.width() * 0.22f)
        val cx = if (wide) r.centerX() else r.left + ringR + dp(34f)
        val cy = if (wide) r.top + dp(22f) + ringR + dp(10f) else r.centerY()
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ringR * 0.20f; strokeCap = Paint.Cap.ROUND }
        track.color = if (pal.dark) Ink.darken(pal.card, 0.35f) else Ink.darken(pal.card, 0.08f)
        c.drawCircle(cx, cy, ringR, track)
        val box = RectF(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
        val sweep = (pct.coerceIn(0.0, 100.0) * 3.6).toFloat()
        if (sweep > 0.5f) {
            val glow = Paint(track).apply { this.color = (color and 0x00FFFFFF) or 0x88000000.toInt(); maskFilter = BlurMaskFilter(ringR * 0.18f, BlurMaskFilter.Blur.NORMAL) }
            c.drawArc(box, -90f, sweep, false, glow)
            val fr = (sweep / 360f).coerceAtLeast(0.01f)
            track.shader = SweepGradient(cx, cy, intArrayOf(Ink.darken(color, 0.25f), color, Ink.lighten(color, 0.2f), Ink.lighten(color, 0.2f)), floatArrayOf(0f, fr * 0.6f, fr, 1f)).also {
                val m = android.graphics.Matrix(); m.setRotate(-90f, cx, cy); it.setLocalMatrix(m)
            }
            c.drawArc(box, -90f, sweep, false, track)
            track.shader = null
        }
        val disc = ringR * 0.72f
        ink.card(c, RectF(cx - disc, cy - disc, cx + disc, cy + disc), disc, pal.raised)
        val big = ink.text(0f, pal.text, ink.bold, Paint.Align.CENTER).apply { textSize = disc * 0.62f }
        c.drawText("${pct.roundToInt()}", cx - disc * 0.08f, cy + big.textSize * 0.30f, big)
        val unit = ink.text(0f, pal.muted, ink.medium, Paint.Align.LEFT).apply { textSize = disc * 0.24f }
        c.drawText("%", cx - disc * 0.08f + big.measureText("${pct.roundToInt()}") / 2 + dp(2f), cy + big.textSize * 0.30f, unit)
        val sub = ink.text(0f, pal.muted, ink.medium, Paint.Align.CENTER).apply { textSize = disc * 0.20f }
        c.drawText(range?.let { fmtDistance(it, s) } ?: "—", cx, cy + big.textSize * 0.30f + sub.textSize * 1.35f, sub)

        // Status lines under (or beside) the gauge.
        val lines = ArrayList<Pair<String, Int>>()
        when {
            st.isCharging -> lines += tr("Charging") + (st.remainingMinutes?.let { " · " + tr("full in {0}").replace("{0}", if (it >= 60) "${it / 60} h ${it % 60} min" else "$it min") } ?: "") to pal.accentText
            st.plugged -> lines += tr("Plugged in") to pal.accentText
            else -> lines += tr(CarHub.doorsLine(st)) to (if (st.openDoors.isNotEmpty() || st.locked == false) pal.orange else pal.text)
        }
        st.outsideTempC?.let { lines += "${it.roundToInt()}°C " + tr("outside") to pal.muted }
        if (st.preconditioningOn) lines += tr("Pre-heating") to pal.orange
        val tx: Float; var ty: Float; val align: Paint.Align; val maxW: Float
        if (wide) { tx = r.centerX(); ty = cy + ringR + dp(40f); align = Paint.Align.CENTER; maxW = r.width() - dp(32f) }
        else { tx = cx + ringR + dp(30f); ty = r.centerY() - dp(14f) * (lines.size - 1); align = Paint.Align.LEFT; maxW = r.right - dp(20f) - tx }
        lines.forEachIndexed { i, (txt, col) ->
            if (ty > r.bottom - dp(14f)) return@forEachIndexed
            val p = ink.text(if (i == 0) 17f else 14f, col, if (i == 0) ink.bold else ink.medium, align)
            c.drawText(ink.ellipsize(txt, p, maxW), tx, ty, p)
            ty += dp(if (i == 0) 28f else 23f)
        }
    }

    private fun drawTile(c: Canvas, ink: Ink, r: RectF, t: Tile, pressed: Boolean) {
        val pal = ink.pal
        fun dp(v: Float) = ink.dp(v)
        ink.card(c, r, dp(24f), pressed = pressed)
        val small = r.height() < dp(104f)
        val br = if (small) min(r.height() * 0.24f, dp(19f)) else min(r.height() * 0.22f, dp(26f))
        val pad = dp(if (small) 14f else 18f)
        val textX: Float; val titleY: Float
        if (small || r.width() > r.height() * 2.1f) {
            // Icon on the left, text beside it.
            ink.badge(c, carContext, r.left + pad + br, r.centerY(), br, t.icon, t.lit)
            textX = r.left + pad + br * 2 + dp(12f); titleY = r.centerY() - dp(2f)
        } else {
            ink.badge(c, carContext, r.left + pad + br, r.top + pad + br, br, t.icon, t.lit)
            textX = r.left + pad; titleY = r.bottom - pad - dp(22f)
        }
        val maxW = r.right - dp(14f) - textX
        val tp = ink.text(if (small) 16f else 17f, pal.text, ink.bold)
        c.drawText(ink.ellipsize(t.title, tp, maxW), textX, titleY, tp)
        val sp = ink.text(if (small) 13f else 13.5f, pal.muted, ink.medium)
        c.drawText(ink.ellipsize(t.sub, sp, maxW), textX, titleY + dp(if (small) 20f else 22f), sp)
        if (!small && r.width() <= r.height() * 2.1f) {
            // A small arrow, top right: it can be tapped.
            val ap = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.2f); strokeCap = Paint.Cap.ROUND; color = pal.muted }
            val ax = r.right - pad - dp(4f); val ay = r.top + pad + br
            c.drawLine(ax - dp(8f), ay + dp(8f), ax + dp(1f), ay - dp(1f), ap)
            c.drawLine(ax - dp(6f), ay - dp(1f), ax + dp(1f), ay - dp(1f), ap)
            c.drawLine(ax + dp(1f), ay - dp(1f), ax + dp(1f), ay + dp(6f), ap)
        }
    }

    private fun serviceLine(st: no.lionflow.app.model.VehicleStatus, s: no.lionflow.app.data.AppSettings): String {
        st.kmToService?.let { return "in ${fmtDistance(it, s)}" }
        st.daysToService?.let { d -> return if (d >= 60) "in ${(d / 30).toInt()} months" else "in ${d.toInt()} days" }
        return "Next service unknown"
    }
}
