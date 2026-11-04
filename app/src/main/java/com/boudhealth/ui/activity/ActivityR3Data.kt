package com.boudhealth.ui.activity

import com.boudhealth.data.Best
import com.boudhealth.data.Bedtimes
import com.boudhealth.data.CardData
import com.boudhealth.data.ChangeForm
import com.boudhealth.data.CompareWindow
import com.boudhealth.data.Extreme
import com.boudhealth.data.Ghost
import com.boudhealth.data.GoalState
import com.boudhealth.data.GoalsFact
import com.boudhealth.data.HrZones
import com.boudhealth.data.Pace
import com.boudhealth.data.PeakHour
import com.boudhealth.data.PeriodUnit
import com.boudhealth.data.PeriodWorkouts
import com.boudhealth.data.PeriodZones
import com.boudhealth.data.R3Math
import com.boudhealth.data.ReadingBasis
import com.boudhealth.data.ReadingChange
import com.boudhealth.data.RingMode
import com.boudhealth.data.RingSet
import com.boudhealth.data.SectionBundle
import com.boudhealth.data.SectionPeriod
import com.boudhealth.data.SectionScope
import com.boudhealth.data.SleepNight
import com.boudhealth.data.SleepStage
import com.boudhealth.data.TileIcon
import com.boudhealth.data.Tone
import com.boudhealth.data.TotalChange
import com.boudhealth.data.TrendDir
import com.boudhealth.data.V2Engine
import com.boudhealth.data.V2Reads
import com.boudhealth.data.WeekColumnCell
import com.boudhealth.data.WeekFlag
import com.boudhealth.data.WeekPoints
import com.boudhealth.data.Workout
import com.boudhealth.data.YearRingCell
import com.boudhealth.data.dayRings
import com.boudhealth.data.ghost
import com.boudhealth.data.goalDays
import com.boudhealth.data.startOf
import com.boudhealth.data.history
import com.boudhealth.data.hours
import com.boudhealth.data.lifetime
import com.boudhealth.data.monthRings
import com.boudhealth.data.sessionDetails
import com.boudhealth.data.totalChange
import com.boudhealth.data.weekColumn
import com.boudhealth.data.window
import com.boudhealth.data.yearRings
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit

// =====================================================================================================================
// R3 Activity tab: every number the hero, the Trends strip and the cards draw (IMPL_PLAN §3A), computed ONCE inside
// SectionSpec.loadExtras (one IO hop) from the bundle + the R3 data layer. PURE (no Android / Compose; JVM-tested in
// tools/unit/tests/ActivityR3Test.kt): numbers only, every text is formatted in the UI from R3Texts. Port of
// out/activity/src/act.py (trend_tiles, cards, hero) and actlib.py. No averages anywhere (workout Avg HR excepted).
// =====================================================================================================================

/** Extras key of the [ActR3] model. */
internal const val X_R3 = "activityR3"

/** Status of one ring / goal as an icon + tone (pace chips, "/ week" column, tiles). */
data class Status(val icon: TileIcon, val tone: Tone)

/** One shown workout row: the session, its kind label and its average heart rate (the one allowed average). */
data class SessionRow(val workout: Workout, val day: LocalDate, val start: java.time.LocalTime, val groupLabel: String, val avgHr: Int?)

/** One "All values" row: the latest reading on / before the day ([carried] = from an earlier day). */
data class DayReading(val metricId: String, val day: LocalDate, val value: Double, val carried: Boolean)

/** Hero data per frame unit. */
data class HeroData(
    /** Calendar (1D / 1W / 1M frames): Mondays, month mode, rings, the "/ week" column and per-day values (a11y). */
    val weeks: List<LocalDate> = emptyList(),
    val month: YearMonth? = null,
    val dayRings: Map<LocalDate, FloatArray> = emptyMap(),
    /** Per day (workout min, steps, heart points) for the spoken calendar cell. */
    val dayValues: Map<LocalDate, DoubleArray> = emptyMap(),
    /** Daily goals (workout min, steps, heart points). */
    val dayGoals: DoubleArray = DoubleArray(3),
    val weekColumn: List<WeekColumnCell> = emptyList(),
    /** 1Y frame. */
    val year: Int? = null,
    val monthRings: Map<YearMonth, FloatArray> = emptyMap(),
    /** All frame: one ring per year + lifetime (workout hours, steps, heart points) = sum of the years. */
    val years: List<YearRingCell> = emptyList(),
    val lifetime: Triple<Double, Double, Double>? = null,
)

