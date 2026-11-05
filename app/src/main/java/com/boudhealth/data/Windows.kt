package com.boudhealth.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/*
 * Ranges, windows, chart points and bucketing: pure Kotlin (no Android), moved out of History.kt unchanged except
 * Window.of(ALL), which now starts at ALL_START (2000-01-01) instead of 12 years ago, so "All" really is all history.
 */

/** Oldest day the "All" range (and the history backfill) looks at. */
val ALL_START: LocalDate = LocalDate.of(2000, 1, 1)

/** Time ranges for the detail screens. Longer ranges are bucketed so charts stay readable. */
enum class Range(val label: String, val days: Long) {
    DAY("1D", 1), WEEK("1W", 7), MONTH("1M", 30), QUARTER("3M", 91), YEAR("1Y", 365), ALL("All", 365L * 12);

    /** Bucket length: hour for a day, day up to 3 months, week for a year, month for all. */
    val bucket: Period get() = when (this) { DAY, WEEK, MONTH, QUARTER -> Period.ofDays(1); YEAR -> Period.ofWeeks(1); ALL -> Period.ofMonths(1) }
    val hourly: Boolean get() = this == DAY
    val bucketLabel: String get() = when (this) {
        DAY -> "per hour"; WEEK, MONTH, QUARTER -> "per day"; YEAR -> "daily average per week"; ALL -> "daily average per month"
    }
}

/** The period shown on a detail screen: a range, stepped back [offset] times with ‹ ›. [end] is exclusive. */
data class Window(val range: Range, val offset: Int, val start: LocalDate, val end: LocalDate) {
    val isCurrent: Boolean get() = offset == 0
    val label: String get() {
        val f = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
        val fy = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
        val last = end.minusDays(1)
        return when {
            range == Range.ALL -> "All time"
            range == Range.DAY -> if (offset == 0) "Today" else if (offset == 1) "Yesterday" else fy.format(start)
            // "Last 7 days", not "Last 1w"
            offset == 0 -> "Last ${when (range) { Range.WEEK -> "7 days"; Range.MONTH -> "30 days"; Range.QUARTER -> "3 months"; else -> "12 months" }} · ${f.format(start)} – today"
            else -> "${(if (start.year != last.year) fy else f).format(start)} – ${fy.format(last)}"
        }
    }

    companion object {
        fun of(range: Range, offset: Int, zone: ZoneId = ZoneId.systemDefault()): Window {
            val today = LocalDate.now(zone)
            if (range == Range.ALL) return Window(range, 0, ALL_START, today.plusDays(1))
            val end = today.plusDays(1).minusDays(range.days * offset)
            return Window(range, offset, end.minusDays(range.days), end)
        }
    }
}

/** One point on a chart: bucket start and value. */
data class Pt(val at: LocalDateTime, val v: Double) {
    constructor(date: LocalDate, v: Double) : this(date.atStartOfDay(), v)
    val date: LocalDate get() = at.toLocalDate()
}

/** On-demand reads for the detail screens (foreground only). Same de-duplication rules as the dashboard ([Sources]). */

/** How daily values are combined into a week / month bucket. */
enum class Agg {
    /** Daily totals (steps, kcal, minutes): average per day of the bucket (days up to today), as "daily average per week". */
    SUM_PER_DAY,
    /** Raw total of the bucket (e.g. workouts per week). */
    TOTAL,
    /** Average of the days that have a value. */
    AVG,
    MIN,
    MAX,
    /** Last value in the bucket. */
    LAST,
}

/** Bucketing of daily values for a [Window] (shared by the detail screens and the metric registry). */
object Buckets {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Bucket starts of [w] (days, weeks or months), not past today. For ALL, starts at [dataStart] if given. */
    fun starts(w: Window, bucket: Period = w.range.bucket, dataStart: LocalDate? = null): List<LocalDate> {
        val today = LocalDate.now(zone)
        var first = w.start
        if (w.range == Range.ALL && dataStart != null && dataStart.isAfter(first)) {
            first = if (bucket.months > 0) dataStart.withDayOfMonth(1) else if (bucket.days >= 7) dataStart.minusDays(((dataStart.dayOfWeek.value - 1)).toLong()) else dataStart
        }
        return generateSequence(first) { it.plus(bucket) }.takeWhile { it.isBefore(w.end) && !it.isAfter(today) }.toList()
    }

