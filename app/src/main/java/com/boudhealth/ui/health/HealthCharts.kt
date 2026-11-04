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
 * One value per bucket as dots + line with a soft area (measurements, nightly vitals: one value per night, no fake
 * ranges, V7-S5). Buckets without a value are skipped (the line joins the readings). [target] = dashed line in the
 * metric colour (D9 targets). [muted] draws the data faded (a carried value, D14). Tap a bucket → [onTap];
 * press and hold + slide → read-out (date · value · change vs the reading before), y-axis values on the right.
 */
@Composable
fun MeasureChart(
    buckets: List<SeriesBucket>,
    grain: Grain,
    period: SectionPeriod,
    today: LocalDate,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 118.dp,
    target: Double? = null,
    selected: LocalDate? = null,
    muted: Boolean = false,
    onTap: ((SeriesBucket) -> Unit)? = null,
    unit: String = "",
    decimals: Int = 1,
    yAxis: Boolean = true,
    // ---- R3 (IMPL_PLAN §2.4), all additive: the defaults draw exactly what the detail pages drew before
    /** Previous period as a muted line (Body), aligned by bucket index. */
    ghost: List<Double?>? = null,
    /** Previous period's lowest–highest as a muted band with dashed edges (Vitals). */
    prevBand: ClosedFloatingPointRange<Double>? = null,
    /** Body: break the line where readings are more than n days apart (4; 1Y: 6); null = join (Vitals). */
    breakGapDays: Int? = null,
    /** Days whose dot is ringed (Body week lows, Vitals outside-range days). */
    ringed: Set<LocalDate> = emptySet(),
    /** Body 1Y: dashed first → last chord with [chordLabel] ("−0.3 kg per month"). */
    chord: Pair<com.boudhealth.data.Extreme, com.boudhealth.data.Extreme>? = null,
    chordLabel: String? = null,
    /** Tag at the right end of the target line ("Target 78 kg"). */
    targetTag: String? = null,
    /** Highest value tagged above its dot, lowest below. */
    tagExtremes: Boolean = false,
    /** 1D window: the other days at 55 %. */
    contextDay: LocalDate? = null,
    /** Shared cursor (hero lanes / Body hero). */
    scrub: com.boudhealth.ui.section.ScrubState? = null,
) {
    val hb = LocalHB.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val n = buckets.size
    if (n == 0) { Box(modifier.fillMaxWidth().height(height)); return }
    val vals = buckets.mapNotNull { it.value }
    val extra = ghost.orEmpty().filterNotNull() + listOfNotNull(prevBand?.start, prevBand?.endInclusive)
    val lo0 = (vals + listOfNotNull(target) + extra).minOrNull() ?: 0.0
    val hi0 = (vals + listOfNotNull(target) + extra).maxOrNull() ?: 1.0
    val pad = max((hi0 - lo0) * 0.12, if (hi0 == lo0) max(abs(hi0) * 0.02, 0.5) else 0.0)
    val a = lo0 - pad; val b = hi0 + pad
    val ticks = remember(a, b) { niceTicks(a, b, 3).filter { it in a..b } }
    var hold by remember(buckets) { mutableStateOf<Int?>(null) }
    val gutterPx = with(density) { if (yAxis) Y_GUTTER.toPx() else 0f }
    val gestures = holdGesture(n, buckets, gutterPx, { buckets[it].value != null }, { hold = it; scrub?.let { s -> if (it == null) s.clear() else s.set(buckets[it], buckets) } },
        onTap?.let { f -> { i: Int -> if (!buckets[i].future) f(buckets[i]) } })
    val c = if (muted) hb.colors.muted else color
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
            if (yAxis) yAxis(ticks, ::y, plotW, top, baseY, decimals, measurer, hb.colors.grid, hb.colors.muted)
            drawLine(hb.colors.grid, Offset(0f, baseY + 0.5f), Offset(plotW, baseY + 0.5f), 1.dp.toPx())
            if (prevBand != null) r3Band(prevBand, ::y, plotW, hb.colors.muted)
            if (ghost != null) r3GhostLine(ghost.take(n), ::x, ::y, hb.colors.muted)
            if (target != null) {
                val ty = y(target)
                drawLine(c.copy(alpha = 0.75f), Offset(0f, ty), Offset(plotW, ty), 1.2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
                if (targetTag != null) {
                    val tl = measurer.measure(targetTag, TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c))
                    val tx = plotW - tl.size.width - 2.dp.toPx()
                    val above = ty - tl.size.height - 2.dp.toPx() >= top
                    val tyy = if (above) ty - tl.size.height - 2.dp.toPx() else ty + 2.dp.toPx()
                    drawRect(hb.colors.card, Offset(tx - 2.dp.toPx(), tyy), Size(tl.size.width + 4.dp.toPx(), tl.size.height.toFloat()))
                    drawText(tl, topLeft = Offset(tx, tyy))
                }
            }
            val pts = buckets.withIndex().filter { it.value.value != null }.map { it.index to Offset(x(it.index), y(it.value.value!!)) }
            // R3: Body breaks the line at gaps longer than breakGapDays (never interpolated); default = one joined line
            val runs: List<List<Pair<Int, Offset>>> = if (breakGapDays == null || pts.size < 2) listOf(pts) else {
                val out = ArrayList<List<Pair<Int, Offset>>>(); var cur = ArrayList<Pair<Int, Offset>>()
                pts.forEach { p ->
                    val prev = cur.lastOrNull()
                    if (prev != null && java.time.temporal.ChronoUnit.DAYS.between(buckets[prev.first].day, buckets[p.first].day) > breakGapDays) { out += cur; cur = ArrayList() }
                    cur += p
                }
                out += cur; out
            }
            runs.forEach { run ->
                if (run.size > 1) {
                    val line = Path().apply { moveTo(run[0].second.x, run[0].second.y); run.drop(1).forEach { lineTo(it.second.x, it.second.y) } }
                    val area = Path().apply { addPath(line); lineTo(run.last().second.x, baseY); lineTo(run[0].second.x, baseY); close() }
                    drawPath(area, Brush.verticalGradient(listOf(c.copy(alpha = 0.24f), c.copy(alpha = 0.02f)), startY = top, endY = baseY))
                    drawPath(line, c, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            if (chord != null) {
                val i0 = buckets.indexOfFirst { it.day == chord.first.day }; val i1 = buckets.indexOfLast { it.day == chord.second.day }
                if (i0 >= 0 && i1 > i0) {
                    val p0 = Offset(x(i0), y(chord.first.value)); val p1 = Offset(x(i1), y(chord.second.value))
                    drawLine(hb.colors.text.copy(alpha = 0.75f), p0, p1, 1.4.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
                    if (chordLabel != null) {
                        val cl = measurer.measure(chordLabel, TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = hb.colors.text))
                        val mx = (p0.x + p1.x) / 2f; val my = (p0.y + p1.y) / 2f
                        val ly = (my - cl.size.height - 8.dp.toPx()).coerceAtLeast(top)
                        drawRect(hb.colors.card, Offset(mx - cl.size.width / 2f - 3.dp.toPx(), ly), Size(cl.size.width + 6.dp.toPx(), cl.size.height.toFloat()))
                        drawText(cl, topLeft = Offset(mx - cl.size.width / 2f, ly))
                    }
                }
            }
            val held = hold ?: extHold
            pts.forEachIndexed { k, (i, o) ->
                val isLast = k == pts.lastIndex
                val r = when { held == i -> 5.dp.toPx(); isLast -> 3.8.dp.toPx(); pts.size > 40 -> 1.4.dp.toPx(); else -> 2.4.dp.toPx() }
                val ctxDim = contextDay != null && buckets[i].day != contextDay
                drawCircle(c.copy(alpha = if ((held != null && held != i) || ctxDim) 0.55f else 1f), r, o)
                if (held == i || (isLast && pts.size == 1)) drawCircle(hb.colors.card, r * 0.45f, o)
                if (buckets[i].day in ringed) drawCircle(c, r + 3.dp.toPx(), o, style = Stroke(1.4.dp.toPx()))
            }
            if (tagExtremes && pts.isNotEmpty()) {
                val hiK = pts.indices.maxWith(compareBy<Int> { buckets[pts[it].first].value!! }.thenBy { it })
                val loK = pts.indices.minWith(compareBy<Int> { buckets[pts[it].first].value!! }.thenByDescending { it })
                val st = TextStyle(fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = hb.colors.text, fontFeatureSettings = "tnum")
                listOf(hiK to true, loK to false).distinctBy { it.first }.forEach { (k, up) ->
                    val (i, o) = pts[k]
                    val tl = measurer.measure(SFmt.num(buckets[i].value!!, decimals), st)
                    val tx = (o.x - tl.size.width / 2f).coerceIn(0f, plotW - tl.size.width)
                    val ty = if (up) (o.y - tl.size.height - 4.dp.toPx()).coerceAtLeast(0f) else (o.y + 5.dp.toPx()).coerceAtMost(baseY - tl.size.height)
                    drawText(tl, topLeft = Offset(tx, ty))
                }
            }
            if (held != null) drawLine(hb.colors.text.copy(alpha = 0.35f), Offset(x(held), top), Offset(x(held), baseY), 1.dp.toPx())
            axis(buckets, grain, period, today, selected, ::x, baseY, measurer, hb.colors.muted, hb.colors.accent, hb.colors.onAccent, hb.colors.text, hb.colors.card)
        }
        val h = hold
        if (h != null) {
            val bk = buckets[h]
            val prev = (h - 1 downTo 0).firstOrNull { buckets[it].value != null }?.let { buckets[it] }
            val v = bk.value
            val delta = if (v != null && prev?.value != null) "${signed(v - prev.value!!, decimals)} $unit vs ${shortTitle(prev, grain)}".replace("  ", " ") else null
            ChartCalloutLine(ChartCallout(bucketTitle(bk, grain), v?.let { SFmt.num(it, decimals) } ?: "—", unit, delta = delta),
                onClose = { hold = null }, onAction = {}, modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 4.dp))
        }
    }
}

