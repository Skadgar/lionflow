package no.lionflow.app.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.lionflow.app.R
import no.lionflow.app.plan.SavedRoutes
import no.lionflow.app.ui.fmtDistance
import no.lionflow.app.ui.l10n.L10n
import no.lionflow.app.ui.l10n.TimeFmt
import no.lionflow.app.ui.l10n.tr
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val BG: Int get() = CarTheme.p.bg
private val CARD: Int get() = CarTheme.p.card
private val RAISED: Int get() = CarTheme.p.raised
private val LINE: Int get() = CarTheme.p.line
private val TEXT: Int get() = CarTheme.p.text
private val MUTED: Int get() = CarTheme.p.muted
private val LIME: Int get() = CarTheme.p.accent
private val ORANGE: Int get() = CarTheme.p.orange
private val RED: Int get() = CarTheme.p.red
private val DRIVEN: Int get() = CarTheme.p.driven

/** Fits a route's points into a box, keeping the shape (longitudes squeezed by latitude). */
private class Fit(lat: DoubleArray, lon: DoubleArray, val box: RectF) {
    private val k = cos(Math.toRadians(lat.average()))
    private val x0: Double; private val y0: Double; private val scale: Double; private val ox: Float; private val oy: Float
    init {
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (i in lat.indices) { val x = lon[i] * k; val y = -lat[i]; minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y) }
        val w = max(maxX - minX, 1e-6); val h = max(maxY - minY, 1e-6)
        scale = min(box.width() / w, box.height() / h)
        x0 = minX; y0 = minY
        ox = box.left + ((box.width() - w * scale) / 2).toFloat(); oy = box.top + ((box.height() - h * scale) / 2).toFloat()
    }
    fun x(la: Double, lo: Double) = ox + ((lo * k - x0) * scale).toFloat()
    fun y(la: Double) = oy + ((-la - y0) * scale).toFloat()
}

/** Web-Mercator projection of a map area (the map picture and the route drawn on it use the same). */
private class Merc(val box: RectF, val west: Double, val east: Double, val north: Double, val south: Double) {
    private val x0 = mx(west); private val x1 = mx(east); private val y0 = my(north); private val y1 = my(south)
    fun x(la: Double, lo: Double) = box.left + ((mx(lo) - x0) / (x1 - x0) * box.width()).toFloat()
    fun y(la: Double) = box.top + ((my(la) - y0) / (y1 - y0) * box.height()).toFloat()
    companion object {
        fun mx(lo: Double) = (lo + 180.0) / 360.0
        fun my(la: Double): Double { val r = Math.toRadians(la.coerceIn(-85.0, 85.0)); return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 }
        fun lon(x: Double) = x * 360.0 - 180.0
        fun lat(y: Double) = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y))))
        /** The map area for [box] in which the route's own extent fills [inner] (a part of [box]). */
        fun fitting(lat: DoubleArray, lon: DoubleArray, box: RectF, inner: RectF): Merc {
            var ax = Double.MAX_VALUE; var bx = -Double.MAX_VALUE; var ay = Double.MAX_VALUE; var by = -Double.MAX_VALUE
            for (i in lat.indices) { val x = mx(lon[i]); val y = my(lat[i]); ax = min(ax, x); bx = max(bx, x); ay = min(ay, y); by = max(by, y) }
            // Some room around a short route, so the map shows the area too.
            val minSpan = 0.00006
            if (bx - ax < minSpan) { val c = (ax + bx) / 2; ax = c - minSpan / 2; bx = c + minSpan / 2 }
            if (by - ay < minSpan) { val c = (ay + by) / 2; ay = c - minSpan / 2; by = c + minSpan / 2 }
            val sc = min(inner.width() / (bx - ax), inner.height() / (by - ay))   // screen px per mercator unit
            val ox = inner.left + (inner.width() - (bx - ax) * sc) / 2; val oy = inner.top + (inner.height() - (by - ay) * sc) / 2
            val west = ax - (ox - box.left) / sc; val east = west + box.width() / sc
            val north = ay - (oy - box.top) / sc; val south = north + box.height() / sc
            return Merc(box, lon(west), lon(east), lat(north), lat(south))
        }
    }
}

