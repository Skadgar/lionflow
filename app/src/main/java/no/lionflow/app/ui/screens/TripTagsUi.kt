package no.lionflow.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.lionflow.app.data.AppSettings
import no.lionflow.app.model.Trip
import no.lionflow.app.model.TripTag
import no.lionflow.app.model.TripTags
import no.lionflow.app.ui.AppViewModel
import no.lionflow.app.ui.UiState
import no.lionflow.app.ui.components.Divider
import no.lionflow.app.ui.components.LfCard
import no.lionflow.app.ui.components.LfIcon
import no.lionflow.app.ui.components.LfIcons
import no.lionflow.app.ui.components.Segmented
import no.lionflow.app.ui.components.Toggle
import no.lionflow.app.ui.fmtDistance
import no.lionflow.app.ui.l10n.L10n
import no.lionflow.app.ui.l10n.Text
import no.lionflow.app.ui.theme.LocalLf
import no.lionflow.app.ui.theme.Type
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The seven tag colours: red, orange, yellow, green, teal, blue, purple. */
val TagColors = listOf(
    Color(0xFFE5484D), Color(0xFFF08C3A), Color(0xFFF2C94C), Color(0xFF57B36A), Color(0xFF2BB3B0), Color(0xFF4C8DF6), Color(0xFF9B6BE8),
)
private val TagDark = Color(0xFF0D0E10)

fun tagColor(i: Int): Color = TagColors[i.coerceIn(0, TagColors.size - 1)]

/** Black or white, whichever is easier to read on the colour. */
fun onTag(bg: Color): Color {
    val l = bg.luminance()
    val white = 1.05f / (l + 0.05f)
    val dark = (l + 0.05f) / (TagDark.luminance() + 0.05f)
    return if (dark >= white) TagDark else Color.White
}

/** Text shown as written (tag names, dates, numbers): never translated. */
@Composable
private fun Raw(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) =
    androidx.compose.material3.Text(text, modifier = modifier, color = color, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable
fun TagChip(tag: TripTag, auto: Boolean = false, maxWidth: Dp = 120.dp, onClick: (() -> Unit)? = null) {
    val bg = tagColor(tag.color)
    var m = Modifier.widthIn(max = maxWidth).clip(CircleShape).background(bg)
    if (onClick != null) m = m.clickable(onClick = onClick)
    // A star marks a tag LionFlow put on by itself.
    Raw(if (auto) tag.name + " ✦" else tag.name, Type.tiny.copy(fontWeight = FontWeight.SemiBold), onTag(bg), m.padding(horizontal = 10.dp, vertical = 5.dp))
}

/** In a trip row: the trip's tag, or a small tag button when it has none. Opens the tag picker. */
@Composable
fun TripTagSlot(vm: AppViewModel, s: AppSettings, trip: Trip) {
    val c = LocalLf.current
    var open by remember { mutableStateOf(false) }
    val data = vm.tags.data   // read here so the row redraws when tags change
    val tag = data.marks[trip.id]?.tag?.let { id -> data.tags.firstOrNull { it.id == id } }
    if (tag != null) TagChip(tag, vm.tags.isAuto(trip.id), 96.dp) { open = true }
    else {
        val label = L10n.tr("Tag this trip")
        Box(Modifier.size(34.dp).clip(CircleShape).clickable { open = true }.semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
            LfIcon(LfIcons.Tag, c.icon, 18.dp)
        }
    }
    if (open) TagPicker(vm, s, trip) { open = false }
}

@Composable
private fun ColorDots(selected: Int, onPick: (Int) -> Unit) {
    val c = LocalLf.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TagColors.forEachIndexed { i, col ->
            Box(
                Modifier.size(34.dp).clip(CircleShape).border(2.dp, if (i == selected) c.text else Color.Transparent, CircleShape).padding(4.dp)
                    .clip(CircleShape).background(col).clickable { onPick(i) },
                contentAlignment = Alignment.Center,
            ) { if (i == selected) LfIcon(LfIcons.Check, onTag(col), 14.dp) }
        }
    }
}

@Composable
private fun tagFieldColors() = LocalLf.current.let { c ->
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = c.text, unfocusedTextColor = c.text, focusedBorderColor = c.accentInk, unfocusedBorderColor = c.line2,
        cursorColor = c.accentInk, focusedLabelColor = c.accentInk, unfocusedLabelColor = c.muted,
    )
}