private fun shortTitle(b: SeriesBucket, grain: Grain): String = when (grain) {
    Grain.DAY -> SFmt.shortDate(b.day)
    else -> bucketTitle(b, grain)
}

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
            val value = if (mnv != null && mxv != null) "${SFmt.int(mnv)} – ${SFmt.int(mxv)}" else "—"
            ChartCalloutLine(ChartCallout(bucketTitle(bk, grain) + if (bk.partial) " · so far" else "", value, unit),
                onClose = { hold = null }, onAction = {}, modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 4.dp))
        }
    }
}

/** Lowest–highest of a band (headline "40 – 166"). */
fun bandRange(buckets: List<SeriesBucket>): Pair<Double, Double>? {
    val lo = buckets.mapNotNull { it.min ?: it.value }.minOrNull() ?: return null
    val hi = buckets.mapNotNull { it.max ?: it.value }.maxOrNull() ?: return null
    return lo to hi
}

/**
 * Weight composition (design6 Body, D16): per weigh-in a bar of lean kg (bottom) + fat kg (top), the fat % as a line on
 * its own scale. Impossible readings are already hidden by the data layer (G0). Long periods: last weigh-in per month.
 * The legend reads the values of the newest weigh-in (or the held one: press and hold + slide), with its date
 * (V2_REMOVALS_AUDIT "Weight composition details").
 */
