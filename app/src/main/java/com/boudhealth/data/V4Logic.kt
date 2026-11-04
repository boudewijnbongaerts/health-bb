package com.boudhealth.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/*
 * v4 pure logic (no Android): heart-rate zones and intensity minutes, max HR, calories split, rings, workout weeks,
 * history aggregates, source names. Unit-tested on the JVM (scratchpad/datatest/V4Test.kt). The Android-side API that
 * reads the cache with these is in V4.kt.
 */

// ============================================================================ heart-rate zones (P-INTENSITY, P-MAXHR)

/**
 * ONE zone system for the whole app (v4.1, R2/R3): Fitbit's published heart-rate-reserve zones, as used for Active Zone
 * Minutes on current devices (Pixel Watch, Fitbit Air, Charge 6, Inspire 3, Sense, Versa).
 * Source: https://support.google.com/fitbit/answer/14237938 (zones) and https://support.google.com/fitbit/answer/14236509
 * (1 AZM per fat-burn minute, 2 per cardio or peak minute; weekly goal 150).
 *
 *   heart rate reserve (HRR) = max HR − resting HR;   % HRR = (bpm − resting HR) ÷ HRR
 *   below zones < 40 %   ·   fat burn 40–59 %   ·   cardio 60–84 %   ·   peak ≥ 85 %
 *
 * Each minute counts once, in the zone of that minute's average heart rate; a minute without a reading counts nothing
 * (no gap filling). Fitbit publishes no minimum duration, so none is applied.
 */
object HrZones {
    const val BELOW = 0
    const val FAT_BURN = 1
    const val CARDIO = 2
    const val PEAK = 3
    val NAMES = listOf("Below zones", "Fat burn", "Cardio", "Peak")
    /** Lower bound of fat burn, cardio, peak in % of heart rate reserve. */
    val LOWER_HRR = intArrayOf(40, 60, 85)
    /** Smallest reserve used when max HR − resting HR is implausibly small (bad settings), so zones stay defined. */
    const val MIN_RESERVE = 20

    /** Zone of one minute: [BELOW], [FAT_BURN], [CARDIO] or [PEAK]. */
    fun zoneOf(bpm: Int, maxHr: Int, restHr: Int): Int {
        if (bpm <= 0 || maxHr <= 0) return BELOW
        val reserve = maxOf(maxHr - restHr, MIN_RESERVE)
        val pct = (bpm - restHr) * 100.0 / reserve
        return when {
            pct >= 85.0 -> PEAK
            pct >= 60.0 -> CARDIO
            pct >= 40.0 -> FAT_BURN
            else -> BELOW
        }
    }

    /** Lowest bpm of fat burn, cardio and peak for these settings (labels). */
    fun bounds(maxHr: Int, restHr: Int): IntArray {
        val reserve = maxOf(maxHr - restHr, MIN_RESERVE)
        return IntArray(3) { Math.ceil(restHr + reserve * LOWER_HRR[it] / 100.0).toInt() }
    }

    /**
     * Minutes per zone in minute slots [from, to) of one stored day (per-minute average bpm, 0 = no reading).
     * Returns IntArray(4): below, fat burn, cardio, peak (only minutes with a reading).
     */
    fun minutes(bpm: ByteArray, maxHr: Int, restHr: Int, from: Int = 0, to: Int = bpm.size): IntArray {
        val out = IntArray(4)
        for (m in from.coerceAtLeast(0) until to.coerceAtMost(bpm.size)) {
            val v = bpm[m].toInt() and 0xFF
            if (v == 0) continue
            out[zoneOf(v, maxHr, restHr)]++
        }
        return out
    }

    /** Intensity minutes (Fitbit AZM): fat burn × 1 + (cardio + peak) × 2. Works on the 4-slot arrays (and the old 6-slot shim). */
    fun intensity(z: IntArray): Int = z[FAT_BURN] + 2 * (z[CARDIO] + z[PEAK])

    /** Resting HR used for a day: that day's, else the median of the 7 days before, else 60 (see [restSource]). */
    fun restFor(day: java.time.LocalDate, rhr: Map<java.time.LocalDate, Double>): Pair<Int, RestSource> {
        rhr[day]?.takeIf { it in 25.0..140.0 }?.let { return Math.round(it).toInt() to RestSource.DAY }
        val prev = (1..7).mapNotNull { rhr[day.minusDays(it.toLong())]?.takeIf { v -> v in 25.0..140.0 } }.sorted()
        if (prev.isNotEmpty()) {
            val med = if (prev.size % 2 == 1) prev[prev.size / 2] else (prev[prev.size / 2 - 1] + prev[prev.size / 2]) / 2
            return Math.round(med).toInt() to RestSource.MEDIAN_7D
        }
        return 60 to RestSource.DEFAULT
    }