/** A total card (workout minutes, steps, calories): value, goal, best day, ghost, "goal met on n of m days". */
data class TotalCard(
    val metricId: String,
    val total: Double,
    /** Period goal (ring goal); null on All / without a goal. */
    val goal: Double?,
    val change: TotalChange?,
    val ghost: Ghost?,
    val bestDay: Best?,
    /** 1D: the hour shown as "Peak hour" (steps / kcal: busiest hour; workout minutes: start of the longest session). */
    val peakHour: Int?,
    /** "goal met on n of m days" (W/M/Y). */
    val goalDays: Pair<Int, Int>?,
)

/** Heart points card: zones (segments converted to POINTS so bar totals = heart points), ghost, the week rail. */
data class HeartCard(
    val points: Double,
    val goal: Double?,
    val change: TotalChange?,
    val zones: PeriodZones?,
    /** Zone minutes: fat burn, cardio, peak. */
    val zoneMinutes: IntArray,
    /** Per bucket (fat ×1, cardio ×2, peak ×2) points; same order as zones.perBucket. */
    val pointSegments: List<DoubleArray?>,
    val ghost: Ghost?,
    val goalDays: Pair<Int, Int>?,
    /** The week of the period end: points, target, status (= the "/ week" column and the 1W tile). */
    val week: WeekColumnCell?,
)

/** Sleep card. */
data class SleepCard(
    val nights: List<SleepNight>,
    /** 1D: the night ending on the day and its stages (hypnogram). */
    val night: SleepNight?,
    val stages: List<SleepStage>,
    /** W/M: previous period asleep minutes per night, aligned by day index. */
    val ghost: List<Double?>?,
    val bedtimes: Bedtimes?,
    /** Shortest / longest asleep minutes of the period. */
    val shortest: Double?, val longest: Double?,
)

/** Streak card / workout days. */
data class StreakCard(
    val current: Int, val best: Int,
    /** Workout days in the period (to today) and the weekly goal (1W only). */
    val days: Int, val goal: Int?,
    /** 1D: the day is a workout day. */
    val dayIsWorkout: Boolean,
    /** Dot row / grid: (day, workout day); 1D = the last 7 days, 1W the week, 1M the month. */
    val dots: List<Pair<LocalDate, Boolean>>,
    /** 1Y / All: workout days per month (future months null) and 1Y the previous year per month. */
    val perMonth: List<Pair<YearMonth, Double?>>,
    val perMonthGhost: List<Double?>?,
)

/** VO₂ max card and tiles. */
data class Vo2Card(
    /** Latest reading on / before the period end vs 3 months before it (the pill and the rail "Since"). */
    val change: ReadingChange?,
    /** Chart values (1D/1W/1M: 91 days; 1Y: per week; All: per month) and the first day of each bucket. */
    val values: List<Double?>,
    val bucketDays: List<LocalDate>,
    /** 1D/1W/1M: indexes of the period inside the 91-day window (shaded). */
    val shade: IntRange?,
    val lowest: Extreme?, val highest: Extreme?,
    /** Rail "Since {d Mon}" and its change (same reference as the pill on D/W/M; first reading in the range on Y/All). */
    val since: LocalDate?, val sinceChange: Double?,
    /** Trends: 1Y FIRST_IN_PERIOD / All FIRST_EVER, and the spark (monthly last readings). */
    val tile: ReadingChange?,
    val spark: List<Double>,
    val birthYear: Int?,
)

/** All-time records (Trends on All). */
data class AllTimeFacts(
    val sessions: Int, val firstDay: LocalDate,
    val sessionsPerMonth: List<Double?>,
    val bestYear: Best?, val yearHours: List<Double>, val bestYearIndex: Int?,
    val bestMonth: Best?, val monthSteps: List<Double?>, val bestMonthIndex: Int?,
    val bestWeek: Best?, val weekPoints: List<Double?>, val bestWeekIndex: Int?,
)

