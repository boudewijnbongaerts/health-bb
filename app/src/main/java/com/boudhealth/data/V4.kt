package com.boudhealth.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/*
 * v4 data API for the UI and widgets (see BACKEND.md "v4 API"). Everything reads the local cache (no Health Connect
 * calls). Blocking functions take a [MetricEnv] and must run on Dispatchers.IO; [V4.load] wraps one in a suspend call.
 */

/** Calories of one day (P-KCAL). Each value carries its source label and whether it is calculated. */
/** v5 (D1): Calories burned of a day = Google Health's own total. [active] / [resting] / [note] are retired shims (always null). */
data class CaloriesDay(val day: LocalDate, val total: DayValue?) {
    @Deprecated("v5: no active energy") val active: DayValue? get() = null
    @Deprecated("v5: no resting energy") val resting: DayValue? get() = null
    @Deprecated("v5: no explanation lines") val note: String? get() = null
}

/** One night (Q7), exactly as Google delivers it: duration and stages. */
data class SleepNightDetail(
    val day: LocalDate,
    val start: Long, val end: Long,
    val asleepMin: Int, val deepMin: Int, val remMin: Int, val lightMin: Int, val awakeMin: Int,
    val stages: List<SleepStage>,
    /** Minutes per stage name over the timeline ("deep", "rem", "light", "asleep", "awake", "inbed", …). */
    val stageTotals: Map<String, Int>,
    val source: String?,
    /** v4.1 fix: minutes asleep without stage information — show as [SleepInfo.UNSPECIFIED_LABEL], not as light. */
    val asleepUnspecifiedMin: Int = 0,
) {
    @Deprecated("v5 (D3): efficiency removed") val efficiency: Double? get() = null
}

/** v6: daily heart-rate range. */
data class HrRange(val day: LocalDate, val min: Double, val max: Double)

/** One value of the day detail (Q17): [estimated] = calculated by Health BB, [source] = human app name. */
data class DayMetric(val id: String, val label: String, val value: Double, val unit: String, val source: String?, val estimated: Boolean)

/**
 * [azm] = minutes below zones / fat burn / cardio / peak; [intensity] = AZM-style intensity minutes.
 * [zones] = DEPRECATED v4.0 shim (6 slots: below, fat burn, cardio, peak, 0, 0).
 */
data class WorkoutDetail(val workout: Workout, val zones: IntArray?, val intensity: Int?, val azm: IntArray? = null)

data class DayDetail(
    val day: LocalDate,
    val rings: RingDay,
    val calories: CaloriesDay,
    /** DEPRECATED v4.0 shim (6 slots: below, fat burn, cardio, peak, 0, 0); use [azm]. */
    val zones: IntArray?,
    val maxHr: MaxHrInfo,
    val metrics: List<DayMetric>,
    val workouts: List<WorkoutDetail>,
    val sleep: SleepNightDetail?,
    /** Minutes below zones / fat burn / cardio / peak of the day (Fitbit HRR zones), null = no heart rate. */
    val azm: IntArray? = null,
    /** Resting HR the zones used that day, and where it came from. */
    val restHr: Pair<Int, RestSource>? = null,
)

/** Weekly cardio (R2): intensity minutes Mon–Sun vs the weekly goal, per day, per zone, pace and last week. */
data class WeeklyCardio(
    val weekStart: LocalDate,
    val total: Int,
    val goal: Int,
    /** Mon..Sun: intensity minutes, null = future day or no heart rate. */
    val perDay: List<Int?>,
    /** Minutes in fat burn, cardio, peak this week (and their points: fat burn × 1, cardio × 2, peak × 2). */
    val fatBurnMin: Int, val cardioMin: Int, val peakMin: Int,
    val pace: CardioPace,
    /** Share of the week's active time gone (07:00–22:00 each day), and the expected total by now. */
    val fraction: Double, val expected: Double,
    val lastWeekTotal: Int,
    /** Last week's total at the same point of the week (for "vs last week"). */
    val lastWeekSoFar: Int,
)

object V4 {

    /** One IO hop: loads a [MetricEnv] (and app-name resolution) and runs [block] on Dispatchers.IO. */
    suspend fun <T> load(context: Context, block: (MetricEnv) -> T): T {
        val env = MetricEnv.load(context)
        Sources.init(context)
        return withContext(Dispatchers.IO) { block(env) }
    }

    private fun ms(env: MetricEnv, d: LocalDate) = d.atStartOfDay(env.zone).toInstant().toEpochMilli()
    private fun dayOf(env: MetricEnv, ms: Long) = Instant.ofEpochMilli(ms).atZone(env.zone).toLocalDate()

    // ------------------------------------------------------------------ max HR and zones (P-MAXHR, P-INTENSITY, Q15)

