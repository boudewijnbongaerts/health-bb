package com.boudhealth.ui.health

import com.boudhealth.data.CacheReads
import com.boudhealth.data.Extreme
import com.boudhealth.data.HrDay
import com.boudhealth.data.M
import com.boudhealth.data.MinMax
import com.boudhealth.data.Outside
import com.boudhealth.data.Plausible
import com.boudhealth.data.R3Math
import com.boudhealth.data.ReadingBasis
import com.boudhealth.data.ReadingChange
import com.boudhealth.data.SectionPeriod
import com.boudhealth.data.SectionScope
import com.boudhealth.data.TrendDir
import com.boudhealth.data.V2Engine
import com.boudhealth.data.history
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

// =====================================================================================================================
// Vitals R3 (IMPL_PLAN §3V, D3-A): the PURE data side of the tab. No android.* / Compose here, so it is JVM-tested in
// tools/unit/tests/VitalsR3Test.kt (UNIT_DATA="../ui/health/VitalsR3Data.kt"). Port of out/vitals/vitals_r3.py:
// x-domains, lane / card rows, weekly heart-rate rows, the hero range block, pills, the six Trends facts and the Heart
// rate card facts. No averages: every number is a reading, a lowest / highest, a count or a difference of two readings.
// Heart rate is ONLY ever a min–max (hr_min / hr_max per day) or a minute sample (HrDay): the daily "hr" (= HR_AVG) value
// is never read here (§4 R6). Texts are made by the UI files from R3Texts.
// =====================================================================================================================

/** The four nightly vitals, in tile / card order. */
val VITALS_IDS: List<String> = listOf("rhr", "hrv", "breathing", "spo2")

/** Decimals, the change threshold of the range icons (IMPL_PLAN §3V table) and the minimum y span of a lane / card chart. */
data class VSpec(val id: String, val decimals: Int, val eps: Double, val laneSpan: Double?, val cardSpan: Double)

object VitalsSpecs {
    val RHR = VSpec("rhr", 0, 1.0, null, 8.0)
    val HRV = VSpec("hrv", 0, 2.0, 12.0, 16.0)
    val BREATHING = VSpec("breathing", 1, 0.2, 1.2, 1.6)
    val SPO2 = VSpec("spo2", 0, 1.0, 4.0, 5.0)
    /** Heart rate (min–max only). */
    val HR = VSpec("hr", 0, 1.0, null, 0.0)
    fun of(id: String): VSpec = when (id) { "rhr" -> RHR; "hrv" -> HRV; "breathing" -> BREATHING; "spo2" -> SPO2; else -> HR }
}

/** Everything the tab reads, loaded once in `loadExtras` (one IO hop). */
class VitalsData(
    val now: LocalDateTime,
    val dataStart: LocalDate,
    /** dataStart..today per-day readings of [VITALS_IDS] (plausible only, R3.history). */
    val series: Map<String, Map<LocalDate, Double>>,
    /** Per-day lowest / highest heart rate (plausible only). */
    val hrMin: Map<LocalDate, Double>,
    val hrMax: Map<LocalDate, Double>,
    /** Minute samples of the day in scope (1D / selected day), for the day curve and the rail times. */
    val hrDay: HrDay?,
) {
    val today: LocalDate get() = now.toLocalDate()
    fun s(id: String): Map<LocalDate, Double> = series[id].orEmpty()
}

/** Loads [VitalsData] (blocking; call inside `SectionSpec.loadExtras`). */
fun loadVitalsData(e: V2Engine, reads: CacheReads, scope: SectionScope, dataStart: LocalDate, now: LocalDateTime): VitalsData {
    val today = now.toLocalDate()
    val series = VITALS_IDS.associateWith { e.history(it, today) }
    fun hr(key: String): Map<LocalDate, Double> = if (today.isBefore(dataStart)) emptyMap() else
        reads.daily(key, dataStart, today).filter { it.value in Plausible.HR_BPM }.associate { it.day to it.value }
    val day = scope.selectedDay ?: (scope.period as? SectionPeriod.Day)?.start
    return VitalsData(now, dataStart, series, hr(M.HR_MIN), hr(M.HR_MAX), day?.let { runCatching { reads.hrDay(it) }.getOrNull() })
}

