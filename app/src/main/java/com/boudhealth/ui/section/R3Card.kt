package com.boudhealth.ui.section

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.data.TrendDir
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.GlyphIcon
import com.boudhealth.ui.components.HBCard
import com.boudhealth.ui.components.plainClickable
import com.boudhealth.ui.theme.HB
import com.boudhealth.ui.theme.LocalHB
import kotlin.math.roundToInt

// =====================================================================================================================
// R3 card anatomy (IMPL_PLAN §2.3, kit/r3ui.py value_block / dpill / ghost_key, act.py card_top / rail_cells):
// header (glyph 18 centred x+26, title 14.5/700 at x+42, (i)), value block at fixed text lines, delta pill, ghost key,
// M-card rail. Plus the shared status icon (▲ ▼ ● ✓ or a metric glyph) drawn on a Canvas so it is exactly centred.
// =====================================================================================================================

/** (background, foreground) of a status icon / chip by tone (kit theme.tone_cols: neutral = tileHi / text). */
fun HB.statusColors(tone: Tone): Pair<Color, Color> = when (tone) {
    Tone.GOOD -> colors.goodCont to colors.good
    Tone.BAD -> colors.badCont to colors.bad
    Tone.NEUTRAL -> colors.tileHi to colors.text
}

/** kit6.tri: ▲ / ▼ (s = size) or a dot (FLAT / null) centred at (cx, cy). */
fun DrawScope.drawTri(cx: Float, cy: Float, s: Float, dir: TrendDir?, color: Color) {
    when (dir) {
        TrendDir.UP -> drawPath(Path().apply { moveTo(cx, cy - s * 0.42f); lineTo(cx + s * 0.5f, cy + s * 0.36f); lineTo(cx - s * 0.5f, cy + s * 0.36f); close() }, color)
        TrendDir.DOWN -> drawPath(Path().apply { moveTo(cx, cy + s * 0.42f); lineTo(cx + s * 0.5f, cy - s * 0.36f); lineTo(cx - s * 0.5f, cy - s * 0.36f); close() }, color)
        else -> drawCircle(color, s * 0.26f, Offset(cx, cy))
    }
}

