package com.boudhealth.ui.section

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.data.Grain
import com.boudhealth.data.PeriodTrend
import com.boudhealth.data.SectionPeriod
import com.boudhealth.data.SeriesBucket
import com.boudhealth.data.TrendDir
import com.boudhealth.ui.theme.LocalHB
import java.time.LocalDate
import java.time.format.TextStyle as JTextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

// =====================================================================================================================
// Section kit: formatting + the small visuals every section card uses (V2_PLAN §2.2 card anatomy).
// TrendArrow / SegPill are copies of the ui/home/MiniKit helpers (ownership note §3.2), adapted to V2 types.
// =====================================================================================================================

/** Previous-period ghost (canonical type: com.boudhealth.data.Ghost), aligned to the current buckets by index. */
typealias Ghost = com.boudhealth.data.Ghost

/** Number formatting used on section cards: "20,076", "1.98M" (compact only from 1 M on rings / hero). */
object SFmt {
    fun int(v: Double): String = String.format(Locale.US, "%,d", v.roundToLong())
    fun dec(v: Double, decimals: Int = 1): String = String.format(Locale.US, "%,.${decimals}f", v)
    fun num(v: Double, decimals: Int): String = if (decimals <= 0) int(v) else dec(v, decimals)

    /** Ring pills / hero totals: full number below 100,000; "1.98M" / "412K" above. */
    fun compact(v: Double): String = when {
        abs(v) >= 1_000_000 -> String.format(Locale.US, "%.2fM", v / 1_000_000.0).replace(".00M", "M")
        abs(v) >= 100_000 -> String.format(Locale.US, "%.0fK", v / 1_000.0)
        else -> int(v)
    }

    /** Signed delta "+105" / "−8,369" (true minus sign). */
    fun delta(v: Double, decimals: Int = 0): String {
        val s = num(abs(v), decimals)
        return if (v < 0) "−$s" else "+$s"
    }

    /** Sleep minutes → "7h 23m". */
    fun hm(minutes: Int): String = if (minutes < 60) "${minutes}m" else "${minutes / 60}h ${String.format(Locale.US, "%02d", minutes % 60)}m"

    fun monthLetter(m: Int): String = java.time.Month.of(m).getDisplayName(JTextStyle.NARROW, Locale.ENGLISH)
    fun dayLetter(d: LocalDate): String = d.dayOfWeek.getDisplayName(JTextStyle.NARROW, Locale.ENGLISH)
    fun shortDate(d: LocalDate): String = "${d.dayOfMonth} ${d.month.getDisplayName(JTextStyle.SHORT, Locale.ENGLISH)}"
    fun monthYear(year: Int, month: Int): String = "${java.time.Month.of(month).getDisplayName(JTextStyle.FULL, Locale.ENGLISH)} $year"
}

/**
 * Trend pill "▲ +105" (green when good, red when bad, neutral when the metric has no polarity).
 * Hidden when the trend has no number (daily values: direction only, never shown as a number).
 */
@Composable
fun TrendArrow(trend: PeriodTrend?, modifier: Modifier = Modifier, decimals: Int = 0) {
    val t = trend ?: return
    val change = t.change ?: return
    if (t.dir == TrendDir.FLAT && abs(change) < 1e-9) return
    val hb = LocalHB.current
    val (bg, fg) = when (t.good) {
        true -> hb.colors.goodCont to hb.colors.good
        false -> hb.colors.badCont to hb.colors.bad
        null -> hb.colors.tile to hb.colors.muted
    }
    val arrow = when (t.dir) { TrendDir.UP -> "▲"; TrendDir.DOWN -> "▼"; TrendDir.FLAT -> "●" }
    Row(modifier.height(26.dp).background(bg, RoundedCornerShape(13.dp)).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(arrow, fontSize = 10.sp, color = fg)
        Spacer(Modifier.width(5.dp))
        Text(SFmt.delta(change, decimals), style = hb.type.labelS.copy(fontSize = 12.5.sp), color = fg, maxLines = 1)
    }
}