    /** Daily intensity goal = weekly goal ÷ 7, rounded UP (150 → 22, the default). */
    fun dailyGoal(weekGoal: Int): Int = Math.ceil(weekGoal / 7.0).toInt()

    // ---- compatibility with the v4.0 UI (5 zones on % max HR, removed): 6-slot arrays [below, fat burn, cardio, peak, 0, 0]
    @Deprecated("v4.1: use bounds(maxHr, restHr)") fun bounds(maxHr: Int): IntArray = bounds(maxHr, 60).let { intArrayOf(it[0], it[1], it[2], maxHr, maxHr) }
    fun toLegacy6(z: IntArray): IntArray = intArrayOf(z[0], z[1], z[2], z[3], 0, 0)
}

enum class RestSource { DAY, MEDIAN_7D, DEFAULT }

enum class MaxHrSource { MANUAL, AGE, MEASURED, DEFAULT }

/**
 * The max HR the zones use, and where it comes from (shown in the UI). [measured12m] is info only.
 *
 * V2.0 (G0, V2-011): max HR is FITTED to Google Health's Active Zone Minutes on reference days ([MaxHrFit]), never
 * driven by measured peaks. The fit gave 190 (best of 160–200; 220 − age and measured percentiles fit worse; details
 * in the private V2_API.md, not in the source).
 * Order: the user's fitted / set value ([manual], Goals.maxHrFit) → [MaxHr.FIT_DEFAULT] (190). A measured value can
 * never raise or lower it, so sensor plateaus (long flat readings near the maximum) have no effect. 220 − age is info only.
 */
data class MaxHrInfo(val value: Int, val source: MaxHrSource, val manual: Int?, val ageBased: Int?, val measured12m: Int?)

object MaxHr {
    /** Best fit to Google Health AZM (V2-011). Also the old no-data default. */
    const val FIT_DEFAULT = 190

    fun resolve(manual: Int?, birthYear: Int?, measured12m: Int?, year: Int): MaxHrInfo {
        val age = birthYear?.let { (220 - (year - it)).coerceIn(120, 230) }
        val meas = measured12m?.takeIf { it in 120..230 }
        val set = manual?.takeIf { it in 120..230 }
        return if (set != null) MaxHrInfo(set, MaxHrSource.MANUAL, set, age, meas)
        else MaxHrInfo(FIT_DEFAULT, MaxHrSource.DEFAULT, null, age, meas)
    }
}

/**
 * V2-011 max HR fit, pure: the max HR in [range] whose heart points (same zone rule, per-day resting HR) best match a
 * reference per day (Google Health AZM). Error = sum of absolute differences; ties → the lower max HR.
 * [days] = per-minute bpm of each reference day (0 = no reading) with that day's resting HR.
 */
object MaxHrFit {
    data class Day(val bpm: ByteArray, val restHr: Int, val reference: Int)
    data class Result(val maxHr: Int, val error: Int, val points: List<Int>, val table: Map<Int, List<Int>>)

    fun pointsOf(bpm: ByteArray, maxHr: Int, restHr: Int): Int = HrZones.intensity(HrZones.minutes(bpm, maxHr, restHr))

    fun fit(days: List<Day>, range: IntRange = 160..200): Result? {
        if (days.isEmpty()) return null
        val table = range.associateWith { mx -> days.map { pointsOf(it.bpm, mx, it.restHr) } }
        val best = table.entries.minWith(compareBy<Map.Entry<Int, List<Int>>> { e -> e.value.indices.sumOf { Math.abs(e.value[it] - days[it].reference) } }.thenBy { it.key })
        return Result(best.key, best.value.indices.sumOf { Math.abs(best.value[it] - days[it].reference) }, best.value, table)
    }

    /** Robust measured peak, info only: the [pct] percentile of all measured minutes (spikes cannot move it much). */
    fun percentile(minutes: IntArray, pct: Double = 99.9): Int? {
        if (minutes.isEmpty()) return null
        val s = minutes.sortedArray()
        val i = Math.ceil(pct / 100.0 * s.size).toInt().coerceIn(1, s.size) - 1
        return s[i]
    }
}

// ============================================================================ weekly cardio pace (R2; same semantics as ui WeeklyPace)

enum class CardioPace { NO_DATA, NEW_WEEK, BEHIND, ON_TRACK, AHEAD, DONE }

