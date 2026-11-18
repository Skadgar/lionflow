package no.lionflow.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.lionflow.app.data.AppSettings
import no.lionflow.app.plan.Place
import no.lionflow.app.plan.PlanCharger
import no.lionflow.app.plan.Planner
import no.lionflow.app.plan.Stop
import no.lionflow.app.ui.Busy
import no.lionflow.app.ui.LocalPlatform
import no.lionflow.app.ui.UiState
import no.lionflow.app.ui.components.LfIcon
import no.lionflow.app.ui.components.LfIcons
import no.lionflow.app.ui.components.PrimaryButton
import no.lionflow.app.ui.fmtDistance
import no.lionflow.app.ui.l10n.L10n
import no.lionflow.app.ui.l10n.Text
import no.lionflow.app.ui.l10n.TimeFmt
import no.lionflow.app.ui.theme.LocalLf
import no.lionflow.app.ui.theme.Type
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

// ---------------- small helpers ----------------

private fun strokeIcon(path: String, filled: Boolean = false): ImageVector =
    ImageVector.Builder("plan", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes(path),
            fill = if (filled) SolidColor(Color.Black) else null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()

private const val STAR = "M12 3.5l2.6 5.4 5.9.8-4.3 4.1 1 5.9-5.2-2.8-5.2 2.8 1-5.9-4.3-4.1 5.9-.8z"
private val IconStar = strokeIcon(STAR)
private val IconStarOn = strokeIcon(STAR, filled = true)
private val IconSwap = strokeIcon("M7 4v16M7 4L3.5 7.5M7 4l3.5 3.5M17 20V4M17 20l-3.5-3.5M17 20l3.5-3.5")

/** Names and other data are shown as they are, never run through the translations. */
@Composable
private fun Plain(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, maxLines: Int = 1, align: TextAlign? = null) =
    androidx.compose.material3.Text(text, modifier = modifier, color = color, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = align)

/** Keeps touches on a panel from reaching the map under it. */
fun Modifier.blockTouches(): Modifier = this.pointerInput(Unit) { detectTapGestures { } }

fun placeName(p: Place): String = if (p.detail.isEmpty() && (p.name == "Car's position" || p.name == "My position")) L10n.tr(p.name) else p.name

private fun viaTag(stay: Int): String = if (stay > 0) L10n.tr("Via") + " · $stay min" else L10n.tr("Via")

private fun dur(minutes: Double): String {
    val m = minutes.roundToInt().coerceAtLeast(0)
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

private fun money(v: Double, cur: String): String =
    if (cur == "€" || cur == "£" || cur == "CHF") String.format(Locale.US, "%.2f %s", v, cur) else "${v.roundToInt()} $cur"

private fun perKwh(c: PlanCharger): String? = c.price?.let { String.format(Locale.US, "%.2f %s/kWh", it, c.currency) }

private fun chargerLine(c: PlanCharger, s: AppSettings, price: Boolean = true): String = listOfNotNull(
    c.operator.takeIf { it.isNotBlank() && !c.name.contains(it, ignoreCase = true) },
    if (c.kw > 0) "${c.kw.roundToInt()} kW" else null,
    c.free?.let { f -> if (c.points >= f && c.points > 0) L10n.tr("$f of ${c.points} free") else L10n.tr("$f free") },
    fmtDistance(c.km, s),
    if (price) perKwh(c) else null,
).joinToString(" · ")

// ---------------- the form: addresses and battery numbers ----------------

@Composable
fun PlanForm(p: Planner, ui: UiState, s: AppSettings) {
    val c = LocalLf.current
    val focus = LocalFocusManager.current
    // The address being typed: 0 = from, 1 = to, 2.. = a stop on the way, -1 = none.
    var field by remember { mutableStateOf(if (p.to == null) 1 else -1) }
    var query by remember { mutableStateOf("") }
    val requester = remember { FocusRequester() }
    LaunchedEffect(field) { if (field >= 0) try { requester.requestFocus() } catch (_: Exception) { } }

    fun open(which: Int) { field = which; query = ""; p.clearSearch() }
    fun done() { query = ""; p.clearSearch(); field = if (p.to == null) 1 else -1; if (field < 0) focus.clearFocus() }
    fun pick(place: Place?) { if (field == 0) p.from = place else if (field == 1) p.to = place else p.setVia(field - 2, place); done() }

    val carHere = ui.status?.lat != null
    val fromText = p.from?.name ?: L10n.tr(if (carHere) "Car's position" else "My position")

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AddressRow(LfIcons.Car, c.orangeInk, "From", fromText, field == 0, query, requester, { query = it; p.search(it) }, { open(0) }, { done() })
                p.vias.forEachIndexed { i, v ->
                    AddressRow(LfIcons.Pin, c.muted, "Via", v?.name, field == i + 2, query, requester, { query = it; p.search(it) }, { open(i + 2) },
                        { if (v == null) p.removeVia(i); done() }, onRemove = { p.removeVia(i); done() })
                }
                AddressRow(LfIcons.Pin, c.accentInk, "Where to?", p.to?.name, field == 1, query, requester, { query = it; p.search(it) }, { open(1) }, { if (p.to != null) done() else { query = ""; p.clearSearch() } })
                if (field < 0 && p.vias.size < 3) Text(
                    "Add a stop on the way", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk,
                    modifier = Modifier.clickable(role = Role.Button) { p.addVia(); open(p.vias.size + 1) }.padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(c.raised).clickable(role = Role.Button) { p.swapEnds() }
                    .semantics { contentDescription = L10n.tr("Swap start and destination") },
                contentAlignment = Alignment.Center,
            ) { LfIcon(IconSwap, c.text2, 18.dp) }
        }

        if (field >= 0) {
            val q = query.trim()
            val rows = if (q.length < 2) p.saved + p.recent.filterNot { r -> p.saved.any { it.name == r.name && it.detail == r.detail } }
            else (p.saved.filter { it.name.contains(q, true) || it.detail.contains(q, true) } + p.results).distinctBy { it.name + "|" + it.detail }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(16.dp))) {
                if (field == 0 && q.length < 2) SuggestRow(LfIcons.Car, c.orangeInk, L10n.tr(if (carHere) "Car's position" else "My position"), "", null, { }) { pick(null) }
                for (place in rows.take(8)) {
                    val saved = p.isSaved(place)
                    val recentOnly = !saved && q.length < 2 && p.recent.any { it.name == place.name && it.detail == place.detail }
                    SuggestRow(LfIcons.Pin, c.muted, place.name, place.detail, saved, { p.toggleSaved(place) }, if (recentOnly) ({ p.removeRecent(place) }) else null) { pick(place) }
                }
                val note = when {
                    q.length >= 2 && rows.isEmpty() && p.searching -> "Searching…"
                    q.length >= 2 && rows.isEmpty() -> "No places found"
                    q.length < 2 && rows.isEmpty() -> "Type an address or a place. Tap the star to save one."
                    else -> null
                }
                if (note != null) Text(note, style = Type.small, color = c.muted, modifier = Modifier.padding(14.dp))
            }
        } else {
            ViaStays(p)
            BatteryOptions(p)
            PrimaryButton(if (p.plan != null) "Plan again" else "Plan trip", Modifier.fillMaxWidth(), icon = LfIcons.Route, enabled = p.to != null && p.busy == null) { p.go() }
        }
        p.busy?.let { msg ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { Busy(); Text(msg, style = Type.small, color = c.text2) }
        }
        p.error?.let { Text(it, style = Type.small, color = c.danger) }
    }
}

