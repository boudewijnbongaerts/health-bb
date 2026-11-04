package com.boudhealth.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale


/** zoneMin is computed from the cached per-minute heart rate for every range (null only if nothing is cached). */
data class ActivityHistory(val steps: List<Pt>, val kcal: List<Pt>, val zoneMin: List<Pt>?, val exerciseMin: List<Pt>)
data class BodyHistory(val weight: List<Pt>, val fatPct: List<Pt>, val fatKg: List<Pt>, val leanKg: List<Pt>, val source: String?)
data class VitalsHistory(
    val restingHr: List<Pt>, val hrv: List<Pt>, val hrAvg: List<Pt>, val hrMin: List<Pt>, val hrMax: List<Pt>,
    val vo2: List<Pt>, val spo2: List<Pt>, val resp: List<Pt>, val sleep: List<SleepNight>,
)

/**
 * Data for the detail screens, read from the local [Cache] (filled by [Sync]): fast, the same numbers as the
 * dashboard, and no hidden 30-day limit per screen. Only the hourly steps/kcal of days older than two weeks are
 * read live from Health Connect (foreground only). Same de-duplication rules as the sync ([Sources]).
 */
class History(context: Context) {
    private val app = context.applicationContext
    private val cache = Cache.get(app)
    private val zone: ZoneId = ZoneId.systemDefault()

    private fun Window.last(): LocalDate = Sync.minDate(end.minusDays(1), LocalDate.now(zone))
    private fun daily(metric: String, w: Window, extraBefore: Long = 0): List<Pair<LocalDate, Double>> =
        cache.daily(metric, w.start.minusDays(extraBefore), w.last()).map { it.day to it.value }

    private fun hourStarts(w: Window): List<LocalDateTime> =
        (0 until 24).map { w.start.atStartOfDay().plusHours(it.toLong()) }.filter { !it.isAfter(LocalDateTime.now(zone)) }

    // ------------------------------------------------------------------ activity

    suspend fun activity(w: Window, sessions: List<Workout>, goals: Goals): ActivityHistory {
        fun ms(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()
        if (w.range.hourly) {
            val hours = hourStarts(w)
            val from = ms(w.start.atStartOfDay()); val to = ms(w.end.atStartOfDay())
            val steps = hourlyOrLive("Steps", M.STEPS, from, to)
            val kcal = hourlyOrLive("TotalCaloriesBurned", M.KCAL, from, to)
            val zm = Derive.zoneHourly(cache, goals, w.start, zone).filter { !it.at.isAfter(LocalDateTime.now(zone)) }
            val ex = hours.map { b ->
                val s = ms(b); val e = ms(b.plusHours(1))
                Pt(b, sessions.sumOf { (minOf(it.end, e) - maxOf(it.start, s)).coerceAtLeast(0L) } / 60_000.0)
            }
            return ActivityHistory(steps, kcal, zm, ex)
        }
        val exDaily = Local.exerciseMinutes(sessions, w.start, w.last()).map { (d, m) -> d to m.toDouble() }
        val stepsD = daily(M.STEPS, w)
        return ActivityHistory(
            steps = Buckets.of(stepsD, w, Agg.SUM_PER_DAY),
            kcal = Buckets.of(daily(M.KCAL, w), w, Agg.SUM_PER_DAY),
            zoneMin = Buckets.of(daily(M.ZONE_MIN, w), w, Agg.SUM_PER_DAY, fillZero = true),
            // "All": start at the first workout instead of 12 years of empty bars
            exerciseMin = Buckets.of(if (w.range == Range.ALL) exDaily.sortedBy { it.first }.dropWhile { it.second == 0.0 } else exDaily, w, Agg.SUM_PER_DAY, fillZero = true),
        )
    }

    /** V2 "Load hours" (V2-046): hourly steps ([M.STEPS]) or calories ([M.KCAL]) of [day]: cache, else live Health Connect. */
    suspend fun hoursOf(metric: String, day: LocalDate): List<Pt> {
        fun ms(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()
        return hourlyOrLive(if (metric == M.STEPS) "Steps" else "TotalCaloriesBurned", metric, ms(day.atStartOfDay()), ms(day.plusDays(1).atStartOfDay()))
    }

    /** Hourly steps / kcal from the cache (last ~2 weeks), else live from Health Connect. */
    private suspend fun hourlyOrLive(type: String, metric: String, fromMs: Long, toMs: Long): List<Pt> {
        val cached = cache.hourly(metric, fromMs, toMs)
        val recent = fromMs >= System.currentTimeMillis() - 14L * 86_400_000L
        if (cached.isNotEmpty() || recent) return cached.map { (h, v) -> Pt(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(h), zone), v) }
        return safe("history/hourly/$type", emptyList()) {
            val client = HealthConnectClient.getOrCreate(app)
            val f = TimeRangeFilter.between(java.time.Instant.ofEpochMilli(fromMs), java.time.Instant.ofEpochMilli(toMs))
            if (metric == M.STEPS) {
                client.aggregateGroupByDuration(AggregateGroupByDurationRequest(setOf(StepsRecord.COUNT_TOTAL), f, Duration.ofHours(1)))
                    .mapNotNull { b -> b.result[StepsRecord.COUNT_TOTAL]?.takeIf { b.result.dataOrigins.isNotEmpty() }?.let { Pt(LocalDateTime.ofInstant(b.startTime, zone), it.toDouble()) } }
            } else {
                client.aggregateGroupByDuration(AggregateGroupByDurationRequest(setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL), f, Duration.ofHours(1)))
                    .mapNotNull { b -> b.result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.takeIf { b.result.dataOrigins.isNotEmpty() }?.let { Pt(LocalDateTime.ofInstant(b.startTime, zone), it.inKilocalories) } }
            }
        }
    }