@Composable
fun CompositionChart(weighIns: List<WeighIn>, monthly: Boolean, today: LocalDate, modifier: Modifier = Modifier, height: Dp = 132.dp,
                     /** R3: shared cursor with the weight chart (Body hero). */
                     scrub: com.boudhealth.ui.section.ScrubState? = null,
                     /** R3: the selected / context day; other weigh-ins at 55 %. */
                     highlight: LocalDate? = null) {
    val hb = LocalHB.current
    val src = weighIns.filter { it.leanKg != null || it.fatPct != null }
    val list = if (!monthly) src else src.groupBy { it.day.withDayOfMonth(1) }.values.map { g -> g.maxByOrNull { it.day }!! }.sortedBy { it.day }
    if (list.isEmpty()) return
    val lean = Cat.lean; val fat = Cat.fat
    var hold by remember(list) { mutableStateOf<Int?>(null) }
    val extHold = if (hold == null) scrub?.day?.let { d -> list.indexOfFirst { it.day == d }.takeIf { it >= 0 } } else null
    val shown = list[hold ?: extHold ?: list.lastIndex]
    val gestures = holdGesture(list.size, list, 0f, { list[it].leanKg != null || list[it].fatPct != null }, {
        hold = it
        scrub?.let { s -> if (it == null) s.clear() else { s.day = list[it].day; s.hour = null; s.ownerKey = list } }
    }, null)
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendKey(lean, "Lean", shown.leanKg?.let { "${SFmt.dec(it, 1)} kg" }, square = true)
            LegendKey(fat, "Fat", shown.fatKg?.let { "${SFmt.dec(it, 1)} kg" }, square = true)
            LegendKey(fat, "Fat %", shown.fatPct?.let { SFmt.dec(it, 1) }, square = false)
            Spacer(Modifier.weight(1f))
            Text(HealthTexts.dateText(shown.day, today), style = hb.type.bodyXS, color = if (hold != null || extHold != null) hb.colors.text else hb.colors.muted, maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(height).then(gestures)) {
            val n = list.size
            val slot = size.width / n
            val bw = (slot * 0.62f).coerceAtMost(36.dp.toPx())
            val pctH = size.height * 0.28f
            val barTop = pctH + 6.dp.toPx()
            val barH = size.height - barTop - 2.dp.toPx()
            val topKg = list.maxOf { (it.leanKg ?: 0.0) + (it.fatKg ?: 0.0) }.coerceAtLeast(1.0)
            drawLine(hb.colors.grid, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1.dp.toPx())
            val held = hold ?: extHold
            list.forEachIndexed { i, w ->
                val cx = slot * i + slot / 2f
                val l = w.leanKg ?: return@forEachIndexed
                val f = w.fatKg ?: 0.0
                val al = if (held != null && held != i) 0.4f else if (highlight != null && w.day != highlight) 0.55f else 1f
                val hl = (barH * (l / topKg)).toFloat(); val hf = (barH * (f / topKg)).toFloat()
                val base = size.height - 1f
                val r = CornerRadius(minOf(bw / 2, 2.dp.toPx() + bw * 0.15f))
                drawRect(lean.copy(alpha = al), Offset(cx - bw / 2, base - hl), Size(bw, hl))
                if (hf > 0f) drawRoundRect(fat.copy(alpha = al), Offset(cx - bw / 2, base - hl - hf), Size(bw, hf + 2.dp.toPx()), r)
            }
            val pv = list.withIndex().filter { it.value.fatPct != null }
            if (pv.isNotEmpty()) {
                val lo = pv.minOf { it.value.fatPct!! }; val hi = pv.maxOf { it.value.fatPct!! }
                val span = (hi - lo).coerceAtLeast(1.0)
                fun y(v: Double) = (2.dp.toPx() + (pctH - 4.dp.toPx()) * (1 - (v - lo) / span)).toFloat()
                val pts = pv.map { it.index to Offset(slot * it.index + slot / 2f, y(it.value.fatPct!!)) }
                if (pts.size > 1) {
                    val path = Path().apply { moveTo(pts[0].second.x, pts[0].second.y); pts.drop(1).forEach { lineTo(it.second.x, it.second.y) } }
                    drawPath(path, fat, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                pts.forEach { (i, o) -> drawCircle(fat, if (i == held) 4.5.dp.toPx() else 2.6.dp.toPx(), o) }
            }
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

/** Direction-only arrow (daily values: no number, V2_API semantics): ▲ ▼ ● in a neutral circle; null = nothing. */
@Composable
fun DirArrow(dir: com.boudhealth.data.TrendDir?, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    if (dir == null) return
    val hb = LocalHB.current
    Box(modifier.size(size).background(hb.colors.tile, CircleShape), contentAlignment = Alignment.Center) {
        Text(when (dir) { com.boudhealth.data.TrendDir.UP -> "▲"; com.boudhealth.data.TrendDir.DOWN -> "▼"; else -> "●" },
            fontSize = (size.value * 0.4f).sp, color = hb.colors.text, lineHeight = (size.value * 0.4f).sp, maxLines = 1)
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
