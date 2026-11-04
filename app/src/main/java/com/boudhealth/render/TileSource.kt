package com.boudhealth.render

import com.boudhealth.data.CaloriesDay
import com.boudhealth.data.ExerciseTypes
import com.boudhealth.data.RingDay
import com.boudhealth.data.RingSet
import com.boudhealth.data.SectionPeriod
import com.boudhealth.data.V2
import com.boudhealth.data.Pace
import com.boudhealth.render.ring.RingData
import com.boudhealth.data.V4
import com.boudhealth.data.WorkoutMath
import java.time.LocalDateTime
import com.boudhealth.data.Local
import com.boudhealth.data.M
import com.boudhealth.data.MetricEnv
import com.boudhealth.data.MetricReading
import com.boudhealth.data.Metrics
import com.boudhealth.data.Pt
import com.boudhealth.data.Range
import com.boudhealth.data.Window
import com.boudhealth.data.Workout
import com.boudhealth.data.Zones
import com.boudhealth.ui.components.Fmt
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Everything the widget tiles read, through the backend's metric registry (Metrics / Local / Cache, BACKEND.md §6).
 * Blocking: create and use on Dispatchers.IO. Results are memoised for one render pass.
 * [updatedAt] = time of the last published snapshot (shown as "Updated 11:43" on the Today tile).
 */
class TileSource(val env: MetricEnv, val updatedAt: Long?) {
    val today: LocalDate = env.today
    val monday: LocalDate = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val goals get() = env.goals

    private val readings = HashMap<String, MetricReading?>()
    private val dailies = HashMap<String, List<Pt>>()

    fun reading(id: String): MetricReading? = readings.getOrPut(id) { Metrics.latest(env, id) }

    /** Days with data in the last [days] days (incl. today), oldest first. */
    fun daily(id: String, days: Long): List<Pt> = dailies.getOrPut("$id/$days") { Metrics.daily(env, id, today.minusDays(days - 1), today) }

    fun values(id: String, days: Long): List<Double> = daily(id, days).map { it.v }

    /** Calendar-aligned bucketed series (weekly for a year, like the detail pages). */
    fun series(id: String, range: Range): List<Pt> = dailies.getOrPut("$id/s/${range.name}") { Metrics.series(env, id, Window.of(range, 0, env.zone)) }

    /** Values for the 7 rolling days ending today (null = no data), with their letters. */
    fun rolling7(id: String): List<Double?> {
        val m = daily(id, 7).associate { it.date to it.v }
        return (6 downTo 0).map { m[today.minusDays(it.toLong())] }
    }
    val rollingLetters: List<String> get() = (6 downTo 0).map { Fmt.dayLetter(today.minusDays(it.toLong())) }

    /** Mon–Sun of this week (null for days after today). */
    fun weekDaily(id: String): List<Double?> {
        val m = Metrics.daily(env, id, monday, today).associate { it.date to it.v }
        return (0..6).map { i -> val d = monday.plusDays(i.toLong()); if (d.isAfter(today)) null else (m[d] ?: 0.0) }
    }
    val weekLetters: List<String> get() = (0..6).map { Fmt.dayLetter(monday.plusDays(it.toLong())) }
    val weekTodayIndex: Int get() = today.dayOfWeek.value - 1

    // ------------------------------------------------------------------ v4 rings (P-RINGS): workout min · weekly intensity · steps

    /** Today's v4 ring day (V4.ringDay): steps, workout minutes (union of sessions), intensity Mon..today. */
    val ringToday: RingDay by lazy { V4.ringDay(env) }
    /** Mon..today. */
    val ringWeek: List<RingDay> by lazy { V4.ringDays(env, monday, today) }
    /** Rolling 7 days, oldest first. */
    val ring7: List<RingDay> by lazy { V4.ringDays(env, today.minusDays(6), today) }