/** Stacked pill of proportional segments (zone minutes, sleep stages). Empty = track. */
@Composable
fun SegPill(parts: List<Pair<Double, Color>>, modifier: Modifier = Modifier, h: Dp = 10.dp, gap: Dp = 2.dp) {
    val hb = LocalHB.current
    Canvas(modifier.height(h)) {
        val tot = parts.sumOf { max(0.0, it.first) }
        val r = CornerRadius(size.height / 2f)
        if (tot <= 0) { drawRoundRect(hb.colors.tileHi, Offset.Zero, size, r); return@Canvas }
        val g = gap.toPx()
        val shown = parts.filter { it.first > 0 }
        val avail = size.width - g * (shown.size - 1).coerceAtLeast(0)
        var x = 0f
        shown.forEach { (v, c) ->
            val w = (avail * (v / tot)).toFloat()
            drawRoundRect(c, Offset(x, 0f), Size(max(1f, w), size.height), CornerRadius(size.height / 2f))
            x += w + g
        }
    }
}

/** Legend item "● Fat burn / 146 min" used under zone bars and sleep stage bars. */
@Composable
fun LegendValue(color: Color, label: String, value: String, unit: String?, modifier: Modifier = Modifier) {
    val hb = LocalHB.current
    androidx.compose.foundation.layout.Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(color, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(label, style = hb.type.bodyXS, color = hb.colors.muted, maxLines = 1)
        }
        Text(buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = hb.colors.text)) { append(value) }
            if (unit != null) withStyle(SpanStyle(color = hb.colors.muted, fontSize = 12.sp)) { append(" $unit") }
        }, style = hb.type.bodyS, modifier = Modifier.padding(start = 13.dp), maxLines = 1)
    }
}

/** Headline "351 / 210 min" (design6 kit6.headline): 30 sp bold value, "/ goal" 0.62× bold muted, unit 0.5× regular muted. */
@Composable
fun Headline(value: String, goal: String? = null, unit: String? = null, modifier: Modifier = Modifier, muted: Boolean = false, size: Float = 30f) {
    val hb = LocalHB.current
    Text(buildAnnotatedString {
        withStyle(SpanStyle(fontSize = size.sp, fontWeight = FontWeight.Bold, color = if (muted) hb.colors.muted else hb.colors.text)) { append(value) }
        if (goal != null) withStyle(SpanStyle(fontSize = (size * 0.62f).sp, fontWeight = FontWeight.Bold, color = hb.colors.muted)) { append(" / $goal") }
        if (unit != null) withStyle(SpanStyle(fontSize = (size * 0.5f).sp, fontWeight = FontWeight.Normal, color = hb.colors.muted)) { append(" $unit") }
    }, modifier = modifier, maxLines = 1, style = TextStyle(fontFeatureSettings = "tnum", lineHeight = (size * 1.2f).sp))
}

// =====================================================================================================================
// Period bar chart (all section cards): hourly / daily / monthly buckets, optional stacked segments, goal line,
// today label highlighted, future buckets faded, tap a bucket → onTap(bucket).
// =====================================================================================================================

/** X-axis label for a bucket, or null when it is skipped. */
fun axisLabel(b: SeriesBucket, i: Int, n: Int, grain: Grain, period: SectionPeriod): String? = when (grain) {
    Grain.HOUR -> if (b.start.hour % 6 == 0) b.start.hour.toString() else null
    Grain.DAY -> if (period is SectionPeriod.Week || n <= 7) SFmt.dayLetter(b.day)
        else if (b.day.dayOfMonth == 1 || b.day.dayOfMonth % 7 == 1) b.day.dayOfMonth.toString() else null
    Grain.MONTH -> if (period is SectionPeriod.Year || n <= 12) SFmt.monthLetter(b.day.monthValue)
        else if (b.day.monthValue == 1) b.day.year.toString() else null
    Grain.QUARTER -> if (b.day.monthValue == 1) b.day.year.toString() else null
}

/** Is [b] the bucket holding today (highlighted label). */
fun isTodayBucket(b: SeriesBucket, grain: Grain, today: LocalDate): Boolean = when (grain) {
    Grain.HOUR -> false
    Grain.DAY -> b.day == today
    Grain.MONTH -> b.day.year == today.year && b.day.monthValue == today.monthValue
    Grain.QUARTER -> b.day.year == today.year && (b.day.monthValue - 1) / 3 == (today.monthValue - 1) / 3
}

