package com.boudhealth.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.launch
import java.time.ZoneOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.data.Pt
import com.boudhealth.ui.theme.HB
import com.boudhealth.ui.theme.LocalHB
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/*
 * Charts (DESIGN §5.5): Canvas only, no chart-library chrome.
 * Data model: the backend's Pt(at: LocalDateTime, v: Double). v = Double.NaN marks an explicit gap.
 */

// =====================================================================================================================
// Model
// =====================================================================================================================

/** One line. [maxGapMs]: break the line (and its area) where consecutive points are further apart. */
@Immutable
data class ChartSeries(
    val points: List<Pt>,
    val color: Color,
    val name: String = "",
    val unit: String = "",
    val decimals: Int = 0,
    val area: Boolean = true,
    val width: Dp = 2.5.dp,
    /** Hollow dots on every point (manual measurements, e.g. waist); the last one filled. */
    val dots: Boolean = false,
    val maxGapMs: Long? = null,
    val smooth: Boolean = true,
    val dashed: Boolean = false,
)

/** Personal normal band (p10–p90) with label "Your normal 54–60". */
@Immutable
data class NormalBand(val low: Double, val high: Double, val color: Color, val label: String? = null)

/**
 * Horizontal reference / goal line: 1.5 dp, dash 4/4, `text` @ 55 % (or [color]); label pill (h 20, `tile`, 11/600) 6 above
 * the line at the left edge (or right with [labelAtEnd]).
 */
@Immutable
data class RefLine(val value: Double, val label: String? = null, val color: Color? = null, val labelAtEnd: Boolean = false)

/** Horizontal value band (HR zones, WHtR zones): fill [color] (pass it with its alpha) between [from] and [to]. */
@Immutable
data class ZoneBand(val from: Double, val to: Double, val color: Color)

/** Scrub result for line charts: [seriesIndex] / [index] into that series' points. */
@Immutable
data class ScrubPoint(val seriesIndex: Int, val index: Int, val point: Pt)

/** Tooltip text: value 14/700 + unit 11/500, date 11 @ 75 %. */
@Immutable
data class ChartTooltip(val value: String, val unit: String = "", val date: String = "")

/** One bar. [segments] (bottom → top) make it a stacked bar; [value] is then ignored for drawing but used as the total. */
@Immutable
data class Bar(
    val x: LocalDateTime,
    val label: String,
    val value: Double,
    val segments: List<Pair<Double, Color>> = emptyList(),
    val today: Boolean = false,
    val future: Boolean = false,
)

// =====================================================================================================================
// Shared helpers
// =====================================================================================================================

/** Round gridline values: ~[count] ticks covering lo..hi (1 / 2 / 2.5 / 5 × 10^n steps). */
fun niceTicks(lo: Double, hi: Double, count: Int = 3): List<Double> {
    var a = lo; var b = hi
    if (b - a < 1e-9) { a -= 1.0; b += 1.0 }
    val rough = (b - a) / max(1, count - 1)
    val mag = 10.0.pow(floor(log10(rough)))
    val r = rough / mag
    val step = mag * when { r <= 1.0 -> 1.0; r <= 2.0 -> 2.0; r <= 2.5 -> 2.5; r <= 5.0 -> 5.0; else -> 10.0 }
    val start = floor(a / step) * step
    val end = ceil(b / step) * step
    val out = ArrayList<Double>()
    var v = start
    while (v <= end + step * 0.001 && out.size < 12) { out.add(if (abs(v) < step * 1e-6) 0.0 else v); v += step }
    return out
}

private fun minutes(t: LocalDateTime, t0: LocalDateTime): Float = ChronoUnit.SECONDS.between(t0, t) / 60f

private val loc: Locale get() = Locale.ENGLISH

/** Default x labels for a time span (≤ ~6). */
fun autoXLabels(x0: LocalDateTime, x1: LocalDateTime): List<Pair<LocalDateTime, String>> {
    val spanH = ChronoUnit.HOURS.between(x0, x1)
    val out = ArrayList<Pair<LocalDateTime, String>>()
    when {
        spanH <= 30 -> {
            var t = x0.truncatedTo(ChronoUnit.DAYS)
            while (t <= x1) { if (t >= x0) out += t to DateTimeFormatter.ofPattern("HH:mm", loc).format(t); t = t.plusHours(6) }
        }
        spanH <= 24 * 8 -> {
            var d = x0.toLocalDate()
            while (!d.atStartOfDay().isAfter(x1)) { out += d.atStartOfDay() to DateTimeFormatter.ofPattern("EEE", loc).format(d); d = d.plusDays(1) }
        }
        spanH <= 24 * 100 -> {
            var d = x0.toLocalDate(); var prevMonth = -1
            while (!d.atStartOfDay().isAfter(x1)) {
                val lbl = if (d.monthValue != prevMonth) DateTimeFormatter.ofPattern("d MMM", loc).format(d) else d.dayOfMonth.toString()
                prevMonth = d.monthValue
                out += d.atStartOfDay() to lbl
                d = d.plusDays(7)
            }
        }
        spanH <= 24 * 400 -> {
            var d = x0.toLocalDate().withDayOfMonth(1)
            if (d.atStartOfDay().isBefore(x0)) d = d.plusMonths(1)
            while (!d.atStartOfDay().isAfter(x1)) { out += d.atStartOfDay() to DateTimeFormatter.ofPattern("MMM", loc).format(d); d = d.plusMonths(1) }
        }
        else -> {
            var d = LocalDate.of(x0.year, 1, 1)
            if (d.atStartOfDay().isBefore(x0)) d = d.plusYears(1)
            while (!d.atStartOfDay().isAfter(x1)) { out += d.atStartOfDay() to d.year.toString(); d = d.plusYears(1) }
        }
    }
    if (out.size <= 6) return out
    val k = ceil(out.size / 6.0).toInt()
    return out.filterIndexed { i, _ -> i % k == 0 }
}

/** Default tooltip date for a point given the chart span. */
fun tooltipDate(t: LocalDateTime, spanDays: Long): String = when {
    spanDays <= 1 -> DateTimeFormatter.ofPattern("HH:mm", loc).format(t)
    spanDays <= 120 -> DateTimeFormatter.ofPattern("EEE d MMM", loc).format(t)
    spanDays <= 400 -> "Week of " + DateTimeFormatter.ofPattern("d MMM", loc).format(t)
    else -> DateTimeFormatter.ofPattern("MMM yyyy", loc).format(t)
}

/**
 * Scrub / swipe gestures. Without [onSwipe]: touch-down or drag scrubs immediately.
 * With [onSwipe]: a horizontal drag swipes the period (−1 older, +1 newer); long-press (then drag) scrubs.
 * A vertical drag is left to the parent scroll. [onScrubX] gets (x px, canvas width px), or null on release.
 */
private fun Modifier.chartGestures(
    enabled: Boolean,
    swipeThresholdPx: Float,
    hasSwipe: Boolean,
    onSwipe: (Int) -> Unit,
    onScrubX: (Float?, Int) -> Unit,
): Modifier = if (!enabled) this else this.pointerInput(hasSwipe) {
    val slop = viewConfiguration.touchSlop
    val longPress = viewConfiguration.longPressTimeoutMillis
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var mode = if (hasSwipe) 0 else 1 // 0 undecided, 1 scrub, 2 swipe, 3 let the parent scroll
        if (mode == 1) onScrubX(down.position.x, size.width)
        var dx = 0f
        val deadline = down.uptimeMillis + longPress
        var last = down.uptimeMillis
        while (true) {
            val ev = if (mode == 0) withTimeoutOrNull((deadline - last).coerceAtLeast(1L)) { awaitPointerEvent() } else awaitPointerEvent()
            if (ev == null) { mode = 1; onScrubX(down.position.x, size.width); continue }
            val change = ev.changes.firstOrNull { it.id == down.id } ?: break
            last = change.uptimeMillis
            if (!change.pressed) break
            val ddx = change.position.x - down.position.x
            val ddy = change.position.y - down.position.y
            when (mode) {
                0 -> when {
                    abs(ddx) > slop && abs(ddx) > abs(ddy) -> { mode = 2; change.consume() }
                    abs(ddy) > slop -> mode = 3
                    last >= deadline -> { mode = 1; onScrubX(change.position.x, size.width) }
                }
                1 -> { onScrubX(change.position.x, size.width); change.consume() }
                2 -> { dx = ddx; change.consume() }
            }
            if (mode == 3) break
        }
        if (mode == 1) onScrubX(null, size.width)
        if (mode == 2 && abs(dx) > swipeThresholdPx) onSwipe(if (dx < 0) 1 else -1)
    }
}

