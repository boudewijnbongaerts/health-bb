@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.boudhealth.ui.health

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.data.Pt
import com.boudhealth.data.Sample
import com.boudhealth.ui.components.BigStepper
import com.boudhealth.ui.components.ButtonKind
import com.boudhealth.ui.components.Fmt
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.GlyphIcon
import com.boudhealth.ui.components.HBBottomSheet
import com.boudhealth.ui.components.HBButton
import com.boudhealth.ui.components.SheetActions
import com.boudhealth.ui.components.hbClickable
import com.boudhealth.ui.theme.Cat
import com.boudhealth.ui.theme.HbShapes
import com.boudhealth.ui.theme.LocalHB
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// =====================================================================================================================
// About card (B_SPEC §1): tile r 20, pad 16, info glyph 20 + titleS, body 14/20 muted, optional extra content.
// =====================================================================================================================

@Composable
fun AboutCard(title: String, body: String, modifier: Modifier = Modifier, extra: (@Composable ColumnScope.() -> Unit)? = null) {
    val hb = LocalHB.current
    Column(modifier.fillMaxWidth().background(hb.colors.tile, HbShapes.group).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.Info, size = 20.dp, color = hb.colors.text)
            Spacer(Modifier.width(10.dp))
            Text("About $title", style = hb.type.titleS, color = hb.colors.text)
        }
        Spacer(Modifier.height(8.dp))
        Text(body, style = hb.type.bodyS, color = hb.colors.muted)
        if (extra != null) {
            Spacer(Modifier.height(14.dp))
            extra()
        }
    }
}

/** Band key for RHR / HRV: mini swatch with a line + sentence. */
@Composable
fun BandKey(color: Color, boldText: String, rest: String) {
    val hb = LocalHB.current
    Box(Modifier.fillMaxWidth().height(1.dp).background(hb.colors.grid))
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 2.dp).size(34.dp, 20.dp).background(hb.band(color).copy(alpha = 0.35f), RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(22.dp, 2.5.dp).background(color, RoundedCornerShape(2.dp)))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = hb.colors.text)) { append(boldText) }
                append(" "); append(rest)
            },
            style = hb.type.bodyXS.copy(fontSize = 13.5.sp, lineHeight = 19.sp), color = hb.colors.muted,
        )
    }
}

/**
 * VO₂ category scale (B_SPEC About card): 4 segments 10 h, gap 3, rounded; the current segment in VO₂ at 45 %;
 * ink bubble with the value over a 2 dp stem at the exact position; names + ranges under it.
 */