    /** Days a bucket covers inside the window and up to today (so a running week/month is averaged correctly). */
    fun days(start: LocalDate, bucket: Period, w: Window): Double {
        val stop = listOf(start.plus(bucket), w.end, LocalDate.now(zone).plusDays(1)).minOrNull()!!
        return ChronoUnit.DAYS.between(start, stop).coerceAtLeast(1).toDouble()
    }

    /**
     * Daily values → one point per bucket that has data. Daily buckets return the values as they are.
     * [fillZero]: also emit 0 for buckets without data (bar charts of minutes).
     */
    fun of(daily: List<Pair<LocalDate, Double>>, w: Window, agg: Agg, bucket: Period = w.range.bucket, fillZero: Boolean = false): List<Pt> {
        val starts = starts(w, bucket, daily.minOfOrNull { it.first })
        if (starts.isEmpty()) return emptyList()
        val out = ArrayList<Pt>()
        var i = 0
        val sorted = daily.sortedBy { it.first }
        for ((k, s) in starts.withIndex()) {
            val e = if (k + 1 < starts.size) starts[k + 1] else s.plus(bucket)
            val vals = ArrayList<Double>()
            while (i < sorted.size && sorted[i].first.isBefore(s)) i++
            var j = i
            while (j < sorted.size && sorted[j].first.isBefore(e)) { vals += sorted[j].second; j++ }
            i = j
            if (vals.isEmpty()) { if (fillZero) out += Pt(s, 0.0); continue }
            val v = when (agg) {
                Agg.SUM_PER_DAY -> vals.sum() / days(s, bucket, w)
                Agg.TOTAL -> vals.sum()
                Agg.AVG -> vals.average()
                Agg.MIN -> vals.min()
                Agg.MAX -> vals.max()
                Agg.LAST -> vals.last()
            }
            out += Pt(s, v)
        }
        return out
    }
}

/**
 * R6 item 1 (owner, 5 Nov 2026): 1D on the page of a metric with one value per day / night / weigh-in shows that day's
 * value (or, muted, the newest one before it) and a chart of the 7 days ending on the day, the same window the
 * Activity / Body / Vitals tabs use on 1D. Pure, so the window logic is unit-tested.
 */
object DayView {
    /** Pages whose 1D is "the day's value + 7 days" (intraday metrics hr, steps, calories, zone_min are not here). */
    val ONE_VALUE_IDS: Set<String> = setOf(
        "rhr", "hrv", "sleep", "breathing", "spo2", "vo2",
        "weight", "body_fat", "fat_mass", "lean_mass", "lean_fat", "bmi", "waist", "whtr",
        "exercise_min", "week_workouts", "streak",
    )

    /** Days in the 1D chart window (the day and the 6 before it). */
    const val SPAN = 7

    fun isOneValue(id: String): Boolean = id in ONE_VALUE_IDS

    /** First day of the 7-day window ending on [day]. */
    fun windowStart(day: LocalDate): LocalDate = day.minusDays((SPAN - 1).toLong())

    /** The 7 days ending on [day], oldest first. */
    fun days(day: LocalDate): List<LocalDate> = (SPAN - 1 downTo 0).map { day.minusDays(it.toLong()) }

    /**
     * Chart window of a 1D window [w]: the 7 days ending on its day (end exclusive), as a WEEK-range window with the
     * same offset (so charts draw days, not hours). Other ranges are returned unchanged.
     */
    fun chartWindow(w: Window): Window =
        if (w.range != Range.DAY) w else Window(Range.WEEK, w.offset, windowStart(w.start), w.start.plusDays(1))

    /** The value shown on 1D: [pt] on [day] itself, or the newest one before it ([carried] = from an earlier day). */
    data class Pick(val pt: Pt, val carried: Boolean)

    /**
     * The 1D value for [day] from the window's daily values [window] (any order; values after [day] are ignored).
     * When the window has none, [older] is asked for the newest value before the window (null = none at all).
     */
    fun pick(window: List<Pt>, day: LocalDate, older: () -> Pt? = { null }): Pick? {
        val p = window.filter { !it.date.isAfter(day) }.maxByOrNull { it.at } ?: older()?.takeIf { !it.date.isAfter(day) } ?: return null
        return Pick(p, p.date != day)
    }

    /** The newest [n] reading days on or before [day] (oldest first): sparse metrics (waist) on 1D, as the Body tab card. */
    fun lastReadings(dates: List<LocalDate>, day: LocalDate, n: Int = 8): List<LocalDate> =
        dates.filter { !it.isAfter(day) }.distinct().sorted().takeLast(n)
}