@Composable
private fun AddressRow(
    icon: ImageVector, tint: Color, hint: String, value: String?, active: Boolean, query: String, requester: FocusRequester,
    onQuery: (String) -> Unit, onOpen: () -> Unit, onClose: () -> Unit, onRemove: (() -> Unit)? = null,
) {
    val c = LocalLf.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(c.raised).border(1.dp, if (active) c.accentInk else Color.Transparent, shape)
            .clickable(enabled = !active, role = Role.Button, onClick = onOpen).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LfIcon(icon, tint, 18.dp)
        if (active) {
            BasicTextField(
                value = query, onValueChange = { onQuery(it.take(80)) }, singleLine = true,
                textStyle = Type.body.copy(color = c.text), cursorBrush = SolidColor(c.accentInk),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.weight(1f).focusRequester(requester),
                decorationBox = { inner -> Box { if (query.isEmpty()) Plain(value ?: L10n.tr(hint), Type.body, c.muted); inner() } },
            )
            LfIcon(LfIcons.Close, c.muted, 18.dp, "Close", Modifier.clickable(onClick = onClose).padding(4.dp))
        } else if (value != null) Plain(value, Type.body, c.text, Modifier.weight(1f))
        else Text(hint, style = Type.body, color = c.muted, modifier = Modifier.weight(1f))
        if (!active && onRemove != null) LfIcon(LfIcons.Close, c.muted, 18.dp, "Remove this stop", Modifier.clickable(onClick = onRemove).padding(4.dp))
    }
}