/** Pick, make, change or delete tags for one trip. */
@Composable
fun TagPicker(vm: AppViewModel, s: AppSettings, trip: Trip, onDismiss: () -> Unit) {
    val c = LocalLf.current
    val data = vm.tags.data
    val current = data.marks[trip.id]?.tag?.takeIf { it.isNotEmpty() }
    var editing by remember { mutableStateOf<TripTag?>(null) }
    var adding by remember { mutableStateOf(data.tags.isEmpty()) }
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(data.tags.size % TagColors.size) }
    var confirmDelete by remember { mutableStateOf(false) }

    val ed = editing
    if (ed != null) {
        AlertDialog(
            onDismissRequest = { editing = null }, containerColor = c.card,
            title = { Text("Change tag", color = c.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(name, { name = it.take(TripTags.MAX_NAME) }, singleLine = true, label = { Text("Name") }, colors = tagFieldColors())
                    ColorDots(color) { color = it }
                    if (confirmDelete) Text("Delete this tag from all trips?", style = Type.small, color = c.danger)
                    Text(if (confirmDelete) "Yes, delete" else "Delete tag", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.danger,
                        modifier = Modifier.clickable { if (confirmDelete) { vm.tags.delete(ed.id); editing = null; confirmDelete = false } else confirmDelete = true }.padding(vertical = 6.dp))
                }
            },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.tags.edit(ed.id, name, color); editing = null }) { Text("Save", color = c.accentInk) } },
            dismissButton = { TextButton(onClick = { editing = null; confirmDelete = false }) { Text("Cancel", color = c.muted) } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.card,
        title = { Text("Tag this trip", color = c.text) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                data.tags.forEach { t ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { vm.tagTrip(trip, t.id); onDismiss() }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TagChip(t, maxWidth = 170.dp)
                        Spacer(Modifier.width(8.dp))
                        if (t.id == current) LfIcon(LfIcons.Check, c.accentInk, 18.dp)
                        Spacer(Modifier.weight(1f))
                        Text("Edit", style = Type.small, color = c.muted, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { name = t.name; color = t.color; editing = t }.padding(8.dp))
                    }
                }
                if (current != null) Text("Remove the tag from this trip", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.danger,
                    modifier = Modifier.clickable { vm.tagTrip(trip, null); onDismiss() }.padding(vertical = 6.dp))
                if (data.tags.isNotEmpty()) Divider()
                if (!adding) Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { adding = true; name = ""; color = data.tags.size % TagColors.size }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LfIcon(LfIcons.Plus, c.accentInk, 16.dp)
                    Text("New tag", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk)
                } else {
                    OutlinedTextField(name, { name = it.take(TripTags.MAX_NAME) }, singleLine = true, label = { Text("New tag") },
                        placeholder = { Text("Work, holiday, a client…", color = c.muted) }, colors = tagFieldColors(), modifier = Modifier.fillMaxWidth())
                    ColorDots(color) { color = it }
                    if (name.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TagChip(TripTag("", name.trim(), color), maxWidth = 150.dp)
                        Text("Add to this trip", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { vm.tagTrip(trip, vm.tags.create(name, color).id); onDismiss() }.padding(8.dp))
                    }
                }
                Divider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Tag similar trips automatically", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                        Text("A trip with about the same distance and average speed as one you tagged gets the same tag, marked ✦. You can always change it.", style = Type.tiny, color = c.muted)
                    }
                    Spacer(Modifier.width(10.dp))
                    Toggle(s.autoTagTrips, "Tag similar trips automatically") { vm.setAutoTag(it) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = c.muted) } },
    )
}

