package com.boudhealth.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/*
 * Health BB R3 (Activity / Body / Vitals tabs): the NO-AVERAGES stats API (IMPL_PLAN §2.6). PURE (no android.*),
 * JVM-tested in tools/unit/tests/R3StatsTest.kt. Every function works on per-day maps from V2Engine.daily (plausible
 * values only). No averaging or fitted lines of any kind. Changes are computed from values ROUNDED to the displayed
 * decimals ([R3Math.round], HALF_UP on the exact double = what String.format / SFmt / R3Texts print), so a pill, a tile
 * and a table can never disagree.
 *
 * Shared value types used by the UI live here (pure) and are re-exported in com.boudhealth.ui.section by typealiases
 * (TileIcon, Tone, Ghost, WeekColumnCell, YearRingCell). Import them explicitly (never star-import both packages).
 */

// ============================================================================ shared value types

/** Status icon of a Trends tile / week cell: ▲ ▼ ● ✓, or a neutral metric glyph for a plain value. */
enum class TileIcon { UP, DOWN, SAME, DONE, GLYPH }

/** Icon / pill colour: GOOD = goodCont/good, BAD = badCont/bad, NEUTRAL = tile/muted. */
enum class Tone { GOOD, BAD, NEUTRAL }

/**
 * Previous-period ghost behind a chart, aligned to the CURRENT buckets by index ([values][i] = previous bucket i; null =
 * no data). [segments] = stacked parts per bucket (heart points zones), same alignment.
 */
data class Ghost(val values: List<Double?>, val segments: List<DoubleArray?>? = null)

/** One row of the "/ week" column beside the ring calendar: icon + bar of [points] vs [target] + total. */
data class WeekColumnCell(val monday: LocalDate, val icon: TileIcon, val tone: Tone, val points: Int, val target: Int)

/** One year of the All hero row: ring progress (outer → inner) and the year's totals (workout HOURS, steps, points). */
data class YearRingCell(val year: Int, val progress: FloatArray, val hours: Double, val steps: Double, val points: Double) {
    override fun equals(other: Any?): Boolean = other is YearRingCell && other.year == year && other.progress.contentEquals(progress) &&
        other.hours == hours && other.steps == steps && other.points == points
    override fun hashCode(): Int = year * 31 + progress.contentHashCode()
}

// ---------------------------------------------------------------------------- comparison windows (totals)

/**
 * The window a total is compared on. [curTo] = now (running period) or the period end. The previous side is
 * [prevFrom]..[prevTo], cut at the same elapsed point AND clock time while the period runs ([cut] non-null → "until
 * hh:mm"). [wholeDays] = the hourly data of the cut day is missing: partial days are excluded on BOTH sides and the
 * header drops "until hh:mm" (O3).
 */
data class CompareWindow(
    val period: SectionPeriod,
    val prevPeriod: SectionPeriod,
    val curTo: LocalDateTime,
    val prevFrom: LocalDate,
    val prevTo: LocalDateTime,
    val cut: LocalTime?,
    val wholeDays: Boolean,
) {
    /** The previous side's last (cut) day: prevTo's date while running, else the previous period's last day. */
    val prevEndDay: LocalDate get() = if (cut != null) prevTo.toLocalDate() else prevPeriod.last
}

enum class ChangeForm { ABSOLUTE /*1D*/, PERCENT /*1W–1Y*/ }

/** A total vs the previous comparable window. [diff] / [pct] from the ROUNDED totals; [good] null = FLAT or neutral metric. */
data class TotalChange(
    val metricId: String, val current: Double, val previous: Double, val diff: Double,
    val pct: Double?, val dir: TrendDir, val form: ChangeForm, val good: Boolean?, val window: CompareWindow,
)

// ---------------------------------------------------------------------------- readings

data class Extreme(val value: Double, val day: LocalDate)

/**
 * A reading vs its reference. [carried] = no reading in the period / on the day: [current] is the last earlier one
 * (W/M/Y: [diff] = null, no pill and no tile change). [diff] is rounded to the metric's decimals.
 */