/** kit widgets.status_icon check mark inside a circle of [size]. */
fun DrawScope.drawCheck(cx: Float, cy: Float, size: Float, color: Color) {
    val s = size * 0.36f
    drawPath(Path().apply { moveTo(cx - s * 0.62f, cy + s * 0.02f); lineTo(cx - s * 0.15f, cy + s * 0.48f); lineTo(cx + s * 0.66f, cy - s * 0.44f) },
        color, style = Stroke(size * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Status icon (▲ ▼ ● ✓) in a tinted circle; [glyph] (icon GLYPH) = neutral metric glyph on a tileHi circle. DONE is always GOOD. */
@Composable
fun R3StatusIcon(icon: TileIcon, tone: Tone, size: Dp = 18.dp, glyph: Glyph? = null) {
    val hb = LocalHB.current
    if (icon == TileIcon.GLYPH || (glyph != null && icon == TileIcon.GLYPH)) {
        Box(Modifier.size(size).background(hb.colors.tileHi, CircleShape), contentAlignment = Alignment.Center) {
            if (glyph != null) GlyphIcon(glyph, size = size * (12f / 18f), color = hb.colors.muted)
        }
        return
    }
    val (bg, fg) = hb.statusColors(if (icon == TileIcon.DONE) Tone.GOOD else tone)
    Canvas(Modifier.size(size)) {
        val c = center; val d = this.size.minDimension
        drawCircle(bg, d / 2f, c)
        when (icon) {
            TileIcon.DONE -> drawCheck(c.x, c.y, d, fg)
            TileIcon.UP -> drawTri(c.x, c.y, d * 0.45f, TrendDir.UP, fg)
            TileIcon.DOWN -> drawTri(c.x, c.y, d * 0.45f, TrendDir.DOWN, fg)
            else -> drawTri(c.x, c.y, d * 0.45f, TrendDir.FLAT, fg)
        }
    }
}

/** Set by [R3CardFrame] so [MRow] can run its rail and divider from card top 18 to card bottom − 18 (beside the header). */
private val LocalInR3Frame = staticCompositionLocalOf { false }

/** Header height of [R3CardFrame] (header row centre at 26). */
val R3_HEADER_H = 52.dp

/**
 * R3 card: r 22, header (glyph 18 centred at x+26, title 14.5/700 at x+42, (i) as today), content padded 20 (bottom 14).
 * The parent sets the height (CardHost: 288 inner). [headerTrailing] sits right-aligned 16 from the edge.
 */
@Composable
fun R3CardFrame(
    title: String, glyph: Glyph, color: Color, info: InfoText? = null,
    headerTrailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)?, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val hb = LocalHB.current
    var showInfo by remember { mutableStateOf(false) }
    HBCard(modifier.fillMaxWidth().fillMaxHeight(), padding = PaddingValues(0.dp), onClick = onClick) {
        Row(Modifier.fillMaxWidth().height(R3_HEADER_H).padding(start = 17.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(glyph, size = 18.dp, color = hb.legibleGlyph(color))
            Spacer(Modifier.width(7.dp))
            Text(title, style = CardTitle, color = hb.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (info != null) {
                Spacer(Modifier.width(2.dp))
                Box(Modifier.plainClickable { showInfo = true }.padding(4.dp).semantics { contentDescription = "About ${info.title}" }) {
                    GlyphIcon(Glyph.Info, size = 15.dp, color = hb.colors.muted)
                }
            }
            Spacer(Modifier.weight(1f))
            headerTrailing?.invoke()
        }
        CompositionLocalProvider(LocalInR3Frame provides true) {
            Column(Modifier.fillMaxWidth().fillMaxHeight().padding(start = R3.CARD_PAD, end = R3.CARD_PAD, bottom = 14.dp)) { content() }
        }
    }
    if (showInfo && info != null) InfoSheet(info) { showInfo = false }
}

/** Value size: S 32 · M 40 · cover 30 sp. */
enum class ValueSize(val sp: Float, val valueY: Dp) { S(32f, 86.dp), M(40f, 90.dp), COVER(30f, 82.dp) }

/** Delta pill model: [text] has NO sign when [dir] is UP / DOWN ("0.2 kg vs 31 Oct"); dir null = no icon. */
data class DeltaPillModel(val dir: TrendDir?, val text: String, val tone: Tone)

/** Kind of previous-period swatch: bar (Activity), line (Body), band (Vitals). */
enum class GhostKind { BAR, LINE, BAND }
data class GhostKeyModel(val word: String, val kind: GhostKind)

/**
 * The value block at fixed text lines from the CARD TOP (it must be the first thing under the [R3CardFrame] header):
 * value text line S 86 / M 90 / cover 82 · "/ goal" ([rest]) 0.62× muted bold · [unit] 0.5× muted · [context] 12.5 muted
 * (cover 12) at +22 · [pill] centred at +44 · [trailingKey] right-aligned on the pill row. Ends at +62 (pill) / +42.
 * [muted] = carried value. [contextExtra] draws inline after the context (e.g. the WHtR (i)).
 */
@Composable
fun ValueBlock(
    value: String, rest: String? = null, unit: String? = null, context: String? = null,
    pill: DeltaPillModel? = null, size: ValueSize, muted: Boolean = false,
    contextExtra: (@Composable () -> Unit)? = null, trailingKey: GhostKeyModel? = null,
    /** Scrub: 0..1 opacity of the pill (it fades out while the value mirrors a scrubbed point). */
    pillAlpha: Float = 1f,
) {
    val hb = LocalHB.current
    val fs = size.sp
    val cover = size == ValueSize.COVER
    val b0 = size.valueY - R3_HEADER_H          // value text line inside the block
    val a11y = listOfNotNull(value, rest, unit, context, pill?.let { pillA11y(it) }).joinToString(" ")
    Layout(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = a11y },
        content = {
            Text(buildAnnotatedString {
                withStyle(SpanStyle(fontSize = fs.sp, fontWeight = FontWeight.Bold, color = if (muted) hb.colors.muted else hb.colors.text)) { append(value) }
                if (rest != null) withStyle(SpanStyle(fontSize = (fs * 0.62f).sp, fontWeight = FontWeight.Bold, color = hb.colors.muted)) { append(" $rest") }
                if (unit != null) withStyle(SpanStyle(fontSize = (fs * 0.5f).sp, fontWeight = FontWeight.W400, color = hb.colors.muted)) { append(" $unit") }
            }, maxLines = 1, style = TextStyle(fontFeatureSettings = "tnum"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (context != null) Text(context, fontSize = (if (cover) 12f else 12.5f).sp, color = hb.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                contextExtra?.invoke()
            }
            Box(Modifier.alpha(pillAlpha)) { if (pill != null) DeltaPill(pill) }
            Box { if (trailingKey != null) GhostKey(trailingKey) }
        },
    ) { ms, c ->
        val w = c.maxWidth
        val loose = c.copy(minWidth = 0, minHeight = 0)
        val pv = ms[0].measure(loose); val pc = ms[1].measure(loose)
        val pk = ms[3].measure(loose)
        val pp = ms[2].measure(loose.copy(maxWidth = (w - pk.width - 8.dp.roundToPx()).coerceAtLeast(0)))
        val base = b0.roundToPx()
        fun bl(p: androidx.compose.ui.layout.Placeable) = p[FirstBaseline].takeIf { it != androidx.compose.ui.layout.AlignmentLine.Unspecified } ?: (p.height * 0.78f).roundToInt()
        val hasPill = pill != null
        val h = (b0 + if (hasPill) 62.dp else 42.dp).roundToPx()
        val rowC = if (hasPill) base + 44.dp.roundToPx() else h - 18.dp.roundToPx()
        layout(w, h) {
            pv.place(0, base - bl(pv))
            if (context != null || contextExtra != null) pc.place(0, base + 22.dp.roundToPx() - (if (context != null) bl(pc) else pc.height / 2))
            if (hasPill) pp.place(0, rowC - pp.height / 2)
            if (trailingKey != null) pk.place(w - pk.width, rowC - pk.height / 2)
        }
    }
}

private fun pillA11y(m: DeltaPillModel): String = when (m.dir) {
    TrendDir.UP -> "${R3Texts.A11Y_UP} ${m.text}"
    TrendDir.DOWN -> "${R3Texts.A11Y_DOWN} ${m.text}"
    else -> m.text
}

/** The one delta pill: h 24, r 12, 12/600, ▲ / ▼ / ● + text (no sign with an arrow). Neutral: tile (tileHi on a tile) / muted. */
@Composable
fun DeltaPill(m: DeltaPillModel, onTile: Boolean = false) {
    val hb = LocalHB.current
    val (bg, fg) = when (m.tone) {
        Tone.GOOD -> hb.colors.goodCont to hb.colors.good
        Tone.BAD -> hb.colors.badCont to hb.colors.bad
        Tone.NEUTRAL -> (if (onTile) hb.colors.tileHi else hb.colors.tile) to hb.colors.muted
    }
    Row(Modifier.height(24.dp).background(bg, RoundedCornerShape(12.dp)).padding(start = if (m.dir != null) 8.5.dp else 10.dp, end = 10.dp)
        .semantics { contentDescription = pillA11y(m) }, verticalAlignment = Alignment.CenterVertically) {
        if (m.dir != null) {
            Canvas(Modifier.size(9.dp)) { drawTri(center.x, center.y, size.minDimension, m.dir, fg) }
            Spacer(Modifier.width(4.5.dp))
        }
        Text(m.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFeatureSettings = "tnum"))
    }
}

/** Previous-period key: swatch 10×10 r 2.5 (line: 12×2) in muted + word 11/600 muted. Always above the chart. */
@Composable
fun GhostKey(m: GhostKeyModel) {
    val hb = LocalHB.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (m.kind) {
            GhostKind.LINE -> Box(Modifier.width(12.dp).height(2.dp).background(hb.colors.muted.copy(alpha = GhostStyle.LINE_A + 0.2f), RoundedCornerShape(1.dp)))
            else -> Box(Modifier.size(10.dp).background(hb.colors.muted.copy(alpha = GhostStyle.FILL_A * 1.6f), RoundedCornerShape(2.5.dp)))
        }
        Spacer(Modifier.width(6.dp))
        Text(m.word, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.muted, maxLines = 1)
    }
}

/** One stat cell (M-card rail, cover stat row). [note] = short muted text after the value. */
data class StatCell(val label: String, val value: String, val unit: String? = null, val note: String? = null)

/** M-card rail: cells stacked with 1 dp grid dividers; label 11.5/600 muted, value 18/700 + unit 12 muted (act.py rail_cells). */
@Composable
fun RailCells(cells: List<StatCell>, modifier: Modifier) {
    val hb = LocalHB.current
    Column(modifier) {
        cells.forEachIndexed { i, c ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(hb.colors.grid))
            Box(Modifier.fillMaxWidth().weight(1f).semantics(mergeDescendants = true) {}, contentAlignment = Alignment.CenterStart) {
                Column {
                    Text(c.label, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = hb.colors.text)) { append(c.value) }
                        if (c.unit != null) withStyle(SpanStyle(fontSize = 12.sp, color = hb.colors.muted)) { append(" ${c.unit}") }
                        if (c.note != null) withStyle(SpanStyle(fontSize = 11.5.sp, color = hb.colors.muted)) { append("  ${c.note}") }
                    }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFeatureSettings = "tnum"))
                }
            }
        }
    }
}