object CardioPaceMath {
    const val DAY_START_H = 7
    const val DAY_END_H = 22

    /** Share of the week's active time (07:00–22:00 each day, Mon–Sun) gone at [now]. */
    fun fraction(weekStart: LocalDate, now: LocalDateTime): Double {
        val d = ChronoUnit.DAYS.between(weekStart, now.toLocalDate()).coerceIn(0, 6).toDouble()
        val h = now.hour + now.minute / 60.0
        val t = ((h - DAY_START_H) / (DAY_END_H - DAY_START_H)).coerceIn(0.0, 1.0)
        return ((d + t) / 7.0).coerceIn(0.0, 1.0)
    }

    /** Expected by now, tolerance max(10 % of expected, 5 % of goal); status like WeeklyPace. */
    fun status(actual: Double?, goal: Int, f: Double): CardioPace {
        val a = actual ?: return CardioPace.NO_DATA
        if (goal <= 0) return CardioPace.NO_DATA
        val e = goal * f
        val tol = maxOf(0.10 * e, 0.05 * goal)
        return when {
            a >= goal -> CardioPace.DONE
            f < 0.02 && a < tol -> CardioPace.NEW_WEEK
            a >= e + tol -> CardioPace.AHEAD
            a < e - tol -> CardioPace.BEHIND
            else -> CardioPace.ON_TRACK
        }
    }
}

// ============================================================================ calories (P-KCAL, Q3)

object Kcal {
    /**
     * v5 (D1): sums of energy records ([start, end) ms, kcal) per bucket ([bounds] ascending, n + 1 values), each
     * record split pro rata over the buckets it covers and cut at [nowMs]. null = no record touches the bucket.
     */
    fun bucketSums(records: List<Triple<Long, Long, Double>>, bounds: LongArray, nowMs: Long): Array<Double?> {
        val out = arrayOfNulls<Double>(maxOf(0, bounds.size - 1))
        for ((s, e0, v) in records) {
            if (v.isNaN() || v < 0) continue
            val e = maxOf(e0, s)
            for (i in out.indices) {
                val a = maxOf(s, bounds[i]); val b = minOf(e, bounds[i + 1], nowMs)
                val part = when {
                    e == s -> if (s >= bounds[i] && s < bounds[i + 1] && s <= nowMs) v else continue
                    b <= a -> continue
                    else -> v * (b - a).toDouble() / (e - s)
                }
                out[i] = (out[i] ?: 0.0) + part
            }
        }
        return out
    }

    /** End of the read window for days [..to]: NOW when the range reaches today, else the start of the day after [to]. */
    fun windowEnd(to: LocalDate, today: LocalDate, now: LocalDateTime): LocalDateTime =
        if (!to.isBefore(today)) now else to.plusDays(1).atStartOfDay()
}

// ============================================================================ workout types (v5 S5)

/**
 * One name and one colour slot per kind of workout ([ordinal] = stable colour index for the UI). [OTHER] ("Workout")
 * only when neither the exercise type nor the title says what it was.
 */
enum class WorkoutGroup(val label: String) {
    STRENGTH("Strength"), CYCLING("Cycling"), WALKING("Walking"), RUNNING("Running"), TREADMILL("Treadmill"),
    ROWING("Rowing"), ELLIPTICAL("Elliptical"), SWIMMING("Swimming"), HIIT("HIIT"), YOGA("Yoga"), HIKING("Hiking"),
    STAIRS("Stairs"), SPORTS("Sports"), DANCING("Dancing"), OTHER("Workout");

    companion object {
        /** From an app's title ("Stationary bike", "Treadmill walk", "Upper body"…); null = says nothing. */
        fun fromTitle(title: String?): WorkoutGroup? {
            val t = title?.lowercase() ?: return null
            return when {
                "treadmill" in t -> TREADMILL
                listOf("bike", "cycl", "spin", "biking").any { it in t } -> CYCLING
                listOf("strength", "weight", "upper body", "lower body", "full body", "gym", "lifting", "core").any { it in t } -> STRENGTH
                "hiit" in t || "interval" in t -> HIIT
                "row" in t -> ROWING
                "ellipt" in t || "cross trainer" in t || "crosstrainer" in t -> ELLIPTICAL
                "swim" in t -> SWIMMING
                "yoga" in t || "pilates" in t || "stretch" in t -> YOGA
                "hike" in t || "hiking" in t -> HIKING
                "run" in t || "jog" in t -> RUNNING
                "walk" in t -> WALKING
                "stair" in t -> STAIRS
                "danc" in t -> DANCING
                else -> null
            }
        }

        /** Exercise type's group ([byType], from ExerciseTypes) — a title naming a treadmill wins, OTHER is refined by the title. */
        fun of(byType: WorkoutGroup?, title: String?): WorkoutGroup {
            val fromTitle = fromTitle(title)
            return when {
                fromTitle == TREADMILL -> TREADMILL
                byType != null && byType != OTHER -> byType
                else -> fromTitle ?: OTHER
            }
        }
    }
}

