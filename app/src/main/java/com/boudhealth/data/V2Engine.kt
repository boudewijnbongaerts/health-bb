package com.boudhealth.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/*
 * V2 section data engine (IC2), PURE: works on [CacheReads] only, so the same code runs on the device (V2 facade) and on
 * the JVM against the real-data fixture (scratchpad/datatest/V2Test.kt). One instance = one consistent view (one goals,
 * one max HR); it memoises its own reads, so a bundle reads each metric's daily rows once (perf contract, RK4).
 *
 * Counting rule (V2-049): every period value of a daily-total metric is the SUM of the same per-day values, from one
 * function ([daily]); Day ⊂ Week ⊂ Month ⊂ Year agree by construction (AcceptanceV2).
 */

/** Pure read helpers shared with [Local] (one rule for app, tiles, widgets and the JVM harness). */
object V2Reads {
    fun dayStartMs(d: LocalDate, zone: ZoneId): Long = d.atStartOfDay(zone).toInstant().toEpochMilli()
    fun dayOf(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    /** De-duplicated workouts starting on [from]..[to] (inclusive), newest first. */
    fun workouts(reads: CacheReads, from: LocalDate, to: LocalDate, zone: ZoneId): List<Workout> =
        WorkoutDedupe.of(reads.workouts(dayStartMs(from, zone), dayStartMs(to.plusDays(1), zone)))

    /**
     * Workout minutes per day in [from]..[to]: union of the sessions (overlaps count once) clipped to each day. Sorted
     * sweep, O(sessions + days) (was O(days × sessions), V2-051). Same result as WorkoutMath.unionMinutes per day.
     */
    fun exerciseMinutes(workouts: List<Workout>, from: LocalDate, to: LocalDate, zone: ZoneId): Map<LocalDate, Int> {
        val out = LinkedHashMap<LocalDate, Int>()
        if (to.isBefore(from)) return out
        val days = SyncLogic.datesBetween(from, to)
        val bounds = LongArray(days.size + 1) { i -> dayStartMs(if (i < days.size) days[i] else to.plusDays(1), zone) }
        val msPerDay = LongArray(days.size)
        // union intervals
        val spans = workouts.map { it.start to it.end }.filter { it.second > it.first }.sortedBy { it.first }
        val merged = ArrayList<LongArray>()
        for ((a, b) in spans) {
            val last = merged.lastOrNull()
            if (last != null && a <= last[1]) { if (b > last[1]) last[1] = b } else merged += longArrayOf(a, b)
        }
        var di = 0
        for (iv in merged) {
            val a = maxOf(iv[0], bounds[0]); val b = minOf(iv[1], bounds[days.size])
            if (b <= a) continue
            while (di > 0 && bounds[di] > a) di--
            while (di < days.size && bounds[di + 1] <= a) di++
            var k = di
            while (k < days.size && bounds[k] < b) {
                val s = maxOf(a, bounds[k]); val e = minOf(b, bounds[k + 1])
                if (e > s) msPerDay[k] += e - s
                k++
            }
        }
        for (i in days.indices) out[days[i]] = (msPerDay[i] / 60_000L).toInt()
        return out
    }

    /** Main sleep per wake-up day, oldest first. */
    fun sleepNights(reads: CacheReads, from: LocalDate, to: LocalDate): List<SleepNight> {
        val byMetric = M.SLEEP.associateWith { m -> reads.daily(m, from, to).associateBy { it.day } }
        val days = byMetric.getValue(M.SLEEP_END).keys.sorted()
        return days.mapNotNull { d ->
            fun v(m: String) = byMetric.getValue(m)[d]?.value
            val end = v(M.SLEEP_END) ?: return@mapNotNull null
            SleepNight(d, SleepInfo(
                start = (v(M.SLEEP_START) ?: end).toLong(), end = end.toLong(),
                deepMin = (v(M.SLEEP_DEEP) ?: 0.0).toInt(), remMin = (v(M.SLEEP_REM) ?: 0.0).toInt(),
                lightMin = (v(M.SLEEP_LIGHT) ?: 0.0).toInt(), awakeMin = (v(M.SLEEP_AWAKE) ?: 0.0).toInt(),
                asleepUnspecifiedMin = (v(M.SLEEP_ASLEEP) ?: 0.0).toInt(),
            ))
        }
    }

    fun heightM(reads: CacheReads): Double? =
        (reads.latest(M.HEIGHT) ?: reads.latest(M.HEIGHT_MANUAL))?.value?.takeIf { it in 0.5..2.6 }

    /** Max HR the STORED zone rows were computed with (meta "zone_params" … ";hrmax=184"), so 1D hours = day totals. */
    fun storedMaxHr(reads: CacheReads): Int? = reads.meta("zone_params")?.substringAfter("hrmax=", "")?.toIntOrNull()
}

class V2Engine(
    val reads: CacheReads,
    val goals: Goals,
    val zone: ZoneId,
    /** Max HR for hourly zones; default = the one the stored daily zone rows used, else the resolved value. */
    val maxHr: Int = V2Reads.storedMaxHr(reads) ?: (goals.maxHrFit ?: MaxHr.FIT_DEFAULT),
    /** Workout kind (ExerciseTypes.group on the device). */
    val group: (Workout) -> WorkoutGroup = { WorkoutGroup.of(null, it.title) },
) {
    private val ALL0 = LocalDate.of(2000, 1, 1)

    // ------------------------------------------------------------------ memoised reads

    private data class Slice(val from: LocalDate, val to: LocalDate, val rows: Map<LocalDate, Double>)
    // M2: one engine is shared by concurrent IO loads (section load + prefetch, other screens): thread-safe memo.
    // Slices are built locally and never mutated after being stored; a race only recomputes a slice.
    private val slices = java.util.concurrent.ConcurrentHashMap<String, Slice>()
    @Volatile private var workoutCache: Triple<LocalDate, LocalDate, List<Workout>>? = null
    @Volatile private var workoutDaysAll: Set<LocalDate>? = null
    @Volatile private var dataStartMemo: LocalDate? = null

    /** Bumped by [invalidateFrom]: a read that started before an invalidation is never memoised (M2 rule). */
    @Volatile private var gen = 0L

    /**
     * V2_SYNC_PERF: the cache changed from [day] on (a sync re-read recent days). Instead of throwing the whole engine
     * away (every slice of 3+ years re-read on every app open), memoised rows are trimmed to the days before
     * `day − 1` (one day of margin for records near midnight / in another time zone) and only the tail is read again.
     * Derived metrics and workouts are recomputed (they are cheap and can span days). dataStart is kept unless the
     * change reaches back to it.
     */
    @Synchronized fun invalidateFrom(day: LocalDate) {
        val cut = day.minusDays(1)
        gen++
        for ((k, s) in slices.entries.toList()) {
            when {
                k.startsWith("derived|") || !s.from.isBefore(cut) -> slices.remove(k)
                !s.to.isBefore(cut) -> slices[k] = Slice(s.from, cut.minusDays(1), s.rows.filterKeys { it.isBefore(cut) })
            }
        }
        workoutCache = null
        workoutDaysAll = null
        dataStartMemo?.let { if (!it.isBefore(cut)) dataStartMemo = null }
    }

    /** Warm the memo for a whole range (bundle: [previous.start, end]) so later reads are sub-ranges. */
    fun preload(metricIds: Collection<String>, from: LocalDate, to: LocalDate) {
        for (id in metricIds) runCatching { daily(id, from, to) }
        workouts(from.minusDays(1), to)
    }

    /** Raw plausible rows of a cache key, memoised by covering range. */
    private fun rows(key: String, plausible: ClosedFloatingPointRange<Double>, from: LocalDate, to: LocalDate): Map<LocalDate, Double> {
        val ck = "$key|$plausible"
        val g = gen
        val s = slices[ck]
        if (s != null && !from.isBefore(s.from) && !to.isAfter(s.to)) return s.rows.filterKeys { !it.isBefore(from) && !it.isAfter(to) }
        val a = if (s != null) minOf(s.from, from) else from
        val b = if (s != null) maxOf(s.to, to) else to
        val m = LinkedHashMap<LocalDate, Double>()
        if (s != null && !from.isBefore(s.from)) {
            // V2_SYNC_PERF: only the days after the memoised slice are read (after a sync, invalidateFrom trimmed it)
            m.putAll(s.rows)
            for (r in reads.daily(key, s.to.plusDays(1), b)) if (r.value in plausible) m[r.day] = r.value
        } else {
            for (r in reads.daily(key, a, b)) if (r.value in plausible) m[r.day] = r.value
        }
        // M2 + V2_SYNC_PERF: never store a slice read across an invalidation (it may hold pre-sync rows)
        if (gen == g) slices[ck] = Slice(a, b, m)
        return m.filterKeys { !it.isBefore(from) && !it.isAfter(to) }
    }

    /** De-duplicated workouts starting on [from]..[to], newest first (memoised by covering range). */
    fun workouts(from: LocalDate, to: LocalDate): List<Workout> {
        val g = gen
        val c = workoutCache
        val list = if (c != null && !from.isBefore(c.first) && !to.isAfter(c.second)) c.third else {
            val a = if (c != null) minOf(c.first, from) else from
            val b = if (c != null) maxOf(c.second, to) else to
            V2Reads.workouts(reads, a, b, zone).also { if (gen == g) workoutCache = Triple(a, b, it) }
        }
        return list.filter { val d = V2Reads.dayOf(it.start, zone); !d.isBefore(from) && !d.isAfter(to) }
    }

    /** First day with any activity / body / vitals data (All, swipe clamp). */
    fun dataStart(): LocalDate = dataStartMemo ?: run {
        val g = gen
        val keys = listOf(M.STEPS, M.KCAL, M.WEIGHT, M.RHR, M.SLEEP_MIN)
        // m6: ignore stray rows before 2000 (bad clocks / imports) so All never spans decades of nothing
        val d = keys.mapNotNull { runCatching { reads.oldestDay(it) }.getOrNull()?.takeIf { !it.isBefore(ALL0) } }.minOrNull()
        val w = workoutDays().filter { !it.isBefore(ALL0) }.minOrNull()
        (listOfNotNull(d, w).minOrNull() ?: LocalDate.now(zone)).also { if (gen == g) dataStartMemo = it }
    }

    private fun height(): Double? = V2Reads.heightM(reads)

    /** Weigh-ins with lean / fat (impossible readings hidden, G0). */
    fun composition(from: LocalDate, to: LocalDate): List<WeighIn> {
        val w = rows(M.WEIGHT, Plausible.WEIGHT, from, to)
        val l = rows(M.LEAN_KG, Plausible.LEAN_KG, from, to)
        val f = rows(M.FAT_PCT, Plausible.FAT_PCT, from, to)
        return BodyMath.composition(w.entries.sortedBy { it.key }.map { DayValue(it.key, it.value, 0L, null) }, l, f)
    }

    /**
     * THE per-day values of a V2 metric (plausible only). Every headline, graph, ring and trend is built from this.
     * Derived: exercise_min (sessions), lean_mass / fat_mass (weigh-ins), whtr, bmi, week_workouts (1 = workout day),
     * streak (1 = any workout).
     */
    fun daily(metricId: String, from: LocalDate, to: LocalDate): Map<LocalDate, Double> {
        val spec = V2Metrics.get(metricId) ?: return emptyMap()
        spec.key?.let { return rows(it, spec.plausible, from, to) }
        // derived metrics: memoised like the raw rows (computed once over the covering range)
        val ck = "derived|$metricId"
        val s = slices[ck]
        if (s != null && !from.isBefore(s.from) && !to.isAfter(s.to)) return s.rows.filterKeys { !it.isBefore(from) && !it.isAfter(to) }
        val a = if (s != null) minOf(s.from, from) else from
        val b = if (s != null) maxOf(s.to, to) else to
        val m = derived(metricId, a, b)
        slices[ck] = Slice(a, b, m)
        return if (a == from && b == to) m else m.filterKeys { !it.isBefore(from) && !it.isAfter(to) }
    }

    private fun derived(metricId: String, from: LocalDate, to: LocalDate): Map<LocalDate, Double> {
        return when (metricId) {
            "exercise_min" -> V2Reads.exerciseMinutes(workouts(from.minusDays(1), to), from, to, zone).filterValues { it > 0 }.mapValues { it.value.toDouble() }
            "lean_mass" -> composition(from, to).mapNotNull { w -> w.leanKg?.let { w.day to it } }.toMap()
            "fat_mass" -> composition(from, to).mapNotNull { w -> w.fatKg?.let { w.day to it } }.toMap()
            "whtr" -> height()?.let { h -> rows(M.WAIST, 40.0..200.0, from, to).mapValues { it.value / 100.0 / h } } ?: emptyMap()
            "bmi" -> height()?.let { h -> rows(M.WEIGHT, Plausible.WEIGHT, from, to).mapValues { it.value / (h * h) } } ?: emptyMap()
            "week_workouts" -> activeDays(from, to).associateWith { 1.0 }
            "streak" -> workouts(from, to).filter { streakSession(it) }.map { V2Reads.dayOf(it.start, zone) }.toSet().associateWith { 1.0 }
            else -> emptyMap()
        }
    }

    /** Last plausible value before [day] (MEASUREMENT carry, D14), within 3 years. */
    private fun before(metricId: String, day: LocalDate): Pair<LocalDate, Double>? {
        val spec = V2Metrics.get(metricId) ?: return null
        if (spec.kind != V2Kind.MEASUREMENT && spec.kind != V2Kind.DAILY_VALUE) return null
        return daily(metricId, day.minusYears(3), day.minusDays(1)).maxByOrNull { it.key }?.toPair()
    }

    /** D14: the last plausible value on or before [day] (within 3 years), with its date — for per-column carry in tables. */
    fun lastOnOrBefore(metricId: String, day: LocalDate): Pair<LocalDate, Double>? =
        daily(metricId, day.minusYears(3), day).maxByOrNull { it.key }?.toPair()

    // ------------------------------------------------------------------ workouts, active days, streak

    /** Workout days by Goals.activeDayRule (D18) in [from]..[to]. */
    fun activeDays(from: LocalDate, to: LocalDate): Set<LocalDate> {
        val rule = goals.activeDayRule
        val days = SyncLogic.datesBetween(from, to)
        return when (rule.mode) {
            ActiveDayRule.Mode.WORKOUT -> rule.activeDays(days, workouts(from, to).groupBy { V2Reads.dayOf(it.start, zone) }, emptyMap())
            ActiveDayRule.Mode.POINTS -> rule.activeDays(days, emptyMap(), rows(M.ZONE_MIN, 0.0..1_000.0, from, to).mapValues { it.value.toInt() })
        }
    }

    /** Days with any recorded workout, all history (streak; v6 meaning kept). */
    private fun workoutDays(): Set<LocalDate> = workoutDaysAll ?: run {
        val g = gen
        val today = LocalDate.now(zone)
        workouts(ALL0, today.plusDays(1)).filter { streakSession(it) }.map { V2Reads.dayOf(it.start, zone) }.toHashSet().also { if (gen == g) workoutDaysAll = it }
    }

    /**
     * Streak day = a day with a recorded workout of ≥ Goals.activeDayMinWorkoutMin (20) minutes (de-duplicated session,
     * local start day). Chosen over "any workout" (short auto-detected sessions bridge gaps and inflate the streak) and over the full
     * workout-day rule (≥ 10 points too), which is stricter than people expect from a streak.
     */
    fun streakSession(w: Workout): Boolean = w.minutes >= goals.activeDayRule.minWorkoutMin

    /** (current streak, best streak) as of [day] (B7). */
    fun streakAsOf(day: LocalDate): Pair<Int, Int> = V2Math.streakAsOf(workoutDays(), day)

    fun periodWorkouts(p: SectionPeriod, today: LocalDate): PeriodWorkouts {
        val end = minOf(p.last, today)
        val sessions = if (end.isBefore(p.start)) emptyList() else workouts(p.start, end)
        val minutes = if (end.isBefore(p.start)) 0 else V2Reads.exerciseMinutes(workouts(p.start.minusDays(1), end), p.start, end, zone).values.sum()
        val active = if (end.isBefore(p.start)) emptySet() else activeDays(p.start, end)
        val types = sessions.map(group).groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<WorkoutGroup, Int>> { it.value }.thenBy { it.key.ordinal }).map { it.key to it.value }
        val (streak, best) = streakAsOf(end)
        val goal = V2Math.goalFor("week_workouts", goals, p)?.value?.toInt()
        return PeriodWorkouts(p, sessions, sessions.size, minutes, active, goal, types, streak, best,
            if (p is SectionPeriod.Day) p.start in active else null)
    }

