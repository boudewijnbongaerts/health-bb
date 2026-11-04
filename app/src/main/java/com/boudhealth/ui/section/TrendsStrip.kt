package com.boudhealth.ui.section

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.HBCard
import com.boudhealth.ui.components.plainClickable
import com.boudhealth.ui.theme.LocalHB
import kotlin.math.max
import kotlin.math.min

// =====================================================================================================================
// R3 Trends strip (IMPL_PLAN §2.2, port of kit/r3ui.py trends_strip / fact_tile / mv_* and actlib mv_goals / mv_pace /
// mv_minibars / mv_range). One card "Trends" + the comparison basis, 6 fact tiles (inner 6 × 1, cover 2 × 3), 92 dp.
// =====================================================================================================================

/** Status icon of a tile (canonical type: com.boudhealth.data.TileIcon). */
typealias TileIcon = com.boudhealth.data.TileIcon
/** GOOD / BAD / NEUTRAL (canonical type: com.boudhealth.data.Tone). */
typealias Tone = com.boudhealth.data.Tone

/** The strip: [basis] = right-aligned comparison basis ("vs 1–4 Oct until 13:05"), null = none. Always 6 tiles. */
data class TrendsModel(val basis: String?, val tiles: List<TrendTileModel>)

/**
 * One fact tile. [suffix] = short muted text after the value; it moves into the caption band when it does not fit.
 * [glyph] is required when [icon] == GLYPH (plain value: neutral metric glyph). [a11y] = the spoken text (§5.7).
 * [onClick] → the metric detail page at the period the fact refers to.
 */
data class TrendTileModel(
    val icon: TileIcon, val tone: Tone, val label: String, val value: String,
    val suffix: String? = null,
    val visual: MicroVisual? = null,
    val glyph: Glyph? = null,
    val a11y: String,
    val onClick: (() -> Unit)? = null,
)

/** The small fact visual in a tile's bottom band (y + h − 22 … y + h − 12, width w − 24). */
sealed interface MicroVisual {
    /** This period (colour) over the previous (ghost grey), same scale. */
    data class Cmp(val cur: Double, val prev: Double, val color: Color) : MicroVisual
    /** Two min–max capsules on one scale: this period (colour) above, previous (ghost) below. */
    data class RangePair(val cur: ClosedFloatingPointRange<Double>, val prev: ClosedFloatingPointRange<Double>, val color: Color) : MicroVisual
    data class Spark(val values: List<Double>, val color: Color, val highlightLast: Boolean = true) : MicroVisual
    /** One dot per day ([flags] true = coloured), [future] outlined dots after them. */
    data class Dots(val flags: List<Boolean>, val future: Int, val color: Color) : MicroVisual
    /** One square per week (met = colour), [currentOpen] = the running week outlined. */
    data class Ticks(val flags: List<Boolean>, val currentOpen: Boolean, val color: Color) : MicroVisual
    /** Progress to [goal] with a tick at [expectedShare] (pace). */
    data class Pace(val actual: Double, val goal: Double, val expectedShare: Double?, val color: Color) : MicroVisual
    /** Three thin bars (fraction of goal) with the expected-share tick. */
    data class Goals(val items: List<Triple<Double, Double?, Color>>) : MicroVisual
    /** Mini bars; [highlight] in colour, the rest tileHi (or all colour with [baseIsColor]). */
    data class MiniBars(val values: List<Double?>, val highlight: Int?, val color: Color, val baseIsColor: Boolean = false) : MicroVisual
    /** A [lo]..[hi] span on a [min]..[max] scale (bedtimes, minutes after 18:00). */
    data class Span(val lo: Double, val hi: Double, val min: Double, val max: Double, val color: Color) : MicroVisual
    /** Muted caption at the left, then an optional visual in the rest. */
    data class Caption(val text: String, val inner: MicroVisual? = null) : MicroVisual
    /** A one-line note in the band ("No workout yet today", BAD = bad colour). */
    data class Note(val text: String, val tone: Tone) : MicroVisual
}