private fun DrawScope.drawTooltip(m: TextMeasurer, hb: HB, t: ChartTooltip, cx: Float, top: Float): Float {
    val c = hb.colors
    val v = buildAnnotatedString {
        withStyle(SpanStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.onInverse, fontFeatureSettings = "tnum")) { append(t.value) }
        if (t.unit.isNotEmpty()) withStyle(SpanStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = c.onInverse)) { append(" " + t.unit) }
    }
    val l1 = m.measure(v)
    val l2: TextLayoutResult? = if (t.date.isNotEmpty()) m.measure(t.date, TextStyle(fontSize = 11.sp, color = c.onInverse.copy(alpha = 0.75f))) else null
    val pad = 12.dp.toPx()
    val w = max(l1.size.width, l2?.size?.width ?: 0) + pad * 2
    val h = l1.size.height + (l2?.size?.height ?: 0) + pad * 1.4f
    val x = (cx - w / 2).coerceIn(0f, max(0f, size.width - w))
    drawRoundRect(c.inverse, Offset(x, top), Size(w, h), CornerRadius(12.dp.toPx()))
    drawText(l1, topLeft = Offset(x + pad, top + pad * 0.7f))
    if (l2 != null) drawText(l2, topLeft = Offset(x + pad, top + pad * 0.7f + l1.size.height))
    return top + h
}

private fun DrawScope.textAt(m: TextMeasurer, s: String, style: TextStyle, x: Float, y: Float, align: Int = 0): TextLayoutResult {
    // align: 0 left, 1 centre, 2 right; y = top
    val r = m.measure(s, style)
    val left = when (align) { 1 -> x - r.size.width / 2f; 2 -> x - r.size.width; else -> x }
    drawText(r, topLeft = Offset(left, y))
    return r
}

private fun DrawScope.labelPill(m: TextMeasurer, hb: HB, s: String, x: Float, bottom: Float, color: Color, alignEnd: Boolean) {
    val r = m.measure(s, TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color, fontFeatureSettings = "tnum"))
    val h = 20.dp.toPx(); val pad = 8.dp.toPx()
    val w = r.size.width + pad * 2
    val left = if (alignEnd) x - w else x
    drawRoundRect(hb.colors.tile, Offset(left, bottom - h), Size(w, h), CornerRadius(h / 2))
    drawText(r, topLeft = Offset(left + pad, bottom - h + (h - r.size.height) / 2f))
}

// =====================================================================================================================
// Line chart
// =====================================================================================================================

/**
 * Line chart: 2.5 dp Catmull–Rom line, gradient area (24 % → 0), gaps break the line, personal normal [band],
 * reference / goal [refs], value [zones], 3 gridlines at round values with right-aligned y labels in a 32 dp gutter,
 * x labels 20 below the plot, scrub with tooltip + haptic tick, optional period swipe.
 *
 * @param xStart / xEnd fix the x domain (e.g. the Window), else min/max of the data
 * @param onScrub called with the scrubbed point (series 0) or null when released: drive the hero from it
 * @param compact sparkline mode for tiles: no labels, gridlines, padding or gestures
 */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
    xStart: LocalDateTime? = null,
    xEnd: LocalDateTime? = null,
    yMin: Double? = null,
    yMax: Double? = null,
    band: NormalBand? = null,
    refs: List<RefLine> = emptyList(),
    zones: List<ZoneBand> = emptyList(),
    xLabels: List<Pair<LocalDateTime, String>>? = null,
    yFormat: (Double) -> String = { Fmt.compact(it) },
    showYLabels: Boolean = true,
    showXLabels: Boolean = true,
    gridLines: Boolean = true,
    tooltip: ((ScrubPoint) -> ChartTooltip)? = null,
    scrubEnabled: Boolean = true,
    onScrub: ((ScrubPoint?) -> Unit)? = null,
    onSwipe: ((Int) -> Unit)? = null,
    animateKey: Any? = series,
    compact: Boolean = false,
    topPadding: Dp = if (compact) 4.dp else 48.dp,
    secondary: List<ChartSeries> = emptyList(),
    secondaryMin: Double? = null,
    secondaryMax: Double? = null,
    secondaryFormat: (Double) -> String = { Fmt.compact(it) },
) {
    val hb = LocalHB.current
    val measurer = rememberTextMeasurer()
    val haptics = rememberHaptics()
    val reveal = remember { Animatable(if (hb.reducedMotion) 1f else 0f) }
    LaunchedEffect(animateKey) {
        if (hb.reducedMotion) reveal.snapTo(1f) else { reveal.snapTo(0f); reveal.animateTo(1f, tween(450, easing = EmphasizedDecelerate)) }
    }
    var scrubbed by remember { mutableStateOf<ScrubPoint?>(null) }
    val onScrubState by rememberUpdatedState(onScrub)
    val onSwipeState by rememberUpdatedState(onSwipe)
    val density = LocalDensity.current

    val all = series.flatMap { s -> s.points.filter { !it.v.isNaN() } }
    val all2 = secondary.flatMap { s -> s.points.filter { !it.v.isNaN() } }
    val x0 = xStart ?: (all + all2).minOfOrNull { it.at } ?: LocalDateTime.now().minusDays(1)
    val x1raw = xEnd ?: (all + all2).maxOfOrNull { it.at } ?: LocalDateTime.now()
    val x1 = if (!x1raw.isAfter(x0)) x0.plusHours(12) else x1raw
    val spanMin = max(1f, minutes(x1, x0))
    val spanDays = ChronoUnit.DAYS.between(x0, x1)

    val values = all.map { it.v } + refs.map { it.value } + listOfNotNull(band?.low, band?.high)
    val ticks = if (values.isEmpty()) niceTicks(0.0, 1.0) else niceTicks(yMin ?: values.min(), yMax ?: values.max())
    val lo = yMin ?: ticks.first()
    val hi = yMax ?: ticks.last()
    val labels = if (showXLabels && !compact) (xLabels ?: autoXLabels(x0, x1)) else emptyList()
    val interactive = !compact && scrubEnabled
    val yGutterPx = with(density) { if (showYLabels && !compact) 32.dp.toPx() else 0f }
    // secondary axis (e.g. body fat % over weight): own value domain, labels in a left gutter
    val hasSecondary = all2.isNotEmpty() && !compact
    val leftGutterPx = with(density) { if (hasSecondary) 34.dp.toPx() else 0f }
    val ticks2 = if (!hasSecondary) emptyList() else niceTicks(secondaryMin ?: all2.minOf { it.v }, secondaryMax ?: all2.maxOf { it.v })
    val lo2 = secondaryMin ?: ticks2.firstOrNull() ?: 0.0
    val hi2 = secondaryMax ?: ticks2.lastOrNull() ?: 1.0
    val currentSeries by rememberUpdatedState(series)
    val geometry by rememberUpdatedState(Triple(x0, spanMin, yGutterPx + leftGutterPx))
    val leftGutterState by rememberUpdatedState(leftGutterPx)

    Canvas(
        modifier.fillMaxWidth().height(height).chartGestures(
            enabled = interactive || (onSwipe != null && !compact),
            swipeThresholdPx = with(density) { 56.dp.toPx() },
            hasSwipe = onSwipe != null,
            onSwipe = { d -> onSwipeState?.invoke(d) },
        ) { x, width ->
            val primary = currentSeries.firstOrNull()
            val next: ScrubPoint? = if (x == null || !interactive || primary == null) null else {
                val (gx0, gspan, gutter) = geometry
                val plotW = width - gutter
                val lg = leftGutterState
                var best = -1; var bestD = Float.MAX_VALUE
                primary.points.forEachIndexed { i, p ->
                    if (!p.v.isNaN()) {
                        val d = abs(lg + minutes(p.at, gx0) / gspan * plotW - x)
                        if (d < bestD) { bestD = d; best = i }
                    }
                }
                if (best >= 0) ScrubPoint(0, best, primary.points[best]) else null
            }
            if (next?.index != scrubbed?.index || (next == null) != (scrubbed == null)) {
                scrubbed = next
                if (next != null) haptics.tick()
                onScrubState?.invoke(next)
            }
        },
    ) {
        val right = if (showYLabels && !compact) size.width - 32.dp.toPx() else size.width
        val top = topPadding.toPx()
        val bottom = size.height - if (labels.isNotEmpty()) 28.dp.toPx() else if (compact) 2.dp.toPx() else 4.dp.toPx()
        val plotL = leftGutterPx
        val plotW = right - plotL
        fun xOf(t: LocalDateTime) = plotL + minutes(t, x0) / spanMin * plotW
        fun yOf(v: Double) = (bottom - ((v - lo) / (hi - lo)).toFloat() * (bottom - top))
        fun yOf2(v: Double) = (bottom - ((v - lo2) / (hi2 - lo2).coerceAtLeast(1e-9)).toFloat() * (bottom - top))

        // zones
        zones.forEach { z ->
            val ya = yOf(min(z.to, hi)); val yb = yOf(max(z.from, lo))
            if (yb > ya) drawRect(z.color, Offset(plotL, ya), Size(plotW, yb - ya))
        }
        // normal band
        band?.let { b ->
            val ya = yOf(b.high); val yb = yOf(b.low)
            drawRoundRect(hb.band(b.color), Offset(plotL, ya), Size(plotW, max(1f, yb - ya)), CornerRadius(4.dp.toPx()))
            if (b.label != null && !compact) {
                val r = measurer.measure(b.label, TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = hb.legible(b.color)))
                drawText(r, topLeft = Offset(plotL, ya - 7.dp.toPx() - r.size.height))
            }
        }
        // gridlines + y labels
        if (gridLines && !compact) {
            ticks.filter { it in lo..hi }.forEach { v ->
                val y = yOf(v)
                drawLine(hb.colors.grid, Offset(plotL, y), Offset(right, y), 1.dp.toPx())
                if (showYLabels) {
                    val r = measurer.measure(yFormat(v), TextStyle(fontSize = 11.sp, color = hb.colors.muted, fontFeatureSettings = "tnum"))
                    drawText(r, topLeft = Offset(size.width - r.size.width, y - r.size.height / 2f))
                }
            }
        }
        // secondary axis labels (left gutter, in the first secondary series' colour)
        if (hasSecondary) {
            val c2 = hb.legible(secondary.first().color)
            ticks2.filter { it in lo2..hi2 }.forEach { v ->
                val r = measurer.measure(secondaryFormat(v), TextStyle(fontSize = 11.sp, color = c2, fontFeatureSettings = "tnum"))
                drawText(r, topLeft = Offset(0f, yOf2(v) - r.size.height / 2f))
            }
        }
        // x labels
        labels.forEachIndexed { i, (t, s) ->
            val x = xOf(t)
            if (x < plotL - 1f || x > right + 1f) return@forEachIndexed
            val r = measurer.measure(s, TextStyle(fontSize = 11.sp, color = hb.colors.muted, fontFeatureSettings = "tnum"))
            val left = (x - if (i == 0 && x - plotL < r.size.width / 2f) 0f else r.size.width / 2f).coerceIn(plotL, max(plotL, right - r.size.width))
            drawText(r, topLeft = Offset(left, bottom + 20.dp.toPx() - r.size.height + 4.dp.toPx()))
        }
        // series (revealed left → right); secondary first so the primary line stays on top
        // clip to the plot area: nothing (e.g. a bucket starting before xStart) may draw into the y-label gutter
        clipRect(left = plotL, top = 0f, right = plotL + plotW * reveal.value + 1f, bottom = size.height) {
            if (hasSecondary) secondary.forEach { s ->
                runs(s).forEach { run ->
                    val pts = run.map { Offset(xOf(it.at), yOf2(it.v)) }
                    if (pts.size == 1) drawCircle(s.color, s.width.toPx(), pts[0])
                    else drawPath(
                        linePath(pts, s.smooth), s.color,
                        style = Stroke(s.width.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                            pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null),
                    )
                }
            }
            series.forEach { s ->
                val segs = runs(s)
                segs.forEach { run ->
                    val pts = run.map { Offset(xOf(it.at), yOf(it.v)) }
                    if (pts.size == 1) { drawCircle(s.color, s.width.toPx(), pts[0]); return@forEach }
                    val line = linePath(pts, s.smooth)
                    if (s.area) {
                        val area = Path().apply {
                            addPath(line); lineTo(pts.last().x, bottom); lineTo(pts.first().x, bottom); close()
                        }
                        val minY = pts.minOf { it.y }
                        drawPath(area, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.24f), s.color.copy(alpha = 0f)), startY = minY, endY = bottom))
                    }
                    drawPath(
                        line, s.color,
                        style = Stroke(s.width.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                            pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())) else null),
                    )
                }
                if (s.dots) {
                    val valid = s.points.filter { !it.v.isNaN() }
                    valid.forEachIndexed { i, p ->
                        val o = Offset(xOf(p.at), yOf(p.v))
                        drawCircle(hb.colors.surface, 4.dp.toPx(), o)
                        if (i == valid.lastIndex) drawCircle(s.color, 4.dp.toPx(), o)
                        else drawCircle(s.color, 3.dp.toPx(), o, style = Stroke(2.dp.toPx()))
                    }
                }
            }
        }
        // refs / goal lines
        refs.forEach { r ->
            if (r.value < lo || r.value > hi) return@forEach
            val y = yOf(r.value)
            val col = r.color ?: hb.colors.text.copy(alpha = 0.55f)
            drawLine(col, Offset(plotL, y), Offset(right, y), 1.5.dp.toPx(), cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
            if (r.label != null && !compact) {
                labelPill(measurer, hb, r.label, if (r.labelAtEnd) right else plotL, y - 6.dp.toPx(),
                    if (r.color != null) hb.legible(r.color) else hb.colors.text, r.labelAtEnd)
            }
        }
        // scrub
        val sp = scrubbed
        val primary = series.firstOrNull()
        if (sp != null && primary != null && sp.index in primary.points.indices) {
            val p = primary.points[sp.index]
            val o = Offset(xOf(p.at), yOf(p.v))
            val tt = tooltip?.invoke(sp) ?: ChartTooltip(Fmt.num(p.v, primary.decimals), primary.unit, tooltipDate(p.at, spanDays))
            val tb = drawTooltip(measurer, hb, tt, o.x, 0f)
            // v3 C2: scrub cursor + dot ring in the palette accent
            drawLine(hb.colors.accent.copy(alpha = 0.55f), Offset(o.x, tb), Offset(o.x, bottom), 1.5.dp.toPx())
            drawCircle(hb.colors.accent, 9.dp.toPx(), o)
            drawCircle(hb.colors.surface, 7.dp.toPx(), o)
            drawCircle(primary.color, 5.dp.toPx(), o)
        }
    }
}