    // ------------------------------------------------------------------ zones and hourly

    internal fun restFor(day: LocalDate): Int = HrZones.restFor(day, rows(M.RHR, 25.0..140.0, day.minusDays(7), day)).first

    /** Per hour of [day]: (hour start, fat burn, cardio, peak minutes); empty without heart rate. */
    private fun zoneHours(day: LocalDate): List<Pair<LocalDateTime, IntArray>> {
        val hd = reads.hrDay(day) ?: return emptyList()
        val rest = restFor(day)
        return hourSlots(hd).map { (at, m0, m1) -> at to HrZones.minutes(hd.bpm, maxHr, rest, m0, m1) }
    }

    /** (hour start, first minute, end minute) of the stored day (DST-aware, from its minute-0 instant). */
    private fun hourSlots(hd: HrDay): List<Triple<LocalDateTime, Int, Int>> {
        val start = Instant.ofEpochMilli(hd.start(zone)).atZone(zone)
        val dayMin = ChronoUnit.MINUTES.between(hd.day.atStartOfDay(zone), hd.day.plusDays(1).atStartOfDay(zone)).toInt().coerceAtMost(HrDay.MINUTES)
        val out = ArrayList<Triple<LocalDateTime, Int, Int>>()
        var h = 0
        while (h * 60 < dayMin) {
            out += Triple(start.plusHours(h.toLong()).toLocalDateTime(), h * 60, minOf(h * 60 + 60, dayMin))
            h++
        }
        return out
    }

