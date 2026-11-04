package com.boudhealth.ui.section

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.GlyphIcon
import com.boudhealth.ui.components.HBBottomSheet
import com.boudhealth.ui.components.HBCard
import com.boudhealth.ui.components.plainClickable
import com.boudhealth.ui.theme.LocalHB

/** Card frame (V2_PLAN §2.2 card anatomy): glyph + title (+ (i)) left, [trailing] (trend pill) right, then content. */
@Composable
fun MetricCardFrame(
    title: String,
    glyph: Glyph,
    color: Color,
    modifier: Modifier = Modifier,
    info: InfoText? = null,
    trailing: @Composable () -> Unit = {},
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val hb = LocalHB.current
    var showInfo by remember { mutableStateOf(false) }
    // design6 kit6.card: r 22, glyph 18 at (26, 26), title 14.5/700 at x 42, trend chip 26 dp right-aligned 16 from the edge
    HBCard(modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(start = 17.dp, end = 16.dp, top = 13.dp, bottom = 16.dp), onClick = onClick) {
        Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(glyph, size = 18.dp, color = hb.legibleGlyph(color))
            Spacer(Modifier.width(7.dp))
            Text(title, style = CardTitle, color = hb.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (info != null) {
                Spacer(Modifier.width(4.dp))
                Box(Modifier.plainClickable { showInfo = true }.padding(4.dp).semantics { contentDescription = "About ${info.title}" }) {
                    GlyphIcon(Glyph.Info, size = 15.dp, color = hb.colors.muted)
                }
            }
            Spacer(Modifier.weight(1f))
            trailing()
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.padding(start = 3.dp)) { content() }
    }
    if (showInfo && info != null) InfoSheet(info) { showInfo = false }
}

/** One of the approved (i) popups. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(info: InfoText, onDismiss: () -> Unit) {
    val hb = LocalHB.current
    HBBottomSheet(onDismiss = onDismiss, title = info.title) {
        Text(info.body, style = hb.type.body, color = hb.colors.text)
        Spacer(Modifier.height(24.dp))
    }
}

/** Card title: 14.5 sp bold (design6 kit6.card). */
val CardTitle = androidx.compose.ui.text.TextStyle(fontSize = androidx.compose.ui.unit.TextUnit(14.5f, androidx.compose.ui.unit.TextUnitType.Sp),
    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, lineHeight = androidx.compose.ui.unit.TextUnit(20f, androidx.compose.ui.unit.TextUnitType.Sp))

// =====================================================================================================================
// R3 grid (IMPL_PLAN §2.1): S = 1 column, M = 2 columns + gap. Inner: 3 columns from 900 dp content, 2 from 600 dp
// (M = full row), each card exactly 288 high. Cover: 1 column, gap 12, S cards at least 256, M cards wrap content.
// Rows fill left to right in spec order; a row closes when the next card's span would not fit.
// =====================================================================================================================

/** Card columns of the R3 grid for a content width (dp) on the inner / cover screen. */
fun r3Columns(contentWidthDp: Float, wide: Boolean): Int = when {
    !wide -> 1
    contentWidthDp >= R3.COLS_3.value -> 3
    contentWidthDp >= R3.COLS_2.value -> 2
    else -> 1
}

/** The size of [c] in [ctx] (Custom.sizeFor wins). */
fun r3Size(ctx: SectionContext, c: CardSpec): CardSize = (c as? CardSpec.Custom)?.sizeFor?.invoke(ctx) ?: c.size

/** Rows of (card, span) for [cols] columns: M = 2 (capped at cols), S = 1; a row closes when the next would not fit. */
internal fun r3Rows(cards: List<Pair<CardSpec, Int>>, cols: Int): List<List<Pair<CardSpec, Int>>> {
    val rows = ArrayList<List<Pair<CardSpec, Int>>>()
    var cur = ArrayList<Pair<CardSpec, Int>>(); var used = 0
    for ((c, span0) in cards) {
        val span = span0.coerceAtMost(cols)
        if (used + span > cols && cur.isNotEmpty()) { rows += cur; cur = ArrayList(); used = 0 }
        cur += c to span; used += span
        if (used == cols) { rows += cur; cur = ArrayList(); used = 0 }
    }
    if (cur.isNotEmpty()) rows += cur
    return rows
}

@Composable
fun R3CardHost(ctx: SectionContext, cards: List<CardSpec>, modifier: Modifier = Modifier) {
    val shown = cards.filter { c ->
        when (c) { is CardSpec.Metric -> ctx.card(c.id) != null; is CardSpec.Custom -> c.shown?.invoke(ctx) ?: true }
    }
    val w = minOf(ctx.contentWidth.value, R3.CONTENT_MAX.value)
    val cols = r3Columns(w, ctx.wide)
    val gap = if (ctx.wide) R3.GAP else R3.GAP_COVER
    val colW = (w - (cols - 1) * gap.value) / cols
    val rows = r3Rows(shown.map { it to if (r3Size(ctx, it) == CardSize.M) 2 else 1 }, cols)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().then(if (ctx.wide) Modifier.height(R3.CARD_H) else Modifier),
                horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { (spec, span) ->
                    val cw = if (cols == 1) Modifier.fillMaxWidth() else Modifier.width(androidx.compose.ui.unit.Dp(span * colW + (span - 1) * gap.value))
                    val hm = when {
                        ctx.wide -> Modifier.fillMaxHeight()
                        r3Size(ctx, spec) == CardSize.S -> Modifier.heightIn(min = R3.CARD_H_COVER_S)
                        else -> Modifier
                    }
                    // cover S: the card itself is at least 256 high (propagate the min height to the frame)
                    Box(cw.then(hm), propagateMinConstraints = !ctx.wide && r3Size(ctx, spec) == CardSize.S) { R3CardSlot(ctx, spec) }
                }
            }
        }
    }
}

@Composable
private fun R3CardSlot(ctx: SectionContext, spec: CardSpec) {
    when (spec) {
        is CardSpec.Custom -> spec.content(ctx)
        is CardSpec.Metric -> {
            val data = ctx.card(spec.id) ?: return
            R3CardFrame(
                title = spec.title, glyph = spec.glyph, color = spec.color(LocalHB.current), info = spec.info,
                onClick = { (spec.onClick ?: { c: SectionContext -> c.openMetric(spec.id) }).invoke(ctx) },
            ) {
                spec.headline(ctx, data)
                spec.graph(ctx, data)
            }
        }
    }
}