    /** Ring fractions by ring id (RingData.ORDER: Workout minutes · Steps · Heart points, outer → inner); > 1 laps. */
    val ringProgress: FloatArray by lazy { RingData.progress(ringToday) }
    /** Values / goals in the same order; ring 3 = the DAY's heart points vs ceil(weekly ÷ 7) (D3). */
    val ringValues: DoubleArray by lazy { doubleArrayOf(ringToday.workoutMin.toDouble(), ringToday.steps ?: 0.0, (ringToday.intensity ?: 0).toDouble()) }
    val ringGoals: IntArray by lazy { intArrayOf(ringToday.workoutMinGoal, ringToday.stepsGoal, ringToday.intensityGoal) }
    /**
     * V2 (D3, IC2): today's rings from the V2 data API, the same call as the app's Today rings: Workout minutes ·
     * Steps · Heart points, ring 3 = the DAY's heart points vs ceil(weekly ÷ 7). Null if V2 fails (callers fall back).
     */
    val todayRingSet: RingSet? by lazy { runCatching { V2.rings(env, SectionPeriod.Day(today.toEpochDay())) }.getOrNull() }

    /** Rolling 7 days of ring progress (same index order), oldest first. */
    val ringDays: List<FloatArray> by lazy { ring7.map { RingData.progress(it) } }
    /** Rings closed today (≥ 100 %). */
    val closedToday: Int get() = ringProgress.count { it >= 1f }
    /** Days in the last 7 / this week with all three rings closed. */
    val closedDays7: Int by lazy { ring7.count { it.closed } }
    val closedThisWeek: Int by lazy { ringWeek.count { it.closed } }

    // ------------------------------------------------------------------ v4 calories, zones, workouts (P-KCAL, Q13–Q15, Q19)

    val calories: CaloriesDay by lazy { V4.calories(env, today) }
    /** Daily totals of the last 7 days (null = none). */
    val kcal7: List<Double?> by lazy { rolling7("calories") }

    /**
     * v4.1 R3 (one zone system, Fitbit HRR zones): minutes today in [below, fat burn, cardio, peak]; null without heart rate.
     */
    val azmToday: IntArray? by lazy { V4.dayAzm(env, today) }
    /** Minutes Mon..today in [below, fat burn, cardio, peak]. */
    val azmWeek: IntArray by lazy { IntArray(4) { z -> V4.azmDaily(env, z, monday, today).values.sum() } }


    val workoutWeek: WorkoutMath.Week by lazy { V4.workoutWeek(env) }
    val yesterday: List<Workout> by lazy { V4.yesterdayWorkouts(env) }
    /** v6 D2: active days (≥ 10 heart points) vs goal, sessions and workout types this week. */
    val workoutsWeek: com.boudhealth.data.WorkoutsWeek by lazy { V4.workoutsWeek(env) }
    /** v5 W1 "Recent sessions": the last sessions, newest first (last 14 days, at most 6), same window as the app. */
    val recent: List<Workout> by lazy { Local.workouts(env.cache, today.minusDays(13), today).sortedByDescending { it.start }.take(6) }
    val streakDays: Int by lazy { V4.streakDays(env) }
    val bestStreakDays: Int by lazy { V4.bestStreakDays(env) }
    /** Last 7 days (oldest first): at least one workout? */
    val workedOut7: List<Boolean> by lazy {
        val days = Local.workouts(env.cache, today.minusDays(7), today).map { Instant.ofEpochMilli(it.start).atZone(env.zone).toLocalDate() }.toSet()
        (6 downTo 0).map { today.minusDays(it.toLong()) in days }
    }
    /** Mon..Sun: a day with a workout (false for future days). */
    val weekDayFilled: List<Boolean> by lazy { (0..6).map { k -> monday.plusDays(k.toLong()) in workoutWeek.dayList } }
    /** Daily steps of the last 14 days (oldest first). */
    val steps14: List<Double?> by lazy {
        val m = Metrics.daily(env, "steps", today.minusDays(13), today).associate { it.date to it.v }
        (13 downTo 0).map { m[today.minusDays(it.toLong())] }
    }

    /**
     * Time the weekly pace is computed for (WEEKLY_SPEC §5 "Stale"): now, or the last sync when that is older than 6 h,
     * so the widget never says "behind" only because data hasn't arrived.
     */
    val paceTime: LocalDateTime by lazy {
        val now = System.currentTimeMillis()
        val at = updatedAt?.takeIf { now - it > 6 * 3_600_000L } ?: now
        LocalDateTime.ofInstant(Instant.ofEpochMilli(at), env.zone)
    }
    val stale: Boolean get() = updatedAt?.let { System.currentTimeMillis() - it > 6 * 3_600_000L } ?: false
    /** Share of this week elapsed at [paceTime] (T1 data/Pace.kt, IC9; replaces ui/home WeeklyPace). */
    val paceFraction: Double by lazy { Pace.weekFraction(monday, paceTime) }

