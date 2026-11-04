package com.boudhealth.ui.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.data.ReadingChange
import com.boudhealth.data.SectionPeriod
import com.boudhealth.data.SeriesBucket
import com.boudhealth.data.TrendDir
import com.boudhealth.ui.components.niceTicks
import com.boudhealth.ui.section.DeltaPill
import com.boudhealth.ui.section.DeltaPillModel
import com.boudhealth.ui.section.GhostKey
import com.boudhealth.ui.section.GhostKeyModel
import com.boudhealth.ui.section.GhostKind
import com.boudhealth.ui.section.GhostStyle
import com.boudhealth.ui.section.R3StatusIcon
import com.boudhealth.ui.section.R3Texts
import com.boudhealth.ui.section.ScrubState
import com.boudhealth.ui.section.SectionContext
import com.boudhealth.ui.section.TileIcon
import com.boudhealth.ui.section.drawTri
import com.boudhealth.ui.section.Tone
import com.boudhealth.ui.section.rememberScrub
import com.boudhealth.ui.section.scrubAndTap
import com.boudhealth.ui.theme.Cat
import com.boudhealth.ui.theme.HB
import com.boudhealth.ui.theme.LocalHB
import java.time.LocalDate
import java.time.format.TextStyle as JTextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// =====================================================================================================================
// Body hero (D2-A, IMPL_PLAN §3B; mock out/body/src/body_r3.py hero_left / hero_chart / line_plot / comp_plot):
// left 340 = "Weight" + 56/700 value + date + change pill + to-target pill + 2×2 (Body fat percentage · Lean mass ·
// Waist · BMI); 1 dp divider; right = weight chart (ghost line, week lows ringed, target, extremes, 1Y chord, All monthly
// band, weekend columns) · weekly ▼/▲ row · composition legend · lean / fat mass bars + fat % line, ONE x-axis, one
// shared scrub cursor (the left value and the 2×2 mirror the scrubbed weigh-in). No rings.
// =====================================================================================================================

internal const val EXTRA_BODY = "body_r3"

internal fun bodyModel(ctx: SectionContext): BodyModel? = ctx.extras[EXTRA_BODY] as? BodyModel

/** Fat mass colour of the mock (kit theme K["fat_mass"]); not in Cat. */
internal val FAT_MASS_COLOR = com.boudhealth.ui.section.R3Colors.fatMass


@Composable
internal fun bodyColor(id: String): Color {
    val hb = LocalHB.current
    return when (id) {
        BodyIds.WEIGHT -> hb.colors.text
        BodyIds.FAT_PCT -> Cat.fat
        BodyIds.LEAN -> Cat.lean
        BodyIds.FAT_MASS -> FAT_MASS_COLOR
        BodyIds.WAIST -> Cat.waist
        BodyIds.BMI -> Cat.bmi
        else -> hb.colors.text
    }
}

/** Unit word of a Body metric (HMETRICS; fat mass = kg). */
internal fun bodyUnit(id: String): String = if (id == BodyIds.FAT_MASS) hm(BodyIds.LEAN).unit else hm(id).unit

/** Pill unit: "%" and BMI pills carry no unit ("▼ 0.4 vs 1 Oct"). */
internal fun pillUnit(id: String): String = if (id == BodyIds.FAT_PCT || id == BodyIds.BMI) "" else bodyUnit(id)

/** Signed tile value "+0.3 kg" / "−0.2" (fat % and BMI without unit); 0 → "0.0 kg". */
internal fun bodySigned(d: Double, id: String): String = R3Texts.signed(d, 1) + pillUnit(id).let { if (it.isEmpty()) "" else " $it" }

/** The change pill with the period's basis: "▲ 0.3 kg vs 30 Sep", All "▼ 4.3 kg since Nov 2023", "Same as …". */
internal fun bodyPill(c: ReadingChange?, id: String, s: BodyScope): DeltaPillModel? {
    c ?: return null
    if (c.diff == null || (c.carried && !s.dayMode)) return null
    val ref = c.reference ?: return null
    val cur = c.current ?: return null
    if (s.period is SectionPeriod.All && !s.dayMode) {
        val tone = R3MathTone.of(c)
        return DeltaPillModel(c.dir, R3Texts.pillSince(R3Texts.mag(c.diff!!, 1), pillUnit(id), ref.day), tone)
    }
    return R3Texts.readingPill(c, pillUnit(id), 1, cur.day)
}

internal object R3MathTone {
    fun of(c: ReadingChange): Tone = when (c.good) { true -> Tone.GOOD; false -> Tone.BAD; null -> Tone.NEUTRAL }
    fun icon(dir: TrendDir?): TileIcon = when (dir) { TrendDir.UP -> TileIcon.UP; TrendDir.DOWN -> TileIcon.DOWN; else -> TileIcon.SAME }
}

/** Ghost key word ("Last week" / "{Month}" / "{yyyy}"); null on day mode / All. */
internal fun bodyGhostWord(s: BodyScope): String? =
    if (s.period is SectionPeriod.Day || s.period is SectionPeriod.All) null else R3Texts.ghostWord(s.period, s.today)

// ---------------------------------------------------------------------------------------------------------- the hero