    /** Value in use + where it comes from + the measured 12-month max as a suggestion. */
    fun maxHr(env: MetricEnv): MaxHrInfo = Derive.maxHrInfo(env.cache, env.goals)

    /** Resting HR the zones use on [day]: that day's, else the median of the 7 days before, else 60. */
    fun restHr(env: MetricEnv, day: LocalDate): Pair<Int, RestSource> = HrZones.restFor(day, Derive.restMap(env.cache, day, day))

    /** Minutes below zones / fat burn / cardio / peak of [day] (Fitbit HRR zones), null when no heart rate is stored. */
    fun dayAzm(env: MetricEnv, day: LocalDate): IntArray? {
        val hd = env.cache.hrDay(day) ?: return null
        return HrZones.minutes(hd.bpm, maxHr(env).value, restHr(env, day).first)
    }

    /** Minutes per day in zone [zoneIndex] (HrZones.BELOW..PEAK). */
    fun azmDaily(env: MetricEnv, zoneIndex: Int, from: LocalDate, to: LocalDate): Map<LocalDate, Int> =
        env.cache.daily(M.AZM[zoneIndex], from, to).associate { it.day to it.value.toInt() }

    /** Minutes below zones / fat burn / cardio / peak of one workout. */
    fun workoutAzm(env: MetricEnv, w: Workout): IntArray? = Derive.workoutZones(env.cache, w.start, w.end, maxHr(env).value, env.zone)

    /** Weekly cardio (R2) for the week (Mon–Sun) of [anyDay], with pace at [now]. */
    fun weeklyCardio(env: MetricEnv, anyDay: LocalDate = env.today, now: java.time.LocalDateTime = java.time.LocalDateTime.now(env.zone)): WeeklyCardio {
        val ws = WorkoutMath.weekStart(anyDay)
        val goal = env.goals.zoneMinWeek
        val week = intensityDaily(env, ws, ws.plusDays(6))
        val perDay = (0..6).map { i -> val d = ws.plusDays(i.toLong()); if (d.isAfter(env.today)) null else week[d] }
        val total = perDay.filterNotNull().sum()
        val zones = (1..3).map { z -> azmDaily(env, z, ws, ws.plusDays(6)).values.sum() }
        val f = if (ws == WorkoutMath.weekStart(now.toLocalDate())) CardioPaceMath.fraction(ws, now) else 1.0
        val last = intensityDaily(env, ws.minusDays(7), ws.minusDays(1))
        val lastSoFar = last.filterKeys { ChronoUnit.DAYS.between(ws.minusDays(7), it) < ChronoUnit.DAYS.between(ws, now.toLocalDate()) + 1 }.values.sum()
        val any = perDay.any { it != null }
        return WeeklyCardio(ws, total, goal, perDay, zones[0], zones[1], zones[2],
            CardioPaceMath.status(if (any) total.toDouble() else null, goal, f), f, goal * f, last.values.sum(), lastSoFar)
    }

    // ---- v4.0 compatibility shims (5 zones on % max HR are retired; arrays: below, fat burn, cardio, peak, 0, 0)
    @Deprecated("v4.1: dayAzm") fun dayZones(env: MetricEnv, day: LocalDate): IntArray? = dayAzm(env, day)?.let { HrZones.toLegacy6(it) }

    /** Intensity minutes per day in [from]..[to] (stored daily, same rule). */
    fun intensityDaily(env: MetricEnv, from: LocalDate, to: LocalDate): Map<LocalDate, Int> =
        env.cache.daily(M.ZONE_MIN, from, to).associate { it.day to it.value.toInt() }

    @Deprecated("v4.1: azmDaily (1 = fat burn, 2 = cardio, 3 = peak; 4–5 are empty)")
    fun zoneDaily(env: MetricEnv, z: Int, from: LocalDate, to: LocalDate): Map<LocalDate, Int> =
        if (z in 1..3) azmDaily(env, z, from, to) else emptyMap()

    @Deprecated("v4.1: workoutAzm") fun workoutZones(env: MetricEnv, w: Workout): IntArray? = workoutAzm(env, w)?.let { HrZones.toLegacy6(it) }

    // ------------------------------------------------------------------ rings (P-RINGS)

    fun ringDays(env: MetricEnv, from: LocalDate, to: LocalDate): List<RingDay> {
        val g = env.goals
        val steps = env.cache.daily(M.STEPS, from, to).associate { it.day to it.value }
        val wkStart = WorkoutMath.weekStart(from)
        val intensity = intensityDaily(env, wkStart, to)
        val days = SyncLogic.datesBetween(from, to)
        val running = HistoryMath.weekRunning(intensity, days)
        val ex = Local.exerciseMinutes(Local.workouts(env.cache, from.minusDays(1), to), from, to)
        return days.map { d ->
            RingDay(d, steps[d], g.steps, ex[d] ?: 0, g.workoutMinDay, intensity[d], running[d] ?: 0, g.zoneMinWeek)
        }
    }