/** Split a series into continuous runs (NaN or > maxGapMs breaks). */
private fun runs(s: ChartSeries): List<List<Pt>> {
    val out = ArrayList<List<Pt>>()
    var cur = ArrayList<Pt>()
    var prev: Pt? = null
    for (p in s.points) {
        if (p.v.isNaN()) { if (cur.isNotEmpty()) out.add(cur); cur = ArrayList(); prev = null; continue }
        val pv = prev
        if (pv != null && s.maxGapMs != null && ChronoUnit.MILLIS.between(pv.at, p.at) > s.maxGapMs) {
            out.add(cur); cur = ArrayList()
        }
        cur.add(p); prev = p
    }
    if (cur.isNotEmpty()) out.add(cur)
    return out
}

/** Catmull–Rom (tension 1/6) through [pts], or straight segments. */
private fun linePath(pts: List<Offset>, smooth: Boolean): Path {
    val path = Path()
    path.moveTo(pts[0].x, pts[0].y)
    if (!smooth || pts.size < 3) { for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y); return path }
    for (i in 0 until pts.size - 1) {
        val p0 = pts[max(0, i - 1)]; val p1 = pts[i]; val p2 = pts[i + 1]; val p3 = pts[min(pts.size - 1, i + 2)]
        val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
        val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
        path.cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
    }
    return path
}

// =====================================================================================================================
// Bar chart
// =====================================================================================================================

// =====================================================================================================================
// Time chart (v4 Q23): tap / scrub to a real reading with a persistent callout, pinch / double-tap zoom with level of
// detail (readings → weeks → months), raw dots + calculated trend, min / max / latest markers, adaptive axes.
// One component for every metric detail chart (lines, dots and bars).
// =====================================================================================================================

/** Time unit of a chart level. [minutes] = nominal length (month ≈ 30.4 days). */
enum class ChartGrain(val minutes: Double) { HOUR(60.0), DAY(1_440.0), WEEK(10_080.0), MONTH(43_830.0) }

/** Wall-clock minutes (local date-time read as UTC: pure arithmetic, no DST jumps). */
fun chartMinutes(t: LocalDateTime): Double = t.toEpochSecond(ZoneOffset.UTC) / 60.0

fun chartTime(m: Double): LocalDateTime = LocalDateTime.ofEpochSecond(floor(m * 60.0).toLong(), 0, ZoneOffset.UTC)

/**
 * One chart point. A reading has [at] == [end] (drawn at its time); a bar or bucket spans [at, end) and is drawn at its
 * centre. [n] readings behind it (buckets), [ref] = index of the (first) reading in the caller's list, [lo]..[hi] = range.
 */
@Immutable
class TPoint(
    val at: LocalDateTime,
    val end: LocalDateTime,
    val v: Double,
    val n: Int = 1,
    val ref: Int = -1,
    val lo: Double = v,
    val hi: Double = v,
    /** V2-104: stacked bar (TimeStyle.Bars): (value, colour) bottom → top, e.g. fat burn · cardio · peak minutes; [v] = total. */
    val segments: List<Pair<Double, Color>>? = null,
) {
    val t0: Double = chartMinutes(at)
    val t1: Double = chartMinutes(end)
    val x: Double = (t0 + t1) / 2.0
}

/** One level of detail, points sorted by time. [bucketed]: points are averages of several readings (weeks, months). */
@Immutable
class TLevel(val grain: ChartGrain, val points: List<TPoint>, val bucketed: Boolean) {
    private val xs = DoubleArray(points.size) { points[it].x }

    /** First index whose x is ≥ [x]. */
    fun lowerBound(x: Double): Int {
        var lo = 0; var hi = xs.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (xs[mid] < x) lo = mid + 1 else hi = mid }
        return lo
    }

    /** Index of the point nearest to [x] (−1 when empty). */
    fun nearest(x: Double): Int {
        if (xs.isEmpty()) return -1
        val i = lowerBound(x)
        return when {
            i <= 0 -> 0
            i >= xs.size -> xs.size - 1
            x - xs[i - 1] <= xs[i] - x -> i - 1
            else -> i
        }
    }
}

/** The selected point: [index] into level [level]. */
@Immutable
data class ChartSel(val level: Int, val index: Int)

