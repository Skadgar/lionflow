package no.lionflow.app.plan

import no.lionflow.app.api.ApiException
import no.lionflow.app.api.Http
import no.lionflow.app.api.OsmChargers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale

/** The free online services behind the trip planner. All are OpenStreetMap-based and need no key. */
object PlanApi {
    private fun num(v: Double) = String.format(Locale.US, "%.5f", v)

    /** Address search while typing (Photon). Results near [lat]/[lon] come first. */
    suspend fun search(q: String, lat: Double?, lon: Double?, lang: String): List<Place> {
        val url = "https://photon.komoot.io/api/".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("limit", "7")
        if (lat != null && lon != null) url.addQueryParameter("lat", num(lat)).addQueryParameter("lon", num(lon))
        if (lang in setOf("en", "de", "fr")) url.addQueryParameter("lang", lang)
        val j = Http.json(Request.Builder().url(url.build()).header("User-Agent", Http.CONTACT_AGENT).get().build()) as? JSONObject ?: return emptyList()
        return PlanParse.places(j)
    }

    /** The address at a position, or null. */
    suspend fun reverse(lat: Double, lon: Double): Place? {
        val url = "https://photon.komoot.io/reverse".toHttpUrl().newBuilder()
            .addQueryParameter("lat", num(lat)).addQueryParameter("lon", num(lon)).build()
        val j = Http.json(Request.Builder().url(url).header("User-Agent", Http.CONTACT_AGENT).get().build()) as? JSONObject ?: return null
        return PlanParse.places(j).firstOrNull()
    }

    private val ROUTERS = listOf(
        "https://routing.openstreetmap.de/routed-car/route/v1/driving/",
        "https://router.project-osrm.org/route/v1/driving/",
    )

    /** The fastest driving route (OSRM), with distance and time for every stretch. */
    suspend fun route(a: Place, b: Place): RouteData {
        var last: Exception? = null
        for (base in ROUTERS) {
            try {
                val url = base + "${num(a.lon)},${num(a.lat)};${num(b.lon)},${num(b.lat)}?overview=full&geometries=polyline6&annotations=distance,duration"
                val j = Http.json(Request.Builder().url(url).header("User-Agent", Http.CONTACT_AGENT).get().build()) as? JSONObject
                val r = j?.let { PlanParse.route(it) }
                if (r != null && r.totalKm > 0.05) return r
                last = ApiException(0, "No road route found between these places")
                break
            } catch (e: ApiException) {
                last = if (e.code == 400) ApiException(0, "No road route found between these places") else e
                if (e.code == 400) break
            } catch (e: java.io.IOException) {
                last = e
            }
        }
        throw last ?: ApiException(0, "No road route found between these places")
    }

    /** Fast chargers (with a CCS plug) within about 2 km of the route. */
    suspend fun chargers(r: RouteData): List<RawCharger> {
        // Boxes around each ~40 km of the route. A box search with a plug filter is answered far quicker than
        // "everything near this long line"; chargers too far from the road are dropped afterwards.
        val boxes = StringBuilder()
        var i = 0
        while (i < r.size - 1) {
            var s = 90.0; var n = -90.0; var w = 180.0; var e = -180.0
            val startKm = r.km[i]
            var k = i
            while (k < r.size && (r.km[k] - startKm <= 40.0 || k == i)) {
                if (r.lat[k] < s) s = r.lat[k]; if (r.lat[k] > n) n = r.lat[k]
                if (r.lon[k] < w) w = r.lon[k]; if (r.lon[k] > e) e = r.lon[k]
                k++
            }
            if (k < r.size) {   // reach to the first point of the next stretch, so no gap is left between boxes
                if (r.lat[k] < s) s = r.lat[k]; if (r.lat[k] > n) n = r.lat[k]
                if (r.lon[k] < w) w = r.lon[k]; if (r.lon[k] > e) e = r.lon[k]
            }
            val padLat = 0.025
            val padLon = 0.025 / Math.cos(Math.toRadians((s + n) / 2)).coerceAtLeast(0.2)
            val box = "(${num(s - padLat)},${num(w - padLon)},${num(n + padLat)},${num(e + padLon)})"
            boxes.append("node[\"amenity\"=\"charging_station\"][\"socket:type2_combo\"]$box;")
                .append("way[\"amenity\"=\"charging_station\"][\"socket:type2_combo\"]$box;")
                .append("node[\"amenity\"=\"charging_station\"][\"socket:tesla_supercharger_ccs\"]$box;")
            i = k
        }
        val q = "[out:json][timeout:25];($boxes);out center 2000;"
        return PlanParse.chargers(OsmChargers.raw(q))
    }
}