data class ReadingChange(
    val metricId: String, val current: Extreme?, val carried: Boolean,
    val reference: Extreme?, val diff: Double?, val dir: TrendDir?, val good: Boolean?,
)

enum class ReadingBasis {
    /** Day mode: the reading on / before the day vs the reading before it. */
    PREVIOUS_READING,
    /** W/M/Y: the last reading in the period vs the last reading before the period. */
    LAST_BEFORE_PERIOD,
    /** All: the latest reading vs the first reading ever. */
    FIRST_EVER,
    /** Body 1D T1: the day's reading vs the reading exactly 7 days before; falls back to PREVIOUS_READING. */
    SAME_WEEKDAY_LAST_WEEK,
    /** VO₂ max: the latest reading vs the last reading on / before (its day − months). */
    MONTHS_AGO,
    /** VO₂ 1Y tile: last vs first reading in the period. */
    FIRST_IN_PERIOD,
}

// ---------------------------------------------------------------------------- ranges

/** Lowest / highest in a range; ties → the latest day for both ends. [n] = readings. */
data class MinMax(val lo: Extreme, val hi: Extreme, val n: Int)

/** Days of a period above / below the FULL previous period's range. */
data class Outside(val above: Set<LocalDate>, val below: Set<LocalDate>, val prevRange: MinMax?) {
    val days: Int get() = (above + below).size
    val dir: TrendDir get() = when {
        above.isNotEmpty() && below.isEmpty() -> TrendDir.UP
        below.isNotEmpty() && above.isEmpty() -> TrendDir.DOWN
        else -> TrendDir.FLAT
    }
}

/** One month: its last reading and its lowest–highest. */
data class MonthPoint(val month: YearMonth, val last: Extreme, val range: MinMax)

// ---------------------------------------------------------------------------- records

/** The period's record [at] is the lowest ([low]) / highest since [lastAtLeast] (the last earlier day at least as low / high; null = ever). */
data class SinceRecord(val metricId: String, val at: Extreme, val low: Boolean, val lastAtLeast: LocalDate?)

/** Best bucket (day / Mon–Sun week / month / year) by its total; [start] = the bucket's first day. */
data class Best(val start: LocalDate, val value: Double)

// ---------------------------------------------------------------------------- streaks, goals, weeks

enum class GoalState { DONE, AHEAD, ON_PACE, BEHIND, MISSED }

/** Goals tile: [count] of 3; per ring state; [expectedShare] = elapsed share (running) for the tick, else null. */
data class GoalsFact(val count: Int, val icon: TileIcon, val tone: Tone, val perRing: List<GoalState>, val expectedShare: Double?)

data class WeekFlag(val monday: LocalDate, val met: Boolean)

/** A complete week's lowest vs the week before's lowest; [dir] DOWN = lower by ≥ 0.1 (rounded 1 dp). */
data class WeekLow(val monday: LocalDate, val low: Extreme, val prevLow: Extreme?, val dir: TrendDir?)

/** First → last reading; [perMonth] = (last − first) ÷ (days ÷ 30.4375). Two points, no fit. */
data class Chord(val first: Extreme, val last: Extreme, val perMonth: Double)

/** Earliest / latest bedtime of a set of nights, ordered as minutes after 18:00 (so 23:40 < 00:20). */
data class Bedtimes(val earliest: LocalTime, val latest: LocalTime)

data class PeakHour(val hour: Int, val value: Double)

// ============================================================================ R3Math (pure)

object R3Math {
    /** Percent changes below this (absolute, in %) are FLAT ("●"). */
    const val FLAT_PCT = 2.0
    private const val EPS = 1e-9

    /** Value rounded as it is displayed (HALF_UP on the exact double, = String.format("%.nf") / SFmt / R3Texts). */
    fun round(v: Double, decimals: Int): Double =
        if (v.isNaN() || v.isInfinite()) v else BigDecimal(v).setScale(decimals.coerceAtLeast(0), RoundingMode.HALF_UP).toDouble()

    // ------------------------------------------------------------------ windows