@Composable
private fun SuggestRow(icon: ImageVector, tint: Color, name: String, detail: String, saved: Boolean?, onStar: () -> Unit, onRemove: (() -> Unit)? = null, onClick: () -> Unit) {
    val c = LocalLf.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LfIcon(icon, tint, 18.dp)
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Plain(name, Type.body, c.text)
            if (detail.isNotBlank()) Plain(detail, Type.tiny, c.muted)
        }
        if (onRemove != null) Box(
            Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = L10n.tr("Remove from recent places") },
            contentAlignment = Alignment.Center,
        ) { LfIcon(LfIcons.Close, c.muted, 16.dp) }
        if (saved != null) Box(
            Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onStar)
                .semantics { contentDescription = L10n.tr(if (saved) "Remove from saved places" else "Save this place") },
            contentAlignment = Alignment.Center,
        ) { LfIcon(if (saved) IconStarOn else IconStar, if (saved) c.orangeInk else c.icon, 18.dp) }
    }
}

/** Start charge, the lowest the battery may go, and how full to charge at a stop. */
@Composable
private fun BatteryOptions(p: Planner) {
    val c = LocalLf.current
    val start = p.startNow()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.raised).padding(vertical = 4.dp)) {
        OptionRow(if (p.startPct == null) "Start with (car now)" else "Start with", "$start %", { p.setStart(((start - 1) / 5) * 5) }, { p.setStart((start / 5 + 1) * 5) })
        OptionRow("Never below", "${p.minPct} %", { p.setMin(p.minPct - 5) }, { p.setMin(p.minPct + 5) })
        OptionRow("Charge to", "${p.targetPct} %", { p.setTarget(p.targetPct - 5) }, { p.setTarget(p.targetPct + 5) })
    }
}

/** A stop on the way. With a stay, a fast charger within 1 km is suggested: charge while you are there. */
@Composable
private fun ViaRow(p: Planner, v: Place, stay: Int, s: AppSettings, onShow: (Double, Double) -> Unit) {
    val c = LocalLf.current
    EndRow(c.muted, placeName(v), v.detail, viaTag(stay)) { onShow(v.lat, v.lon) }
    if (stay <= 0) return
    val near = p.chargers.map { it to no.lionflow.app.plan.Geo.km(v.lat, v.lon, it.lat, it.lon) }.filter { it.second < 1.0 }.minByOrNull { it.second } ?: return
    val ch = near.first
    if (p.plan?.stops?.any { it.charger.id == ch.id } == true) return     // already a charging stop
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.okBg).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Charge while you stay: ${ch.name} (${fmtDistance(near.second, s)} away)", style = Type.small, color = c.okText)
        Text("Charge there for $stay min", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk,
            modifier = Modifier.clickable(role = Role.Button) { p.chargeDuringStay(ch.id, stay) })
    }
}

/** How long to stay at each stop on the way. */
@Composable
private fun ViaStays(p: Planner) {
    val c = LocalLf.current
    val rows = p.vias.withIndex().filter { it.value != null }
    if (rows.isEmpty()) return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.raised).padding(vertical = 4.dp)) {
        for ((i, v) in rows) {
            val m = p.stays.getOrElse(i) { 0 }
            OptionRow("Stay at ${placeName(v!!)}", "$m min", { p.setStay(i, m - if (m > 30) 15 else 5) }, { p.setStay(i, m + if (m >= 30) 15 else 5) })
        }
    }
}

@Composable
private fun OptionRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    val c = LocalLf.current
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.body, color = c.text2, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        StepButton(LfIcons.Minus, "Less", onMinus)
        Plain(value, Type.label, c.text, Modifier.width(64.dp), align = TextAlign.Center)
        StepButton(LfIcons.Plus, "More", onPlus)
    }
}

@Composable
private fun StepButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = LocalLf.current
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = L10n.tr(label) },
        contentAlignment = Alignment.Center,
    ) { LfIcon(icon, c.accentInk, 16.dp) }
}

@Composable
private fun Stepper(label: String, value: String, modifier: Modifier, onMinus: () -> Unit, onPlus: () -> Unit) {
    val c = LocalLf.current
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(c.card).padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = Type.tiny, color = c.muted, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton(LfIcons.Minus, "Less", onMinus)
            Plain(value, Type.label, c.text, Modifier.weight(1f), align = TextAlign.Center)
            StepButton(LfIcons.Plus, "More", onPlus)
        }
    }
}

// ---------------- the result: summary and stops ----------------