@Composable
fun CategoryScale(value: Double, scale: Vo2Scale, color: Color) {
    val hb = LocalHB.current
    val measurer = rememberTextMeasurer()
    val zone = scale.zoneOf(value)
    val lo = scale.cuts[0] - 6.0
    val hi = scale.cuts[2] + 6.0
    Canvas(Modifier.fillMaxWidth().height(36.dp)) {
        val gap = 3.dp.toPx()
        val segW = (size.width - gap * 3) / 4f
        val barTop = size.height - 10.dp.toPx()
        val segBounds = listOf(lo, scale.cuts[0], scale.cuts[1], scale.cuts[2], hi)
        for (i in 0..3) {
            val x = i * (segW + gap)
            val c = if (i == zone) color.copy(alpha = 0.45f) else hb.colors.text.copy(alpha = if (hb.dark) 0.14f else 0.09f)
            drawRoundRect(c, Offset(x, barTop), Size(segW, 10.dp.toPx()), CornerRadius(5.dp.toPx()))
        }
        // marker position inside the zone's segment
        val z = zone.coerceIn(0, 3)
        val a = segBounds[z]; val b = segBounds[z + 1]
        val f = ((value - a) / (b - a)).coerceIn(0.05, 0.95).toFloat()
        val mx = z * (segW + gap) + segW * f
        val label = measurer.measure(Fmt.num(value), TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = hb.colors.onInverse, fontFeatureSettings = "tnum"))
        val bw = label.size.width + 14.dp.toPx(); val bh = 20.dp.toPx()
        val bx = (mx - bw / 2f).coerceIn(0f, size.width - bw)
        drawLine(hb.colors.inverse, Offset(mx, bh), Offset(mx, barTop + 10.dp.toPx()), 2.dp.toPx())
        drawRoundRect(hb.colors.inverse, Offset(bx, 0f), Size(bw, bh), CornerRadius(bh / 2))
        drawText(label, topLeft = Offset(bx + (bw - label.size.width) / 2f, (bh - label.size.height) / 2f))
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth()) {
        for (i in 0..3) {
            val cur = i == zone
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(scale.names[i], style = hb.type.caption.copy(fontWeight = if (cur) FontWeight.Bold else FontWeight.Normal),
                    color = if (cur) hb.legible(color, hb.colors.tile) else hb.colors.muted, maxLines = 1)
                Text(scale.rangeText(i), style = hb.type.caption.copy(fontWeight = if (cur) FontWeight.Bold else FontWeight.Normal),
                    color = if (cur) hb.legible(color, hb.colors.tile) else hb.colors.muted, maxLines = 1)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        if (scale.ageLabel != null) "Typical ranges for men aged ${scale.ageLabel}." else "Typical ranges for men aged 40–49. Add your birth year in Settings for your age group.",
        style = hb.type.bodyXS, color = hb.colors.muted,
    )
}

// =====================================================================================================================
// History row (glyph-less list row, B_SPEC §1)
// =====================================================================================================================

@Composable
fun HistoryRow(
    title: String,
    value: String,
    unit: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleBad: Boolean = false,
    change: String? = null,
    divider: Boolean = true,
    struck: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val hb = LocalHB.current
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (onLongClick != null) Modifier.hbClickable(scaleOnPress = false, shape = HbShapes.tileS, onLongClick = onLongClick, onClick = {}) else Modifier)
                .heightIn(min = 60.dp).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = hb.type.titleS, color = hb.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(
                    subtitle, style = if (subtitleBad) hb.type.label.copy(fontSize = 13.sp) else hb.type.bodyXS,
                    color = if (subtitleBad) hb.colors.bad else hb.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.text,
                            textDecoration = if (struck) TextDecoration.LineThrough else null)) { append(value) }
                        if (!unit.isNullOrEmpty()) withStyle(SpanStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = hb.colors.muted)) { append(" "); append(unit) }
                    },
                    style = hb.type.titleS, maxLines = 1,
                )
                if (change != null) Text(change, style = hb.type.bodyXS.copy(fontSize = 12.sp), color = hb.colors.muted, maxLines = 1)
            }
        }
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(hb.colors.grid))
    }
}

/** Change text for history rows: "+1", "−0.4", "Same". */
fun changeText(v: Double, prev: Double?, decimals: Int): String? {
    if (prev == null) return null
    val d = v - prev
    val f = Math.pow(10.0, decimals.toDouble())
    return if (Math.round(d * f) == 0L) "Same" else Fmt.delta(d, decimals)
}

/** Swipe left to delete (with a trash reveal) + long-press to delete. */
@Composable
fun SwipeToDelete(onDelete: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val hb = LocalHB.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val latest by rememberUpdatedState(onDelete)
    Box(modifier.fillMaxWidth()) {
        if (offset.value < -1f) {
            Row(Modifier.matchParentSize().background(hb.colors.badCont, HbShapes.tileS).padding(end = 20.dp),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.Trash, size = 20.dp, color = hb.colors.bad)
            }
        }
        Box(
            Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }
                .background(hb.colors.surface)
                .pointerInput(Unit) {
                    val threshold = size.width * 0.35f
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (offset.value < -threshold) {
                                    offset.animateTo(-size.width.toFloat(), tween(150))
                                    latest()
                                    offset.snapTo(0f)
                                } else offset.animateTo(0f, tween(200))
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f, tween(200)) } },
                    ) { change, dx ->
                        change.consume()
                        scope.launch { offset.snapTo(min(0f, offset.value + dx)) }
                    }
                },
        ) { content() }
    }
}