    /** Heart points with zone minutes for a period: totals from the stored daily rows; graph per hour (1D) or bucket. */
    fun zones(p: SectionPeriod, today: LocalDate): PeriodZones {
        val end = minOf(p.last, today)
        val az = (0..3).map { i -> if (end.isBefore(p.start)) emptyMap() else rows(M.AZM[i], 0.0..1_440.0, p.start, end) }
        val pts = if (end.isBefore(p.start)) emptyMap() else rows(M.ZONE_MIN, 0.0..1_000.0, p.start, end)
        val minutes = IntArray(4) { i -> az[i].values.sum().toInt() }
        val points = pts.values.sum().toInt()
        val grain = V2Math.grainFor(p)
        val buckets = if (p is SectionPeriod.Day) {
            zoneHours(p.start).map { (at, z) ->
                SeriesBucket(at, at.plusHours(1), HrZones.intensity(z).toDouble(), segments = doubleArrayOf(z[1].toDouble(), z[2].toDouble(), z[3].toDouble()))
            }
        } else {
            val sums = V2Math.buckets(pts, p, grain, BucketAgg.SUM, today)
            val segs = (1..3).map { i -> V2Math.buckets(az[i], p, grain, BucketAgg.SUM, today) }
            sums.mapIndexed { k, b -> b.copy(segments = DoubleArray(3) { i -> segs[i][k].value ?: 0.0 }) }
        }
        return PeriodZones(p, minutes, points, buckets, if (p is SectionPeriod.Day) Grain.HOUR else grain, V2Math.goalFor("zone_min", goals, p))
    }