private fun dayStart(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private const val DAY = 86_400_000L

/** Driving split by tag over a period the user picks. */
@Composable
fun TagStats(vm: AppViewModel, ui: UiState, s: AppSettings) {
    val c = LocalLf.current
    val data = vm.tags.data
    if (data.tags.isEmpty()) {
        if (ui.trips.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LfIcon(LfIcons.Tag, c.muted, 16.dp)
            Text("Tip: tag your trips (work, holiday, a client…) to see statistics per tag.", style = Type.tiny, color = c.muted)
        }
        return
    }
    var from by remember { mutableStateOf(dayStart(System.currentTimeMillis()) - 29 * DAY) }
    var to by remember { mutableStateOf(dayStart(System.currentTimeMillis())) }
    var metric by remember { mutableStateOf("km") }
    var pick by remember { mutableStateOf(false) }
    LaunchedEffect(from, ui.tripsLoading) { vm.ensureTrips(((System.currentTimeMillis() - from) / DAY).toInt() + 2) }

    val trips = ui.trips.filter { (it.startMillis ?: 0L) in from until to + DAY }
    fun value(t: Trip): Double = when (metric) { "trips" -> 1.0; "time" -> (t.durationSec ?: 0.0) / 3600.0; else -> t.distanceKm }
    fun show(v: Double): String = when (metric) {
        "trips" -> "${v.toInt()}"
        "time" -> if (v >= 1) "${v.toInt()} h ${((v - v.toInt()) * 60).toInt()} min" else "${(v * 60).toInt()} min"
        else -> fmtDistance(v, s)
    }
    val groups = trips.groupBy { t -> data.marks[t.id]?.tag?.let { id -> data.tags.firstOrNull { it.id == id } } }
    val rows = (data.tags.map { it to groups[it].orEmpty() } + listOf(null to groups[null].orEmpty()))
        .map { (tag, list) -> Triple(tag, list.sumOf { value(it) }, list.size) }
        .filter { it.third > 0 || it.first != null }.sortedByDescending { it.second }
    val total = rows.sumOf { it.second }
    val fmt = SimpleDateFormat("d MMM yyyy", L10n.locale)

    LfCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Trip statistics", style = Type.label, color = c.text, modifier = Modifier.weight(1f))
            if (ui.tripsLoading) Text("Loading trips…", style = Type.tiny, color = c.muted)
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, c.line2, RoundedCornerShape(14.dp)).clickable { pick = true }.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LfIcon(LfIcons.Calendar, c.accentInk, 18.dp)
            Raw(fmt.format(Date(from)) + "  –  " + fmt.format(Date(to)), Type.body.copy(fontWeight = FontWeight.SemiBold), c.text, Modifier.weight(1f))
            Text("Change", style = Type.small, color = c.accentInk)
        }
        Segmented(listOf("km" to "Distance", "trips" to "Trips", "time" to "Time"), metric, Modifier.fillMaxWidth()) { metric = it }
        if (total <= 0) Text("No trips in this period.", style = Type.small, color = c.muted)
        else {
            // One bar split by tag, then a line per tag.
            Row(Modifier.fillMaxWidth().height(18.dp).clip(CircleShape).background(c.track)) {
                rows.filter { it.second > 0 }.forEach { (tag, v, _) ->
                    Box(Modifier.weight((v / total).toFloat().coerceAtLeast(0.004f)).height(18.dp).background(tag?.let { tagColor(it.color) } ?: c.bar))
                }
            }
            val max = rows.maxOf { it.second }.coerceAtLeast(1e-9)
            rows.forEach { (tag, v, n) ->
                val col = tag?.let { tagColor(it.color) } ?: c.bar
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (tag != null) TagChip(tag, maxWidth = 150.dp) else Text("No tag", style = Type.small, color = c.muted)
                        Spacer(Modifier.weight(1f))
                        if (metric != "trips") Text(if (n == 1) "1 trip" else "$n trips", style = Type.tiny, color = c.muted)
                        Raw(show(v), Type.label.copy(fontSize = 14.sp), c.text)
                        Raw(String.format(Locale.US, "%.0f %%", v / total * 100), Type.tiny, c.muted, Modifier.width(38.dp))
                    }
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.track)) {
                        Box(Modifier.fillMaxWidth((v / max).toFloat().coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(col))
                    }
                }
            }
        }
    }
    if (pick) RangePicker(from, to, onDismiss = { pick = false }) { a, b -> from = a; to = b; pick = false }
}