// =====================================================================================================================
// Pills / chips
// =====================================================================================================================

enum class PillKind { Good, Bad, Neutral, Waist }

/** Small status pill: h 26, r 13, optional glyph 14 + 12.5/700 text (e.g. "✓ Recomp", "Searching…"). */
@Composable
fun StatusPill(text: String, kind: PillKind, modifier: Modifier = Modifier, glyph: Glyph? = null, trailing: (@Composable () -> Unit)? = null) {
    val hb = LocalHB.current
    val (bg, fg) = when (kind) {
        PillKind.Good -> hb.colors.goodCont to hb.colors.good
        PillKind.Bad -> hb.colors.badCont to hb.colors.bad
        PillKind.Neutral -> hb.colors.tile to hb.colors.muted
        PillKind.Waist -> hb.tint(Cat.waist) to hb.legible(Cat.waist, hb.colors.surface)
    }
    Row(modifier.height(26.dp).background(bg, RoundedCornerShape(13.dp)).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (glyph != null) { GlyphIcon(glyph, size = 14.dp, color = fg, stroke = 2.6f); Spacer(Modifier.width(5.dp)) }
        Text(text, style = hb.type.labelS.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold), color = fg, maxLines = 1)
        if (trailing != null) { Spacer(Modifier.width(6.dp)); trailing() }
    }
}

/** Quick preset chip in sheets: sheetTile, h 34, "BMI 25 · 84.6". */
@Composable
fun QuickChip(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val hb = LocalHB.current
    val shape = RoundedCornerShape(17.dp)
    Row(modifier.height(34.dp).hbClickable(shape = shape, scaleOnPress = false, onClick = onClick).background(hb.colors.sheetTile, shape).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = hb.colors.text)) { append(label) }
                withStyle(SpanStyle(color = hb.colors.muted)) { append(" · ") }
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = hb.colors.text)) { append(value) }
            },
            style = hb.type.bodyS, maxLines = 1,
        )
    }
}

// =====================================================================================================================
// WHtR gauge (B_SPEC §2): 12 h bar 0.40–0.65 in three zones, ink ticks at 0.5 / 0.6, marker r 8 + value bubble.
// =====================================================================================================================