// ============================================================================ workouts: minutes, weeks, streak (Q13, Q14, Q19)

object WorkoutMath {
    /**
     * One session from copies of the same workout ([copies] in preference order, first = preferred app's): start / end
     * = union; type and title from the best-described copy (typed and titled, else typed, else titled); kcal, avg HR
     * and intensity from the preferred copy, else the next copy that has them.
     */
    fun merged(copies: List<Workout>): Workout {
        val rep = copies[0]
        if (copies.size == 1) return rep
        val meta = copies.firstOrNull { !it.title.isNullOrBlank() && it.type != 0 } ?: copies.firstOrNull { it.type != 0 }
            ?: copies.firstOrNull { !it.title.isNullOrBlank() } ?: rep
        return rep.copy(
            start = copies.minOf { it.start }, end = copies.maxOf { it.end },
            type = meta.type, title = meta.title ?: copies.firstNotNullOfOrNull { it.title?.takeIf { t -> t.isNotBlank() } },
            kcal = rep.kcal ?: copies.firstNotNullOfOrNull { it.kcal },
            avgHr = rep.avgHr ?: copies.firstNotNullOfOrNull { it.avgHr },
            zoneMin = rep.zoneMin ?: copies.firstNotNullOfOrNull { it.zoneMin },
        )
    }

    /** Minutes covered by the UNION of the sessions inside [dayStart, dayEnd): overlapping sessions count once. */
    fun unionMinutes(sessions: List<Pair<Long, Long>>, dayStart: Long, dayEnd: Long): Int {
        val cl = sessions.map { (a, b) -> maxOf(a, dayStart) to minOf(b, dayEnd) }.filter { it.second > it.first }.sortedBy { it.first }
        var total = 0L
        var s = Long.MIN_VALUE; var e = Long.MIN_VALUE
        for ((a, b) in cl) {
            if (a > e) { if (e > s) total += e - s; s = a; e = b } else if (b > e) e = b
        }
        if (e > s) total += e - s
        return (total / 60_000L).toInt()
    }

