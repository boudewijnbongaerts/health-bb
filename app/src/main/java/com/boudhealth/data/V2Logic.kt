package com.boudhealth.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/*
 * Health BB V2.0, pure data types and maths (no android.*; JVM-tested in scratchpad/datatest/V2Test.kt).
 * Contracts IC1 (periods), IC2 (section data types), IC3 (MetricSections). Env-level reads: V2Engine (pure, on
 * CacheReads) and V2 (Android facade). Semantics per metric kind: V2_PLAN.md §3.1 (normative).
 */

// ============================================================================ IC1 periods

/** The three section tabs. */
enum class SectionId { ACTIVITY, BODY, VITALS }

enum class PeriodUnit(val label: String) { DAY("1D"), WEEK("1W"), MONTH("1M"), YEAR("1Y"), ALL("All") }

/**
 * A calendar period of a section (D8): a day, a Mon–Sun week, a calendar month, a calendar year, or All (first data day
 * → [All.toEpochDay]). [start] inclusive, [endExclusive] exclusive. Serializable for Nav.sections (kept small: ids only).
 */
@Serializable
sealed class SectionPeriod {
    abstract val unit: PeriodUnit
    abstract val start: LocalDate
    abstract val endExclusive: LocalDate

    /** Last day of the period (inclusive). */
    val last: LocalDate get() = endExclusive.minusDays(1)

    @Serializable @SerialName("d")
    data class Day(val epochDay: Long) : SectionPeriod() {
        override val unit: PeriodUnit get() = PeriodUnit.DAY
        override val start: LocalDate get() = LocalDate.ofEpochDay(epochDay)
        override val endExclusive: LocalDate get() = start.plusDays(1)
    }

    /** Mon–Sun; [mondayEpochDay] is normalised to the Monday by [of]. */
    @Serializable @SerialName("w")
    data class Week(val mondayEpochDay: Long) : SectionPeriod() {
        override val unit: PeriodUnit get() = PeriodUnit.WEEK
        override val start: LocalDate get() = LocalDate.ofEpochDay(mondayEpochDay)
        override val endExclusive: LocalDate get() = start.plusDays(7)
    }

    @Serializable @SerialName("m")
    data class Month(val year: Int, val month: Int) : SectionPeriod() {
        override val unit: PeriodUnit get() = PeriodUnit.MONTH
        override val start: LocalDate get() = LocalDate.of(year, month, 1)
        override val endExclusive: LocalDate get() = start.plusMonths(1)
    }

    @Serializable @SerialName("y")
    data class Year(val year: Int) : SectionPeriod() {
        override val unit: PeriodUnit get() = PeriodUnit.YEAR
        override val start: LocalDate get() = LocalDate.of(year, 1, 1)
        override val endExclusive: LocalDate get() = start.plusYears(1)
    }

    /** All data: [fromEpochDay] = first data day, [toEpochDay] = today when built (refreshed by [SectionScope.refreshed]). */
    @Serializable @SerialName("a")
    data class All(val fromEpochDay: Long, val toEpochDay: Long) : SectionPeriod() {
        override val unit: PeriodUnit get() = PeriodUnit.ALL
        override val start: LocalDate get() = LocalDate.ofEpochDay(fromEpochDay)
        override val endExclusive: LocalDate get() = LocalDate.ofEpochDay(maxOf(fromEpochDay, toEpochDay) + 1)
    }

    fun contains(d: LocalDate): Boolean = !d.isBefore(start) && d.isBefore(endExclusive)

    /** The period contains today (it is still running). */
    fun isCurrent(today: LocalDate): Boolean = contains(today)

    /** The period ended before today. */
    fun isPast(today: LocalDate): Boolean = !endExclusive.isAfter(today)

    fun isFuture(today: LocalDate): Boolean = start.isAfter(today)

    fun totalDays(): Int = ChronoUnit.DAYS.between(start, endExclusive).toInt()

    /** Days up to and including today (0 when the period is in the future; [totalDays] when past). */
    fun elapsedDays(today: LocalDate): Int = when {
        today.isBefore(start) -> 0
        !today.isBefore(endExclusive) -> totalDays()
        else -> ChronoUnit.DAYS.between(start, today).toInt() + 1
    }

    /** start..min(last, today), oldest first. */
    fun days(today: LocalDate): List<LocalDate> {
        val end = if (last.isAfter(today)) today else last
        if (end.isBefore(start)) return emptyList()
        return SyncLogic.datesBetween(start, end)
    }

    /** Same unit, [n] periods later (negative = earlier). All returns itself. */
    fun shift(n: Int): SectionPeriod = when (this) {
        is Day -> Day(epochDay + n)
        is Week -> Week(mondayEpochDay + 7L * n)
        is Month -> start.plusMonths(n.toLong()).let { Month(it.year, it.monthValue) }
        is Year -> Year(year + n)
        is All -> this
    }

    /** One period back (same unit); All → null. */
    fun previous(): SectionPeriod? = if (this is All) null else shift(-1)

