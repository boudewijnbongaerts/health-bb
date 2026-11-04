package com.boudhealth.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/*
 * R3 engine extensions (IMPL_PLAN §2.6), PURE (on V2Engine / CacheReads), JVM-tested in tools/unit/tests/R3StatsTest.kt.
 * Same per-day values as every card ([V2Engine.daily]); hourly reads only for the single cut day (and yesterday on 1D).
 */

private val HOURLY_KEY = mapOf("steps" to M.STEPS, "calories" to M.KCAL)

private fun V2Engine.dayValue(metricId: String, day: LocalDate): Double = daily(metricId, day, day)[day] ?: 0.0

private fun V2Engine.sumDays(metricId: String, from: LocalDate, to: LocalDate): Double =
    if (to.isBefore(from)) 0.0 else daily(metricId, from, to).values.sum()

/**
 * The amount of [metricId] on [day] from midnight to [cut]. [day] ≥ [today] (the running day): its stored daily value
 * (so far). Earlier days: steps / calories from the hourly cache (the hour holding the cut pro rata, as the mock-ups);
 * exercise_min = union of sessions inside [00:00, cut]; zone_min = zone minutes of the stored minute heart rate up to
 * the cut. null = the day has a value but no hourly data to cut it (→ [CompareWindow.wholeDays]).
 */
fun V2Engine.partialDay(metricId: String, day: LocalDate, cut: LocalTime, today: LocalDate = LocalDate.now(zone)): Double? {
    if (!day.isBefore(today)) return dayValue(metricId, day)
    val dayStart = V2Reads.dayStartMs(day, zone)
    val cutMs = day.atTime(cut).atZone(zone).toInstant().toEpochMilli()
    when (metricId) {
        "steps", "calories" -> {
            val key = HOURLY_KEY.getValue(metricId)
            val rows = reads.hourly(key, dayStart, V2Reads.dayStartMs(day.plusDays(1), zone))
            if (rows.isEmpty()) return if (dayValue(metricId, day) > 0.0) null else 0.0
            var sum = 0.0
            for ((ms, v) in rows) {
                val end = ms + 3_600_000L
                when {
                    end <= cutMs -> sum += v
                    ms < cutMs -> sum += v * (cutMs - ms) / 3_600_000.0
                }
            }
            return sum
        }
        "exercise_min" -> {
            val ws = workouts(day.minusDays(1), day).map { it.start to it.end }
            return WorkoutMath.unionMinutes(ws, dayStart, cutMs).toDouble()
        }
        "zone_min" -> {
            val hd = reads.hrDay(day) ?: return if (dayValue(metricId, day) > 0.0) null else 0.0
            val m1 = ((cutMs - hd.start(zone)) / 60_000L).coerceIn(0L, HrDay.MINUTES.toLong()).toInt()
            return HrZones.intensity(HrZones.minutes(hd.bpm, maxHr, restFor(day), 0, m1)).toDouble()
        }
        else -> return null
    }
}

/**
 * Total of [metricId] from [from] 00:00 to [to]: whole days from..to−1 + the day of [to] up to its clock time
 * ([partialDay]; a missing cut counts 0 — check with [hourlyAvailable] first). [to] at 00:00 = whole days only.
 * The current side uses the same function with `to = now`.
 */
fun V2Engine.periodTotalUntil(metricId: String, from: LocalDate, to: LocalDateTime, today: LocalDate = LocalDate.now(zone)): Double {
    val toDay = to.toLocalDate()
    var sum = sumDays(metricId, from, toDay.minusDays(1))
    if (to.toLocalTime() != LocalTime.MIDNIGHT && !toDay.isBefore(from)) sum += partialDay(metricId, toDay, to.toLocalTime(), today) ?: 0.0
    return sum
}

/** True when [day] can be cut at a clock time for [metricId] (hourly data present, or nothing to cut). */
fun V2Engine.hourlyAvailable(metricId: String, day: LocalDate, cut: LocalTime = LocalTime.NOON, today: LocalDate = LocalDate.now(zone)): Boolean =
    partialDay(metricId, day, cut, today) != null

/** The comparison window for headers ("until hh:mm"); wholeDays = steps' hourly data of the cut day is missing (O3). */
fun V2Engine.window(p: SectionPeriod, now: LocalDateTime): CompareWindow? {
    val w = R3Math.window(p, now) ?: return null
    val cut = w.cut ?: return w
    return if (hourlyAvailable("steps", w.prevEndDay, cut, now.toLocalDate())) w else w.copy(wholeDays = true)
}

/**
 * [metricId]'s total in [p] vs the previous comparable window (ABSOLUTE on 1D, PERCENT otherwise). Running periods are
 * cut at the same elapsed point and clock time; when the cut day has no hourly data both sides use whole days only
 * (1D then has nothing to compare → null). null for All / unknown metrics.
 */