    /** 1D graph of one metric. */
    private fun hourly(spec: V2Metric, day: LocalDate, now: LocalDateTime): PeriodSeries {
        val p = SectionPeriod.Day(day.toEpochDay())
        val goalLine: Double? = null
        fun hoursOfDay(): List<LocalDateTime> {
            val out = ArrayList<LocalDateTime>()
            var t = day.atStartOfDay(zone)
            val e = day.plusDays(1).atStartOfDay(zone)
            while (t.isBefore(e)) { out += t.toLocalDateTime(); t = t.plusHours(1) }
            return out
        }
        fun mark(b: SeriesBucket): SeriesBucket {
            val fut = b.start.isAfter(now); val part = !fut && !now.isBefore(b.start) && now.isBefore(b.end)
            return b.copy(future = fut, partial = part)
        }
        return when (spec.hourly) {
            HourlySource.CACHE -> {
                val a = V2Reads.dayStartMs(day, zone); val b = V2Reads.dayStartMs(day.plusDays(1), zone)
                val h = reads.hourly(spec.key!!, a, b).associate { (ms, v) -> Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime() to v }
                if (h.isEmpty()) {
                    val dv = reads.daily(spec.key, day, day).firstOrNull()
                    val gap = when {
                        dv == null -> SeriesGap.NONE
                        dv.source?.startsWith("com.apple.health.import") == true -> SeriesGap.NO_HOURLY_SOURCE
                        else -> SeriesGap.HOURLY_NOT_CACHED
                    }
                    PeriodSeries(spec.id, p, Grain.HOUR, emptyList(), goalLine, gap)
                } else PeriodSeries(spec.id, p, Grain.HOUR, hoursOfDay().map { t -> mark(SeriesBucket(t, t.plusHours(1), h[t])) }, goalLine)
            }
            HourlySource.HR_DAY -> {
                val hd = reads.hrDay(day)
                if (hd == null) PeriodSeries(spec.id, p, Grain.HOUR, emptyList(), goalLine, SeriesGap.NONE)
                else if (spec.id == "zone_min") {
                    val z = zones(p, now.toLocalDate()).perBucket.map(::mark)
                    PeriodSeries(spec.id, p, Grain.HOUR, z, goalLine)
                } else {
                    PeriodSeries(spec.id, p, Grain.HOUR, hourSlots(hd).map { (at, m0, m1) ->
                        var sum = 0; var n = 0; var mn = Int.MAX_VALUE; var mx = 0
                        for (m in m0 until m1) { val v = hd.at(m); if (v < Plausible.HR_MIN_BPM) continue; sum += v; n++; if (v < mn) mn = v; if (v > mx) mx = v }
                        mark(if (n == 0) SeriesBucket(at, at.plusHours(1), null) else SeriesBucket(at, at.plusHours(1), sum.toDouble() / n, mn.toDouble(), mx.toDouble()))
                    }, goalLine)
                }
            }
            HourlySource.SESSIONS -> {
                val ws = workouts(day.minusDays(1), day).map { it.start to it.end }
                PeriodSeries(spec.id, p, Grain.HOUR, hoursOfDay().map { t ->
                    val a = t.atZone(zone).toInstant().toEpochMilli(); val b = a + 3_600_000L
                    mark(SeriesBucket(t, t.plusHours(1), WorkoutMath.unionMinutes(ws, a, b).toDouble()))
                }, goalLine)
            }
            HourlySource.NONE -> PeriodSeries(spec.id, p, Grain.DAY, V2Math.buckets(daily(spec.id, day, day), p, Grain.DAY, spec.agg, now.toLocalDate()), null)
        }
    }

