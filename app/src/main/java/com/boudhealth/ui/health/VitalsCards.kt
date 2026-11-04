package com.boudhealth.ui.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.data.SeriesBucket
import com.boudhealth.data.TrendDir
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.section.CoverStatRow
import com.boudhealth.ui.section.DeltaPillModel
import com.boudhealth.ui.section.GhostKeyModel
import com.boudhealth.ui.section.GhostKind
import com.boudhealth.ui.section.GhostStyle
import com.boudhealth.ui.section.MRow
import com.boudhealth.ui.section.R3CardFrame
import com.boudhealth.ui.section.R3Texts
import com.boudhealth.ui.section.RailCells
import com.boudhealth.ui.section.ScrubState
import com.boudhealth.ui.section.SectionContext
import com.boudhealth.ui.section.StatCell
import com.boudhealth.ui.section.Tone
import com.boudhealth.ui.section.ValueBlock
import com.boudhealth.ui.section.ValueSize
import com.boudhealth.ui.section.rememberScrub
import com.boudhealth.ui.section.scrubAndTap
import com.boudhealth.ui.theme.Cat
import com.boudhealth.ui.theme.LocalHB
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// =====================================================================================================================
// Vitals R3 cards (IMPL_PLAN §3V, out/vitals/vitals_r3.py metric_card / metric_chart / hr_card) and the small chart kit
// shared with the hero lanes (VitalsHero.kt): one slot per bucket, right y gutter 30, axis row 18, today = accent
// circle, selected day = text circle, previous period = grey band with dashed edges, outside days ringed, highest tag
// above / lowest below, future stubs. Heart rate = lowest–highest or a minute sample only (never the daily average).
// =====================================================================================================================

/** Metric colour of a vital (Cat tokens; the same in both themes, text in it goes through `hb.legible`). */
fun vitalColor(id: String): Color = when (id) { "hrv" -> Cat.hrv; "breathing" -> Cat.breathing; "spo2" -> Cat.spo2; else -> Cat.heart }

/** Glyph per vital; heart rate = pulse wave (QA 14.1), resting heart rate = heart. */
fun vitalGlyph(id: String): Glyph = if (id == "hr") Glyph.Pulse else Glyph.forMetric(id)

fun vitalTitle(id: String): String = when (id) {
    "rhr" -> R3Texts.RESTING_HR; "hrv" -> R3Texts.HRV; "breathing" -> R3Texts.BREATHING; "spo2" -> R3Texts.SPO2; else -> R3Texts.HEART_RATE
}

internal fun vUnit(id: String): String = hm(id).unit
internal fun vDec(id: String): Int = VitalsSpecs.of(id).decimals
internal fun vVal(id: String, v: Double): String = R3Texts.dec(v, vDec(id))
internal fun vRange(id: String, r: VRow): String = if (R3Texts.dec(r.lo, vDec(id)) == R3Texts.dec(r.hi, vDec(id))) R3Texts.dec(r.lo, vDec(id)) else R3Texts.range(r.lo, r.hi, vDec(id))

/** "Wed 4 Nov" (this year) / "4 Nov 2025". */
internal fun vDay(d: LocalDate, today: LocalDate): String = if (d.year == today.year) R3Texts.wkdDMon(d) else R3Texts.dMonYyyy(d)

/** The value pill (hero + cards), always neutral (vitals icons carry no good / bad colour). */
internal fun vPill(id: String, p: VPill?, f: VFrame): DeltaPillModel? = when (p) {
    null -> null
    is VPill.Change -> {
        val ref = p.r.reference ?: return null
        val diff = p.r.diff ?: return null
        val day = vDay(ref.day, f.today)
        if (p.r.dir == TrendDir.FLAT) DeltaPillModel(TrendDir.FLAT, R3Texts.sameAs(day), Tone.NEUTRAL)
        else DeltaPillModel(p.r.dir, R3Texts.pillUnit(R3Texts.mag(diff, vDec(id)), vUnit(id), day), Tone.NEUTRAL)
    }
    is VPill.SinceFirst -> {
        val ref = p.r.reference ?: return null
        val diff = p.r.diff ?: return null
        if (p.r.dir == TrendDir.FLAT) DeltaPillModel(TrendDir.FLAT, R3Texts.sameAs(R3Texts.monYyyy(ref.day)), Tone.NEUTRAL)
        else DeltaPillModel(p.r.dir, R3Texts.pillSince(R3Texts.mag(diff, vDec(id)), vUnit(id), ref.day), Tone.NEUTRAL)
    }
    is VPill.Out -> R3Texts.outsidePill(p.o.above.size, p.o.below.size, f.period)?.let { DeltaPillModel(p.o.dir, it, Tone.NEUTRAL) }
}