/** Cover stat row (kit stat_row h 58): tile r 18, cells split by 1 dp grid lines inset 18; label 12 muted, value 20/17 bold. */
@Composable
fun CoverStatRow(cells: List<StatCell>) {
    val hb = LocalHB.current
    Row(Modifier.fillMaxWidth().height(58.dp).background(hb.colors.tile, RoundedCornerShape(18.dp))) {
        val big = cells.size <= 2
        cells.forEachIndexed { i, c ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 18.dp).background(hb.colors.grid))
            Column(Modifier.weight(1f).fillMaxHeight().padding(start = if (big) 16.dp else 12.dp, end = 4.dp).semantics(mergeDescendants = true) {},
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                Text(c.label, fontSize = 12.sp, color = hb.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(fontSize = (if (big) 20 else 17).sp, fontWeight = FontWeight.Bold, color = hb.colors.text)) { append(c.value) }
                    if (c.unit != null) withStyle(SpanStyle(fontSize = 12.sp, color = hb.colors.muted)) { append(" ${c.unit}") }
                }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFeatureSettings = "tnum"))
            }
        }
    }
}

/**
 * M-card body: [left] (= card width − 236, i.e. chart width − 216 of the content), 1 dp grid divider, [rail] 188 wide
 * (208 with the card's right padding). Inside an [R3CardFrame] the divider and rail run from card top + 18 to card
 * bottom − 18, beside the header (act.py c_total_bars). Use as the frame's whole content: `R3CardFrame(...) { MRow(...) }`.
 */
