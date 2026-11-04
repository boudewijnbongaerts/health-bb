package com.boudhealth.ui.activity

import com.boudhealth.data.WorkoutMath

import com.boudhealth.data.V4

import com.boudhealth.data.RingDay

import com.boudhealth.data.CaloriesDay

import android.content.Context
import androidx.health.connect.client.records.ExerciseSessionRecord
import com.boudhealth.data.Derive
import com.boudhealth.data.ExerciseTypes
import com.boudhealth.data.WorkoutGroup
import com.boudhealth.data.Goals
import com.boudhealth.data.Local
import com.boudhealth.data.M
import com.boudhealth.data.MetricEnv
import com.boudhealth.data.Range
import com.boudhealth.data.Window
import com.boudhealth.data.Workout
import com.boudhealth.ui.components.Fmt
import com.boudhealth.ui.components.Glyph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.ceil

/*
 * Read-only helpers for the Activity area. Everything comes from the cache via the public data API
 * (MetricEnv, Cache, Local, Derive); nothing here calls Health Connect or writes anything.
 * All loaders are suspend and switch to Dispatchers.IO themselves.
 */

// =====================================================================================================================
// Workout presentation helpers
// =====================================================================================================================

internal object WorkoutUi {
    /** 0 strength, 1 cardio, 2 other. */
    fun category(w: Workout): Int = ExerciseTypes.category(w.type)

    // owner: never "Other"; the kind's name comes from ExerciseTypes.typeName (WorkoutGroup label, "Workout" when unknown)
    fun categoryName(cat: Int): String = when (cat) { 0 -> "Strength"; 1 -> "Cardio"; else -> "Workout" }

    fun glyph(w: Workout): Glyph = when (ExerciseTypes.group(w)) {
        WorkoutGroup.CYCLING -> Glyph.Bike
        WorkoutGroup.RUNNING, WorkoutGroup.TREADMILL -> Glyph.Run
        WorkoutGroup.WALKING, WorkoutGroup.HIKING, WorkoutGroup.STAIRS -> Glyph.Walk
        WorkoutGroup.STRENGTH -> Glyph.Dumbbell
        WorkoutGroup.SWIMMING, WorkoutGroup.ROWING -> Glyph.Drop
        else -> Glyph.Pulse
    }
    fun color(w: Workout): androidx.compose.ui.graphics.Color = com.boudhealth.ui.theme.Cat.workoutColor(w)

    fun name(w: Workout): String = ExerciseTypes.typeName(w)

    fun timeRange(w: Workout): String = "${Fmt.hm(w.start)}–${Fmt.hm(w.end)}"