    companion object {
        fun mondayOf(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        /** The period of [unit] containing [anchor]. All = [dataStart]..[today]. */
        fun of(unit: PeriodUnit, anchor: LocalDate, dataStart: LocalDate, today: LocalDate = anchor): SectionPeriod = when (unit) {
            PeriodUnit.DAY -> Day(anchor.toEpochDay())
            PeriodUnit.WEEK -> Week(mondayOf(anchor).toEpochDay())
            PeriodUnit.MONTH -> Month(anchor.year, anchor.monthValue)
            PeriodUnit.YEAR -> Year(anchor.year)
            PeriodUnit.ALL -> All(minOf(dataStart, today).toEpochDay(), today.toEpochDay())
        }

        fun current(unit: PeriodUnit, today: LocalDate, dataStart: LocalDate): SectionPeriod = of(unit, today, dataStart, today)

        /** Whether ‹ / › may move [p] by [n]: the result must overlap [dataStart, today] (never the future). */
        fun canShift(p: SectionPeriod, n: Int, dataStart: LocalDate, today: LocalDate): Boolean {
            if (p is All || n == 0) return false
            val q = p.shift(n)
            return !q.start.isAfter(today) && !q.last.isBefore(minOf(dataStart, today))
        }
    }
}

/**
 * What a section shows: the chosen [period], or one day picked inside it ([selectedEpochDay], the whole section
 * re-scopes to [effective]). [followToday]: the user is on the current period; it is recomputed at midnight / restore.
 */
@Serializable
data class SectionScope(
    val period: SectionPeriod,
    val selectedEpochDay: Long? = null,
    val followToday: Boolean = true,
) {
    val selectedDay: LocalDate? get() = selectedEpochDay?.let { LocalDate.ofEpochDay(it) }

    /** The period every card, ring and list loads. A selected day counts as a past (or today's) Day. */
    val effective: SectionPeriod get() = selectedEpochDay?.let { SectionPeriod.Day(it) } ?: period

    val unit: PeriodUnit get() = period.unit

    fun select(day: LocalDate): SectionScope = copy(selectedEpochDay = day.toEpochDay(), followToday = false)

    fun clearSelection(today: LocalDate): SectionScope = copy(selectedEpochDay = null, followToday = period.isCurrent(today))

    /**
     * ‹ › / swipe: with a selected day the DAY moves (and the parent period follows when it crosses its border), else
     * the period moves. Clamped to [dataStart, today]; returns this when the move is not allowed.
     */
    fun shift(n: Int, dataStart: LocalDate, today: LocalDate): SectionScope {
        val sel = selectedDay
        if (sel != null) {
            val d = sel.plusDays(n.toLong())
            if (d.isAfter(today) || d.isBefore(minOf(dataStart, today))) return this
            val p = if (period.contains(d)) period else SectionPeriod.of(period.unit, d, dataStart, today)
            return SectionScope(p, d.toEpochDay(), false)
        }
        if (!SectionPeriod.canShift(period, n, dataStart, today)) return this
        val p = period.shift(n)
        return SectionScope(p, null, p.isCurrent(today))
    }

    /** Unit chip tapped: the period of [unit] around the selected day (else around the current period's anchor). */
    fun withUnit(unit: PeriodUnit, dataStart: LocalDate, today: LocalDate): SectionScope {
        val anchor = if (followToday || period.isCurrent(today)) today else minOf(period.last, today)
        val p = SectionPeriod.of(unit, anchor, dataStart, today)
        return SectionScope(p, null, p.isCurrent(today))
    }

    /** After midnight / a restore: a scope that follows today moves to the current period of its unit; All extends to today. */
    fun refreshed(today: LocalDate, dataStart: LocalDate): SectionScope = when {
        followToday -> SectionScope(SectionPeriod.current(period.unit, today, dataStart), null, true)
        period is SectionPeriod.All -> copy(period = SectionPeriod.of(PeriodUnit.ALL, today, dataStart, today))
        else -> this
    }

    companion object {
        /** D15 default: 1D today. */
        fun today(today: LocalDate, dataStart: LocalDate, unit: PeriodUnit = PeriodUnit.DAY): SectionScope =
            SectionScope(SectionPeriod.current(unit, today, dataStart), null, true)
    }
}

// ============================================================================ IC3 metric → section (single home, B34, D2)

object MetricSections {
    /** Card order (V2_PLAN §2.1). Workouts are the Activity hero list ("week_workouts" row in All metrics). */
    val ACTIVITY: List<String> = listOf("exercise_min", "steps", "zone_min", "streak", "calories", "sleep", "vo2")
    val BODY: List<String> = listOf("weight", "body_fat", "lean_mass", "waist", "bmi")
    val VITALS: List<String> = listOf("rhr", "hr", "hrv", "breathing", "spo2")

    /** All metrics list (2.6): Activity 7 + Workouts row; Body 5; Vitals 5. */
    val ALL_METRICS: Map<SectionId, List<String>> = linkedMapOf(
        SectionId.ACTIVITY to ACTIVITY + "week_workouts",
        SectionId.BODY to BODY,
        SectionId.VITALS to VITALS,
    )

    fun of(section: SectionId): List<String> = when (section) { SectionId.ACTIVITY -> ACTIVITY; SectionId.BODY -> BODY; SectionId.VITALS -> VITALS }

    private val ACTIVITY_EXTRA = setOf(
        "today_rings", "weekly_goals", "weekly_cardio", "heart_zones", "last_workout", "yesterday_workouts", "week_workouts",
        "zone_below", "zone_fatburn", "zone_cardio", "zone_peak", "hz1", "hz2", "hz3", "hz4", "hz5", "heart_week",
    )
    private val BODY_EXTRA = setOf("fat_mass", "lean_fat", "whtr", "height", "composition")

