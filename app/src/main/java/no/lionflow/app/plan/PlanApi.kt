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
        // The route as a line with a point about every 2 km (at most ~450 points).
        val step = maxOf(2.0, r.totalKm / 450.0)
        val sb = StringBuilder()
        var next = 0.0
        for (i in 0 until r.size) {
            if (r.km[i] >= next || i == r.size - 1) {
                if (sb.isNotEmpty()) sb.append(',')
                sb.append(num(r.lat[i])).append(',').append(num(r.lon[i]))
                next = r.km[i] + step
            }
        }
        val around = "(around:2000,$sb)"
        val q = "[out:json][timeout:40];(" +
            "node[\"amenity\"=\"charging_station\"][\"socket:type2_combo\"]$around;" +
            "way[\"amenity\"=\"charging_station\"][\"socket:type2_combo\"]$around;" +
            "node[\"amenity\"=\"charging_station\"][\"socket:tesla_supercharger_ccs\"]$around;" +
            ");out center 900;"
        return PlanParse.chargers(OsmChargers.raw(q))
    }
}