    fun ringDay(env: MetricEnv, day: LocalDate = env.today): RingDay = ringDays(env, day, day).first()

    // ------------------------------------------------------------------ workouts (Q13, Q14, Q19)

    /** De-duplicated sessions starting on [day], oldest first. */
    fun workoutsOn(env: MetricEnv, day: LocalDate): List<Workout> =
        WorkoutMath.onDay(Local.workouts(env.cache, day.minusDays(1), day.plusDays(1)), { it.start }, day, env.zone)

    /**
     * v5 (D6): workouts with their zone minutes (below, fat burn, cardio, peak) for the Heart zones page: [week] false =
     * TODAY's sessions only (was today + yesterday), true = this week's (Mon–today). Newest first.
     */
    fun zoneWorkouts(env: MetricEnv, week: Boolean): List<Pair<Workout, IntArray?>> {
        val from = if (week) WorkoutMath.weekStart(env.today) else env.today
        return Local.workouts(env.cache, from.minusDays(1), env.today)
            .filter { val d = dayOf(env, it.start); !d.isBefore(from) && !d.isAfter(env.today) }
            .sortedByDescending { it.start }.map { it to workoutAzm(env, it) }
    }

    /** Yesterday's workouts, all of them, oldest first (Q13). */
    fun yesterdayWorkouts(env: MetricEnv): List<Workout> = workoutsOn(env, env.today.minusDays(1))

    /**
     * Sessions and WORKOUT DAYS of the week (Mon–Sun) of [anyDay] vs the days goal (V2: Goals.activeDayRule, D18).
     */
    fun workoutWeek(env: MetricEnv, anyDay: LocalDate = env.today): WorkoutMath.Week {
        val w = workoutsWeek(env, anyDay)
        return WorkoutMath.Week(w.weekStart, w.sessions, w.activeDays, w.goal, w.activeDayList)
    }