private fun pctColor(p: Double) = when { p < 10 -> RED; p < 20 -> ORANGE; else -> LIME }

private fun dur(m: Double): String { val t = m.roundToInt().coerceAtLeast(0); return if (t >= 60) "${t / 60} h ${t % 60} min" else "$t min" }

private fun clock(ms: Long): String = java.text.SimpleDateFormat(TimeFmt.pattern("HH:mm"), L10n.locale).format(java.util.Date(ms))

/** A small picture of a route's shape, for the list of saved routes. */
internal fun routeThumb(r: SavedRoutes.Saved, size: Int = 256): CarIcon {
    val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    val pad = size * 0.14f
    val fit = Fit(r.lat, r.lon, RectF(pad, pad, size - pad, size - pad))
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = RAISED }
    c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.18f, size * 0.18f, p)
    val path = Path()
    for (i in r.lat.indices) { val x = fit.x(r.lat[i], r.lon[i]); val y = fit.y(r.lat[i]); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
    p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.045f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND; p.color = LIME
    c.drawPath(path, p)
    p.style = Paint.Style.FILL
    for (s in r.stops) { p.color = BG; c.drawCircle(fit.x(s.lat, s.lon), fit.y(s.lat), size * 0.06f, p); p.color = LIME; c.drawCircle(fit.x(s.lat, s.lon), fit.y(s.lat), size * 0.035f, p) }
    p.color = ORANGE; c.drawCircle(fit.x(r.lat[0], r.lon[0]), fit.y(r.lat[0]), size * 0.045f, p)
    p.color = TEXT; c.drawCircle(fit.x(r.lat.last(), r.lon.last()), fit.y(r.lat.last()), size * 0.05f, p)
    return CarIcon.Builder(IconCompat.createWithBitmap(b)).build()
}

/** Saved routes as tiles, each with a picture of the route. */
class SavedRoutesScreen(ctx: CarContext) : Screen(ctx) {
    init {
        // Trips saved or deleted on the phone show up here at once.
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { SavedRoutes.changes.collect { invalidate() } } }
    }

    override fun onGetTemplate(): Template {
        CarTheme.refresh(carContext)
        val s = CarHub.app(carContext).settings.value
        val saved = SavedRoutes.all(carContext)
        if (saved.isEmpty()) return MessageTemplate.Builder(tr("Plan a trip on your phone and tap Save trip.")).setTitle(tr("Saved routes")).setHeaderAction(Action.BACK).build()
        val list = ItemList.Builder()
        for (r in saved) {
            val stops = when (r.stops.size) { 0 -> "no charging stops"; 1 -> "1 charging stop"; else -> "${r.stops.size} charging stops" }
            list.addItem(GridItem.Builder().setTitle(r.name.ifBlank { "—" })
                .setText(fmtDistance(r.totalKm, s) + " · " + tr(stops))
                .setImage(routeThumb(r), GridItem.IMAGE_TYPE_LARGE)
                .setOnClickListener { screenManager.push(RouteLiveScreen(carContext, r.id)) }.build())
        }
        return GridTemplate.Builder().setTitle(tr("Saved routes")).setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }
}

/**
 * One saved route, drawn on the car screen: a card with what is left and the battery, the route itself with the car on it,
 * and a timeline of the stops. The car is asked for a fresh status every 30 seconds while this is showing.
 */
class RouteLiveScreen(ctx: CarContext, private val id: Long, private val temp: SavedRoutes.Saved? = null) : Screen(ctx), SurfaceCallback, DefaultLifecycleObserver {
    /** The route shown: a saved one, or [temp] (a trip just planned in the car, not saved). */
    private fun route(): SavedRoutes.Saved? = temp ?: SavedRoutes.all(carContext).firstOrNull { it.id == id }

    private val main = Handler(Looper.getMainLooper())
    private var surface: SurfaceContainer? = null
    private var visible: Rect? = null
    private var lastFetch = 0L