/** The whole Activity tab for one scope. */
data class ActR3(
    /** Effective period (the selected day or the period): rings, Trends and cards. */
    val p: SectionPeriod,
    /** The hero frame (the scope's period, even with a selected day). */
    val frame: SectionPeriod,
    val selected: LocalDate?,
    val today: LocalDate,
    val now: LocalDateTime,
    val dataStart: LocalDate,
    /** p contains today. */
    val running: Boolean,
    /** Elapsed share of p (running only) for the goal ticks. */
    val share: Double?,
    val rings: RingSet?,
    val goals: GoalsFact?,
    /** Pace chips (workout, steps, heart points). */
    val chips: List<Status>,
    val window: CompareWindow?,
    val hero: HeroData,
    // ---- Trends extras
    val peak: PeakHour?,
    val hourlySteps: List<Double?>,
    /** 1D streak tile: 14 days of workout-day flags ending on the day; at risk = today, no workout yet. */
    val streakFlags: List<Boolean>,
    val atRisk: Boolean,
    /** Workout days (W: pace vs goal; M/Y: vs the previous window), dots for W/M, per month for Y. */
    val workoutDaysPrev: Int?,
    val workoutDaysStatus: Status?,
    val weeksMet: List<WeekFlag>,
    val allTime: AllTimeFacts?,
    // ---- cards
    val workoutMin: TotalCard,
    val steps: TotalCard,
    val calories: TotalCard,
    val heart: HeartCard,
    val workouts: PeriodWorkouts?,
    val sessionRows: List<SessionRow>,
    /** 1D without workouts: the previous day's sessions (shown faint). */
    val prevSessionRows: List<SessionRow>,
    val longest: Workout?,
    val sleep: SleepCard,
    val streak: StreakCard,
    val vo2: Vo2Card,
    val allValues: List<DayReading>,
)

object ActivityR3Data {
    /** The All values card rows (1D), in order. */
    val ALL_VALUE_IDS = listOf("weight", "body_fat", "rhr", "hrv", "breathing", "spo2")

    /** Pace chip / Goals state → icon + tone (✓ GOOD · ▲ GOOD · ● NEUTRAL · ▼ BAD). */
    fun statusOf(s: GoalState): Status = when (s) {
        GoalState.DONE -> Status(TileIcon.DONE, Tone.GOOD)
        GoalState.AHEAD -> Status(TileIcon.UP, Tone.GOOD)
        GoalState.ON_PACE -> Status(TileIcon.SAME, Tone.NEUTRAL)
        GoalState.BEHIND, GoalState.MISSED -> Status(TileIcon.DOWN, Tone.BAD)
    }

    /**
     * Status of an amount vs a goal in [p]: past period (or a past day) = ✓ met / ▼ missed; running = pace
     * (✓ ▲ ● ▼, [Pace.status] at the elapsed share).
     */
    fun paceStatus(actual: Double, goal: Double, p: SectionPeriod, now: LocalDateTime): Status {
        val today = now.toLocalDate()
        if (goal <= 0.0) return Status(TileIcon.SAME, Tone.NEUTRAL)
        if (!p.isCurrent(today)) return if (actual >= goal) Status(TileIcon.DONE, Tone.GOOD) else Status(TileIcon.DOWN, Tone.BAD)
        return when (Pace.status(actual, goal, Pace.fraction(p, now))) {
            com.boudhealth.data.CardioPace.DONE -> Status(TileIcon.DONE, Tone.GOOD)
            com.boudhealth.data.CardioPace.AHEAD -> Status(TileIcon.UP, Tone.GOOD)
            com.boudhealth.data.CardioPace.BEHIND, com.boudhealth.data.CardioPace.NO_DATA -> Status(TileIcon.DOWN, Tone.BAD)
            else -> Status(TileIcon.SAME, Tone.NEUTRAL)
        }
    }

    /** Heart points per zone bucket: fat burn ×1, cardio ×2, peak ×2 (bar totals = heart points). */
    fun zonePoints(minutes: DoubleArray?): DoubleArray? = minutes?.let { m ->
        doubleArrayOf(m.getOrElse(0) { 0.0 }, 2 * m.getOrElse(1) { 0.0 }, 2 * m.getOrElse(2) { 0.0 })
    }

    /**
     * Heart points per Mon–Sun week from the daily points, rounded like the "/ week" column (one rounding for the
     * column, the 1W tile, the card rail and "{n} of {m} weeks").
     */
    fun weekPoints(daily: Map<LocalDate, Double>, from: LocalDate, to: LocalDate, today: LocalDate, target: Int): List<WeekPoints> {
        val out = ArrayList<WeekPoints>()
        var mon = SectionPeriod.mondayOf(from)
        val curMon = SectionPeriod.mondayOf(today)
        while (!mon.isAfter(to)) {
            val sun = mon.plusDays(6)
            val xs = daily.filterKeys { !it.isBefore(mon) && !it.isAfter(minOf(sun, today)) }
            val pts = Math.round(xs.values.sum()).toInt()
            out += WeekPoints(mon, pts, target, pts >= target, mon == curMon, xs.isNotEmpty())
            mon = mon.plusDays(7)
        }
        return out
    }