    /**
     * The comparison window of [p] at [now] (null for All). Running period: the previous period cut at the same elapsed
     * point and clock time — D: yesterday; W: same weekday last week; M: prev.start + (today − p.start), clamped to
     * prev.last; Y: same month/day last year (29 Feb → 28 Feb). Past period: the FULL previous period.
     * [CompareWindow.wholeDays] is always false here; [V2Engine.totalChange] sets it when the cut day has no hourly data.
     */
    fun window(p: SectionPeriod, now: LocalDateTime): CompareWindow? {
        val prev = p.previous() ?: return null
        val today = now.toLocalDate()
        if (!p.isCurrent(today)) {
            val curTo = if (p.endExclusive.isAfter(today)) now else p.endExclusive.atStartOfDay()
            return CompareWindow(p, prev, curTo, prev.start, prev.endExclusive.atStartOfDay(), null, false)
        }
        val endDay: LocalDate = when (p) {
            is SectionPeriod.Day -> prev.start
            is SectionPeriod.Week -> prev.start.plusDays(ChronoUnit.DAYS.between(p.start, today))
            is SectionPeriod.Month -> minOf(prev.start.plusDays(ChronoUnit.DAYS.between(p.start, today)), prev.last)
            is SectionPeriod.Year -> {
                val y = prev.start.year
                val dom = if (today.monthValue == 2 && today.dayOfMonth == 29) 28 else today.dayOfMonth
                LocalDate.of(y, today.monthValue, minOf(dom, YearMonth.of(y, today.monthValue).lengthOfMonth()))
            }
            is SectionPeriod.All -> return null
        }
        val cut = now.toLocalTime().withSecond(0).withNano(0)
        return CompareWindow(p, prev, now, prev.start, endDay.atTime(cut), cut, false)
    }

    /**
     * [cur] vs [prev] (both rounded to integers first: totals are shown without decimals). PERCENT: FLAT when |pct| < 2
     * (prev = 0: UP when cur > 0, else FLAT, pct null). ABSOLUTE: FLAT when the rounded diff is 0. [good] by
     * [higherIsBetter] (null = neutral metric), null when FLAT.
     */
    fun totalChange(metricId: String, cur: Double, prev: Double, form: ChangeForm, higherIsBetter: Boolean?, w: CompareWindow): TotalChange {
        val c = round(cur, 0); val pv = round(prev, 0)
        val diff = c - pv
        val pct = if (pv != 0.0) diff / pv * 100.0 else null
        val dir = when (form) {
            ChangeForm.PERCENT -> when {
                pct == null -> if (c > 0) TrendDir.UP else TrendDir.FLAT
                Math.abs(pct) < FLAT_PCT -> TrendDir.FLAT
                pct > 0 -> TrendDir.UP
                else -> TrendDir.DOWN
            }
            ChangeForm.ABSOLUTE -> when {
                diff == 0.0 -> TrendDir.FLAT
                diff > 0 -> TrendDir.UP
                else -> TrendDir.DOWN
            }
        }
        return TotalChange(metricId, c, pv, diff, pct, dir, form, goodOf(dir, higherIsBetter), w)
    }

    fun goodOf(dir: TrendDir?, higherIsBetter: Boolean?): Boolean? =
        if (dir == null || dir == TrendDir.FLAT || higherIsBetter == null) null else (dir == TrendDir.UP) == higherIsBetter

    /** Tone of a direction for a polarised metric (FLAT / neutral metric → NEUTRAL). */
    fun toneOf(dir: TrendDir?, higherIsBetter: Boolean?): Tone = when (goodOf(dir, higherIsBetter)) {
        true -> Tone.GOOD; false -> Tone.BAD; null -> Tone.NEUTRAL
    }

    fun iconOf(dir: TrendDir?): TileIcon = when (dir) { TrendDir.UP -> TileIcon.UP; TrendDir.DOWN -> TileIcon.DOWN; else -> TileIcon.SAME }

    // ------------------------------------------------------------------ readings

    private fun lastOnOrBefore(s: Map<LocalDate, Double>, d: LocalDate): Extreme? =
        s.entries.filter { !it.key.isAfter(d) }.maxByOrNull { it.key }?.let { Extreme(it.value, it.key) }

