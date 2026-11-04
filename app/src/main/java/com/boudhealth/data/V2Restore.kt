package com.boudhealth.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/*
 * V2 restore round (V2_REMOVALS_AUDIT.md §4.1): data for the insights the restructure dropped. PURE (on V2Engine),
 * JVM-tested in scratchpad/datatest/V2Test.kt ("restore"). Every number comes from V2Engine.daily — the same per-day
 * values as cards, rings and series, so a list, a stat and a card never disagree.
 */

/** One week of heart points vs the weekly target (H3). [partial] = the running week. */
data class WeekPoints(val monday: LocalDate, val points: Int, val target: Int, val met: Boolean, val partial: Boolean, val hasData: Boolean)

/** One day of a per-day list (D2, H4, K1): value, change vs the day before, the day's goal. */
data class DayRow(val day: LocalDate, val value: Double?, val changeVsPrevious: Double?, val goal: Double?, val met: Boolean?)

/** Stat row (D1): total, best day, peak hour (1D), change vs the previous day (1D). */
data class PeriodStats(
    val metricId: String,
    val total: Double?,
    val bestDay: LocalDate?, val bestValue: Double?,
    /** 1D only: the hour with the highest value. */
    val peakHour: LocalDateTime?, val peakValue: Double?,
    /** 1D only: the day's value minus the previous day's. */
    val changeVsPreviousDay: Double?,
    val goal: PeriodGoal?,
)

/** Heart points detail (H1): zone lower limits in bpm and the max / resting HR they come from. */
data class ZoneLimits(
    val day: LocalDate,
    val maxHr: Int, val maxHrSource: MaxHrSource,
    val restHr: Int, val restSource: RestSource,
    /** Lowest bpm of fat burn, cardio, peak. */
    val fatBurnFrom: Int, val cardioFrom: Int, val peakFrom: Int,
)

/** One session with its zone split (H2, W2): minutes below / fat burn / cardio / peak, points, average HR. */
data class SessionDetail(
    val workout: Workout,
    val group: WorkoutGroup,
    val zoneMinutes: IntArray?,
    val points: Int?,
    /** Stored average HR, else computed from the minute heart rate (≥ 35 bpm); null without heart rate. */
    val avgHr: Int?,
)

/** "Goal met on n of m days" (A2) for one goal; [days] = counted days (past days + today once met). */
data class GoalDays(val metricId: String, val met: Int, val days: Int, val metDays: List<LocalDate>)

/** A2 for a period: per ring, all three closed, and per month on 1Y/All. */
data class GoalDaysSummary(val period: SectionPeriod, val perGoal: List<GoalDays>, val allClosed: GoalDays, val perMonth: List<Pair<LocalDate, GoalDays>>)

/** W1: workout minutes per kind for a period, most first, and per graph bucket. */
data class MinutesByType(val period: SectionPeriod, val total: List<Pair<WorkoutGroup, Int>>, val perBucket: List<Pair<SeriesBucket, Map<WorkoutGroup, Int>>>)

/** Lean mass / HRV tile lines (1.4): the latest reading vs the one before. */
data class LatestChange(val metricId: String, val latest: Double, val latestDay: LocalDate, val previous: Double?, val previousDay: LocalDate?, val change: Double?, val dir: TrendDir?)

/** Weekly goals tile (1.4): one line per goal with its pace this week. */
data class WeeklyGoalPace(val metricId: String, val actual: Double, val goal: Double, val expectedByNow: Double, val fraction: Double, val status: CardioPace)

// ============================================================================ engine extensions

private val TOTAL_IDS = setOf("steps", "exercise_min", "zone_min", "calories")

/** H3: the last [weeks] weeks (oldest first, ending with the week of [today]) of heart points vs the weekly target. */
fun V2Engine.weeklyHeartPoints(weeks: Int, today: LocalDate): List<WeekPoints> {
    val monday = SectionPeriod.mondayOf(today)
    val from = monday.minusWeeks((weeks - 1).toLong())
    val pts = daily("zone_min", from, today)
    val target = goals.zoneMinWeek
    return (0 until weeks).map { k ->
        val a = from.plusWeeks(k.toLong())
        val xs = pts.filterKeys { !it.isBefore(a) && it.isBefore(a.plusWeeks(1)) }
        val p = xs.values.sum().toInt()
        WeekPoints(a, p, target, p >= target, a == monday, xs.isNotEmpty())
    }
}