    /** The one section a metric (or tile id) belongs to. sleep, vo2 → ACTIVITY (D2). Unknown ids → ACTIVITY. */
    fun primary(metricId: String): SectionId = when {
        metricId in BODY || metricId in BODY_EXTRA -> SectionId.BODY
        metricId in VITALS -> SectionId.VITALS
        else -> SectionId.ACTIVITY
    }

    fun isKnown(metricId: String): Boolean = metricId in ACTIVITY || metricId in BODY || metricId in VITALS ||
        metricId in ACTIVITY_EXTRA || metricId in BODY_EXTRA
}

// ============================================================================ IC2 section data types

/** Graph grain: 1D per hour · 1W/1M per day · 1Y per month · All per month (per quarter beyond 60 months). */
enum class Grain { HOUR, DAY, MONTH, QUARTER }

/** How the headline number is built. */
enum class ValueKind { TOTAL, LAST, RANGE, COUNT }

enum class GoalBasis { DAILY_SCALED, WEEKLY_SCALED, TARGET }

/** Never SUM_PER_DAY in sections (no averages). */
enum class BucketAgg { SUM, LAST, MIN_MAX, COUNT }

/** Metric kinds of the V2 semantics table (§3.1). */
enum class V2Kind { DAILY_TOTAL, DAILY_VALUE, MEASUREMENT, WORKOUT, SLEEP }

data class PeriodGoal(val value: Double, val basis: GoalBasis)

data class PeriodValue(
    val metricId: String,
    val period: SectionPeriod,
    val kind: ValueKind,
    /** TOTAL: sum of elapsed days · LAST: last reading in (or before, [carried]) the period · COUNT: days/sessions · RANGE: latest. */
    val value: Double?,
    /** RANGE (and line cards): lowest / highest daily value in the period. */
    val min: Double?,
    val max: Double?,
    /** LAST / RANGE: day of [value]. */
    val at: LocalDate?,
    /** LAST: the value is from before the period (shown muted with its date, D14). */
    val carried: Boolean = false,
    val daysWithData: Int,
    val daysElapsed: Int,
    /** The period is still running ("so far"). */
    val soFar: Boolean,
    val goal: PeriodGoal?,
    /** value / goal (not capped); null without a goal. */
    val progress: Float?,
)

/**
 * One graph bucket [start, end). [value] = SUM / LAST / COUNT; MIN_MAX: [min]..[max] with [value] = last.
 * [segments] (heart points card): fat burn, cardio, peak MINUTES of the bucket, [value] = their points.
 * [future]: after now (drawn faded); [partial]: contains now. [day]: the bucket's day for DAY grain (tap → select day).
 */
data class SeriesBucket(
    val start: LocalDateTime,
    val end: LocalDateTime,
    val value: Double?,
    val min: Double? = null,
    val max: Double? = null,
    val segments: DoubleArray? = null,
    val future: Boolean = false,
    val partial: Boolean = false,
) {
    val day: LocalDate get() = start.toLocalDate()
}

enum class SeriesGap { NONE, HOURLY_NOT_CACHED, NO_HOURLY_SOURCE }

data class PeriodSeries(
    val metricId: String,
    val period: SectionPeriod,
    val grain: Grain,
    val buckets: List<SeriesBucket>,
    /** Goal line per bucket (daily goal on DAY grain), null = none. */
    val goalPerBucket: Double?,
    /** 1D only: why there is no hourly graph ("Load hours" for HOURLY_NOT_CACHED). */
    val gap: SeriesGap = SeriesGap.NONE,
)

enum class TrendDir { UP, DOWN, FLAT }

/** R3 (IMPL_PLAN §2.7): the daily-value median basis is gone; W/M/Y use [OUTSIDE_PREVIOUS_RANGE] (days outside the previous range). */
enum class TrendBasis { PREVIOUS_SAME_ELAPSED, PREVIOUS_FULL, FIRST_LAST_IN_PERIOD, PREVIOUS_READING, OUTSIDE_PREVIOUS_RANGE }

/**
 * Trend arrow ▲●▼ + delta vs the previous equal period. [change] is shown only for totals and measurements (and daily
 * values on a day: the reading vs the reading before it). Daily values on W/M/Y: direction only, from the days outside
 * the previous period's range ([current] = those days). No averages or medians (R3). [good] = the direction is an
 * improvement (null = neutral metric).
 */
data class PeriodTrend(
    val metricId: String,
    val current: Double?,
    val previous: Double?,
    val change: Double?,
    val pct: Double?,
    val dir: TrendDir,
    val good: Boolean?,
    val basis: TrendBasis,
    /** Measurements: day of [previous] ("since 29 Oct", "since Mar 2024") and of [current]. */
    val previousDay: LocalDate? = null,
    val currentDay: LocalDate? = null,
)

data class PeriodPace(
    val metricId: String,
    val status: CardioPace,
    val actual: Double,
    val expectedByNow: Double,
    val goal: Double,
    val remaining: Double,
    val fraction: Double,
)

/** DAY: 1D / selected day · PERIOD_TOTAL: 1W/1M/1Y · LIFETIME: All (no rings, 3 totals). */
enum class RingMode { DAY, PERIOD_TOTAL, LIFETIME }

/**
 * One ring (outer → inner: workout minutes · steps · heart points), labelled BY ID so a label can never pair with
 * another ring's value (the Today pill bug, V2-111). [valueText] "34" / "7,240" / "29"; [goalText] "50" / "10,000" /
 * "22"; [unit] "min" / "" / "pts". The UI shows "34 / 50 min".
 */
data class PeriodRing(
    val id: String,
    val metricId: String,
    val value: Double,
    val goal: Double?,
    /** value / goal, not capped (> 1 = second lap). 0 without a goal. */
    val progress: Float,
    val valueText: String,
    val goalText: String?,
    val unit: String,
) {
    val closed: Boolean get() = goal != null && progress >= 1f

    companion object {
        const val WORKOUT = "exercise_min"
        const val STEPS = "steps"
        const val HEART = "zone_min"
        val ORDER = listOf(WORKOUT, STEPS, HEART)
    }
}

data class RingSet(
    val period: SectionPeriod,
    val mode: RingMode,
    /** Outer → inner; empty in LIFETIME mode. */
    val rings: List<PeriodRing>,
    /** LIFETIME (All): the 3 totals (workout minutes, steps, heart points) — "1,234 h workouts" etc. */
    val lifetime: List<PeriodRing> = emptyList(),
) {
    fun ring(id: String): PeriodRing? = (rings + lifetime).firstOrNull { it.id == id }
    val allClosed: Boolean get() = rings.isNotEmpty() && rings.all { it.closed }
}

/**
 * Workouts of a period (Activity hero, Workouts sub-page, Streak card). [sessions] newest first, de-duplicated.
 * [activeDays] = workout days by Goals.activeDayRule (D18). [streak] / [bestStreak] as of the period end (or today).
 */
data class PeriodWorkouts(
    val period: SectionPeriod,
    val sessions: List<Workout>,
    val sessionCount: Int,
    /** Workout minutes (union of sessions, overlaps once) in the period. */
    val minutes: Int,
    val activeDays: Set<LocalDate>,
    /** Weekly workout-days goal scaled to the period (1W = goal; 1M = goal × days ÷ 7); null on 1D. */
    val activeDaysGoal: Int?,
    /** (kind, sessions), most first. */
    val types: List<Pair<WorkoutGroup, Int>>,
    val streak: Int,
    val bestStreak: Int,
    /** Today (or the 1D day) is a workout day. */
    val dayIsActive: Boolean? = null,
)

/** Heart points with their zones (one card). minutes = below, fat burn, cardio, peak. */
data class PeriodZones(
    val period: SectionPeriod,
    val minutes: IntArray,
    val points: Int,
    /** Graph: [SeriesBucket.segments] = fat burn, cardio, peak minutes; value = points. */
    val perBucket: List<SeriesBucket>,
    val grain: Grain,
    val goal: PeriodGoal?,
)

/** One card's data. Extra fields per custom card. */
data class CardData(
    val metricId: String,
    val value: PeriodValue?,
    val series: PeriodSeries?,
    val trend: PeriodTrend?,
    val pace: PeriodPace?,
    val workouts: PeriodWorkouts? = null,
    val zones: PeriodZones? = null,
    val sleep: List<SleepNight>? = null,
    /** Body weight card: per weigh-in lean kg, fat kg, fat % (fat mass lives here only, D16). */
    val composition: List<WeighIn>? = null,
    /** Waist card: WHtR of the latest value (null without height). */
    val whtr: Double? = null,
    /** Vitals HR: daily min–max band (or monthly band on 1Y/All) — same as series with MIN_MAX. */
    val band: List<SeriesBucket>? = null,
)

enum class InsightTone { DONE, AHEAD, ON_TRACK, BEHIND, MISSED, INFO }

/** One status line (D7): icon by [tone] + [text] (templates from V2_TEXTS.md §1, id = template id). */
data class Insight(
    val id: String, val metricId: String?, val tone: InsightTone, val text: String, val priority: Int,
    /** Change lines (st_body_change, st_vitals_change): direction for the ONE icon ▲/▼; the text carries no arrow. */
    val dir: TrendDir? = null,
    /** The change is an improvement (green) / not (red); null = neutral. */
    val good: Boolean? = null,
)

data class SectionBundle(
    val section: SectionId,
    val scope: SectionScope,
    /** Activity rings for scope.effective; Body target rings (D9) for Body; null for Vitals. */
    val rings: RingSet?,
    val bodyRings: List<TargetRing>? = null,
    val cards: Map<String, CardData>,
    val insights: List<Insight>,
    /** Activity hero right: the period's workouts. */
    val workouts: PeriodWorkouts?,
    val dataStart: LocalDate,
    val builtAtVersion: Long,
)

/** D9 body hero ring: share of the way from [start] to [target] as of the period end. */
data class TargetRing(
    val metricId: String,
    val current: Double?,
    val currentDay: LocalDate?,
    val start: Double?,
    val target: Double?,
    /** 0..1 toward the target, never above 1 (no laps on Body); 1 only when [met]. null without a target or value. */
    val progress: Float?,
    /** The current value reached the target in the goal direction (✓). Never true while still on the far side. */
    val met: Boolean = false,
    /** [current] is the last earlier value (D14: show muted with [currentDay]). */
    val carried: Boolean = false,
)

/** All metrics row (2.6). */
data class MetricIndexRow(
    val metricId: String,
    val section: SectionId,
    val latest: Double?,
    val latestDay: LocalDate?,
    val unit: String,
    val trend: TrendDir?,
)

// ============================================================================ metric specs (V2 semantics)

/** Where a 1D graph comes from. */
enum class HourlySource { CACHE, HR_DAY, SESSIONS, NONE }

/**
 * V2 view of one metric. [key] = cache metric (null = derived by the engine). [plausible]: values outside are
 * impossible readings and hidden (G0). [higherIsBetter] null = neutral.
 */
data class V2Metric(
    val id: String,
    val kind: V2Kind,
    val key: String?,
    val unit: String,
    val plausible: ClosedFloatingPointRange<Double>,
    val higherIsBetter: Boolean?,
    val goalBasis: GoalBasis? = null,
    val hourly: HourlySource = HourlySource.NONE,
) {
    val valueKind: ValueKind get() = when (kind) {
        V2Kind.DAILY_TOTAL -> ValueKind.TOTAL
        V2Kind.DAILY_VALUE, V2Kind.SLEEP -> ValueKind.RANGE
        V2Kind.MEASUREMENT -> ValueKind.LAST
        V2Kind.WORKOUT -> ValueKind.COUNT
    }
    val agg: BucketAgg get() = when (kind) {
        V2Kind.DAILY_TOTAL -> BucketAgg.SUM
        V2Kind.DAILY_VALUE, V2Kind.SLEEP -> BucketAgg.MIN_MAX
        V2Kind.MEASUREMENT -> BucketAgg.LAST
        V2Kind.WORKOUT -> BucketAgg.COUNT
    }
}

object V2Metrics {
    val all: List<V2Metric> = listOf(
        V2Metric("steps", V2Kind.DAILY_TOTAL, M.STEPS, "", 0.0..150_000.0, true, GoalBasis.DAILY_SCALED, HourlySource.CACHE),
        V2Metric("exercise_min", V2Kind.DAILY_TOTAL, null, "min", 0.0..1_440.0, true, GoalBasis.DAILY_SCALED, HourlySource.SESSIONS),
        V2Metric("zone_min", V2Kind.DAILY_TOTAL, M.ZONE_MIN, "pts", 0.0..1_000.0, true, GoalBasis.WEEKLY_SCALED, HourlySource.HR_DAY),
        V2Metric("calories", V2Kind.DAILY_TOTAL, M.KCAL, "kcal", 0.0..15_000.0, null, null, HourlySource.CACHE),
        V2Metric("sleep", V2Kind.SLEEP, M.SLEEP_MIN, "min", 0.0..1_440.0, null),
        V2Metric("vo2", V2Kind.MEASUREMENT, M.VO2, "ml/kg/min", 10.0..95.0, true),
        V2Metric("streak", V2Kind.WORKOUT, null, "days", 0.0..100_000.0, true),
        V2Metric("week_workouts", V2Kind.WORKOUT, null, "days", 0.0..100_000.0, true, GoalBasis.WEEKLY_SCALED),
        V2Metric("weight", V2Kind.MEASUREMENT, M.WEIGHT, "kg", Plausible.WEIGHT, false, GoalBasis.TARGET),
        V2Metric("body_fat", V2Kind.MEASUREMENT, M.FAT_PCT, "%", Plausible.FAT_PCT, false, GoalBasis.TARGET),
        V2Metric("lean_mass", V2Kind.MEASUREMENT, null, "kg", Plausible.LEAN_KG, true),
        V2Metric("fat_mass", V2Kind.MEASUREMENT, null, "kg", 1.0..250.0, false),
        V2Metric("waist", V2Kind.MEASUREMENT, M.WAIST, "cm", 40.0..200.0, false, GoalBasis.TARGET),
        V2Metric("whtr", V2Kind.MEASUREMENT, null, "", 0.3..1.2, false),
        V2Metric("bmi", V2Kind.MEASUREMENT, null, "", 10.0..80.0, false),
        V2Metric("rhr", V2Kind.DAILY_VALUE, M.RHR, "bpm", Plausible.RHR_BPM, false),
        V2Metric("hr", V2Kind.DAILY_VALUE, M.HR_AVG, "bpm", Plausible.HR_BPM, null, null, HourlySource.HR_DAY),
        // HRV = Google's RMSSD only (G0): the Apple SDNN series (M.HRV_SDNN) is never read by V2
        V2Metric("hrv", V2Kind.DAILY_VALUE, M.HRV, "ms", 1.0..300.0, true),
        V2Metric("breathing", V2Kind.DAILY_VALUE, M.RESP, "/min", 4.0..40.0, null),
        V2Metric("spo2", V2Kind.DAILY_VALUE, M.SPO2, "%", 70.0..100.0, true),
    )
    private val byId = all.associateBy { it.id }
    fun get(id: String): V2Metric? = byId[id]
}

// ============================================================================ V2Math (pure)

object V2Math {
    const val MAX_MONTH_BUCKETS = 60

