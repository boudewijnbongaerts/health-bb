package com.boudhealth.ui.section

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.boudhealth.data.Grain
import com.boudhealth.data.Insights
import com.boudhealth.data.PeriodUnit
import com.boudhealth.data.SectionPeriod
import com.boudhealth.data.SectionScope
import com.boudhealth.data.SeriesBucket
import com.boudhealth.ui.components.CalloutLine
import com.boudhealth.ui.components.ChartCallout
import com.boudhealth.ui.components.ChartGrain
import com.boudhealth.ui.components.DetailScaffold
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.GlyphIcon
import com.boudhealth.ui.components.IconCircleButton
import com.boudhealth.ui.components.LocalScreen
import com.boudhealth.ui.components.TLevel
import com.boudhealth.ui.components.TPoint
import com.boudhealth.ui.components.TimeChart
import com.boudhealth.ui.components.TimeChartState
import com.boudhealth.ui.components.TimeStyle
import com.boudhealth.ui.components.plainClickable
import com.boudhealth.ui.components.rememberHaptics
import com.boudhealth.ui.nav.Nav
import com.boudhealth.ui.theme.HbShapes
import com.boudhealth.ui.theme.LocalHB
import java.time.LocalDate
import java.time.YearMonth

// =====================================================================================================================
// Detail pages (restore round, V2_REMOVALS_AUDIT C1–C3, D1–D5): periods 1D · 1W · 1M · 3M · 1Y · All (D8: detail pages
// keep 3M), the interactive TimeChart (tap / scrub read-out, y-axis values, pinch zoom on long ranges), a per-day
// history list with the change vs the day before.
// =====================================================================================================================

/**
 * Period of a detail page: a section scope, or 3M = the three calendar months ending with [q] (D8). A day picked
 * inside 3M becomes a 1D scope; the page keeps the 3M scope as the parent for Back.
 */
@Immutable
data class DScope(val std: SectionScope? = null, val q: YearMonth? = null) {
    val is3M: Boolean get() = q != null
    /** What the page loads: the section scope's effective period, or the 3 months as one range. */
    val period: SectionPeriod get() = q?.let { quarterPeriod(it) } ?: std!!.effective
    val selectedDay: LocalDate? get() = std?.selectedDay

    companion object {
        fun of(s: SectionScope) = DScope(s, null)
        fun quarter(end: YearMonth) = DScope(null, end)
        fun quarterPeriod(end: YearMonth): SectionPeriod.All =
            SectionPeriod.All(end.minusMonths(2).atDay(1).toEpochDay(), end.atEndOfMonth().toEpochDay())
        val LABELS = listOf("1D", "1W", "1M", "3M", "1Y", "All")
    }
}

/** "Sep – Nov 2026" / "Dec 2025 – Feb 2026". */
fun quarterLabel(end: YearMonth): String {
    val a = end.minusMonths(2)
    fun m(y: YearMonth) = y.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
    return if (a.year == end.year) "${m(a)} – ${m(end)} ${end.year}" else "${m(a)} ${a.year} – ${m(end)} ${end.year}"
}

@Composable
fun rememberDScope(key: String, defaultUnit: PeriodUnit = PeriodUnit.WEEK, initial: SectionPeriod? = null) = remember(key) {
    val today = LocalDate.now()
    val p = initial ?: SectionScopes.takeHandoff(key)
    mutableStateOf(DScope.of(
        if (p == null) SectionScope(SectionPeriod.current(defaultUnit, today, today.minusYears(10)), null, true)
        else SectionScope(p, null, p.isCurrent(today)),
    ))
}

