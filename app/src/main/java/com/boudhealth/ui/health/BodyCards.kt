package com.boudhealth.ui.health

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.data.Extreme
import com.boudhealth.data.SectionPeriod
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.GlyphIcon
import com.boudhealth.ui.components.plainClickable
import com.boudhealth.ui.nav.Route
import com.boudhealth.ui.section.CardSize
import com.boudhealth.ui.section.CardSpec
import com.boudhealth.ui.section.GhostKeyModel
import com.boudhealth.ui.section.GhostKind
import com.boudhealth.ui.section.InfoSheet
import com.boudhealth.ui.section.InfoText
import com.boudhealth.ui.section.R3CardFrame
import com.boudhealth.ui.section.R3Texts
import com.boudhealth.ui.section.SectionContext
import com.boudhealth.ui.section.ValueBlock
import com.boudhealth.ui.section.ValueSize
import com.boudhealth.ui.section.rememberScrub
import com.boudhealth.ui.theme.LocalHB
import java.time.LocalDate

// =====================================================================================================================
// Body cards (IMPL_PLAN §3B "Cards", mock body_r3.value_card / measurements_card / bmi_scale):
// inner rows [Body fat percentage S][Lean mass S][Waist circumference S] · [Measurements M][BMI S]; cover same order.
// Value block (value, date or "Last measured {d Mon}", pill with the hero's basis, ghost key) + the Body line chart on
// the card's axis (day mode = 7 days ending on the day; waist on 1D/1W/1M = the last 8 tape readings).
// Waist: no change on 1D/1W (tape error > weekly change), "Last measured {d Mon}" instead.
// =====================================================================================================================

internal fun bodyCards(): List<CardSpec> = listOf(
    CardSpec.Custom(BodyIds.FAT_PCT) { ctx -> BodyValueCard(ctx, BodyIds.FAT_PCT) },
    CardSpec.Custom(BodyIds.LEAN) { ctx -> BodyValueCard(ctx, BodyIds.LEAN) },
    CardSpec.Custom(BodyIds.WAIST) { ctx -> BodyValueCard(ctx, BodyIds.WAIST) },
    CardSpec.Custom("measurements", size = CardSize.M) { ctx -> MeasurementsCard(ctx) },
    CardSpec.Custom(BodyIds.BMI) { ctx -> BodyValueCard(ctx, BodyIds.BMI) },
)

private fun cardTitle(id: String): String = when (id) {
    BodyIds.FAT_PCT -> R3Texts.BODY_FAT; BodyIds.LEAN -> R3Texts.LEAN_MASS; BodyIds.WAIST -> R3Texts.WAIST; else -> R3Texts.BMI
}

/** Body fat percentage · Lean mass · Waist circumference · BMI. */
@Composable
private fun BodyValueCard(ctx: SectionContext, id: String) {
    val hb = LocalHB.current
    val m = bodyModel(ctx) ?: return
    val s = m.scope
    val cover = !ctx.wide
    val col = bodyColor(id)
    val series = m.series.of(id)
    val c = m.changes.getValue(id)
    val scrub = rememberScrub(ctx.scope to id)
    val ax = remember(m, id) { BodyAxis.card(s, id, m.series, m.series.firstDay) }
    val pts = remember(m, id) { ax.points(series) }
    val ghost = remember(m, id) { if (id == BodyIds.WAIST) null else ax.ghost(series) }
    val buckets = remember(m, id) { scrubBuckets(ax, series) }
    val labels = remember(m, id) { axisLabels(ax) }
    // the value mirrors the scrubbed reading (the pill fades out)
    val sd = scrub.dayOrNull()?.takeIf { series[it] != null }
    val cur: Extreme? = sd?.let { Extreme(series[it]!!, it) } ?: c.current
    val carried = sd == null && c.carried
    val waist = id == BodyIds.WAIST
    val waistNoChange = waist && (s.dayMode || s.period is SectionPeriod.Week)
    val pill = if (waistNoChange) null else bodyPill(c, id, s)
    var info by remember { mutableStateOf(false) }
    val whtr = if (waist) cur?.let { v -> m.heightM?.let { BodyR3Math.whtr(v.value, it) } } else null
    val context: String? = when {
        cur == null -> null
        waist -> if (whtr != null) R3Texts.WHTR else null
        carried -> R3Texts.lastMeasured(cur.day, s.end)
        else -> R3Texts.wkdDMon(cur.day)
    }
    val ghostKey = bodyGhostWord(s)?.takeIf { ghost != null }?.let { GhostKeyModel(it, GhostKind.LINE) }
    val target = when (id) { BodyIds.FAT_PCT -> m.targets.fatPct; BodyIds.WAIST -> m.targets.waist; else -> null }
    val spec = BodyLineSpec(
        axis = ax, labels = labels, pts = pts, color = col, ghost = ghost, band = ax.band(series),
        target = target, targetTag = target?.let { R3Texts.target(it, hm(id).unit) },
        tags = id != BodyIds.BMI, lineAlpha = if (ax.kind == BodyAxisKind.YEAR) 0.55f else 1f,
        dotR = if (ax.kind == BodyAxisKind.WAIST8) 3.6f else null, ticksN = if (id == BodyIds.BMI) 2 else 3,
        maxGap = if (waist) 22 else null,
    )
    val extra: (@Composable () -> Unit)? = if (waist && whtr != null) { { WhtrValue(whtr) { info = true } } } else null
    R3CardFrame(cardTitle(id), Glyph.forMetric(id), col, onClick = { ctx.openMetric(id) }) {
        ValueBlock(
            value = cur?.let { R3Texts.dec(it.value, 1) } ?: R3Texts.DASH, unit = hm(id).unit.ifEmpty { null }, context = context,
            pill = pill, size = if (cover) ValueSize.COVER else ValueSize.S, muted = carried,
            contextExtra = extra,
            trailingKey = ghostKey, pillAlpha = scrub.pillAlpha,
        )
        // waist on 1D / 1W (and before the month's first tape reading): "Last measured {d Mon}" on the pill row
        if (pill == null && waist && cur != null) PillRowText(R3Texts.lastMeasured(cur.day, s.end))
        if (id == BodyIds.BMI) {
            Spacer(Modifier.height(2.dp))
            BmiScaleBar(cur?.value, muted = carried)
        }
        val chartMod = if (cover) Modifier.fillMaxWidth().height(if (id == BodyIds.BMI) 70.dp else 96.dp) else Modifier.fillMaxWidth().weight(1f)
        BodyLineChart(spec, buckets, scrub, onTap = { d -> ctx.drill(d) }, modifier = chartMod.padding(top = 4.dp), a11y = cardTitle(id))
    }
    if (info) InfoSheet(InfoText(R3Texts.WHTR, HealthTexts.WHTR_INFO)) { info = false }
}