    fun grainFor(p: SectionPeriod): Grain = when (p.unit) {
        PeriodUnit.DAY -> Grain.HOUR
        PeriodUnit.WEEK, PeriodUnit.MONTH -> Grain.DAY
        PeriodUnit.YEAR -> Grain.MONTH
        PeriodUnit.ALL -> if (monthsBetween(p.start, p.last) + 1 > MAX_MONTH_BUCKETS) Grain.QUARTER else Grain.MONTH
    }

    private fun monthsBetween(a: LocalDate, b: LocalDate): Long = ChronoUnit.MONTHS.between(a.withDayOfMonth(1), b.withDayOfMonth(1))

    /** Day ranges [start, endExclusive) of the period's buckets for DAY / MONTH / QUARTER grain (HOUR: one per day). */
    fun bucketRanges(p: SectionPeriod, grain: Grain): List<Pair<LocalDate, LocalDate>> {
        val out = ArrayList<Pair<LocalDate, LocalDate>>()
        when (grain) {
            Grain.HOUR, Grain.DAY -> { var d = p.start; while (d.isBefore(p.endExclusive)) { out += d to d.plusDays(1); d = d.plusDays(1) } }
            Grain.MONTH -> {
                var d = p.start.withDayOfMonth(1)
                while (d.isBefore(p.endExclusive)) { out += maxOf(d, p.start) to minOf(d.plusMonths(1), p.endExclusive); d = d.plusMonths(1) }
            }
            Grain.QUARTER -> {
                var d = p.start.withDayOfMonth(1).let { it.withMonth((it.monthValue - 1) / 3 * 3 + 1) }
                while (d.isBefore(p.endExclusive)) { out += maxOf(d, p.start) to minOf(d.plusMonths(3), p.endExclusive); d = d.plusMonths(3) }
            }
        }
        return out
    }