/** A month calendar: tap the first day, then the last. */
@Composable
private fun RangePicker(from: Long, to: Long, onDismiss: () -> Unit, onDone: (Long, Long) -> Unit) {
    val c = LocalLf.current
    val today = dayStart(System.currentTimeMillis())
    var start by remember { mutableStateOf<Long?>(from) }
    var end by remember { mutableStateOf<Long?>(to) }
    var month by remember { mutableStateOf(Calendar.getInstance().apply { timeInMillis = to; set(Calendar.DAY_OF_MONTH, 1) }.let { dayStart(it.timeInMillis) }) }
    fun shift(n: Int) { month = Calendar.getInstance().apply { timeInMillis = month; add(Calendar.MONTH, n) }.timeInMillis }
    fun show(a: Long, b: Long) { start = a; end = b; month = dayStart(Calendar.getInstance().apply { timeInMillis = b; set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis) }
    val cal = Calendar.getInstance(L10n.locale).apply { timeInMillis = month }
    val first = cal.firstDayOfWeek
    val lead = (cal.get(Calendar.DAY_OF_WEEK) - first + 7) % 7
    val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val nextAllowed = Calendar.getInstance().apply { timeInMillis = month; add(Calendar.MONTH, 1) }.timeInMillis <= today
    val dayFmt = SimpleDateFormat("d MMM", L10n.locale)

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.card,
        title = { Text("Choose period", color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    @Composable fun quick(label: String, a: Long) = Text(label, style = Type.tiny.copy(fontWeight = FontWeight.SemiBold), color = c.chipText,
                        modifier = Modifier.clip(CircleShape).background(c.raised).clickable { show(a, today) }.padding(horizontal = 12.dp, vertical = 8.dp))
                    quick("7 days", today - 6 * DAY)
                    quick("30 days", today - 29 * DAY)
                    quick("This month", dayStart(Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis))
                    quick("This year", dayStart(Calendar.getInstance().apply { set(Calendar.DAY_OF_YEAR, 1) }.timeInMillis))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).clickable { shift(-1) }, contentAlignment = Alignment.Center) { LfIcon(LfIcons.Back, c.text2, 18.dp) }
                    Raw(SimpleDateFormat("LLLL yyyy", L10n.locale).format(Date(month)).replaceFirstChar { it.uppercase() }, Type.label, c.text,
                        Modifier.weight(1f).padding(horizontal = 8.dp))
                    Box(Modifier.size(40.dp).clip(CircleShape).clickable(enabled = nextAllowed) { shift(1) }, contentAlignment = Alignment.Center) { LfIcon(LfIcons.Chevron, if (nextAllowed) c.text2 else c.line2, 18.dp) }
                }
                Row(Modifier.fillMaxWidth()) {
                    val wf = SimpleDateFormat("EEE", L10n.locale)
                    for (i in 0 until 7) {
                        val d = Calendar.getInstance().apply { set(Calendar.DAY_OF_WEEK, (first - 1 + i) % 7 + 1) }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Raw(wf.format(d.time).take(2), Type.tiny.copy(fontSize = 11.sp), c.muted) }
                    }
                }
                val a = start; val b = end ?: start
                for (week in 0 until (lead + days + 6) / 7) {
                    Row(Modifier.fillMaxWidth()) {
                        for (col in 0 until 7) {
                            val n = week * 7 + col - lead + 1
                            if (n < 1 || n > days) { Box(Modifier.weight(1f).height(40.dp)); continue }
                            val day = dayStart(Calendar.getInstance().apply { timeInMillis = month; set(Calendar.DAY_OF_MONTH, n) }.timeInMillis)
                            val future = day > today
                            val edge = day == a || day == b
                            val inside = a != null && b != null && day in a..b
                            // The band between the two days runs edge to edge; the two ends are filled circles.
                            val band = when {
                                !inside || a == b -> Modifier
                                day == a -> Modifier.padding(start = 20.dp).background(c.raised)
                                day == b -> Modifier.padding(end = 20.dp).background(c.raised)
                                else -> Modifier.background(c.raised)
                            }
                            Box(Modifier.weight(1f).height(40.dp).padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
                                Box(Modifier.fillMaxWidth().height(36.dp).then(band))
                                Box(
                                    Modifier.size(36.dp).clip(CircleShape).background(if (edge) c.accent else Color.Transparent)
                                        .then(if (day == today && !edge) Modifier.border(1.dp, c.line2, CircleShape) else Modifier)
                                        .clickable(enabled = !future) {
                                            val s0 = start
                                            if (s0 == null || end != null) { start = day; end = null }
                                            else if (day < s0) { start = day; end = s0 } else end = day
                                        },
                                    contentAlignment = Alignment.Center,
                                ) { Raw("$n", Type.small.copy(fontWeight = if (edge) FontWeight.SemiBold else FontWeight.Normal), if (edge) c.onAccent else if (future) c.line2 else c.text) }
                            }
                        }
                    }
                }
                val a2 = start
                if (a2 == null) Text("Tap the first day.", style = Type.tiny, color = c.muted)
                else if (end == null) Text("Now tap the last day.", style = Type.tiny, color = c.muted)
                else Raw(dayFmt.format(Date(a2)) + "  –  " + dayFmt.format(Date(end!!)) + "  ·  " + L10n.tr("${((end!! - a2) / DAY + 1)} days"), Type.tiny, c.muted)
            }
        },
        confirmButton = { TextButton(enabled = start != null, onClick = { val a = start ?: return@TextButton; onDone(a, end ?: a) }) { Text("Done", color = c.accentInk) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.muted) } },
    )
}
