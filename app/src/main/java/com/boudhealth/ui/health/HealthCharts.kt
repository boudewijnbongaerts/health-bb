package com.boudhealth.ui.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import com.boudhealth.ui.components.ChartCallout
import com.boudhealth.ui.components.ChartCalloutLine
import com.boudhealth.ui.components.niceTicks
import com.boudhealth.ui.section.SFmt
import com.boudhealth.ui.section.bucketTitle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.data.Grain
import com.boudhealth.data.SectionPeriod
import com.boudhealth.data.SeriesBucket
import com.boudhealth.data.WeighIn
import com.boudhealth.ui.section.axisLabel
import com.boudhealth.ui.section.isTodayBucket
import com.boudhealth.ui.theme.Cat
import com.boudhealth.ui.theme.LocalHB
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max

// =====================================================================================================================
// Body + Vitals charts (T3): measurement line with target, filled min–max band (heart rate, V7-S1), weight
// composition (lean kg + fat kg stacked + fat % line), BMI scale. Same axis labels / today mark as the section kit.
// =====================================================================================================================

/** Axis row height under every chart here. */
private val AXIS_H = 18.dp

/** Right gutter for the y-axis values (same as the section kit's `PeriodBars`). */
private val Y_GUTTER = 30.dp

/** Draws the bucket axis labels (today: accent circle; selected day: text circle), like `PeriodBars`. */
private fun DrawScope.axis(
    buckets: List<SeriesBucket>, grain: Grain, period: SectionPeriod, today: LocalDate, selected: LocalDate?,
    x: (Int) -> Float, baseY: Float, measurer: TextMeasurer, muted: Color, accent: Color,
    onAccent: Color, text: Color, card: Color,
) {
    val n = buckets.size
    val style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = muted)
    buckets.forEachIndexed { i, b ->
        val lbl = axisLabel(b, i, n, grain, period) ?: return@forEachIndexed
        val isToday = isTodayBucket(b, grain, today)
        val isSel = selected != null && grain == Grain.DAY && b.day == selected
        val t = measurer.measure(lbl, style.copy(color = when { isSel -> card; isToday -> onAccent; else -> muted }))
        val cx = x(i)
        val ly = baseY + 3.dp.toPx()
        if (isToday || isSel) {
            val r = maxOf(t.size.width, t.size.height) / 2f + 3.dp.toPx()
            drawCircle(if (isSel) text else accent, r, Offset(cx, ly + t.size.height / 2f))
        }
        drawText(t, topLeft = Offset(cx - t.size.width / 2f, ly))
    }
}

/** Hairline y-grid + tick values in the right gutter (V2_REMOVALS_AUDIT C1). */
private fun DrawScope.yAxis(ticks: List<Double>, y: (Double) -> Float, plotW: Float, top: Float, baseY: Float, decimals: Int,
                            measurer: TextMeasurer, grid: Color, muted: Color) {
    val style = TextStyle(fontSize = 9.5.sp, fontWeight = FontWeight.Medium, color = muted.copy(alpha = 0.85f), fontFeatureSettings = "tnum")
    ticks.forEach { t ->
        val ty = y(t)
        if (ty < top - 1f || ty > baseY + 1f) return@forEach
        drawLine(grid.copy(alpha = 0.45f), Offset(0f, ty), Offset(plotW, ty), 0.6.dp.toPx())
        val tl = measurer.measure(if (decimals > 0 && t % 1.0 != 0.0) SFmt.dec(t, 1) else SFmt.compact(t), style)
        drawText(tl, topLeft = Offset(plotW + 4.dp.toPx(), ty - tl.size.height / 2f))
    }
}