    // ------------------------------------------------------------------ workouts

    /** De-duplicated, enriched sessions of the window, newest first. [enrich] is kept for compatibility (always enriched). */
    @Suppress("UNUSED_PARAMETER")
    suspend fun workouts(w: Window, enrich: Boolean = true): List<Workout> = Local.workouts(cache, w.start, w.last())

    // ------------------------------------------------------------------ body (Withings first, per day)

    suspend fun body(w: Window): BodyHistory {
        // 14 extra days so the first weigh-in can borrow a fat % measured just before the window
        val from = w.start.minusDays(14)
        val wt = cache.daily(M.WEIGHT, from, w.last())
        val fat = cache.daily(M.FAT_PCT, from, w.last()).associate { it.day to it.value }.toSortedMap()
        val lean = cache.daily(M.LEAN_KG, from, w.last()).associate { it.day to it.value }
        val inWin: (LocalDate) -> Boolean = { !it.isBefore(w.start) && it.isBefore(w.end) }
        val fatKg = mutableListOf<Pt>(); val leanKg = mutableListOf<Pt>()
        for (dv in wt) {
            val d = dv.day; val kg = dv.value
            if (!inWin(d)) continue
            val fp = fat[d] ?: fat.headMap(d).entries.lastOrNull()?.takeIf { ChronoUnit.DAYS.between(it.key, d) <= 14 }?.value
            if (fp != null) {
                fatKg += Pt(d, kg * fp / 100.0); leanKg += Pt(d, kg - kg * fp / 100.0)
            } else lean[d]?.let { leanKg += Pt(d, it) }
        }
        val inWindow = wt.filter { inWin(it.day) }
        return BodyHistory(
            weight = inWindow.map { Pt(it.day, it.value) },
            fatPct = fat.filterKeys(inWin).map { Pt(it.key, it.value) },
            fatKg = fatKg, leanKg = leanKg,
            source = (inWindow.lastOrNull() ?: wt.lastOrNull())?.source?.let { Sources.labels(it) },
        )
    }

    // ------------------------------------------------------------------ vitals (Google Health / Fitbit first, per day)

    suspend fun vitals(w: Window): VitalsHistory {
        fun pts(metric: String) = daily(metric, w).filter { !it.first.isBefore(w.start) }.map { Pt(it.first, it.second) }
        val hrAvg: List<Pt>; val hrMin: List<Pt>; val hrMax: List<Pt>
        if (w.range.hourly) {
            val h = Derive.hrHourly(cache, w.start, zone)
            hrAvg = h.map { Pt(it.at, it.avg) }; hrMin = h.map { Pt(it.at, it.min) }; hrMax = h.map { Pt(it.at, it.max) }
        } else {
            hrAvg = Buckets.of(daily(M.HR_AVG, w), w, Agg.AVG)
            hrMin = Buckets.of(daily(M.HR_MIN, w), w, Agg.MIN)
            hrMax = Buckets.of(daily(M.HR_MAX, w), w, Agg.MAX)
        }
        val sleep = Local.sleepNights(cache, w.start, w.last())
        return VitalsHistory(pts(M.RHR), pts(M.HRV), hrAvg, hrMin, hrMax, pts(M.VO2), pts(M.SPO2), pts(M.RESP), sleep)
    }
}