    // ------------------------------------------------------------------ workouts

    /** De-duplicated workouts this week (Mon–today), newest first. */
    val weekWorkouts: List<Workout> by lazy { Local.workouts(env.cache, monday, today) }

    /** Mon–Sun minutes per category (strength, cardio, other, bottom → top); null = future day. */
    val weekWorkoutBars: List<List<Pair<Double, Int>>?> by lazy {
        val byDay = weekWorkouts.groupBy { Instant.ofEpochMilli(it.start).atZone(env.zone).toLocalDate() }
        (0..6).map { i ->
            val d = monday.plusDays(i.toLong())
            if (d.isAfter(today)) null
            // v5 S5: one segment per workout kind in its own colour
            else (byDay[d] ?: emptyList()).groupBy { ExerciseTypes.group(it) }.toSortedMap()
                .map { (g, ws) -> ws.sumOf { it.minutes }.toDouble() to Hue.workoutGroup(g) }
        }
    }

    val lastWorkout: Workout? by lazy { Local.workouts(env.cache, today.minusDays(400), today).firstOrNull() }

    /** Per-minute heart rate during [w] from the cached per-minute HR (0 = none, skipped). */
    fun workoutHr(w: Workout): List<Double> {
        val z = env.zone
        val d0 = Instant.ofEpochMilli(w.start).atZone(z).toLocalDate()
        val d1 = Instant.ofEpochMilli(w.end - 1).atZone(z).toLocalDate()
        val out = ArrayList<Double>()
        for (hd in env.cache.hrDays(d0, d1)) {
            val dayStart = hd.day.atStartOfDay(z).toInstant().toEpochMilli()
            val m0 = ((w.start - dayStart) / 60_000L).coerceIn(0L, 1500L).toInt()
            val m1 = ((w.end - dayStart) / 60_000L).coerceIn(0L, 1500L).toInt()
            for (m in m0 until m1) { val v = hd.at(m); if (v > 0) out += v.toDouble() }
        }
        return out
    }

    // ------------------------------------------------------------------ sleep / body helpers

    /**
     * Last 7 nights: (deep, rem, light, asleep without stages) minutes per wake-up day, oldest first; null = no night.
     * "Asleep (no stages)" (M.SLEEP_ASLEEP) is its own segment in the sleep colour, as in the app's StageBar.
     */
    val sleepNights: List<List<Pair<Double, Int>>?> by lazy {
        val from = today.minusDays(6)
        fun m(metric: String) = env.cache.daily(metric, from, today).associate { it.day to it.value }
        val deep = m(M.SLEEP_DEEP); val rem = m(M.SLEEP_REM); val light = m(M.SLEEP_LIGHT); val total = m(M.SLEEP_MIN)
        val unspec = m(M.SLEEP_ASLEEP); val awake = m(M.SLEEP_AWAKE)
        (6 downTo 0).map { i ->
            val d = today.minusDays(i.toLong())
            if (total[d] == null) null
            else listOf((deep[d] ?: 0.0) to Hue.deep, (rem[d] ?: 0.0) to Hue.rem, (light[d] ?: 0.0) to Hue.light, (unspec[d] ?: 0.0) to Hue.sleep, (awake[d] ?: 0.0) to Hue.awake)
                .let { parts -> if (parts.dropLast(1).sumOf { it.first } <= 0) listOf((total[d] ?: 0.0) to Hue.light) else parts }
        }
    }

    val heightM: Double? by lazy { Local.heightM(env.cache) }

    /** Personal normal band (mean ± 1 SD of the previous 60 days) for a metric with daily values. */
    fun band(id: String): Pair<Double, Double>? = reading(id)?.band ?: Zones.normalBand(values(id, 60))

    private fun frac(v: Double, goal: Double): Float = if (goal <= 0) 0f else (v / goal).toFloat().coerceAtLeast(0f)
}