fun V2Engine.totalChange(metricId: String, p: SectionPeriod, now: LocalDateTime): TotalChange? {
    val w0 = R3Math.window(p, now) ?: return null
    val spec = V2Metrics.get(metricId) ?: return null
    val today = now.toLocalDate()
    val form = if (p is SectionPeriod.Day) ChangeForm.ABSOLUTE else ChangeForm.PERCENT
    val cut = w0.cut
    if (cut == null) {
        val cur = sumDays(metricId, p.start, minOf(p.last, today))
        val prev = sumDays(metricId, w0.prevPeriod.start, w0.prevPeriod.last)
        return R3Math.totalChange(metricId, cur, prev, form, spec.higherIsBetter, w0)
    }
    val prevEnd = w0.prevEndDay
    return if (partialDay(metricId, prevEnd, cut, today) != null) {
        val cur = periodTotalUntil(metricId, p.start, now, today)
        val prev = periodTotalUntil(metricId, w0.prevFrom, w0.prevTo, today)
        R3Math.totalChange(metricId, cur, prev, form, spec.higherIsBetter, w0)
    } else {
        if (p is SectionPeriod.Day) return null
        val w = w0.copy(wholeDays = true)
        val cur = sumDays(metricId, p.start, today.minusDays(1))
        val prev = sumDays(metricId, w0.prevFrom, prevEnd.minusDays(1))
        R3Math.totalChange(metricId, cur, prev, form, spec.higherIsBetter, w)
    }
}

/** dataStart..today per-day values (plausible), memoised by the engine's slices (cleared from a day on by invalidateFrom). */
fun V2Engine.history(metricId: String, today: LocalDate = LocalDate.now(zone)): Map<LocalDate, Double> {
    val ds = dataStart()
    return if (today.isBefore(ds)) emptyMap() else daily(metricId, ds, today)
}

/**
 * The FULL previous period behind the current buckets, aligned by index. 1D: yesterday's hours · 1W: last week Mon..Sun ·
 * 1M: previous month day 1..n (n = this month's length; extra days dropped, missing = null) · 1Y: previous year's
 * months · All: null. zone_min carries the zone segments (fat burn, cardio, peak minutes).
 */
fun V2Engine.ghost(metricId: String, p: SectionPeriod, now: LocalDateTime): Ghost? {
    val prev = p.previous() ?: return null
    val spec = V2Metrics.get(metricId) ?: return null
    val today = now.toLocalDate()
    if (prev.start.isAfter(today)) return null
    return when (p) {
        is SectionPeriod.Day -> {
            val b = series(metricId, prev, now)?.buckets.orEmpty()
            if (b.isEmpty() || b.all { it.value == null && it.segments == null }) null
            else Ghost(b.map { it.value }, if (metricId == "zone_min") b.map { it.segments } else null)
        }
        is SectionPeriod.Week, is SectionPeriod.Month -> {
            val n = p.totalDays()
            val d = daily(metricId, prev.start, minOf(prev.last, today))
            if (d.isEmpty()) return null
            val days = (0 until n).map { prev.start.plusDays(it.toLong()) }
            val segs = if (metricId == "zone_min") {
                val z = zones(prev, today).perBucket
                days.map { day -> if (prev.contains(day)) z.getOrNull(ChronoUnit.DAYS.between(prev.start, day).toInt())?.segments else null }
            } else null
            Ghost(days.map { if (prev.contains(it)) d[it] else null }, segs)
        }
        is SectionPeriod.Year -> {
            val d = daily(metricId, prev.start, minOf(prev.last, today))
            if (d.isEmpty()) return null
            val agg = if (spec.agg == BucketAgg.MIN_MAX) BucketAgg.LAST else spec.agg
            val b = V2Math.buckets(d, prev, Grain.MONTH, agg, today)
            Ghost(b.map { it.value }, if (metricId == "zone_min") zones(prev, today).perBucket.map { it.segments } else null)
        }
        is SectionPeriod.All -> null
    }
}

/** Body line ghost: the previous period's readings per day, aligned by day index to [p] (length = p's days). Day / All: null. */
fun V2Engine.prevSeries(metricId: String, p: SectionPeriod, today: LocalDate = LocalDate.now(zone)): List<Double?>? {
    if (p is SectionPeriod.Day || p is SectionPeriod.All) return null
    val prev = p.previous() ?: return null
    if (prev.start.isAfter(today)) return null
    val d = daily(metricId, prev.start, minOf(prev.last, today))
    if (d.isEmpty()) return null
    return (0 until p.totalDays()).map { i -> prev.start.plusDays(i.toLong()).let { if (prev.contains(it)) d[it] else null } }
}

/** Ring progress (outer → inner: workout, steps, heart points) of [a]..[b] totals vs [goals] (each > 0). */
private fun V2Engine.progress(a: LocalDate, b: LocalDate, goals3: DoubleArray): FloatArray {
    val v = doubleArrayOf(sumDays("exercise_min", a, b), sumDays("steps", a, b), sumDays("zone_min", a, b))
    return FloatArray(3) { if (goals3[it] > 0) (v[it] / goals3[it]).toFloat() else 0f }
}