/** Detail-page state: the scope plus a parent stack (a day picked in 3M / 1Y / All; Back returns). */
class DetailNav(initial: DScope) {
    var scope by mutableStateOf(initial)
    var parents by mutableStateOf(listOf<DScope>())
    fun set(s: DScope) { scope = s; parents = emptyList() }
    /** Tap a day: inside 1W / 1M it becomes the selected day; from 3M / 1Y / All it opens that day (Back returns). */
    fun pick(day: LocalDate, today: LocalDate) {
        if (day.isAfter(today)) return
        val st = scope.std
        if (st != null && (st.unit == PeriodUnit.WEEK || st.unit == PeriodUnit.MONTH)) { scope = DScope.of(st.select(day)); return }
        if (st != null && st.unit == PeriodUnit.DAY) return
        parents = parents + scope
        scope = DScope.of(SectionScope(SectionPeriod.Day(day.toEpochDay()), null, day == today))
    }
    fun canBack(): Boolean = scope.selectedDay != null || parents.isNotEmpty()
    fun back(today: LocalDate) {
        val st = scope.std
        if (st?.selectedDay != null) scope = DScope.of(st.clearSelection(today))
        else if (parents.isNotEmpty()) { scope = parents.last(); parents = parents.dropLast(1) }
    }
}

@Composable
fun rememberDetailNav(key: String, defaultUnit: PeriodUnit = PeriodUnit.WEEK, initial: SectionPeriod? = null): DetailNav {
    val s = rememberDScope(key, defaultUnit, initial)
    return remember(key) { DetailNav(s.value) }
}

/** Period bar of a detail page: the section bar's look, with 3M between 1M and 1Y. */
@Composable
fun DetailPeriodBar(dn: DetailNav, dataStart: LocalDate, today: LocalDate, wide: Boolean, modifier: Modifier = Modifier) {
    val hb = LocalHB.current
    val haptics = rememberHaptics()
    val s = dn.scope
    val idx = if (s.is3M) 3 else when (s.std!!.unit) { PeriodUnit.DAY -> 0; PeriodUnit.WEEK -> 1; PeriodUnit.MONTH -> 2; PeriodUnit.YEAR -> 4; PeriodUnit.ALL -> 5 }
    val curQ = YearMonth.from(today)
    fun anchor(): LocalDate = s.q?.atEndOfMonth()?.let { minOf(it, today) } ?: minOf(s.std!!.period.last, today)
    fun select(i: Int) {
        haptics.segment()
        if (i == 3) { dn.set(DScope.quarter(YearMonth.from(anchor()).let { if (it.isAfter(curQ)) curQ else it })); return }
        val u = listOf(PeriodUnit.DAY, PeriodUnit.WEEK, PeriodUnit.MONTH, null, PeriodUnit.YEAR, PeriodUnit.ALL)[i]!!
        val p = SectionPeriod.of(u, anchor(), dataStart, today)
        dn.set(DScope.of(SectionScope(p, null, p.isCurrent(today))))
    }
    fun shifted(n: Int): DScope? {
        val q = s.q
        if (q != null) {
            val nq = q.plusMonths(3L * n)
            if (nq.isAfter(curQ) || nq.atEndOfMonth().isBefore(dataStart)) return null
            return DScope.quarter(nq)
        }
        val st = s.std!!
        val ns = st.shift(n, dataStart, today)
        return if (ns == st) null else DScope.of(ns)
    }
    val label = s.q?.let { quarterLabel(it) } ?: periodLabel(s.std!!.period)
    val nav: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val back = shifted(-1); val fwd = shifted(1)
            IconCircleButton(Glyph.ChevL, { back?.let { haptics.segment(); dn.scope = it } }, contentDescription = "Previous", size = 36.dp, glyphSize = 18.dp,
                enabled = back != null, tint = if (back != null) hb.colors.text else hb.colors.muted.copy(alpha = 0.4f))
            Text(label, style = hb.type.titleS.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = hb.colors.text, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp).plainClickable { select(idx) })
            IconCircleButton(Glyph.ChevR, { fwd?.let { haptics.segment(); dn.scope = it } }, contentDescription = "Next", size = 36.dp, glyphSize = 18.dp,
                enabled = fwd != null, tint = if (fwd != null) hb.colors.text else hb.colors.muted.copy(alpha = 0.4f))
            s.selectedDay?.let { d -> Spacer(Modifier.width(10.dp)); SelectedDayChip(d) { dn.back(today) } }
        }
    }
    if (wide) {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { nav() }
            Spacer(Modifier.width(12.dp))
            UnitChips(DScope.LABELS, idx, Modifier.width(340.dp)) { select(it) }
        }
    } else {
        Column(modifier.fillMaxWidth()) {
            UnitChips(DScope.LABELS, idx, Modifier.fillMaxWidth()) { select(it) }
            Spacer(Modifier.height(12.dp))
            nav()
        }
    }
}