/**
 * The frame of the tab: [period] = the scope's period (the hero keeps it), [selected] = a selected day (1W / 1M).
 * Day scope ([day] non-null) = 1D or a selected day: Trends, cards and the hero pill take that day's content.
 */
class VFrame(val period: SectionPeriod, val selected: LocalDate?, val today: LocalDate, val dataStart: LocalDate) {
    val day: LocalDate? = selected ?: (period as? SectionPeriod.Day)?.start
    val dayScope: Boolean get() = day != null
    /** The period's last day up to today. */
    val end: LocalDate = minOf(period.last, today)
    /** W / M / Y frame (a previous period exists to compare with). */
    val periodRanged: Boolean = period is SectionPeriod.Week || period is SectionPeriod.Month || period is SectionPeriod.Year
    /** W / M / Y content (no selected day). */
    val ranged: Boolean get() = periodRanged && !dayScope
    val isAll: Boolean get() = period is SectionPeriod.All && !dayScope

    companion object {
        fun of(scope: SectionScope, today: LocalDate, dataStart: LocalDate) = VFrame(scope.period, scope.selectedDay, today, dataStart)
    }
}

/** One bucket's lowest–highest (a single reading: lo == hi). */
data class VRow(val lo: Double, val hi: Double)

enum class DomainKind { DAY, MONTH }

/** Buckets of a lane / card x-axis (one slot per key). [futureFrom] = first future bucket; [selIndex] = marked day. */
class Domain(
    val kind: DomainKind,
    val keys: List<LocalDate>,
    val labels: List<String?>,
    val todayIndex: Int?,
    val futureFrom: Int?,
    val selIndex: Int?,
    /** 7-day context window ending on the day (1D lanes; day-scoped cards). */
    val window: Boolean = false,
) {
    val n: Int get() = keys.size
    fun indexOf(d: LocalDate?): Int? = d?.let { x -> keys.indexOfFirst { if (kind == DomainKind.DAY) it == x else YearMonth.from(it) == YearMonth.from(x) }.takeIf { it >= 0 } }
}

/** Weekly heart-rate rows over a month domain's extent (1Y / All: a monthly band would be a flat block). */
class HrWeeks(val rows: List<VRow?>, val labels: List<String?>, val todayIndex: Int?, val futureFrom: Int?)

/** Label of a hero range row (texts in the UI: This week · Last week · {Month} · {Mon yyyy} · {d–d Mon} · {yyyy}). */
sealed interface RowLabel {
    data object ThisWeek : RowLabel
    data object LastWeek : RowLabel
    data class Month(val ym: YearMonth) : RowLabel
    data class MonYear(val d: LocalDate) : RowLabel
    data class Week(val monday: LocalDate) : RowLabel
    data class Year(val year: Int) : RowLabel
}

data class RangeSpan(val label: RowLabel, val from: LocalDate, val to: LocalDate, val mm: MinMax, val current: Boolean)

/** The latest reading for a card / the hero: [carried] = not on the day (day scope) / before the period (W/M/Y/All). */
data class VReading(val at: Extreme?, val carried: Boolean)

/** The pill under a value: day change, All change since the first reading, or the days outside the previous range. */
sealed interface VPill {
    data class Change(val r: ReadingChange) : VPill
    data class SinceFirst(val r: ReadingChange) : VPill
    data class Out(val o: Outside) : VPill
}

/** The six Trends facts (texts in VitalsTrends.kt). */
sealed interface VTile {
    val id: String
    /** Day: the day's reading vs the previous reading. [spark] = the 7 days ending on the day. */
    data class Change(override val id: String, val r: ReadingChange, val spark: List<Double>) : VTile
    /** Day without a reading: the carried reading (plain value, "Last measured …"), or nothing at all. */
    data class Carried(override val id: String, val at: Extreme?) : VTile
    /** A range: W/M/Y vs the previous period's ([prev] null = plain value: day HR, All). */
    data class Range(override val id: String, val cur: VRow?, val prev: VRow?, val dir: TrendDir?, val spark: List<Double>) : VTile
    /** "Lowest RHR since {date}" ([since] null = all-time: "Lowest RHR"). */
    data class Record(override val id: String, val low: Boolean, val at: Extreme, val since: LocalDate?, val spark: List<Double>) : VTile
    /** Days outside the previous range across the four vitals; [flags] per elapsed day (null: too many days to draw). */
    data class Out(val o: Outside, val flags: List<Boolean>?) : VTile { override val id: String get() = "rhr" }
}