    fun day(w: Workout, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(w.start).atZone(zone).toLocalDate()

    fun weekStart(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** "Today" / "Yesterday" / "Wednesday" (this week) / "Tue 27 Oct" + secondary date text (or null). */
    fun dayHeader(d: LocalDate, today: LocalDate = LocalDate.now()): Pair<String, String?> {
        val loc = Locale.getDefault()
        val days = ChronoUnit.DAYS.between(d, today)
        return when {
            days == 0L -> "Today" to DateTimeFormatter.ofPattern("EEE d MMM", loc).format(d)
            days == 1L -> "Yesterday" to DateTimeFormatter.ofPattern("EEE d MMM", loc).format(d)
            days in 2L..6L -> d.dayOfWeek.getDisplayName(TextStyle.FULL, loc) to DateTimeFormatter.ofPattern("d MMM", loc).format(d)
            d.year == today.year -> DateTimeFormatter.ofPattern("EEE d MMM", loc).format(d) to null
            else -> DateTimeFormatter.ofPattern("EEE d MMM yyyy", loc).format(d) to null
        }
    }

    /** Most common source label among [ws] (named once in the header chip). */
    fun mainSource(ws: List<Workout>): String? = ws.mapNotNull { it.source }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    /** Time-weighted average HR over sessions that have one. */
    fun avgHr(ws: List<Workout>): Int? {
        val withHr = ws.filter { it.avgHr != null && it.minutes > 0 }
        val mins = withHr.sumOf { it.minutes }
        if (mins == 0) return null
        return Math.round(withHr.sumOf { it.avgHr!!.toDouble() * it.minutes } / mins).toInt()
    }
}

// =====================================================================================================================
// Weekly workout-days goal + streak (C_SPEC §1: counted in workout DAYS per Mon–Sun week)
// =====================================================================================================================

internal data class WeekMark(val weekStart: LocalDate, val days: Int, val met: Boolean, val current: Boolean)

/**
 * [days] workout days in the week of the reference day, against [goal]; [streak] weeks in a row meeting the goal ending
 * at that week (that week counts once met); [best] longest streak ever; [weeks] the last 8 weeks, oldest first.
 */
internal data class WorkoutWeek(val days: Int, val goal: Int, val streak: Int, val best: Int, val weeks: List<WeekMark>)

internal object WorkoutStats {
    /** Distinct workout days per week start. */
    fun daysPerWeek(all: List<Workout>, zone: ZoneId): Map<LocalDate, Int> =
        all.map { WorkoutUi.day(it, zone) }.distinct().groupingBy { WorkoutUi.weekStart(it) }.eachCount()

    fun streakAt(counts: Map<LocalDate, Int>, goal: Int, at: LocalDate): Int {
        var n = 0
        var wk = WorkoutUi.weekStart(at)
        if ((counts[wk] ?: 0) >= goal) n++
        wk = wk.minusWeeks(1)
        while ((counts[wk] ?: 0) >= goal) { n++; wk = wk.minusWeeks(1) }
        return n
    }

    fun best(counts: Map<LocalDate, Int>, goal: Int): Int {
        val met = counts.filterValues { it >= goal }.keys.sorted()
        var best = 0; var run = 0; var prev: LocalDate? = null
        for (w in met) {
            run = if (prev != null && prev.plusWeeks(1) == w) run + 1 else 1
            if (run > best) best = run
            prev = w
        }
        return best
    }

    fun summary(all: List<Workout>, goal: Int, at: LocalDate, today: LocalDate, zone: ZoneId): WorkoutWeek {
        val counts = daysPerWeek(all, zone)
        val g = goal.coerceAtLeast(1)
        val wk = WorkoutUi.weekStart(at)
        val thisWeek = WorkoutUi.weekStart(today)
        val weeks = (7 downTo 0).map { i ->
            val s = wk.minusWeeks(i.toLong())
            val n = counts[s] ?: 0
            WeekMark(s, n, n >= g, s == thisWeek)
        }
        return WorkoutWeek(counts[wk] ?: 0, g, streakAt(counts, g, at), maxOf(best(counts, g), streakAt(counts, g, at)), weeks)
    }
}

// =====================================================================================================================
// Activity tab
// =====================================================================================================================

internal fun frac(v: Double, goal: Double): Float = if (goal <= 0.0) 0f else (v / goal).toFloat().coerceAtLeast(0f)

/** Activity tab data (v4): rings from V4.ringDays, workouts by days (Q19), yesterday's sessions (Q13). */
internal data class ActivityOverview(
    val goals: Goals,
    /** Last 7 days, oldest first; the last one is today. */
    val week7: List<RingDay>,
    /** Mon..today. */
    val week: List<RingDay>,
    /** Weekly-goal streak in weeks (kept for the streak sheet only). */
    val work: WorkoutWeek,
    /** Days with a workout vs the days goal, and sessions (Q19). */
    val wweek: WorkoutMath.Week,
    val lastWorkout: Workout?,
    /** All of yesterday's sessions, oldest first (Q13). */
    val yesterday: List<Workout>,
    /** Today's sessions, oldest first. */
    val todayWorkouts: List<Workout>,
    val calories: CaloriesDay,
    /** Consecutive days with a workout (Q14). */
    val streakDays: Int,
    val bestStreakDays: Int,
) {
    val today: RingDay get() = week7.last()
}

internal suspend fun loadOverview(context: Context): ActivityOverview = V4.load(context) { env ->
    val today = env.today
    val mon = WorkoutUi.weekStart(today)
    val rd = V4.ringDays(env, minOf(mon, today.minusDays(6)), today)
    val all = Local.workouts(env.cache, com.boudhealth.data.ALL_START, today)
    val work = WorkoutStats.summary(all, env.goals.workoutDaysWeek, today, today, env.zone)
    ActivityOverview(
        env.goals,
        rd.filter { !it.day.isBefore(today.minusDays(6)) },
        rd.filter { !it.day.isBefore(mon) },
        work, V4.workoutWeek(env), all.firstOrNull(), V4.yesterdayWorkouts(env), V4.workoutsOn(env, today),
        V4.calories(env, today), V4.streakDays(env), V4.bestStreakDays(env),
    )
}

// =====================================================================================================================
// Workouts page
// =====================================================================================================================

/** One chart slot (hour / day / week / month / year). [end] exclusive. */
internal data class Slot(
    val start: LocalDateTime,
    val end: LocalDateTime,
    val label: String,
    val tip: String,
    val today: Boolean,
    val future: Boolean,
)

internal data class WorkoutsPage(
    val window: Window,
    /** Sessions starting in the window, newest first. */
    val sessions: List<Workout>,
    val slots: List<Slot>,
    /** Minutes per slot per category [strength, cardio, other]. */
    val minutes: List<DoubleArray>,
    val labelEvery: Int,
    val scaleMax: Double,
    val totalMin: Int,
    val avgHr: Int?,
    val zoneMin: Int,
    val kcal: Double,
    val workoutDays: Int,
    /** Weekly goal state for the week of the period's last (or reference) day. */
    val week: WorkoutWeek,
    /** Workout days per week across the period (elapsed part only). */
    val avgDaysPerWeek: Double,
    val source: String?,
    /** v4 Q14: consecutive days with a workout (up to today / yesterday), and the best run. */
    val streakDays: Int = 0,
    val bestStreakDays: Int = 0,
    /** v4 Q19: sessions in the week of the reference day (days are in [week]). */
    val weekSessions: Int = 0,
)

private fun slotsFor(w: Window, firstDay: LocalDate?, now: LocalDateTime): Pair<List<Slot>, Int> {
    val loc = Locale.getDefault()
    val today = now.toLocalDate()
    val dm = DateTimeFormatter.ofPattern("EEE d MMM", loc)
    val out = ArrayList<Slot>()
    var every = 1
    when (w.range) {
        Range.DAY -> {
            val d0 = w.start.atStartOfDay()
            for (h in 0 until 24) {
                val s = d0.plusHours(h.toLong()); val e = s.plusHours(1)
                out += Slot(s, e, if (h % 6 == 0) String.format(loc, "%02d", h) else "", "${String.format(loc, "%02d:00", h)}–${String.format(loc, "%02d:00", (h + 1) % 24)}",
                    today = false, future = s.isAfter(now))
            }
            every = 6
        }
        Range.WEEK, Range.MONTH -> {
            var d = w.start
            while (d.isBefore(w.end)) {
                val label = if (w.range == Range.WEEK) Fmt.dayLetter(d) else d.dayOfMonth.toString()
                out += Slot(d.atStartOfDay(), d.plusDays(1).atStartOfDay(), label, dm.format(d), d == today, d.isAfter(today))
                d = d.plusDays(1)
            }
            every = if (w.range == Range.WEEK) 1 else 7
        }
        Range.QUARTER -> {
            var ws = WorkoutUi.weekStart(w.start)
            while (ws.isBefore(w.end)) {
                val we = ws.plusWeeks(1)
                out += Slot(ws.atStartOfDay(), we.atStartOfDay(), Fmt.shortDate(ws), "Week of ${Fmt.shortDate(ws)}",
                    !today.isBefore(ws) && today.isBefore(we), ws.isAfter(today))
                ws = we
            }
            every = 3
        }
        Range.YEAR -> {
            var m = w.start.withDayOfMonth(1)
            while (m.isBefore(w.end)) {
                val me = m.plusMonths(1)
                out += Slot(m.atStartOfDay(), me.atStartOfDay(), m.month.getDisplayName(TextStyle.NARROW, loc),
                    DateTimeFormatter.ofPattern("MMMM yyyy", loc).format(m), !today.isBefore(m) && today.isBefore(me), m.isAfter(today))
                m = me
            }
            every = 1
        }
        Range.ALL -> {
            // "All" starts at the first stored workout: months while that is ≤ 2 years back, else years
            val first = firstDay ?: today
            val m0 = first.withDayOfMonth(1)
            val months = ChronoUnit.MONTHS.between(m0, today.withDayOfMonth(1)) + 1
            if (months <= 24) {
                var m = m0
                while (!m.isAfter(today)) {
                    val me = m.plusMonths(1)
                    out += Slot(m.atStartOfDay(), me.atStartOfDay(),
                        if (m.monthValue == 1 || m == m0) DateTimeFormatter.ofPattern("MMM yy", loc).format(m) else m.month.getDisplayName(TextStyle.NARROW, loc),
                        DateTimeFormatter.ofPattern("MMMM yyyy", loc).format(m), !today.isBefore(m) && today.isBefore(me), false)
                    m = me
                }
                every = if (out.size > 12) 2 else 1
                return out to every
            }
            val y0 = first.year
            for (y in y0..today.year) {
                val s = LocalDate.of(y, 1, 1)
                out += Slot(s.atStartOfDay(), s.plusYears(1).atStartOfDay(), if (today.year - y0 > 6) "'" + (y % 100).toString().padStart(2, '0') else y.toString(),
                    y.toString(), y == today.year, false)
            }
            every = if (out.size > 12) 2 else 1
        }
    }
    return out to every
}

internal suspend fun loadWorkouts(context: Context, w: Window): WorkoutsPage {
    return V4.load(context) { env ->
        val zone = env.zone
        val today = env.today
        val now = LocalDateTime.now(zone)
        val all = Local.workouts(env.cache, com.boudhealth.data.ALL_START, today) // DATA FIX: all history (was 2010)
        val first = all.lastOrNull()?.let { WorkoutUi.day(it, zone) }
        val start = if (w.range == Range.ALL) (first ?: today) else w.start
        val sessions = all.filter { val d = WorkoutUi.day(it, zone); !d.isBefore(start) && d.isBefore(w.end) }
        val (slots, every) = slotsFor(w, first, now)

        fun ms(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()
        val bounds = slots.map { ms(it.start) to ms(it.end) }
        // v5 S5: minutes per workout kind (index = WorkoutGroup.ordinal)
        val minutes = slots.map { DoubleArray(WorkoutGroup.entries.size) }
        for (s in sessions) {
            val cat = ExerciseTypes.group(s).ordinal
            bounds.forEachIndexed { i, (a, b) ->
                val ov = minOf(s.end, b) - maxOf(s.start, a)
                if (ov > 0) minutes[i][cat] += ov / 60_000.0
            }
        }
        val maxV = minutes.maxOfOrNull { it.sum() } ?: 0.0
        val floor = when (w.range) { Range.DAY, Range.WEEK, Range.MONTH -> 60.0; Range.QUARTER -> 180.0; Range.YEAR -> 600.0; Range.ALL -> 60.0 }
        val scaleMax = maxOf(floor, maxV / 0.82)

        val days = sessions.map { WorkoutUi.day(it, zone) }.distinct().size
        val lastDay = minOf(w.end.minusDays(1), today)
        val refDay = if (w.range == Range.DAY) w.start else lastDay
        val week = WorkoutStats.summary(all, env.goals.workoutDaysWeek, refDay, today, zone)
        val elapsedDays = ChronoUnit.DAYS.between(start, minOf(w.end, today.plusDays(1))).coerceAtLeast(1)
        val weeks = (elapsedDays / 7.0).coerceAtLeast(1.0)

        WorkoutsPage(
            window = w,
            sessions = sessions,
            slots = slots,
            minutes = minutes,
            labelEvery = every,
            scaleMax = scaleMax,
            totalMin = sessions.sumOf { it.minutes },
            avgHr = WorkoutUi.avgHr(sessions),
            zoneMin = sessions.sumOf { it.zoneMin ?: 0 },
            kcal = sessions.sumOf { it.kcal ?: 0.0 },
            workoutDays = days,
            week = week,
            avgDaysPerWeek = days / weeks,
            source = WorkoutUi.mainSource(sessions),
            streakDays = V4.streakDays(env),
            bestStreakDays = V4.bestStreakDays(env),
            weekSessions = V4.workoutWeek(env, refDay).sessions,
        )
    }
}

// =====================================================================================================================
// Session detail
// =====================================================================================================================

/** Lower bounds (bpm) of the heart-rate-reserve zones used by [com.boudhealth.data.Zones]: 40 / 60 / 85 %. */
internal data class ZoneLimits(val rest: Double, val hrMax: Int, val fat: Int, val cardio: Int, val peak: Int) {
    /** 0 below, 1 fat burn, 2 cardio, 3 peak. */
    fun zoneOf(bpm: Int): Int = when {
        bpm >= peak -> 3
        bpm >= cardio -> 2
        bpm >= fat -> 1
        else -> 0
    }

    companion object {
        fun of(rest: Double, hrMax: Int): ZoneLimits {
            val r = (hrMax - rest).coerceAtLeast(20.0)
            return ZoneLimits(rest, hrMax, ceil(rest + 0.40 * r).toInt(), ceil(rest + 0.60 * r).toInt(), ceil(rest + 0.85 * r).toInt())
        }
    }
}

internal data class HrSample(val ms: Long, val bpm: Int)

internal data class SessionData(
    val workout: Workout,
    /** Per-minute heart rate (cache resolution) inside the session, oldest first. */
    val hr: List<HrSample>,
    val limits: ZoneLimits,
    /** Minutes per zone [below, fat burn, cardio, peak]. */
    val zoneMinutes: IntArray,
    val zonePoints: Int,
    val avgHr: Int?,
    val max: HrSample?,
)

internal suspend fun loadSession(context: Context, start: Long): SessionData? {
    val env = MetricEnv.load(context)
    return withContext(Dispatchers.IO) {
        val zone = env.zone
        val c = env.cache
        val d = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        val w = Local.workouts(c, d, d).firstOrNull { it.start == start }
            ?: c.workouts(start, start + 1).firstOrNull()?.toWorkout()
            ?: return@withContext null
        val hrMax = Derive.hrMax(c, env.goals)
        val rest = Derive.restOn(c.daily(M.RHR, d.minusDays(30), d), d)
        val limits = ZoneLimits.of(rest, hrMax)

        val hr = ArrayList<HrSample>()
        if (w.end > w.start) {
            val d1 = Instant.ofEpochMilli(w.end - 1).atZone(zone).toLocalDate()
            for (hd in c.hrDays(d, d1)) {
                val dayStart = hd.day.atStartOfDay(zone).toInstant().toEpochMilli()
                val m0 = ((w.start - dayStart) / 60_000L).coerceIn(0L, 1500L).toInt()
                val m1 = ((w.end - dayStart + 59_999L) / 60_000L).coerceIn(0L, 1500L).toInt()
                for (m in m0 until m1) {
                    val v = hd.at(m)
                    if (v > 0) hr += HrSample(dayStart + m * 60_000L, v)
                }
            }
        }
        hr.sortBy { it.ms }
        val zm = IntArray(4)
        hr.forEach { zm[limits.zoneOf(it.bpm)]++ }
        val points = w.zoneMin ?: (zm[1] + 2 * (zm[2] + zm[3]))
        val avg = w.avgHr?.toInt() ?: if (hr.isEmpty()) null else Math.round(hr.sumOf { it.bpm }.toDouble() / hr.size).toInt()
        SessionData(w, hr, limits, zm, points, avg, hr.maxByOrNull { it.bpm })
    }
}