/** Press-and-hold + slide read-out (the section kit's gesture): index of the held bucket, or null. */
@Composable
private fun holdGesture(n: Int, key: Any, gutterPx: Float, enabled: (Int) -> Boolean, onHold: (Int?) -> Unit, onTap: ((Int) -> Unit)?): Modifier {
    val haptics = com.boudhealth.ui.components.rememberHaptics()
    fun indexAt(x: Float, w: Float): Int = (x / ((w - gutterPx).coerceAtLeast(1f) / n)).toInt().coerceIn(0, n - 1)
    // snap to the nearest bucket with a value (measurements are sparse)
    fun snap(i: Int): Int? = (0 until n).filter(enabled).minByOrNull { abs(it - i) }
    return Modifier
        .pointerInput(key, onTap != null) {
            detectTapGestures(onLongPress = {}) { o -> onTap?.invoke(indexAt(o.x, size.width.toFloat())) }
        }
        .pointerInput(key) {
            var last: Int? = null
            detectDragGesturesAfterLongPress(
                onDragStart = { o -> last = snap(indexAt(o.x, size.width.toFloat())); onHold(last); haptics.tick() },
                onDragEnd = { last = null; onHold(null) },
                onDragCancel = { last = null; onHold(null) },
                onDrag = { ch, _ ->
                    val i = snap(indexAt(ch.position.x, size.width.toFloat()))
                    if (i != last) { last = i; onHold(i); haptics.tick() }
                    ch.consume()
                },
            )
        }
}

/** Signed change "+0.4" / "−1.2" with the metric's decimals. */
private fun signed(v: Double, decimals: Int): String = (if (v < 0) "−" else "+") + SFmt.num(abs(v), decimals)

/**
 * Heart rate as a FILLED min–max band (V7-S1): per day (1W/1M), per month (1Y/All) or per hour (1D). A gap (bucket
 * without data) breaks the band; below 6 elapsed buckets it draws rounded min–max bars. [curve] = the 1D look: the
 * hourly average as a line with a soft area, the hour's min–max as a faint band behind it. Press and hold + slide →
 * read-out "lowest – highest" of the bucket (V2_REMOVALS_AUDIT C4); y-axis values on the right.
 */
