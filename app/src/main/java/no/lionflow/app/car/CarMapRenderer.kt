package no.lionflow.app.car

import android.Manifest
import android.annotation.SuppressLint
import android.app.Presentation
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import no.lionflow.app.R
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * LionFlow's map on the driver display (cluster): dark OpenFreeMap tiles in 3D, following the car,
 * with a small 3D car model (drawn as extruded shapes) instead of the usual position arrow.
 * It's rendered into a private VirtualDisplay at the cluster's own size and density.
 */
class CarMapRenderer(
    private val carContext: CarContext,
    owner: LifecycleOwner,
) : SurfaceCallback, DefaultLifecycleObserver {

    private val main = Handler(Looper.getMainLooper())
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var visible = Rect()
    private var heading = 0.0
    private var running = false

    init { owner.lifecycle.addObserver(this) }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(androidx.car.app.AppManager::class.java).setSurfaceCallback(this)
    }

    override fun onDestroy(owner: LifecycleOwner) { tearDown() }

    override fun onSurfaceAvailable(container: SurfaceContainer) { main.post { setUp(container) } }
    override fun onSurfaceDestroyed(container: SurfaceContainer) { main.post { tearDown() } }
    override fun onVisibleAreaChanged(visibleArea: Rect) { main.post { visible = Rect(visibleArea); applyPadding() } }
    override fun onStableAreaChanged(stableArea: Rect) { main.post { if (visible.isEmpty) { visible = Rect(stableArea); applyPadding() } } }

    private fun setUp(c: SurfaceContainer) {
        tearDown()
        val surface = c.surface ?: return
        if (c.width <= 0 || c.height <= 0) return
        MapLibre.getInstance(carContext.applicationContext)
        val vd = carContext.getSystemService(DisplayManager::class.java).createVirtualDisplay(
            "LionFlowCluster", c.width, c.height, c.dpi, surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
        display = vd
        val p = Presentation(carContext, vd.display)
        val ctx = p.context
        val mv = MapView(ctx, MapLibreMapOptions.createFromAttributes(ctx).textureMode(false)
            .logoEnabled(false).compassEnabled(false).attributionEnabled(false))
        p.setContentView(mv)
        p.show()
        presentation = p
        mapView = mv
        if (visible.isEmpty) visible = Rect(0, 0, c.width, c.height)
        mv.onCreate(null); mv.onStart(); mv.onResume()
        mv.getMapAsync { m ->
            map = m
            m.uiSettings.isAttributionEnabled = false
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isCompassEnabled = false
            val start = CarHub.status.value?.let { st -> st.lat?.let { la -> st.lon?.let { lo -> LatLng(la, lo) } } } ?: LatLng(59.91, 10.75)
            m.cameraPosition = CameraPosition.Builder().target(start).zoom(17.0).tilt(55.0).build()
            m.setStyle(Style.Builder().fromUri(STYLE_DARK)) { st ->
                style = st
                add3dBuildings(st)
                addCar(st)
                enableLocation(m, st)
                applyPadding()
                running = true
                main.post(tick)
            }
        }
    }

    private fun tearDown() {
        running = false
        main.removeCallbacks(tick)
        val mv = mapView
        mapView = null; map = null; style = null
        try { mv?.onPause(); mv?.onStop(); mv?.onDestroy() } catch (_: Exception) { }
        try { presentation?.dismiss() } catch (_: Exception) { }
        presentation = null
        display?.release(); display = null
    }

    @SuppressLint("MissingPermission")
    private fun enableLocation(m: MapLibreMap, st: Style) {
        val ok = ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!ok) return   // no phone GPS: the map stays on the car's last reported position
        try {
            val ctx = presentation?.context ?: carContext
            // The built-in arrow is made invisible; our 3D car is drawn instead.
            val opts = LocationComponentOptions.builder(ctx)
                .foregroundDrawable(R.drawable.ic_blank).backgroundDrawable(R.drawable.ic_blank)
                .bearingDrawable(R.drawable.ic_blank).gpsDrawable(R.drawable.ic_blank)
                .foregroundDrawableStale(R.drawable.ic_blank).backgroundDrawableStale(R.drawable.ic_blank)
                .accuracyAlpha(0f).pulseEnabled(false)
                .build()
            val lc = m.locationComponent
            lc.activateLocationComponent(
                LocationComponentActivationOptions.builder(ctx, st)
                    .locationComponentOptions(opts)
                    .useDefaultLocationEngine(true)
                    .locationEngineRequest(LocationEngineRequest.Builder(1000)
                        .setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY).setFastestInterval(500).build())
                    .build()
            )
            lc.isLocationComponentEnabled = true
            lc.renderMode = RenderMode.GPS
            lc.cameraMode = CameraMode.TRACKING_GPS
            lc.zoomWhileTracking(17.0)
            lc.tiltWhileTracking(55.0)
        } catch (_: Exception) { }
    }

    /** Keeps the car in the free part of the screen and a bit low, like a sat-nav. */
    private fun applyPadding() {
        val m = map ?: return
        val w = mapView?.width?.takeIf { it > 0 } ?: return
        val h = mapView?.height?.takeIf { it > 0 } ?: return
        val v = if (visible.isEmpty) Rect(0, 0, w, h) else visible
        m.moveCamera(CameraUpdateFactory.paddingTo(
            v.left.toDouble(), (v.top + v.height() * 0.30).toInt().toDouble(), (w - v.right).coerceAtLeast(0).toDouble(), (h - v.bottom).coerceAtLeast(0).toDouble()))
    }

    private fun add3dBuildings(st: Style) {
        val src = listOf("openmaptiles", "composite").firstOrNull { st.getSource(it) != null } ?: return
        st.addLayer(FillExtrusionLayer("lf-3d", src).apply {
            sourceLayer = "building"
            setProperties(
                PropertyFactory.fillExtrusionColor(Color.parseColor("#2A2E35")),
                PropertyFactory.fillExtrusionHeight(Expression.coalesce(Expression.get("render_height"), Expression.literal(8))),
                PropertyFactory.fillExtrusionBase(Expression.coalesce(Expression.get("render_min_height"), Expression.literal(0))),
                PropertyFactory.fillExtrusionOpacity(0.85f),
            )
        })
    }

    // ---------------- the 3D car ----------------

    private fun addCar(st: Style) {
        st.addSource(GeoJsonSource(CAR_SRC, FeatureCollection.fromFeatures(emptyList())))
        st.addLayer(FillExtrusionLayer("lf-car-3d", CAR_SRC).withProperties(
            PropertyFactory.fillExtrusionColor(Expression.get("c")),
            PropertyFactory.fillExtrusionHeight(Expression.get("h1")),
            PropertyFactory.fillExtrusionBase(Expression.get("h0")),
            PropertyFactory.fillExtrusionOpacity(1f),
            PropertyFactory.fillExtrusionVerticalGradient(true),
        ))
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try { updateCar() } catch (_: Exception) { }
            main.postDelayed(this, 120)
        }
    }

    private fun updateCar() {
        val m = map ?: return
        val st = style ?: return
        val loc = try { m.locationComponent.takeIf { it.isLocationComponentActivated }?.lastKnownLocation } catch (_: Exception) { null }
        val s = CarHub.status.value
        val lat: Double; val lon: Double
        if (loc != null) {
            lat = loc.latitude; lon = loc.longitude
            if (loc.hasBearing() && loc.speed > 0.8f) heading = loc.bearing.toDouble()
        } else if (s?.lat != null && s.lon != null) {
            lat = s.lat; lon = s.lon
        } else return
        // Keep the car about 44 px long whatever the zoom: readable, but not out of proportion to the houses.
        val mpp = 78271.51696 * cos(Math.toRadians(lat)) / 2.0.pow(m.cameraPosition.zoom)
        val scale = (44 * mpp / 4.4).coerceIn(0.5, 80.0)
        val feats = carFeatures(lat, lon, heading, scale)
        st.getSourceAs<GeoJsonSource>(CAR_SRC)?.setGeoJson(FeatureCollection.fromFeatures(feats))
    }

    private class Part(val pts: List<Pair<Double, Double>>, val h0: Double, val h1: Double, val color: String)

    private fun carFeatures(lat: Double, lon: Double, headingDeg: Double, scale: Double): List<Feature> {
        val th = Math.toRadians(headingDeg)
        val mLat = 111_320.0
        val mLon = 111_320.0 * cos(Math.toRadians(lat))
        fun pt(x: Double, y: Double): org.maplibre.geojson.Point {
            val e = (x * cos(th) + y * sin(th)) * scale
            val n = (-x * sin(th) + y * cos(th)) * scale
            return Point.fromLngLat(lon + e / mLon, lat + n / mLat)
        }
        fun box(x0: Double, x1: Double, y0: Double, y1: Double) = listOf(x0 to y0, x1 to y0, x1 to y1, x0 to y1)
        val parts = listOf(
            // wheels (a little outside the body)
            Part(box(-1.02, -0.78, 1.1, 1.9), 0.0, 0.5, "#0B0B0C"), Part(box(0.78, 1.02, 1.1, 1.9), 0.0, 0.5, "#0B0B0C"),
            Part(box(-1.02, -0.78, -1.9, -1.1), 0.0, 0.5, "#0B0B0C"), Part(box(0.78, 1.02, -1.9, -1.1), 0.0, 0.5, "#0B0B0C"),
            // lower body with a rounded nose and tail
            Part(listOf(-0.9 to -1.95, -0.65 to -2.2, 0.65 to -2.2, 0.9 to -1.95, 0.9 to 1.6, 0.6 to 2.2, -0.6 to 2.2, -0.9 to 1.6), 0.3, 0.8, "#E6E9ED"),
            // roof / glass: shorter and narrower than the body, so it reads as a car and not a van
            Part(listOf(-0.72 to -1.5, 0.72 to -1.5, 0.66 to 0.55, -0.66 to 0.55), 0.8, 1.35, "#7B8FA5"),
            // lights
            Part(box(-0.6, -0.2, 2.2, 2.3), 0.5, 0.65, "#FFF3B0"), Part(box(0.2, 0.6, 2.2, 2.3), 0.5, 0.65, "#FFF3B0"),
            Part(box(-0.75, -0.3, -2.3, -2.2), 0.5, 0.65, "#FF3B30"), Part(box(0.3, 0.75, -2.3, -2.2), 0.5, 0.65, "#FF3B30"),
        )
        return parts.map { part ->
            val ring = part.pts.map { (x, y) -> pt(x, y) }.let { it + it.first() }
            Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))).apply {
                addStringProperty("c", part.color)
                addNumberProperty("h0", part.h0 * scale)
                addNumberProperty("h1", part.h1 * scale)
            }
        }
    }

    companion object {
        const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
        private const val CAR_SRC = "lf-car-3d-src"
    }
}