    /** Workout-days icon for M/Y: ▲ ▼ ● by the absolute count vs the previous window (higher = good). */
    fun countStatus(cur: Int, prev: Int): Status = when {
        cur > prev -> Status(TileIcon.UP, Tone.GOOD)
        cur < prev -> Status(TileIcon.DOWN, Tone.BAD)
        else -> Status(TileIcon.SAME, Tone.NEUTRAL)
    }

    /** Sums of [daily] per bucket key (inclusive range), in key order. */
    private fun sums(daily: Map<LocalDate, Double>, keys: List<LocalDate>, key: (LocalDate) -> LocalDate): List<Double> {
        val m = HashMap<LocalDate, Double>()
        for ((d, v) in daily) m[key(d)] = (m[key(d)] ?: 0.0) + v
        return keys.map { m[it] ?: 0.0 }
    }

    private fun months(from: LocalDate, to: LocalDate): List<YearMonth> {
        val out = ArrayList<YearMonth>(); var m = YearMonth.from(from); val e = YearMonth.from(to)
        while (!m.isAfter(e)) { out += m; m = m.plusMonths(1) }
        return out
    }

    /**
     * Builds the model. [bundle] is the section bundle of [scope] (rings / cards of the effective period); [engine] the
     * same engine; [now] the clock (zone of the engine).
     */
    fun build(engine: V2Engine, scope: SectionScope, bundle: SectionBundle, now: LocalDateTime): ActR3 {
        val today = now.toLocalDate()
        val p = scope.effective
        val frame = scope.period
        val ds = bundle.dataStart
        val goals = engine.goals
        val end = minOf(p.last, today)
        val running = p.isCurrent(today)
        val share = if (running) Pace.fraction(p, now) else null
        val isAll = p is SectionPeriod.All

        // ---- rings, goals, chips
        val rings = bundle.rings
        val gf = if (rings != null && rings.mode != RingMode.LIFETIME && rings.rings.size == 3) R3Math.goalsFact(rings, running, share) else null
        val chips = gf?.perRing?.map(::statusOf).orEmpty()
        val window = engine.window(p, now)

        // ---- hero (frame)
        val hero = hero(engine, frame, scope.selectedDay, today, now, ds)

        // ---- totals
        fun card(id: String): CardData? = bundle.cards[id]
        fun total(id: String): Double = card(id)?.value?.value ?: 0.0
        val wmCh = engine.totalChange("exercise_min", p, now)
        val stCh = engine.totalChange("steps", p, now)
        val hpCh = engine.totalChange("zone_min", p, now)
        val kcCh = engine.totalChange("calories", p, now)
        val goalDays: Map<String, Pair<Int, Int>> = if (p is SectionPeriod.Day || isAll || end.isBefore(p.start)) emptyMap()
            else engine.goalDaysPer(p, today)
        val sessions = card("exercise_min")?.workouts ?: card("streak")?.workouts ?: bundle.workouts
        val longest = sessions?.sessions?.maxWithOrNull(compareBy<Workout> { it.minutes }.thenBy { it.start })
        val ringGoal = { id: String -> rings?.ring(id)?.goal?.takeIf { !isAll } }
        val ringValue = { id: String -> rings?.ring(id)?.value }
        val daily = { id: String -> if (end.isBefore(p.start)) emptyMap() else engine.daily(id, p.start, end) }
        val peakSteps = if (p is SectionPeriod.Day) R3Math.peakHour(engine.hours("steps", p.start, now)) else null
        val stepHours = if (p is SectionPeriod.Day) engine.hours("steps", p.start, now) else emptyList()
        val peakKcal = if (p is SectionPeriod.Day) R3Math.peakHour(engine.hours("calories", p.start, now)) else null
        val ghostOf = { id: String -> if (isAll) null else engine.ghost(id, p, now) }
        val wm = TotalCard("exercise_min", ringValue("exercise_min") ?: total("exercise_min"), ringGoal("exercise_min"), wmCh, ghostOf("exercise_min"),
            R3Math.best(daily("exercise_min"), PeriodUnit.DAY, p.start, end),
            if (p is SectionPeriod.Day) longest?.let { engine.startOf(it).hour } else null, goalDays["exercise_min"])
        val st = TotalCard("steps", ringValue("steps") ?: total("steps"), ringGoal("steps"), stCh, ghostOf("steps"),
            R3Math.best(daily("steps"), PeriodUnit.DAY, p.start, end), peakSteps?.hour, goalDays["steps"])
        val kc = TotalCard("calories", total("calories"), null, kcCh, ghostOf("calories"),
            R3Math.best(daily("calories"), PeriodUnit.DAY, p.start, end), peakKcal?.hour, null)

        // ---- heart points
        val z = card("zone_min")?.zones
        val hpGhost0 = ghostOf("zone_min")
        val hpGhost = hpGhost0?.let { g ->
            val segs = g.segments?.map { zonePoints(it) }
            Ghost(g.values.indices.map { i -> segs?.getOrNull(i)?.sum() ?: g.values[i] }, segs)
        }
        val weekEnd = end.coerceAtLeast(p.start).let { if (isAll) today else it }
        val wkMon = SectionPeriod.mondayOf(weekEnd)
        val wkPts = Math.round(engine.daily("zone_min", wkMon, minOf(wkMon.plusDays(6), weekEnd)).values.sum()).toInt()
        val wkStatus = paceStatus(wkPts.toDouble(), goals.zoneMinWeek.toDouble(), SectionPeriod.Week(wkMon.toEpochDay()), now)
        val heart = HeartCard(ringValue("zone_min") ?: total("zone_min"), ringGoal("zone_min"), hpCh, z,
            intArrayOf(z?.minutes?.getOrElse(1) { 0 } ?: 0, z?.minutes?.getOrElse(2) { 0 } ?: 0, z?.minutes?.getOrElse(3) { 0 } ?: 0),
            z?.perBucket?.map { zonePoints(it.segments) }.orEmpty(), hpGhost, goalDays["zone_min"],
            WeekColumnCell(wkMon, wkStatus.icon, wkStatus.tone, wkPts, goals.zoneMinWeek))

        // ---- workouts card rows (3 latest; 1D empty: the previous day's, faint)
        val rows = sessionRows(engine, sessions?.sessions.orEmpty().take(3), today)
        val prevRows = if (p is SectionPeriod.Day && sessions?.sessions.isNullOrEmpty()) {
            val y = p.start.minusDays(1)
            sessionRows(engine, engine.workouts(y, y).take(2), today)
        } else emptyList()

        // ---- sleep
        val nights = card("sleep")?.sleep ?: engine.sleep(p, today)
        val night = if (p is SectionPeriod.Day) nights.lastOrNull { it.date == p.start } else null
        val stages = if (night != null) runCatching { engine.reads.sleepStages(p.start) }.getOrDefault(emptyList()) else emptyList()
        val sleepGhost = if (p is SectionPeriod.Week || p is SectionPeriod.Month) p.previous()?.let { q ->
            if (q.start.isAfter(today)) null else {
                val byDay = engine.sleep(q, today).associateBy { it.date }
                (0 until p.totalDays()).map { i -> q.start.plusDays(i.toLong()).let { d -> if (q.contains(d)) byDay[d]?.info?.asleepMin?.toDouble() else null } }
            }
        } else null
        val mins = nights.map { it.info.asleepMin.toDouble() }
        val sleep = SleepCard(nights, night, stages, sleepGhost, R3Math.bedtimes(nights, engine.zone), mins.minOrNull(), mins.maxOrNull())

        // ---- streak / workout days
        val (cur, best) = engine.streakAsOf(if (isAll) today else end)
        val active: Set<LocalDate> = when (p) {
            is SectionPeriod.Day -> engine.activeDays(p.start.minusDays(13), p.start)
            else -> card("streak")?.workouts?.activeDays ?: if (end.isBefore(p.start)) emptySet() else engine.activeDays(p.start, end)
        }
        val nDays = if (p is SectionPeriod.Day) (if (p.start in active) 1 else 0) else active.count { !it.isBefore(p.start) && !it.isAfter(end) }
        val dGoal = if (p is SectionPeriod.Week) goals.workoutDaysWeek else null
        val dots: List<Pair<LocalDate, Boolean>> = when (p) {
            is SectionPeriod.Day -> (6 downTo 0).map { p.start.minusDays(it.toLong()) }.map { it to (it in active) }
            is SectionPeriod.Week, is SectionPeriod.Month -> (0 until p.totalDays()).map { p.start.plusDays(it.toLong()) }.map { it to (it in active) }
            else -> emptyList()
        }
        val perMonth: List<Pair<YearMonth, Double?>> = when (p) {
            is SectionPeriod.Year -> (1..12).map { YearMonth.of(p.start.year, it) }.map { ym ->
                ym to (if (ym.atDay(1).isAfter(today)) null else active.count { YearMonth.from(it) == ym }.toDouble())
            }
            is SectionPeriod.All -> months(p.start, today).map { ym -> ym to active.count { YearMonth.from(it) == ym }.toDouble() }
            else -> emptyList()
        }
        val perMonthGhost = if (p is SectionPeriod.Year) {
            val q = p.previous()!!
            if (q.last.isBefore(ds)) null else {
                val prevActive = engine.activeDays(maxOf(q.start, ds), minOf(q.last, today))
                (1..12).map { m -> prevActive.count { it.monthValue == m }.toDouble() }
            }
        } else null
        val streak = StreakCard(cur, best, nDays, dGoal, p is SectionPeriod.Day && p.start in active, dots, perMonth, perMonthGhost)
        val streakFlags = if (p is SectionPeriod.Day) (13 downTo 0).map { p.start.minusDays(it.toLong()) in active } else emptyList()
        val atRisk = p is SectionPeriod.Day && p.start == today && today !in active

        // workout days vs the previous window (M/Y) or vs the weekly goal (W)
        var wdPrev: Int? = null
        val wdStatus: Status? = when (p) {
            is SectionPeriod.Week -> paceStatus(nDays.toDouble(), goals.workoutDaysWeek.toDouble(), p, now)
            is SectionPeriod.Month, is SectionPeriod.Year -> {
                val w = window ?: com.boudhealth.data.R3Math.window(p, now)
                if (w != null) {
                    val pe = if (w.cut != null) w.prevEndDay else w.prevPeriod.last
                    val pa = maxOf(w.prevFrom, ds)
                    wdPrev = if (pe.isBefore(pa)) 0 else engine.activeDays(pa, pe).size
                    countStatus(nDays, wdPrev!!)
                } else null
            }
            else -> null
        }

        // weeks met (1M / 1Y)
        val weeksMet = if (p is SectionPeriod.Month || p is SectionPeriod.Year) {
            val from = SectionPeriod.mondayOf(p.start).minusDays(7)
            val hp = engine.daily("zone_min", from, minOf(p.last, today))
            R3Math.weeksMet(weekPoints(hp, from, minOf(p.last, today), today, goals.zoneMinWeek), p.start, p.last, today)
        } else emptyList()

        // ---- VO2
        val vo2 = vo2(engine, p, end, today, ds)

        // ---- All-time records
        val allTime = if (isAll) allTime(engine, sessions, ds, today) else null

        // ---- All values (1D)
        val allValues = if (p is SectionPeriod.Day) ALL_VALUE_IDS.mapNotNull { id ->
            engine.lastOnOrBefore(id, p.start)?.let { (d, v) -> DayReading(id, d, v, d != p.start) }
        } else emptyList()

        return ActR3(
            p, frame, scope.selectedDay, today, now, ds, running, share, rings, gf, chips, window, hero,
            peakSteps, stepHours.map { it.value }, streakFlags, atRisk, wdPrev, wdStatus, weeksMet, allTime,
            wm, st, kc, heart, sessions, rows, prevRows, longest, sleep, streak, vo2, allValues,
        )
    }