    // ------------------------------------------------------------------ per-metric API

    fun value(metricId: String, p: SectionPeriod, today: LocalDate): PeriodValue? {
        val spec = V2Metrics.get(metricId) ?: return null
        val end = minOf(p.last, today)
        val d = if (end.isBefore(p.start)) emptyMap() else daily(metricId, p.start, end)
        val v = V2Math.value(spec, d, p, today, V2Math.goalFor(metricId, goals, p), if (d.isEmpty()) before(metricId, p.start) else null)
        // streak: COUNT of days is meaningless; the value is the streak as of the period end
        return if (metricId == "streak") v.copy(value = streakAsOf(end).first.toDouble()) else v
    }

    fun series(metricId: String, p: SectionPeriod, now: LocalDateTime): PeriodSeries? {
        val spec = V2Metrics.get(metricId) ?: return null
        val today = now.toLocalDate()
        if (p is SectionPeriod.Day) return hourly(spec, p.start, now)
        val grain = V2Math.grainFor(p)
        val end = minOf(p.last, today)
        val d = if (end.isBefore(p.start)) emptyMap() else daily(metricId, p.start, end)
        val agg = if (metricId == "sleep" && grain == Grain.DAY) BucketAgg.LAST else spec.agg
        return PeriodSeries(metricId, p, grain, V2Math.buckets(d, p, grain, agg, today),
            if (grain == Grain.DAY) V2Math.dailyGoal(metricId, goals) else null)
    }