    fun weekStart(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** Items whose start falls on local [day] in [zone], oldest first (e.g. yesterday's workouts, all of them). */
    fun <T> onDay(items: List<T>, start: (T) -> Long, day: LocalDate, zone: java.time.ZoneId): List<T> =
        items.filter { java.time.Instant.ofEpochMilli(start(it)).atZone(zone).toLocalDate() == day }.sortedBy(start)

    /** Sessions and DAYS with ≥ 1 workout in Mon..Sun of [anyDay]'s week (14 sessions over 3 days = 3 days). */
    data class Week(val weekStart: LocalDate, val sessions: Int, val days: Int, val daysGoal: Int, val dayList: List<LocalDate>)

    fun week(sessionDays: List<LocalDate>, anyDay: LocalDate, daysGoal: Int): Week {
        val ws = weekStart(anyDay)
        val inWeek = sessionDays.filter { !it.isBefore(ws) && it.isBefore(ws.plusDays(7)) }
        val days = inWeek.distinct().sorted()
        return Week(ws, inWeek.size, days.size, daysGoal, days)
    }

    /**
     * Workout streak in DAYS: consecutive days with ≥ 1 workout ending today, or yesterday when today has none yet
     * (a streak isn't broken before the day is over).
     */
    fun streakDays(sessionDays: Collection<LocalDate>, today: LocalDate): Int {
        val set = sessionDays.toHashSet()
        var d = if (today in set) today else today.minusDays(1)
        var n = 0
        while (d in set) { n++; d = d.minusDays(1) }
        return n
    }

    /** Longest run of consecutive workout days. */
    fun bestStreakDays(sessionDays: Collection<LocalDate>): Int {
        val sorted = sessionDays.distinct().sorted()
        var best = 0; var run = 0; var prev: LocalDate? = null
        for (d in sorted) { run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1; best = maxOf(best, run); prev = d }
        return best
    }
}

// ============================================================================ rings and history (P-RINGS, Q17)

/** One day of the three v4 rings. Values are null when there is no data that day. */
data class RingDay(
    val day: LocalDate,
    val steps: Double?, val stepsGoal: Int,
    val workoutMin: Int, val workoutMinGoal: Int,
    /** Intensity minutes of THIS day. */
    val intensity: Int?,
    /** Intensity minutes Mon..this day (weekly cardio as of this day). */
    val weekIntensity: Int, val weekIntensityGoal: Int,
    /** v4.1 (R1): DAILY intensity goal = weekly goal ÷ 7 rounded up (default 22). */
    val intensityGoal: Int = HrZones.dailyGoal(weekIntensityGoal),
) {
    val stepsProgress: Float get() = frac(steps, stepsGoal)
    val workoutProgress: Float get() = frac(workoutMin.toDouble(), workoutMinGoal)
    /** v4.1 (R1): the intensity ring is DAILY: this day's intensity ÷ daily goal. */
    val intensityProgress: Float get() = frac(intensity?.toDouble(), intensityGoal)
    /** Weekly cardio as of this day (not a ring any more; see V4.weeklyCardio). */
    val weekIntensityProgress: Float get() = frac(weekIntensity.toDouble(), weekIntensityGoal)
    val stepsClosed: Boolean get() = stepsProgress >= 1f
    val workoutClosed: Boolean get() = workoutProgress >= 1f
    val intensityClosed: Boolean get() = intensityProgress >= 1f
    /** v5 (R1) ring 3 "Heart week": heart points Mon..this day as a share of the weekly goal (1.0 = 100 %, may exceed). */
    val heartWeekProgress: Float get() = weekIntensityProgress
    /** Heart week in whole percent, for the value at the end of the ring arc ("64%"). */
    val heartWeekPct: Int get() = Math.round(heartWeekProgress * 100f)
    val heartWeekClosed: Boolean get() = heartWeekProgress >= 1f
    /** v5: training minutes today (ring 2 "Training (min)"). */
    val trainingMin: Int get() = workoutMin
    /**
     * Ring order, outer → inner: Workout minutes · Steps · Heart points. V2 (D3, D22): ring 3 is the DAY's heart points
     * vs the daily target ceil(weekly ÷ 7) (was the week-to-date %); every [RingValue.text] is a plain number of its
     * own ring ("34", "7,240", "29"), never a % (V2-111). Same values as V2.rings(Day).
     */
    val ordered: List<RingValue> get() = listOf(
        RingValue(RingValue.TRAINING, "Workout minutes", workoutMin.toDouble(), workoutMinGoal, workoutProgress, workoutMin.toString()),
        RingValue(RingValue.STEPS, "Steps", steps ?: 0.0, stepsGoal, stepsProgress, V2Math.intText(steps ?: 0.0)),
        RingValue(RingValue.HEART_WEEK, "Heart points", (intensity ?: 0).toDouble(), intensityGoal, intensityProgress, (intensity ?: 0).toString()),
    )

    /** All three rings of the day closed (V2: ring 3 = the day's heart points, D3). */
    val closed: Boolean get() = stepsClosed && workoutClosed && intensityClosed

    private fun frac(v: Double?, goal: Int) = if (v == null || goal <= 0) 0f else (v / goal).toFloat().coerceAtLeast(0f)
}

/** One ring of [RingDay.ordered]: [progress] 1.0 = closed (may exceed). */
data class RingValue(val id: String, val label: String, val value: Double, val goal: Int, val progress: Float, val text: String) {
    companion object {
        const val TRAINING = "exercise_min"
        const val STEPS = "steps"
        const val HEART_WEEK = "heart_week"
        /** 950 → "950", 6240 → "6.2k", 12400 → "12k". */
        fun compact(v: Double): String = when {
            v < 1000 -> Math.round(v).toString()
            v < 10_000 -> String.format(java.util.Locale.US, "%.1fk", v / 1000).replace(".0k", "k")
            else -> "${Math.round(v / 1000)}k"
        }
    }
}

// ============================================================================ v6: active days, workouts week, body, VO₂

/** v6: an ACTIVE DAY is a day with at least [MIN_POINTS] heart points. V2: see [ActiveDayRule] (Goals.activeDayRule). */
object ActiveDay {
    const val MIN_POINTS = 10
    fun isActive(points: Int?): Boolean = (points ?: 0) >= MIN_POINTS
}

/**
 * V2 (D18, G0): what counts as a workout day. Default [WORKOUT]: at least one recorded workout of ≥ [minWorkoutMin] min
 * that itself earned ≥ [minWorkoutPoints] heart points (slow auto-detected walks don't count). [POINTS]: a day with
 * ≥ [minDayPoints] heart points. All thresholds are settings (Goals).
 */
data class ActiveDayRule(
    val mode: Mode = Mode.WORKOUT,
    val minWorkoutMin: Int = 20,
    val minWorkoutPoints: Int = 10,
    val minDayPoints: Int = 10,
) {
    enum class Mode { WORKOUT, POINTS }

    /** One session qualifies (WORKOUT mode): long enough AND its own heart points (null = no heart rate = 0). */
    fun qualifies(w: Workout): Boolean = w.minutes >= minWorkoutMin && (w.zoneMin ?: 0) >= minWorkoutPoints

    /** Workout days among [days]: by the sessions starting that day ([sessionsByDay]) or the day's heart points. */
    fun activeDays(days: Collection<java.time.LocalDate>, sessionsByDay: Map<java.time.LocalDate, List<Workout>>, pointsByDay: Map<java.time.LocalDate, Int>): Set<java.time.LocalDate> =
        days.filterTo(HashSet()) { d ->
            when (mode) {
                Mode.WORKOUT -> sessionsByDay[d].orEmpty().any { qualifies(it) }
                Mode.POINTS -> (pointsByDay[d] ?: 0) >= minDayPoints
            }
        }
}

/**
 * v6 "Workouts" week (Mon–Sun): [activeDays] (≥ 10 heart points) of [goal], [sessions] (de-duplicated workouts) and
 * [types] = (kind, sessions) for the type chips, most first ("Strength 3 · Cycling 2 · Walking 1").
 */
data class WorkoutsWeek(
    val weekStart: LocalDate, val activeDays: Int, val goal: Int, val activeDayList: List<LocalDate>,
    val sessions: Int, val types: List<Pair<WorkoutGroup, Int>>,
)

/** v6 weight composition, one per weigh-in day: fat mass = weight − lean mass (both from the scale), fat % as delivered. */
data class WeighIn(val day: LocalDate, val time: Long, val weightKg: Double, val leanKg: Double?, val fatKg: Double?, val fatPct: Double?)

/** v6: latest value vs the value [months] months before it (null when there is none that old). */
data class ChangeVs(val latest: DayValue, val before: DayValue?, val change: Double?)

/**
 * V2 (G0): plausible ranges; readings outside are impossible (scale misreports) and hidden everywhere. Everything else
 * is shown as delivered.
 */
object Plausible {
    val FAT_PCT = 5.0..75.0
    val WEIGHT = 25.0..350.0
    val LEAN_KG = 15.0..250.0
    /** QA 18.2: heart rate below 35 bpm is a sensor artefact (e.g. a 33 bpm daily minimum); hidden. */
    val HR_BPM = 35.0..250.0
    val RHR_BPM = 35.0..140.0
    const val HR_MIN_BPM = 35
}

object BodyMath {
    fun workoutsWeek(weekStart: LocalDate, pointsByDay: Map<LocalDate, Int>, sessionKinds: List<WorkoutGroup>, goal: Int): WorkoutsWeek {
        val days = (0..6).map { weekStart.plusDays(it.toLong()) }.filter { ActiveDay.isActive(pointsByDay[it]) }
        val types = sessionKinds.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<WorkoutGroup, Int>> { it.value }.thenBy { it.key.ordinal }).map { it.key to it.value }
        return WorkoutsWeek(weekStart, days.size, goal, days, sessionKinds.size, types)
    }

    /** Weigh-ins with lean mass / fat % of the same day; fat kg only when lean < weight. */
    fun composition(weights: List<DayValue>, lean: Map<LocalDate, Double>, fatPct: Map<LocalDate, Double>): List<WeighIn> =
        weights.map { w ->
            // V2 (G0): lean ≥ 95 % of weight means body fat < 5 %: an impossible reading, hidden
            val l = lean[w.day]?.takeIf { it > 0 && it < w.value * (1 - Plausible.FAT_PCT.start / 100.0) }
            WeighIn(w.day, w.time, w.value, l, l?.let { w.value - it }, fatPct[w.day])
        }

    fun changeVs(series: List<DayValue>, months: Long = 3): ChangeVs? {
        val latest = series.maxByOrNull { it.day } ?: return null
        val cut = latest.day.minusMonths(months)
        val before = series.filter { !it.day.isAfter(cut) }.maxByOrNull { it.day }
        return ChangeVs(latest, before, before?.let { latest.value - it.value })
    }
}

enum class PeriodKind { WEEK, MONTH, YEAR }
enum class TrendBucket { DAY, WEEK, MONTH }

/** Averages per day of a period (null = no data in it). */
data class PeriodAverages(val steps: Double?, val workoutMin: Double?, val intensity: Double?, val kcal: Double?)

data class TrendPoint(val start: LocalDate, val steps: Double?, val workoutMin: Double?, val intensity: Double?, val closedDays: Int, val days: Int)

data class PeriodSummary(
    val kind: PeriodKind,
    val start: LocalDate,
    /** Exclusive. */
    val end: LocalDate,
    /** Days of the period up to today. */
    val days: List<RingDay>,
    /** Share of elapsed days with each ring closed, and with all three (0..1). */
    val stepsSuccess: Float, val workoutSuccess: Float, val intensitySuccess: Float, val allSuccess: Float,
    val averages: PeriodAverages,
    val previous: PeriodAverages,
    /**
     * v4.1 fix: the period contains today — label it "so far". Today is still running, so it only counts in a ring's
     * success % and average once that ring is closed (all three for "all rings" and calories); [countedDays] = days
     * behind [allSuccess], [closedDays] = of those, days with all three closed ("closedDays of countedDays days").
     */
    val soFar: Boolean = false,
    val countedDays: Int = days.size,
    val closedDays: Int = days.count { it.closed },
)

object HistoryMath {
    fun bounds(kind: PeriodKind, anchor: LocalDate): Pair<LocalDate, LocalDate> = when (kind) {
        PeriodKind.WEEK -> WorkoutMath.weekStart(anchor).let { it to it.plusDays(7) }
        PeriodKind.MONTH -> anchor.withDayOfMonth(1).let { it to it.plusMonths(1) }
        PeriodKind.YEAR -> anchor.withDayOfYear(1).let { it to it.plusYears(1) }
    }