@Composable
fun PlanResult(p: Planner, s: AppSettings, options: Boolean, onShow: (Double, Double) -> Unit) {
    val plan = p.plan ?: return
    val c = LocalLf.current
    val platform = LocalPlatform.current
    val from = p.planFrom
    val to = p.planTo
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Summary
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(dur(plan.totalMin), style = Type.stat.copy(fontSize = 26.sp), color = c.text, modifier = Modifier.weight(1f))
                if (plan.ok) {
                    val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.MINUTE, plan.totalMin.roundToInt()) }
                    Text("Arrive " + TimeFmt.hm(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE)), style = Type.label, color = c.text2)
                }
            }
            val charging = plan.stops.count { it.kwh > 0.01 }
            Text(
                listOfNotNull(
                    fmtDistance(plan.totalKm, s),
                    when (charging) { 0 -> "No charging needed"; 1 -> "1 stop"; else -> "$charging stops" },
                    if (plan.chargeMin > 0) "${dur(plan.chargeMin.toDouble())} charging" else null,
                ).joinToString(" · "), style = Type.small, color = c.muted,
            )
            if (plan.ok) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tag("Arrive with ${plan.arrivePct.roundToInt()} %", c.okBg, c.okText)
                plan.cost?.let { Tag("≈ " + money(it, plan.currency) + if (plan.unpriced > 0) " +" else "", c.card, c.text2, c.line2) }
            }
        }
        if (!plan.ok) Text(
            if (p.startNow() <= p.minPct) "The start charge is at or below your minimum. Start with more, or lower the minimum."
            else "No fast charger within reach after ${fmtDistance(plan.failKm, s)}. Lower the minimum, raise Charge to, charge more before, or pick a charger on the map.",
            style = Type.small, color = c.text,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, c.danger, RoundedCornerShape(14.dp)).padding(12.dp),
        )
        p.busy?.let { msg ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { Busy(); Text(msg, style = Type.small, color = c.text2) }
        }
        if (options) p.error?.let { Text(it, style = Type.small, color = c.danger) }

        // The trip, stop by stop
        if (from != null) EndRow(c.orangeInk, placeName(from), from.detail, "${p.startNow()} %") { onShow(from.lat, from.lon) }
        // Charging stops and the places the user asked to pass through, in the order they come along the road.
        val rt = p.route
        val vias = if (rt == null) emptyList() else p.planVias.mapIndexed { k, v -> Triple(v, rt.km[no.lionflow.app.plan.Geo.project(rt, v.lat, v.lon).first], p.planStays.getOrElse(k) { 0 }) }.sortedBy { it.second }
        var vi = 0
        plan.stops.forEachIndexed { i, st ->
            while (vi < vias.size && vias[vi].second <= st.charger.km) { ViaRow(p, vias[vi].first, vias[vi].third, s, onShow); vi++ }
            StopCard(i + 1, st, p, s, onShow)
        }
        while (vi < vias.size) { ViaRow(p, vias[vi].first, vias[vi].third, s, onShow); vi++ }
        if (to != null) EndRow(c.accentInk, placeName(to), to.detail, if (plan.ok) "${plan.arrivePct.roundToInt()} %" else "—") { onShow(to.lat, to.lon) }

        if (options) { ViaStays(p); BatteryOptions(p) }
        if (plan.ok && from != null && to != null) PrimaryButton("Open in Google Maps", Modifier.fillMaxWidth(), icon = LfIcons.Locate) {
            val rt2 = p.route
            val along = plan.stops.map { it.charger.km to "${it.charger.lat},${it.charger.lon}" } +
                (if (rt2 == null) emptyList() else p.planVias.map { v -> rt2.km[no.lionflow.app.plan.Geo.project(rt2, v.lat, v.lon).first] to "${v.lat},${v.lon}" })
            val stops = along.sortedBy { it.first }.take(9).joinToString("%7C") { it.second }
            platform.openUrl(
                "https://www.google.com/maps/dir/?api=1&travelmode=driving&origin=${from.lat},${from.lon}&destination=${to.lat},${to.lon}" +
                    if (stops.isNotEmpty()) "&waypoints=$stops" else "",
            )
        }
        if (p.changed) Text("Undo my changes to the stops", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk, modifier = Modifier.clickable { p.resetStops() })

        Text(
            if (p.model.trips > 0) "Battery use is worked out from your ${p.model.trips} longest trips (${String.format(Locale.US, "%.1f", p.model.at(90.0))} kWh/100 km at 90 km/h). Hills and wind are not counted."
            else "Battery use is a typical figure until LionFlow has your trips. Hills and wind are not counted.",
            style = Type.tiny, color = c.muted,
        )
        Text(
            if (p.fitCount > 0) "Charging time matches your last ${p.fitCount} fast charges (top speed about ${p.carKw.roundToInt()} kW)."
            else "Charging time is a typical figure until LionFlow has seen your fast charges.",
            style = Type.tiny, color = c.muted,
        )
        if (plan.stops.any { it.cost != null }) Text("Prices are the usual drop-in prices (checked ${p.pricesAsOf}), not live prices.", style = Type.tiny, color = c.muted)
        Text("Map and chargers © OpenStreetMap contributors · Route: OSRM · Address search: Photon", style = Type.tiny.copy(fontSize = 10.sp), color = c.muted,
            modifier = Modifier.clickable { platform.openUrl("https://www.openstreetmap.org/copyright") })
    }
}