    fun trend(metricId: String, p: SectionPeriod, today: LocalDate): PeriodTrend? {
        val spec = V2Metrics.get(metricId) ?: return null
        if (metricId == "streak") return null
        // All: measurements compare first vs last reading (QA 12.1); other kinds have no previous period
        val prev = p.previous() ?: if (spec.kind == V2Kind.MEASUREMENT) p else return null
        // measurements and daily values on a day look back for the previous reading (R3 §2.7)
        val from = if (spec.kind == V2Kind.MEASUREMENT || (p is SectionPeriod.Day && spec.kind != V2Kind.DAILY_TOTAL && spec.kind != V2Kind.WORKOUT)) p.start.minusYears(3) else prev.start
        if (spec.kind == V2Kind.DAILY_VALUE && spec.key != null && !sameSourceMix(spec.key, p, today)) return null
        return V2Math.trend(spec, daily(metricId, from, minOf(p.last, today)), p, today)
    }

    /**
     * QA 17.2: a daily-value trend (RHR, HRV, breathing, SpO₂) only compares like with like: both periods need enough
     * readings (1D: 1 · 1W: 3 · longer: 5) and the same main source (Apple import vs Health Connect). A watch change
     * (Apple Watch → Fitbit) or a handful of scattered readings would otherwise read as a physiological change.
     */
    fun sameSourceMix(key: String, p: SectionPeriod, today: LocalDate): Boolean {
        val prev = p.previous() ?: return false
        val end = minOf(p.last, today)
        if (end.isBefore(p.start)) return false
        val rows = reads.daily(key, prev.start, end)
        val spec = V2Metrics.all.firstOrNull { it.key == key }
        fun side(xs: List<DayValue>): Boolean? {
            val ok = xs.filter { spec == null || it.value in spec.plausible }
            val need = when (p) { is SectionPeriod.Day -> 1; is SectionPeriod.Week -> 3; else -> 5 }
            if (ok.size < need) return null
            return ok.count { it.source?.startsWith("com.apple.health.import") == true } * 2 > ok.size
        }
        val a = side(rows.filter { !it.day.isBefore(p.start) }) ?: return false
        val b = side(rows.filter { it.day.isBefore(p.start) }) ?: return false
        return a == b
    }

