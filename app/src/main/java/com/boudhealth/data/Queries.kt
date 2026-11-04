package com.boudhealth.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Values derived locally from the cache (no Health Connect calls): zone minutes, per-hour heart rate,
 * workout heart rate. Blocking — call from Dispatchers.IO.
 */
internal object Derive {

    private const val K_ZONE = "zone_params"

    /** Highest per-minute heart rate of the last 12 months (suggestion for the max HR setting), or null. */
    fun measuredMaxHr(cache: CacheReads): Int? {
        val today = LocalDate.now()
        return cache.daily(M.HR_MAX, today.minusDays(365), today).map { it.value }.filter { it in 100.0..230.0 }.maxOrNull()?.toInt()
    }

    /** Max HR the zones use (v6): measured 12-month max, else 220 − age, else 190. No setting. */
    fun maxHrInfo(cache: CacheReads, goals: Goals): MaxHrInfo =
        MaxHr.resolve(goals.maxHrFit, goals.birthYear, measuredMaxHr(cache), LocalDate.now().year)

    fun hrMax(cache: CacheReads, goals: Goals): Int = maxHrInfo(cache, goals).value

    /** Resting HR per day around [from]..[to] (for the zones' heart-rate reserve). */
    fun restMap(cache: CacheReads, from: LocalDate, to: LocalDate): Map<LocalDate, Double> =
        cache.daily(M.RHR, from.minusDays(7), to).associate { it.day to it.value }

    /** Daily zone rows (intensity minutes as [M.ZONE_MIN], minutes per zone as [M.AZM]) of one stored day. */
    private fun zoneRows(hd: HrDay, hrMax: Int, rest: Map<LocalDate, Double>, time: Long): List<DailyRow> {
        val z = HrZones.minutes(hd.bpm, hrMax, HrZones.restFor(hd.day, rest).first)
        return listOf(DailyRow(M.ZONE_MIN, hd.day, HrZones.intensity(z).toDouble(), time, hd.source, estimated = true)) +
            (0..3).map { DailyRow(M.AZM[it], hd.day, z[it].toDouble(), time, hd.source, estimated = true) }
    }

    /** Resting HR used for [day]: that day's, else the most recent of the 30 days before, else 60. */
    fun restOn(rhrAsc: List<DayValue>, day: LocalDate): Double =
        rhrAsc.lastOrNull { !it.day.isAfter(day) && !it.day.isBefore(day.minusDays(30)) }?.value ?: 60.0

    /** Zone minutes per day from the cached per-minute heart rate, written to `daily` (local, fast). */
    fun recomputeZones(cache: Cache, goals: Goals, from: LocalDate, to: LocalDate, zone: ZoneId = ZoneId.systemDefault()) {
        val hrMax = hrMax(cache, goals)
        var a = from
        while (!a.isAfter(to)) {
            val b = Sync.minDate(to, a.plusDays(89))
            // Health Connect heart-rate days only (one source per day); imported days: recomputeImportZones
            val rest = restMap(cache, a, b)
            val rows = cache.hrDaysHc(a, b).flatMap { hd ->
                zoneRows(hd, hrMax, rest, minOf(System.currentTimeMillis(), hd.day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1))
            }
            cache.replaceDaily(listOf(M.ZONE_MIN) + M.AZM + OLD_ZONE_KEYS, a, b, rows)
            a = b.plusDays(1)
        }
        cache.setMeta(K_ZONE, params(hrMax))
    }

    /** Zone minutes of the Apple Health import (written to the import table, so a rebuild keeps them). */
    fun recomputeImportZones(cache: Cache, goals: Goals, zone: ZoneId = ZoneId.systemDefault()) {
        val (from, to) = cache.importHrDayRange() ?: return
        val hrMax = hrMax(cache, goals)
        var a = from
        while (!a.isAfter(to)) {
            val b = Sync.minDate(to, a.plusDays(89))
            val rest = restMap(cache, a, b)
            val rows = cache.hrDaysImport(a, b).flatMap { hd -> zoneRows(hd, hrMax, rest, hd.day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1) }
            for (m in listOf(M.ZONE_MIN) + M.AZM + OLD_ZONE_KEYS) cache.replaceImportDaily(m, a, b, rows.filter { it.metric == m })
            a = b.plusDays(1)
        }
        // imported workouts: average HR and zone minutes from the (merged) per-minute heart rate
        cache.updateImportWorkoutHr(cache.importWorkouts().map { w ->
            Triple(w.id, avgHr(cache, w.start, w.end, zone) ?: w.avgHr, zoneMinutes(cache, goals, w.start, w.end, hrMax, zone))
        })
    }