/** Extra line over the primary series (weight: fat % on a secondary axis, fat / lean mass in kg). Levels match the primary's grains. */
@Immutable
class TOverlay(val levels: List<TLevel>, val color: Color, val secondary: Boolean = false, val dashed: Boolean = false)

enum class TimeStyle {
    /** A line through the points (daily averages, vitals). */
    Line,
    /** Real measurements as dots (weigh-ins); bucket levels become a line with small dots. */
    Dots,
    /** One bar per point (daily totals, nights). */
    Bars,
}

/** A label drawn inside a value band (VO₂ max categories). */
@Immutable
data class ZoneLabel(val from: Double, val to: Double, val text: String, val color: Color, val bold: Boolean = false)

@Immutable
data class CalloutLine(val label: String, val value: String, val color: Color? = null)

/**
 * Callout content: [title] (date), [prefix] + [value] + [unit], extra [lines] (overlay values), [delta] (vs the previous
 * reading), [source] (app), [note] (e.g. "calculated"), optional [action] ("Show weigh-ins": zooms into the bucket).
 */
@Immutable
data class ChartCallout(
    val title: String,
    val value: String,
    val unit: String = "",
    val prefix: String = "",
    val lines: List<CalloutLine> = emptyList(),
    val delta: String? = null,
    val source: String? = null,
    val note: String? = null,
    val action: String? = null,
) {
    /** One sentence for TalkBack. */
    fun spoken(): String = buildString {
        append(title); append(": ")
        if (prefix.isNotEmpty()) append("$prefix ")
        append(value); if (unit.isNotEmpty()) append(" $unit")
        lines.forEach { append(". ${it.label} ${it.value}") }
        delta?.let { append(". $it") }
        source?.let { append(". Source $it") }
        note?.let { append(". $it") }
    }
}

/**
 * Viewport + selection of a [TimeChart], hoisted so the page (hero, list, range chips) can follow it.
 * The viewport ([v0]..[v1], chart minutes) is read only in the draw / layout phases, so zooming never recomposes the page.
 * Recreate it (remember with the window as key) when the range changes: that clears zoom and selection.
 */
@Stable
class TimeChartState {
    var d0 by mutableDoubleStateOf(0.0); private set
    var d1 by mutableDoubleStateOf(1.0); private set
    var v0 by mutableDoubleStateOf(0.0); private set
    var v1 by mutableDoubleStateOf(1.0); private set
    var selection by mutableStateOf<ChartSel?>(null)
    var levels by mutableStateOf<List<TLevel>>(emptyList()); private set
    private var bars by mutableStateOf(false)
    private var plotPx by mutableFloatStateOf(0f)
    private var minGapPx by mutableFloatStateOf(8f)
    internal var minSpan = 7 * 1_440.0

    /** True while zoomed in (the range chips show "Custom"). */
    val zoomed: Boolean by derivedStateOf { v0 > d0 + 1.0 || v1 < d1 - 1.0 }

    /** Current level of detail (index into [levels]): the finest one whose points fit the plot width. */
    val level: Int by derivedStateOf {
        val ls = levels
        if (ls.size <= 1 || plotPx <= 0f) return@derivedStateOf 0
        val span = v1 - v0
        var pick = ls.lastIndex
        for (i in ls.indices) {
            val n = if (bars) span / ls[i].grain.minutes else (ls[i].lowerBound(v1) - ls[i].lowerBound(v0)).toDouble()
            if (n * minGapPx <= plotPx) { pick = i; break }
        }
        pick
    }

    // geometry of the last draw pass (plain fields: gestures and the callout placement read them)
    internal var plotLeft = 0f
    internal var plotRight = 1f
    internal var plotTop = 0f
    internal var plotBottom = 1f
    internal var yLo = 0.0
    internal var yHi = 1.0
    internal var calloutRect: Rect? = null
    internal var coords: LayoutCoordinates? = null

    internal fun configure(levels: List<TLevel>, start: Double, end: Double, bars: Boolean, plotPx: Float, minGapPx: Float, minSpan: Double) {
        if (start != d0 || end != d1) { d0 = start; d1 = max(end, start + 60.0); v0 = d0; v1 = d1 }
        if (levels !== this.levels) {
            val sel = selection
            if (sel != null && levels.getOrNull(sel.level)?.points?.getOrNull(sel.index) == null) selection = null
            this.levels = levels
        }
        if (this.bars != bars) this.bars = bars
        if (this.plotPx != plotPx) this.plotPx = plotPx
        if (this.minGapPx != minGapPx) this.minGapPx = minGapPx
        this.minSpan = minSpan
    }

    fun point(sel: ChartSel? = selection): TPoint? = sel?.let { levels.getOrNull(it.level)?.points?.getOrNull(it.index) }

    val visibleStart: LocalDateTime get() = chartTime(v0)
    val visibleEnd: LocalDateTime get() = chartTime(v1)

    /** Is [rootPos] (root coordinates) inside the chart? Used by the page to clear the selection on a tap outside. */
    fun contains(rootPos: Offset): Boolean = coords?.takeIf { it.isAttached }?.boundsInRoot()?.contains(rootPos) == true

    private fun clamped(a: Double, b: Double): Pair<Double, Double> {
        val full = d1 - d0
        val span = (b - a).coerceIn(min(minSpan, full), full)
        var na = a + ((b - a) - span) / 2.0
        if (na < d0) na = d0
        if (na + span > d1) na = d1 - span
        return na to na + span
    }

    fun setView(a: Double, b: Double) { val (na, nb) = clamped(a, b); v0 = na; v1 = nb }

    suspend fun animateView(a: Double, b: Double, reducedMotion: Boolean = false) {
        val (ta, tb) = clamped(a, b)
        if (reducedMotion) { v0 = ta; v1 = tb; return }
        val sa = v0; val sb = v1
        animate(0f, 1f, animationSpec = tween(320, easing = EmphasizedDecelerate)) { f, _ ->
            v0 = sa + (ta - sa) * f
            v1 = sb + (tb - sb) * f
        }
    }

    suspend fun resetZoom(reducedMotion: Boolean = false) = animateView(d0, d1, reducedMotion)

    /** Zoom into [p]'s span with a little padding (a bucket's "Show weigh-ins"). */
    suspend fun zoomInto(p: TPoint, reducedMotion: Boolean = false) {
        val pad = max(1_440.0, (p.t1 - p.t0) * 0.15)
        animateView(p.t0 - pad, p.t1 + pad, reducedMotion)
    }

    /** Pan (keeping the zoom) so that [x] is visible. */
    fun ensureVisible(x: Double) {
        if (x in v0..v1) return
        val span = v1 - v0
        setView(x - span / 2.0, x + span / 2.0)
    }

    /** Select the reading [index] of level 0 (the readings) and make sure it is on screen. */
    fun selectReading(index: Int) {
        val p = levels.firstOrNull()?.points?.getOrNull(index) ?: return
        selection = ChartSel(0, index)
        ensureVisible(p.x)
    }
}

/**
 * The detail chart of every metric page.
 *
 * - **Tap** a point in time: snaps to the nearest point of the current level (readings when they fit, else week / month
 *   buckets), draws a marker and a callout ([callout] builds its content). The callout stays after lifting the finger,
 *   until its × or a tap outside the chart (the page clears [TimeChartState.selection]) or a range change.
 * - **Drag** horizontally to scrub: a haptic tick on every new point. A vertical drag scrolls the page.
 * - With [zoomable]: **pinch** (two fingers) zooms and pans horizontally; **double-tap** zooms to a month around the tap
 *   (again: back to the whole range). Levels of detail switch with the visible span; nothing recomposes per frame.
 * - Bucket callouts get an action ([ChartCallout.action]) that zooms into the bucket.
 * - TalkBack: [description] for the chart, the callout is a polite live region, and custom actions step through the
 *   points, zoom and clear.
 */