/** The Trends card. [cover] = 2 × 3 tiles (height 348), else 6 × 1 (148). */
@Composable
fun TrendsStrip(model: TrendsModel, cover: Boolean, modifier: Modifier = Modifier) {
    val hb = LocalHB.current
    val measurer = rememberTextMeasurer()
    val pad = if (cover) 12.dp else 14.dp
    val g = if (cover) 8.dp else 10.dp
    HBCard(modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        // header: "Trends" 13/600 muted at (18, text line 27); basis right-aligned 12 (cover 11.5) muted, shrinking to 10
        Canvas(Modifier.fillMaxWidth().height(42.dp).semantics { contentDescription = listOfNotNull(R3Texts.TRENDS, model.basis).joinToString(", ") }) {
            val title = measurer.measure(R3Texts.TRENDS, TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.muted))
            drawText(title, topLeft = Offset(18.dp.toPx(), 27.dp.toPx() - title.firstBaseline))
            val b = model.basis ?: return@Canvas
            var fs = if (cover) 11.5f else 12f
            val maxW = size.width - 110.dp.toPx()
            var t = measurer.measure(b, TextStyle(fontSize = fs.sp, color = hb.colors.muted), maxLines = 1)
            while (t.size.width > maxW && fs > 10f) { fs -= 0.5f; t = measurer.measure(b, TextStyle(fontSize = fs.sp, color = hb.colors.muted), maxLines = 1) }
            drawText(t, topLeft = Offset(size.width - 18.dp.toPx() - t.size.width, 27.dp.toPx() - t.firstBaseline))
        }
        val tiles = model.tiles
        Column(Modifier.fillMaxWidth().padding(start = pad, end = pad, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(g)) {
            val cols = if (cover) 2 else 6
            tiles.chunked(cols).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(g)) {
                    row.forEach { t -> TrendTile(t, cover, Modifier.weight(1f)) }
                    repeat(cols - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** One fact tile (92 dp): label (2 lines), icon + value row centred at y 56, visual band at the bottom. */
@Composable
fun TrendTile(t: TrendTileModel, cover: Boolean, modifier: Modifier = Modifier) {
    val hb = LocalHB.current
    val measurer = rememberTextMeasurer()
    val click = t.onClick
    Box(
        modifier.height(R3.TILE_H).background(hb.colors.tile, RoundedCornerShape(R3.TILE_RADIUS))
            .then(if (click != null) Modifier.plainClickable { click() } else Modifier)
            .semantics { contentDescription = t.a11y },
    ) {
        // status icon 18 centred at (21, 56)
        Box(Modifier.offset(12.dp, 47.dp)) { R3StatusIcon(t.icon, t.tone, 18.dp, t.glyph) }
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            // label 11.5/600 muted (cover 11), up to 2 lines with ellipsis, baselines y+20 / y+34
            val lfs = if (cover) 11f else 11.5f
            val lab = measurer.measure(t.label, TextStyle(fontSize = lfs.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.muted, lineHeight = 14.sp),
                overflow = TextOverflow.Ellipsis, maxLines = 2, constraints = Constraints(maxWidth = (w - 24.dp.toPx()).toInt().coerceAtLeast(1)))
            drawText(lab, topLeft = Offset(12.dp.toPx(), 20.dp.toPx() - lab.firstBaseline))
            // value 17/700 (cover 16) at x+38, shrinking to 13 sp to fit; suffix 11.5/600 muted after it
            val cy = 56.dp.toPx()
            var vfs = if (cover) 16f else 17f
            val room = w - 46.dp.toPx()
            fun vStyle(f: Float) = TextStyle(fontSize = f.sp, fontWeight = FontWeight.Bold, color = hb.colors.text, fontFeatureSettings = "tnum")
            var vt = measurer.measure(t.value, vStyle(vfs), maxLines = 1)
            while (vt.size.width > room && vfs > 13f) { vfs -= 0.5f; vt = measurer.measure(t.value, vStyle(vfs), maxLines = 1) }
            val vx = 38.dp.toPx()
            drawText(vt, topLeft = Offset(vx, cy - vt.size.height / 2f))
            var viz = t.visual
            t.suffix?.let { suf ->
                val st = measurer.measure(suf, TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.muted), maxLines = 1)
                if (vx + vt.size.width + 5.dp.toPx() + st.size.width <= w - 8.dp.toPx()) {
                    drawText(st, topLeft = Offset(vx + vt.size.width + 5.dp.toPx(), cy + 0.5.dp.toPx() - st.size.height / 2f))
                } else viz = MicroVisual.Caption(suf, viz)
            }
            viz?.let { drawMicro(it, 12.dp.toPx(), h - 22.dp.toPx(), w - 24.dp.toPx(), 10.dp.toPx(), measurer, hb) }
        }
    }
}

/** Draws a micro visual in the band (x, y, w, h = 10 dp), port of the r3ui / actlib mv_* functions. Color.Unspecified = theme text. */
internal fun DrawScope.drawMicro(v: MicroVisual, x: Float, y: Float, w: Float, h: Float, measurer: TextMeasurer, hb: com.boudhealth.ui.theme.HB) {
    val dp = density
    val tileHi = hb.colors.tileHi
    val ghost = hb.colors.muted
    fun rr(x0: Float, y0: Float, ww: Float, hh: Float, r: Float, c: Color) =
        drawRoundRect(c, Offset(x0, y0), Size(max(0f, ww), max(0f, hh)), CornerRadius(min(r, hh / 2f)))
    fun cc(c: Color): Color = if (c == Color.Unspecified) hb.colors.text else c   // Unspecified = theme text colour
    when (v) {
        is MicroVisual.Cmp -> {
            val mx = max(max(v.cur, v.prev), 1e-6)
            rr(x, y - 2 * dp, w, 5 * dp, 2.5f * dp, tileHi); rr(x, y - 2 * dp, max(5 * dp, (w * v.cur / mx).toFloat()), 5 * dp, 2.5f * dp, cc(v.color))
            rr(x, y + 6 * dp, w, 5 * dp, 2.5f * dp, tileHi); rr(x, y + 6 * dp, max(5 * dp, (w * v.prev / mx).toFloat()), 5 * dp, 2.5f * dp, ghost.copy(alpha = 0.55f))
        }
        is MicroVisual.RangePair -> {
            var lo = min(v.cur.start, v.prev.start); var hi = max(v.cur.endInclusive, v.prev.endInclusive)
            val sp = (hi - lo).takeIf { it > 0 } ?: 1.0; lo -= sp * 0.08; hi += sp * 0.08
            fun X(a: Double) = x + ((a - lo) / (hi - lo) * w).toFloat()
            listOf(Triple(y - 2 * dp, v.cur, cc(v.color)), Triple(y + 6 * dp, v.prev, ghost.copy(alpha = 0.55f))).forEach { (yy, r, c) ->
                rr(x, yy, w, 5 * dp, 2.5f * dp, tileHi)
                rr(X(r.start) - 2.5f * dp, yy, max(5 * dp, X(r.endInclusive) - X(r.start) + 5 * dp), 5 * dp, 2.5f * dp, c)
            }
        }
        is MicroVisual.Spark -> {
            val vals = v.values
            if (vals.size >= 2) {
                // kit sparkline(d, x, y − 6, w, 18): plot inset 3 top / 3 bottom, range padded 10 %
                val lo0 = vals.min(); val hi0 = vals.max(); val pad = ((hi0 - lo0).takeIf { it > 0 } ?: max(kotlin.math.abs(hi0) * 0.02, 1.0)) * 0.1
                val lo = lo0 - pad; val hi = hi0 + pad
                val top = y - 6 * dp + 3 * dp; val ph = 12 * dp
                fun px(i: Int) = x + w * i / (vals.size - 1).toFloat()
                fun py(a: Double) = top + ph * (1f - ((a - lo) / (hi - lo)).toFloat())
                val path = Path().apply { moveTo(px(0), py(vals[0])); for (i in 1 until vals.size) lineTo(px(i), py(vals[i])) }
                drawPath(path, cc(v.color), style = Stroke(1.8f * dp, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                if (v.highlightLast) {
                    val o = Offset(px(vals.lastIndex), py(vals.last()))
                    drawCircle(hb.colors.tile, 3.6f * dp, o); drawCircle(cc(v.color), 2.6f * dp, o)
                }
            } else if (vals.size == 1) drawCircle(cc(v.color), 3.2f * dp, Offset(x + w - 4 * dp, y + 5 * dp))
        }
        is MicroVisual.Dots -> {
            val n = v.flags.size + v.future
            if (n == 0) return
            val g = w / n; val r = min(4 * dp, g * 0.36f)
            for (i in 0 until n) {
                val c = Offset(x + g * (i + 0.5f), y + 5 * dp)
                if (i < v.flags.size) drawCircle(if (v.flags[i]) cc(v.color) else tileHi, r, c)
                else drawCircle(tileHi, r - 0.6f * dp, c, style = Stroke(1.2f * dp))
            }
        }
        is MicroVisual.Ticks -> {
            val n = v.flags.size + if (v.currentOpen) 1 else 0
            if (n == 0) return
            val g = (if (n > 20) 1.2f else 4f) * dp
            val s = min(10 * dp, (w - g * (n - 1)) / n)
            v.flags.forEachIndexed { i, ok -> rr(x + i * (s + g), y, s, 10 * dp, min(2.5f * dp, s / 2), if (ok) cc(v.color) else tileHi) }
            if (v.currentOpen) {
                val i = v.flags.size
                drawRoundRect(hb.colors.muted, Offset(x + i * (s + g) + 0.6f * dp, y + 0.6f * dp), Size(s - 1.2f * dp, 8.8f * dp),
                    CornerRadius(min(2.5f * dp, s / 2)), style = Stroke(1.2f * dp))
            }
        }
        is MicroVisual.Pace -> {
            // kit pace_bar(d, x, y + 1, w, actual, target, share, col, h = 7)
            val hh = 7 * dp; val y0 = y + 1 * dp
            rr(x, y0, w, hh, hh / 2, tileHi)
            val q = if (v.goal > 0) (v.actual / v.goal).coerceIn(0.0, 1.0) else 0.0
            if (q > 0) rr(x, y0, max(hh, (w * q).toFloat()), hh, hh / 2, cc(v.color))
            v.expectedShare?.takeIf { it > 0 && it < 1 }?.let { sh ->
                val tx = x + (w * sh).toFloat()
                drawLine(hb.colors.text.copy(alpha = 0.7f), Offset(tx, y0 - 3 * dp), Offset(tx, y0 + hh + 3 * dp), 1.6f * dp)
            }
        }
        is MicroVisual.Goals -> {
            val g = 6 * dp; val bw = (w - 2 * g) / 3
            v.items.take(3).forEachIndexed { i, (q, sh, c) ->
                val bx = x + i * (bw + g)
                rr(bx, y + 1 * dp, bw, 7 * dp, 3.5f * dp, tileHi)
                if (q > 0) rr(bx, y + 1 * dp, max(7 * dp, (bw * min(q, 1.0)).toFloat()), 7 * dp, 3.5f * dp, cc(c))
                if (sh != null && sh > 0 && sh < 1) {
                    val tx = bx + (bw * sh).toFloat()
                    drawLine(hb.colors.text.copy(alpha = 0.75f), Offset(tx, y - 2 * dp), Offset(tx, y + 10 * dp), 1.4f * dp)
                }
            }
        }
        is MicroVisual.MiniBars -> {
            val n = v.values.size
            if (n == 0) return
            val mx = max(v.values.maxOf { it ?: 0.0 }, 1e-6)
            val slot = w / n; val bw = max(1.5f * dp, slot * 0.62f)
            v.values.forEachIndexed { i, a ->
                if (a == null || a <= 0) return@forEachIndexed
                val hh = max(1.5f * dp, ((h + 4 * dp) * a / mx).toFloat())
                val c = if (i == v.highlight || v.baseIsColor) cc(v.color) else tileHi
                rr(x + slot * i + (slot - bw) / 2, y + h - hh, bw, hh, min(1.2f * dp, bw / 2), c)
            }
        }
        is MicroVisual.Span -> {
            val span = (v.max - v.min).takeIf { it > 0 } ?: 1.0
            fun X(a: Double) = x + ((a - v.min) / span * w).toFloat().coerceIn(0f, w)
            rr(x, y + 2 * dp, w, 6 * dp, 3 * dp, tileHi)
            rr(X(v.lo), y + 2 * dp, max(6 * dp, X(v.hi) - X(v.lo)), 6 * dp, 3 * dp, cc(v.color))
        }
        is MicroVisual.Caption -> {
            val t = measurer.measure(v.text, TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.muted), maxLines = 1,
                overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = w.toInt().coerceAtLeast(1)))
            drawText(t, topLeft = Offset(x, y + 5 * dp - t.size.height / 2f))
            val rest = w - t.size.width - 10 * dp
            if (v.inner != null && rest > 24 * dp) drawMicro(v.inner, x + t.size.width + 10 * dp, y, rest, h, measurer, hb)
        }
        is MicroVisual.Note -> {
            val c = when (v.tone) { Tone.BAD -> hb.colors.bad; Tone.GOOD -> hb.colors.good; Tone.NEUTRAL -> hb.colors.muted }
            val t = measurer.measure(v.text, TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = c), maxLines = 1,
                overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = w.toInt().coerceAtLeast(1)))
            drawText(t, topLeft = Offset(x, y + 5 * dp - t.size.height / 2f))
        }
    }
}
