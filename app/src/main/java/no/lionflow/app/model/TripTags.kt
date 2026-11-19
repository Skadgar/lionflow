package no.lionflow.app.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** A label the user puts on trips ("Work", "Client A", "Holiday"). [color] is an index into the 7 tag colours. */
data class TripTag(val id: String, val name: String, val color: Int)

/**
 * What a trip is tagged with. [tag] is empty when the user removed the tag on purpose (then it is never
 * tagged automatically again). [km] and [speed] are kept so later trips on the same route can be recognised.
 */
data class TagMark(val tag: String, val auto: Boolean, val km: Double, val speed: Double?, val at: Long)

data class TagData(val tags: List<TripTag> = emptyList(), val marks: Map<String, TagMark> = emptyMap())

/** The user's trip tags, kept on the phone only. */
class TripTags(load: () -> String, private val save: (String) -> Unit) {
    var data by mutableStateOf(parse(load()))
        private set

    fun tagOf(tripId: String): TripTag? = data.marks[tripId]?.tag?.takeIf { it.isNotEmpty() }?.let { id -> data.tags.firstOrNull { it.id == id } }
    fun isAuto(tripId: String): Boolean = data.marks[tripId]?.auto == true
    fun byName(name: String): TripTag? = data.tags.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    fun create(name: String, color: Int): TripTag {
        byName(name)?.let { return it }
        val t = TripTag("t" + System.currentTimeMillis().toString(36) + data.tags.size, name.trim().take(MAX_NAME), color.coerceIn(0, COLORS - 1))
        put(data.copy(tags = data.tags + t))
        return t
    }

    fun edit(id: String, name: String, color: Int) =
        put(data.copy(tags = data.tags.map { if (it.id == id) it.copy(name = name.trim().take(MAX_NAME), color = color.coerceIn(0, COLORS - 1)) else it }))

    /** Removes the tag everywhere. */
    fun delete(id: String) = put(TagData(data.tags.filter { it.id != id }, data.marks.filterValues { it.tag != id }))

    /** The user's own choice for a trip; null takes the tag off. */
    fun set(trip: Trip, tagId: String?) {
        val mark = TagMark(tagId.orEmpty(), false, trip.distanceKm, speedOf(trip), trip.startMillis ?: System.currentTimeMillis())
        // A trip that never had a tag just goes back to having none.
        val marks = if (tagId == null && data.marks[trip.id]?.auto != true) data.marks - trip.id else data.marks + (trip.id to mark)
        put(data.copy(marks = marks))
    }

    /**
     * Tags trips that look like a trip the user tagged before: about the same distance and average speed.
     * Trips the user has already decided on are left alone. Returns how many were tagged.
     */
    fun autoTag(trips: List<Trip>): Int {
        val known = data.marks.values.filter { !it.auto && it.tag.isNotEmpty() && it.speed != null && data.tags.any { t -> t.id == it.tag } }
        if (known.isEmpty()) return 0
        val marks = data.marks.toMutableMap()
        var n = 0
        for (t in trips) {
            if (marks.containsKey(t.id)) continue
            val speed = speedOf(t) ?: continue
            val best = known.mapNotNull { k -> likeness(t.distanceKm, speed, k)?.let { it to k } }.minByOrNull { it.first } ?: continue
            marks[t.id] = TagMark(best.second.tag, true, t.distanceKm, speed, t.startMillis ?: System.currentTimeMillis())
            n++
        }
        if (n > 0) put(data.copy(marks = marks))
        return n
    }

    /** Takes off every tag that was put on automatically (used when the feature is turned off). */
    fun clearAuto() = put(data.copy(marks = data.marks.filterValues { !it.auto }))

    private fun put(d: TagData) {
        // Keep the newest marks only, so this never grows without end.
        val marks = if (d.marks.size > MAX_MARKS) d.marks.entries.sortedByDescending { it.value.at }.take(MAX_MARKS).associate { it.key to it.value } else d.marks
        data = d.copy(marks = marks)
        save(toJson(data))
    }

    companion object {
        const val COLORS = 7
        const val MAX_NAME = 24
        private const val MAX_MARKS = 3000
        /** How far apart two drives of the same route can be. */
        const val SPEED_TOL = 8.0
        fun kmTol(km: Double): Double = (km * 0.1).coerceIn(0.5, 2.0)

        /** Average speed from distance and time (the car's own figure isn't reliable on every model). */
        fun speedOf(t: Trip): Double? =
            t.durationSec?.takeIf { it >= 60 && t.distanceKm > 0 }?.let { t.distanceKm / (it / 3600.0) } ?: t.avgSpeedKmh?.takeIf { it > 0 }

        /** Lower is more alike; null when the trip is not the same route. */
        fun likeness(km: Double, speed: Double, k: TagMark): Double? {
            val ks = k.speed ?: return null
            val dk = abs(km - k.km) / kmTol(k.km)
            val ds = abs(speed - ks) / SPEED_TOL
            return if (dk <= 1.0 && ds <= 1.0) dk + ds else null
        }

        fun parse(text: String): TagData = try {
            val j = JSONObject(text.ifBlank { "{}" })
            val tags = j.optJSONArray("tags")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
                .map { TripTag(it.optString("id"), it.optString("name"), it.optInt("color")) }.filter { it.id.isNotEmpty() && it.name.isNotEmpty() }
            val m = j.optJSONObject("marks")
            val marks = HashMap<String, TagMark>()
            m?.keys()?.forEach { id ->
                val o = m.optJSONObject(id) ?: return@forEach
                marks[id] = TagMark(o.optString("tag"), o.optBoolean("auto"), o.optDouble("km", 0.0), if (o.has("speed")) o.optDouble("speed") else null, o.optLong("at"))
            }
            TagData(tags, marks)
        } catch (_: Exception) { TagData() }

        fun toJson(d: TagData): String {
            val tags = JSONArray()
            d.tags.forEach { tags.put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.color)) }
            val marks = JSONObject()
            d.marks.forEach { (id, k) ->
                val o = JSONObject().put("tag", k.tag).put("auto", k.auto).put("km", k.km).put("at", k.at)
                if (k.speed != null) o.put("speed", k.speed)
                marks.put(id, o)
            }
            return JSONObject().put("tags", tags).put("marks", marks).toString()
        }
    }
}