    fun previous(kind: PeriodKind, start: LocalDate): Pair<LocalDate, LocalDate> = when (kind) {
        PeriodKind.WEEK -> start.minusDays(7) to start
        PeriodKind.MONTH -> start.minusMonths(1) to start
        PeriodKind.YEAR -> start.minusYears(1) to start
    }

    /**
     * Weekly intensity as of each day: running sum Mon..day of [intensity] (missing days count 0), restarting each
     * Monday. [intensity] must cover from the Monday of the first day.
     */
    fun weekRunning(intensity: Map<LocalDate, Int>, days: List<LocalDate>): Map<LocalDate, Int> {
        // V2-051: one pass over the days in order (was a re-sum per day); unsorted input still works per week
        val out = HashMap<LocalDate, Int>()
        var week: LocalDate? = null; var s = 0; var x: LocalDate? = null
        for (d in days.sorted()) {
            val ws = WorkoutMath.weekStart(d)
            if (ws != week) { week = ws; s = 0; x = ws }
            while (!x!!.isAfter(d)) { s += intensity[x] ?: 0; x = x.plusDays(1) }
            out[d] = s
        }
        return out
    }

    fun share(days: List<RingDay>, f: (RingDay) -> Boolean): Float = if (days.isEmpty()) 0f else days.count(f).toFloat() / days.size