    /** v6 Workouts week: active days X/goal, sessions, type chips ([WorkoutsWeek]). */
    fun workoutsWeek(env: MetricEnv, anyDay: LocalDate = env.today): WorkoutsWeek {
        val ws = WorkoutMath.weekStart(anyDay)
        val sessions = Local.workouts(env.cache, ws.minusDays(1), ws.plusDays(6)).filter { val d = dayOf(env, it.start); !d.isBefore(ws) && !d.isAfter(ws.plusDays(6)) }
        // V2 (D18): workout days by Goals.activeDayRule, the same rule as the sections
        val active = activeDays(env, ws, ws.plusDays(6)).sorted()
        val types = sessions.map { ExerciseTypes.group(it) }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<WorkoutGroup, Int>> { it.value }.thenBy { it.key.ordinal }).map { it.key to it.value }
        return WorkoutsWeek(ws, active.size, env.goals.workoutDaysWeek, active, sessions.size, types)
    }

    /** V2 (D18): workout days in [from]..[to] by Goals.activeDayRule (default: a workout ≥ 20 min with ≥ 10 heart points). */
    fun activeDays(env: MetricEnv, from: LocalDate, to: LocalDate): Set<LocalDate> = V2.engine(env).activeDays(from, to)

    fun isActiveDay(env: MetricEnv, day: LocalDate = env.today): Boolean = day in activeDays(env, day, day)

    /** v6 weight composition chart: per weigh-in lean kg, fat kg (= weight − lean) and fat %. Oldest first. */
    fun composition(env: MetricEnv, from: LocalDate, to: LocalDate): List<WeighIn> =
        BodyMath.composition(env.cache.daily(M.WEIGHT, from, to).filter { it.value in Plausible.WEIGHT },
            env.cache.daily(M.LEAN_KG, from, to).filter { it.value in Plausible.LEAN_KG }.associate { it.day to it.value },
            env.cache.daily(M.FAT_PCT, from, to).filter { it.value in Plausible.FAT_PCT }.associate { it.day to it.value })

    /** v6 Heart rate "All": daily min and max (bpm) for the light range band. Oldest first. */
    fun hrDailyRange(env: MetricEnv, from: LocalDate, to: LocalDate): List<HrRange> {
        val mx = env.cache.daily(M.HR_MAX, from, to).filter { it.value in Plausible.HR_BPM }.associate { it.day to it.value }
        return env.cache.daily(M.HR_MIN, from, to).filter { it.value in Plausible.HR_BPM }.mapNotNull { mn -> mx[mn.day]?.let { HrRange(mn.day, mn.value, it) } }
    }

    /** v6 VO₂ max: latest vs 3 months before it (Google Health first, Withings fallback, as stored). */
    fun vo2Change(env: MetricEnv, months: Long = 3): ChangeVs? =
        BodyMath.changeVs(env.cache.daily(M.VO2, env.today.minusYears(3), env.today).filter { it.value in 10.0..95.0 }, months)

    /** Workout streak in days (Q14), ending today or yesterday. V2: the engine's streak days (workout ≥ 20 min). */
    fun streakDays(env: MetricEnv): Int = V2.engine(env).streakAsOf(env.today).first

    fun bestStreakDays(env: MetricEnv): Int = V2.engine(env).streakAsOf(env.today).second

    // ------------------------------------------------------------------ history (Q17)

    fun period(env: MetricEnv, kind: PeriodKind, anchor: LocalDate = env.today): PeriodSummary {
        val (s, e) = HistoryMath.bounds(kind, anchor)
        val last = SyncLogic.minDate(e.minusDays(1), env.today)
        val (ps, pe) = HistoryMath.previous(kind, s)
        val days = if (last.isBefore(s)) emptyList() else ringDays(env, s, last)
        val prev = ringDays(env, ps, pe.minusDays(1))
        val kcal = env.cache.daily(M.KCAL, ps, last).associate { it.day to it.value }
        return HistoryMath.summary(kind, s, e, days, prev, kcal, env.today) // v4.1: today counts only once closed; soFar
    }

    /** Trend buckets for a period: [by] DAY (week / month views), WEEK (month / year), MONTH (year). */
    fun trend(env: MetricEnv, kind: PeriodKind, anchor: LocalDate, by: TrendBucket): List<TrendPoint> =
        HistoryMath.trend(period(env, kind, anchor).days, by)

    fun calories(env: MetricEnv, day: LocalDate): CaloriesDay {
        fun one(m: String) = env.cache.daily(m, day, day).firstOrNull()
        return CaloriesDay(day, one(M.KCAL))
    }

    /** Everything about one day (Q17 day detail). */
    fun dayDetail(env: MetricEnv, day: LocalDate): DayDetail {
        val c = env.cache
        val mx = maxHr(env)
        val metrics = ArrayList<DayMetric>()
        fun add(id: String, label: String, metric: String, unit: String, scale: Double = 1.0) {
            c.daily(metric, day, day).firstOrNull()?.let { metrics += DayMetric(id, label, it.value * scale, unit, it.sourceLabel, it.estimated) }
        }
        add("steps", "Steps", M.STEPS, "")
        add("calories", "Calories burned", M.KCAL, "kcal")
        add("intensity", "Heart points", M.ZONE_MIN, "pts")
        add("rhr", "Resting heart rate", M.RHR, "bpm")
        add("hr_avg", "Heart rate", M.HR_AVG, "bpm")
        add("hr_max", "Max heart rate", M.HR_MAX, "bpm")
        add("hrv", "HRV", M.HRV, "ms")
        add("vo2", "VO₂ max", M.VO2, "ml/kg/min")
        add("spo2", "Blood oxygen", M.SPO2, "%")
        add("breathing", "Breathing rate", M.RESP, "/min")
        add("weight", "Weight", M.WEIGHT, "kg")
        add("body_fat", "Body fat percentage", M.FAT_PCT, "%")
        add("distance", "Distance", M.DISTANCE_M, "km", 0.001)
        val ws = workoutsOn(env, day).map { w ->
            val z = Derive.workoutZones(c, w.start, w.end, mx.value, env.zone)
            WorkoutDetail(w, z?.let { HrZones.toLegacy6(it) }, z?.let { HrZones.intensity(it) }, z)
        }
        val azm = dayAzm(env, day)
        return DayDetail(day, ringDay(env, day), calories(env, day), azm?.let { HrZones.toLegacy6(it) }, mx, metrics, ws, sleepNight(env, day), azm, restHr(env, day))
    }

    // ------------------------------------------------------------------ sleep (Q7)

    /** The night ending on [day] (wake-up day), with its stage timeline; null when there is none. */
    fun sleepNight(env: MetricEnv, day: LocalDate): SleepNightDetail? {
        val n = Local.sleepNights(env.cache, day, day).firstOrNull() ?: return null
        val stages = env.cache.sleepStages(day)
        val totals = stages.groupBy { it.stage }.mapValues { (_, l) -> (l.sumOf { it.end - it.start } / 60_000L).toInt() }
        val src = env.cache.daily(M.SLEEP_MIN, day, day).firstOrNull()?.sourceLabel
        val i = n.info
        return SleepNightDetail(day, i.start, i.end, i.asleepMin, i.deepMin, i.remMin, i.lightMin, i.awakeMin, stages, totals, src, i.asleepUnspecifiedMin)
    }

    /** Per-night stage totals for the history table / stacked bars (no timelines: cheap). Oldest first. */
    fun sleepNights(env: MetricEnv, from: LocalDate, to: LocalDate): List<SleepNight> = Local.sleepNights(env.cache, from, to)
}