    /** "Goal met on n of m days" per ring goal (the same source as V2Restore.goalDays). */
    private fun V2Engine.goalDaysPer(p: SectionPeriod, today: LocalDate): Map<String, Pair<Int, Int>> =
        goalDays(p, today).perGoal.associate { it.metricId to (it.met to it.days) }

    private fun sessionRows(engine: V2Engine, ws: List<Workout>, today: LocalDate): List<SessionRow> {
        if (ws.isEmpty()) return emptyList()
        val days = ws.map { V2Reads.dayOf(it.start, engine.zone) }.distinct()
        val details = days.flatMap { d -> runCatching { engine.sessionDetails(SectionPeriod.Day(d.toEpochDay()), today) }.getOrDefault(emptyList()) }
            .associateBy { it.workout.start }
        return ws.map { w ->
            val t = java.time.Instant.ofEpochMilli(w.start).atZone(engine.zone)
            val det = details[w.start]
            SessionRow(w, t.toLocalDate(), t.toLocalTime(), (det?.group ?: engine.group(w)).label, det?.avgHr ?: w.avgHr?.toInt())
        }
    }

    private fun hero(engine: V2Engine, frame: SectionPeriod, selected: LocalDate?, today: LocalDate, now: LocalDateTime, ds: LocalDate): HeroData {
        val g = engine.goals
        val dayGoals = doubleArrayOf(g.workoutMinDay.toDouble(), g.steps.toDouble(), HrZones.dailyGoal(g.zoneMinWeek).toDouble())
        fun calendar(weeks: List<LocalDate>, month: YearMonth?): HeroData {
            val a = weeks.first(); val b = weeks.last().plusDays(6)
            val rings = engine.dayRings(a, b, today)
            val to = minOf(b, today)
            val vals = if (to.isBefore(a)) emptyMap() else {
                val wm = engine.daily("exercise_min", a, to); val st = engine.daily("steps", a, to); val hp = engine.daily("zone_min", a, to)
                rings.keys.associateWith { d -> doubleArrayOf(wm[d] ?: 0.0, st[d] ?: 0.0, hp[d] ?: 0.0) }
            }
            return HeroData(weeks = weeks, month = month, dayRings = rings, dayValues = vals, dayGoals = dayGoals,
                weekColumn = engine.weekColumn(weeks, now))
        }
        return when (frame) {
            is SectionPeriod.Day -> YearMonth.from(frame.start).let { calendar(R3Math.monthWeeks(it), it) }
            is SectionPeriod.Week -> calendar(R3Math.sixWeeks(frame.start), null)
            is SectionPeriod.Month -> YearMonth.from(frame.start).let { calendar(R3Math.monthWeeks(it), it) }
            is SectionPeriod.Year -> HeroData(year = frame.start.year, monthRings = engine.monthRings(frame.start.year, today))
            is SectionPeriod.All -> HeroData(years = engine.yearRings(today), lifetime = engine.lifetime(today))
        }
    }