@Composable
fun MRow(left: @Composable () -> Unit, rail: @Composable () -> Unit) {
    val hb = LocalHB.current
    val inFrame = LocalInR3Frame.current
    val up = if (inFrame) R3_HEADER_H - 18.dp else 0.dp          // 34: rail starts at card top + 18
    val down = if (inFrame) (-4).dp else 0.dp                    // ends at card bottom − 18 (content bottom is − 14)
    fun Modifier.stretch(): Modifier = layout { m, c ->
        val extra = (up + down).roundToPx()
        val h = if (c.hasBoundedHeight) c.maxHeight + extra else c.maxHeight
        val p = m.measure(c.copy(minHeight = if (c.hasBoundedHeight) h.coerceAtLeast(0) else c.minHeight, maxHeight = if (c.hasBoundedHeight) h.coerceAtLeast(0) else c.maxHeight))
        layout(p.width, c.maxHeight.takeIf { c.hasBoundedHeight } ?: p.height) { p.place(0, -up.roundToPx()) }
    }
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight()) { left() }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.width(1.dp).fillMaxHeight().stretch().background(hb.colors.grid))
        Spacer(Modifier.width(17.dp))
        Box(Modifier.width(188.dp).fillMaxHeight().stretch()) { rail() }
    }
}

/** Previous-period ghost style (kit/r3ui.py GHOST_*): one grey (muted) for every chart type, both themes. */
object GhostStyle {
    const val FILL_A = 0.20f      // bars
    const val LINE_A = 0.50f      // lines
    val LINE_W = 1.2.dp
    const val BAND_A = 0.14f      // band fill (FILL_A × 0.7)
    const val EDGE_A = 0.40f      // band edges, dashed 2 / 3
}

/** Small spacer used by tab cards between a ValueBlock and a chart. */
@Composable fun R3Gap(h: Dp = 2.dp) = Spacer(Modifier.height(h))

/** Offset helper for absolutely placed overlays inside a card. */
fun Modifier.r3At(x: Dp, y: Dp): Modifier = this.offset(x, y)