    /**
     * Graph buckets from daily values (DAY / MONTH / QUARTER grain): SUM, LAST, MIN_MAX or COUNT (days with a value)
     * per bucket. Days after [today] are ignored; buckets starting after today are [SeriesBucket.future].
     */
    fun buckets(daily: Map<LocalDate, Double>, p: SectionPeriod, grain: Grain, agg: BucketAgg, today: LocalDate): List<SeriesBucket> {
        val g = if (grain == Grain.HOUR) Grain.DAY else grain
        val sorted = daily.entries.filter { p.contains(it.key) && !it.key.isAfter(today) }.sortedBy { it.key }
        return bucketRanges(p, g).map { (a, b) ->
            val xs = sorted.filter { !it.key.isBefore(a) && it.key.isBefore(b) }
            val future = a.isAfter(today)
            val partial = !future && !today.isBefore(a) && today.isBefore(b)
            val v: Double?; var mn: Double? = null; var mx: Double? = null
            when (agg) {
                BucketAgg.SUM -> v = if (xs.isEmpty()) null else xs.sumOf { it.value }
                BucketAgg.LAST -> v = xs.lastOrNull()?.value
                BucketAgg.COUNT -> v = if (future) null else xs.count { it.value > 0 }.toDouble()
                BucketAgg.MIN_MAX -> { v = xs.lastOrNull()?.value; mn = xs.minOfOrNull { it.value }; mx = xs.maxOfOrNull { it.value } }
            }
            SeriesBucket(a.atStartOfDay(), b.atStartOfDay(), v, mn, mx, null, future, partial)
        }
    }