    /** The days counted for one ring: past days, plus [today] only once [closed] holds for it (v4.1 fix). */
    fun counted(days: List<RingDay>, today: LocalDate?, closed: (RingDay) -> Boolean): List<RingDay> =
        days.filter { it.day != today || closed(it) }

    fun averages(days: List<RingDay>, kcal: Map<LocalDate, Double>): PeriodAverages {
        fun avg(xs: List<Double>) = if (xs.isEmpty()) null else xs.average()
        return PeriodAverages(
            avg(days.mapNotNull { it.steps }),
            avg(days.map { it.workoutMin.toDouble() }),
            avg(days.mapNotNull { it.intensity?.toDouble() }),
            avg(days.mapNotNull { kcal[it.day] }),
        )
    }

    /**
     * [today] (v4.1 fix): when the period contains it, today counts in a ring's success % and average only once that
     * ring is closed (all three rings for "all" and calories), and the summary is marked [PeriodSummary.soFar]. A
     * running day would otherwise drag every rate and average down until midnight. null = the old behaviour.
     */
    fun summary(kind: PeriodKind, start: LocalDate, end: LocalDate, days: List<RingDay>, prevDays: List<RingDay>, kcal: Map<LocalDate, Double>,
                today: LocalDate? = null): PeriodSummary {
        val soFar = today != null && !today.isBefore(start) && today.isBefore(end)
        val t = if (soFar) today else null
        fun avg(xs: List<Double>) = if (xs.isEmpty()) null else xs.average()
        val stepsDays = counted(days, t) { it.stepsClosed }
        val workoutDays = counted(days, t) { it.workoutClosed }
        val intensityDays = counted(days, t) { it.heartWeekClosed }
        val allDays = counted(days, t) { it.closed }
        val averages = PeriodAverages(
            avg(stepsDays.mapNotNull { it.steps }),
            avg(workoutDays.map { it.workoutMin.toDouble() }),
            avg(intensityDays.mapNotNull { it.intensity?.toDouble() }),
            avg(allDays.mapNotNull { kcal[it.day] }),
        )
        return PeriodSummary(
            kind, start, end, days,
            share(stepsDays) { it.stepsClosed }, share(workoutDays) { it.workoutClosed }, share(intensityDays) { it.heartWeekClosed }, share(allDays) { it.closed },
            averages, averages(prevDays, kcal),
            soFar, allDays.size, allDays.count { it.closed },
        )
    }