    private fun lastBefore(s: Map<LocalDate, Double>, d: LocalDate): Extreme? =
        s.entries.filter { it.key.isBefore(d) }.maxByOrNull { it.key }?.let { Extreme(it.value, it.key) }

    private fun inRange(s: Map<LocalDate, Double>, a: LocalDate, b: LocalDate) =
        s.entries.filter { !it.key.isBefore(a) && !it.key.isAfter(b) }.sortedBy { it.key }

    /**
     * A reading vs its reference by [basis] (see [ReadingBasis]); [day] = the day in day mode (null → the period's last
     * day, capped at [today]). Both values are rounded to [decimals] before subtracting; FLAT when |diff| < [flat].
     * W/M/Y with no reading in the period: current = the last earlier reading, carried, diff = null.
     */
    fun readingChange(series: Map<LocalDate, Double>, p: SectionPeriod, day: LocalDate?, today: LocalDate,
                      basis: ReadingBasis, decimals: Int, flat: Double, higherIsBetter: Boolean?, months: Long = 3): ReadingChange {
        val id = ""
        val end = minOf(p.last, today)
        fun make(cur: Extreme?, carried: Boolean, ref: Extreme?, withDiff: Boolean = true): ReadingChange {
            if (cur == null) return ReadingChange(id, null, false, null, null, null, null)
            if (ref == null || !withDiff) return ReadingChange(id, cur, carried, ref, null, null, null)
            val d = round(round(cur.value, decimals) - round(ref.value, decimals), decimals)
            val dir = when { Math.abs(d) < flat - EPS -> TrendDir.FLAT; d > 0 -> TrendDir.UP; else -> TrendDir.DOWN }
            return ReadingChange(id, cur, carried, ref, d, dir, goodOf(dir, higherIsBetter))
        }
        return when (basis) {
            ReadingBasis.PREVIOUS_READING -> {
                val d = minOf(day ?: end, today)
                val cur = lastOnOrBefore(series, d)
                make(cur, cur != null && cur.day != d, cur?.let { lastBefore(series, it.day) })
            }
            ReadingBasis.SAME_WEEKDAY_LAST_WEEK -> {
                val d = minOf(day ?: end, today)
                val v = series[d]; val w = series[d.minusDays(7)]
                if (v != null && w != null) make(Extreme(v, d), false, Extreme(w, d.minusDays(7)))
                else readingChange(series, p, d, today, ReadingBasis.PREVIOUS_READING, decimals, flat, higherIsBetter, months)
            }
            ReadingBasis.LAST_BEFORE_PERIOD -> {
                val pts = inRange(series, p.start, end)
                if (pts.isEmpty()) make(lastOnOrBefore(series, end), true, null, withDiff = false)
                else make(Extreme(pts.last().value, pts.last().key), false, lastBefore(series, p.start))
            }
            ReadingBasis.FIRST_EVER -> {
                val cur = lastOnOrBefore(series, minOf(end, today))
                val first = series.entries.minByOrNull { it.key }?.let { Extreme(it.value, it.key) }
                make(cur, false, first?.takeIf { cur != null && it.day.isBefore(cur.day) })
            }
            ReadingBasis.MONTHS_AGO -> {
                val cur = lastOnOrBefore(series, minOf(day ?: end, today))
                make(cur, cur != null && day != null && cur.day != day, cur?.let { lastOnOrBefore(series, it.day.minusMonths(months)) })
            }
            ReadingBasis.FIRST_IN_PERIOD -> {
                val pts = inRange(series, p.start, end)
                if (pts.isEmpty()) make(lastOnOrBefore(series, end), true, null, withDiff = false)
                else make(Extreme(pts.last().value, pts.last().key), false,
                    pts.first().takeIf { pts.size > 1 }?.let { Extreme(it.value, it.key) })
            }
        }
    }

    /** [readingChange] with the metric id filled in. */
    fun readingChange(metricId: String, series: Map<LocalDate, Double>, p: SectionPeriod, day: LocalDate?, today: LocalDate,
                      basis: ReadingBasis, decimals: Int, flat: Double, higherIsBetter: Boolean?, months: Long = 3): ReadingChange =
        readingChange(series, p, day, today, basis, decimals, flat, higherIsBetter, months).copy(metricId = metricId)