/** "0.49 (i)" after the WHtR context word. */
@Composable
private fun WhtrValue(v: Double, onInfo: () -> Unit) {
    val hb = LocalHB.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(5.dp))
        Text(R3Texts.dec(v, 2), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = hb.colors.text, style = TextStyle(fontFeatureSettings = "tnum"))
        Box(Modifier.plainClickable(onClick = onInfo).padding(horizontal = 4.dp, vertical = 2.dp).semantics { contentDescription = R3Texts.WHTR }) {
            GlyphIcon(Glyph.Info, size = 14.dp, color = hb.colors.muted)
        }
    }
}

/** Muted text centred on the pill row (ValueBlock without a pill ends 20 dp above where the pill block would). */
@Composable
private fun ColumnScope.PillRowText(text: String) {
    val hb = LocalHB.current
    Box(Modifier.fillMaxWidth().height(20.dp)) {
        Text(text, fontSize = 12.sp, color = hb.colors.muted, maxLines = 1, modifier = Modifier.layout { mm, cc ->
            val p = mm.measure(cc.copy(minHeight = 0))
            layout(p.width, p.height) { p.place(0, 2.dp.roundToPx() - p.height / 2) }
        })
    }
}

// ---------------------------------------------------------------------------------------------------------- Measurements

/** Measurements (M; cover S auto): "Measure" (cover: + button) and "All {n} ›", the table newest first, tap a row = select it. */
@Composable
private fun MeasurementsCard(ctx: SectionContext) {
    val hb = LocalHB.current
    val m = bodyModel(ctx) ?: return
    val cover = !ctx.wide
    val rows = m.table.take(if (cover) 3 else 5)
    val cols = listOf(BodyIds.WEIGHT, BodyIds.FAT_PCT, BodyIds.LEAN, BodyIds.WAIST).let { if (cover) listOf(it[0], it[1], it[3]) else it }
    val weights = if (cover) listOf(1f, 1f, 1f) else listOf(0.9f, 1.3f, 1.0f, 0.8f)
    val names = mapOf(BodyIds.WEIGHT to R3Texts.WEIGHT, BodyIds.FAT_PCT to R3Texts.BODY_FAT, BodyIds.LEAN to R3Texts.LEAN_MASS, BodyIds.WAIST to R3Texts.WAIST_SHORT)
    val dateW = if (cover) 84.dp else 98.dp          // mock 104 / 118 from the card edge (content starts at 20)
    val pitch = if (cover) 38.dp else 36.dp
    val fs = if (cover) 13f else 13.5f
    val hiColor = hb.colors.tileHi
    // values right-aligned in a box as wide as the column's widest value ("000.0" weight / waist, "00.0" fat % / lean): decimals line up
    val tm = androidx.compose.ui.text.rememberTextMeasurer()
    val dens = androidx.compose.ui.platform.LocalDensity.current
    val valW = cols.associateWith { id ->
        val probe = if (id == BodyIds.WEIGHT || id == BodyIds.WAIST) "000.0" else "00.0"
        with(dens) { tm.measure(probe, TextStyle(fontSize = fs.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")).size.width.toDp() }
    }
    R3CardFrame(
        R3Texts.MEASUREMENTS, Glyph.List, hb.colors.muted, onClick = null,
        headerTrailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MeasureButton(iconOnly = cover) { ctx.nav.push(Route.Tape) }
                Row(Modifier.height(28.dp).background(hb.colors.tile, RoundedCornerShape(14.dp)).plainClickable { ctx.openMetric(BodyIds.WEIGHT) }
                    .padding(start = 13.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(R3Texts.all(m.series.weighIns), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.text, maxLines = 1)
                    Spacer(Modifier.width(3.dp))
                    GlyphIcon(Glyph.ChevR, size = 13.dp, color = hb.colors.muted)
                }
            }
        },
    ) {
        // header: glyph 15 in the metric colour + name 11/600 muted (cover: glyph only), divider
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(dateW))
            cols.forEachIndexed { j, id ->
                Row(Modifier.weight(weights[j]), verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(Glyph.forMetric(id), size = 15.dp, color = hb.legibleGlyph(bodyColor(id)))
                    if (!cover) {
                        Spacer(Modifier.width(7.dp))
                        Text(names.getValue(id), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(hb.colors.grid))
        Spacer(Modifier.height(4.dp))
        rows.forEach { r ->
            val dateText = r.day?.let { R3Texts.wkdDMon(it) } ?: r.month?.let { R3Texts.rowMonth(it) } ?: r.year?.let { R3Texts.rowYear(it) } ?: ""
            val target: LocalDate? = r.day ?: r.month?.atEndOfMonth()?.let { minOf(it, ctx.today) } ?: r.year?.let { minOf(LocalDate.of(it, 12, 31), ctx.today) }
            val a11y = listOf(dateText) + cols.map { id -> r.cells[id]?.let { "${names.getValue(id)} ${R3Texts.dec(it.value, 1)}" } ?: "${names.getValue(id)} ${R3Texts.DASH}" }
            Row(
                Modifier.fillMaxWidth().height(pitch)
                    // selected-day row: tileHi r 12, inset 10 from the card edge (10 dp outside the content column)
                    .then(if (r.highlighted) Modifier.drawBehind {
                        drawRoundRect(hiColor, Offset(-10.dp.toPx(), 2.dp.toPx()), Size(size.width + 20.dp.toPx(), size.height - 4.dp.toPx()), CornerRadius(12.dp.toPx()))
                    } else Modifier)
                    .plainClickable { target?.let { ctx.drill(it) } }
                    .semantics(mergeDescendants = true) { contentDescription = a11y.joinToString(", ") },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(dateText, fontSize = fs.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.text, maxLines = 1, modifier = Modifier.width(dateW))
                cols.forEachIndexed { j, id ->
                    val cell = r.cells[id]
                    Row(Modifier.weight(weights[j]), verticalAlignment = Alignment.CenterVertically) {
                        if (cell == null) Text(R3Texts.DASH, fontSize = fs.sp, fontWeight = FontWeight.Bold, color = hb.colors.muted.copy(alpha = 0.6f))
                        else {
                            Text(R3Texts.dec(cell.value, 1), fontSize = fs.sp, fontWeight = FontWeight.Bold, color = hb.colors.text, maxLines = 1,
                                style = TextStyle(fontFeatureSettings = "tnum", textAlign = androidx.compose.ui.text.style.TextAlign.End), modifier = Modifier.width(valW.getValue(id)))
                            if (!cover && cell.change != null) {
                                Spacer(Modifier.width(6.dp))
                                Text(R3Texts.signed(cell.change, 1), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.muted, maxLines = 1,
                                    style = TextStyle(fontFeatureSettings = "tnum"))
                            }
                        }
                    }
                }
            }
        }
        if (cover) Spacer(Modifier.height(4.dp))
    }
}

/** "+ Measure" (accent container pill 28 high; cover: a 28 dp circle with +): opens the tape. */
@Composable
private fun MeasureButton(iconOnly: Boolean, onClick: () -> Unit) {
    val hb = LocalHB.current
    if (iconOnly) {
        Box(Modifier.size(28.dp).background(hb.colors.accentCont, CircleShape).plainClickable(onClick = onClick).semantics { contentDescription = R3Texts.MEASURE },
            contentAlignment = Alignment.Center) { GlyphIcon(Glyph.Plus, size = 15.dp, color = hb.colors.onAccentCont) }
        return
    }
    Row(Modifier.height(28.dp).background(hb.colors.accentCont, RoundedCornerShape(14.dp)).plainClickable(onClick = onClick).padding(start = 10.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        GlyphIcon(Glyph.Plus, size = 14.dp, color = hb.colors.onAccentCont)
        Spacer(Modifier.width(6.dp))
        Text(R3Texts.MEASURE, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.onAccentCont, maxLines = 1)
    }
}