    /** Trend buckets (per day, ISO week or month): daily averages and closed days per bucket. */
    fun trend(days: List<RingDay>, by: TrendBucket): List<TrendPoint> {
        fun key(d: LocalDate) = when (by) { TrendBucket.DAY -> d; TrendBucket.WEEK -> WorkoutMath.weekStart(d); TrendBucket.MONTH -> d.withDayOfMonth(1) }
        return days.groupBy { key(it.day) }.toSortedMap().map { (k, ds) ->
            fun avg(xs: List<Double>) = if (xs.isEmpty()) null else xs.average()
            TrendPoint(k, avg(ds.mapNotNull { it.steps }), avg(ds.map { it.workoutMin.toDouble() }), avg(ds.mapNotNull { it.intensity?.toDouble() }), ds.count { it.closed }, ds.size)
        }
    }

    fun daysBetween(a: LocalDate, b: LocalDate): Long = ChronoUnit.DAYS.between(a, b)
}

// ============================================================================ source names (Q16)

/**
 * Human app name for a Health Connect data origin (package name). Never shows an identifier: unknown packages are
 * resolved through the installed app's label ([resolver], set on Android), else a readable last segment; anything that
 * is not a package name (no dot, UUID / hex id) becomes "Unknown app".
 */
object SourceNames {
    const val PHONE = "This phone (Health Connect)"

    private val NAMES = mapOf(
        "com.fitbit.FitbitMobile" to "Google Health",
        "com.google.android.apps.fitness" to "Google Fit",
        "com.google.android.apps.healthdata" to PHONE,
        "com.android.healthconnect.controller" to PHONE,
        "com.google.android.healthconnect.controller" to PHONE,
        "android" to PHONE,
        "com.withings.wiscale2" to "Withings",
        "com.sec.android.app.shealth" to "Samsung Health",
        "com.renpho.health" to "Renpho",
        "com.qingniu.renpho" to "Renpho",
        "lu.boud.gymsync" to "GymSync",
        "com.boudhealth" to "Health BB (tape)",
        "com.apple.health.import" to "Apple Health (import)",
        "com.apple.health.import.intervalhr" to "Apple Health (import, approximate interval HR)",
        "health.connect.merged" to "Health Connect (merged)",
        "manual" to "Entered in Health BB",
        "calculated" to "Calculated",
    )

    /** Installed-app label lookup (Android PackageManager); null = not installed / not visible. */
    @Volatile var resolver: ((String) -> String?)? = null

    private val UUID = Regex("^[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}$")
    private val HEX = Regex("^[0-9a-fA-F]{12,}$")

    fun isIdentifier(s: String): Boolean = UUID.matches(s) || HEX.matches(s)

    fun label(pkg: String): String {
        val p = pkg.trim()
        NAMES[p]?.let { return it }
        // the phone's own step counter writes as "com.android.healthconnect.phone.<id>" (seen on the owner's Fold)
        if (p.startsWith("com.android.healthconnect.phone") || p.startsWith("com.google.android.healthconnect.phone")) return PHONE
        if (p.isEmpty() || isIdentifier(p) || !p.contains('.')) return "Unknown app"
        resolver?.invoke(p)?.takeIf { it.isNotBlank() && !isIdentifier(it) }?.let { return it }
        val seg = p.substringAfterLast('.')
        return if (seg.isEmpty() || isIdentifier(seg)) "Unknown app" else seg.replaceFirstChar { it.uppercase() }
    }

    /** "a,b" (as stored for aggregates) → "Google Health, Withings"; duplicates collapsed. */
    fun labels(pkgs: String?): String? =
        pkgs?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map { label(it) }?.distinct()?.joinToString(", ")?.ifEmpty { null }
}