    // ------------------------------------------------------------------ ranges

    /** Lowest / highest in [from]..[to]; ties → the latest day for both ends. null without readings. */
    fun minMax(series: Map<LocalDate, Double>, from: LocalDate, to: LocalDate): MinMax? {
        val pts = inRange(series, from, to)
        if (pts.isEmpty()) return null
        var lo = pts[0]; var hi = pts[0]
        for (e in pts) {           // ascending days: ≤ / ≥ keeps the LATEST of equal values
            if (e.value <= lo.value) lo = e
            if (e.value >= hi.value) hi = e
        }
        return MinMax(Extreme(lo.value, lo.key), Extreme(hi.value, hi.key), pts.size)
    }

    /** UP: an end moved up by more than eps/2 and none down; DOWN the mirror; else FLAT. */
    fun rangeDir(cur: MinMax, prev: MinMax, eps: Double): TrendDir {
        val dl = cur.lo.value - prev.lo.value; val dh = cur.hi.value - prev.hi.value
        val up = dl > eps / 2 + EPS || dh > eps / 2 + EPS
        val dn = dl < -eps / 2 - EPS || dh < -eps / 2 - EPS
        return when { up && !dn -> TrendDir.UP; dn && !up -> TrendDir.DOWN; else -> TrendDir.FLAT }
    }

    /** Days of [p] (up to [today]) above / below the FULL previous period's lowest–highest. Day / All: none. */
    fun outsidePrevious(series: Map<LocalDate, Double>, p: SectionPeriod, today: LocalDate): Outside {
        if (p is SectionPeriod.Day || p is SectionPeriod.All) return Outside(emptySet(), emptySet(), null)
        val prev = p.previous() ?: return Outside(emptySet(), emptySet(), null)
        val pr = minMax(series, prev.start, prev.last) ?: return Outside(emptySet(), emptySet(), null)
        val cur = inRange(series, p.start, minOf(p.last, today))
        return Outside(cur.filter { it.value > pr.hi.value }.map { it.key }.toSet(), cur.filter { it.value < pr.lo.value }.map { it.key }.toSet(), pr)
    }

    /** Union over several metrics (the 4 vitals): a day counts once; above/below per day from any metric. */
    fun outsideAny(series: List<Map<LocalDate, Double>>, p: SectionPeriod, today: LocalDate): Outside {
        val all = series.map { outsidePrevious(it, p, today) }
        return Outside(all.flatMap { it.above }.toSet(), all.flatMap { it.below }.toSet(), null)
    }

    /** Per calendar month in [from]..[to]: last reading + lowest–highest, oldest first (months without readings skipped). */
    fun perMonth(series: Map<LocalDate, Double>, from: LocalDate, to: LocalDate): List<MonthPoint> =
        inRange(series, from, to).groupBy { YearMonth.from(it.key) }.toSortedMap().map { (ym, xs) ->
            val mm = minMax(xs.associate { it.key to it.value }, xs.first().key, xs.last().key)!!
            MonthPoint(ym, Extreme(xs.last().value, xs.last().key), mm)
        }

    // ------------------------------------------------------------------ records

    /** The last day before [day] with a value ≤ ([low]) / ≥ the value on [day]; null = never (an all-time record). */
    fun lastAtLeast(series: Map<LocalDate, Double>, day: LocalDate, low: Boolean): LocalDate? {
        val v = series[day] ?: return null
        return lastAtLeastValue(series, day, v, low)
    }

    private fun lastAtLeastValue(series: Map<LocalDate, Double>, before: LocalDate, v: Double, low: Boolean): LocalDate? =
        series.entries.filter { it.key.isBefore(before) && (if (low) it.value <= v else it.value >= v) }.maxOfOrNull { it.key }