/** Heart rate card facts (only lowest / highest / minute samples). */
class HrCardFacts(
    val lo: Extreme?, val hi: Extreme?,
    /** Previous period's lowest–highest (W/M/Y). */
    val prev: VRow?,
    /** Day scope: latest minute sample of today (time, bpm). */
    val latest: Pair<LocalTime, Int>?,
    val loTime: LocalTime?, val hiTime: LocalTime?,
    /** Day scope: 288 five-minute maxima (null = no sample). */
    val curve: List<Double?>?,
    /** Resting heart rate rail cell: the latest reading on / before the day / period end. */
    val rhr: Extreme?,
)

object VitalsMath {
    private val L = Locale.ENGLISH

    // ------------------------------------------------------------------ small helpers

    fun lastOnOrBefore(s: Map<LocalDate, Double>, d: LocalDate): Extreme? =
        s.entries.filter { !it.key.isAfter(d) }.maxByOrNull { it.key }?.let { Extreme(it.value, it.key) }

    fun monthEnd(d: LocalDate): LocalDate = YearMonth.from(d).atEndOfMonth()

    fun days(a: LocalDate, b: LocalDate): List<LocalDate> = generateSequence(a) { it.plusDays(1) }.takeWhile { !it.isAfter(b) }.toList()

    /** Lowest–highest of a pair of maps (heart rate: lowest of the minima, highest of the maxima) over [a]..[b]. */
    fun hrRange(mn: Map<LocalDate, Double>, mx: Map<LocalDate, Double>, a: LocalDate, b: LocalDate): Pair<Extreme, Extreme>? {
        if (b.isBefore(a)) return null
        val lo = R3Math.minMax(mn, a, b)?.lo ?: return null
        val hi = R3Math.minMax(mx, a, b)?.hi ?: return null
        return lo to hi
    }

    /** y range of a chart (vitals_r3.yr): values + extra, widened to [minSpan], then padded by [pad] of the span. */
    fun yRange(values: List<Double>, extra: List<Double> = emptyList(), pad: Double, minSpan: Double? = null): Pair<Double, Double> {
        val vs = values + extra
        if (vs.isEmpty()) return 0.0 to 1.0
        var lo = vs.min(); var hi = vs.max()
        if (minSpan != null && hi - lo < minSpan) { val c = (hi + lo) / 2; lo = c - minSpan / 2; hi = c + minSpan / 2 }
        if (hi - lo < 1e-9) { lo -= 1.0; hi += 1.0 }
        val pd = (hi - lo) * pad
        return (lo - pd) to (hi + pd)
    }