/** Title of a bucket in a read-out: "14:00–15:00", "Tue 3 Nov", "October 2026", "Q4 2026". */
fun bucketTitle(b: SeriesBucket, grain: Grain): String = when (grain) {
    Grain.HOUR -> String.format(Locale.US, "%02d:00–%02d:00", b.start.hour, (b.start.hour + 1) % 24)
    Grain.DAY -> com.boudhealth.data.Insights.dayLabel(b.day)
    Grain.MONTH -> SFmt.monthYear(b.day.year, b.day.monthValue)
    Grain.QUARTER -> "Q${(b.day.monthValue - 1) / 3 + 1} ${b.day.year}"
}

/** Default read-out of a bucket: date · value unit (+ one line per stacked segment). */
fun bucketCallout(b: SeriesBucket, grain: Grain, unit: String, decimals: Int, segmentNames: List<String>?, segmentUnit: String): com.boudhealth.ui.components.ChartCallout =
    com.boudhealth.ui.components.ChartCallout(
        title = bucketTitle(b, grain) + if (b.partial) " · so far" else "",
        value = b.value?.let { SFmt.num(it, decimals) } ?: "—",
        unit = unit,
        lines = segmentNames?.let { names -> b.segments?.mapIndexed { i, v -> com.boudhealth.ui.components.CalloutLine(names.getOrElse(i) { "" }, "${SFmt.int(v)} $segmentUnit".trim()) } }.orEmpty(),
    )

/**
 * Card bar chart (design6 kit6.bars) for hourly / daily / monthly buckets: optional stacked segments, goal line, today /
 * selected label, future buckets faded, y-axis values on the right.
 * Tap a bar → [onTap] (drill). **Press and hold, then slide** → exact read-out of the bar under the finger (the
 * TimeChart callout line, V2_REMOVALS_AUDIT C1); it closes when the finger lifts.
 */