    /**
     * The period's lowest ([low]) / highest reading and since when it is a record (the last day BEFORE THE PERIOD with a
     * value at least as low / high). Shown (non-null) only when that day is null (all-time) or ≥ [minBackDays] before
     * p.start. Body: W 56 · M 240 · Vitals: W 56 · M 240 · Y 1,095. Day / All: null.
     */
    fun periodRecord(series: Map<LocalDate, Double>, metricId: String, p: SectionPeriod, today: LocalDate, low: Boolean, minBackDays: Long): SinceRecord? {
        if (p is SectionPeriod.Day || p is SectionPeriod.All) return null
        val mm = minMax(series, p.start, minOf(p.last, today)) ?: return null
        val at = if (low) mm.lo else mm.hi
        val last = lastAtLeastValue(series, p.start, at.value, low)
        if (last != null && ChronoUnit.DAYS.between(last, p.start) < minBackDays) return null
        return SinceRecord(metricId, at, low, last)
    }

    /** Best day / Mon–Sun week / month / year by its total in [from]..[to]; ties → the earliest bucket. null = no positive bucket. */
    fun best(daily: Map<LocalDate, Double>, unit: PeriodUnit, from: LocalDate, to: LocalDate): Best? {
        fun key(d: LocalDate): LocalDate = when (unit) {
            PeriodUnit.DAY -> d
            PeriodUnit.WEEK -> SectionPeriod.mondayOf(d)
            PeriodUnit.MONTH -> d.withDayOfMonth(1)
            PeriodUnit.YEAR, PeriodUnit.ALL -> d.withDayOfYear(1)
        }
        val sums = sortedMapOf<LocalDate, Double>()
        for (e in inRange(daily, from, to)) sums[key(e.key)] = (sums[key(e.key)] ?: 0.0) + e.value
        var best: Best? = null
        for ((k, v) in sums) if (v > 0 && (best == null || v > best.value)) best = Best(k, v)
        return best
    }

    /** Lowest ([low]) / highest reading ever; ties → the latest day. */
    fun allTimeExtreme(series: Map<LocalDate, Double>, low: Boolean): Extreme? {
        if (series.isEmpty()) return null
        val mm = minMax(series, series.keys.min(), series.keys.max()) ?: return null
        return if (low) mm.lo else mm.hi
    }

    // ------------------------------------------------------------------ goals, weeks

    /**
     * Goals tile from the rings. Running: DONE (≥ goal) | AHEAD | ON_PACE | BEHIND by [Pace.status] at [expectedShare];
     * counted = DONE + AHEAD + ON_PACE. Past: DONE | MISSED, counted = DONE. Icon: all DONE → ✓ GOOD; count 3 → ● NEUTRAL;
     * else ▼ BAD.
     */
    fun goalsFact(rings: RingSet, running: Boolean, expectedShare: Double?): GoalsFact {
        val states = rings.rings.map { r ->
            val g = r.goal
            when {
                g == null || g <= 0 -> GoalState.MISSED
                r.value >= g -> GoalState.DONE
                !running -> GoalState.MISSED
                else -> when (Pace.status(r.value, g, expectedShare ?: 1.0)) {
                    CardioPace.DONE -> GoalState.DONE
                    CardioPace.AHEAD -> GoalState.AHEAD
                    CardioPace.BEHIND, CardioPace.NO_DATA -> GoalState.BEHIND
                    else -> GoalState.ON_PACE
                }
            }
        }
        val count = if (running) states.count { it == GoalState.DONE || it == GoalState.AHEAD || it == GoalState.ON_PACE } else states.count { it == GoalState.DONE }
        val (icon, tone) = when {
            states.isNotEmpty() && states.all { it == GoalState.DONE } -> TileIcon.DONE to Tone.GOOD
            count == 3 -> TileIcon.SAME to Tone.NEUTRAL
            else -> TileIcon.DOWN to Tone.BAD
        }
        return GoalsFact(count, icon, tone, states, if (running) expectedShare else null)
    }

    /** Complete Mon–Sun weeks whose SUNDAY lies in [from]..[to] and before [today], met = points ≥ target. */
    fun weeksMet(weekly: List<WeekPoints>, from: LocalDate, to: LocalDate, today: LocalDate): List<WeekFlag> =
        weekly.sortedBy { it.monday }.filter { val sun = it.monday.plusDays(6); !sun.isBefore(from) && !sun.isAfter(to) && sun.isBefore(today) }
            .map { WeekFlag(it.monday, it.points >= it.target) }

