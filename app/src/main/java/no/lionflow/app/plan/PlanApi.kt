package no.lionflow.app.plan

import no.lionflow.app.api.ApiException
import no.lionflow.app.api.Http
import no.lionflow.app.api.NobilApi
import no.lionflow.app.api.OsmChargers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

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

    /** The fastest driving route (OSRM). */
    suspend fun route(a: Place, b: Place, via: List<Place> = emptyList()): RouteData = routes(a, b, via).first()

    /**
     * The fastest driving route (OSRM) and, when there are no stops on the way, up to two alternatives,
     * with distance and time for every stretch and the car ferries.
     */
    suspend fun routes(a: Place, b: Place, via: List<Place> = emptyList()): List<RouteData> {
        val noRoute = "No road route found between these places"
        val way = (listOf(a) + via + listOf(b)).joinToString(";") { "${num(it.lon)},${num(it.lat)}" }
        // Servers differ in what they accept: ask for 2 alternatives, then 1, then none.
        val variants = if (via.isEmpty()) listOf("&alternatives=2", "&alternatives=true", "") else listOf("")
        var last: Exception? = null
        for (base in ROUTERS) {
            var done = false
            for (alt in variants) {
                try {
                    val url = base + "$way?overview=full&geometries=polyline6&annotations=distance,duration&steps=true$alt"
                    val j = Http.json(Request.Builder().url(url).header("User-Agent", Http.CONTACT_AGENT).get().build()) as? JSONObject
                    val rs = j?.let { PlanParse.routes(it) }.orEmpty().filter { it.totalKm > 0.05 }
                    if (rs.isNotEmpty()) return rs.take(3)
                    last = ApiException(0, noRoute); done = true; break
                } catch (e: ApiException) {
                    last = if (e.code == 400) ApiException(0, noRoute) else e
                    if (e.code != 400) break
                } catch (e: java.io.IOException) {
                    last = e; break
                }
            }
            if (done) break
        }
        throw last ?: ApiException(0, noRoute)
    }

    /**
     * The next departure of a car ferry at or after [atMs], from Entur's public timetable (Norway only);
     * null when the crossing isn't found or isn't in Norway.
     */
    suspend fun ferryDeparture(f: Ferry, atMs: Long): Long? {
        if (f.aLat !in 57.5..71.5 || f.aLon !in 4.0..31.5) return null
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        fun loc(la: Double, lo: Double) = JSONObject().put("coordinates", JSONObject().put("latitude", la).put("longitude", lo))
        val query = "query(\$from: Location!, \$to: Location!, \$dt: DateTime) { trip(from: \$from, to: \$to, dateTime: \$dt, numTripPatterns: 3, " +
            "modes: {accessMode: foot, egressMode: foot, transportModes: [{transportMode: water}]}) { tripPatterns { legs { mode expectedStartTime } } } }"
        val body = JSONObject().put("query", query).put("variables", JSONObject()
            .put("from", loc(f.aLat, f.aLon)).put("to", loc(f.bLat, f.bLon)).put("dt", fmt.format(java.util.Date(atMs))))
        val req = Request.Builder().url("https://api.entur.io/journey-planner/v3/graphql")
            .header("ET-Client-Name", "lionflow-app").header("User-Agent", Http.CONTACT_AGENT)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val j = Http.json(req) as? JSONObject ?: return null
        val pats = j.optJSONObject("data")?.optJSONObject("trip")?.optJSONArray("tripPatterns") ?: return null
        var best: Long? = null
        for (i in 0 until pats.length()) {
            val legs = pats.optJSONObject(i)?.optJSONArray("legs") ?: continue
            for (k in 0 until legs.length()) {
                val l = legs.optJSONObject(k) ?: continue
                if (l.optString("mode") != "water") continue
                val t = try { fmt.parse(l.optString("expectedStartTime"))?.time } catch (_: Exception) { null } ?: continue
                if (t >= atMs - 60_000L && (best == null || t < best!!)) best = t
                break
            }
        }
        return best
    }

    /** Fast chargers (with a CCS plug) near the route: ready-made map tiles first, the live search if those are not available. */
    suspend fun chargers(r: RouteData, nobilKey: String = ""): List<RawCharger> {
        // Nordic routes with a NOBIL key: NOBIL is quick and says how many charge points are free right now.
        if (nobilKey.isNotBlank() && nordic(r)) {
            try {
                val found = kotlinx.coroutines.withTimeout(20_000L) {
                    kotlinx.coroutines.coroutineScope {
                        boxes(r).map { b -> async { NobilApi.box(nobilKey, b[0], b[1], b[2], b[3]) } }.awaitAll().flatten()
                    }
                }
                if (found.isNotEmpty()) return found
            } catch (e: kotlinx.coroutines.CancellationException) { if (e is kotlinx.coroutines.TimeoutCancellationException) Unit else throw e } catch (e: Exception) { }
        }
        return (try { ChargerTiles.load(r) } catch (e: Exception) { null }) ?: live(r)
    }

    /** Fast chargers within about 1.5 km of one place (a stop on the way). NOBIL in the Nordics, else the map tiles, else Overpass. */
    suspend fun chargersNear(lat: Double, lon: Double, nobilKey: String = ""): List<RawCharger> {
        val dLat = 0.015; val dLon = 0.015 / Math.cos(Math.toRadians(lat)).coerceAtLeast(0.2)
        if (nobilKey.isNotBlank() && lat in 54.5..71.5 && lon in 4.0..32.0) {
            try {
                val found = kotlinx.coroutines.withTimeout(10_000L) { NobilApi.box(nobilKey, lat - dLat, lon - dLon, lat + dLat, lon + dLon) }
                if (found.isNotEmpty()) return found
            } catch (e: kotlinx.coroutines.CancellationException) { if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e } catch (e: Exception) { }
        }
        try { ChargerTiles.near(lat, lon, 1.6)?.let { return it } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
        val q = "[out:json][timeout:20];(" +
            "node[\"amenity\"=\"charging_station\"][\"socket:type2_combo\"](around:1600,${num(lat)},${num(lon)});" +
            "way[\"amenity\"=\"charging_station\"][\"socket:type2_combo\"](around:1600,${num(lat)},${num(lon)});" +
            ");out center 40;"
        return PlanParse.chargers(OsmChargers.raw(q))
    }

    /** Both ends of the route are in Norway, Sweden, Denmark or Finland. */
    private fun nordic(r: RouteData): Boolean {
        fun inside(la: Double, lo: Double) = la in 54.5..71.5 && lo in 4.0..32.0
        return r.size > 1 && inside(r.lat[0], r.lon[0]) && inside(r.lat[r.size - 1], r.lon[r.size - 1])
    }

    /** South, west, north, east of a box around each ~40 km of the route, with some room on every side. */
    private fun boxes(r: RouteData): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
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
            if (k < r.size) {
                if (r.lat[k] < s) s = r.lat[k]; if (r.lat[k] > n) n = r.lat[k]
                if (r.lon[k] < w) w = r.lon[k]; if (r.lon[k] > e) e = r.lon[k]
            }
            val padLat = 0.025
            val padLon = 0.025 / Math.cos(Math.toRadians((s + n) / 2)).coerceAtLeast(0.2)
            out.add(doubleArrayOf(s - padLat, w - padLon, n + padLat, e + padLon))
            i = k
        }
        return out
    }

    /** Fast chargers (with a CCS plug) within about 2 km of the route, asked of the free Overpass servers. */
    private suspend fun live(r: RouteData): List<RawCharger> {
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

/**
 * Fast-charger map tiles (2x2 degrees) built weekly by GitHub Actions from OpenStreetMap and served from the
 * repository's "chargers" branch. A route touches a handful of small files, which arrive in a second or two,
 * far quicker than asking the busy free Overpass servers.
 */
object ChargerTiles {
    private const val BASE = "https://raw.githubusercontent.com/thocubeat/lionflow/chargers/"
    private const val DAY = 24 * 3600 * 1000L
    private var covered: List<DoubleArray>? = null   // south, west, north, east of every area the tiles cover
    private var builtAt = 0L
    private val cache = HashMap<String, Pair<Long, List<RawCharger>>>()

    private fun tile(lat: Double, lon: Double) = "${Math.floor(lat / 2).toInt()}_${Math.floor(lon / 2).toInt()}"

    private suspend fun fetch(path: String): JSONObject? =
        try {
            Http.json(Request.Builder().url(BASE + path).header("User-Agent", Http.CONTACT_AGENT).get().build()) as? JSONObject
        } catch (e: ApiException) {
            if (e.code == 404) null else throw e
        }

    /** The chargers within [km] of a place, or null when the tiles don't cover it or can't be had. */
    suspend fun near(lat: Double, lon: Double, km: Double): List<RawCharger>? {
        val have = areas() ?: return null
        val dLat = km / 111.0; val dLon = km / (111.0 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        val ids = LinkedHashSet<String>()
        for (a in listOf(-dLat, dLat)) for (b in listOf(-dLon, dLon)) ids.add(tile(lat + a, lon + b))
        if (!ids.all { covers(have, it) }) return null
        val now = System.currentTimeMillis()
        for (id in ids.filter { i -> cache[i]?.let { now - it.first < 7 * DAY } != true }) {
            cache[id] = now to (fetch("t/$id.json")?.let { PlanParse.chargers(it) } ?: emptyList())
        }
        return ids.flatMap { cache[it]?.second.orEmpty() }.filter { Geo.km(lat, lon, it.lat, it.lon) <= km }.distinctBy { it.id }
    }

    private suspend fun areas(): List<DoubleArray>? {
        val now = System.currentTimeMillis()
        if (covered == null || now - builtAt > DAY) {
            val m = fetch("meta.json") ?: return null
            val arr = m.optJSONArray("covered") ?: return null
            covered = (0 until arr.length()).mapNotNull { i -> arr.optJSONArray(i)?.let { a -> DoubleArray(4) { a.optDouble(it) } } }
            builtAt = now
        }
        return covered
    }

    private fun covers(have: List<DoubleArray>, id: String): Boolean {
        val (ty, tx) = id.split("_").map { it.toInt() }
        val la = ty * 2 + 1.0; val lo = tx * 2 + 1.0
        return have.any { la >= it[0] && la <= it[2] && lo >= it[1] && lo <= it[3] }
    }

    /** The chargers near the route, or null when the tiles can't be had (then the live search is used). */
    suspend fun load(r: RouteData): List<RawCharger>? {
        val now = System.currentTimeMillis()
        if (covered == null || now - builtAt > DAY) {
            val m = fetch("meta.json") ?: return null
            val arr = m.optJSONArray("covered") ?: return null
            covered = (0 until arr.length()).mapNotNull { i -> arr.optJSONArray(i)?.let { a -> DoubleArray(4) { a.optDouble(it) } } }
            builtAt = now
        }
        val have = covered ?: return null
        val need = LinkedHashSet<String>()
        for (i in 0 until r.size) for (dy in listOf(-0.05, 0.05)) for (dx in listOf(-0.08, 0.08)) need.add(tile(r.lat[i] + dy, r.lon[i] + dx))
        // Every tile must lie inside an area the tiles cover; otherwise the live search is used.
        for (id in need) {
            val (ty, tx) = id.split("_").map { it.toInt() }
            val la = ty * 2 + 1.0; val lo = tx * 2 + 1.0
            if (have.none { la >= it[0] && la <= it[2] && lo >= it[1] && lo <= it[3] }) return null
        }
        val missing = need.filter { id -> cache[id]?.let { now - it.first < 7 * DAY } != true }
        if (missing.isNotEmpty()) {
            val loaded = kotlinx.coroutines.coroutineScope {
                missing.map { id -> async { id to fetch("t/$id.json") } }.awaitAll()
            }
            for ((id, j) in loaded) cache[id] = now to (j?.let { PlanParse.chargers(it) } ?: emptyList())
        }
        // Keep only what is near the road: mark small grid cells along the route (and their neighbours).
        val cells = HashSet<Long>()
        fun mark(la: Double, lo: Double) {
            val cy = Math.floor(la / 0.03).toLong(); val cx = Math.floor(lo / 0.06).toLong()
            for (a in -1..1) for (b in -1..1) cells.add((cy + a) * 1_000_000L + (cx + b))
        }
        for (i in 0 until r.size) {
            mark(r.lat[i], r.lon[i])
            if (i + 1 < r.size) {
                val steps = ((r.km[i + 1] - r.km[i]) / 2.0).toInt()
                for (k in 1..steps) {
                    val f = k.toDouble() / (steps + 1)
                    mark(r.lat[i] + (r.lat[i + 1] - r.lat[i]) * f, r.lon[i] + (r.lon[i + 1] - r.lon[i]) * f)
                }
            }
        }
        val out = ArrayList<RawCharger>()
        for (id in need) for (c in cache[id]?.second.orEmpty()) {
            val key = Math.floor(c.lat / 0.03).toLong() * 1_000_000L + Math.floor(c.lon / 0.06).toLong()
            if (key in cells) out.add(c)
        }
        return out
    }
}