    /** Period goal (D4): daily × days; heart points weekly × days ÷ 7 (1D: ceil(weekly ÷ 7), D3); targets as is. */
    fun goalFor(metricId: String, goals: Goals, p: SectionPeriod): PeriodGoal? {
        val days = p.totalDays()
        return when (metricId) {
            "steps" -> if (p is SectionPeriod.All) null else PeriodGoal(goals.steps.toDouble() * days, GoalBasis.DAILY_SCALED)
            "exercise_min" -> if (p is SectionPeriod.All) null else PeriodGoal(goals.workoutMinDay.toDouble() * days, GoalBasis.DAILY_SCALED)
            "zone_min" -> when (p) {
                is SectionPeriod.All -> null
                is SectionPeriod.Day -> PeriodGoal(HrZones.dailyGoal(goals.zoneMinWeek).toDouble(), GoalBasis.WEEKLY_SCALED)
                else -> PeriodGoal(goals.zoneMinWeek * days / 7.0, GoalBasis.WEEKLY_SCALED)
            }
            "week_workouts" -> when (p) {
                is SectionPeriod.All, is SectionPeriod.Day -> null
                else -> PeriodGoal(Math.round(goals.workoutDaysWeek * days / 7.0).toDouble(), GoalBasis.WEEKLY_SCALED)
            }
            "weight" -> goals.targetWeightKg?.let { PeriodGoal(it, GoalBasis.TARGET) }
            "body_fat" -> goals.targetFatPct?.let { PeriodGoal(it, GoalBasis.TARGET) }
            "waist" -> goals.targetWaistCm?.let { PeriodGoal(it, GoalBasis.TARGET) }
            else -> null
        }
    }

    /** Daily goal for a bucket line on DAY grain (steps, workout min, heart points), else null. */
    fun dailyGoal(metricId: String, goals: Goals): Double? = when (metricId) {
        "steps" -> goals.steps.toDouble()
        "exercise_min" -> goals.workoutMinDay.toDouble()
        "zone_min" -> HrZones.dailyGoal(goals.zoneMinWeek).toDouble()
        else -> null
    }