    private fun vo2(engine: V2Engine, p: SectionPeriod, end: LocalDate, today: LocalDate, ds: LocalDate): Vo2Card {
        val s = engine.history("vo2", today)
        val dec = 1
        val flat = 0.1
        val change = if (s.isEmpty()) null else R3Math.readingChange("vo2", s, p, if (p is SectionPeriod.Day) p.start else null, today,
            ReadingBasis.MONTHS_AGO, dec, flat, true)
        var shade: IntRange? = null
        val values: List<Double?>; val bucketDays: List<LocalDate>
        val ra: LocalDate; val rb: LocalDate
        when (p) {
            is SectionPeriod.Day, is SectionPeriod.Week, is SectionPeriod.Month -> {
                val a = end.minusDays(90)
                bucketDays = (0..90).map { a.plusDays(it.toLong()) }
                values = bucketDays.map { s[it] }
                val i0 = ChronoUnit.DAYS.between(a, p.start).toInt().coerceAtLeast(0)
                shade = i0..90
                ra = a; rb = end
            }
            is SectionPeriod.Year -> {
                // per Mon–Sun week touching the year: the last reading of the week
                val first = SectionPeriod.mondayOf(p.start)
                bucketDays = generateSequence(first) { it.plusDays(7) }.takeWhile { !it.isAfter(p.last) }.toList()
                values = bucketDays.map { mon -> (6 downTo 0).map { mon.plusDays(it.toLong()) }.firstNotNullOfOrNull { d -> if (p.contains(d) && !d.isAfter(today)) s[d] else null } }
                ra = p.start; rb = end
            }
            is SectionPeriod.All -> {
                val ms = months(p.start, today)
                bucketDays = ms.map { it.atDay(1) }
                val last = R3Math.perMonth(s, p.start, today).associateBy { it.month }
                values = ms.map { last[it]?.last?.value }
                ra = p.start; rb = today
            }
        }
        val mm = R3Math.minMax(s, ra, rb)
        val since: LocalDate?; val sinceCh: Double?
        if (p is SectionPeriod.Year || p is SectionPeriod.All) {
            val pts = s.entries.filter { !it.key.isBefore(ra) && !it.key.isAfter(rb) }.sortedBy { it.key }
            if (pts.size >= 2) { since = pts.first().key; sinceCh = R3Math.round(R3Math.round(pts.last().value, dec) - R3Math.round(pts.first().value, dec), dec) }
            else { since = null; sinceCh = null }
        } else {
            since = change?.reference?.day; sinceCh = change?.diff
        }
        val tile = when (p) {
            is SectionPeriod.Year -> if (s.isEmpty()) null else R3Math.readingChange("vo2", s, p, null, today, ReadingBasis.FIRST_IN_PERIOD, dec, flat, true)
            is SectionPeriod.All -> if (s.isEmpty()) null else R3Math.readingChange("vo2", s, p, null, today, ReadingBasis.FIRST_EVER, dec, flat, true)
            else -> null
        }
        val spark = when (p) {
            is SectionPeriod.Year, is SectionPeriod.All -> R3Math.perMonth(s, p.start, minOf(p.last, today)).map { it.last.value }
            else -> emptyList()
        }
        return Vo2Card(change, values, bucketDays, shade, mm?.lo, mm?.hi, since, sinceCh, tile, spark, engine.goals.birthYear)
    }