@Composable
fun BandChart(
    buckets: List<SeriesBucket>,
    grain: Grain,
    period: SectionPeriod,
    today: LocalDate,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 118.dp,
    curve: Boolean = false,
    selected: LocalDate? = null,
    onTap: ((SeriesBucket) -> Unit)? = null,
    unit: String = "bpm",
    yAxis: Boolean = true,
    // ---- R3 (IMPL_PLAN §2.4), additive
    /** Previous period's lowest–highest as a muted band with dashed edges. */
    prevBand: ClosedFloatingPointRange<Double>? = null,
    /** Highest max tagged above, lowest min below. */
    tagExtremes: Boolean = false,
    scrub: com.boudhealth.ui.section.ScrubState? = null,
) {
    val hb = LocalHB.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val n = buckets.size
    if (n == 0) { Box(modifier.fillMaxWidth().height(height)); return }
    val lo = (buckets.mapNotNull { it.min ?: it.value } + listOfNotNull(prevBand?.start)).minOrNull() ?: 0.0
    val hi = (buckets.mapNotNull { it.max ?: it.value } + listOfNotNull(prevBand?.endInclusive)).maxOrNull() ?: 1.0
    val pad = max((hi - lo) * 0.08, 2.0)
    val a = lo - pad; val b = hi + pad
    val ticks = remember(a, b) { niceTicks(a, b, 3).filter { it in a..b } }
    var hold by remember(buckets) { mutableStateOf<Int?>(null) }
    val gutterPx = with(density) { if (yAxis) Y_GUTTER.toPx() else 0f }
    val gestures = holdGesture(n, buckets, gutterPx, { (buckets[it].min ?: buckets[it].value) != null }, { hold = it; scrub?.let { s -> if (it == null) s.clear() else s.set(buckets[it], buckets) } },
        onTap?.let { f -> { i: Int -> if (!buckets[i].future) f(buckets[i]) } })
    val extHold = if (hold == null) scrub?.let { s -> buckets.indexOfFirst { s.matches(it) }.takeIf { it >= 0 } } else null
    Box(modifier.fillMaxWidth().height(height)) {
        Canvas(Modifier.fillMaxWidth().height(height).then(gestures)) {
            val axisH = AXIS_H.toPx()
            val top = 6.dp.toPx()
            val baseY = size.height - axisH
            val plotH = baseY - top - 4.dp.toPx()
            val plotW = size.width - gutterPx
            val slot = plotW / n
            fun x(i: Int) = slot * i + slot / 2f
            fun y(v: Double) = (top + plotH * (1.0 - (v - a) / (b - a))).toFloat()
            if (yAxis) yAxis(ticks, ::y, plotW, top, baseY, 0, measurer, hb.colors.grid, hb.colors.muted)
            drawLine(hb.colors.grid, Offset(0f, baseY + 0.5f), Offset(plotW, baseY + 0.5f), 1.dp.toPx())
            if (prevBand != null) r3Band(prevBand, ::y, plotW, hb.colors.muted)
            val held = hold ?: extHold
            fun mn(i: Int) = buckets[i].min ?: buckets[i].value!!
            fun mx(i: Int) = buckets[i].max ?: buckets[i].value!!
            // fewer than 6 elapsed buckets: rounded min–max bars per bucket, not an area between 2–3 points (visual QA #13)
            if (!curve && buckets.count { !it.future } < 6) {
                val w = minOf(slot * 0.42f, 12.dp.toPx())
                buckets.forEachIndexed { i, bk ->
                    if ((bk.min ?: bk.value) == null || (bk.max ?: bk.value) == null) return@forEachIndexed
                    val top0 = y(mx(i)); val h = max(w, y(mn(i)) - top0)
                    drawRoundRect(color.copy(alpha = if (held != null && held != i) 0.4f else 0.85f), Offset(x(i) - w / 2, top0), Size(w, h), CornerRadius(w / 2))
                }
            } else {
                // contiguous runs of buckets with a min and a max
                val runs = ArrayList<List<Int>>()
                var cur = ArrayList<Int>()
                buckets.forEachIndexed { i, bk ->
                    val ok = (bk.min ?: bk.value) != null && (bk.max ?: bk.value) != null
                    if (ok) cur.add(i) else if (cur.isNotEmpty()) { runs.add(cur); cur = ArrayList() }
                }
                if (cur.isNotEmpty()) runs.add(cur)
                val stroke = Stroke(if (curve) 1.dp.toPx() else 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                runs.forEach { run ->
                    if (run.size == 1) {
                        val i = run[0]
                        val w = minOf(slot * 0.5f, 6.dp.toPx())
                        drawRoundRect(color.copy(alpha = if (curve) 0.18f else 0.45f), Offset(x(i) - w / 2, y(mx(i))), Size(w, max(2f, y(mn(i)) - y(mx(i)))), CornerRadius(w / 2))
                        return@forEach
                    }
                    val upper = Path().apply { run.forEachIndexed { k, i -> if (k == 0) moveTo(x(i), y(mx(i))) else lineTo(x(i), y(mx(i))) } }
                    val lower = Path().apply { run.forEachIndexed { k, i -> if (k == 0) moveTo(x(i), y(mn(i))) else lineTo(x(i), y(mn(i))) } }
                    val band = Path().apply {
                        run.forEachIndexed { k, i -> if (k == 0) moveTo(x(i), y(mx(i))) else lineTo(x(i), y(mx(i))) }
                        run.reversed().forEach { i -> lineTo(x(i), y(mn(i))) }
                        close()
                    }
                    drawPath(band, color.copy(alpha = if (curve) 0.14f else 0.34f))
                    if (!curve) { drawPath(upper, color, style = stroke); drawPath(lower, color, style = stroke) }
                }
                if (curve) {
                    val pts = buckets.withIndex().filter { it.value.value != null }.map { Offset(x(it.index), y(it.value.value!!)) }
                    if (pts.size > 1) {
                        val line = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
                        val area = Path().apply { addPath(line); lineTo(pts.last().x, baseY); lineTo(pts[0].x, baseY); close() }
                        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0.02f)), startY = top, endY = baseY))
                        drawPath(line, color, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    pts.lastOrNull()?.let { drawCircle(color, 3.5.dp.toPx(), it) }
                }
            }
            if (tagExtremes) {
                val ok = buckets.indices.filter { (buckets[it].min ?: buckets[it].value) != null && (buckets[it].max ?: buckets[it].value) != null }
                if (ok.isNotEmpty()) {
                    val st = TextStyle(fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = hb.colors.text, fontFeatureSettings = "tnum")
                    val iHi = ok.maxWith(compareBy<Int> { mx(it) }.thenBy { it }); val iLo = ok.minWith(compareBy<Int> { mn(it) }.thenByDescending { it })
                    val th = measurer.measure(SFmt.int(mx(iHi)), st); val tl = measurer.measure(SFmt.int(mn(iLo)), st)
                    drawText(th, topLeft = Offset((x(iHi) - th.size.width / 2f).coerceIn(0f, plotW - th.size.width), (y(mx(iHi)) - th.size.height - 3.dp.toPx()).coerceAtLeast(0f)))
                    drawText(tl, topLeft = Offset((x(iLo) - tl.size.width / 2f).coerceIn(0f, plotW - tl.size.width), (y(mn(iLo)) + 3.dp.toPx()).coerceAtMost(baseY - tl.size.height)))
                }
            }
            if (held != null && (buckets[held].min ?: buckets[held].value) != null) {
                drawLine(hb.colors.text.copy(alpha = 0.35f), Offset(x(held), top), Offset(x(held), baseY), 1.dp.toPx())
                drawCircle(color, 3.5.dp.toPx(), Offset(x(held), y(mx(held))))
                drawCircle(color, 3.5.dp.toPx(), Offset(x(held), y(mn(held))))
            }
            axis(buckets, grain, period, today, selected, ::x, baseY, measurer, hb.colors.muted, hb.colors.accent, hb.colors.onAccent, hb.colors.text, hb.colors.card)
        }
        val h = hold
        if (h != null) {
            val bk = buckets[h]
            val mnv = bk.min ?: bk.value; val mxv = bk.max ?: bk.value
            val value = if (mnv != null && mxv != null) "${SFmt.int(mnv)}–${SFmt.int(mxv)}" else "—"
            ChartCalloutLine(ChartCallout(bucketTitle(bk, grain) + if (bk.partial) " · so far" else "", value, unit),
                onClose = { hold = null }, onAction = {}, modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 4.dp))
        }
    }
}