    /**
     * Headline per kind (§3.1). [daily] = plausible daily values (any order) covering at least the period; [before] =
     * the last plausible reading before the period (MEASUREMENT carry, D14).
     */
    fun value(spec: V2Metric, daily: Map<LocalDate, Double>, p: SectionPeriod, today: LocalDate, goal: PeriodGoal?,
              before: Pair<LocalDate, Double>? = null): PeriodValue {
        val inP = daily.entries.filter { p.contains(it.key) && !it.key.isAfter(today) }.sortedBy { it.key }
        val elapsed = p.elapsedDays(today)
        val soFar = p.isCurrent(today)
        val kind = spec.valueKind
        var v: Double? = null; var mn: Double? = null; var mx: Double? = null; var at: LocalDate? = null; var carried = false
        when (kind) {
            ValueKind.TOTAL -> v = if (inP.isEmpty()) (if (elapsed > 0) 0.0 else null) else inP.sumOf { it.value }
            ValueKind.COUNT -> v = inP.count { it.value > 0 }.toDouble()
            ValueKind.LAST -> {
                val l = inP.lastOrNull()
                if (l != null) { v = l.value; at = l.key } else if (before != null) { v = before.second; at = before.first; carried = true }
                mn = inP.minOfOrNull { it.value }; mx = inP.maxOfOrNull { it.value }
            }
            ValueKind.RANGE -> {
                val l = inP.lastOrNull(); v = l?.value; at = l?.key
                mn = inP.minOfOrNull { it.value }; mx = inP.maxOfOrNull { it.value }
                if (l == null && before != null) { v = before.second; at = before.first; carried = true }
            }
        }
        val progress = if (goal != null && goal.value > 0 && v != null && goal.basis != GoalBasis.TARGET) (v / goal.value).toFloat() else null
        return PeriodValue(spec.id, p, kind, v, mn, mx, at, carried, inP.size, elapsed, soFar, goal, progress)
    }

    /** The previous equal period, cut at the same elapsed point while [p] is current (B10). null for All. */
    fun previousRange(p: SectionPeriod, today: LocalDate): Pair<LocalDate, LocalDate>? {
        val prev = p.previous() ?: return null
        if (!p.isCurrent(today)) return prev.start to prev.last
        val n = p.elapsedDays(today).toLong()
        val end = minOf(prev.start.plusDays(n - 1), prev.last)
        return prev.start to end
    }

    /**
     * Trend per kind: totals vs the previous period cut at the same elapsed point (past: previous full); daily values on
     * a day vs the previous reading, on W/M/Y by days outside the previous range (direction only); measurements first vs
     * last in the period (1D: vs the previous reading).
     * [flatPct]: |change| below this share of the previous value counts as FLAT (noise).
     */
    fun trend(spec: V2Metric, daily: Map<LocalDate, Double>, p: SectionPeriod, today: LocalDate, flatPct: Double = 0.02): PeriodTrend? {
        fun inRange(a: LocalDate, b: LocalDate) = daily.entries.filter { !it.key.isBefore(a) && !it.key.isAfter(b) && !it.key.isAfter(today) }.sortedBy { it.key }
        val curDays = inRange(p.start, minOf(p.last, today))
        when (spec.kind) {
            V2Kind.DAILY_TOTAL, V2Kind.WORKOUT -> {
                val (a, b) = previousRange(p, today) ?: return null
                val prev = inRange(a, b)
                if (prev.isEmpty() && curDays.isEmpty()) return null
                val agg: (List<Map.Entry<LocalDate, Double>>) -> Double =
                    if (spec.kind == V2Kind.WORKOUT) { xs -> xs.count { it.value > 0 }.toDouble() } else { xs -> xs.sumOf { it.value } }
                val c = agg(curDays); val pv = agg(prev)
                return make(spec, c, pv, if (p.isCurrent(today)) TrendBasis.PREVIOUS_SAME_ELAPSED else TrendBasis.PREVIOUS_FULL, flatPct, true)
            }
            V2Kind.DAILY_VALUE, V2Kind.SLEEP -> {
                if (p is SectionPeriod.All) return null
                if (p is SectionPeriod.Day) {
                    // R3 §2.7: the reading on / before the day vs the reading before it (change set)
                    val cur = daily.entries.filter { !it.key.isAfter(p.last) && !it.key.isAfter(today) }.maxByOrNull { it.key } ?: return null
                    val prev = daily.entries.filter { it.key.isBefore(cur.key) }.maxByOrNull { it.key } ?: return null
                    return make(spec, cur.value, prev.value, TrendBasis.PREVIOUS_READING, flatPct, true).copy(previousDay = prev.key, currentDay = cur.key)
                }
                // R3 §2.7: days outside the FULL previous period's lowest–highest (no number, direction only)
                val o = R3Math.outsidePrevious(daily, p, today)
                if (o.prevRange == null || curDays.isEmpty()) return null
                val dir = o.dir
                return PeriodTrend(spec.id, o.days.toDouble(), null, null, null, dir, R3Math.goodOf(dir, spec.higherIsBetter), TrendBasis.OUTSIDE_PREVIOUS_RANGE)
            }
            V2Kind.MEASUREMENT -> {
                if (p is SectionPeriod.Day) {
                    // D14: the day's reading, else the last earlier one (carried), vs the reading before it (QA 08.5)
                    val cur = curDays.lastOrNull() ?: daily.entries.filter { !it.key.isAfter(p.last) }.maxByOrNull { it.key } ?: return null
                    val prev = daily.entries.filter { it.key.isBefore(cur.key) }.maxByOrNull { it.key } ?: return null
                    return make(spec, cur.value, prev.value, TrendBasis.PREVIOUS_READING, flatPct, true).copy(previousDay = prev.key, currentDay = cur.key)
                }
                if (curDays.size < 2) return null
                return make(spec, curDays.last().value, curDays.first().value, TrendBasis.FIRST_LAST_IN_PERIOD, flatPct, true)
                    .copy(previousDay = curDays.first().key, currentDay = curDays.last().key)
            }
        }
    }