    private fun allTime(engine: V2Engine, pw: PeriodWorkouts?, ds: LocalDate, today: LocalDate): AllTimeFacts {
        val ms = months(ds, today)
        val byMonth = pw?.sessions.orEmpty().groupingBy { YearMonth.from(V2Reads.dayOf(it.start, engine.zone)) }.eachCount()
        val wm = engine.history("exercise_min", today)
        val st = engine.history("steps", today)
        val hp = engine.history("zone_min", today)
        val years = (ds.year..today.year).map { LocalDate.of(it, 1, 1) }
        val yearMin = sums(wm, years) { it.withDayOfYear(1) }
        val bestYear = R3Math.best(wm, PeriodUnit.YEAR, ds, today)
        val monthKeys = ms.map { it.atDay(1) }
        val monthSteps = sums(st, monthKeys) { it.withDayOfMonth(1) }
        val bestMonth = R3Math.best(st, PeriodUnit.MONTH, ds, today)
        val weekKeys = generateSequence(SectionPeriod.mondayOf(ds)) { it.plusDays(7) }.takeWhile { !it.isAfter(today) }.toList()
        val weekPts = sums(hp, weekKeys) { SectionPeriod.mondayOf(it) }
        val bestWeek = R3Math.best(hp, PeriodUnit.WEEK, ds, today)
        return AllTimeFacts(
            pw?.sessionCount ?: 0, ds, ms.map { (byMonth[it] ?: 0).toDouble() },
            bestYear, yearMin.map { it / 60.0 }, bestYear?.let { b -> years.indexOf(b.start).takeIf { it >= 0 } },
            bestMonth, monthSteps, bestMonth?.let { b -> monthKeys.indexOf(b.start).takeIf { it >= 0 } },
            bestWeek, weekPts, bestWeek?.let { b -> weekKeys.indexOf(b.start).takeIf { it >= 0 } },
        )
    }