@Composable
fun WhtrGauge(whtr: Double, modifier: Modifier = Modifier) {
    val hb = LocalHB.current
    val measurer = rememberTextMeasurer()
    val green = Cat.healthyMark
    val amber = Cat.fat
    val red = Cat.waist
    Canvas(modifier.fillMaxWidth().height(70.dp)) {
        val lo = 0.40; val hi = 0.65
        fun x(v: Double) = ((v - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f) * size.width
        val barTop = 28.dp.toPx(); val bh = 12.dp.toPx()
        val a = if (hb.dark) 0.55f else 0.6f
        // three zones, rounded ends only on the outside
        drawRoundRect(green.copy(alpha = a), Offset(0f, barTop), Size(x(0.5), bh), CornerRadius(bh / 2))
        drawRect(amber.copy(alpha = a), Offset(x(0.5), barTop), Size(x(0.6) - x(0.5), bh))
        drawRoundRect(red.copy(alpha = a), Offset(x(0.6), barTop), Size(size.width - x(0.6), bh), CornerRadius(bh / 2))
        drawRect(red.copy(alpha = a), Offset(x(0.6), barTop), Size(bh, bh))
        // ticks
        for (t in listOf(0.5, 0.6)) drawLine(hb.colors.text, Offset(x(t), barTop - 4.dp.toPx()), Offset(x(t), barTop + bh + 4.dp.toPx()), 2.dp.toPx())
        // marker + bubble
        val mx = x(whtr)
        drawCircle(hb.colors.surface, 10.dp.toPx(), Offset(mx, barTop + bh / 2))
        drawCircle(hb.colors.text, 8.dp.toPx(), Offset(mx, barTop + bh / 2))
        val lbl = measurer.measure(Fmt.num(whtr, 2), TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = hb.colors.onInverse, fontFeatureSettings = "tnum"))
        val bw = lbl.size.width + 14.dp.toPx(); val bubH = 20.dp.toPx()
        val bx = (mx - bw / 2f).coerceIn(0f, size.width - bw)
        drawRoundRect(hb.colors.inverse, Offset(bx, 0f), Size(bw, bubH), CornerRadius(bubH / 2))
        drawText(lbl, topLeft = Offset(bx + (bw - lbl.size.width) / 2f, (bubH - lbl.size.height) / 2f))
        // labels
        val ly = barTop + bh + 8.dp.toPx()
        val st = TextStyle(fontSize = 11.sp, color = hb.colors.muted)
        val bold = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = hb.colors.text, fontFeatureSettings = "tnum")
        drawText(measurer.measure("Healthy", st), topLeft = Offset(0f, ly))
        measurer.measure("0.5", bold).let { drawText(it, topLeft = Offset(x(0.5) - it.size.width / 2f, ly)) }
        measurer.measure("Raised", st).let { drawText(it, topLeft = Offset((x(0.5) + x(0.6)) / 2f - it.size.width / 2f, ly)) }
        measurer.measure("0.6", bold).let { drawText(it, topLeft = Offset(x(0.6) - it.size.width / 2f, ly)) }
        measurer.measure("High", st).let { drawText(it, topLeft = Offset(size.width - it.size.width, ly)) }
    }
}

// =====================================================================================================================
// Recomposition chart (B_SPEC §2): change since the period start, lean + fat lines from a shared dotted zero,
// area between zero and the fat line tinted, end labels.
// =====================================================================================================================