    private fun make(spec: V2Metric, c: Double, pv: Double, basis: TrendBasis, flatPct: Double, showChange: Boolean): PeriodTrend {
        val ch = c - pv
        val dir = when {
            pv == 0.0 && c == 0.0 -> TrendDir.FLAT
            Math.abs(ch) <= flatPct * Math.abs(pv) -> TrendDir.FLAT
            ch > 0 -> TrendDir.UP
            else -> TrendDir.DOWN
        }
        val good = spec.higherIsBetter?.let { h -> if (dir == TrendDir.FLAT) null else (dir == TrendDir.UP) == h }
        return PeriodTrend(spec.id, c, pv, if (showChange) ch else null, if (showChange && pv != 0.0) ch / pv * 100 else null, dir, good, basis)
    }

    /** "7,240" style integer text. */
    fun intText(v: Double): String = String.format(java.util.Locale.US, "%,d", Math.round(v))

    /**
     * Rings for a period (D3/D4): DAY = that day vs the daily goals (heart points vs ceil(weekly ÷ 7)); 1W/1M/1Y =
     * period totals vs the scaled goals; All = LIFETIME totals (no rings). The SAME function feeds Activity 1D, the
     * Today tile and the widgets (one data path, V2-042).
     */
    fun ringSet(p: SectionPeriod, goals: Goals, workoutMin: Double, steps: Double, points: Double): RingSet {
        fun ring(id: String, v: Double, unit: String): PeriodRing {
            val g = goalFor(id, goals, p)?.value
            return PeriodRing(id, id, v, g, if (g != null && g > 0) (v / g).toFloat() else 0f, intText(v), g?.let { intText(it) }, unit)
        }
        val rings = listOf(ring(PeriodRing.WORKOUT, workoutMin, "min"), ring(PeriodRing.STEPS, steps, ""), ring(PeriodRing.HEART, points, "pts"))
        return when (p) {
            is SectionPeriod.All -> RingSet(p, RingMode.LIFETIME, emptyList(), rings)
            is SectionPeriod.Day -> RingSet(p, RingMode.DAY, rings)
            else -> RingSet(p, RingMode.PERIOD_TOTAL, rings)
        }
    }

    /** Workout days in a row ending on [asOf] (or the day before while [asOf] has none yet), and the best run up to [asOf]. */
    fun streakAsOf(activeDays: Set<LocalDate>, asOf: LocalDate): Pair<Int, Int> {
        val upTo = activeDays.filter { !it.isAfter(asOf) }
        return WorkoutMath.streakDays(upTo, asOf) to WorkoutMath.bestStreakDays(upTo)
    }

    /** D9 progress from [start] to [target] given [current]: 0 at start, 1 at target (either direction). */
    fun targetProgress(start: Double?, target: Double?, current: Double?, down: Boolean? = null): Float? {
        if (start == null || target == null || current == null) return null
        val dn = down ?: (start > target)
        // reached (or passed) the target in the goal direction → 1; never "met" while still on the far side
        if (if (dn) current <= target else current >= target) return 1f
        // a start on the wrong side of the target cannot measure progress (the caller re-bases it)
        if (if (dn) start <= target else start >= target) return 0f
        return ((start - current) / (start - target)).toFloat().coerceIn(0f, 0.999f)
    }

    /** Goal direction of a body target: body fat and waist are always "down"; weight follows start (else current) vs target. */
    fun targetDown(metricId: String, start: Double?, current: Double?, target: Double): Boolean = when (metricId) {
        "body_fat", "waist" -> true
        else -> (start ?: current)?.let { it >= target } ?: true
    }
}

// ============================================================================ IC10 goal helpers

/** D9: set (or clear, [target] null) a body target with its start value [latest] and today's date. */
fun Goals.withBodyTarget(metricId: String, target: Double?, latest: Double?, today: LocalDate): Goals {
    val day = if (target != null) today.toEpochDay() else null
    val start = if (target != null) latest else null
    return when (metricId) {
        "weight" -> copy(targetWeightKg = target, targetWeightStart = start, targetWeightSetDay = day)
        "body_fat" -> copy(targetFatPct = target, targetFatPctStart = start, targetFatPctSetDay = day)
        "waist" -> copy(targetWaistCm = target, targetWaistStart = start, targetWaistSetDay = day)
        else -> this
    }
}