@Composable
fun PeriodBars(
    buckets: List<SeriesBucket>,
    grain: Grain,
    period: SectionPeriod,
    color: Color,
    today: LocalDate,
    modifier: Modifier = Modifier,
    height: Dp = 118.dp,
    goal: Double? = null,
    /** Stacked colours for [SeriesBucket.segments]; the bar height is then the segment sum (minutes), [valueLabels] show value. */
    segmentColors: List<Color>? = null,
    valueLabels: Boolean = false,
    selected: LocalDate? = null,
    onTap: ((SeriesBucket) -> Unit)? = null,
    unit: String = "",
    decimals: Int = 0,
    segmentNames: List<String>? = null,
    segmentUnit: String = "min",
    yAxis: Boolean = true,
    callout: ((SeriesBucket) -> com.boudhealth.ui.components.ChartCallout)? = null,
    // ---- R3 (IMPL_PLAN §2.4), all additive: the defaults draw exactly what detail pages drew before
    /** Previous period behind the bars (muted 20 %, same radius), aligned by index. */
    ghost: Ghost? = null,
    /** Goal value bold in the right gutter (ticks within 12 dp of it dropped). */
    goalTickBold: Boolean = false,
    /** Highest bar tagged with its value (9.5/700), 16 % headroom. */
    tagHighest: Boolean = false,
    /** Bars below the goal at 42 % (W/M goal charts). */
    dimBelowGoal: Boolean = false,
    /** Shared scrub (hero / card): the held bucket mirrors [ScrubState]. */
    scrub: ScrubState? = null,
) {
    val hb = LocalHB.current
    val measurer = rememberTextMeasurer()
    val haptics = com.boudhealth.ui.components.rememberHaptics()
    val n = buckets.size
    if (n == 0) { Box(modifier.fillMaxWidth().height(height)); return }
    fun barValue(b: SeriesBucket): Double = if (segmentColors != null) (b.segments?.sum() ?: 0.0) else (b.value ?: 0.0)
    fun ghostValue(i: Int): Double = ghost?.let { g -> if (segmentColors != null) (g.segments?.getOrNull(i)?.sum() ?: g.values.getOrNull(i) ?: 0.0) else (g.values.getOrNull(i) ?: 0.0) } ?: 0.0
    val ghostMax = if (ghost != null) (0 until n).maxOf { ghostValue(it) } else 0.0
    val curMax = buckets.maxOf { barValue(it) }
    val rawMax = max(max(curMax, ghostMax), if (segmentColors == null) goal ?: 0.0 else 0.0).coerceAtLeast(1e-6)
    val ticks0 = remember(rawMax) { com.boudhealth.ui.components.niceTicks(0.0, rawMax, 3).filter { it > 0 } }
    val maxV = max(max(rawMax, ticks0.lastOrNull() ?: rawMax), if (tagHighest) curMax * 1.16 else 0.0)
    val labelStyle = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = hb.colors.muted)
    val valueStyle = TextStyle(fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = hb.colors.muted, fontFeatureSettings = "tnum")
    val tickStyle = TextStyle(fontSize = 9.5.sp, fontWeight = FontWeight.Medium, color = hb.colors.muted.copy(alpha = 0.8f), fontFeatureSettings = "tnum")
    var hold by remember(buckets) { androidx.compose.runtime.mutableStateOf<Int?>(null) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gutterPx = with(density) { if (yAxis) 28.dp.toPx() else 0f }
    fun indexAt(x: Float, w: Float): Int = ((x / ((w - gutterPx).coerceAtLeast(1f) / n)).toInt()).coerceIn(0, n - 1)
    val gestures = Modifier
        .pointerInput(buckets, onTap != null) {
            detectTapGestures(onLongPress = {}) { o ->
                val b = buckets[indexAt(o.x, size.width.toFloat())]
                if (!b.future) onTap?.invoke(b)
            }
        }
        .pointerInput(buckets) {
            detectDragGesturesAfterLongPress(
                onDragStart = { o -> hold = indexAt(o.x, size.width.toFloat()); scrub?.set(buckets[hold!!], buckets); haptics.tick() },
                onDragEnd = { hold = null; scrub?.clear() },
                onDragCancel = { hold = null; scrub?.clear() },
                onDrag = { ch, _ ->
                    val i = indexAt(ch.position.x, size.width.toFloat())
                    if (i != hold) { hold = i; scrub?.set(buckets[i], buckets); haptics.tick() }
                    ch.consume()
                },
            )
        }
    Box(modifier.fillMaxWidth().height(height)) {
        Canvas(Modifier.fillMaxWidth().height(height).then(gestures)) {
            // design6 kit6.bars: labels 16 dp under the plot, bar = slot × (1 − gap) (gap .30 week/year, .22 month, .28 hours),
            // corner = 2.5 dp + 18 % of the bar width, baseline in the grid colour
            val axisH = 18.dp.toPx()
            val topPad = if (valueLabels) 14.dp.toPx() else 6.dp.toPx()
            val plotH = size.height - axisH - topPad
            val plotW = size.width - gutterPx
            val slot = plotW / n
            val gapF = when (grain) { Grain.HOUR -> 0.28f; Grain.DAY -> if (n > 7) 0.22f else 0.30f; else -> if (n > 12) 0.22f else 0.30f }
            val barW = (slot * (1f - gapF)).coerceAtMost(44.dp.toPx())
            val baseY = topPad + plotH
            val rad = CornerRadius(minOf(barW / 2f, 2.5.dp.toPx() + barW * 0.18f))
            // y-axis: hairline grid + values on the right
            val ticks = if (goalTickBold && goal != null && goal > 0 && segmentColors == null) ticks0.filter { kotlin.math.abs(it - goal) / maxV * plotH > 12.dp.toPx() } else ticks0
            if (yAxis) ticks.forEach { t ->
                val ty = baseY - (t / maxV * plotH).toFloat()
                drawLine(hb.colors.grid.copy(alpha = 0.45f), Offset(0f, ty), Offset(plotW, ty), 0.6.dp.toPx())
                val tl = measurer.measure(SFmt.compact(t), tickStyle)
                drawText(tl, topLeft = Offset(plotW + 4.dp.toPx(), ty - tl.size.height / 2f))
            }
            drawLine(hb.colors.grid, Offset(0f, baseY + 0.5f), Offset(plotW, baseY + 0.5f), 1.dp.toPx())
            if (goal != null && goal > 0 && segmentColors == null) {
                val gy = baseY - (goal / maxV * plotH).toFloat()
                drawLine(hb.colors.muted.copy(alpha = 0.6f), Offset(0f, gy), Offset(plotW, gy), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
            }
            if (goalTickBold && yAxis && goal != null && goal > 0 && segmentColors == null) {
                val gy = baseY - (goal / maxV * plotH).toFloat()
                val gl = measurer.measure(SFmt.compact(goal), tickStyle.copy(fontWeight = FontWeight.Bold, color = hb.colors.text))
                drawRect(hb.colors.card, Offset(plotW + 3.dp.toPx(), gy - gl.size.height / 2f), Size(gl.size.width + 2.dp.toPx(), gl.size.height.toFloat()))
                drawText(gl, topLeft = Offset(plotW + 4.dp.toPx(), gy - gl.size.height / 2f))
            }
            // R3 previous-period ghost, behind the current bars (r3ui ghost_bar: muted 20 %)
            if (ghost != null) for (i in 0 until n) {
                val gv = ghostValue(i)
                if (gv <= 0.0) continue
                val gh = (gv / maxV * plotH).toFloat().coerceAtLeast(3.dp.toPx())
                val gx = slot * i + slot / 2f - barW / 2f
                drawRoundRect(hb.colors.muted.copy(alpha = GhostStyle.FILL_A), Offset(gx, baseY - gh), Size(barW, gh), CornerRadius(minOf(rad.x, gh / 2f)))
            }
            val held = hold ?: scrub?.let { s -> buckets.indexOfFirst { s.matches(it) }.takeIf { it >= 0 } }
            val tagI = if (tagHighest && n <= 31 && curMax > 0) buckets.indices.maxByOrNull { barValue(buckets[it]) } else null
            buckets.forEachIndexed { i, b ->
                val cx = slot * i + slot / 2f
                val x = cx - barW / 2f
                val v = barValue(b)
                val dim = (selected != null && b.day != selected && grain == Grain.DAY) || (held != null && held != i)
                if (b.future || v <= 0.0) {
                    drawRoundRect(hb.colors.tileHi.copy(alpha = if (b.future) 0.5f else 1f), Offset(x, baseY - 3.dp.toPx()), Size(barW, 3.dp.toPx()), CornerRadius(1.5.dp.toPx()))
                } else {
                    val h = (v / maxV * plotH).toFloat().coerceAtLeast(3.dp.toPx())
                    val alpha = if (dim) 0.35f else 1f
                    if (segmentColors != null && b.segments != null) {
                        val clip = Path().apply { addRoundRect(RoundRect(x, baseY - h, x + barW, baseY, rad)) }
                        clipPath(clip) {
                            var y = baseY
                            b.segments.forEachIndexed { si, sv ->
                                if (sv <= 0) return@forEachIndexed
                                val sh = (sv / v * h).toFloat()
                                drawRect(segmentColors.getOrElse(si) { color }.copy(alpha = alpha), Offset(x, y - sh), Size(barW, sh))
                                y -= sh
                            }
                        }
                    } else {
                        val below = dimBelowGoal && goal != null && goal > 0 && v < goal
                        drawRoundRect(color.copy(alpha = alpha * if (below) 0.42f else 1f), Offset(x, baseY - h), Size(barW, h), rad)
                    }
                    if (tagI == i && (selected == null || b.day == selected || grain != Grain.DAY)) {
                        val tg = measurer.measure(r3Tag(v), valueStyle.copy(color = hb.colors.text))
                        drawText(tg, topLeft = Offset(cx - tg.size.width / 2f, baseY - h - tg.size.height - 2.dp.toPx()))
                    }
                    if (valueLabels && (b.value ?: 0.0) > 0) {
                        val t = measurer.measure(SFmt.int(b.value ?: 0.0), valueStyle)
                        drawText(t, topLeft = Offset(cx - t.size.width / 2f, baseY - h - t.size.height - 2.dp.toPx()))
                    }
                }
                val lbl = axisLabel(b, i, n, grain, period) ?: return@forEachIndexed
                val isToday = isTodayBucket(b, grain, today)
                val isSel = selected != null && b.day == selected && grain == Grain.DAY
                val t = measurer.measure(lbl, labelStyle.copy(color = if (isToday || isSel) hb.colors.onAccent else hb.colors.muted))
                val ly = baseY + 3.dp.toPx()
                if (isToday || isSel) {
                    val r = maxOf(t.size.width, t.size.height) / 2f + 3.dp.toPx()
                    drawCircle(if (isSel) hb.colors.text else hb.colors.accent, r, Offset(cx, ly + t.size.height / 2f))
                    if (isSel) {
                        val t2 = measurer.measure(lbl, labelStyle.copy(color = hb.colors.card))
                        drawText(t2, topLeft = Offset(cx - t2.size.width / 2f, ly)); return@forEachIndexed
                    }
                }
                drawText(t, topLeft = Offset(cx - t.size.width / 2f, ly))
            }
        }
        val h = hold
        if (h != null) {
            val b = buckets[h]
            val c = callout?.invoke(b) ?: bucketCallout(b, grain, unit, decimals, segmentNames, segmentUnit)
            com.boudhealth.ui.components.ChartCalloutLine(c, onClose = { hold = null }, onAction = {},
                modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 4.dp))
        }
    }
}