    /** The lowest reading of the Mon–Sun week of [monday]; ties → the latest day. */
    fun weekLow(series: Map<LocalDate, Double>, monday: LocalDate): Extreme? = minMax(series, monday, monday.plusDays(6))?.lo

    /**
     * Complete weeks whose THURSDAY lies in [from]..[to] and whose Sunday is before [today], each with the week before's
     * lowest (both needed). DOWN = (low − prevLow) rounded to 1 dp ≤ −[flat]; UP ≥ +[flat]; else FLAT.
     */
    fun weeksDown(series: Map<LocalDate, Double>, from: LocalDate, to: LocalDate, today: LocalDate, flat: Double = 0.1): List<WeekLow> {
        val out = ArrayList<WeekLow>()
        var mon = SectionPeriod.mondayOf(from).minusDays(7)
        while (!mon.isAfter(to)) {
            val thu = mon.plusDays(3); val sun = mon.plusDays(6)
            if (!thu.isBefore(from) && !thu.isAfter(to) && sun.isBefore(today)) {
                val cur = weekLow(series, mon); val prv = weekLow(series, mon.minusDays(7))
                if (cur != null && prv != null) {
                    val d = round(cur.value - prv.value, 1)
                    val dir = when { Math.abs(d) < flat - EPS -> TrendDir.FLAT; d < 0 -> TrendDir.DOWN; else -> TrendDir.UP }
                    out += WeekLow(mon, cur, prv, dir)
                }
            }
            mon = mon.plusDays(7)
        }
        return out
    }

    /** First → last reading in [from]..[to] and the change per month between them (null with < 2 readings). */
    fun chord(series: Map<LocalDate, Double>, from: LocalDate, to: LocalDate): Chord? {
        val pts = inRange(series, from, to)
        if (pts.size < 2) return null
        val f = pts.first(); val l = pts.last()
        val months = ChronoUnit.DAYS.between(f.key, l.key) / 30.4375
        if (months <= 0.0) return null
        return Chord(Extreme(f.value, f.key), Extreme(l.value, l.key), (l.value - f.value) / months)
    }

    /** Minutes after 18:00 of a clock time (so evening and after-midnight bedtimes order correctly). */
    fun minutesAfter18(t: LocalTime): Int { val m = t.hour * 60 + t.minute; return if (m >= 18 * 60) m - 18 * 60 else m + 6 * 60 }

    /** Earliest / latest bedtime (night start, local time in [zone]); null without nights. */
    fun bedtimes(nights: List<SleepNight>, zone: ZoneId = ZoneId.systemDefault()): Bedtimes? {
        if (nights.isEmpty()) return null
        val ts = nights.map { Instant.ofEpochMilli(it.info.start).atZone(zone).toLocalTime().withSecond(0).withNano(0) }
        return Bedtimes(ts.minBy { minutesAfter18(it) }, ts.maxBy { minutesAfter18(it) })
    }

    /** The hour with the highest value (ties → the earliest); null when no hour has a value > 0. */
    fun peakHour(hourly: List<SeriesBucket>): PeakHour? {
        var best: SeriesBucket? = null
        for (b in hourly.sortedBy { it.start }) {
            val v = b.value ?: continue
            if (v > 0 && (best == null || v > best.value!!)) best = b
        }
        return best?.let { PeakHour(it.start.hour, it.value!!) }
    }

    // ------------------------------------------------------------------ misc helpers shared by the facade

    /** Mondays of the 6 weeks ending with the week of [day] (1W hero, D1-B). */
    fun sixWeeks(day: LocalDate): List<LocalDate> = SectionPeriod.mondayOf(day).let { m -> (5 downTo 0).map { m.minusWeeks(it.toLong()) } }

    /** Mondays of the rows of [ym]'s month grid (Mon–Sun rows touching the month). */
    fun monthWeeks(ym: YearMonth): List<LocalDate> {
        val first = ym.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val last = ym.atEndOfMonth()
        return generateSequence(first) { it.plusWeeks(1) }.takeWhile { !it.isAfter(last) }.toList()
    }
}