    /**
     * Average HR and zone minutes of the HEALTH CONNECT workouts in [from]..[to], from the per-minute heart rate the
     * app shows for those days (per day the side with more measured minutes). Run after an import,
     * a re-import or "Remove import" (the HR source of those days changed).
     */
    fun refreshHcWorkoutsHr(cache: Cache, goals: Goals, from: LocalDate, to: LocalDate, zone: ZoneId = ZoneId.systemDefault()) {
        val ws = cache.workoutsHc(from.atStartOfDay(zone).toInstant().toEpochMilli(), to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        if (ws.isEmpty()) return
        val hrMax = hrMax(cache, goals)
        cache.updateWorkoutHr(ws.map { w -> Triple(w.id, avgHr(cache, w.start, w.end, zone) ?: w.avgHr, zoneMinutes(cache, goals, w.start, w.end, hrMax, zone)) })
    }

    /** v4.0 keys (5 zones on % max HR), removed: deleted on the next recompute. */
    private val OLD_ZONE_KEYS = listOf("hz1", "hz2", "hz3", "hz4", "hz5")

    /** v4.1: Fitbit AZM zones on heart-rate reserve (a change of method or max HR recomputes everything). */
    private fun params(hrMax: Int) = "v41-azm-hrr;hrmax=$hrMax"

    /** When the max HR changed (birth year edited, new observed max): recompute all zone minutes. */
    fun ensureZoneParams(cache: Cache, goals: Goals) {
        val p = params(hrMax(cache, goals))
        if (cache.meta(K_ZONE) == p) return
        recomputeImportZones(cache, goals)
        val (from, to) = cache.hrDayRange() ?: run { cache.setMeta(K_ZONE, p); return }
        recomputeZones(cache, goals, from, to)
        // workouts store their zone minutes too
        val zone = ZoneId.systemDefault()
        val hrMax = hrMax(cache, goals)
        cache.updateWorkoutZones(cache.workoutsHc(0L, Long.MAX_VALUE).map { it.id to zoneMinutes(cache, goals, it.start, it.end, hrMax, zone) })
    }

    /** Calls [f] for every minute in [startMs, endMs) that has a heart-rate value: (day, minute index, bpm). */
    private inline fun forEachMinute(cache: CacheReads, startMs: Long, endMs: Long, zone: ZoneId, f: (HrDay, Int, Int) -> Unit) {
        if (endMs <= startMs) return
        // v4.1 fix (time-zone change): align on each day's STORED minute-0 instant; one day of margin each side, as a
        // day written in another zone can cover these instants (minutes outside the range are clamped away)
        val d0 = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate().minusDays(1)
        val d1 = Instant.ofEpochMilli(endMs - 1).atZone(zone).toLocalDate().plusDays(1)
        for (hd in cache.hrDays(d0, d1)) {
            val dayStart = hd.start(zone)
            val m0 = ((startMs - dayStart) / 60_000L).coerceIn(0, HrDay.MINUTES.toLong()).toInt()
            val m1 = ((endMs - dayStart + 59_999L) / 60_000L).coerceIn(0, HrDay.MINUTES.toLong()).toInt()
            for (m in m0 until m1) {
                val v = hd.at(m)
                if (v > 0) f(hd, m, v)
            }
        }
    }

    fun avgHr(cache: CacheReads, startMs: Long, endMs: Long, zone: ZoneId): Long? {
        var sum = 0L
        var n = 0
        forEachMinute(cache, startMs, endMs, zone) { _, _, v -> sum += v; n++ }
        return if (n == 0) null else Math.round(sum.toDouble() / n)
    }

    /** Intensity minutes (fat burn × 1 + (cardio + peak) × 2) of [startMs, endMs), from the per-minute HR; null = no HR. */
    @Suppress("UNUSED_PARAMETER")
    fun zoneMinutes(cache: CacheReads, goals: Goals, startMs: Long, endMs: Long, hrMax: Int, zone: ZoneId): Int? =
        workoutZones(cache, startMs, endMs, hrMax, zone)?.let { HrZones.intensity(it) }

    /** Minutes per zone (below, fat burn, cardio, peak) of [startMs, endMs); null = no heart rate stored. */
    fun workoutZones(cache: CacheReads, startMs: Long, endMs: Long, hrMax: Int, zone: ZoneId): IntArray? {
        if (endMs <= startMs) return null
        // v4.1 fix (time-zone change): stored minute-0 instants, ±1 day margin (see forEachMinute)
        val d0 = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate().minusDays(1)
        val d1 = Instant.ofEpochMilli(endMs - 1).atZone(zone).toLocalDate().plusDays(1)
        val days = cache.hrDays(d0, d1).filter { hd ->
            val s = hd.start(zone); s < endMs && s + HrDay.MINUTES * 60_000L > startMs
        }
        if (days.isEmpty()) return null
        val rest = restMap(cache, d0, d1)
        val total = IntArray(4)
        for (hd in days) {
            val dayStart = hd.start(zone)
            val m0 = ((startMs - dayStart) / 60_000L).coerceIn(0, HrDay.MINUTES.toLong()).toInt()
            val m1 = ((endMs - dayStart) / 60_000L).coerceIn(0, HrDay.MINUTES.toLong()).toInt()
            val z = HrZones.minutes(hd.bpm, hrMax, HrZones.restFor(hd.day, rest).first, m0, m1)
            for (i in 0..3) total[i] += z[i]
        }
        return total
    }

    /** Per hour of [day]: (hour start, avg, min, max) of the per-minute averages. */
    fun hrHourly(cache: CacheReads, day: LocalDate, zone: ZoneId): List<HourHr> {
        val hd = cache.hrDay(day) ?: return emptyList()
        // v4.1 fix: hours from the day's stored minute-0 instant, shown in the current zone
        val start = Instant.ofEpochMilli(hd.start(zone)).atZone(zone)
        val dayMin = minutesOfDay(hd, zone)
        val out = ArrayList<HourHr>()
        var h = 0
        while (true) {
            val hs = start.plusHours(h.toLong())
            if (h * 60 >= dayMin) break
            val m0 = ChronoUnit.MINUTES.between(start, hs).toInt()
            var sum = 0; var n = 0; var mn = Int.MAX_VALUE; var mx = 0
            for (m in m0 until (m0 + 60).coerceAtMost(HrDay.MINUTES)) {
                val v = hd.at(m)
                if (v == 0) continue
                sum += v; n++; if (v < mn) mn = v; if (v > mx) mx = v
            }
            if (n > 0) out += HourHr(hs.toLocalDateTime(), sum.toDouble() / n, mn.toDouble(), mx.toDouble())
            h++
        }
        return out
    }

    /** Intensity minutes per hour of [day]. */
    fun zoneHourly(cache: CacheReads, goals: Goals, day: LocalDate, zone: ZoneId): List<Pt> {
        val hd = cache.hrDay(day) ?: return emptyList()
        val hrMax = hrMax(cache, goals)
        val restHr = HrZones.restFor(day, restMap(cache, day, day)).first
        // intensity points per minute: fat burn 1, cardio / peak 2
        val pts = IntArray(hd.bpm.size) { m -> when (HrZones.zoneOf(hd.at(m), hrMax, restHr)) { HrZones.FAT_BURN -> 1; HrZones.CARDIO, HrZones.PEAK -> 2; else -> 0 } }
        // v4.1 fix: hours from the day's stored minute-0 instant, shown in the current zone
        val start = Instant.ofEpochMilli(hd.start(zone)).atZone(zone)
        val dayMin = minutesOfDay(hd, zone)
        val out = ArrayList<Pt>()
        var h = 0
        while (true) {
            val hs = start.plusHours(h.toLong())
            if (h * 60 >= dayMin) break
            val m0 = ChronoUnit.MINUTES.between(start, hs).toInt()
            var s = 0
            for (m in m0 until (m0 + 60).coerceAtMost(HrDay.MINUTES)) s += pts[m]
            out += Pt(hs.toLocalDateTime(), s.toDouble())
            h++
        }
        return out
    }

    /** Latest heart-rate minute in the cache (time, bpm). */
    fun hrLatest(cache: CacheReads, zone: ZoneId): Sample? {
        val hd = cache.latestHrDay() ?: return null
        for (m in HrDay.MINUTES - 1 downTo 0) {
            val v = hd.at(m)
            if (v > 0) return Sample(hd.start(zone) + m * 60_000L, v.toDouble()) // v4.1: stored minute-0 instant
        }
        return null
    }

    /** Minutes of [hd]'s day (23 / 24 / 25 h around DST, by the current zone's rules), at most the stored minutes. */
    private fun minutesOfDay(hd: HrDay, zone: ZoneId): Int =
        ChronoUnit.MINUTES.between(hd.day.atStartOfDay(zone), hd.day.plusDays(1).atStartOfDay(zone)).toInt().coerceAtMost(HrDay.MINUTES)
}

data class HourHr(val at: LocalDateTime, val avg: Double, val min: Double, val max: Double)

/** Reads for the UI, from the cache. Blocking — call from Dispatchers.IO. */
object Local {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private fun ms(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()

    /** De-duplicated workouts starting on [from]..[to] (inclusive), newest first. */
    fun workouts(cache: CacheReads, from: LocalDate, to: LocalDate): List<Workout> =
        WorkoutDedupe.of(cache.workouts(ms(from), ms(to.plusDays(1))))

    /** Workout minutes per day: sessions clipped to the day, overlapping time counted once. */
    fun exerciseMinutes(workouts: List<Workout>, from: LocalDate, to: LocalDate): Map<LocalDate, Int> {
        // v4: union of the (already de-duplicated) sessions, so two partly overlapping sessions never count twice.
        // V2-051: one sorted sweep (was O(days × sessions)); same rule as the V2 engine.
        return V2Reads.exerciseMinutes(workouts, from, to, zone)
    }

    /** Body points per day with any measurement, oldest first. */
    fun bodyPoints(cache: CacheReads, from: LocalDate, to: LocalDate): List<BodyPoint> {
        val w = cache.daily(M.WEIGHT, from, to).associateBy { it.day }
        val f = cache.daily(M.FAT_PCT, from, to).associateBy { it.day }
        val l = cache.daily(M.LEAN_KG, from, to).associateBy { it.day }
        return (w.keys + f.keys + l.keys).toSortedSet().map { d -> BodyPoint(d.toString(), w[d]?.value, f[d]?.value, l[d]?.value) }
    }

    /** Main sleep per wake-up day, oldest first. */
    fun sleepNights(cache: CacheReads, from: LocalDate, to: LocalDate): List<SleepNight> = V2Reads.sleepNights(cache, from, to)

    /** Latest height: Health Connect first, else the manually entered one. */
    fun heightM(cache: CacheReads): Double? =
        (cache.latest(M.HEIGHT) ?: cache.latest(M.HEIGHT_MANUAL))?.value?.takeIf { it in 0.5..2.6 }
}

/** Builds the dashboard [Snapshot] from the cache (no Health Connect calls). */
object Snapshots {
    fun build(cache: Cache, goals: Goals, manualHeightM: Double? = null): Snapshot {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val today = LocalDate.now(zone)
        val weekStart = today.minusDays(6)
        val steps = cache.daily(M.STEPS, weekStart, today).associateBy { it.day }
        val kcal = cache.daily(M.KCAL, weekStart, today).associateBy { it.day }
        val zm = cache.daily(M.ZONE_MIN, weekStart, today).associateBy { it.day }
        val workouts = Local.workouts(cache, today.minusDays(182), today)
        val ex = Local.exerciseMinutes(workouts, weekStart, today)
        val week = Sync.datesBetween(weekStart, today).map { d ->
            DayActivity(d.toString(), steps[d]?.value?.toLong() ?: 0L, kcal[d]?.value ?: 0.0, zm[d]?.value?.toInt() ?: 0, ex[d] ?: 0)
        }
        fun samples(metric: String, days: Long) = cache.daily(metric, today.minusDays(days), today).map { Sample(it.time, it.value) }
        fun latestWithin(metric: String, days: Long) = cache.latest(metric)?.takeIf { !it.day.isBefore(today.minusDays(days)) }?.let { Sample(it.time, it.value) }
        val sleep = Local.sleepNights(cache, today.minusDays(1), today).lastOrNull()?.info?.takeIf { it.end >= now - 36L * 3_600_000L }
        val sources = HashMap<String, String>()
        cache.latest(M.WEIGHT)?.source?.let { Sources.labels(it) }?.let { sources["body"] = it }
        cache.latestHrDay()?.source?.let { sources["vitals"] = Sources.label(it) }
        return Snapshot(
            updatedAt = cache.meta("last_sync")?.toLongOrNull() ?: now,
            week = week,
            workouts = workouts,
            body = Local.bodyPoints(cache, today.minusDays(400), today),
            heightM = Local.heightM(cache) ?: manualHeightM,
            hrLatest = Derive.hrLatest(cache, zone),
            restingHr = samples(M.RHR, 60),
            hrv = samples(M.HRV, 60),
            vo2 = latestWithin(M.VO2, 365),
            spo2 = latestWithin(M.SPO2, 30),
            resp = latestWithin(M.RESP, 30),
            sleep = sleep,
            sources = sources,
            hrMax = Derive.hrMax(cache, goals),
        )
    }
}