    /** Round tick values inside [a]..[b] (kit charts.nice_ticks, the mock-ups' axis). */
    fun niceTicks(a: Double, b: Double, n: Int = 3): List<Double> {
        val span = b - a
        if (span <= 0) return listOf(a)
        val raw = span / n
        val mag = 10.0.pow(floor(log10(raw)))
        val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw * 0.7 }
        val out = ArrayList<Double>()
        var t = ceil(a / step) * step
        while (t <= b + 1e-9) { out += Math.round(t * 1e6) / 1e6; t += step }
        return out
    }

    // ------------------------------------------------------------------ x-domains

    private fun dayLetter(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.NARROW, L)
    private fun monthLetter(m: Int) = java.time.Month.of(m).getDisplayName(TextStyle.NARROW, L)

    /**
     * The x-domain (vitals_r3.domain). [window] = the 7 days ending on [VFrame.day] (1D lanes, day-scoped cards).
     * Otherwise the frame period: W/M per day (M labels 1 · 8 · 15 · 22 · 29; today and the selected day always get
     * their label, neighbours within 2 yield), Y 12 months, All the months from the first data month.
     */
    fun domain(f: VFrame, window: Boolean): Domain {
        val today = f.today
        if (window && f.day != null) {
            val day = f.day
            val ks = (6 downTo 0).map { day.minusDays(it.toLong()) }
            return Domain(DomainKind.DAY, ks, ks.map { dayLetter(it) }, if (day == today) 6 else null, null, 6, window = true)
        }
        val p = f.period
        return when (p) {
            is SectionPeriod.Week, is SectionPeriod.Month, is SectionPeriod.Day -> {
                val ks = days(p.start, p.last)
                val labels: MutableList<String?> = if (p is SectionPeriod.Week) ks.map { dayLetter(it) }.toMutableList()
                    else ks.map { if (it.dayOfMonth in setOf(1, 8, 15, 22, 29)) "${it.dayOfMonth}" else null }.toMutableList()
                val ti = ks.indexOf(today).takeIf { it >= 0 }
                val si = f.selected?.let { s -> ks.indexOf(s).takeIf { it >= 0 } }
                if (p is SectionPeriod.Month) {
                    for (j in listOfNotNull(ti, si)) for (q in maxOf(0, j - 2)..minOf(labels.lastIndex, j + 2)) if (q != ti && q != si) labels[q] = null
                    for (j in listOfNotNull(ti, si)) labels[j] = "${ks[j].dayOfMonth}"
                }
                Domain(DomainKind.DAY, ks, labels, ti, ks.indexOfFirst { it.isAfter(today) }.takeIf { it >= 0 }, si)
            }
            is SectionPeriod.Year -> {
                val ks = (1..12).map { LocalDate.of(p.start.year, it, 1) }
                Domain(DomainKind.MONTH, ks, (1..12).map { monthLetter(it) }, if (p.start.year == today.year) today.monthValue - 1 else null,
                    ks.indexOfFirst { it.isAfter(today) }.takeIf { it >= 0 }, null)
            }
            is SectionPeriod.All -> {
                val ks = ArrayList<LocalDate>()
                var k = f.dataStart.withDayOfMonth(1)
                while (!k.isAfter(today)) { ks += k; k = k.plusMonths(1) }
                Domain(DomainKind.MONTH, ks, ks.map { if (it.monthValue == 1) "${it.year}" else null }, null, null, null)
            }
        }
    }

    /** Per bucket: the reading (day) or the month's lowest–highest (month); future buckets null. */
    fun rows(s: Map<LocalDate, Double>, d: Domain, today: LocalDate): List<VRow?> = d.keys.map { k ->
        if (k.isAfter(today)) null
        else if (d.kind == DomainKind.DAY) s[k]?.let { VRow(it, it) }
        else R3Math.minMax(s, k, minOf(monthEnd(k), today))?.let { VRow(it.lo.value, it.hi.value) }
    }

    /** Heart rate per bucket (day: that day's lowest–highest; month: the month's). */
    fun hrRows(mn: Map<LocalDate, Double>, mx: Map<LocalDate, Double>, d: Domain, today: LocalDate): List<VRow?> = d.keys.map { k ->
        if (k.isAfter(today)) null
        else if (d.kind == DomainKind.DAY) { val a = mn[k]; val b = mx[k]; if (a != null && b != null) VRow(a, b) else null }
        else hrRange(mn, mx, k, minOf(monthEnd(k), today))?.let { VRow(it.first.value, it.second.value) }
    }

    /** vitals_r3.hr_daily: Mon–Sun weekly lowest–highest across a month domain; a week is labelled with its month when its Thursday is the 12th–18th. */
    fun hrWeeks(mn: Map<LocalDate, Double>, mx: Map<LocalDate, Double>, d: Domain, today: LocalDate): HrWeeks {
        val a = d.keys.first(); val b = monthEnd(d.keys.last())
        var mon = SectionPeriod.mondayOf(a)
        val rows = ArrayList<VRow?>(); val labs = ArrayList<String?>(); var ti: Int? = null
        while (!mon.isAfter(b)) {
            val lo = days(maxOf(mon, a), minOf(mon.plusDays(6), b, today))
            val l = lo.mapNotNull { mn[it] }; val h = lo.mapNotNull { mx[it] }
            rows += if (l.isNotEmpty() && h.isNotEmpty()) VRow(l.min(), h.max()) else null
            val mid = mon.plusDays(3)
            var lab: String? = null
            if (mid.dayOfMonth in 12..18 && !mid.isBefore(a) && !mid.isAfter(b)) {
                val j = d.keys.indexOf(mid.withDayOfMonth(1))
                if (j >= 0) { lab = d.labels[j]; if (d.todayIndex == j) ti = labs.size }
            }
            labs += lab
            mon = mon.plusDays(7)
        }
        val ff = rows.indices.firstOrNull { i -> (i until rows.size).all { rows[it] == null } }
        return HrWeeks(rows, labs, ti, ff)
    }

    // ------------------------------------------------------------------ readings, pills, bands

    /** The latest reading on / before the day (day scope) or the period's end. */
    fun reading(s: Map<LocalDate, Double>, f: VFrame): VReading {
        val ref = f.day ?: f.end
        val at = lastOnOrBefore(s, minOf(ref, f.today))
        val carried = at != null && (if (f.day != null) at.day != f.day else at.day.isBefore(f.period.start))
        return VReading(at, carried)
    }

    /**
     * The value's pill (hero + cards): day scope = the day's reading vs the previous reading (FLAT only when the two
     * displayed values are equal: "Same as …"); All = latest vs the first reading; W/M/Y = days outside the previous
     * period's lowest–highest. null without a reading in scope (carried) or nothing to compare with.
     */
    fun pill(id: String, s: Map<LocalDate, Double>, f: VFrame): VPill? {
        val r = reading(s, f)
        if (r.at == null || r.carried) return null
        val dec = VitalsSpecs.of(id).decimals
        val flat = 0.5 * 10.0.pow(-dec)
        return when {
            f.day != null -> R3Math.readingChange(id, s, SectionPeriod.Day(f.day.toEpochDay()), f.day, f.today, ReadingBasis.PREVIOUS_READING, dec, flat, null)
                .takeIf { it.diff != null }?.let { VPill.Change(it) }
            f.period is SectionPeriod.All -> R3Math.readingChange(id, s, f.period, null, f.today, ReadingBasis.FIRST_EVER, dec, flat, null)
                .takeIf { it.diff != null }?.let { VPill.SinceFirst(it) }
            f.periodRanged -> R3Math.outsidePrevious(s, f.period, f.today).takeIf { it.prevRange != null }?.let { VPill.Out(it) }
            else -> null
        }
    }

    /** The previous FULL period's lowest–highest (the grey band) for W/M/Y frames. */
    fun prevBand(s: Map<LocalDate, Double>, f: VFrame): VRow? {
        if (!f.periodRanged) return null
        val q = f.period.previous() ?: return null
        return R3Math.minMax(s, q.start, q.last)?.let { VRow(it.lo.value, it.hi.value) }
    }

    fun prevHrBand(mn: Map<LocalDate, Double>, mx: Map<LocalDate, Double>, f: VFrame): VRow? {
        if (!f.periodRanged) return null
        val q = f.period.previous() ?: return null
        return hrRange(mn, mx, q.start, q.last)?.let { VRow(it.first.value, it.second.value) }
    }

    /** Days of the frame period outside the previous range (ringed points), W/M/Y only. */
    fun ringed(s: Map<LocalDate, Double>, f: VFrame): Set<LocalDate> =
        if (!f.periodRanged) emptySet() else R3Math.outsidePrevious(s, f.period, f.today).let { it.above + it.below }

    // ------------------------------------------------------------------ hero range block

    /**
     * Resting heart rate lowest–highest per span (vitals_r3.range_spans): day = This week · Last week · {previous month};
     * 1W = This week · Last week · {d–d Mon} (a past week: three week labels); 1M = {Month} · {previous Month} · {Mon yyyy}
     * (same month a year earlier); 1Y = the year and the two before; All = the four most recent years. Spans without a
     * reading are left out; the first row is the current one.
     */
    fun rangeSpans(s: Map<LocalDate, Double>, f: VFrame): List<RangeSpan> {
        val today = f.today
        val spans: List<Triple<RowLabel, LocalDate, LocalDate>> = when {
            f.day != null -> {
                val mon = SectionPeriod.mondayOf(f.day)
                val pm = YearMonth.from(f.day).minusMonths(1)
                listOf(Triple(RowLabel.ThisWeek, mon, minOf(mon.plusDays(6), today)), Triple(RowLabel.LastWeek, mon.minusDays(7), mon.minusDays(1)),
                    Triple(RowLabel.Month(pm), pm.atDay(1), pm.atEndOfMonth()))
            }
            f.period is SectionPeriod.Week -> (0..2).map { i ->
                val m = f.period.start.minusDays(7L * i)
                val cur = f.period.isCurrent(today)
                val lab = when { cur && i == 0 -> RowLabel.ThisWeek; cur && i == 1 -> RowLabel.LastWeek; else -> RowLabel.Week(m) }
                Triple(lab, m, minOf(m.plusDays(6), today))
            }
            f.period is SectionPeriod.Month -> {
                val ym = YearMonth.from(f.period.start)
                listOf(Triple(RowLabel.Month(ym), ym.atDay(1), minOf(ym.atEndOfMonth(), today)),
                    Triple(RowLabel.Month(ym.minusMonths(1)), ym.minusMonths(1).atDay(1), ym.minusMonths(1).atEndOfMonth()),
                    Triple(RowLabel.MonYear(ym.minusYears(1).atDay(1)), ym.minusYears(1).atDay(1), ym.minusYears(1).atEndOfMonth()))
            }
            else -> {
                val y0 = if (f.period is SectionPeriod.All) today.year else f.period.start.year
                val n = if (f.period is SectionPeriod.All) 4 else 3
                (0 until n).map { i -> val y = y0 - i; Triple(RowLabel.Year(y), maxOf(LocalDate.of(y, 1, 1), f.dataStart), minOf(LocalDate.of(y, 12, 31), today)) }
            }
        }
        return spans.mapIndexedNotNull { i, (lab, a, b) -> R3Math.minMax(s, a, b)?.let { RangeSpan(lab, a, b, it, i == 0) } }
    }

    // ------------------------------------------------------------------ Trends facts

    private fun spark7(s: Map<LocalDate, Double>, day: LocalDate): List<Double> = (6 downTo 0).mapNotNull { s[day.minusDays(it.toLong())] }

    /** Last reading per month from the first data month (All sparks). */
    fun monthlyLast(s: Map<LocalDate, Double>, today: LocalDate): List<Double> =
        if (s.isEmpty()) emptyList() else R3Math.perMonth(s, s.keys.min(), today).map { it.last.value }

    /** The minimum gap before a W / M / Y record counts (8 weeks / 8 months / 3 years). */
    fun recordBackDays(p: SectionPeriod): Long = when (p) { is SectionPeriod.Week -> 56; is SectionPeriod.Month -> 240; else -> 1095 }

    /**
     * Day T6: among RHR low · HRV high · RHR high · HRV low (readings on the day), the record with the OLDEST "since"
     * (an all-time record first; ties → that order). null without a reading on the day.
     */
    fun dayRecord(data: VitalsData, day: LocalDate): VTile.Record? {
        val cands = listOf("rhr" to true, "hrv" to false, "rhr" to false, "hrv" to true).mapNotNull { (id, low) ->
            val s = data.s(id); val v = s[day] ?: return@mapNotNull null
            VTile.Record(id, low, Extreme(v, day), R3Math.lastAtLeast(s, day, low), spark7(s, day))
        }
        return cands.minByOrNull { it.since ?: LocalDate.MIN }
    }

    /**
     * W/M/Y T6: the period's lowest RHR or highest HRV when it is a record of ≥ 8 weeks / 8 months / 3 years (the older
     * one; an all-time record first, ties → RHR). A value reached twice in the period is not shown (mock-up rule).
     */
    fun periodRecord(data: VitalsData, f: VFrame): VTile.Record? {
        val p = f.period
        val back = recordBackDays(p)
        val cands = listOf("rhr" to true, "hrv" to false).mapNotNull { (id, low) ->
            val s = data.s(id)
            val r = R3Math.periodRecord(s, id, p, f.today, low, back) ?: return@mapNotNull null
            // as the mock-up (vitals_r3.trend_tiles): not a record when an earlier day of the same period already had that value
            if (R3Math.lastAtLeast(s, r.at.day, low)?.let { !it.isBefore(p.start) } == true) return@mapNotNull null
            VTile.Record(id, low, r.at, r.lastAtLeast, s.filterKeys { k -> !k.isBefore(p.start) && !k.isAfter(f.end) }.toSortedMap().values.toList().takeLast(60))
        }
        return cands.minByOrNull { it.since ?: LocalDate.MIN }
    }

    /** W/M/Y T6 fallback: days outside the previous range across the four vitals (dots per elapsed day, ≤ 62 days). */
    fun outsideTile(data: VitalsData, f: VFrame): VTile.Out {
        val o = R3Math.outsideAny(VITALS_IDS.map { data.s(it) }, f.period, f.today)
        val ds = if (f.end.isBefore(f.period.start)) emptyList() else days(f.period.start, f.end)
        val out = o.above + o.below
        return VTile.Out(o, ds.takeIf { it.size <= 62 }?.map { it in out })
    }

    private fun rangeOf(s: Map<LocalDate, Double>, a: LocalDate, b: LocalDate): VRow? =
        if (b.isBefore(a)) null else R3Math.minMax(s, a, b)?.let { VRow(it.lo.value, it.hi.value) }

    private fun rangeTile(id: String, cur: VRow?, prev: VRow?): VTile.Range {
        val eps = VitalsSpecs.of(id).eps
        val dir = if (cur != null && prev != null) {
            val dl = cur.lo - prev.lo; val dh = cur.hi - prev.hi
            val up = dl > eps / 2 + 1e-9 || dh > eps / 2 + 1e-9; val dn = dl < -eps / 2 - 1e-9 || dh < -eps / 2 - 1e-9
            when { up && !dn -> TrendDir.UP; dn && !up -> TrendDir.DOWN; else -> TrendDir.FLAT }
        } else null
        return VTile.Range(id, cur, prev, dir, emptyList())
    }

    /** The six facts per IMPL_PLAN §3V (always 6, fixed slots). */
    fun tiles(data: VitalsData, f: VFrame): List<VTile> {
        val today = f.today
        val day = f.day
        if (day != null) {
            val out = ArrayList<VTile>()
            for (id in VITALS_IDS) {
                val s = data.s(id)
                if (s[day] == null) { out += VTile.Carried(id, lastOnOrBefore(s, day)); continue }
                val dec = VitalsSpecs.of(id).decimals
                val r = R3Math.readingChange(id, s, SectionPeriod.Day(day.toEpochDay()), day, today, ReadingBasis.PREVIOUS_READING, dec, 0.5 * 10.0.pow(-dec), null)
                out += VTile.Change(id, r, spark7(s, day))
            }
            val a = data.hrMin[day]; val b = data.hrMax[day]
            out += VTile.Range("hr", if (a != null && b != null) VRow(a, b) else null, null, null, spark7(data.hrMax, day))
            out += dayRecord(data, day) ?: VTile.Carried("rhr", null)
            return out
        }
        if (f.period is SectionPeriod.All) {
            val rhr = data.s("rhr"); val hrv = data.s("hrv")
            val c = R3Math.readingChange("rhr", rhr, f.period, null, today, ReadingBasis.FIRST_EVER, 0, 0.5, null)
            val t1: VTile = if (c.diff != null) VTile.Change("rhr", c, monthlyLast(rhr, today)) else VTile.Carried("rhr", c.current)
            val t2: VTile = R3Math.allTimeExtreme(hrv, low = false)?.let { VTile.Record("hrv", false, it, null, monthlyLast(hrv, today)) } ?: VTile.Carried("hrv", null)
            val all = listOf("breathing", "spo2").map { id -> val s = data.s(id)
                VTile.Range(id, rangeOf(s, s.keys.minOrNull() ?: today, today), null, null, monthlyLast(s, today)) }
            val hr = hrRange(data.hrMin, data.hrMax, data.dataStart, today)?.let { VRow(it.first.value, it.second.value) }
            val t6: VTile = R3Math.allTimeExtreme(rhr, low = true)?.let { VTile.Record("rhr", true, it, null, monthlyLast(rhr, today)) } ?: VTile.Carried("rhr", null)
            return listOf(t1, t2) + all + listOf(VTile.Range("hr", hr, null, null, emptyList()), t6)
        }
        // W / M / Y
        val p = f.period
        val q = p.previous()
        val out = ArrayList<VTile>()
        for (id in VITALS_IDS) {
            val s = data.s(id)
            out += rangeTile(id, rangeOf(s, p.start, f.end), q?.let { rangeOf(s, it.start, it.last) })
        }
        val hc = hrRange(data.hrMin, data.hrMax, p.start, f.end)?.let { VRow(it.first.value, it.second.value) }
        out += rangeTile("hr", hc, q?.let { prevHrBand(data.hrMin, data.hrMax, f) })
        out += periodRecord(data, f) ?: outsideTile(data, f)
        return out
    }

    // ------------------------------------------------------------------ Heart rate card

    /** 288 five-minute maxima of a day's minute samples (null = no sample in those 5 minutes). */
    fun fiveMinuteMax(h: HrDay): List<Double?> = (0 until 288).map { i ->
        var m = 0
        for (k in i * 5 until i * 5 + 5) m = maxOf(m, h.at(k))
        if (m > 0) m.toDouble() else null
    }

    private fun minuteOf(h: HrDay, v: Double?): LocalTime? {
        v ?: return null
        val t = Math.round(v).toInt()
        val m = (0 until 1440).firstOrNull { h.at(it) == t } ?: return null
        return LocalTime.of(m / 60, m % 60)
    }

    fun hrCard(data: VitalsData, f: VFrame): HrCardFacts {
        val today = f.today
        val rhr = lastOnOrBefore(data.s("rhr"), minOf(f.day ?: f.end, today))
        val day = f.day
        if (day != null) {
            val lo = data.hrMin[day]?.let { Extreme(it, day) }; val hi = data.hrMax[day]?.let { Extreme(it, day) }
            val h = data.hrDay?.takeIf { it.day == day }
            val latest = if (h != null && day == today) (1439 downTo 0).firstOrNull { h.at(it) > 0 }?.let { LocalTime.of(it / 60, it % 60) to h.at(it) } else null
            // times only when the minute samples hold exactly the day's lowest / highest (else no time: nothing to show honestly)
            return HrCardFacts(lo, hi, null, latest, h?.let { minuteOf(it, lo?.value) }, h?.let { minuteOf(it, hi?.value) }, h?.let { fiveMinuteMax(it) }, rhr)
        }
        val r = hrRange(data.hrMin, data.hrMax, f.period.start, f.end)
        return HrCardFacts(r?.first, r?.second, prevHrBand(data.hrMin, data.hrMax, f), null, null, null, null, rhr)
    }
}

