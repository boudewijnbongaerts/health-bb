package com.boudhealth.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.data.RingMode
import com.boudhealth.data.SectionPeriod
import com.boudhealth.data.SectionScope
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.GlyphCircle
import com.boudhealth.ui.section.R3Base
import com.boudhealth.ui.section.CalendarModel
import com.boudhealth.ui.section.MonthRingGrid
import com.boudhealth.ui.section.R3StatusIcon
import com.boudhealth.ui.section.R3Texts
import com.boudhealth.ui.section.RingCalendar
import com.boudhealth.ui.section.SectionContext
import com.boudhealth.ui.section.YearRingRow
import com.boudhealth.ui.theme.HB
import com.boudhealth.ui.theme.LocalHB
import java.time.LocalDate

// =====================================================================================================================
// R3 Activity hero (IMPL_PLAN §3A "Hero", port of out/activity/src/act.py hero / ring_block / lifetime / hero_cover).
// INNER (frame 424, All 344, padding 22 by S): left zone 372 from the card edge = ring block (D 252 + 3 pace chips)
// centred at card x 186, 1 dp divider at card x 372 (inset 22), right zone from card x 400 to w − 26 = ring calendar
// (1D / 1M: month, 1W: D1-B six weeks), 1Y month grid, All year row. All: lifetime totals on the left.
// COVER (padding 16, height = content): ring D 168 + stacked chips (h 30) · divider at card y 196 · calendar / month grid;
// All: lifetime rows · divider at y 146 · year row. The ring shows the EFFECTIVE period (a selected day), the right zone
// the scope's period with the day marked.
// =====================================================================================================================

@Composable
internal fun ActivityHero(ctx: SectionContext) {
    val m = ctx.act() ?: return
    if (ctx.wide) HeroInner(ctx, m) else HeroCover(ctx, m)
}

// ---------------------------------------------------------------------------------------------------------------- inner

@Composable
private fun HeroInner(ctx: SectionContext, m: ActR3) {
    val hb = LocalHB.current
    Row(Modifier.fillMaxSize()) {
        // left zone: card x 22..350 (centre 186 − 22 = 164 → width 328), then 22 to the divider at card x 372
        Box(Modifier.width(328.dp).fillMaxHeight(), contentAlignment = if (m.frame is SectionPeriod.All) Alignment.CenterStart else Alignment.Center) {
            if (m.frame is SectionPeriod.All) LifetimeRows(m, cover = false) else RingBlock(m, 252.dp, cover = false)
        }
        Spacer(Modifier.width(22.dp))
        Box(Modifier.width(1.dp).fillMaxHeight().background(hb.colors.grid.copy(alpha = 0.8f)))
        Spacer(Modifier.width(27.dp))
        Box(Modifier.weight(1f).fillMaxHeight().padding(end = 4.dp)) { HeroRight(ctx, m, cover = false) }
    }
}

/** Ring D (numbers in the stroke) + the 3 pace chips 16 under it (inner: one row centred; cover: stacked, see [HeroCover]). */
@Composable
private fun RingBlock(m: ActR3, size: Dp, cover: Boolean) {
    val rs = m.rings ?: return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SectionRings(rs, size)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            m.chips.forEachIndexed { i, s -> PaceChip(s, R3Texts.CHIPS[i], if (cover) 30.dp else 28.dp) }
        }
    }
}