// ---------------------------------------------------------------------------------------------------------------------
// Model access (one model per scope, shared by hero, Trends and cards)
// ---------------------------------------------------------------------------------------------------------------------

const val EXTRA_VITALS = "vitals_r3"
/** The [VitalsModel] prebuilt on IO by loadVitalsExtras (same scope as the extras). */
const val EXTRA_VITALS_MODEL = "vitals_r3_model"

object VitalsModels {
    @Volatile private var last: Triple<VitalsData, Any, VitalsModel>? = null

    /** The model of [ctx] (cached by data + scope); null before the extras are loaded. */
    fun of(ctx: SectionContext): VitalsModel? {
        val d = ctx.extras[EXTRA_VITALS] as? VitalsData ?: return null
        (ctx.extras[EXTRA_VITALS_MODEL] as? VitalsModel)?.takeIf { it.data === d && it.f.period == ctx.scope.period && it.f.selected == ctx.scope.selectedDay }?.let { return it }
        last?.let { (ld, ls, m) -> if (ld === d && ls == ctx.scope) return m }
        return VitalsModel(d, VFrame.of(ctx.scope, d.today, d.dataStart)).also { last = Triple(d, ctx.scope, it) }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Chart kit (port of kit/charts.py Plot / _frame / axis_labels and vitals_r3.py draw helpers)
// ---------------------------------------------------------------------------------------------------------------------

/** One slot per bucket, marks centred on the slot. Pixels. */
internal class VPlot(val x: Float, val y: Float, val w: Float, val h: Float, val n: Int, val lo: Double, val hi: Double) {
    val base: Float get() = y + h
    val slot: Float get() = w / max(n, 1)
    fun X(i: Int): Float = x + slot * i + slot / 2f
    fun Y(v: Double): Float = (y + h * (1.0 - (v - lo) / (if (hi - lo == 0.0) 1.0 else hi - lo))).toFloat()
}

/** Theme colours + the text measurer for a Canvas. */
internal class VInk(
    val text: Color, val muted: Color, val grid: Color, val card: Color, val tile: Color, val tileHi: Color, val outline: Color,
    val accent: Color, val onAccent: Color, val dark: Boolean, val measurer: TextMeasurer,
)

@Composable
internal fun rememberInk(): VInk {
    val hb = LocalHB.current
    val m = rememberTextMeasurer()
    val c = hb.colors
    return remember(hb, m) { VInk(c.text, c.muted, c.grid, c.card, c.tile, c.tileHi, c.outline, c.accent, c.onAccent, hb.dark, m) }
}

internal fun DrawScope.u(v: Number): Float = v.toFloat() * density

/** Text with its baseline at [baseline] (align 0 left, 0.5 centre, 1 right). Returns the width. */
internal fun DrawScope.textAt(ink: VInk, s: String, x: Float, baseline: Float, sp: Float, color: Color, weight: FontWeight = FontWeight.Normal, align: Float = 0f, tnum: Boolean = true): Float {
    val t = ink.measurer.measure(s, TextStyle(fontSize = sp.sp, fontWeight = weight, color = color, fontFeatureSettings = if (tnum) "tnum" else null), maxLines = 1)
    drawText(t, topLeft = Offset(x - t.size.width * align, baseline - t.firstBaseline))
    return t.size.width.toFloat()
}

/** Text vertically centred on [cy]. */
internal fun DrawScope.textMid(ink: VInk, s: String, x: Float, cy: Float, sp: Float, color: Color, weight: FontWeight = FontWeight.Normal, align: Float = 0f): Float {
    val t = ink.measurer.measure(s, TextStyle(fontSize = sp.sp, fontWeight = weight, color = color, fontFeatureSettings = "tnum"), maxLines = 1)
    drawText(t, topLeft = Offset(x - t.size.width * align, cy - t.size.height / 2f))
    return t.size.width.toFloat()
}

internal fun textWidth(ink: VInk, s: String, sp: Float, weight: FontWeight, density: Float): Float =
    ink.measurer.measure(s, TextStyle(fontSize = sp.sp, fontWeight = weight)).size.width.toFloat()

internal const val V_AXIS_H = 18f
internal const val V_GUTTER = 30f
internal const val V_TOP = 6f

/** kit charts._frame: y grid + tick values in the right gutter (9.5 muted 85 %), 1 dp baseline. Returns the plot. */
internal fun DrawScope.vFrame(ink: VInk, x: Float, y: Float, w: Float, h: Float, n: Int, lo: Double, hi: Double, ticks: List<Double>, dec: Int,
                              yAxis: Boolean = true, axis: Boolean = true): VPlot {
    val gut = if (yAxis) u(V_GUTTER) else 0f
    val p = VPlot(x, y + u(V_TOP), w - gut, h - (if (axis) u(V_AXIS_H) else 0f) - u(V_TOP), n, lo, hi)
    if (yAxis) for (t in ticks) {
        val ty = p.Y(t)
        if (ty < p.y - 1 || ty > p.base + 1) continue
        if (abs(ty - p.base) > 1) drawLine(ink.grid.copy(alpha = if (ink.dark) 0.45f else 0.9f), Offset(p.x, ty), Offset(p.x + p.w, ty), u(0.6))
        textMid(ink, R3Texts.dec(t, if (dec > 0 && t % 1.0 != 0.0) dec else 0), p.x + p.w + u(4), ty, 9.5f, ink.muted.copy(alpha = 0.85f), FontWeight.Normal)
    }
    drawLine(ink.grid, Offset(p.x, p.base + u(0.5)), Offset(p.x + p.w, p.base + u(0.5)), u(1))
    return p
}

/** The previous period's lowest–highest across the plot: muted 14 %, dashed 2 / 3 edges at 40 % (r3ui.ghost_band). */
internal fun DrawScope.vGhostBand(ink: VInk, p: VPlot, band: VRow) {
    val y0 = p.Y(band.hi); val y1 = p.Y(band.lo)
    drawRect(ink.muted.copy(alpha = GhostStyle.BAND_A), Offset(p.x, y0), Size(p.w, max(y1 - y0, u(1.5))))
    val dash = PathEffect.dashPathEffect(floatArrayOf(u(2), u(3)))
    for (yy in listOf(y0, y1)) drawLine(ink.muted.copy(alpha = GhostStyle.EDGE_A), Offset(p.x, yy), Offset(p.x + p.w, yy), u(0.8), pathEffect = dash)
}

/** A drawn point: bucket index, position, value. */
internal data class VPt(val i: Int, val o: Offset, val v: Double)

/**
 * vitals_r3.draw_points: one line joining the readings (gaps joined), dots, points outside [band] ringed, the selected
 * point bigger, the last point ringed ([lastRing]); [dim] = context window (others at 55 %); [area] = soft fill to the base.
 */
internal fun DrawScope.vPoints(ink: VInk, p: VPlot, rows: List<Double?>, c: Color, ff: Int? = null, sel: Int? = null, dim: Boolean = false,
                               band: VRow? = null, sw: Float = 1.8f, area: Boolean = false, lastRing: Boolean = true, rmul: Float = 1f): List<VPt> {
    val pts = rows.withIndex().filter { it.value != null && !(ff != null && it.index >= ff) }.map { VPt(it.index, Offset(p.X(it.index), p.Y(it.value!!)), it.value!!) }
    if (pts.size > 1) {
        val line = Path().apply { moveTo(pts[0].o.x, pts[0].o.y); pts.drop(1).forEach { lineTo(it.o.x, it.o.y) } }
        if (area) {
            val fill = Path().apply { addPath(line); lineTo(pts.last().o.x, p.base); lineTo(pts[0].o.x, p.base); close() }
            drawPath(fill, Brush.verticalGradient(listOf(c.copy(alpha = 0.26f), c.copy(alpha = 0.03f)), startY = p.y, endY = p.base))
        }
        drawPath(line, c.copy(alpha = if (dim) 0.55f else 1f), style = Stroke(u(sw), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    val n = pts.size
    pts.forEachIndexed { k, pt ->
        val out = band != null && (pt.v > band.hi || pt.v < band.lo)
        val r = u((if (n > 22) 2.2f else 2.6f) * rmul)
        val a = if (dim && pt.i != sel) 0.5f else 1f
        if (out) { drawCircle(ink.card, r + u(3.2), pt.o); drawCircle(c.copy(alpha = a), r + u(2.2), pt.o, style = Stroke(u(1.3))) }
        when {
            pt.i == sel -> { drawCircle(ink.card, u(5.6), pt.o); drawCircle(c, u(4.2), pt.o) }
            k == n - 1 && lastRing -> { drawCircle(ink.card, u(5.2), pt.o); drawCircle(c.copy(alpha = a), u(3.8), pt.o) }
            else -> drawCircle(c.copy(alpha = a), r, pt.o)
        }
    }
    return pts
}

/** Rounded lowest–highest capsules per bucket (monthly vitals, few heart-rate days). */
internal fun DrawScope.vCapsules(p: VPlot, rows: List<VRow?>, c: Color, wmax: Float, alpha: Float = 0.9f, sel: Int? = null) {
    val bw = min(p.slot * 0.46f, u(wmax))
    rows.forEachIndexed { i, r ->
        r ?: return@forEachIndexed
        val t = p.Y(r.hi); val b = p.Y(r.lo); val hh = max(b - t, bw); val mid = (t + b) / 2f
        drawRoundRect(c.copy(alpha = if (sel != null && sel != i) alpha * 0.45f else alpha), Offset(p.X(i) - bw / 2f, mid - hh / 2f), Size(bw, hh), CornerRadius(bw / 2f))
    }
}

/** kit charts._band_fill: filled lowest–highest band per contiguous run (gaps break it), edges in the colour. */
internal fun DrawScope.vBandFill(p: VPlot, rows: List<VRow?>, c: Color, alpha: Float, edge: Boolean = true) {
    val runs = ArrayList<List<Int>>(); var cur = ArrayList<Int>()
    rows.forEachIndexed { i, r -> if (r != null) cur.add(i) else if (cur.isNotEmpty()) { runs += cur; cur = ArrayList() } }
    if (cur.isNotEmpty()) runs += cur
    for (run in runs) {
        if (run.size == 1) {
            val i = run[0]; val r = rows[i]!!; val bw = min(p.slot * 0.5f, u(6))
            drawRoundRect(c.copy(alpha = 0.45f), Offset(p.X(i) - bw / 2f, p.Y(r.hi)), Size(bw, max(u(2), p.Y(r.lo) - p.Y(r.hi))), CornerRadius(bw / 2f))
            continue
        }
        val band = Path().apply {
            run.forEachIndexed { k, i -> if (k == 0) moveTo(p.X(i), p.Y(rows[i]!!.hi)) else lineTo(p.X(i), p.Y(rows[i]!!.hi)) }
            run.asReversed().forEach { i -> lineTo(p.X(i), p.Y(rows[i]!!.lo)) }
            close()
        }
        drawPath(band, c.copy(alpha = alpha))
        if (edge) {
            val st = Stroke(u(1.6), cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(Path().apply { run.forEachIndexed { k, i -> if (k == 0) moveTo(p.X(i), p.Y(rows[i]!!.hi)) else lineTo(p.X(i), p.Y(rows[i]!!.hi)) } }, c, style = st)
            drawPath(Path().apply { run.forEachIndexed { k, i -> if (k == 0) moveTo(p.X(i), p.Y(rows[i]!!.lo)) else lineTo(p.X(i), p.Y(rows[i]!!.lo)) } }, c, style = st)
        }
    }
}

/** kit charts.axis_labels: 10.5 bold muted; today = accent circle, selected = text circle; future labels faint. */
internal fun DrawScope.vAxisLabels(ink: VInk, p: VPlot, labels: List<String?>, today: Int?, sel: Int?, futureFrom: Int?) {
    val fs = 10.5f
    val cy = p.base + u(3) + u(fs * 0.62f)
    labels.forEachIndexed { i, t ->
        t ?: return@forEachIndexed
        val cx = p.X(i)
        when (i) {
            sel, today -> {
                val tw = textWidth(ink, t, fs - 0.5f, FontWeight.Bold, density)
                val r = max(max(tw, u(fs * 0.72f)) / 2f + u(5), u(9))
                drawCircle(if (i == sel) ink.text else ink.accent, r, Offset(cx, cy))
                textMid(ink, t, cx, cy, fs - 0.5f, if (i == sel) ink.card else ink.onAccent, FontWeight.Bold, 0.5f)
            }
            else -> {
                val fut = futureFrom != null && i >= futureFrom
                textMid(ink, t, cx, cy, fs, if (fut) ink.muted.copy(alpha = 0.45f) else ink.muted, FontWeight.Bold, 0.5f)
            }
        }
    }
}

/** A value tag (9.5 bold text), baseline at [baseline]. */
internal fun DrawScope.vTag(ink: VInk, s: String, x: Float, baseline: Float, align: Float = 0.5f) = textAt(ink, s, x, baseline, 9.5f, ink.text, FontWeight.Bold, align)

/** Highest tag above its point, lowest below (≥ 3 points; vitals_r3.extreme_tags). */
internal fun DrawScope.vExtremeTags(ink: VInk, p: VPlot, pts: List<VPt>, dec: Int) {
    if (pts.size < 3) return
    val hi = pts.maxWith(compareBy<VPt> { it.v }.thenBy { it.i }); val lo = pts.minWith(compareBy<VPt> { it.v }.thenBy { it.i })
    val lastI = pts.last().i
    for ((pt, up) in listOf(hi to true, lo to false)) {
        val x = pt.o.x.coerceIn(p.x + u(8), p.x + p.w - u(8))
        val extra = if (pt.i == lastI) u(3) else 0f
        vTag(ink, R3Texts.dec(pt.v, dec), x, if (up) pt.o.y - u(6) - extra else pt.o.y + u(13) + extra)
    }
}

/** vitals_r3.low_tag: under the mark, or beside it when that would hit the axis row. */
internal fun DrawScope.vLowTag(ink: VInk, p: VPlot, i: Int, v: Double, s: String) {
    val y = p.Y(v) + u(14)
    if (y > p.base - 1) {
        val right = p.X(i) < p.x + p.w - u(30)
        vTag(ink, s, p.X(i) + (if (right) u(8) else -u(8)), p.Y(v) + u(3.5), if (right) 0f else 1f)
    } else vTag(ink, s, p.X(i), y)
}

/** Day column behind a marked day (tileHi 55 %). */
internal fun DrawScope.vDayColumn(ink: VInk, p: VPlot, i: Int, y0: Float, y1: Float) {
    val cw = max(min(p.slot * 0.9f, u(26)), u(10))
    drawRoundRect(ink.tileHi.copy(alpha = 0.55f), Offset(p.X(i) - cw / 2f, y0), Size(cw, y1 - y0), CornerRadius(min(cw / 2f, u(8))))
}

/** Buckets for [scrubAndTap] over a domain (value 1 where any data is drawn, so the scrub snaps to data). */
internal fun domainBuckets(d: Domain, has: (Int) -> Boolean, today: LocalDate): List<SeriesBucket> = d.keys.mapIndexed { i, k ->
    val end = if (d.kind == DomainKind.DAY) k.plusDays(1) else k.plusMonths(1)
    SeriesBucket(k.atStartOfDay(), end.atStartOfDay(), if (has(i)) 1.0 else null, future = k.isAfter(today))
}

/** Index of the scrubbed bucket in [d] (null when not scrubbing). */
internal fun scrubIndex(s: ScrubState, d: Domain): Int? = s.day?.let { d.indexOf(it) }

// ---------------------------------------------------------------------------------------------------------------------
// S cards: Resting heart rate · HRV · Breathing rate · Blood oxygen
// ---------------------------------------------------------------------------------------------------------------------

/** Cover card heights (mock-ups: S 268, Heart rate 326). */
private val COVER_S_H = 268.dp
private val COVER_HR_H = 326.dp

@Composable
fun VitalCardR3(ctx: SectionContext, id: String) {
    val m = VitalsModels.of(ctx) ?: return
    val cover = !ctx.wide
    val f = m.f
    val scrub = rememberScrub(ctx.scope to id)
    val s = m.data.s(id)
    val rd = m.reading(id)
    val dom = m.cardDomain
    val si = scrubIndex(scrub, dom)
    // scrub: the value block mirrors the held point (day: its reading and date; month: its lowest–highest and month)
    val held: Pair<String, String>? = si?.let { i ->
        val k = dom.keys[i]
        if (dom.kind == DomainKind.DAY) s[k]?.let { vVal(id, it) to vDay(k, f.today) }
        else VitalsMath.rows(s, dom, f.today)[i]?.let { vRange(id, it) to R3Texts.monthYear(k) }
    }
    val value = held?.first ?: rd.at?.let { vVal(id, it.value) } ?: R3Texts.DASH
    val context = held?.second ?: rd.at?.let { if (rd.carried) R3Texts.lastMeasured(it.day, f.today) else vDay(it.day, f.today) }
    val band = m.cardBand(id)
    val key = if (band != null) R3Texts.ghostWordShort(f.period)?.let { GhostKeyModel(it, GhostKind.BAND) } else null
    R3CardFrame(
        title = vitalTitle(id), glyph = vitalGlyph(id), color = vitalColor(id), onClick = { ctx.openMetric(id) },
        modifier = if (cover) Modifier.height(COVER_S_H) else Modifier,
    ) {
        ValueBlock(value, unit = vUnit(id).ifEmpty { null }, context = context, pill = vPill(id, m.pill(id), f),
            size = if (cover) ValueSize.COVER else ValueSize.S, muted = held == null && (rd.carried || rd.at == null),
            trailingKey = key, pillAlpha = scrub.pillAlpha)
        Spacer(Modifier.height(2.dp))
        // tap: select the day (W/M) / open the month (1Y) / the year (All); on the 7-day context window: the metric page
        VitalChart(m, id, scrub, onTap = { b -> if (dom.window) ctx.openMetric(id) else ctx.drill(b.day) }, modifier = Modifier.weight(1f).fillMaxWidth())
    }
}

/** vitals_r3.metric_chart: band + points (day buckets) or monthly capsules, tags, future stubs, axis. */
@Composable
internal fun VitalChart(m: VitalsModel, id: String, scrub: ScrubState, onTap: (SeriesBucket) -> Unit, modifier: Modifier) {
    val ink = rememberInk()
    val f = m.f
    val dom = m.cardDomain
    val spec = VitalsSpecs.of(id)
    val color = vitalColor(id)
    val rows = remember(m, id) { VitalsMath.rows(m.data.s(id), dom, f.today) }
    val band = m.cardBand(id)
    val (lo, hi) = remember(m, id) {
        VitalsMath.yRange(rows.filterNotNull().flatMap { listOf(it.lo, it.hi) }, band?.let { listOf(it.lo, it.hi) }.orEmpty(), 0.3, spec.cardSpan)
    }
    val ticks = remember(lo, hi) { VitalsMath.niceTicks(lo, hi, 2) }
    val buckets = remember(m, id) { domainBuckets(dom, { rows[it] != null }, f.today) }
    val si = scrubIndex(scrub, dom)
    val a11y = vitalTitle(id) + (rows.lastOrNull { it != null }?.let { ", ${vRange(id, it)} ${vUnit(id)}" } ?: "")
    Canvas(modifier.fillMaxSize().semantics { contentDescription = a11y }.scrubAndTap(buckets, scrub, onTap, V_GUTTER.dp, true)) {
        val p = vFrame(ink, 0f, 0f, size.width, size.height, dom.n, lo, hi, ticks, if (id == "breathing") 1 else 0)
        if (band != null) vGhostBand(ink, p, band)
        if (dom.kind == DomainKind.MONTH) {
            vCapsules(p, rows, color, if (rows.size <= 12) 7f else 5f, sel = si)
            val rr = rows.withIndex().filter { it.value != null }
            if (rr.isNotEmpty()) {
                val h = rr.maxWith(compareBy<IndexedValue<VRow?>> { it.value!!.hi }.thenBy { it.index })
                vTag(ink, R3Texts.dec(h.value!!.hi, spec.decimals), p.X(h.index), p.Y(h.value!!.hi) - u(6))
                val l = rr.minWith(compareBy<IndexedValue<VRow?>> { it.value!!.lo }.thenBy { it.index })
                vLowTag(ink, p, l.index, l.value!!.lo, R3Texts.dec(l.value!!.lo, spec.decimals))
            }
        } else {
            dom.futureFrom?.let { ff ->
                val bw = min(p.slot * 0.5f, u(6))
                for (i in ff until rows.size) drawRoundRect(ink.tileHi.copy(alpha = 0.8f), Offset(p.X(i) - bw / 2f, p.base - u(3)), Size(bw, u(3)), CornerRadius(u(1.5)))
            }
            val sel = si ?: if (dom.window) dom.selIndex else null
            val pts = vPoints(ink, p, rows.map { it?.lo }, color, dom.futureFrom, sel, dim = dom.window || si != null, band = band,
                area = band == null, lastRing = !dom.window && si == null)
            vExtremeTags(ink, p, pts, spec.decimals)
        }
        if (si != null) drawLine(ink.text.copy(alpha = 0.35f), Offset(p.X(si), p.y), Offset(p.X(si), p.base), u(1))
        val selLabel = if (dom.window && dom.todayIndex != dom.selIndex) dom.selIndex else null
        vAxisLabels(ink, p, dom.labels, dom.todayIndex, selLabel, dom.futureFrom)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Heart rate card (M): range / latest sample, day curve or band, rail Lowest · Highest · Resting heart rate
// ---------------------------------------------------------------------------------------------------------------------

@Composable
fun HeartRateCardR3(ctx: SectionContext) {
    val m = VitalsModels.of(ctx) ?: return
    val f = m.f
    val cover = !ctx.wide
    val facts = m.hrCard
    val scrub = rememberScrub(ctx.scope to "hr")
    val bpm = vUnit("hr")
    val dom = m.cardDomain
    val si = if (f.dayScope) null else scrubIndex(scrub, dom)
    val held: Pair<String, String>? = si?.let { i ->
        val k = dom.keys[i]
        if (dom.kind == DomainKind.DAY) { val a = m.data.hrMin[k]; val b = m.data.hrMax[k]; if (a != null && b != null) R3Texts.range(a, b) to vDay(k, f.today) else null }
        else VitalsMath.hrRows(m.data.hrMin, m.data.hrMax, dom, f.today)[i]?.let { R3Texts.range(it.lo, it.hi) to R3Texts.monthYear(k) }
    }
    val lo = facts.lo; val hi = facts.hi
    val (value, context) = when {
        held != null -> held
        lo == null || hi == null -> R3Texts.DASH to null
        f.day != null && facts.latest != null -> R3Texts.int(facts.latest.second.toDouble()) to R3Texts.rangeToday(lo.value, hi.value)
        f.day != null -> R3Texts.range(lo.value, hi.value) to vDay(f.day, f.today)
        f.isAll -> R3Texts.range(lo.value, hi.value) to R3Texts.sinceCap(m.data.dataStart)
        else -> R3Texts.range(lo.value, hi.value) to (facts.prev?.let { pr -> R3Texts.ghostWordShort(f.period)?.let { R3Texts.rangeBpm(it, pr.lo, pr.hi) } })
    }
    fun note(e: com.boudhealth.data.Extreme?, t: java.time.LocalTime?): String? =
        if (f.day != null) t?.let { R3Texts.hhmm(it) } else e?.let { R3Texts.dMonRel(it.day, f.today) }
    val cells = listOf(
        StatCell(R3Texts.LOWEST, lo?.let { R3Texts.int(it.value) } ?: R3Texts.DASH, bpm, if (cover) null else note(lo, facts.loTime)),
        StatCell(R3Texts.HIGHEST, hi?.let { R3Texts.int(it.value) } ?: R3Texts.DASH, bpm, if (cover) null else note(hi, facts.hiTime)),
        StatCell(if (cover) R3Texts.RESTING_HR_SHORT else R3Texts.RESTING_HR, facts.rhr?.let { R3Texts.int(it.value) } ?: R3Texts.DASH, bpm),
    )
    R3CardFrame(
        title = R3Texts.HEART_RATE, glyph = vitalGlyph("hr"), color = vitalColor("hr"), onClick = { ctx.openMetric("hr") },
        modifier = if (cover) Modifier.height(COVER_HR_H) else Modifier,
    ) {
        if (cover) {
            ValueBlock(value, unit = bpm, context = context, size = ValueSize.COVER, muted = lo == null)
            Spacer(Modifier.height(6.dp))
            HrChart(m, scrub, onTap = { b -> if (!f.dayScope) ctx.drill(b.day) }, modifier = Modifier.fillMaxWidth().height(120.dp))
            Spacer(Modifier.height(12.dp))
            CoverStatRow(cells)
        } else MRow(
            left = {
                Column(Modifier.fillMaxSize()) {
                    ValueBlock(value, unit = bpm, context = context, size = ValueSize.M, muted = lo == null)
                    Spacer(Modifier.height(6.dp))
                    HrChart(m, scrub, onTap = { b -> if (!f.dayScope) ctx.drill(b.day) }, modifier = Modifier.weight(1f).fillMaxWidth())
                }
            },
            rail = { RailCells(cells, Modifier.fillMaxSize()) },
        )
    }
}

/**
 * Heart rate chart on a fixed 30–190 scale (ticks 50 · 100 · 150): day scope = the day curve of 5-minute maxima (gaps
 * break it, the highest tagged, today's last point ringed); W/M = daily lowest–highest band (capsules below 6 days);
 * Y/All = weekly lowest–highest band. Highest tagged above, lowest below.
 */
@Composable
internal fun HrChart(m: VitalsModel, scrub: ScrubState, onTap: (SeriesBucket) -> Unit, modifier: Modifier) {
    val ink = rememberInk()
    val f = m.f
    val c = vitalColor("hr")
    val dom = m.cardDomain
    val ticks = listOf(50.0, 100.0, 150.0)
    if (f.dayScope) {
        val curve = m.hrCard.curve
        val today = f.day == f.today
        Canvas(modifier.fillMaxSize().semantics { contentDescription = R3Texts.HEART_RATE }) {
            val n = 288
            val p = vFrame(ink, 0f, 0f, size.width, size.height, n, 30.0, 190.0, ticks, 0)
            if (curve != null) {
                val runs = ArrayList<List<Int>>(); var cur = ArrayList<Int>()
                curve.forEachIndexed { i, v -> if (v != null) cur.add(i) else if (cur.isNotEmpty()) { runs += cur; cur = ArrayList() } }
                if (cur.isNotEmpty()) runs += cur
                for (run in runs) {
                    if (run.size < 2) continue
                    val line = Path().apply { run.forEachIndexed { k, i -> val o = Offset(p.X(i), p.Y(curve[i]!!)); if (k == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
                    val area = Path().apply { addPath(line); lineTo(p.X(run.last()), p.base); lineTo(p.X(run.first()), p.base); close() }
                    drawPath(area, c.copy(alpha = 0.22f))
                    drawPath(line, c, style = Stroke(u(1.4), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                val idx = curve.indices.filter { curve[it] != null }
                if (idx.isNotEmpty()) {
                    val ih = idx.maxWith(compareBy<Int> { curve[it]!! }.thenByDescending { it })
                    vTag(ink, R3Texts.int(curve[ih]!!), p.X(ih), p.Y(curve[ih]!!) - u(6))
                    if (today) { val il = idx.last(); val o = Offset(p.X(il), p.Y(curve[il]!!)); drawCircle(ink.card, u(5.2), o); drawCircle(c, u(3.8), o) }
                }
            }
            val labels = (0 until n).map { i -> if ((i * 5) % 360 == 0) "${i * 5 / 60}" else null }
            vAxisLabels(ink, p, labels, null, null, null)
        }
        return
    }
    val month = dom.kind == DomainKind.MONTH
    val weeks = remember(m) { if (month) VitalsMath.hrWeeks(m.data.hrMin, m.data.hrMax, dom, f.today) else null }
    val rows = remember(m) { weeks?.rows ?: VitalsMath.hrRows(m.data.hrMin, m.data.hrMax, dom, f.today) }
    val labels = weeks?.labels ?: dom.labels
    val ti = if (weeks != null) weeks.todayIndex else dom.todayIndex
    val ff = if (weeks != null) weeks.futureFrom else dom.futureFrom
    val hrRowsDom = remember(m) { VitalsMath.hrRows(m.data.hrMin, m.data.hrMax, dom, f.today) }
    val buckets = remember(m) { domainBuckets(dom, { hrRowsDom[it] != null }, f.today) }
    val si = scrubIndex(scrub, dom)
    Canvas(modifier.fillMaxSize().semantics { contentDescription = R3Texts.HEART_RATE }.scrubAndTap(buckets, scrub, onTap, V_GUTTER.dp, true)) {
        val p = vFrame(ink, 0f, 0f, size.width, size.height, rows.size, 30.0, 190.0, ticks, 0)
        val elapsed = rows.indices.count { ff == null || it < ff }
        if (elapsed < 6) {
            val bw = min(p.slot * 0.42f, u(12))
            rows.forEachIndexed { i, r ->
                r ?: return@forEachIndexed
                val t = p.Y(r.hi); val hh = max(bw, p.Y(r.lo) - t)
                drawRoundRect(c.copy(alpha = if (si == null || si == i) 0.85f else 0.4f), Offset(p.X(i) - bw / 2f, t), Size(bw, hh), CornerRadius(bw / 2f))
            }
        } else vBandFill(p, rows, c, 0.34f)
        val rr = rows.withIndex().filter { it.value != null }
        if (rr.isNotEmpty()) {
            val h = rr.maxWith(compareBy<IndexedValue<VRow?>> { it.value!!.hi }.thenBy { it.index })
            vTag(ink, R3Texts.int(h.value!!.hi), p.X(h.index), p.Y(h.value!!.hi) - u(6))
            val l = rr.minWith(compareBy<IndexedValue<VRow?>> { it.value!!.lo }.thenBy { it.index })
            vLowTag(ink, p, l.index, l.value!!.lo, R3Texts.int(l.value!!.lo))
        }
        if (si != null && !month) {
            drawLine(ink.text.copy(alpha = 0.35f), Offset(p.X(si), p.y), Offset(p.X(si), p.base), u(1))
            rows.getOrNull(si)?.let { r -> drawCircle(c, u(3.5), Offset(p.X(si), p.Y(r.hi))); drawCircle(c, u(3.5), Offset(p.X(si), p.Y(r.lo))) }
        }
        vAxisLabels(ink, p, labels, ti, null, ff)
    }
}