@Composable
fun RecompChart(lean: List<Pt>, fat: List<Pt>, start: LocalDate, end: LocalDate, modifier: Modifier = Modifier, height: Dp = 170.dp) {
    val hb = LocalHB.current
    val measurer = rememberTextMeasurer()
    val loc = Locale.getDefault()
    val l0 = lean.firstOrNull()?.v
    val f0 = fat.firstOrNull()?.v
    val ld = if (l0 == null) emptyList() else lean.map { it.at to it.v - l0 }
    val fd = if (f0 == null) emptyList() else fat.map { it.at to it.v - f0 }
    val leanC = Cat.lean
    val fatC = Cat.fat
    Canvas(modifier.fillMaxWidth().height(height)) {
        val top = 22.dp.toPx(); val bottom = size.height - 24.dp.toPx()
        val right = size.width - 64.dp.toPx()
        val all = ld.map { it.second } + fd.map { it.second } + 0.0
        val hiV = max(0.5, all.maxOrNull() ?: 0.5)
        val loV = min(-0.5, all.minOrNull() ?: -0.5)
        val x0 = start.atStartOfDay()
        val span = max(1L, ChronoUnit.HOURS.between(x0, end.atStartOfDay())).toFloat()
        fun xOf(t: LocalDateTime) = (ChronoUnit.HOURS.between(x0, t) / span).coerceIn(0f, 1f) * right
        fun yOf(v: Double) = top + ((hiV - v) / (hiV - loV)).toFloat() * (bottom - top)
        val zy = yOf(0.0)
        // zero line + label
        drawLine(hb.colors.muted.copy(alpha = 0.6f), Offset(0f, zy), Offset(right, zy), 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())))
        val zl = measurer.measure("${DateTimeFormatter.ofPattern("MMM yyyy", loc).format(start)} level", TextStyle(fontSize = 11.sp, color = hb.colors.muted))
        drawText(zl, topLeft = Offset(0f, zy - zl.size.height - 4.dp.toPx()))
        // fat area between zero and the fat line
        if (fd.size >= 2) {
            val area = Path().apply {
                moveTo(xOf(fd.first().first), zy)
                fd.forEach { (t, v) -> lineTo(xOf(t), yOf(v)) }
                lineTo(xOf(fd.last().first), zy); close()
            }
            drawPath(area, fatC.copy(alpha = if (hb.dark) 0.22f else 0.18f))
        }
        fun line(pts: List<Pair<LocalDateTime, Double>>, c: Color) {
            if (pts.isEmpty()) return
            val p = Path()
            pts.forEachIndexed { i, (t, v) -> if (i == 0) p.moveTo(xOf(t), yOf(v)) else p.lineTo(xOf(t), yOf(v)) }
            drawPath(p, c, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            val e = Offset(xOf(pts.last().first), yOf(pts.last().second))
            drawCircle(hb.colors.tile, 6.dp.toPx(), e)
            drawCircle(c, 4.5.dp.toPx(), e)
            val t = measurer.measure("${Fmt.delta(pts.last().second, 1)} kg", TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = hb.legible(c, hb.colors.tile), fontFeatureSettings = "tnum"))
            drawText(t, topLeft = Offset(e.x + 9.dp.toPx(), e.y - t.size.height / 2f))
        }
        line(ld, leanC)
        line(fd, fatC)
        // x labels: 4–5 months
        val months = ChronoUnit.MONTHS.between(start.withDayOfMonth(1), end.withDayOfMonth(1)).toInt().coerceAtLeast(1)
        val stepM = max(1, (months + 3) / 4)
        var d = start.withDayOfMonth(1)
        if (d.isBefore(start)) d = d.plusMonths(1)
        while (d.isBefore(end)) {
            val lbl = measurer.measure(DateTimeFormatter.ofPattern("MMM", loc).format(d), TextStyle(fontSize = 11.sp, color = hb.colors.muted))
            val x = xOf(d.atStartOfDay())
            drawText(lbl, topLeft = Offset((x - lbl.size.width / 2f).coerceIn(0f, right - lbl.size.width), bottom + 8.dp.toPx()))
            d = d.plusMonths(stepM.toLong())
        }
    }
}

// =====================================================================================================================
// Add waist sheet (DESIGN §5.11 example): big stepper + "Read from tape" tonal + "Save measurement" primary.
// =====================================================================================================================

@Composable
fun AddWaistSheet(last: Sample?, onDismiss: () -> Unit, onSave: (Double) -> Unit, onTape: (() -> Unit)?) {
    val hb = LocalHB.current
    var v by rememberSaveable { mutableDoubleStateOf(last?.value?.let { Math.round(it * 10) / 10.0 } ?: 95.0) }
    HBBottomSheet(onDismiss = onDismiss, title = "Add waist") {
        BigStepper(
            v, { v = it }, step = 0.1, range = 40.0..200.0, decimals = 1, unit = "cm",
            caption = last?.let { "Last: ${Fmt.num(it.value, 1)} cm · ${Fmt.shortDate(Fmt.date(it.time))}" } ?: "Measure at belly-button height, after breathing out",
        )
        Spacer(Modifier.height(20.dp))
        SheetActions {
            if (onTape != null) HBButton("Read from tape", { onDismiss(); onTape() }, kind = ButtonKind.Tonal, glyph = Glyph.Bluetooth, fullWidth = true)
            HBButton("Save measurement", { onSave(v); onDismiss() }, fullWidth = true)
        }
        Spacer(Modifier.height(4.dp))
        Text("Stays on this phone.", style = hb.type.bodyXS, color = hb.colors.muted, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

/** |a − b| formatted with the unit, for "3.4 cm to reach 0.5". */
fun absText(v: Double, decimals: Int, unit: String): String = "${Fmt.num(abs(v), decimals)} $unit"