/** D2 / H4 / K1: one row per day, newest first, for the last [days] days up to [to] (totals: missing day = 0). */
fun V2Engine.dayList(metricId: String, to: LocalDate, days: Int = 30): List<DayRow> {
    val from = to.minusDays(days.toLong())          // one extra day for the first change
    val d = daily(metricId, from, to)
    val total = metricId in TOTAL_IDS
    val goal = V2Math.dailyGoal(metricId, goals)
    fun v(x: LocalDate) = d[x] ?: if (total && !x.isBefore(dataStart())) 0.0 else null
    return (0 until days).map { i ->
        val x = to.minusDays(i.toLong())
        val cur = v(x); val prev = v(x.minusDays(1))
        DayRow(x, cur, if (cur != null && prev != null) cur - prev else null, goal, if (goal != null && cur != null) cur >= goal else null)
    }
}

/** D1: Total · Best day · Peak hour (1D) · change vs the previous day (1D) · Goal. */
fun V2Engine.stats(metricId: String, p: SectionPeriod, now: LocalDateTime): PeriodStats {
    val today = now.toLocalDate()
    val end = minOf(p.last, today)
    val d = if (end.isBefore(p.start)) emptyMap() else daily(metricId, p.start, end)
    val best = d.maxByOrNull { it.value }
    var peakAt: LocalDateTime? = null; var peakV: Double? = null; var change: Double? = null
    if (p is SectionPeriod.Day) {
        series(metricId, p, now)?.buckets?.filter { it.value != null }?.maxByOrNull { it.value!! }?.let { peakAt = it.start; peakV = it.value }
        val prev = daily(metricId, p.start.minusDays(1), p.start.minusDays(1)).values.firstOrNull() ?: if (metricId in TOTAL_IDS) 0.0 else null
        val cur = d[p.start] ?: if (metricId in TOTAL_IDS) 0.0 else null
        if (cur != null && prev != null) change = cur - prev
    }
    return PeriodStats(metricId, value(metricId, p, today)?.value, best?.key, best?.value, peakAt, peakV, change, V2Math.goalFor(metricId, goals, p))
}

/** H1: zone limits of [day] with the max HR the stored zones use and that day's resting HR (and its source). */
fun V2Engine.zoneLimits(day: LocalDate): ZoneLimits {
    val rest = HrZones.restFor(day, reads.daily(M.RHR, day.minusDays(7), day).associate { it.day to it.value })
    val b = HrZones.bounds(maxHr, rest.first)
    val src = if (goals.maxHrFit != null) MaxHrSource.MANUAL else MaxHrSource.DEFAULT
    return ZoneLimits(day, maxHr, src, rest.first, rest.second, b[0], b[1], b[2])
}

/** H2 / W2: the period's sessions (newest first) with zone split, points and average HR. */
fun V2Engine.sessionDetails(p: SectionPeriod, today: LocalDate): List<SessionDetail> {
    val end = minOf(p.last, today)
    if (end.isBefore(p.start)) return emptyList()
    val ws = workouts(p.start, end)
    if (ws.isEmpty()) return emptyList()
    val hd = reads.hrDays(p.start.minusDays(1), end.plusDays(1))
    val rhr = reads.daily(M.RHR, p.start.minusDays(8), end.plusDays(1)).associate { it.day to it.value }
    return ws.map { w ->
        val z = IntArray(4); var sum = 0L; var n = 0; var any = false
        for (h in hd) {
            val s = h.start(zone)
            val m0 = ((w.start - s) / 60_000L).coerceIn(0, HrDay.MINUTES.toLong()).toInt()
            val m1 = ((w.end - s) / 60_000L).coerceIn(0, HrDay.MINUTES.toLong()).toInt()
            if (m1 <= m0) continue
            val rest = HrZones.restFor(h.day, rhr).first
            val part = HrZones.minutes(h.bpm, maxHr, rest, m0, m1)
            for (i in 0..3) z[i] += part[i]
            for (m in m0 until m1) { val v = h.at(m); if (v >= Plausible.HR_MIN_BPM) { sum += v; n++; any = true } }
        }
        val avg = w.avgHr?.toInt()?.takeIf { it >= Plausible.HR_MIN_BPM } ?: if (n > 0) Math.round(sum.toDouble() / n).toInt() else null
        SessionDetail(w, group(w), if (any) z else null, if (any) HrZones.intensity(z) else null, avg)
    }
}