@Composable
private fun LegendKey(color: Color, label: String, value: String?, square: Boolean) {
    val hb = LocalHB.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (square) Box(Modifier.size(9.dp).background(color, RoundedCornerShape(2.dp)))
        else Box(Modifier.width(12.dp).height(2.5.dp).background(color, RoundedCornerShape(1.25.dp)))
        Spacer(Modifier.width(5.dp))
        Text(label, style = hb.type.bodyXS, color = hb.colors.muted, maxLines = 1)
        if (value != null) {
            Spacer(Modifier.width(4.dp))
            Text(value, style = hb.type.bodyXS.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"), color = hb.colors.text, maxLines = 1)
        }
    }
}

/** BMI scale (S and M tiles show the same info, V7-R13): < 18.5 · 18.5–25 · 25–30 · ≥ 30, marker at the value. */
@Composable
fun BmiScaleBar(bmi: Double?, modifier: Modifier = Modifier, muted: Boolean = false) {
    val hb = LocalHB.current
    val measurer = rememberTextMeasurer()
    val lo = 15.0; val hi = 35.0
    val segs = listOf(lo to 18.5, 18.5 to 25.0, 25.0 to 30.0, 30.0 to hi)
    val cols = listOf(Color(0xFF6E8BB8), hb.colors.good, Cat.fat, hb.colors.bad)
    Canvas(modifier.fillMaxWidth().height(44.dp)) {
        val h = 10.dp.toPx(); val g = 4.dp.toPx(); val top = 6.dp.toPx()
        fun x(v: Double) = (size.width * ((v - lo) / (hi - lo))).toFloat().coerceIn(0f, size.width)
        segs.forEachIndexed { i, (a, b) ->
            val x0 = x(a) + if (i > 0) g / 2 else 0f
            val x1 = x(b) - if (i < segs.lastIndex) g / 2 else 0f
            drawRoundRect(cols[i].copy(alpha = if (muted) 0.4f else 0.85f), Offset(x0, top), Size(x1 - x0, h), CornerRadius(h / 2))
        }
        val style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.muted)
        listOf(18.5, 25.0, 30.0).forEach { v ->
            val t = measurer.measure(if (v % 1.0 == 0.0) v.toInt().toString() else v.toString(), style)
            drawText(t, topLeft = Offset(x(v) - t.size.width / 2f, top + h + 6.dp.toPx()))
        }
        if (bmi != null) {
            val cx = x(bmi)
            drawCircle(hb.colors.card, 9.dp.toPx(), Offset(cx, top + h / 2))
            drawCircle(if (muted) hb.colors.muted else hb.colors.text, 7.dp.toPx(), Offset(cx, top + h / 2))
        }
    }
}