@Composable
private fun Tag(text: String, bg: Color, fg: Color, line: Color? = null) {
    val m = Modifier.clip(CircleShape).background(bg)
    Box((if (line != null) m.border(1.dp, line, CircleShape) else m).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text, style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = fg)
    }
}

@Composable
private fun EndRow(dot: Color, name: String, detail: String, pct: String, onClick: () -> Unit) {
    val c = LocalLf.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(12.dp).clip(CircleShape).background(dot)) }
        Column(Modifier.weight(1f)) {
            Plain(name, Type.label, c.text)
            if (detail.isNotBlank()) Plain(detail, Type.tiny, c.muted)
        }
        Plain(pct, Type.label, c.text2)
    }
}

@Composable
private fun StopCard(n: Int, st: Stop, p: Planner, s: AppSettings, onShow: (Double, Double) -> Unit) {
    val c = LocalLf.current
    val ch = st.charger
    var others by remember(ch.id) { mutableStateOf(false) }
    val arrive = st.arrivePct.roundToInt()
    val leave = st.leavePct.roundToInt()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.raised).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { onShow(ch.lat, ch.lon) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) { Plain("$n", Type.label.copy(fontSize = 14.sp), c.onAccent) }
                Column(Modifier.weight(1f)) {
                    Plain(ch.name, Type.label, c.text)
                    Plain(chargerLine(ch, s), Type.tiny, c.muted)
                }
            }
            LfIcon(LfIcons.Close, c.muted, 18.dp, "Remove this stop", Modifier.clip(CircleShape).clickable { p.removeStop(ch.id) }.padding(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Plain("$arrive % → $leave %", Type.stat.copy(fontSize = 18.sp), c.text, Modifier.weight(1f))
            st.cost?.takeIf { it > 0.5 }?.let { Plain("≈ " + money(it, ch.currency), Type.small, c.text2) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stepper("Charge to", "$leave %", Modifier.weight(1f),
                { p.chargeTo(ch.id, (((leave - 1) / 5) * 5).coerceAtLeast(ceil(st.arrivePct).toInt())) },
                { p.chargeTo(ch.id, ((leave / 5 + 1) * 5).coerceAtMost(100)) })
            Stepper("Break", "${st.minutes} min", Modifier.weight(1f),
                { p.stayFor(ch.id, st.minutes - 5) }, { p.stayFor(ch.id, (st.minutes / 5 + 1) * 5) })
        }
        if (st.alternatives.isNotEmpty()) {
            Text(if (others) "Hide other chargers" else "Choose another charger", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk,
                modifier = Modifier.clickable { others = !others })
            if (others) for (a in st.alternatives) Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.card).clickable { others = false; p.swapStop(ch.id, a.id) }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Plain(a.name, Type.body, c.text)
                    Plain(chargerLine(a, s), Type.tiny, c.muted)
                }
            }
        }
    }
}

/** A charger tapped on the map while planning: add it as a stop, or take it out. */
@Composable
fun PlanChargerCard(ch: PlanCharger, isStop: Boolean, s: AppSettings, modifier: Modifier, onAdd: () -> Unit, onRemove: () -> Unit, onClose: () -> Unit) {
    val c = LocalLf.current
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(20.dp)).blockTouches().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Plain(ch.name, Type.label.copy(fontSize = 16.sp), c.text)
                Plain(chargerLine(ch, s), Type.small, c.muted, maxLines = 2)
            }
            LfIcon(LfIcons.Close, c.muted, 20.dp, "Close", Modifier.clip(CircleShape).clickable(onClick = onClose).padding(4.dp))
        }
        Box(
            Modifier.fillMaxWidth().height(44.dp).clip(CircleShape).background(if (isStop) c.raised else c.accent).clickable(role = Role.Button, onClick = if (isStop) onRemove else onAdd),
            contentAlignment = Alignment.Center,
        ) { Text(if (isStop) "Remove this stop" else "Charge here", style = Type.label, color = if (isStop) c.text else c.onAccent) }
    }
}