    fun pace(metricId: String, p: SectionPeriod, now: LocalDateTime): PeriodPace? {
        if (!p.isCurrent(now.toLocalDate())) return null
        val spec = V2Metrics.get(metricId) ?: return null
        if (spec.kind != V2Kind.DAILY_TOTAL && metricId != "week_workouts") return null
        val v = value(metricId, p, now.toLocalDate()) ?: return null
        return Pace.of(metricId, v.value, v.goal, p, now)
    }

    /** Rings for [p] (D3/D4); 1D = Today tile = widget rings (one path). */
    fun rings(p: SectionPeriod, today: LocalDate): RingSet {
        val end = minOf(p.last, today)
        fun total(id: String) = if (end.isBefore(p.start)) 0.0 else daily(id, p.start, end).values.sum()
        return V2Math.ringSet(p, goals, total("exercise_min"), total("steps"), total("zone_min"))
    }

    fun sleep(p: SectionPeriod, today: LocalDate): List<SleepNight> {
        val end = minOf(p.last, today)
        return if (end.isBefore(p.start)) emptyList() else V2Reads.sleepNights(reads, p.start, end)
    }

    fun card(metricId: String, p: SectionPeriod, now: LocalDateTime): CardData {
        val today = now.toLocalDate()
        val end = minOf(p.last, today)
        return CardData(
            metricId,
            value(metricId, p, today),
            series(metricId, p, now),
            trend(metricId, p, today),
            pace(metricId, p, now),
            workouts = if (metricId == "streak" || metricId == "week_workouts" || metricId == "exercise_min") periodWorkouts(p, today) else null,
            zones = if (metricId == "zone_min" || metricId == "heart_zones") zones(p, today) else null,
            sleep = if (metricId == "sleep") sleep(p, today) else null,
            composition = if (metricId == "weight" && !end.isBefore(p.start)) composition(p.start, end) else null,
            whtr = if (metricId == "waist") height()?.let { h -> value("waist", p, today)?.value?.let { it / 100.0 / h } } else null,
            band = if (metricId == "hr" && p !is SectionPeriod.Day) hrBand(p, today) else null,
        )
    }

    /** Heart rate band: per day min–max (1W/1M), per month (1Y/All). */
    fun hrBand(p: SectionPeriod, today: LocalDate): List<SeriesBucket> {
        val end = minOf(p.last, today)
        if (end.isBefore(p.start)) return emptyList()
        val mn = rows(M.HR_MIN, Plausible.HR_BPM, p.start, end); val mx = rows(M.HR_MAX, Plausible.HR_BPM, p.start, end)
        val grain = V2Math.grainFor(p)
        val lo = V2Math.buckets(mn, p, grain, BucketAgg.MIN_MAX, today); val hi = V2Math.buckets(mx, p, grain, BucketAgg.MIN_MAX, today)
        return lo.mapIndexed { i, b -> b.copy(value = null, min = b.min, max = hi[i].max) }
    }

    // ------------------------------------------------------------------ Body hero (D9)

    /** Start value of a body target: stored when set, else first value on/after the set day, else the value 90 days back. */
    fun bodyTargetStart(metricId: String, today: LocalDate): Double? {
        val (stored, setDay) = when (metricId) {
            "weight" -> goals.targetWeightStart to goals.targetWeightSetDay
            "body_fat" -> goals.targetFatPctStart to goals.targetFatPctSetDay
            "waist" -> goals.targetWaistStart to goals.targetWaistSetDay
            else -> return null
        }
        stored?.let { return it }
        if (setDay != null) {
            val d = LocalDate.ofEpochDay(setDay)
            daily(metricId, d, today).minByOrNull { it.key }?.let { return it.value }
        }
        val back = today.minusDays(90)
        return daily(metricId, back.minusYears(3), back).maxByOrNull { it.key }?.value
            ?: daily(metricId, back, today).minByOrNull { it.key }?.value
    }