    /** Debug consistency checks (IMPL_PLAN §5.4) on a built model; returns the failed check names. */
    fun consistency(m: ActR3): List<String> {
        val bad = ArrayList<String>()
        val rs = m.rings
        if (rs != null && rs.mode != RingMode.LIFETIME) {
            if (rs.ring("exercise_min")?.value != m.workoutMin.total) bad += "ring = workout card"
            if (rs.ring("steps")?.value != m.steps.total) bad += "ring = steps card"
            if (rs.ring("zone_min")?.value != m.heart.points) bad += "ring = heart card"
        }
        if (m.p is SectionPeriod.Week && m.frame is SectionPeriod.Week) {
            val col = m.hero.weekColumn.firstOrNull { it.monday == m.p.start }
            if (col != null && m.heart.week != null && col.points != m.heart.week.points) bad += "week column = HP rail"
        }
        if (m.p is SectionPeriod.Month && m.streak.days != m.streak.dots.count { it.second && !it.first.isAfter(m.today) }) bad += "workout days = streak dots"
        m.hero.lifetime?.let { lt ->
            if (Math.abs(lt.first - m.hero.years.sumOf { it.hours }) > 1e-6) bad += "lifetime = years"
        }
        val z = m.heart.zones
        if (z != null) z.perBucket.forEachIndexed { i, b ->
            val s = m.heart.pointSegments.getOrNull(i)?.sum() ?: return@forEachIndexed
            val v = b.value ?: return@forEachIndexed
            if (Math.abs(s - v) > 1.0 + 1e-9 && m.p !is SectionPeriod.Day) { bad += "HP bar totals = points"; return@forEachIndexed }
        }
        return bad
    }
}

/** Change form of a period (1D absolute, else %). */
internal fun formOf(p: SectionPeriod): ChangeForm = if (p is SectionPeriod.Day) ChangeForm.ABSOLUTE else ChangeForm.PERCENT

/** TrendDir → tile icon. */
internal fun iconOfDir(d: TrendDir?): TileIcon = R3Math.iconOf(d)