/** All facts of one frame, computed once (remembered by the UI per scope). */
class VitalsModel(val data: VitalsData, val f: VFrame) {
    val today: LocalDate get() = f.today
    /** Hero lanes: the frame period (1D: the 7-day window), the selected day marked. */
    val laneDomain: Domain by lazy { VitalsMath.domain(f, window = f.period is SectionPeriod.Day) }
    /** Cards: the 7-day window on a day scope, else the period. */
    val cardDomain: Domain by lazy { VitalsMath.domain(f, window = f.dayScope) }
    val spans: List<RangeSpan> by lazy { VitalsMath.rangeSpans(data.s("rhr"), f) }
    val tiles: List<VTile> by lazy { VitalsMath.tiles(data, f) }
    val hrCard: HrCardFacts by lazy { VitalsMath.hrCard(data, f) }
    private val readings = HashMap<String, VReading>()
    private val pills = HashMap<String, VPill?>()
    fun reading(id: String): VReading = synchronized(readings) { readings.getOrPut(id) { VitalsMath.reading(data.s(id), f) } }
    fun pill(id: String): VPill? = synchronized(pills) { if (id in pills) pills[id] else VitalsMath.pill(id, data.s(id), f).also { pills[id] = it } }
    /** The grey band of the lanes (W/M/Y frame, also with a selected day). */
    fun laneBand(id: String): VRow? = if (f.period is SectionPeriod.Day) null else VitalsMath.prevBand(data.s(id), f)
    /** The grey band of a card (W/M/Y without a selected day). */
    fun cardBand(id: String): VRow? = if (f.ranged) VitalsMath.prevBand(data.s(id), f) else null
    fun ringed(id: String): Set<LocalDate> = synchronized(ringedCache) { ringedCache.getOrPut(id) { VitalsMath.ringed(data.s(id), f) } }
    private val ringedCache = HashMap<String, Set<LocalDate>>()

    /** Computes every lazy fact once (call on IO, QA R3: keeps the stats off the main thread). */
    fun warm() {
        laneDomain; cardDomain; spans; tiles; hrCard
        for (id in listOf("rhr", "hrv", "breathing", "spo2")) { reading(id); pill(id); ringed(id) }
    }
}