    fun bodyTargets(p: SectionPeriod, today: LocalDate): List<TargetRing> = listOf("weight", "body_fat", "waist").map { id ->
        val v = value(id, p, today)
        val target = V2Math.goalFor(id, goals, p)?.value
        var start = if (target != null) bodyTargetStart(id, today) else null
        val cur = v?.value
        var prog: Float? = null; var met = false
        if (target != null && cur != null) {
            val down = V2Math.targetDown(id, start, cur, target)
            // a stored / backfilled start on the wrong side of the target (or behind the current value) is re-based on
            // the 90 days up to the period end: highest value for a "down" goal, lowest for an "up" goal
            val wrong = start == null || (if (down) start <= target || start < cur else start >= target || start > cur)
            if (wrong) {
                val end = minOf(p.last, today)
                val xs = daily(id, end.minusDays(90), end).values + cur
                start = if (down) xs.max() else xs.min()
            }
            prog = V2Math.targetProgress(start, target, cur, down)
            met = prog == 1f
        }
        TargetRing(id, cur, v?.at, start, target, prog, met, v?.carried == true)
    }

    // ------------------------------------------------------------------ section bundle

    fun bundle(section: SectionId, scope: SectionScope, cardMetricIds: List<String>, now: LocalDateTime, version: Long = 0): SectionBundle {
        val today = now.toLocalDate()
        val p = scope.effective
        val ids = cardMetricIds.ifEmpty { MetricSections.of(section) }
        // one read per metric for [previous.start, end] (perf contract)
        val from = (p.previous()?.start ?: p.start)
        preload(ids + if (section == SectionId.ACTIVITY) PeriodRing.ORDER else emptyList(), from, minOf(p.last, today))
        val cards = LinkedHashMap<String, CardData>()
        for (id in ids) cards[id] = card(id, p, now)
        val rings = if (section == SectionId.ACTIVITY) rings(p, today) else null
        val workouts = if (section == SectionId.ACTIVITY) periodWorkouts(p, today) else null
        val (streak, _) = if (section == SectionId.ACTIVITY) streakAsOf(today) else 0 to 0
        // streak = days with any workout, so "at risk" = no workout recorded yet today
        val todayActive = if (section == SectionId.ACTIVITY) workouts(today, today).isNotEmpty() else true
        val insights = Insights.build(section, p, now, cards, rings, goals, streak, todayActive)
        return SectionBundle(section, scope, rings, if (section == SectionId.BODY) bodyTargets(p, today) else null,
            cards, insights, workouts, dataStart(), version)
    }

    // ------------------------------------------------------------------ settings helpers (IC10)

    /**
     * D17 "Suggested for you" is DROPPED (R3, IMPL_PLAN §2.7: it was a 12-week median; no averages). Always null, so
     * Settings hides the row and the sheet line. Kept as a function so its callers still compile.
     */
    @Suppress("UNUSED_PARAMETER")
    fun suggestedHeartPointsTarget(today: LocalDate): Int? = null

    /** All metrics rows: latest plausible value and the trend direction of the current week (totals) or month. */
    fun metricIndex(today: LocalDate): List<MetricIndexRow> = MetricSections.ALL_METRICS.flatMap { (section, ids) ->
        ids.map { id ->
            val spec = V2Metrics.get(id)
            val last = runCatching { daily(id, today.minusYears(3), today).maxByOrNull { it.key } }.getOrNull()
            val p = if (spec?.kind == V2Kind.DAILY_TOTAL || spec?.kind == V2Kind.WORKOUT) SectionPeriod.current(PeriodUnit.WEEK, today, today)
                else SectionPeriod.current(PeriodUnit.MONTH, today, today)
            val latest = when (id) {
                "streak" -> streakAsOf(today).first.toDouble()
                "week_workouts" -> activeDays(SectionPeriod.mondayOf(today), today).size.toDouble()
                else -> last?.value
            }
            MetricIndexRow(id, section, latest, if (id == "streak" || id == "week_workouts") today else last?.key, spec?.unit ?: "",
                runCatching { trend(id, p, today)?.dir }.getOrNull())
        }
    }
}