/** A2: goal met on n of m days per goal (steps, workout minutes, heart points), all three closed, per month on 1Y/All. */
fun V2Engine.goalDays(p: SectionPeriod, today: LocalDate): GoalDaysSummary {
    val end = minOf(p.last, today)
    val ids = PeriodRing.ORDER
    val days = if (end.isBefore(p.start)) emptyList() else SyncLogic.datesBetween(p.start, end)
    val vals = ids.associateWith { if (days.isEmpty()) emptyMap() else daily(it, p.start, end) }
    val goal = ids.associateWith { V2Math.dailyGoal(it, goals) ?: Double.MAX_VALUE }
    fun met(id: String, d: LocalDate) = (vals.getValue(id)[d] ?: 0.0) >= goal.getValue(id)
    fun summary(id: String?, ds: List<LocalDate>): GoalDays {
        val ok: (LocalDate) -> Boolean = if (id == null) { d -> ids.all { met(it, d) } } else { d -> met(id, d) }
        // today counts once met (a running day is not a missed day)
        val counted = ds.filter { it != today || ok(it) }
        val m = counted.filter(ok)
        return GoalDays(id ?: "all", m.size, counted.size, m)
    }
    val perMonth = if (p is SectionPeriod.Year || p is SectionPeriod.All)
        days.groupBy { it.withDayOfMonth(1) }.toSortedMap().map { (k, ds) -> k to summary(null, ds) } else emptyList()
    return GoalDaysSummary(p, ids.map { summary(it, days) }, summary(null, days), perMonth)
}

/** W1: workout minutes per kind (session length, de-duplicated sessions), total and per graph bucket. */
fun V2Engine.minutesByType(p: SectionPeriod, today: LocalDate): MinutesByType {
    val end = minOf(p.last, today)
    val ws = if (end.isBefore(p.start)) emptyList() else workouts(p.start, end)
    val total = ws.groupBy(group).mapValues { e -> e.value.sumOf { it.minutes } }.entries
        .sortedWith(compareByDescending<Map.Entry<WorkoutGroup, Int>> { it.value }.thenBy { it.key.ordinal }).map { it.key to it.value }
    val grain = if (p is SectionPeriod.Day) Grain.DAY else V2Math.grainFor(p)
    val buckets = V2Math.bucketRanges(p, grain).map { (a, b) ->
        val inB = ws.filter { val d = V2Reads.dayOf(it.start, zone); !d.isBefore(a) && d.isBefore(b) }
        val m = inB.groupBy(group).mapValues { e -> e.value.sumOf { it.minutes } }
        SeriesBucket(a.atStartOfDay(), b.atStartOfDay(), m.values.sum().toDouble().takeIf { !a.isAfter(today) }, future = a.isAfter(today)) to m
    }
    return MinutesByType(p, total, buckets)
}

/** 1.4: latest reading of a measurement / daily value (lean_mass, hrv, …) vs the reading before it, up to [asOf]. */
fun V2Engine.latestChange(metricId: String, asOf: LocalDate): LatestChange? {
    val d = daily(metricId, asOf.minusYears(3), asOf).entries.sortedBy { it.key }
    val last = d.lastOrNull() ?: return null
    val prev = d.getOrNull(d.size - 2)
    val ch = prev?.let { last.value - it.value }
    val dir = ch?.let { if (Math.abs(it) < 1e-9) TrendDir.FLAT else if (it > 0) TrendDir.UP else TrendDir.DOWN }
    return LatestChange(metricId, last.value, last.key, prev?.value, prev?.key, ch, dir)
}

/** 1.4 Weekly goals tile: workout minutes, steps, heart points, workout days of the current week with pace at [now]. */
fun V2Engine.weeklyGoals(now: LocalDateTime): List<WeeklyGoalPace> {
    val today = now.toLocalDate()
    val wk = SectionPeriod.current(PeriodUnit.WEEK, today, dataStart())
    val f = Pace.fraction(wk, now)
    return listOf("exercise_min", "steps", "zone_min", "week_workouts").mapNotNull { id ->
        val v = value(id, wk, today) ?: return@mapNotNull null
        val g = V2Math.goalFor(id, goals, wk)?.value ?: return@mapNotNull null
        val a = v.value ?: 0.0
        WeeklyGoalPace(id, a, g, Pace.expected(g, f), f, Pace.status(a, g, f))
    }
}

/** Instant helper for UIs that need a session's local start. */
fun V2Engine.startOf(w: Workout): LocalDateTime = Instant.ofEpochMilli(w.start).atZone(zone).toLocalDateTime()