/** Day rings vs the DAILY goals (same as V2 DAY rings) for [from]..[to], data days only (before dataStart / after today: absent). */
fun V2Engine.dayRings(from: LocalDate, to: LocalDate, today: LocalDate = LocalDate.now(zone)): Map<LocalDate, FloatArray> {
    val a = maxOf(from, dataStart()); val b = minOf(to, today)
    if (b.isBefore(a)) return emptyMap()
    val wm = daily("exercise_min", a, b); val st = daily("steps", a, b); val zp = daily("zone_min", a, b)
    val g = doubleArrayOf(goals.workoutMinDay.toDouble(), goals.steps.toDouble(), HrZones.dailyGoal(goals.zoneMinWeek).toDouble())
    return SyncLogic.datesBetween(a, b).associateWith { d ->
        val v = doubleArrayOf(wm[d] ?: 0.0, st[d] ?: 0.0, zp[d] ?: 0.0)
        FloatArray(3) { if (g[it] > 0) (v[it] / g[it]).toFloat() else 0f }
    }
}

/** Month rings of [year] vs the month-scaled goals (V2Math.goalFor); months after today's / before dataStart's: absent. */
fun V2Engine.monthRings(year: Int, today: LocalDate = LocalDate.now(zone)): Map<YearMonth, FloatArray> {
    val ds = dataStart()
    val out = LinkedHashMap<YearMonth, FloatArray>()
    for (m in 1..12) {
        val ym = YearMonth.of(year, m)
        val p = SectionPeriod.Month(year, m)
        if (p.start.isAfter(today) || p.last.isBefore(ds)) continue
        val g = DoubleArray(3) { i -> V2Math.goalFor(PeriodRing.ORDER[i], goals, p)?.value ?: 0.0 }
        out[ym] = progress(p.start, minOf(p.last, today), g)
    }
    return out
}

/** One ring per year from dataStart's year; the first year's goals prorated from dataStart (days in data × daily goal). */
fun V2Engine.yearRings(today: LocalDate = LocalDate.now(zone)): List<YearRingCell> {
    val ds = dataStart()
    if (today.isBefore(ds)) return emptyList()
    return (ds.year..today.year).map { y ->
        val a = maxOf(LocalDate.of(y, 1, 1), ds); val last = LocalDate.of(y, 12, 31); val b = minOf(last, today)
        val days = ChronoUnit.DAYS.between(a, last) + 1
        val g = doubleArrayOf(goals.workoutMinDay.toDouble() * days, goals.steps.toDouble() * days, Math.round(goals.zoneMinWeek * days / 7.0).toDouble())
        val wm = sumDays("exercise_min", a, b); val st = sumDays("steps", a, b); val zp = sumDays("zone_min", a, b)
        YearRingCell(y, FloatArray(3) { i -> if (g[i] > 0) (doubleArrayOf(wm, st, zp)[i] / g[i]).toFloat() else 0f }, wm / 60.0, st, zp)
    }
}

/** Lifetime (workout hours, steps, heart points) = the sum of [yearRings]. */
fun V2Engine.lifetime(today: LocalDate = LocalDate.now(zone)): Triple<Double, Double, Double> = yearRings(today).let { ys ->
    Triple(ys.sumOf { it.hours }, ys.sumOf { it.steps }, ys.sumOf { it.points })
}

/**
 * The "/ week" column: per Monday, heart points of that Mon–Sun week (up to today) vs the weekly target. Past week:
 * ✓ GOOD met / ▼ BAD missed. Running week: pace ✓ ▲ ● ▼. Weeks starting after today are left out.
 */
fun V2Engine.weekColumn(mondays: List<LocalDate>, now: LocalDateTime): List<WeekColumnCell> {
    val today = now.toLocalDate()
    val target = goals.zoneMinWeek
    return mondays.filter { !it.isAfter(today) }.map { mon ->
        val sun = mon.plusDays(6)
        val pts = Math.round(sumDays("zone_min", mon, minOf(sun, today))).toInt()
        val (icon, tone) = if (sun.isBefore(today)) {
            if (pts >= target) TileIcon.DONE to Tone.GOOD else TileIcon.DOWN to Tone.BAD
        } else when (Pace.status(pts.toDouble(), target.toDouble(), Pace.weekFraction(mon, now))) {
            CardioPace.DONE -> TileIcon.DONE to Tone.GOOD
            CardioPace.AHEAD -> TileIcon.UP to Tone.GOOD
            CardioPace.BEHIND -> TileIcon.DOWN to Tone.BAD
            else -> TileIcon.SAME to Tone.NEUTRAL
        }
        WeekColumnCell(mon, icon, tone, pts, target)
    }
}

/** The day's hourly buckets of [metricId] (Peak hour tile / context); empty without hourly data. */
fun V2Engine.hours(metricId: String, day: LocalDate, now: LocalDateTime): List<SeriesBucket> =
    series(metricId, SectionPeriod.Day(day.toEpochDay()), now)?.buckets.orEmpty()

/** Epoch ms → local time in the engine's zone (bedtimes, session rows). */
fun V2Engine.localTime(ms: Long): LocalTime = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