/** Detail page frame: back header, sticky [DetailPeriodBar], Back clears a selected day / returns to the parent first. */
@Composable
fun SectionDetailScaffold(title: String, nav: Nav, dn: DetailNav, dataStart: LocalDate, content: @Composable ColumnScope.() -> Unit) {
    val today = LocalDate.now()
    val wide = LocalScreen.current.wide
    BackHandler(enabled = dn.canBack()) { dn.back(today) }
    DetailScaffold(title = title, onBack = { nav.back() }, sticky = { DetailPeriodBar(dn, dataStart, today, wide) }) {
        Spacer(Modifier.height(14.dp))
        content()
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Interactive detail chart (TimeChart): tap / drag read-out, y-axis values, zoom on 3M / 1Y / All
// ---------------------------------------------------------------------------------------------------------------------

fun chartGrain(g: Grain): ChartGrain = when (g) { Grain.HOUR -> ChartGrain.HOUR; Grain.DAY -> ChartGrain.DAY; else -> ChartGrain.MONTH }

/**
 * Bars of a period for a detail page via [TimeChart] (T4): stacked when [segmentColors] is given (bar = segment sum,
 * read-out value = [SeriesBucket.value]), goal line, read-out with the value, segments and the change vs the bar before.
 */
@Composable
fun DetailBars(
    p: SectionPeriod,
    buckets: List<SeriesBucket>,
    grain: Grain,
    color: Color,
    unit: String,
    description: String,
    modifier: Modifier = Modifier,
    decimals: Int = 0,
    goal: Double? = null,
    segmentColors: List<Color>? = null,
    segmentNames: List<String>? = null,
    segmentUnit: String = "min",
    height: Dp = 220.dp,
    state: TimeChartState = remember(p, buckets) { TimeChartState() },
) {
    val shown = buckets.filter { !it.future && (it.value != null || it.segments != null) }
    val points = remember(shown, segmentColors) {
        shown.mapIndexed { i, b ->
            val segs = if (segmentColors != null) b.segments?.mapIndexed { k, v -> v to segmentColors.getOrElse(k) { color } } else null
            TPoint(b.start, b.end, segs?.sumOf { it.first } ?: (b.value ?: 0.0), ref = i, segments = segs)
        }
    }
    val levels = remember(points, grain) { listOf(TLevel(chartGrain(grain), points, bucketed = false)) }
    val start = p.start.atStartOfDay()
    val end = p.endExclusive.atStartOfDay()
    val zoom = grain != Grain.HOUR && buckets.size > 45
    TimeChart(
        state = state, levels = levels, start = start, end = end, color = color,
        callout = { _, _, pt ->
            val b = shown.getOrNull(pt.ref) ?: return@TimeChart null
            val prev = shown.getOrNull(pt.ref - 1)
            val d = if (prev?.value != null && b.value != null) b.value - prev.value else null
            ChartCallout(
                title = bucketTitle(b, grain) + if (b.partial) " · so far" else "",
                value = b.value?.let { SFmt.num(it, decimals) } ?: "—", unit = unit,
                lines = if (segmentNames != null) b.segments?.mapIndexed { k, v -> CalloutLine(segmentNames.getOrElse(k) { "" }, "${SFmt.int(v)} $segmentUnit".trim(), segmentColors?.getOrNull(k)) }.orEmpty() else emptyList(),
                delta = d?.takeIf { prev != null }?.let { "${SFmt.delta(it, decimals)} vs ${bucketTitle(prev!!, grain)}" },
            )
        },
        description = description, modifier = modifier, height = height, style = TimeStyle.Bars,
        goal = goal, yFormat = { SFmt.compact(it) }, tagFormat = { SFmt.num(it, decimals) },
        zoomable = zoom, minSpanDays = 7.0,
    )
}

// ---------------------------------------------------------------------------------------------------------------------
// Per-day history (D2, H4, K1): newest first, grouped by month, value + change vs the day before; tap → that day
// ---------------------------------------------------------------------------------------------------------------------

@Composable
fun DayHistoryList(
    rows: List<com.boudhealth.data.DayRow>,
    unit: String,
    onDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    decimals: Int = 0,
    first: Int = 31,
    format: (Double) -> String = { SFmt.num(it, decimals) },
) {
    val shownRows = rows.filter { it.value != null }
    if (shownRows.isEmpty()) return
    val hb = LocalHB.current
    var all by remember(rows) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().background(hb.colors.card, HbShapes.card).padding(vertical = 6.dp)) {
        var month: YearMonth? = null
        shownRows.take(if (all) shownRows.size else first).forEach { r ->
            val d = r.day
            val ym = YearMonth.from(d)
            if (ym != month) {
                month = ym
                Text(SFmt.monthYear(ym.year, ym.monthValue), fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = hb.colors.muted, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
            }
            Row(Modifier.fillMaxWidth().plainClickable { onDay(d) }.padding(horizontal = 20.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Insights.dayLabel(d), fontSize = 14.sp, color = hb.colors.text, modifier = Modifier.weight(1f), maxLines = 1)
                r.changeVsPrevious?.let { Text(SFmt.delta(it, decimals), fontSize = 12.sp, color = hb.colors.muted, maxLines = 1, modifier = Modifier.padding(end = 14.dp)) }
                if (r.met == true) {
                    GlyphIcon(Glyph.Check, size = 14.dp, color = hb.colors.good)
                    Spacer(Modifier.width(6.dp))
                }
                Text(format(r.value!!) + if (unit.isNotEmpty()) " $unit" else "", fontSize = 14.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = hb.colors.text, maxLines = 1)
            }
        }
        if (!all && shownRows.size > first) {
            Text("See all", fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = hb.colors.accent,
                modifier = Modifier.plainClickable { all = true }.padding(horizontal = 20.dp, vertical = 12.dp))
        }
    }
}

/** Pace of this week's goal as one icon (H5): ✓ done · ▲ ahead · ● on track · ▼ behind (T1 Pace / weeklyGoals). */
@Composable
fun PaceChip(status: com.boudhealth.data.CardioPace, modifier: Modifier = Modifier) {
    val hb = LocalHB.current
    val (fg, bg) = when (status) {
        com.boudhealth.data.CardioPace.DONE, com.boudhealth.data.CardioPace.AHEAD -> hb.colors.good to hb.colors.goodCont
        com.boudhealth.data.CardioPace.BEHIND -> hb.colors.bad to hb.colors.badCont
        com.boudhealth.data.CardioPace.ON_TRACK -> hb.colors.text to hb.colors.tileHi
        else -> hb.colors.muted to hb.colors.tileHi
    }
    val word = when (status) {
        com.boudhealth.data.CardioPace.DONE -> "Goal met"; com.boudhealth.data.CardioPace.AHEAD -> "Ahead"
        com.boudhealth.data.CardioPace.ON_TRACK -> "On track"; com.boudhealth.data.CardioPace.BEHIND -> "Behind"; else -> ""
    }
    Box(modifier.semantics { contentDescription = word }) { ToneIcon(com.boudhealth.data.Pace.icon(status), fg, bg, size = 22.dp) }
}

/** A compact header above a detail block ("Days", "Heart zones" …): 13 sp bold muted, tight spacing. */
@Composable
fun DetailHeader(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val hb = LocalHB.current
    Row(modifier.fillMaxWidth().padding(start = 4.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = hb.colors.muted, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}