/** actlib.pace_chip: tile pill (h 28 / cover 30), status icon 18 at x+12, label 12/600 at +37, end padding 13. */
@Composable
private fun PaceChip(s: Status, label: String, h: Dp, compact: Boolean = false) {
    val hb = LocalHB.current
    val a11y = listOfNotNull(label, R3Texts.a11yIcon(s.icon)).joinToString(", ")
    // compact (cover, QA F11): 12 dp narrower so ring 168 + 16 + the widest chip fit the 288 dp content (app gutter 20)
    Row(Modifier.height(h).background(hb.colors.tile, RoundedCornerShape(h / 2)).padding(start = if (compact) 8.dp else 12.dp, end = if (compact) 9.dp else 13.dp)
        .semantics(mergeDescendants = true) { contentDescription = a11y }, verticalAlignment = Alignment.CenterVertically) {
        R3StatusIcon(s.icon, s.tone, if (compact) 16.dp else 18.dp)
        Spacer(Modifier.width(if (compact) 5.dp else 7.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.text, maxLines = 1)
    }
}

/**
 * All: the three lifetime totals (act.py lifetime): glyph circle 40 (cover 30) + value 28/700 (cover 19) + unit 13 (cover
 * 12) muted 6 after it, pitch 74 (cover 44); inner rows start 14 into the left zone (card x 36).
 */
@Composable
private fun LifetimeRows(m: ActR3, cover: Boolean) {
    val hb = LocalHB.current
    val lt = m.hero.lifetime ?: return
    val rows = listOf(
        Triple(Glyph.Stopwatch, R3Texts.int(lt.first), R3Texts.H_WORKOUTS),
        Triple(Glyph.Steps, R3Texts.int(lt.second), R3Texts.STEPS_LC),
        Triple(Glyph.Heart, R3Texts.int(lt.third), R3Texts.HEART_POINTS_LC),
    )
    val gd = if (cover) 30.dp else 40.dp
    Column(Modifier.padding(start = if (cover) 0.dp else 14.dp)) {
        rows.forEachIndexed { i, (g, v, u) ->
            Row(Modifier.height(if (cover) 44.dp else 74.dp).semantics(mergeDescendants = true) { contentDescription = "$v $u" },
                verticalAlignment = Alignment.CenterVertically) {
                GlyphCircle(g, hb.palette.ring(i).first, size = gd, glyphSize = if (cover) 17.dp else 22.dp)
                Spacer(Modifier.width(14.dp))
                Text(v, Modifier.alignByBaseline(), fontSize = (if (cover) 19 else 28).sp, fontWeight = FontWeight.Bold, color = hb.colors.text, maxLines = 1,
                    style = R3Base)
                Spacer(Modifier.width(6.dp))
                Text(u, Modifier.alignByBaseline(), fontSize = (if (cover) 12 else 13).sp, color = hb.colors.muted, maxLines = 1)
            }
        }
    }
}

/** Right zone (inner) / the block under the divider (cover): calendar, month grid or year row by the FRAME unit. */
@Composable
private fun HeroRight(ctx: SectionContext, m: ActR3, cover: Boolean) {
    val h = m.hero
    when (val f = m.frame) {
        is SectionPeriod.Year -> MonthRingGrid(h.year ?: f.start.year, h.monthRings, m.today, cover) { ym -> ctx.drill(ym.atDay(1)) }
        is SectionPeriod.All -> YearRingRow(h.years, m.today, cover) { y -> ctx.drill(LocalDate.of(y, 1, 1)) }
        else -> {
            val model = remember(m) { calendarModel(m) }
            RingCalendar(model, cover, onDay = { d -> onDay(ctx, m, d) }, modifier = if (cover) Modifier else Modifier.fillMaxSize())
        }
    }
}

/** 1D frame: a tap moves the tab to that day; 1W / 1M: select the day (tap again clears) via [SectionContext.drill]. */
private fun onDay(ctx: SectionContext, m: ActR3, d: LocalDate) {
    if (d.isAfter(m.today)) return
    if (m.frame is SectionPeriod.Day) {
        if (d != m.frame.start) ctx.setScope(SectionScope(SectionPeriod.Day(d.toEpochDay()), null, d == m.today))
    } else ctx.drill(d)
}

/** The ring calendar of the frame (act.py month_cal / weeks_cal). */
internal fun calendarModel(m: ActR3): CalendarModel {
    val h = m.hero
    val f = m.frame
    val g = h.dayGoals
    return CalendarModel(
        weeks = h.weeks,
        monthOnly = if (f is SectionPeriod.Week) null else h.month,
        rings = h.dayRings,
        today = m.today,
        selected = if (f is SectionPeriod.Day) f.start else m.selected,
        bandWeek = if (f is SectionPeriod.Week) f.start else null,
        mondayLabels = f is SectionPeriod.Week,
        monthLabel = if (f is SectionPeriod.Day) R3Texts.monthYear(f.start) else null,
        weekColumn = h.weekColumn,
        a11y = h.dayValues.mapValues { (d, v) -> R3Texts.calendarCell(d, v[0], g[0], v[1], g[1], v[2], g[2]) },
    )
}

// ---------------------------------------------------------------------------------------------------------------- cover

/**
 * Draws [content] with [side] dp more on each side and [top] / [bottom] dp reaching into the hero frame's padding (the
 * mock-up boxes start at the card edge + 10 and run to the card bottom), reporting the inset size to the parent.
 */
private fun Modifier.bleed(side: Dp = 0.dp, top: Dp = 0.dp, bottom: Dp = 0.dp, height: Dp? = null): Modifier = layout { me, c ->
    val s = side.roundToPx(); val t = top.roundToPx(); val b = bottom.roundToPx()
    val w = if (c.hasBoundedWidth) c.maxWidth + 2 * s else c.maxWidth
    val hh = height?.roundToPx()
    val p = me.measure(c.copy(minWidth = if (c.hasBoundedWidth) w else c.minWidth, maxWidth = w,
        minHeight = hh ?: 0, maxHeight = hh ?: c.maxHeight))
    layout((p.width - 2 * s).coerceAtLeast(0), (p.height - t - b).coerceAtLeast(0)) { p.place(-s, -t) }
}

@Composable
private fun HeroCover(ctx: SectionContext, m: ActR3) {
    val hb = LocalHB.current
    val divider: @Composable () -> Unit = { Box(Modifier.fillMaxWidth().height(1.dp).background(hb.colors.grid.copy(alpha = 0.8f))) }
    if (m.frame is SectionPeriod.All) {
        // lifetime rows centred at card y 32 / 76 / 120 (content 16 / 60 / 104) · divider at card y 146 · years row 150..290
        Box(Modifier.fillMaxWidth().height(130.dp)) { Box(Modifier.offset(y = (-6).dp)) { LifetimeRows(m, cover = true) } }
        divider()
        Spacer(Modifier.height(3.dp))
        Box(Modifier.fillMaxWidth().bleed(side = 4.dp, bottom = 16.dp, height = 140.dp)) { HeroRight(ctx, m, cover = true) }
        return
    }
    val rs = m.rings
    // ring D 168 at the content corner, the chips stacked 16 to its right, centred on the ring (pitch 38);
    // narrow screens (< 360) shrink the ring (min 136) so the widest chip ("Heart pts" ≈ 94) is not clipped
    val d = (ctx.contentWidth - 32.dp - 16.dp - 96.dp).coerceIn(136.dp, 168.dp)
    Row(Modifier.fillMaxWidth().height(d), verticalAlignment = Alignment.CenterVertically) {
        if (rs != null && rs.mode != RingMode.LIFETIME) SectionRings(rs, d)
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            m.chips.forEachIndexed { i, s -> PaceChip(s, R3Texts.CHIPS[i], 30.dp, compact = true) }
        }
    }
    Spacer(Modifier.height(12.dp))
    divider()
    when (m.frame) {
        is SectionPeriod.Year -> Box(Modifier.fillMaxWidth().bleed(side = 6.dp, bottom = 16.dp, height = 156.dp)) { HeroRight(ctx, m, cover = true) }
        is SectionPeriod.Week -> Box(Modifier.fillMaxWidth().bleed(side = 4.dp, top = 4.dp, bottom = 12.dp, height = 346.dp)) { HeroRight(ctx, m, cover = true) }
        else -> {
            val h = if (m.frame is SectionPeriod.Day) 364.dp else 346.dp
            Box(Modifier.fillMaxWidth().bleed(side = 6.dp, top = 4.dp, bottom = 12.dp, height = h)) { HeroRight(ctx, m, cover = true) }
        }
    }
}