@Composable
fun TimeChart(
    state: TimeChartState,
    levels: List<TLevel>,
    start: LocalDateTime,
    end: LocalDateTime,
    color: Color,
    callout: (ChartSel, TLevel, TPoint) -> ChartCallout?,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 240.dp,
    style: TimeStyle = TimeStyle.Line,
    barColors: Pair<Color, Color> = color to color,
    goal: Double? = null,
    refs: List<RefLine> = emptyList(),
    band: NormalBand? = null,
    zones: List<ZoneBand> = emptyList(),
    zoneLabels: List<ZoneLabel> = emptyList(),
    area: Boolean = false,
    trend: List<TPoint> = emptyList(),
    trendColor: Color = color,
    overlays: List<TOverlay> = emptyList(),
    yMin: Double? = null,
    yMax: Double? = null,
    yTicks: List<Double>? = null,
    minPad: Double = 1.0,
    gridLines: Boolean = true,
    yFormat: (Double) -> String = { Fmt.compact(it) },
    secondaryFormat: (Double) -> String = { Fmt.compact(it) },
    tagFormat: (Double) -> String = { Fmt.num(it) },
    breakGaps: Boolean = false,
    showExtremes: Boolean = true,
    zoomable: Boolean = false,
    minSpanDays: Double = 7.0,
    animateKey: Any? = levels,
) {
    val hb = LocalHB.current
    val measurer = rememberTextMeasurer()
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val reveal = remember { Animatable(if (hb.reducedMotion) 1f else 0f) }
    LaunchedEffect(animateKey) {
        if (hb.reducedMotion) reveal.snapTo(1f) else { reveal.snapTo(0f); reveal.animateTo(1f, tween(450, easing = EmphasizedDecelerate)) }
    }
    val bars = style == TimeStyle.Bars
    val hasSecondary = overlays.any { it.secondary && it.levels.any { l -> l.points.isNotEmpty() } }
    val leftGutter = if (hasSecondary) 36.dp else 0.dp
    val rightGutter = 36.dp
    var widthPx by remember { mutableIntStateOf(0) }
    val plotPx = with(density) { (widthPx - (leftGutter + rightGutter).toPx()).coerceAtLeast(0f) }
    val minGapPx = with(density) { (if (bars) 6.dp else 2.5.dp).toPx() }
    val s0 = remember(start) { chartMinutes(start) }
    val s1 = remember(end) { chartMinutes(end) }
    SideEffect { state.configure(levels, s0, s1, bars, plotPx, minGapPx, minSpanDays * 1_440.0) }
    val calloutState by rememberUpdatedState(callout)
    val trendLevel = remember(trend) { if (trend.isEmpty()) null else TLevel(ChartGrain.DAY, trend, bucketed = false) }
    val reduced = hb.reducedMotion

    fun minutesAt(x: Float): Double {
        val w = (state.plotRight - state.plotLeft).coerceAtLeast(1f)
        return state.v0 + ((x - state.plotLeft) / w).toDouble() * (state.v1 - state.v0)
    }

    fun selectAt(x: Float, tick: Boolean) {
        val li = state.level
        val lv = state.levels.getOrNull(li) ?: return
        val i = lv.nearest(minutesAt(x))
        if (i < 0) return
        val next = ChartSel(li, i)
        if (next != state.selection) {
            state.selection = next
            if (tick) haptics.tick()
        }
    }

    fun step(dir: Int): Boolean {
        val li = state.level
        val lv = state.levels.getOrNull(li) ?: return false
        if (lv.points.isEmpty()) return false
        val cur = state.point()
        val i = if (cur == null) (if (dir > 0) lv.lowerBound(state.v0) else lv.lowerBound(state.v1) - 1)
        else { val k = lv.nearest(cur.x); if (state.selection?.level == li) k + dir else k }
        val j = i.coerceIn(0, lv.points.lastIndex)
        state.selection = ChartSel(li, j)
        state.ensureVisible(lv.points[j].x)
        return true
    }

    val actions = buildList {
        add(CustomAccessibilityAction("Next point") { step(1) })
        add(CustomAccessibilityAction("Previous point") { step(-1) })
        if (zoomable) {
            add(CustomAccessibilityAction("Zoom in") {
                val c = state.point()?.x ?: ((state.v0 + state.v1) / 2.0)
                val span = (state.v1 - state.v0) / 3.0
                scope.launch { state.animateView(c - span / 2.0, c + span / 2.0, reduced) }; true
            })
            add(CustomAccessibilityAction("Show the whole range") { scope.launch { state.resetZoom(reduced) }; true })
        }
        add(CustomAccessibilityAction("Clear selection") { state.selection = null; true })
    }

    Layout(
        content = {
            Canvas(Modifier.fillMaxSize()) {
                drawTimeChart(
                    state, hb, measurer, style, color, barColors, goal, refs, band, zones, zoneLabels, area, trendLevel, trendColor,
                    overlays, yMin, yMax, yTicks, minPad, gridLines, yFormat, secondaryFormat, tagFormat, breakGaps, showExtremes,
                    leftGutter.toPx(), rightGutter.toPx(), reveal.value,
                )
            }
            // v4.1 review: narrow charts (cover screen) get the compact one-line callout so it doesn't cover the plot
            val compact = with(density) { widthPx > 0 && widthPx.toDp() < 360.dp }
            CalloutHost(state, calloutState, compact, onAction = { p -> scope.launch { state.selection = null; state.zoomInto(p, reduced) } })
        },
        modifier = modifier.fillMaxWidth().height(height)
            .onSizeChanged { widthPx = it.width }
            .onGloballyPositioned { state.coords = it }
            .semantics {
                contentDescription = description
                customActions = actions
            }
            .pointerInput(state, zoomable) {
                val slop = viewConfiguration.touchSlop
                val doubleTap = viewConfiguration.doubleTapTimeoutMillis
                var lastTapUp = 0L
                var lastTapPos = Offset.Zero
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val maybeDouble = zoomable && down.uptimeMillis - lastTapUp < doubleTap && (down.position - lastTapPos).getDistance() < slop * 3
                    // the callout handles its own taps (×, action), except the second tap of a double-tap
                    if (!maybeDouble && state.calloutRect?.contains(down.position) == true) return@awaitEachGesture
                    var mode = 0 // 0 undecided (tap), 1 scrub, 2 pinch / pan, 3 page scroll
                    var upTime = 0L
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (zoomable && pressed.size >= 2) mode = 2
                        if (mode == 2) {
                            if (pressed.size >= 2) {
                                val xs = pressed.map { it.position.x }
                                val pxs = pressed.map { it.previousPosition.x }
                                val cur = xs.max() - xs.min()
                                val prev = pxs.max() - pxs.min()
                                val z = if (cur > 24f && prev > 24f) (cur / prev).toDouble() else 1.0
                                val cx = xs.average().toFloat()
                                val pcx = pxs.average().toFloat()
                                val w = (state.plotRight - state.plotLeft).coerceAtLeast(1f)
                                val span = state.v1 - state.v0
                                val anchor = state.v0 + ((pcx - state.plotLeft) / w).toDouble() * span
                                val ns = span / z
                                val na = anchor - ((cx - state.plotLeft) / w).toDouble() * ns
                                state.setView(na, na + ns)
                                ev.changes.forEach { it.consume() }
                            }
                            if (pressed.isEmpty()) break
                            continue
                        }
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) { upTime = ch.uptimeMillis; break }
                        val dx = ch.position.x - down.position.x
                        val dy = ch.position.y - down.position.y
                        if (mode == 0) {
                            if (abs(dx) > slop && abs(dx) > abs(dy)) mode = 1
                            else if (abs(dy) > slop) { mode = 3; break }
                        }
                        if (mode == 1) { selectAt(ch.position.x, tick = true); ch.consume() }
                    }
                    if (mode == 0 && upTime > 0L) {
                        if (maybeDouble) {
                            lastTapUp = 0L
                            val m = minutesAt(down.position.x)
                            val month = 30 * 1_440.0
                            val zoomIn = state.v1 - state.v0 > month * 1.5
                            scope.launch {
                                if (zoomIn) state.animateView(m - month / 2.0, m + month / 2.0, reduced) else state.resetZoom(reduced)
                            }
                        } else {
                            selectAt(down.position.x, tick = true)
                            lastTapUp = upTime
                            lastTapPos = down.position
                        }
                    }
                }
            },
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val canvas = measurables[0].measure(Constraints.fixed(w, h))
        val maxCw = min(w, 288.dp.roundToPx())
        val card = measurables.getOrNull(1)?.measure(Constraints(minWidth = 0, maxWidth = maxCw, minHeight = 0, maxHeight = h))
        layout(w, h) {
            canvas.place(0, 0)
            if (card == null || card.width == 0) { state.calloutRect = null; return@layout }
            val p = state.point()
            val pw = (state.plotRight - state.plotLeft).coerceAtLeast(1f)
            val span = (state.v1 - state.v0).coerceAtLeast(1.0)
            val px = if (p == null) w / 2f else state.plotLeft + ((p.x - state.v0) / span).toFloat() * pw
            val x = (px - card.width / 2f).coerceIn(0f, max(0f, (w - card.width).toFloat()))
            // keep the selected point visible: the card goes to the side of the plot away from it
            val py = if (p == null) state.plotBottom else {
                state.plotBottom - ((p.v - state.yLo) / (state.yHi - state.yLo).coerceAtLeast(1e-9)).toFloat() * (state.plotBottom - state.plotTop)
            }
            val gap = 10.dp.toPx()
            val atBottom = py < card.height + gap && py < (state.plotTop + state.plotBottom) / 2f
            val y = if (atBottom) max(0f, state.plotBottom - card.height) else 0f
            state.calloutRect = Rect(x, y, x + card.width, y + card.height)
            card.place(x.roundToInt(), y.roundToInt())
        }
    }
}

@Composable
private fun CalloutHost(state: TimeChartState, build: (ChartSel, TLevel, TPoint) -> ChartCallout?, compact: Boolean, onAction: (TPoint) -> Unit) {
    val sel = state.selection ?: return
    val lv = state.levels.getOrNull(sel.level) ?: return
    val p = lv.points.getOrNull(sel.index) ?: return
    val c = remember(sel, lv, build) { build(sel, lv, p) } ?: return
    if (compact) ChartCalloutLine(c, onClose = { state.selection = null }, onAction = { onAction(p) })
    else ChartCalloutCard(c, onClose = { state.selection = null }, onAction = { onAction(p) })
}