/** Min–max bars per bucket (sleep per month) with a dot for single-value buckets. */
@Composable
fun RangeBars(buckets: List<SeriesBucket>, grain: Grain, period: SectionPeriod, color: Color, today: LocalDate, modifier: Modifier = Modifier, height: Dp = 118.dp,
              /** R3: previous period's (min, max) per bucket behind the bars (muted 20 %), aligned by index. */
              ghost: List<Pair<Double, Double>?>? = null) {
    val hb = LocalHB.current
    val measurer = rememberTextMeasurer()
    val n = buckets.size
    val gl = ghost.orEmpty().filterNotNull()
    val lo = (buckets.mapNotNull { it.min ?: it.value } + gl.map { it.first }).minOrNull()
    val hi = (buckets.mapNotNull { it.max ?: it.value } + gl.map { it.second }).maxOrNull()
    val labelStyle = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = hb.colors.muted)
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (n == 0) return@Canvas
        val axisH = 22.dp.toPx()
        val plotH = size.height - axisH - 8.dp.toPx()
        val baseY = 4.dp.toPx() + plotH
        drawLine(hb.colors.tileHi, Offset(0f, baseY + 2.dp.toPx()), Offset(size.width, baseY + 2.dp.toPx()), 1.dp.toPx())
        val slot = size.width / n
        val span = if (lo != null && hi != null) (hi - lo).coerceAtLeast(1.0) else 1.0
        fun y(v: Double) = baseY - 8.dp.toPx() - ((v - (lo ?: 0.0)) / span * (plotH - 16.dp.toPx())).toFloat()
        ghost?.forEachIndexed { i, g ->
            if (g == null || i >= n) return@forEachIndexed
            val cx = slot * i + slot / 2f
            val gw = minOf(slot * 0.5f, 5.dp.toPx())
            if (abs(g.second - g.first) < 1e-6) drawCircle(hb.colors.muted.copy(alpha = GhostStyle.FILL_A), 3.5.dp.toPx(), Offset(cx, y(g.second)))
            else drawLine(hb.colors.muted.copy(alpha = GhostStyle.FILL_A), Offset(cx, y(g.first)), Offset(cx, y(g.second)), gw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
        buckets.forEachIndexed { i, b ->
            val cx = slot * i + slot / 2f
            val mn = b.min ?: b.value; val mx = b.max ?: b.value
            if (mn != null && mx != null) {
                if (abs(mx - mn) < 1e-6) drawCircle(color, 3.5.dp.toPx(), Offset(cx, y(mx)))
                else drawLine(color, Offset(cx, y(mn)), Offset(cx, y(mx)), minOf(slot * 0.5f, 5.dp.toPx()), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            val lbl = axisLabel(b, i, n, grain, period) ?: return@forEachIndexed
            val t = measurer.measure(lbl, labelStyle.copy(color = if (isTodayBucket(b, grain, today)) hb.colors.text else hb.colors.muted))
            drawText(t, topLeft = Offset(cx - t.size.width / 2f, baseY + 6.dp.toPx()))
        }
    }
}

/** Dots + line with a soft area (VO₂ max, measurements). Points without a value are skipped. */
@Composable
fun DotLine(values: List<Double?>, color: Color, modifier: Modifier = Modifier, height: Dp = 118.dp, dots: Boolean = true,
            /** R3: previous period as a muted line (50 %, 1.2 dp), aligned by index. */
            ghost: List<Double?>? = null) {
    val hb = LocalHB.current
    Canvas(modifier.fillMaxWidth().height(height)) {
        val pts = values.withIndex().filter { it.value != null }
        if (pts.isEmpty()) return@Canvas
        val gpts = ghost.orEmpty().withIndex().filter { it.value != null && it.index < values.size }
        val lo = (pts.map { it.value!! } + gpts.map { it.value!! }).min(); val hi = (pts.map { it.value!! } + gpts.map { it.value!! }).max()
        val span = (hi - lo).coerceAtLeast(1e-6)
        val n = values.size
        val pad = 8.dp.toPx()
        fun x(i: Int) = if (n <= 1) size.width / 2f else pad + (size.width - 2 * pad) * i / (n - 1).toFloat()
        fun y(v: Double) = if (hi - lo < 1e-6) size.height / 2f else pad + (size.height - 2 * pad) * (1f - ((v - lo) / span).toFloat())
        val offs = pts.map { Offset(x(it.index), y(it.value!!)) }
        if (gpts.size > 1) {
            val g = Path().apply { gpts.forEachIndexed { k, p -> if (k == 0) moveTo(x(p.index), y(p.value!!)) else lineTo(x(p.index), y(p.value!!)) } }
            drawPath(g, hb.colors.muted.copy(alpha = GhostStyle.LINE_A), style = Stroke(GhostStyle.LINE_W.toPx()))
        } else if (gpts.size == 1) drawCircle(hb.colors.muted.copy(alpha = GhostStyle.LINE_A), 1.6.dp.toPx(), Offset(x(gpts[0].index), y(gpts[0].value!!)))
        if (offs.size > 1) {
            val line = Path().apply { moveTo(offs[0].x, offs[0].y); offs.drop(1).forEach { lineTo(it.x, it.y) } }
            val area = Path().apply { addPath(line); lineTo(offs.last().x, size.height); lineTo(offs[0].x, size.height); close() }
            drawPath(area, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(color.copy(alpha = 0.25f), color.copy(alpha = 0f))))
            drawPath(line, color, style = Stroke(2.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
        if (dots || offs.size == 1) offs.forEachIndexed { i, o ->
            drawCircle(color, if (i == offs.lastIndex) 4.5.dp.toPx() else 3.dp.toPx(), o)
        }
        if (offs.size == 1) drawCircle(hb.colors.card, 1.5.dp.toPx(), offs[0])
    }
}

/** A small round "status" icon (✓ ● ▲ ▼) in a tinted circle. */
@Composable
fun ToneIcon(symbol: String, fg: Color, bg: Color, size: Dp = 22.dp) {
    Box(Modifier.size(size).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        Text(symbol, fontSize = (size.value * 0.42f).sp, color = fg, lineHeight = (size.value * 0.42f).sp, maxLines = 1)
    }
}

/** Spacer helpers. */
@Composable internal fun VSp(h: Dp) = Spacer(Modifier.height(h))
@Composable internal fun HSp(w: Dp) = Spacer(Modifier.width(w))

internal val RowGap8 = Arrangement.spacedBy(8.dp)

/** Highest-bar tag (actlib tagfmt): "412" · "12.3k" · "412K". */
internal fun r3Tag(v: Double): String = when {
    v < 1000 -> SFmt.int(v)
    v < 100_000 -> String.format(Locale.US, "%.1fk", v / 1000.0).replace(".0k", "k")
    else -> SFmt.compact(v)
}