@Composable
internal fun BodyHero(ctx: SectionContext) {
    val hb = LocalHB.current
    val m = bodyModel(ctx) ?: return
    val scrub = rememberScrub(ctx.scope)
    if (m.series.weight.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(if (ctx.wide) 380.dp else 120.dp), contentAlignment = Alignment.Center) {
            Text(R3Texts.NO_DATA_PERIOD, fontSize = 13.sp, color = hb.colors.muted)
        }
        return
    }
    if (ctx.wide) {
        Row(Modifier.fillMaxSize()) {
            HeroLeft(m, scrub, cover = false, modifier = Modifier.width(296.dp).fillMaxSize())
            // divider at hero x 340 (content x 318), full content height (hero top 22 … bottom 22)
            Spacer(Modifier.width(22.dp))
            Box(Modifier.width(1.dp).fillMaxSize().background(hb.colors.grid.copy(alpha = 0.8f)))
            Spacer(Modifier.width(21.dp))
            HeroCharts(ctx, m, scrub, cover = false, modifier = Modifier.weight(1f).fillMaxSize())
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            HeroLeft(m, scrub, cover = true, modifier = Modifier.fillMaxWidth())
            HeroCharts(ctx, m, scrub, cover = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** The scrubbed day (shared cursor), or null. */
internal fun ScrubState.dayOrNull(): LocalDate? = if (active) day else null

@Composable
private fun HeroLeft(m: BodyModel, scrub: ScrubState, cover: Boolean, modifier: Modifier) {
    val hb = LocalHB.current
    val s = m.scope
    val w = m.changes.getValue(BodyIds.WEIGHT)
    val sd = scrub.dayOrNull()?.takeIf { m.series.weight[it] != null }
    val cur = sd?.let { com.boudhealth.data.Extreme(m.series.weight[it]!!, it) } ?: w.current
    val carried = sd == null && w.carried
    val context = when {
        cur == null -> null
        carried -> R3Texts.lastMeasured(cur.day, s.end)
        else -> R3Texts.wkdDMon(cur.day)
    }
    val pill = bodyPill(w, BodyIds.WEIGHT, s)
    val target = m.toTarget?.let { g ->
        if (g > 0) DeltaPillModel(null, R3Texts.kgToTarget(g), Tone.NEUTRAL) else DeltaPillModel(null, R3Texts.TARGET_REACHED, Tone.GOOD)
    }
    val wColor = hb.colors.text
    val vfs = if (cover) 40f else 56f
    val valueText = buildAnnotatedString {
        withStyle(SpanStyle(fontSize = vfs.sp, fontWeight = FontWeight.Bold, color = if (carried) hb.colors.muted else hb.colors.text)) { append(cur?.let { R3Texts.dec(it.value, 1) } ?: R3Texts.DASH) }
        withStyle(SpanStyle(fontSize = (if (cover) 16f else 20f).sp, color = hb.colors.muted)) { append(" " + hm(BodyIds.WEIGHT).unit) }
    }
    val a11y = listOfNotNull(R3Texts.WEIGHT, valueText.text, context, pill?.text, target?.text).joinToString(", ")
    // mock: label centre 12 (cover 16) · value line 86 (60) · context +28 (+22) · pills centre +58 (+46)
    val labelY = if (cover) 16.dp else 12.dp
    val valueY = if (cover) 60.dp else 86.dp
    val ctxY = valueY + if (cover) 22.dp else 28.dp
    val pillY = valueY + if (cover) 46.dp else 58.dp
    Layout(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = a11y },
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(wColor, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(R3Texts.WEIGHT, fontSize = (if (cover) 12.5f else 13f).sp, fontWeight = FontWeight.SemiBold, color = wColor, maxLines = 1)
            }
            Text(valueText, maxLines = 1, style = TextStyle(fontFeatureSettings = "tnum"))
            Text(context ?: "", fontSize = (if (cover) 12f else 12.5f).sp, color = hb.colors.muted, maxLines = 1)
            PillFlow(listOfNotNull(pill?.let { it to scrub.pillAlpha }, target?.let { it to 1f }))
            if (!cover) Mini2x2(m, sd) else Box(Modifier)
        },
    ) { ms, c ->
        val W = c.maxWidth
        val loose = c.copy(minWidth = 0, minHeight = 0)
        val pl = ms[0].measure(loose); val pv = ms[1].measure(loose); val pc = ms[2].measure(loose.copy(maxWidth = W))
        val pp = ms[3].measure(loose.copy(maxWidth = W))
        fun bl(p: androidx.compose.ui.layout.Placeable) = p[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: p.height
        val pillTop = pillY.roundToPx() - 12.dp.roundToPx()
        val pillsBottom = pillTop + pp.height
        val H = if (c.hasBoundedHeight) c.maxHeight else pillsBottom + 17.dp.roundToPx()
        // 2×2 bottom-aligned; tiles 96 high, shrinking when the pills wrap (mock th = min(96, …))
        val g = 8.dp.roundToPx()
        val th = min(96.dp.roundToPx(), (H - (pillsBottom + 22.dp.roundToPx()) - g) / 2).coerceAtLeast(0)
        val pg = ms[4].measure(c.copy(minWidth = W, maxWidth = W, minHeight = 2 * th + g, maxHeight = 2 * th + g))
        layout(W, H) {
            pl.place(0, labelY.roundToPx() - pl.height / 2)
            pv.place(-1, valueY.roundToPx() - bl(pv))
            if (context != null) pc.place(0, ctxY.roundToPx() - bl(pc))
            pp.place(0, pillTop)
            if (!cover) pg.place(0, H - pg.height)
        }
    }
}

/** Pills in a row, wrapping to a second row (pitch 32) when they do not fit (mock hero_left). */
@Composable
private fun PillFlow(pills: List<Pair<DeltaPillModel, Float>>) {
    Layout(content = { pills.forEach { (p, a) -> Box(Modifier.alpha(a)) { DeltaPill(p) } } }) { ms, c ->
        val gap = 8.dp.roundToPx(); val pitch = 32.dp.roundToPx()
        val ps = ms.map { it.measure(c.copy(minWidth = 0, minHeight = 0)) }
        var x = 0; var y = 0; var rowH = 0
        val pos = ps.map { p ->
            if (x > 0 && x + p.width > c.maxWidth) { x = 0; y += pitch }
            val o = x to y; x += p.width + gap; rowH = max(rowH, p.height); o
        }
        layout(c.maxWidth, (if (ps.isEmpty()) 0 else y + rowH)) { ps.forEachIndexed { i, p -> p.place(pos[i].first, pos[i].second) } }
    }
}

/** 2×2 tiles: Body fat percentage · Lean mass · Waist · BMI (value 20/700 + unit, ▲●▼ vs the same basis; waist on 1D/1W: its date). */
@Composable
private fun Mini2x2(m: BodyModel, scrubDay: LocalDate?) {
    val s = m.scope
    val specs = listOf(BodyIds.FAT_PCT to R3Texts.BODY_FAT, BodyIds.LEAN to R3Texts.LEAN_MASS, BodyIds.WAIST to R3Texts.WAIST_SHORT, BodyIds.BMI to R3Texts.BMI)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        specs.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (id, label) ->
                    val c = m.changes.getValue(id)
                    val scr = scrubDay?.let { BodyR3Math.valueOn(m.series, id, it) }
                    val cur = scr ?: c.current
                    val waistDate = id == BodyIds.WAIST && cur != null && (c.carried || s.dayMode || s.period is SectionPeriod.Week || (scr != null && scr.day != scrubDay))
                    val icon = if (scrubDay != null || waistDate || c.diff == null || (c.carried && !s.dayMode)) null else R3MathTone.icon(c.dir) to R3MathTone.of(c)
                    MiniTile(label, cur?.let { R3Texts.dec(it.value, 1) } ?: R3Texts.DASH, hm(id).unit.ifEmpty { null },
                        icon, if (waistDate) R3Texts.dMonRel(cur!!.day, s.end) else null, muted = waistDate || (c.carried && scrubDay == null),
                        modifier = Modifier.weight(1f).fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun MiniTile(label: String, value: String, unit: String?, icon: Pair<TileIcon, Tone>?, date: String?, muted: Boolean, modifier: Modifier) {
    val hb = LocalHB.current
    Layout(
        modifier = modifier.background(hb.colors.tile, RoundedCornerShape(16.dp)).semantics(mergeDescendants = true) {
            contentDescription = listOfNotNull(label, value, unit, icon?.let { R3Texts.a11yIcon(it.first) }, date).joinToString(", ")
        },
        content = {
            Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(buildAnnotatedString {
                withStyle(SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (muted) hb.colors.muted else hb.colors.text)) { append(value) }
                if (unit != null) withStyle(SpanStyle(fontSize = 12.5.sp, color = hb.colors.muted)) { append(" $unit") }
            }, maxLines = 1, style = TextStyle(fontFeatureSettings = "tnum"))
            Box { if (date != null) Text(date, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.muted, maxLines = 1) else if (icon != null) R3StatusIcon(icon.first, icon.second, 18.dp) }
        },
    ) { ms, c ->
        val W = c.maxWidth; val H = c.maxHeight
        val loose = c.copy(minWidth = 0, minHeight = 0)
        val pl = ms[0].measure(loose.copy(maxWidth = (W - 26.dp.roundToPx()).coerceAtLeast(0)))
        val pv = ms[1].measure(loose); val pe = ms[2].measure(loose)
        fun bl(p: androidx.compose.ui.layout.Placeable) = p[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: p.height
        layout(W, H) {
            pl.place(14.dp.roundToPx(), 22.dp.roundToPx() - bl(pl))
            pv.place(14.dp.roundToPx(), H - 16.dp.roundToPx() - bl(pv))
            if (date != null) pe.place(W - 12.dp.roundToPx() - pe.width, H - 14.dp.roundToPx() - bl(pe))
            else pe.place(W - 21.dp.roundToPx() - pe.width / 2, H - 23.dp.roundToPx() - pe.height / 2)
        }
    }
}

// ---------------------------------------------------------------------------------------------------------- right zone

/** x labels of an axis: weekday / day of month / month letter / year / "10 Sep" (tape). */
internal fun axisLabels(ax: BodyAxis): List<String?> {
    val set = ax.labelIdx.toSet()
    return ax.days.mapIndexed { i, d ->
        if (i !in set) null else when (ax.kind) {
            BodyAxisKind.WINDOW7, BodyAxisKind.WEEK -> d.dayOfWeek.getDisplayName(JTextStyle.NARROW, Locale.ENGLISH)
            BodyAxisKind.MONTH -> "${d.dayOfMonth}"
            BodyAxisKind.YEAR -> d.month.getDisplayName(JTextStyle.NARROW, Locale.ENGLISH)
            BodyAxisKind.ALL -> "${d.year}"
            BodyAxisKind.WAIST8 -> R3Texts.dMon(d)
        }
    }
}

/** Scrub / tap buckets of an axis (one per bucket; the value = the reading drawn there; All: the month's last reading day). */
internal fun scrubBuckets(ax: BodyAxis, s: Map<LocalDate, Double>): List<SeriesBucket> {
    val pts = ax.points(s).toMap(); val days = ax.pointDays(s)
    return ax.days.indices.map { i ->
        val d = days[i] ?: ax.days[i]
        SeriesBucket(d.atStartOfDay(), d.plusDays(1).atStartOfDay(), pts[i], future = !ax.monthly && ax.days[i].isAfter(ax.today))
    }
}

internal val BODY_AXIS_H = 18.dp
internal val BODY_GUTTER = 30.dp

@Composable
private fun HeroCharts(ctx: SectionContext, m: BodyModel, scrub: ScrubState, cover: Boolean, modifier: Modifier) {
    val hb = LocalHB.current
    val measurer = rememberTextMeasurer()
    val ax = m.heroAxis
    val s = m.scope
    val wc = hb.colors.text
    val pts = remember(m) { ax.points(m.series.weight) }
    val ghost = remember(m) { ax.ghost(m.series.weight) }
    val band = remember(m) { ax.band(m.series.weight) }
    val labels = remember(m) { axisLabels(ax).toMutableList().also { l ->
        // cover: a selected day next to today hides today's label (mock `narrow`)
        if (cover && ax.selI != null && ax.todayI != null && abs(ax.selI - ax.todayI) <= 3) l[ax.todayI] = null
    } }
    val todayI = if (cover && ax.selI != null && ax.todayI != null && abs(ax.selI - ax.todayI) <= 3) null else ax.todayI
    val buckets = remember(m) { scrubBuckets(ax, m.series.weight) }
    val ghostWord = bodyGhostWord(s)?.takeIf { ghost != null }
    val yearStyle = ax.kind == BodyAxisKind.YEAR
    val chord = m.chord?.let { c ->
        val i0 = ax.idx(c.first.day); val i1 = ax.idx(c.last.day)
        if (i0 != null && i1 != null && i1 > i0) Triple(i0 to c.first.value, i1 to c.last.value, R3Texts.perMonth(c.perMonth)) else null
    }
    val spec = BodyLineSpec(
        axis = ax, labels = labels, todayI = todayI, pts = pts, color = wc, ghost = ghost, band = band,
        target = m.targets.weight, targetTag = m.targets.weight?.let { R3Texts.target(it, hm(BodyIds.WEIGHT).unit) },
        rings = m.ringed, chord = chord, lineAlpha = if (yearStyle) 0.6f else 1f, strokeW = if (yearStyle) 1f else 2f,
        showAxis = false, keyRow = ghostWord != null,
    )
    val scrubI = scrub.dayOrNull()?.let { ax.idx(it) }
    // the weekly ▼/▲ row is reserved on 1M / 1Y (period axis) even before the first complete week (mock has_ticks)
    val hasTicks = (s.period is SectionPeriod.Month || s.period is SectionPeriod.Year) && ax.hl == null && !ax.monthly
    val chartH = if (cover) 150.dp else 240.dp
    val compH = if (cover) 54.dp else 70.dp
    val rows = m.comp
    val shownRow = (scrub.dayOrNull()?.let { d -> rows.firstOrNull { it.day == d } })
        ?: ax.hl?.let { h -> rows.firstOrNull { it.i == h } } ?: ax.selI?.let { si -> rows.firstOrNull { it.i == si } } ?: rows.lastOrNull()
    val gesture = Modifier.scrubAndTap(buckets, scrub, { b -> ctx.drill(b.day) }, BODY_GUTTER, true)
    Column(modifier.then(gesture)) {
        Box(Modifier.fillMaxWidth().height(chartH)) {
            Canvas(Modifier.fillMaxSize()) { drawBodyLine(spec, measurer, hb, scrubI) }
            if (ghostWord != null) Box(Modifier.align(Alignment.TopEnd).padding(end = BODY_GUTTER, top = 2.dp)) { GhostKey(GhostKeyModel(ghostWord, GhostKind.LINE)) }
        }
        if (hasTicks) {
            Canvas(Modifier.fillMaxWidth().height(22.dp)) {
                val plotW = size.width - BODY_GUTTER.toPx(); val slot = plotW / ax.n
                val cy = 12.dp.toPx()
                m.weekMarks.forEach { wm ->
                    val cx = slot * wm.i + slot / 2f
                    when (wm.dir) {
                        TrendDir.DOWN -> drawTri(cx, cy, 7.dp.toPx(), TrendDir.DOWN, hb.colors.good)
                        TrendDir.UP -> drawTri(cx, cy, 7.dp.toPx(), TrendDir.UP, hb.colors.bad)
                        else -> drawCircle(hb.colors.muted.copy(alpha = 0.55f), 2.6.dp.toPx(), Offset(cx, cy))
                    }
                }
            }
        } else Spacer(Modifier.height(8.dp))
        Spacer(Modifier.height(if (cover) 6.dp else 8.dp))
        if (shownRow != null) CompLegend(shownRow, cover)
        Spacer(Modifier.height(2.dp))
        Canvas(Modifier.fillMaxWidth().height(compH + BODY_AXIS_H)) {
            val plotW = size.width - BODY_GUTTER.toPx()
            val slot = plotW / max(1, ax.n)
            val sel = scrubI ?: ax.selI
            drawComp(rows, slot, plotW, compH.toPx(), sel = if (ax.hl == null && !yearStyle) sel else null, hl = ax.hl, wide = yearStyle, hb = hb)
            drawAxisLabels(labels, slot, compH.toPx(), todayI, ax.selI, ax.futureFrom, measurer, hb)
        }
    }
}

/** "■ Lean mass 66.8 kg  ■ Fat mass 12.7 kg  — Body fat percentage 16.0 %", right-aligned (cover: 2 lines). */
@Composable
private fun CompLegend(r: CompRow, cover: Boolean) {
    val hb = LocalHB.current
    val kg = hm(BodyIds.LEAN).unit
    val items = listOfNotNull(
        Triple(true, Cat.lean, R3Texts.LEAN_MASS to R3Texts.withUnit(r.lean, kg, 1)),
        Triple(true, FAT_MASS_COLOR, R3Texts.FAT_MASS to R3Texts.withUnit(r.fatKg, kg, 1)),
        r.fatPct?.let { Triple(false, Cat.fat, R3Texts.BODY_FAT to R3Texts.withUnit(it, "%", 1)) },
    )
    @Composable fun item(sq: Boolean, c: Color, nv: Pair<String, String>) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (sq) Box(Modifier.size(8.dp).background(c, RoundedCornerShape(2.dp))) else Box(Modifier.width(10.dp).height(2.4.dp).background(c, RoundedCornerShape(1.2.dp)))
            Spacer(Modifier.width(if (sq) 5.dp else 3.dp))
            Text(nv.first, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.muted, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            Text(nv.second, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = hb.colors.text, maxLines = 1, style = TextStyle(fontFeatureSettings = "tnum"))
        }
    }
    val lines = if (cover) listOf(items.take(2), items.drop(2)) else listOf(items)
    Column(Modifier.fillMaxWidth().padding(end = BODY_GUTTER), horizontalAlignment = Alignment.End) {
        lines.filter { it.isNotEmpty() }.forEach { line ->
            Row(Modifier.height(17.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                line.forEach { (sq, c, nv) -> item(sq, c, nv) }
            }
        }
    }
}

/** Composition: lean (bottom) + fat mass (top) per weigh-in on the shared x mapping, fat % line on its own scale above. */
private fun DrawScope.drawComp(rows: List<CompRow>, slot: Float, plotW: Float, h: Float, sel: Int?, hl: Int?, wide: Boolean, hb: HB) {
    if (rows.isEmpty()) return
    val dp = density
    val pctH = 20 * dp
    val barTop = pctH + 6 * dp
    val bh = h - barTop
    val topKg = (rows.maxOf { it.lean + it.fatKg } * 1.02).coerceAtLeast(1.0)
    val bw = if (wide) slot * 20f else max(2.5f * dp, min(slot * 0.56f, 16 * dp))
    drawLine(hb.colors.grid, Offset(0f, h + 0.5f * dp), Offset(plotW, h + 0.5f * dp), 1 * dp)
    rows.forEach { r ->
        val cx = slot * r.i + slot / 2f
        val a = if ((sel != null && r.i != sel) || (hl != null && r.i != hl)) 0.4f else 1f
        val hl_ = (bh * r.lean / topKg).toFloat(); val hf = (bh * r.fatKg / topKg).toFloat()
        val rad = min(bw / 2f, 2.5f * dp)
        drawRoundRect(FAT_MASS_COLOR.copy(alpha = a), Offset(cx - bw / 2, h - hl_ - hf), Size(bw, hf + rad + 1 * dp), CornerRadius(rad))
        drawRect(Cat.lean.copy(alpha = a), Offset(cx - bw / 2, h - hl_), Size(bw, hl_))
    }
    val pv = rows.mapNotNull { r -> r.fatPct?.let { r.i to it } }
    if (pv.isEmpty()) return
    var lo = pv.minOf { it.second }; var hi = pv.maxOf { it.second }
    val span = max(hi - lo, 0.6); val mid = (lo + hi) / 2; lo = mid - span / 2; hi = mid + span / 2
    fun y(v: Double) = 3 * dp + (pctH - 6 * dp) * (1f - ((v - lo) / (hi - lo)).toFloat())
    val q = pv.map { Offset(slot * it.first + slot / 2f, y(it.second)) }
    if (q.size > 1) drawPath(Path().apply { moveTo(q[0].x, q[0].y); q.drop(1).forEach { lineTo(it.x, it.y) } }, Cat.fat, style = Stroke(1.6f * dp, join = StrokeJoin.Round))
    pv.forEachIndexed { k, (i, _) ->
        val big = (sel != null && i == sel) || (hl != null && i == hl)
        drawCircle(Cat.fat, (if (big) 3.4f else if (pv.size < 40) 1.9f else 1.2f) * dp, q[k])
    }
}

/** Axis labels under [base]: muted 10.5/700; today = accent circle, selected = text circle, future faint (charts.axis_labels). */
internal fun DrawScope.drawAxisLabels(labels: List<String?>, slot: Float, base: Float, todayI: Int?, selI: Int?, futureFrom: Int?, measurer: TextMeasurer, hb: HB) {
    val dp = density
    val cy = base + 3 * dp + 10.5f * 0.62f * dp + 1 * dp
    labels.forEachIndexed { i, t ->
        t ?: return@forEachIndexed
        val cx = slot * i + slot / 2f
        val marked = i == selI || i == todayI
        val col = when { i == selI -> hb.colors.card; i == todayI -> hb.colors.onAccent; futureFrom != null && i >= futureFrom -> hb.colors.muted.copy(alpha = 0.5f); else -> hb.colors.muted }
        val tl = measurer.measure(t, TextStyle(fontSize = (if (marked) 10f else 10.5f).sp, fontWeight = FontWeight.Bold, color = col))
        if (marked) drawCircle(if (i == selI) hb.colors.text else hb.colors.accent, max(tl.size.width / 2f + 5 * dp, 9 * dp), Offset(cx, cy))
        val x = if (marked) cx - tl.size.width / 2f else (cx - tl.size.width / 2f).coerceIn(0f, max(0f, size.width - tl.size.width))
        drawText(tl, topLeft = Offset(x, cy - tl.size.height / 2f))
    }
}

// ---------------------------------------------------------------------------------------------------------- line chart

/** One Body line chart (mock `line_plot`): everything the hero and the cards draw. */
internal class BodyLineSpec(
    val axis: BodyAxis,
    val labels: List<String?>,
    val todayI: Int? = axis.todayI,
    val pts: List<Pair<Int, Double>>,
    val color: Color,
    val decimals: Int = 1,
    val ghost: List<Pair<Int, Double>>? = null,
    val band: List<Pair<Double, Double>?>? = null,
    val target: Double? = null,
    val targetTag: String? = null,
    val tags: Boolean = true,
    val rings: Set<Int> = emptySet(),
    val chord: Triple<Pair<Int, Double>, Pair<Int, Double>, String>? = null,
    val lineAlpha: Float = 1f,
    val strokeW: Float = 2f,
    val dotR: Float? = null,
    val ticksN: Int = 3,
    val showAxis: Boolean = true,
    /** Reserve the 22 dp key row above the plot (a previous-period key sits there, never on the data). */
    val keyRow: Boolean = false,
    /** Gap rule override in buckets (waist: 22, a weekly tape joins); null = the axis rule. */
    val maxGap: Int? = null,
)

private fun segments(pts: List<Pair<Int, Double>>, maxGap: Int): List<List<Pair<Int, Double>>> {
    val out = ArrayList<List<Pair<Int, Double>>>(); var cur = ArrayList<Pair<Int, Double>>()
    pts.forEach { p -> if (cur.isNotEmpty() && maxGap != Int.MAX_VALUE && p.first - cur.last().first > maxGap) { out += cur; cur = ArrayList() }; cur += p }
    if (cur.isNotEmpty()) out += cur
    return out
}

internal fun DrawScope.drawBodyLine(sp: BodyLineSpec, measurer: TextMeasurer, hb: HB, scrubI: Int?) {
    val dp = density
    val ax = sp.axis
    if (ax.n == 0) return
    val vals = sp.pts.map { it.second } + sp.ghost.orEmpty().map { it.second } + listOfNotNull(sp.target) + sp.band.orEmpty().filterNotNull().flatMap { listOf(it.first, it.second) }
    if (vals.isEmpty()) { if (sp.showAxis) drawAxisLabels(sp.labels, (size.width - BODY_GUTTER.toPx()) / ax.n, size.height - BODY_AXIS_H.toPx(), sp.todayI, ax.selI, ax.futureFrom, measurer, hb); return }
    val vlo = vals.min(); val vhi = vals.max(); val pad = max((vhi - vlo) * 0.14, 0.3)
    val lo = vlo - pad; val hi = vhi + pad
    val top = (if (sp.keyRow) 22 else 8) * dp
    val base = size.height - if (sp.showAxis) BODY_AXIS_H.toPx() else 0f
    val plotW = size.width - BODY_GUTTER.toPx()
    val slot = plotW / ax.n
    fun X(i: Int) = slot * i + slot / 2f
    fun Y(v: Double) = (top + (base - top) * (1.0 - (v - lo) / (hi - lo))).toFloat()
    val c = sp.color
    // grid + ticks in the right gutter
    val tickStyle = TextStyle(fontSize = 9.5.sp, fontWeight = FontWeight.W400, color = hb.colors.muted.copy(alpha = 0.85f), fontFeatureSettings = "tnum")
    niceTicks(lo, hi, sp.ticksN).filter { it in lo..hi }.forEach { t ->
        val ty = Y(t)
        if (abs(ty - base) > 1 * dp) drawLine(hb.colors.grid.copy(alpha = if (hb.dark) 0.45f else 0.9f), Offset(0f, ty), Offset(plotW, ty), 0.6f * dp)
        val tl = measurer.measure(if (t % 1.0 != 0.0) R3Texts.dec(t, 1) else R3Texts.int(t), tickStyle)
        drawText(tl, topLeft = Offset(plotW + 4 * dp, ty - tl.size.height / 2f))
    }
    drawLine(hb.colors.grid, Offset(0f, base + 0.5f * dp), Offset(plotW, base + 0.5f * dp), 1 * dp)
    // weekend columns
    ax.weekend.forEach { i -> drawRect(hb.colors.text.copy(alpha = if (hb.dark) 0.022f else 0.016f), Offset(X(i) - slot / 2f, top - 6 * dp), Size(slot, base - top + 6 * dp)) }
    // All: the month's lowest–highest band
    sp.band?.let { band ->
        var run = ArrayList<Int>()
        fun flush() {
            if (run.isEmpty()) return
            val path = Path()
            if (run.size == 1) { val i = run[0]; val b = band[i]!!; path.addRect(androidx.compose.ui.geometry.Rect(X(i) - slot * 0.3f, Y(b.second), X(i) + slot * 0.3f, max(Y(b.first), Y(b.second) + 1.5f * dp))) }
            else {
                run.forEachIndexed { k, i -> if (k == 0) path.moveTo(X(i), Y(band[i]!!.second)) else path.lineTo(X(i), Y(band[i]!!.second)) }
                run.asReversed().forEach { i -> path.lineTo(X(i), Y(band[i]!!.first)) }
                path.close()
            }
            drawPath(path, c.copy(alpha = 0.16f)); run = ArrayList()
        }
        band.forEachIndexed { i, b -> if (b == null) flush() else run.add(i) }
        flush()
    }
    // previous period (ghost): muted 50 %, 1.2 dp, broken at gaps
    sp.ghost?.let { g ->
        val gc = hb.colors.muted.copy(alpha = GhostStyle.LINE_A)
        segments(g, sp.maxGap ?: ax.maxGap).forEach { seg ->
            if (seg.size > 1) drawPath(Path().apply { moveTo(X(seg[0].first), Y(seg[0].second)); seg.drop(1).forEach { lineTo(X(it.first), Y(it.second)) } }, gc,
                style = Stroke(GhostStyle.LINE_W.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            else drawCircle(gc, 1.6f * dp, Offset(X(seg[0].first), Y(seg[0].second)))
        }
    }
    // target: dashed, tag on a card box at the clearer end
    var tagBox: androidx.compose.ui.geometry.Rect? = null
    sp.target?.let { tv ->
        val ty = Y(tv)
        drawLine(c.copy(alpha = 0.7f), Offset(0f, ty), Offset(plotW, ty), 1.1f * dp, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4 * dp, 4 * dp)))
        sp.targetTag?.let { tag ->
            val tl = measurer.measure(tag, TextStyle(fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = hb.colors.muted))
            val tw = tl.size.width.toFloat()
            fun clear(right: Boolean): Float = sp.pts.map { X(it.first) to Y(it.second) }
                .filter { (x, _) -> if (right) x > plotW - tw - 16 * dp else x < tw + 16 * dp }.minOfOrNull { abs(it.second - (ty - 9 * dp)) } ?: (99 * dp)
            val right = clear(true) >= min(clear(false), 14 * dp)
            val tx = if (right) plotW - 3 * dp else 3 * dp
            val bx = if (right) tx - tw - 3 * dp else tx - 3 * dp
            tagBox = androidx.compose.ui.geometry.Rect(bx, ty - 15 * dp, bx + tw + 6 * dp, ty - 2 * dp)
            drawRoundRect(hb.colors.card.copy(alpha = 0.85f), Offset(bx, ty - 15 * dp), Size(tw + 6 * dp, 13 * dp), CornerRadius(4 * dp))
            drawText(tl, topLeft = Offset(if (right) tx - tw else tx, ty - 5 * dp - tl.firstBaseline))
        }
    }
    // the line: broken at gaps (never interpolated), soft area under each run
    val segs = segments(sp.pts, sp.maxGap ?: ax.maxGap)
    val sw = (if (sp.pts.size <= 120) sp.strokeW else min(sp.strokeW, 1f)) * dp
    segs.forEach { seg ->
        if (seg.size > 1) {
            val line = Path().apply { moveTo(X(seg[0].first), Y(seg[0].second)); seg.drop(1).forEach { lineTo(X(it.first), Y(it.second)) } }
            val area = Path().apply { addPath(line); lineTo(X(seg.last().first), base); lineTo(X(seg[0].first), base); close() }
            drawPath(area, Brush.verticalGradient(listOf(c.copy(alpha = 0.22f), c.copy(alpha = 0.02f)), startY = top, endY = base))
            drawPath(line, c.copy(alpha = sp.lineAlpha), style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
    // 1Y chord: dashed first → last, arrow head, "−0.3 kg per month" pill under the readings
    sp.chord?.let { (p0, p1, lab) ->
        val x0 = X(p0.first); val y0 = Y(p0.second); val x1 = X(p1.first); val y1 = Y(p1.second)
        val tc = hb.colors.text
        drawLine(tc.copy(alpha = 0.75f), Offset(x0, y0), Offset(x1, y1), 1.3f * dp, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5 * dp, 4 * dp)))
        drawCircle(tc, 3 * dp, Offset(x0, y0))
        val ang = atan2(y1 - y0, x1 - x0); val ah = 7 * dp
        drawPath(Path().apply {
            moveTo(x1 + 4 * dp * cos(ang), y1 + 4 * dp * sin(ang))
            lineTo(x1 - ah * cos(ang - 0.45f), y1 - ah * sin(ang - 0.45f)); lineTo(x1 - ah * cos(ang + 0.45f), y1 - ah * sin(ang + 0.45f)); close()
        }, tc.copy(alpha = 0.9f))
        val tl = measurer.measure(lab, TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = hb.colors.onInverse))
        val tw = tl.size.width + 20 * dp
        val tyLim = sp.target?.let { Y(it) - 17 * dp } ?: (base - 4 * dp)
        val xy = sp.pts.map { X(it.first) to Y(it.second) }
        var spot: Offset? = null
        for (t in listOf(.3f, .25f, .35f, .2f, .4f, .45f, .5f, .15f, .55f, .6f)) {
            val mx = x0 + (x1 - x0) * t
            if (mx - tw / 2 < 4 * dp) continue
            val inside = xy.filter { it.first in (mx - tw / 2 - 4 * dp)..(mx + tw / 2 + 4 * dp) }.map { it.second }
            val tp = (inside + (y0 + (y1 - y0) * t)).max() + 7 * dp
            if (tp + 22 * dp <= tyLim) { spot = Offset(mx, tp); break }
        }
        val o = spot ?: run {
            val mx = x0 + (x1 - x0) * 0.55f
            val inside = xy.filter { it.first in (mx - tw / 2 - 4 * dp)..(mx + tw / 2 + 4 * dp) }.map { it.second }
            Offset(mx, max(top, (inside + y0).min() - 30 * dp))
        }
        drawRoundRect(hb.colors.inverse, Offset(o.x - tw / 2, o.y), Size(tw, 22 * dp), CornerRadius(11 * dp))
        drawText(tl, topLeft = Offset(o.x - tl.size.width / 2f, o.y + 11 * dp - tl.size.height / 2f))
    }
    // dots: the last (or the highlighted day) as a ring dot; the day window dims the other days to 55 %
    val dr = (sp.dotR ?: if (sp.pts.size <= 40) 2.4f else 1.4f) * dp
    sp.pts.forEachIndexed { k, (i, v) ->
        val o = Offset(X(i), Y(v))
        val big = (ax.hl == null && k == sp.pts.lastIndex) || (ax.hl != null && i == ax.hl) || i == scrubI
        if (big) { drawCircle(hb.colors.card, 5.4f * dp, o); drawCircle(c, 3.8f * dp, o) }
        else drawCircle(c.copy(alpha = if (ax.hl != null && i != ax.hl) 0.55f else 1f), dr, o)
    }
    // week lows ringed
    val pmap = sp.pts.toMap()
    sp.rings.forEach { i -> pmap[i]?.let { v -> drawCircle(c.copy(alpha = 0.9f), 6.2f * dp, Offset(X(i), Y(v)), style = Stroke(1.3f * dp)) } }
    // highest tagged above, lowest below (All: the band's edges)
    if (sp.tags && sp.pts.size >= 3) {
        var hiP = sp.pts.maxWith(compareBy<Pair<Int, Double>> { it.second }.thenBy { it.first })
        var loP = sp.pts.minWith(compareBy<Pair<Int, Double>> { it.second }.thenByDescending { it.first })
        sp.band?.let { b ->
            val bb = b.withIndex().filter { it.value != null }
            if (bb.isNotEmpty()) {
                hiP = bb.map { it.index to it.value!!.second }.maxWith(compareBy<Pair<Int, Double>> { it.second }.thenBy { it.first })
                loP = bb.map { it.index to it.value!!.first }.minWith(compareBy<Pair<Int, Double>> { it.second }.thenByDescending { it.first })
            }
        }
        if (hiP.second != loP.second) {
            val st = TextStyle(fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = hb.colors.text.copy(alpha = 0.9f), fontFeatureSettings = "tnum")
            listOf(hiP to true, loP to false).forEach { (p, up) ->
                val tl = measurer.measure(R3Texts.dec(p.second, sp.decimals), st)
                val tw = tl.size.width.toFloat()
                var tx = X(p.first).coerceIn(12 * dp, plotW - 12 * dp)
                var tyB = if (up) max(Y(p.second) - 10 * dp, top + 2 * dp) else (Y(p.second) + 19 * dp).let { if (it > base - 2 * dp) Y(p.second) - 10 * dp else it }
                val tb = tagBox
                if (tb != null && tx + tw / 2 + 3 * dp > tb.left && tx - tw / 2 - 3 * dp < tb.right && tyB > tb.top - 1 * dp && tyB - 10 * dp < tb.bottom) {
                    val px = X(p.first); val py = Y(p.second)
                    tx = if (px < tb.center.x) px + tw / 2 + 8 * dp else px - tw / 2 - 8 * dp
                    tyB = py + 3 * dp
                    if (tx in tb.left..tb.right) tx = tb.right + tw / 2 + 4 * dp
                }
                drawRoundRect(hb.colors.card.copy(alpha = 0.8f), Offset(tx - tw / 2 - 3 * dp, tyB - 9 * dp), Size(tw + 6 * dp, 12 * dp), CornerRadius(4 * dp))
                drawText(tl, topLeft = Offset(tx - tw / 2, tyB - tl.firstBaseline))
            }
        }
    }
    // selected day on the period axis: cursor + ringed dot (its label is circled)
    val selI = ax.selI
    if (selI != null && ax.hl == null) {
        val sx = X(selI)
        drawLine(hb.colors.text.copy(alpha = 0.35f), Offset(sx, top - 4 * dp), Offset(sx, base), 1 * dp)
        pmap[selI]?.let { v -> drawCircle(c, 6 * dp, Offset(sx, Y(v))); drawCircle(hb.colors.card, 2.6f * dp, Offset(sx, Y(v))) }
    }
    // shared scrub cursor
    if (scrubI != null) drawLine(hb.colors.text.copy(alpha = 0.35f), Offset(X(scrubI), top - 4 * dp), Offset(X(scrubI), base), 1 * dp)
    if (sp.showAxis) drawAxisLabels(sp.labels, slot, base, sp.todayI, selI, ax.futureFrom, measurer, hb)
}

/** The chart as a composable with tap (re-scope) and hold-and-slide (scrub) on its buckets. */
@Composable
internal fun BodyLineChart(sp: BodyLineSpec, buckets: List<SeriesBucket>, scrub: ScrubState, onTap: ((LocalDate) -> Unit)?, modifier: Modifier, a11y: String) {
    val hb = LocalHB.current
    val measurer = rememberTextMeasurer()
    val scrubI = scrub.dayOrNull()?.let { sp.axis.idx(it) ?: sp.axis.days.indexOf(it).takeIf { i -> i >= 0 } }
    Canvas(modifier.semantics { contentDescription = a11y }.scrubAndTap(buckets, scrub, { b -> onTap?.invoke(b.day) }, BODY_GUTTER, true)) {
        drawBodyLine(sp, measurer, hb, scrubI)
    }
}