/** Compact callout for narrow charts: one line "date · value unit · delta", a 48 dp close and an optional zoom action. */
@Composable
fun ChartCalloutLine(c: ChartCallout, onClose: () -> Unit, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val hb = LocalHB.current
    val on = hb.colors.onInverse
    Row(
        modifier.heightIn(min = 48.dp).background(hb.colors.inverse, RoundedCornerShape(24.dp)).padding(start = 14.dp)
            .semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite; contentDescription = c.spoken() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(c.title, style = hb.type.labelS, color = on.copy(alpha = 0.78f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(8.dp))
        Text((if (c.prefix.isNotEmpty()) c.prefix + " " else "") + c.value + (if (c.unit.isNotEmpty()) " " + c.unit else ""),
            style = hb.type.label, color = on, maxLines = 1)
        if (c.action != null) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).plainClickable(onClick = onAction).semantics { contentDescription = c.action },
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(Glyph.Search, size = 16.dp, color = on.copy(alpha = 0.85f)) }
        }
        Box(
            Modifier.size(48.dp).clip(CircleShape).plainClickable(onClick = onClose).semantics { contentDescription = "Close" },
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(Glyph.Close, size = 16.dp, color = on.copy(alpha = 0.8f)) }
    }
}

/** The callout: inverse surface, r 16, date 12/600, value 22/700 + unit, overlay lines, delta, source, action. */
@Composable
fun ChartCalloutCard(c: ChartCallout, onClose: () -> Unit, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val hb = LocalHB.current
    val on = hb.colors.onInverse
    Column(
        modifier.widthIn(min = 168.dp, max = 288.dp)
            .background(hb.colors.inverse, RoundedCornerShape(16.dp))
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(
                Modifier.weight(1f, fill = false).padding(top = 8.dp, end = 4.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite; contentDescription = c.spoken() },
            ) {
                Text(c.title, style = hb.type.labelS, color = on.copy(alpha = 0.78f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.Bottom) {
                    if (c.prefix.isNotEmpty()) {
                        Text(c.prefix + " ", style = hb.type.bodyXS, color = on.copy(alpha = 0.78f), modifier = Modifier.padding(bottom = 3.dp))
                    }
                    Text(c.value, style = hb.type.numberS, color = on, maxLines = 1)
                    if (c.unit.isNotEmpty()) Text(" " + c.unit, style = hb.type.bodyXS.copy(fontWeight = FontWeight.Medium), color = on.copy(alpha = 0.78f),
                        modifier = Modifier.padding(bottom = 3.dp), maxLines = 1)
                }
            }
            Box(
                Modifier.size(48.dp).clip(CircleShape).plainClickable(onClick = onClose)
                    .semantics { contentDescription = "Close" },
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(Glyph.Close, size = 16.dp, color = on.copy(alpha = 0.8f)) }
        }
        Column(Modifier.padding(end = 10.dp)) {
            c.lines.forEach { l ->
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (l.color != null) { Dot(l.color, size = 7.dp); Spacer(Modifier.width(6.dp)) }
                    Text(l.label, style = hb.type.bodyXS, color = on.copy(alpha = 0.78f), maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                    Text(l.value, style = hb.type.bodyXS.copy(fontWeight = FontWeight.SemiBold), color = on, maxLines = 1)
                }
            }
            if (c.delta != null) Text(c.delta, style = hb.type.bodyXS, color = on, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            val src = listOfNotNull(c.source, c.note).joinToString(" · ")
            if (src.isNotEmpty()) Text(src, style = hb.type.caption, color = on.copy(alpha = 0.7f), maxLines = 2, modifier = Modifier.padding(top = 2.dp))
            if (c.action != null) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(on.copy(alpha = 0.14f))
                        .plainClickable(onClick = onAction).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(c.action, style = hb.type.labelS, color = on, maxLines = 1) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Time chart drawing
// ---------------------------------------------------------------------------------------------------------------------

private val TAG_STYLE = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")

/** Visible index range [from, to) of a level for minutes a..b (bars: any overlap). */
private fun visibleRange(lv: TLevel, a: Double, b: Double, bars: Boolean): IntRange {
    val half = if (bars) lv.grain.minutes / 2.0 else 0.0
    val i0 = lv.lowerBound(a - half)
    val i1 = lv.lowerBound(b + half + 1e-6)
    return i0 until i1
}

private fun DrawScope.drawTimeChart(
    state: TimeChartState,
    hb: HB,
    measurer: TextMeasurer,
    style: TimeStyle,
    color: Color,
    barColors: Pair<Color, Color>,
    goal: Double?,
    refs: List<RefLine>,
    band: NormalBand?,
    zones: List<ZoneBand>,
    zoneLabels: List<ZoneLabel>,
    area: Boolean,
    trend: TLevel?,
    trendColor: Color,
    overlays: List<TOverlay>,
    yMin: Double?,
    yMax: Double?,
    yTicks: List<Double>?,
    minPad: Double,
    gridLines: Boolean,
    yFormat: (Double) -> String,
    secondaryFormat: (Double) -> String,
    tagFormat: (Double) -> String,
    breakGaps: Boolean,
    showExtremes: Boolean,
    leftGutter: Float,
    rightGutter: Float,
    reveal: Float,
) {
    val bars = style == TimeStyle.Bars
    val li = state.level
    val lv = state.levels.getOrNull(li)
    val a = state.v0
    val b = state.v1
    val span = max(1.0, b - a)
    val left = leftGutter
    val right = size.width - rightGutter
    val top = 18.dp.toPx()
    val bottom = size.height - 26.dp.toPx()
    val plotW = max(1f, right - left)
    state.plotLeft = left; state.plotRight = right; state.plotTop = top; state.plotBottom = bottom
    fun xOf(m: Double): Float = left + ((m - a) / span).toFloat() * plotW

    val pts = lv?.points.orEmpty()
    val vis = if (lv == null) IntRange.EMPTY else visibleRange(lv, a, b, bars)
    val grain = lv?.grain
    fun overlayLevel(o: TOverlay): TLevel? = o.levels.firstOrNull { it.grain == grain } ?: o.levels.firstOrNull()

    // ---- y range of what is visible
    var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
    for (k in vis) { val v = pts[k].v; if (!v.isNaN()) { lo = min(lo, v); hi = max(hi, v) } }
    overlays.filter { !it.secondary }.forEach { o ->
        overlayLevel(o)?.let { ol -> for (k in visibleRange(ol, a, b, false)) { lo = min(lo, ol.points[k].v); hi = max(hi, ol.points[k].v) } }
    }
    val hasData = lo <= hi
    if (!hasData) { lo = 0.0; hi = 1.0 }
    band?.let { lo = min(lo, it.low); hi = max(hi, it.high) }
    val dataLo = lo; val dataHi = hi
    val shownRefs = ArrayList<RefLine>()
    val edgeRefs = ArrayList<RefLine>()
    if (bars) {
        lo = 0.0
        hi = max(hi, goal ?: 0.0) * 1.08
        shownRefs += refs
    } else {
        val reach = max(dataHi - dataLo, minPad * 4)
        refs.forEach { r ->
            if (r.value >= dataLo - reach && r.value <= dataHi + reach) { shownRefs += r; lo = min(lo, r.value); hi = max(hi, r.value) } else edgeRefs += r
        }
        val pad = max((hi - lo) * 0.10, minPad)
        lo -= pad; hi += pad
    }
    val ticks = yTicks ?: niceTicks(yMin ?: lo, yMax ?: hi, 4)
    val yLo = yMin ?: ticks.first()
    val yHi = yMax ?: ticks.last()
    state.yLo = yLo; state.yHi = yHi
    fun yOf(v: Double): Float = bottom - ((v - yLo) / (yHi - yLo).coerceAtLeast(1e-9)).toFloat() * (bottom - top)

    // secondary axis (fat % over weight)
    val sec = overlays.filter { it.secondary }
    var lo2 = Double.MAX_VALUE; var hi2 = -Double.MAX_VALUE
    sec.forEach { o -> overlayLevel(o)?.let { ol -> for (k in visibleRange(ol, a, b, false)) { lo2 = min(lo2, ol.points[k].v); hi2 = max(hi2, ol.points[k].v) } } }
    val ticks2 = if (lo2 <= hi2) niceTicks(lo2 - max((hi2 - lo2) * 0.1, 0.5), hi2 + max((hi2 - lo2) * 0.1, 0.5), 4) else emptyList()
    val y2Lo = ticks2.firstOrNull() ?: 0.0
    val y2Hi = ticks2.lastOrNull() ?: 1.0
    fun yOf2(v: Double): Float = bottom - ((v - y2Lo) / (y2Hi - y2Lo).coerceAtLeast(1e-9)).toFloat() * (bottom - top)

    val muted = TextStyle(fontSize = 11.sp, color = hb.colors.muted, fontFeatureSettings = "tnum")

    // ---- zones + labels
    zones.forEach { z ->
        val ya = yOf(min(z.to, yHi)); val yb = yOf(max(z.from, yLo))
        if (yb > ya) drawRect(z.color, Offset(left, ya), Size(plotW, yb - ya))
    }
    zoneLabels.forEach { z ->
        val mid = (max(z.from, yLo) + min(z.to, yHi)) / 2.0
        val r = measurer.measure(z.text, TextStyle(fontSize = 11.sp, fontWeight = if (z.bold) FontWeight.Bold else FontWeight.SemiBold, color = z.color))
        val y = yOf(mid) - r.size.height / 2f
        if (y > top - 4f && y + r.size.height < bottom + 4f) drawText(r, topLeft = Offset(left + 8.dp.toPx(), y))
    }
    // ---- personal normal band
    band?.let { bd ->
        val ya = yOf(bd.high); val yb = yOf(bd.low)
        drawRoundRect(hb.band(bd.color), Offset(left, ya), Size(plotW, max(1f, yb - ya)), CornerRadius(4.dp.toPx()))
        if (bd.label != null) {
            val r = measurer.measure(bd.label, TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = hb.legible(bd.color)))
            drawText(r, topLeft = Offset(left + 4.dp.toPx(), max(0f, ya - 4.dp.toPx() - r.size.height)))
        }
    }
    // ---- gridlines + y labels (right gutter)
    ticks.filter { it >= yLo - 1e-9 && it <= yHi + 1e-9 }.forEach { v ->
        val y = yOf(v)
        if (gridLines) drawLine(hb.colors.grid, Offset(left, y), Offset(right, y), 1.dp.toPx())
        val r = measurer.measure(yFormat(v), muted)
        drawText(r, topLeft = Offset(size.width - r.size.width, y - r.size.height / 2f))
    }
    if (sec.isNotEmpty() && ticks2.isNotEmpty()) {
        val c2 = hb.legible(sec.first().color)
        ticks2.forEach { v ->
            val r = measurer.measure(secondaryFormat(v), TextStyle(fontSize = 11.sp, color = c2, fontFeatureSettings = "tnum"))
            drawText(r, topLeft = Offset(0f, yOf2(v) - r.size.height / 2f))
        }
    }
    // ---- x labels: adaptive step, never overlapping
    // v6 I3: bucketed levels (weeks / months) put their points at the bucket middle, so the labels follow, like bars
    drawTimeAxis(measurer, muted, a, b, left, right, bottom + 8.dp.toPx(), if (bars || lv?.bucketed == true) grain else null)

    // ---- series, clipped to the plot and revealed left → right
    val gapLimit = (grain?.minutes ?: 1_440.0) * 1.6
    clipRect(left = left, top = 0f, right = left + plotW * reveal + 1f, bottom = size.height) {
        // overlays (secondary first)
        (sec + overlays.filter { !it.secondary }).forEach { o ->
            val ol = overlayLevel(o) ?: return@forEach
            val r = expand(visibleRange(ol, a, b, false), ol.points.size)
            val opts = r.map { k -> Offset(xOf(ol.points[k].x), if (o.secondary) yOf2(ol.points[k].v) else yOf(ol.points[k].v)) }
            if (opts.size == 1) drawCircle(o.color, 2.5.dp.toPx(), opts[0])
            else if (opts.size > 1) drawPath(
                linePath(opts, ol.bucketed), o.color,
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                    pathEffect = if (o.dashed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null),
            )
        }
        if (lv != null && pts.isNotEmpty()) {
            val drawRange = expand(vis, pts.size)
            when {
                bars -> {
                    val sel = state.selection
                    val (c0, c1) = barColors
                    val base = yOf(0.0)
                    for (k in vis) {
                        val p = pts[k]
                        val slot = ((p.t1 - p.t0) / span).toFloat() * plotW
                        val bw = (slot * 0.62f).coerceIn(2.dp.toPx(), 24.dp.toPx())
                        val cx = xOf(p.x)
                        val yv = yOf(p.v)
                        val h = max(min(bw, 6.dp.toPx()), (base - yv) * reveal)
                        if (p.v <= 0.0) continue
                        val isSel = sel != null && sel.level == li && sel.index == k
                        val alpha = if (sel == null || isSel) 1f else 0.45f
                        val rr = RoundRect(cx - bw / 2f, base - h, cx + bw / 2f, base, CornerRadius(min(bw / 2f, 6.dp.toPx())))
                        val path = Path().apply { addRoundRect(rr) }
                        val segs = p.segments?.filter { it.first > 0 }
                        if (!segs.isNullOrEmpty()) {
                            // stacked: solid zone colours, 1 dp tile-coloured gaps, clipped to the rounded bar; no outlines
                            val tot = segs.sumOf { it.first }.takeIf { it > 0 } ?: 1.0
                            clipPath(path) {
                                var yy = base
                                segs.forEachIndexed { j, (m, c) ->
                                    val sh = (h * (m / tot)).toFloat()
                                    drawRect(c.copy(alpha = alpha), Offset(cx - bw / 2f, yy - sh), Size(bw, sh))
                                    yy -= sh
                                    if (j < segs.size - 1) drawRect(hb.colors.tile, Offset(cx - bw / 2f, yy - 0.5.dp.toPx()), Size(bw, 1.dp.toPx()))
                                }
                            }
                            continue
                        }
                        val met = goal == null || p.v >= goal
                        if (met) drawPath(path, Brush.verticalGradient(listOf(c1.copy(alpha = alpha), c0.copy(alpha = alpha)), startY = base - h, endY = base))
                        else { val bc = hb.belowGoal(c0); drawPath(path, bc.copy(alpha = bc.alpha * alpha)) }
                    }
                }
                style == TimeStyle.Dots && !lv.bucketed -> {
                    // calculated trend (light), else a faint connector so the shape still reads
                    val tr = if (trend != null && trend.points.isNotEmpty()) {
                        val tp = trend.points
                        expand(visibleRange(trend, a, b, false), tp.size).map { Offset(xOf(tp[it].x), yOf(tp[it].v)) }
                    } else emptyList()
                    if (tr.size > 1) drawPath(linePath(tr, true), trendColor.copy(alpha = 0.55f), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    else {
                        val con = drawRange.map { Offset(xOf(pts[it].x), yOf(pts[it].v)) }
                        if (con.size > 1) drawPath(linePath(con, false), color.copy(alpha = 0.28f), style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round))
                    }
                    val dense = vis.last - vis.first + 1 > 120
                    val r = (if (dense) 2.25.dp else 3.25.dp).toPx()
                    for (k in vis) {
                        val o = Offset(xOf(pts[k].x), yOf(pts[k].v))
                        drawCircle(hb.colors.surface, r + 1.25.dp.toPx(), o)
                        drawCircle(color, r, o)
                    }
                }
                else -> {
                    val runsList = ArrayList<List<Offset>>()
                    var cur = ArrayList<Offset>()
                    var prevX = Double.NaN
                    for (k in drawRange) {
                        val p = pts[k]
                        if (p.v.isNaN()) { if (cur.isNotEmpty()) runsList += cur; cur = ArrayList(); prevX = Double.NaN; continue }
                        if (breakGaps && !prevX.isNaN() && p.x - prevX > gapLimit && cur.isNotEmpty()) { runsList += cur; cur = ArrayList() }
                        cur += Offset(xOf(p.x), yOf(p.v)); prevX = p.x
                    }
                    if (cur.isNotEmpty()) runsList += cur
                    runsList.forEach { run ->
                        if (run.size == 1) { drawCircle(color, 2.5.dp.toPx(), run[0]); return@forEach }
                        val line = linePath(run, true)
                        if (area) {
                            val ap = Path().apply { addPath(line); lineTo(run.last().x, bottom); lineTo(run.first().x, bottom); close() }
                            drawPath(ap, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0f)), startY = run.minOf { it.y }, endY = bottom))
                        }
                        drawPath(line, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    // bucket levels of measurements: small dots at the averages
                    if (style == TimeStyle.Dots && vis.last - vis.first + 1 <= 120) for (k in vis) {
                        val o = Offset(xOf(pts[k].x), yOf(pts[k].v))
                        drawCircle(hb.colors.surface, 3.5.dp.toPx(), o)
                        drawCircle(color, 2.25.dp.toPx(), o)
                    }
                }
            }
        }
    }

    // ---- reference / goal lines (labelled); off-scale goals as an edge tag
    shownRefs.forEach { r ->
        if (r.value < yLo || r.value > yHi) return@forEach
        val y = yOf(r.value)
        val col = r.color ?: hb.colors.text.copy(alpha = 0.55f)
        drawLine(col, Offset(left, y), Offset(right, y), 1.5.dp.toPx(), cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
        if (r.label != null) labelPill(measurer, hb, r.label, if (r.labelAtEnd) right else left, y - 6.dp.toPx(),
            if (r.color != null) hb.legible(r.color) else hb.colors.text, r.labelAtEnd)
    }
    edgeRefs.forEach { r ->
        if (r.label == null) return@forEach
        val below = r.value < yLo
        val text = r.label + if (below) " ↓" else " ↑"
        labelPill(measurer, hb, text, right, if (below) bottom - 2.dp.toPx() else top + 20.dp.toPx(), hb.colors.muted, alignEnd = true)
    }

    // ---- min / max / latest of the visible range (hidden while a point is selected)
    if (showExtremes && state.selection == null && hasData && vis.last - vis.first + 1 >= 3 && reveal >= 0.999f) {
        // R5 #29: on bucketed levels (weeks / months) a point is a mean, so High / Low / Latest show real readings:
        // the bucket's highest / lowest reading and the last raw reading; the tag stays anchored at the bucket mark.
        val bucketed = lv?.bucketed == true
        fun hiOf(k: Int) = if (bucketed) pts[k].hi else pts[k].v
        fun loOf(k: Int) = if (bucketed) pts[k].lo else pts[k].v
        var iMax = vis.first; var iMin = vis.first
        for (k in vis) { if (hiOf(k) > hiOf(iMax)) iMax = k; if (loOf(k) < loOf(iMin)) iMin = k }
        val iLast = vis.last
        val lastValue = if (!bucketed) pts[iLast].v else {
            val lp = pts[iLast]
            state.levels.firstOrNull { !it.bucketed }?.points?.getOrNull(lp.ref + lp.n - 1)?.v ?: lp.v
        }
        val placed = ArrayList<Rect>()
        fun tag(k: Int, label: String, above: Boolean, value: Double = pts[k].v) {
            val p = pts[k]
            val x = xOf(p.x); val y = yOf(p.v)
            val r = measurer.measure("$label ${tagFormat(value)}", TAG_STYLE.copy(color = hb.colors.text))
            val padH = 6.dp.toPx(); val hgt = r.size.height + 4.dp.toPx(); val wdt = r.size.width + padH * 2
            val bx = (x - wdt / 2f).coerceIn(left, max(left, right - wdt))
            var by = if (above) y - 8.dp.toPx() - hgt else y + 8.dp.toPx()
            if (by < 0f) by = y + 8.dp.toPx()
            if (by + hgt > bottom) by = y - 8.dp.toPx() - hgt
            val rect = Rect(bx, by, bx + wdt, by + hgt)
            if (placed.any { it.overlaps(rect) }) return
            placed += rect
            if (!bars) {
                drawCircle(hb.colors.surface, 5.dp.toPx(), Offset(x, y))
                drawCircle(color, 5.dp.toPx(), Offset(x, y), style = Stroke(2.dp.toPx()))
            }
            drawRoundRect(hb.colors.tile.copy(alpha = 0.92f), Offset(bx, by), Size(wdt, hgt), CornerRadius(hgt / 2f))
            drawText(r, topLeft = Offset(bx + padH, by + 2.dp.toPx()))
        }
        if (bucketed) {
            tag(iMax, "High", above = true, value = hiOf(iMax))
            if (!bars) tag(iMin, "Low", above = false, value = loOf(iMin))
            if (!bars && iLast != iMax && iLast != iMin) {
                val mid = (pts[iMax].v + pts[iMin].v) / 2.0
                tag(iLast, "Latest", above = pts[iLast].v < mid, value = lastValue)
            }
        } else {
            val lastIsMax = iLast == iMax; val lastIsMin = iLast == iMin
            tag(iMax, if (lastIsMax) "Latest · high" else "High", above = true)
            if (!bars) tag(iMin, if (lastIsMin) "Latest · low" else "Low", above = false)
            if (!lastIsMax && !lastIsMin && !bars) {
                val mid = (pts[iMax].v + pts[iMin].v) / 2.0
                tag(iLast, "Latest", above = pts[iLast].v < mid)
            }
        }
    }

    // ---- selection marker
    val sp = state.point()
    if (sp != null) {
        val x = xOf(sp.x)
        if (x >= left - 1f && x <= right + 1f) {
            val y = yOf(sp.v)
            drawLine(hb.colors.accent.copy(alpha = 0.6f), Offset(x, 0f), Offset(x, bottom), 1.5.dp.toPx())
            if (!bars) {
                drawCircle(hb.colors.accent, 9.dp.toPx(), Offset(x, y))
                drawCircle(hb.colors.surface, 7.dp.toPx(), Offset(x, y))
                drawCircle(color, 5.dp.toPx(), Offset(x, y))
            } else {
                drawCircle(hb.colors.accent, 4.dp.toPx(), Offset(x, min(y, bottom)))
            }
        }
    }
}

/** [r] widened by one point on each side (lines continue to the edge of the plot). */
private fun expand(r: IntRange, n: Int): IntRange = if (n == 0) IntRange.EMPTY else max(0, r.first - 1)..min(n - 1, if (r.isEmpty()) r.first else r.last + 1)

/**
 * X axis for minutes [a]..[b]: the smallest step (hours, days, weeks, months, years) whose labels fit with a 12 dp gap,
 * aligned to calendar boundaries; a label that would still overlap its neighbour is skipped. Works from ~300 dp up.
 */
private fun DrawScope.drawTimeAxis(m: TextMeasurer, style: TextStyle, a: Double, b: Double, left: Float, right: Float, top: Float, barGrain: ChartGrain? = null) {
    val span = b - a
    val plotW = right - left
    val gap = 12.dp.toPx()
    fun pxPer(minutes: Double) = (minutes / span).toFloat() * plotW
    val wDay = m.measure("30 Sep", style).size.width
    val wMon = m.measure("Sep 26", style).size.width
    val wHour = m.measure("00:00", style).size.width
    val wYear = m.measure("2026", style).size.width
    val t0 = chartTime(a); val t1 = chartTime(b)
    val out = ArrayList<Pair<LocalDateTime, String>>()
    // bar charts: a label sits under the middle of its own bar (same unit as the bars), else on the boundary
    var shift: (LocalDateTime) -> Double = { 0.0 }
    val hourSteps = listOf(1L, 2L, 3L, 6L, 12L)
    val daySteps = listOf(1L, 2L, 7L, 14L)
    val monthSteps = listOf(1L, 2L, 3L, 6L)
    val hs = hourSteps.firstOrNull { pxPer(it * 60.0) >= wHour + gap }
    val ds = daySteps.firstOrNull { pxPer(it * 1_440.0) >= wDay + gap }
    val ms = monthSteps.firstOrNull { pxPer(it * 43_830.0) >= wMon + gap }
    val f = { p: String -> DateTimeFormatter.ofPattern(p, loc) }
    when {
        span <= 2 * 1_440.0 && hs != null -> {
            var t = t0.truncatedTo(ChronoUnit.HOURS)
            while (t.hour % hs.toInt() != 0) t = t.plusHours(1)
            while (t <= t1) { out += t to (if (t.hour == 0) f("EEE").format(t) else f("HH:mm").format(t)); t = t.plusHours(hs) }
            if (barGrain == ChartGrain.HOUR) shift = { 30.0 }
        }
        ds != null -> {
            var d = t0.toLocalDate()
            if (ds >= 7) d = d.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            if (ds == 2L && d.toEpochDay() % 2 != 0L) d = d.plusDays(1)
            if (ds == 14L && (d.toEpochDay() / 7) % 2 != 0L) d = d.plusWeeks(1)
            var prevMonth = -1
            val short = span <= 8 * 1_440.0 && pxPer(1_440.0) >= m.measure("Wed 30", style).size.width + gap
            while (!d.atStartOfDay().isAfter(t1)) {
                val lbl = when {
                    short -> f("EEE d").format(d)
                    d.monthValue != prevMonth -> f("d MMM").format(d)
                    else -> d.dayOfMonth.toString()
                }
                prevMonth = d.monthValue
                out += d.atStartOfDay() to lbl
                d = d.plusDays(ds)
            }
            if (barGrain == ChartGrain.DAY) shift = { 720.0 }
            // week buckets (3M): a Monday label sits under the middle of its week, where the selection pill is
            if (barGrain == ChartGrain.WEEK && ds >= 7) shift = { 3.5 * 1_440.0 }
        }
        ms != null -> {
            var d = t0.toLocalDate().withDayOfMonth(1)
            while ((d.year * 12 + d.monthValue - 1) % ms.toInt() != 0) d = d.plusMonths(1)
            var first = true
            while (!d.atStartOfDay().isAfter(t1)) {
                val withYear = first || d.monthValue == 1
                out += d.atStartOfDay() to (if (withYear) f("MMM yy").format(d) else f("MMM").format(d))
                first = false
                d = d.plusMonths(ms)
            }
            if (barGrain == ChartGrain.MONTH) shift = { t -> t.toLocalDate().lengthOfMonth() * 720.0 }
        }
        else -> {
            // month buckets (All): a year label sits under the middle of January
            if (barGrain == ChartGrain.MONTH) shift = { t -> t.toLocalDate().lengthOfMonth() * 720.0 }
            val ys = listOf(1, 2, 5, 10).firstOrNull { pxPer(it * 525_960.0) >= wYear + gap } ?: 10
            var y = t0.year
            while (y % ys != 0) y++
            while (LocalDate.of(y, 1, 1).atStartOfDay() <= t1) { out += LocalDate.of(y, 1, 1).atStartOfDay() to y.toString(); y += ys }
        }
    }
    var lastRight = -Float.MAX_VALUE
    out.forEach { (t, s) ->
        val x = left + ((chartMinutes(t) + shift(t) - a) / span).toFloat() * plotW
        if (x < left - 1f || x > right + 1f) return@forEach
        val r = m.measure(s, style)
        val l = (x - r.size.width / 2f).coerceIn(left, max(left, right - r.size.width))
        if (l < lastRight + 8.dp.toPx()) return@forEach
        drawLine(style.color.copy(alpha = 0.35f), Offset(x, top - 8.dp.toPx()), Offset(x, top - 4.dp.toPx()), 1.dp.toPx())
        drawText(r, topLeft = Offset(l, top))
        lastRight = l + r.size.width
    }
}