/** Large trend arrow (VO₂ max detail, V7-S2): ▲ / ▼ / ● in a tinted circle. */
@Composable
fun BigTrendArrow(dir: Int, good: Boolean?, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val hb = LocalHB.current
    val (bg, fg) = when (good) {
        true -> hb.colors.goodCont to hb.colors.good
        false -> hb.colors.badCont to hb.colors.bad
        null -> hb.colors.tile to hb.colors.text
    }
    Box(modifier.size(size).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        Text(when { dir > 0 -> "▲"; dir < 0 -> "▼"; else -> "●" }, fontSize = (size.value * 0.42f).sp, color = fg, lineHeight = (size.value * 0.42f).sp)
    }
}

/** R3 previous-period band (kit/r3ui ghost_band): muted fill 14 %, dashed 2 / 3 edges at 40 %, across the plot. */
private fun DrawScope.r3Band(band: ClosedFloatingPointRange<Double>, y: (Double) -> Float, plotW: Float, muted: Color) {
    val y0 = y(band.endInclusive); val y1 = y(band.start)
    drawRect(muted.copy(alpha = com.boudhealth.ui.section.GhostStyle.BAND_A), Offset(0f, y0), Size(plotW, max(y1 - y0, 1.5.dp.toPx())))
    val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))
    listOf(y0, y1).forEach { yy -> drawLine(muted.copy(alpha = com.boudhealth.ui.section.GhostStyle.EDGE_A), Offset(0f, yy), Offset(plotW, yy), 0.8.dp.toPx(), pathEffect = dash) }
}

/** R3 previous-period line (kit/r3ui ghost_line): muted 50 %, 1.2 dp, joining the non-null values; a lone value = dot 1.6. */
private fun DrawScope.r3GhostLine(values: List<Double?>, x: (Int) -> Float, y: (Double) -> Float, muted: Color) {
    val pts = values.withIndex().filter { it.value != null }.map { Offset(x(it.index), y(it.value!!)) }
    val c = muted.copy(alpha = com.boudhealth.ui.section.GhostStyle.LINE_A)
    if (pts.size > 1) drawPath(Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }, c,
        style = Stroke(com.boudhealth.ui.section.GhostStyle.LINE_W.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    else if (pts.size == 1) drawCircle(c, 1.6.dp.toPx(), pts[0])
}