    init {
        lifecycle.addObserver(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { while (true) { lastFetch = System.currentTimeMillis(); CarHub.refresh(carContext, force = true); delay(10_000L) } }
                launch { CarHub.status.collect { main.post { draw() } } }
                // Deleted on the phone: leave.
                launch { SavedRoutes.changes.collect { if (SavedRoutes.all(carContext).none { it.id == id }) main.post { try { screenManager.pop() } catch (_: Exception) { } } } }
                launch { while (true) { delay(5_000L); main.post { draw() } } }   // keeps "updated … ago" current
            }
        }
    }

    override fun onCreate(owner: LifecycleOwner) { carContext.getCarService(AppManager::class.java).setSurfaceCallback(this) }
    override fun onDestroy(owner: LifecycleOwner) {
        try { carContext.getCarService(AppManager::class.java).setSurfaceCallback(null) } catch (_: Exception) { }
        try { snapper?.cancel() } catch (_: Exception) { }
    }

    override fun onSurfaceAvailable(c: SurfaceContainer) { surface = c; main.post { draw() } }
    override fun onSurfaceDestroyed(c: SurfaceContainer) { surface = null }
    override fun onVisibleAreaChanged(visibleArea: Rect) { visible = Rect(visibleArea); main.post { draw() } }
    override fun onStableAreaChanged(stableArea: Rect) { if (visible == null) { visible = Rect(stableArea); main.post { draw() } } }

    override fun onGetTemplate(): Template {
        val r = route()
        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setIcon(carContext.icon(R.drawable.ic_car_back)).setOnClickListener { screenManager.pop() }.build())
        if (r != null) {
            strip.addAction(Action.Builder().setTitle(tr("Navigate")).setOnClickListener {
                val st = CarHub.status.value
                val live = SavedRoutes.live(carContext, r, st?.lat, st?.lon, st?.batteryPct)
                val n = live.nextStop
                val here = { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
                if (n != null) openInGoogleMaps(carContext, n.lat, n.lon, n.name, stillHere = here)
                else openInGoogleMaps(carContext, r.lat.last(), r.lon.last(), r.name, stillHere = here)
            }.build())
            strip.addAction(Action.Builder().setIcon(carContext.icon(R.drawable.ic_car_refresh)).setOnClickListener {
                SavedRoutes.resetProgress(carContext, id); CarHub.refresh(carContext, force = true); CarHub.say("Updating…")
            }.build())
        }
        return NavigationTemplate.Builder().setActionStrip(strip.build()).build()
    }

    // ---------------- the map picture ----------------

    /** The map behind the route (the same OpenFreeMap style as the app), made once per route, size and look. */
    private var mapPic: android.graphics.Bitmap? = null
    private var mapKey: String? = null
    private var snapper: org.maplibre.android.snapshotter.MapSnapshotter? = null

    private fun ensureMap(key: String, m: Merc, ratio: Float, dark: Boolean) {
        if (key == mapKey) return
        mapKey = key; mapPic = null
        try { snapper?.cancel() } catch (_: Exception) { }
        try {
            org.maplibre.android.MapLibre.getInstance(carContext.applicationContext)
            val url = if (dark) "https://tiles.openfreemap.org/styles/dark" else "https://tiles.openfreemap.org/styles/positron"
            val bounds = org.maplibre.android.geometry.LatLngBounds.from(m.north, m.east, m.south, m.west)
            val opts = org.maplibre.android.snapshotter.MapSnapshotter.Options((m.box.width() / ratio).toInt().coerceAtLeast(64), (m.box.height() / ratio).toInt().coerceAtLeast(64))
                .withPixelRatio(ratio)
                .withStyleBuilder(org.maplibre.android.maps.Style.Builder().fromUri(url))
                .withRegion(bounds)
                .withLogo(false)
            val sn = org.maplibre.android.snapshotter.MapSnapshotter(carContext.applicationContext, opts)
            snapper = sn
            sn.start({ shot -> if (mapKey == key) { mapPic = shot.bitmap; main.post { draw() } } }, { err -> android.util.Log.w("LionFlowCar", "map picture failed: $err") })
        } catch (e: Exception) { android.util.Log.w("LionFlowCar", "map picture failed", e) }
    }

    // ---------------- drawing ----------------

    private fun draw() {
        val sc = surface ?: return
        val sf = sc.surface ?: return
        if (!sf.isValid) return
        val canvas = try { sf.lockHardwareCanvas() } catch (_: Exception) { try { sf.lockCanvas(null) } catch (_: Exception) { return } }
        try { paint(canvas, sc) } catch (e: Exception) {
            android.util.Log.e("LionFlowCar", "route draw failed", e)
        } finally { try { sf.unlockCanvasAndPost(canvas) } catch (_: Exception) { } }
    }

    private fun paint(c: Canvas, sc: SurfaceContainer) {
        // Sized from both the screen's density and its height, so wide, low car screens (like the E-5008's) get big, readable text.
        val d = maxOf(sc.dpi / 160f * 1.15f, sc.height / 400f)
        val ink = Ink(d, CarTheme.refresh(carContext))
        fun dp(v: Float) = v * d
        val w = sc.width.toFloat(); val h = sc.height.toFloat()
        c.drawColor(BG)
        // The whole screen is used; the car's buttons float on top of the route panel (top right).
        val area = RectF(dp(12f), dp(12f), w - dp(12f), h - dp(12f))
        val buttonsBottom = visible?.top?.toFloat() ?: 0f
        val s = CarHub.app(carContext).settings.value
        val r = route()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        val bold = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        if (r == null) { text.textSize = dp(20f); c.drawText(tr("This route was deleted."), area.left, area.centerY(), text); return }
        val st = CarHub.status.value
        val live = SavedRoutes.live(carContext, r, st?.lat, st?.lon, st?.batteryPct)
        val now = System.currentTimeMillis()

        // Card on the left, the route on the right (or the card on top on a tall screen).
        val wide = area.width() > area.height() * 1.15f
        val card = if (wide) RectF(area.left, area.top, area.left + min(dp(340f), area.width() * (if (area.width() > area.height() * 2.2f) 0.36f else 0.44f)), area.bottom)
        else RectF(area.left, area.top, area.right, area.top + min(dp(300f), area.height() * 0.55f))
        val mapBox = if (wide) RectF(card.right + dp(16f), area.top, area.right, area.bottom) else RectF(area.left, card.bottom + dp(12f), area.right, area.bottom)

        // ---- card
        ink.card(c, card, dp(26f))
        val L = card.left + dp(20f); var y = card.top + dp(32f)
        val cw = card.width() - dp(40f)
        text.typeface = bold; text.textSize = dp(15f); text.color = MUTED
        c.drawText(ellipsize(r.name, text, cw), L, y, text)
        y += dp(44f)
        text.textSize = dp(40f); text.color = TEXT
        val big = fmtDistance(live.leftKm, s)
        c.drawText(big, L, y, text)
        val bigW = text.measureText(big)
        text.typeface = Typeface.DEFAULT; text.textSize = dp(16f); text.color = MUTED
        c.drawText(tr("left"), L + bigW + dp(8f), y, text)
        y += dp(26f)
        live.minLeft?.let { m ->
            text.textSize = dp(15f); text.color = TEXT
            c.drawText(ellipsize(tr("≈ {0} · arrive {1}").replace("{0}", dur(m)).replace("{1}", clock(now + (m * 60_000).toLong())), text, cw), L, y, text)
            y += dp(22f)
        }

        // Battery: two small rings, "Now" in grey and the % expected at the next stop (or the end) in colour.
        y += dp(10f)
        val ringR = dp(32f)
        val pct = live.pctAtNext
        val nowPct = st?.batteryPct
        fun ring(cx: Float, cy: Float, value: Double?, color: Int, label: String) {
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(7f); p.strokeCap = Paint.Cap.ROUND; p.color = RAISED
            c.drawCircle(cx, cy, ringR, p)
            if (value != null && value > 0.5) {
                p.color = color
                c.drawArc(RectF(cx - ringR, cy - ringR, cx + ringR, cy + ringR), -90f, (value.coerceIn(0.0, 100.0) * 3.6).toFloat(), false, p)
            }
            text.typeface = bold; text.textSize = dp(19f); text.color = TEXT; text.textAlign = Paint.Align.CENTER
            c.drawText(value?.let { "${it.coerceAtLeast(0.0).roundToInt()}%" } ?: "—", cx, cy + dp(7f), text)
            text.typeface = Typeface.DEFAULT; text.textSize = dp(13f); text.color = MUTED
            c.drawText(ellipsize(label, text, ringR * 2 + dp(24f)), cx, cy + ringR + dp(22f), text)
            text.textAlign = Paint.Align.LEFT
        }
        val c1 = L + ringR + dp(4f); val cyR = y + ringR
        val c2 = c1 + ringR * 2 + dp(56f)
        ring(c1, cyR, nowPct, MUTED, tr("Now"))
        // An arrow from now to then.
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(2.5f); p.strokeCap = Paint.Cap.ROUND; p.color = MUTED
        val ax0 = c1 + ringR + dp(14f); val ax1 = c2 - ringR - dp(14f)
        c.drawLine(ax0, cyR, ax1, cyR, p); c.drawLine(ax1 - dp(7f), cyR - dp(7f), ax1, cyR, p); c.drawLine(ax1 - dp(7f), cyR + dp(7f), ax1, cyR, p)
        ring(c2, cyR, pct, pct?.let { pctColor(it) } ?: LIME, tr(if (live.nextStop != null) "Next stop" else "Arrival"))
        y = cyR + ringR + dp(40f)

        // next charging stop
        p.style = Paint.Style.FILL; p.color = RAISED
        val boxH = dp(66f)
        if (y + boxH < card.bottom - dp(30f)) {
            val box = RectF(L, y, card.right - dp(20f), y + boxH)
            c.drawRoundRect(box, dp(16f), dp(16f), p)
            val n = live.nextStop
            boltBadge(c, box.left + dp(26f), box.centerY(), dp(15f), if (n != null) LIME else DRIVEN)
            val bx = box.left + dp(52f); val bw = box.right - dp(12f) - bx
            text.typeface = bold; text.textSize = dp(16f); text.color = TEXT
            c.drawText(ellipsize(if (n != null) n.name else tr("No charging stops left"), text, bw), bx, box.centerY() - dp(4f), text)
            text.typeface = Typeface.DEFAULT; text.textSize = dp(14f); text.color = MUTED
            val sub = if (n != null) tr("In {0}").replace("{0}", fmtDistance(live.toNextKm ?: 0.0, s)) + " · " +
                tr(if (live.stopsLeft == 1) "1 charging stop left" else "{0} charging stops left").replace("{0}", "${live.stopsLeft}")
            else tr("Straight to the destination")
            c.drawText(ellipsize(sub, text, bw), bx, box.centerY() + dp(18f), text)
            y += boxH
        }

        // footer: how fresh the car's numbers are, or that the car is off the route
        text.textSize = dp(13f)
        val foot = if (st == null) tr("Getting the car's status…")
        else if (live.offRouteKm >= 3.0) tr("The car is {0} from the route").replace("{0}", fmtDistance(live.offRouteKm, s))
        else tr(CarHub.updatedLine())
        text.color = if (st != null && live.offRouteKm >= 3.0) ORANGE else MUTED
        c.drawText(ellipsize(foot, text, cw), L, card.bottom - dp(18f), text)

        // ---- the route
        val tlH = dp(64f)
        // The route itself stays clear of the floating buttons; the panel behind it doesn't.
        val routeBox = RectF(mapBox.left + dp(18f), maxOf(mapBox.top + dp(18f), buttonsBottom + dp(6f), area.top + dp(60f)), mapBox.right - dp(18f), mapBox.bottom - tlH - dp(18f))
        ink.card(c, mapBox, dp(26f), CarTheme.p.bg2, pressed = true)
        p.style = Paint.Style.FILL
        val merc = Merc.fitting(r.lat, r.lon, mapBox, routeBox)
        ensureMap("${r.id}:${mapBox.width().toInt()}x${mapBox.height().toInt()}:${CarTheme.p.dark}", merc, d, CarTheme.p.dark)
        val pic = mapPic
        val clip = Path().apply { addRoundRect(mapBox, dp(26f), dp(26f), Path.Direction.CW) }
        c.save(); c.clipPath(clip)
        if (pic != null) {
            c.drawBitmap(pic, null, mapBox, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            // A light veil, so the route and the stops stand out from the map.
            p.style = Paint.Style.FILL; p.color = (BG and 0x00FFFFFF) or (if (CarTheme.p.dark) 0x30000000 else 0x22000000)
            c.drawRect(mapBox, p)
        } else {
            // A faint dot grid until the map has loaded.
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (CarTheme.p.dark) 0x14FFFFFF else 0x22000000 }
            val step = dp(22f)
            var gy = mapBox.top + step
            while (gy < mapBox.bottom - dp(8f)) { var gx = mapBox.left + step; while (gx < mapBox.right - dp(8f)) { c.drawCircle(gx, gy, dp(1.2f), dot); gx += step }; gy += step }
        }
        c.restore()
        if (routeBox.height() > dp(60f) && routeBox.width() > dp(60f)) {
            val fit = merc
            fun path(from: Int, to: Int): Path { val pa = Path(); for (i in from..to) { val x = fit.x(r.lat[i], r.lon[i]); val yy = fit.y(r.lat[i]); if (i == from) pa.moveTo(x, yy) else pa.lineTo(x, yy) }; return pa }
            val at = live.at.coerceIn(0, r.lat.size - 1)
            p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
            if (at > 0) { p.color = DRIVEN; p.strokeWidth = dp(5f); c.drawPath(path(0, at), p) }
            if (at < r.lat.size - 1) {
                val ahead = path(at, r.lat.size - 1)
                // A wider, see-through band under the line (no blur: quick to draw).
                p.color = CarTheme.p.glow; p.strokeWidth = dp(12f); c.drawPath(ahead, p)
                p.maskFilter = null; p.color = LIME; p.strokeWidth = dp(6f); c.drawPath(ahead, p)
            }
            p.style = Paint.Style.FILL
            // start and destination
            p.color = DRIVEN; c.drawCircle(fit.x(r.lat[0], r.lon[0]), fit.y(r.lat[0]), dp(6f), p)
            val ex = fit.x(r.lat.last(), r.lon.last()); val ey = fit.y(r.lat.last())
            p.color = TEXT; c.drawCircle(ex, ey, dp(11f), p); p.color = BG; c.drawCircle(ex, ey, dp(4.5f), p)
            // charging stops: the next one with the expected %, later ones with the planned %
            for (stp in r.stops) {
                val sx = fit.x(stp.lat, stp.lon); val sy = fit.y(stp.lat)
                val passed = stp.idx <= at
                boltBadge(c, sx, sy, dp(13f), if (passed) DRIVEN else LIME)
                val label = when {
                    passed -> null
                    stp === live.nextStop -> live.pctAtNext?.let { "${it.coerceAtLeast(0.0).roundToInt()}%" }
                    stp.arrivePct > 0 -> "≈${stp.arrivePct.roundToInt()}%"
                    else -> null
                }
                if (label != null) pill(c, label, sx, sy - dp(24f), dp(13f), if (stp === live.nextStop) pctColor(live.pctAtNext ?: 50.0) else RAISED, if (stp === live.nextStop) BG else TEXT, d)
            }
            live.pctAtEnd?.let { pill(c, "${it.coerceAtLeast(0.0).roundToInt()}%", ex, ey - dp(26f), dp(13f), pctColor(it), BG, d) }
            // The destination's name under its marker.
            val destName = r.name.substringAfterLast("→ ").removeSuffix(" ⇄").trim()
            if (destName.isNotEmpty()) {
                val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(12f); typeface = bold }
                pill(c, ellipsize(destName, lp, dp(160f)), ex, ey + dp(24f), dp(12f), RAISED, TEXT, d)
            }
            // the car
            if (st?.lat != null && st.lon != null && live.offRouteKm < 3.0) {
                val carX = fit.x(r.lat[at], r.lon[at]); val carY = fit.y(r.lat[at])
                p.color = (ORANGE and 0x00FFFFFF) or 0x55000000; c.drawCircle(carX, carY, dp(20f), p)
                p.color = TEXT; c.drawCircle(carX, carY, dp(11f), p)
                p.color = ORANGE; c.drawCircle(carX, carY, dp(8f), p)
            }
        }

        // ---- timeline: start → stops → end, with the car on it
        val tl = RectF(mapBox.left + dp(28f), mapBox.bottom - tlH, mapBox.right - dp(28f), mapBox.bottom - dp(18f))
        val ly = tl.top + dp(14f)
        // A panel behind the timeline, so it reads on top of the map.
        p.style = Paint.Style.FILL; p.color = (CARD and 0x00FFFFFF) or 0xE6000000.toInt()
        val panel = RectF(mapBox.left + dp(12f), tl.top - dp(10f), mapBox.right - dp(12f), mapBox.bottom - dp(10f))
        c.drawRoundRect(panel, dp(18f), dp(18f), p)
        if (mapPic != null) {
            text.typeface = Typeface.DEFAULT; text.textSize = dp(10f); text.color = MUTED; text.textAlign = Paint.Align.RIGHT
            c.drawText("© OpenFreeMap © OpenStreetMap", panel.right - dp(6f), panel.top - dp(6f), text)
            text.textAlign = Paint.Align.LEFT
        }
        val total = r.totalKm.coerceAtLeast(0.1)
        fun xAt(km: Double) = tl.left + (km / total).toFloat().coerceIn(0f, 1f) * tl.width()
        val doneKm = r.km[live.at.coerceIn(0, r.km.size - 1)]
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = dp(6f)
        p.color = RAISED; c.drawLine(tl.left, ly, tl.right, ly, p)
        p.color = LIME; if (doneKm > 0) c.drawLine(tl.left, ly, xAt(doneKm), ly, p)
        p.style = Paint.Style.FILL
        for (stp in r.stops) boltBadge(c, xAt(stp.km), ly, dp(10f), if (stp.idx <= live.at) DRIVEN else LIME)
        p.color = TEXT; c.drawCircle(tl.right, ly, dp(7f), p)
        p.color = ORANGE; c.drawCircle(xAt(doneKm), ly, dp(8f), p)
        text.typeface = Typeface.DEFAULT; text.textSize = dp(12f); text.color = MUTED
        c.drawText("0", tl.left, ly + dp(26f), text)
        text.textAlign = Paint.Align.RIGHT
        c.drawText(fmtDistance(r.totalKm, s), tl.right, ly + dp(26f), text)
        text.textAlign = Paint.Align.LEFT
    }

    /** A round badge with a lightning bolt. */
    private fun boltBadge(c: Canvas, x: Float, y: Float, r: Float, fill: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = BG; c.drawCircle(x, y, r + r * 0.28f, p)
        p.color = fill; c.drawCircle(x, y, r, p)
        val b = Path()
        val u = r / 10f
        b.moveTo(x + 1.5f * u, y - 6.5f * u); b.lineTo(x - 4f * u, y + 1f * u); b.lineTo(x - 0.2f * u, y + 1f * u)
        b.lineTo(x - 1.5f * u, y + 6.5f * u); b.lineTo(x + 4f * u, y - 1f * u); b.lineTo(x + 0.2f * u, y - 1f * u); b.close()
        p.color = BG; c.drawPath(b, p)
    }

    /** A rounded label centred on (x, y). */
    private fun pill(c: Canvas, label: String, x: Float, y: Float, size: Float, bg: Int, fg: Int, d: Float) {
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); color = fg; textAlign = Paint.Align.CENTER }
        val w = t.measureText(label) + 16f * d; val h = size + 10f * d
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
        c.drawRoundRect(RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2), h / 2, h / 2, p)
        c.drawText(label, x, y + size * 0.35f, t)
    }

    private fun ellipsize(s: String, p: Paint, width: Float): String {
        if (width <= 0f || p.measureText(s) <= width) return s
        var e = s
        while (e.isNotEmpty() && p.measureText("$e…") > width) e = e.dropLast(1)
        return "$e…"
    }
}
