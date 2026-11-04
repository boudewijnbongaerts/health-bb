package com.boudhealth.data

import android.content.Context
import com.boudhealth.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

enum class MetricCategory(val label: String) { ACTIVITY("Activity"), WORKOUTS("Workouts"), BODY("Body"), HEART("Heart"), SLEEP("Sleep") }

enum class MetricKind {
    /** Summed per day (steps, kcal, minutes). */
    DAILY_TOTAL,
    /** Averaged per day (heart rate, resting HR, HRV, breathing). */
    DAILY_AVERAGE,
    /** Sparse readings (weight, VO2 max, waist): one value per day that has one. */
    MEASUREMENT,
    /** Derived from workout sessions (last workout, workouts per week, streak). */
    WORKOUT,
    /** Several metrics in one tile (Today rings, Lean/fat); see [Metric.parts]. */
    COMPOSITE,
}

/**
 * The latest value of a metric for a tile.
 * [time] = when it was measured (for "2 h ago"), [day] = its local day, [source] = app label(s).
 * [parts] = sub-values (composite tiles, workout details: "kcal", "avgHr", "zoneMin", "deep", …).
 * [band] = personal normal band (mean ± 1 SD of the previous 60 days) where meaningful.
 */
data class MetricReading(
    val value: Double,
    val time: Long,
    val day: LocalDate,
    val source: String? = null,
    val parts: Map<String, Double> = emptyMap(),
    val goal: Double? = null,
    val band: Pair<Double, Double>? = null,
    val text: String? = null,
    /** v4 (P-DATA): calculated by Health BB, not delivered by an app — label it in the UI. */
    val estimated: Boolean = false,
)

/** Summary of a series for a detail page header. */
data class SeriesStats(val latest: Pt?, val average: Double?, val min: Double?, val max: Double?, val change: Double?, val count: Int)

/** Everything a metric needs to read the cache: built once per screen / render with [MetricEnv.load]. */
class MetricEnv(val cache: CacheReads, val goals: Goals, val zone: ZoneId = ZoneId.systemDefault()) {
    val today: LocalDate get() = LocalDate.now(zone)

    companion object {
        suspend fun load(context: Context): MetricEnv = withContext(Dispatchers.IO) {
            val app = context.applicationContext
            MetricEnv(Cache.get(app), (app as App).store.goalsNow())
        }
    }
}

/**
 * One metric the tile system can show. Everything is read from the cache: [daily] gives one value per day,
 * [latest] the newest reading, [hourly] (optional) per-hour points for a 1D view.
 *
 * [agg]: how days combine into week/month buckets. [bucket]: fixed bucket (e.g. weekly for workout counts).
 * [plausible]: values outside are ignored (sensor glitches). [axis]: sensible default chart range.
 */
class Metric(
    val id: String,
    val label: String,
    val unit: String,
    val category: MetricCategory,
    val kind: MetricKind,
    val agg: Agg,
    val decimals: Int,
    val plausible: ClosedFloatingPointRange<Double>,
    val axis: ClosedFloatingPointRange<Double>,
    /** true: up is good (HRV, steps); false: down is good (resting HR, body fat); null: neutral. */
    val higherIsBetter: Boolean?,
    val ranges: List<Range>,
    val defaultRange: Range,
    val description: String,
    val parts: List<String> = emptyList(),
    val bucket: Period? = null,
    val goal: (Goals) -> Double? = { null },
    val personalBand: Boolean = false,
    internal val daily: (MetricEnv, LocalDate, LocalDate) -> List<Pair<LocalDate, Double>>,
    internal val latest: (MetricEnv) -> MetricReading?,
    internal val hourly: ((MetricEnv, LocalDate) -> List<Pt>)? = null,
) {
    val supportsHourly: Boolean get() = hourly != null
}

/**
 * Registry of every metric a tile or a single-metric detail page can show. One API for all:
 *
 *  - [Metrics.all] / [Metrics.get] / [Metrics.TILES]: what exists (id, label, unit, category, ranges, goal…)
 *  - [Metrics.latest]: value for a tile
 *  - [Metrics.series]: points for a chart in a [Window] (bucketed like the detail screens; per hour for 1D)
 *  - [Metrics.partSeries]: one series per part, for composite tiles
 *  - [Metrics.stats]: average / min / max / change for a detail header
 *
 * Blocking variants take a [MetricEnv] (call on Dispatchers.IO, e.g. inside the renderer); suspend variants take a Context.
 */
object Metrics {

    /** v4.1 zone metric ids (index = HrZones.BELOW..PEAK). */
    val AZM_IDS = listOf("zone_below", "zone_fatburn", "zone_cardio", "zone_peak")
    private val ZONE_DESC = listOf(
        "Minutes with heart rate below 40 % of your heart-rate reserve.",
        "Minutes at 40–59 % of your heart-rate reserve (1 intensity minute each).",
        "Minutes at 60–84 % of your heart-rate reserve (2 intensity minutes each).",
        "Minutes at 85 % of your heart-rate reserve or more (2 intensity minutes each).",
    )

    private val SHORT = listOf(Range.WEEK, Range.MONTH, Range.QUARTER, Range.YEAR, Range.ALL)
    private val ALL_RANGES = Range.entries.toList()
    private val VITAL_RANGES = listOf(Range.WEEK, Range.MONTH, Range.QUARTER, Range.YEAR, Range.ALL)

    // ------------------------------------------------------------------ building blocks

    private fun fromCache(metric: String, plausible: ClosedFloatingPointRange<Double>): (MetricEnv, LocalDate, LocalDate) -> List<Pair<LocalDate, Double>> =
        { env, a, b -> env.cache.daily(metric, a, b).filter { it.value in plausible }.map { it.day to it.value } }

    private fun latestFromCache(metric: String, plausible: ClosedFloatingPointRange<Double>, maxAgeDays: Long? = null, scale: Double = 1.0): (MetricEnv) -> MetricReading? = { env ->
        env.cache.daily(metric, maxAgeDays?.let { env.today.minusDays(it) } ?: LocalDate.of(1900, 1, 1), env.today)
            .lastOrNull { it.value in plausible }
            ?.let { MetricReading(it.value * scale, it.time, it.day, Sources.labels(it.source), estimated = it.estimated) }
    }

    private fun band(env: MetricEnv, metric: String): Pair<Double, Double>? =
        Zones.normalBand(env.cache.daily(metric, env.today.minusDays(60), env.today).map { it.value })

    private fun hourlyFromCache(metric: String): (MetricEnv, LocalDate) -> List<Pt> = { env, day ->
        val a = day.atStartOfDay(env.zone).toInstant().toEpochMilli()
        val b = day.plusDays(1).atStartOfDay(env.zone).toInstant().toEpochMilli()
        env.cache.hourly(metric, a, b).map { (h, v) -> Pt(java.time.LocalDateTime.ofInstant(Instant.ofEpochMilli(h), env.zone), v) }
    }

    private fun dayEnd(env: MetricEnv, d: LocalDate): Long =
        minOf(System.currentTimeMillis(), d.plusDays(1).atStartOfDay(env.zone).toInstant().toEpochMilli() - 1)

    private fun weekStart(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun ringsPct(r: RingDay): Double =
        100.0 * (r.stepsProgress.coerceAtMost(1f) + r.workoutProgress.coerceAtMost(1f) + r.intensityProgress.coerceAtMost(1f)) / 3.0


    // ------------------------------------------------------------------ the registry

    val all: List<Metric> = listOf(
        // ---- activity
        Metric(
            "today_rings", "Rings", "%", MetricCategory.ACTIVITY, MetricKind.COMPOSITE, Agg.AVG, 0, 0.0..100.0, 0.0..100.0, true,
            ALL_RANGES.drop(1), Range.WEEK, "Workout minutes · Steps · Heart points: ring completion of the day, each capped at 100 %.",
            // v4.1 fix: the composite follows the CURRENT rings (V4.ringDays: steps · daily intensity · workout minutes),
            // no longer calories / zone minutes / steps.
            parts = listOf("exercise_min", "steps", "heart_week"),
            daily = { env, a, b ->
                V4.ringDays(env, a, b).filter { it.steps != null || it.intensity != null || it.workoutMin > 0 }
                    .map { r -> r.day to ringsPct(r) }
            },
            latest = { env ->
                val r = V4.ringDay(env, env.today)
                MetricReading(ringsPct(r), dayEnd(env, r.day), r.day,
                    parts = linkedMapOf("exercise_min" to r.workoutMin.toDouble(), "steps" to (r.steps ?: 0.0), "heart_week" to r.heartWeekPct.toDouble()), goal = 100.0)
            },
        ),
        Metric(
            "calories", "Calories burned", "kcal", MetricCategory.ACTIVITY, MetricKind.DAILY_TOTAL, Agg.SUM_PER_DAY, 0, 0.0..15_000.0, 0.0..4_000.0, true,
            ALL_RANGES, Range.WEEK, "Google Health's own total calories burned, up to now.",
            daily = fromCache(M.KCAL, 0.0..15_000.0),
            latest = latestFromCache(M.KCAL, 0.0..15_000.0, maxAgeDays = 0),
            hourly = hourlyFromCache(M.KCAL),
        ),
        Metric(
            "zone_min", "Heart points", "pts", MetricCategory.ACTIVITY, MetricKind.DAILY_TOTAL, Agg.SUM_PER_DAY, 0, 0.0..1_000.0, 0.0..120.0, true,
            ALL_RANGES, Range.WEEK, "Fat burn ×1, cardio and peak ×2 per minute (Fitbit AZM method, heart-rate reserve zones).",
            goal = { HrZones.dailyGoal(it.zoneMinWeek).toDouble() },
            daily = fromCache(M.ZONE_MIN, 0.0..1_000.0),
            latest = latestFromCache(M.ZONE_MIN, 0.0..1_000.0, maxAgeDays = 0),
            hourly = { env, day -> Derive.zoneHourly(env.cache, env.goals, day, env.zone) },
        ),
    ) + AZM_IDS.indices.map { i ->
        Metric(
            AZM_IDS[i], HrZones.NAMES[i] + " minutes", "min", MetricCategory.HEART, MetricKind.DAILY_TOTAL, Agg.SUM_PER_DAY, 0, 0.0..1_440.0, 0.0..120.0, null,
            ALL_RANGES, Range.WEEK, ZONE_DESC[i] + " Heart-rate reserve zones as Fitbit defines them; calculated.",
            daily = fromCache(M.AZM[i], 0.0..1_440.0),
            latest = latestFromCache(M.AZM[i], 0.0..1_440.0, maxAgeDays = 0),
        )
    } + (1..5).map { z ->
        // v4.0 compatibility shims (the UI catalog still lists hz1..hz5): hz1..hz3 = fat burn / cardio / peak, hz4–hz5 empty
        val i = if (z <= 3) z else -1
        Metric(
            "hz$z", if (i > 0) HrZones.NAMES[i] + " minutes" else "Zone $z (retired)", "min", MetricCategory.HEART, MetricKind.DAILY_TOTAL, Agg.SUM_PER_DAY, 0,
            0.0..1_440.0, 0.0..120.0, null, ALL_RANGES, Range.WEEK, "Deprecated id: use ${if (i > 0) AZM_IDS[i] else "the four AZM zones"}.",
            daily = if (i > 0) fromCache(M.AZM[i], 0.0..1_440.0) else { _, _, _ -> emptyList() },
            latest = if (i > 0) latestFromCache(M.AZM[i], 0.0..1_440.0, maxAgeDays = 0) else { _ -> null },
        )
    } + listOf(
        Metric(
            "steps", "Steps", "", MetricCategory.ACTIVITY, MetricKind.DAILY_TOTAL, Agg.SUM_PER_DAY, 0, 0.0..150_000.0, 0.0..20_000.0, true,
            ALL_RANGES, Range.WEEK, "Steps (Health Connect total, de-duplicated by its app priority).",
            goal = { it.steps.toDouble() },
            daily = fromCache(M.STEPS, 0.0..150_000.0),
            latest = latestFromCache(M.STEPS, 0.0..150_000.0, maxAgeDays = 0),
            hourly = hourlyFromCache(M.STEPS),
        ),
        Metric(
            "exercise_min", "Workout minutes", "min", MetricCategory.ACTIVITY, MetricKind.DAILY_TOTAL, Agg.SUM_PER_DAY, 0, 0.0..1_440.0, 0.0..120.0, true,
            ALL_RANGES.drop(1), Range.MONTH, "Length of recorded workouts per day (duplicates across apps removed).",
            goal = { it.workoutMinDay.toDouble() },
            daily = { env, a, b -> Local.exerciseMinutes(Local.workouts(env.cache, a.minusDays(1), b), a, b).filterValues { it > 0 }.toSortedMap().map { it.key to it.value.toDouble() } },
            latest = { env ->
                val d = env.today
                val m = Local.exerciseMinutes(Local.workouts(env.cache, d.minusDays(1), d), d, d)[d] ?: 0
                MetricReading(m.toDouble(), dayEnd(env, d), d)
            },
        ),
        // ---- workouts
        Metric(
            "last_workout", "Last workout", "min", MetricCategory.WORKOUTS, MetricKind.WORKOUT, Agg.TOTAL, 0, 0.0..1_440.0, 0.0..120.0, null,
            SHORT, Range.QUARTER, "Most recent session: duration, kcal (its own app first), average heart rate, zone minutes.",
            daily = { env, a, b ->
                Local.workouts(env.cache, a, b).groupBy { Instant.ofEpochMilli(it.start).atZone(env.zone).toLocalDate() }
                    .map { (d, ws) -> d to ws.sumOf { it.minutes }.toDouble() }.sortedBy { it.first }
            },
            latest = { env ->
                Local.workouts(env.cache, env.today.minusDays(400), env.today).firstOrNull()?.let { w ->
                    MetricReading(
                        w.minutes.toDouble(), w.end, Instant.ofEpochMilli(w.start).atZone(env.zone).toLocalDate(), w.source,
                        parts = buildMap {
                            w.kcal?.let { put("kcal", it) }; w.avgHr?.let { put("avgHr", it.toDouble()) }; w.zoneMin?.let { put("zoneMin", it.toDouble()) }
                            put("type", w.type.toDouble()); put("start", w.start.toDouble())
                        },
                        text = ExerciseTypes.name(w),
                    )
                }
            },
        ),
        Metric(
            // v6 "Workouts": ACTIVE DAYS (≥ 10 heart points, Mon–Sun) against the days goal; parts: sessions
            "week_workouts", "Workouts", "days", MetricCategory.WORKOUTS, MetricKind.WORKOUT, Agg.TOTAL, 0, 0.0..7.0, 0.0..7.0, true,
            SHORT, Range.QUARTER, "Active days per week (Mon–Sun). parts on latest: sessions.",
            bucket = Period.ofWeeks(1),
            goal = { it.workoutDaysWeek.toDouble() },
            daily = { env, a, b -> V4.activeDays(env, a, b).sorted().map { it to 1.0 } },
            latest = { env ->
                val w = V4.workoutsWeek(env)
                MetricReading(w.activeDays.toDouble(), dayEnd(env, env.today), env.today, goal = w.goal.toDouble(),
                    parts = mapOf("sessions" to w.sessions.toDouble()))
            },
        ),
        Metric(
            // v5: "Streak" = consecutive days with a workout, up to today (or yesterday while today has none yet)
            "streak", "Streak", "days", MetricCategory.WORKOUTS, MetricKind.WORKOUT, Agg.LAST, 0, 0.0..10_000.0, 0.0..30.0, true,
            listOf(Range.MONTH, Range.QUARTER, Range.YEAR), Range.MONTH, "Consecutive days with a workout of at least 20 minutes.",
            daily = { env, a, b ->
                val days = V2.engine(env).daily("streak", ALL_START, b).keys
                SyncLogic.datesBetween(a, b).mapNotNull { d -> var n = 0; var x = d; while (x in days) { n++; x = x.minusDays(1) }; if (n > 0) d to n.toDouble() else null }
            },
            latest = { env -> MetricReading(V4.streakDays(env).toDouble(), dayEnd(env, env.today), env.today) },
        ),
        // ---- body
        Metric(
            "weight", "Weight", "kg", MetricCategory.BODY, MetricKind.MEASUREMENT, Agg.AVG, 1, 25.0..350.0, 50.0..120.0, false,
            SHORT, Range.YEAR, "Last weigh-in of each day (Withings first, then Google Health, then Renpho).",
            goal = { it.targetWeightKg },
            daily = fromCache(M.WEIGHT, 25.0..350.0),
            latest = latestFromCache(M.WEIGHT, 25.0..350.0),
        ),
        Metric(
            "body_fat", "Body fat percentage", "%", MetricCategory.BODY, MetricKind.MEASUREMENT, Agg.AVG, 1, Plausible.FAT_PCT, 10.0..35.0, false,
            SHORT, Range.YEAR, "Body fat percentage from the scale. V2 (G0): impossible readings (< 5 %) are hidden.",
            goal = { it.targetFatPct },
            daily = fromCache(M.FAT_PCT, Plausible.FAT_PCT),
            latest = latestFromCache(M.FAT_PCT, Plausible.FAT_PCT),
        ),
        Metric(
            // v6: fat mass = weight − lean mass of the same weigh-in (both from the scale)
            "fat_mass", "Fat mass", "kg", MetricCategory.BODY, MetricKind.MEASUREMENT, Agg.AVG, 1, 1.0..250.0, 5.0..40.0, false,
            SHORT, Range.YEAR, "Weight − lean mass of the same weigh-in.",
            daily = { env, a, b -> V4.composition(env, a, b).mapNotNull { w -> w.fatKg?.let { w.day to it } } },
            latest = { env ->
                V4.composition(env, env.today.minusDays(400), env.today).lastOrNull { it.fatKg != null }?.let { MetricReading(it.fatKg!!, it.time, it.day) }
            },
        ),
        Metric(
            "lean_mass", "Lean mass", "kg", MetricCategory.BODY, MetricKind.MEASUREMENT, Agg.AVG, 1, 15.0..250.0, 40.0..90.0, true,
            SHORT, Range.YEAR, "Lean mass as the scale delivers it (Withings first).",
            // V2 (G0): lean mass of a weigh-in whose fat % is impossible (lean ≥ 95 % of weight) is hidden too
            daily = { env, a, b -> V4.composition(env, a, b).mapNotNull { w -> w.leanKg?.let { w.day to it } } },
            latest = { env -> V4.composition(env, env.today.minusDays(400), env.today).lastOrNull { it.leanKg != null }?.let { MetricReading(it.leanKg!!, it.time, it.day) } },
        ),
        Metric(
            // v5: kept as an alias id for stored layouts; lean mass as delivered, part body_fat (%) as delivered. No fat-mass kg.
            "lean_fat", "Lean mass", "kg", MetricCategory.BODY, MetricKind.COMPOSITE, Agg.AVG, 1, 15.0..250.0, 40.0..90.0, true,
            SHORT, Range.YEAR, "Lean mass as delivered; parts: lean_mass, body_fat.",
            parts = listOf("lean_mass", "body_fat"),
            daily = fromCache(M.LEAN_KG, 15.0..250.0),
            latest = { env ->
                latestFromCache(M.LEAN_KG, 15.0..250.0)(env)?.let { r ->
                    val f = env.cache.daily(M.FAT_PCT, r.day, r.day).firstOrNull()?.value?.takeIf { it in Plausible.FAT_PCT }
                    r.copy(parts = buildMap { put("lean_mass", r.value); f?.let { put("body_fat", it) } })
                }
            },
        ),
        Metric(
            "waist", "Waist circumference", "cm", MetricCategory.BODY, MetricKind.MEASUREMENT, Agg.AVG, 1, 40.0..200.0, 70.0..120.0, false,
            SHORT, Range.YEAR, "Entered in the app or read from the Renpho tape. parts: whtr (waist-to-height ratio) when height is known.",
            goal = { it.targetWaistCm },
            daily = fromCache(M.WAIST, 40.0..200.0),
            latest = { env ->
                env.cache.latest(M.WAIST)?.let { w ->
                    val h = Local.heightM(env.cache)
                    MetricReading(w.value, w.time, w.day, "Manual", parts = if (h != null) mapOf("whtr" to w.value / 100.0 / h) else emptyMap())
                }
            },
        ),
        Metric(
            "whtr", "Waist-to-height", "", MetricCategory.BODY, MetricKind.MEASUREMENT, Agg.AVG, 2, 0.3..1.2, 0.4..0.7, false,
            SHORT, Range.YEAR, "Waist ÷ height: < 0.5 healthy, 0.5–0.6 increased, ≥ 0.6 high (NICE).",
            daily = { env, a, b -> Local.heightM(env.cache)?.let { h -> env.cache.daily(M.WAIST, a, b).map { it.day to it.value / 100.0 / h } } ?: emptyList() },
            latest = { env ->
                val h = Local.heightM(env.cache)
                val w = env.cache.latest(M.WAIST)
                if (h == null || w == null) null else MetricReading(w.value / 100.0 / h, w.time, w.day, "Manual")
            },
        ),
        Metric(
            "bmi", "BMI", "", MetricCategory.BODY, MetricKind.MEASUREMENT, Agg.AVG, 1, 10.0..80.0, 18.0..35.0, false,
            SHORT, Range.YEAR, "Weight ÷ height² (height from Health Connect, else entered in the app).",
            daily = { env, a, b -> Local.heightM(env.cache)?.let { h -> env.cache.daily(M.WEIGHT, a, b).filter { it.value in 25.0..350.0 }.map { it.day to it.value / (h * h) } } ?: emptyList() },
            latest = { env ->
                val h = Local.heightM(env.cache)
                val w = env.cache.latest(M.WEIGHT)
                if (h == null || w == null) null else MetricReading(w.value / (h * h), w.time, w.day, Sources.labels(w.source))
            },
        ),
        Metric(
            "height", "Height", "m", MetricCategory.BODY, MetricKind.MEASUREMENT, Agg.LAST, 2, 0.5..2.6, 1.4..2.1, null,
            listOf(Range.YEAR, Range.ALL), Range.ALL, "From Health Connect, else entered in the app.",
            daily = { env, a, b -> (env.cache.daily(M.HEIGHT, a, b).ifEmpty { env.cache.daily(M.HEIGHT_MANUAL, a, b) }).map { it.day to it.value } },
            latest = { env -> (env.cache.latest(M.HEIGHT) ?: env.cache.latest(M.HEIGHT_MANUAL))?.let { MetricReading(it.value, it.time, it.day, Sources.labels(it.source)) } },
        ),
        // ---- heart
        Metric(
            "rhr", "Resting heart rate", "bpm", MetricCategory.HEART, MetricKind.DAILY_AVERAGE, Agg.AVG, 0, 25.0..140.0, 40.0..80.0, false,
            VITAL_RANGES, Range.MONTH, "Resting heart rate per day (Google Health first). Band = your normal (60 days).",
            personalBand = true,
            daily = fromCache(M.RHR, 25.0..140.0),
            latest = { env -> latestFromCache(M.RHR, 25.0..140.0)(env)?.copy(band = band(env, M.RHR)) },
        ),
        Metric(
            "hrv", "HRV", "ms", MetricCategory.HEART, MetricKind.DAILY_AVERAGE, Agg.AVG, 0, 1.0..300.0, 10.0..120.0, true,
            VITAL_RANGES, Range.MONTH, "Heart-rate variability (RMSSD) per night. Higher than your usual = well recovered.",
            personalBand = true,
            daily = fromCache(M.HRV, 1.0..300.0),
            latest = { env -> latestFromCache(M.HRV, 1.0..300.0)(env)?.copy(band = band(env, M.HRV)) },
        ),
        Metric(
            "hr", "Heart rate", "bpm", MetricCategory.HEART, MetricKind.DAILY_AVERAGE, Agg.AVG, 0, Plausible.HR_BPM, 40.0..180.0, null,
            ALL_RANGES, Range.DAY, "Latest heart rate; series = daily average (per hour for 1D). Daily min–max: V4.hrDailyRange.",
            daily = fromCache(M.HR_AVG, Plausible.HR_BPM),
            latest = { env ->
                Derive.hrLatest(env.cache, env.zone)?.let { s ->
                    val d = Instant.ofEpochMilli(s.time).atZone(env.zone).toLocalDate()
                    val mn = env.cache.daily(M.HR_MIN, d, d).firstOrNull()?.value?.takeIf { it in Plausible.HR_BPM }
                    val mx = env.cache.daily(M.HR_MAX, d, d).firstOrNull()?.value?.takeIf { it in Plausible.HR_BPM }
                    MetricReading(s.value, s.time, d, env.cache.hrDay(d)?.source?.let { Sources.label(it) },
                        parts = buildMap { mn?.let { put("min", it) }; mx?.let { put("max", it) } })
                }
            },
            hourly = { env, day -> Derive.hrHourly(env.cache, day, env.zone).map { Pt(it.at, it.avg) } },
        ),
        Metric(
            "vo2", "VO₂ max", "ml/kg/min", MetricCategory.HEART, MetricKind.MEASUREMENT, Agg.AVG, 1, 10.0..95.0, 30.0..60.0, true,
            VITAL_RANGES, Range.YEAR, "VO₂ max from any app in Health Connect, Google Health first.",
            daily = fromCache(M.VO2, 10.0..95.0),
            latest = latestFromCache(M.VO2, 10.0..95.0, maxAgeDays = 365),
        ),
        Metric(
            "breathing", "Breathing rate", "/min", MetricCategory.HEART, MetricKind.DAILY_AVERAGE, Agg.AVG, 1, 4.0..40.0, 10.0..20.0, null,
            VITAL_RANGES, Range.MONTH, "Breathing rate during sleep.",
            daily = fromCache(M.RESP, 4.0..40.0),
            latest = latestFromCache(M.RESP, 4.0..40.0, maxAgeDays = 30),
        ),
        Metric(
            "spo2", "Blood oxygen", "%", MetricCategory.HEART, MetricKind.DAILY_AVERAGE, Agg.AVG, 0, 70.0..100.0, 85.0..100.0, true,
            VITAL_RANGES, Range.MONTH, "SpO2, only if an app writes it (Google Health doesn't share it).",
            daily = fromCache(M.SPO2, 70.0..100.0),
            latest = latestFromCache(M.SPO2, 70.0..100.0, maxAgeDays = 30),
        ),
        // ---- sleep
        Metric(
            "sleep", "Sleep", "h", MetricCategory.SLEEP, MetricKind.DAILY_TOTAL, Agg.AVG, 1, 0.0..24.0, 4.0..10.0, true,
            VITAL_RANGES, Range.MONTH, "Time asleep in the main sleep, by wake-up day. parts on latest: deep, rem, light, asleep (no stages), awake (minutes), start, end.",
            daily = { env, a, b -> env.cache.daily(M.SLEEP_MIN, a, b).map { it.day to it.value / 60.0 } },
            latest = { env ->
                Local.sleepNights(env.cache, env.today.minusDays(30), env.today).lastOrNull()?.let { n ->
                    val i = n.info
                    MetricReading(i.asleepMin / 60.0, i.end, n.date, env.cache.daily(M.SLEEP_MIN, n.date, n.date).firstOrNull()?.source?.let { Sources.labels(it) },
                        parts = mapOf("deep" to i.deepMin.toDouble(), "rem" to i.remMin.toDouble(), "light" to i.lightMin.toDouble(),
                            "asleep" to i.asleepUnspecifiedMin.toDouble(), "awake" to i.awakeMin.toDouble(), "start" to i.start.toDouble(), "end" to i.end.toDouble()))
                }
            },
        ),
    )

    private val byId: Map<String, Metric> = all.associateBy { it.id }

    /** Tile ids the owner listed, in a sensible default order. */
    val TILES: List<String> = listOf(
        "today_rings", "calories", "zone_min", "steps", "last_workout", "week_workouts", "streak",
        "weight", "body_fat", "lean_fat", "waist", "bmi", "rhr", "hrv", "hr", "vo2", "breathing", "spo2", "sleep",
    )

    fun get(id: String): Metric = byId[id] ?: error("unknown metric $id")
    fun find(id: String): Metric? = byId[id]
    fun byCategory(): Map<MetricCategory, List<Metric>> = all.groupBy { it.category }

    // ------------------------------------------------------------------ blocking API (Dispatchers.IO)

    fun latest(env: MetricEnv, id: String): MetricReading? {
        val m = find(id) ?: return null // v5: removed ids (fat_mass, active_kcal, rest_kcal, hrv_sdnn) → no data
        return safe("metric/$id/latest", null) { m.latest(env)?.let { r -> if (r.goal == null) r.copy(goal = m.goal(env.goals)) else r } }
    }

    /** One point per day with data in [from]..[to]. */
    fun daily(env: MetricEnv, id: String, from: LocalDate, to: LocalDate): List<Pt> =
        safe("metric/$id/daily", emptyList()) { (find(id) ?: return@safe emptyList()).daily(env, from, to).map { Pt(it.first, it.second) } }

    /** Chart points for a window: per hour for 1D (when supported), else per day/week/month bucket with the metric's [Agg]. */
    fun series(env: MetricEnv, id: String, w: Window): List<Pt> = safe("metric/$id/series", emptyList()) {
        val m = find(id) ?: return@safe emptyList()
        val h = m.hourly
        if (w.range.hourly && h != null) return@safe h(env, w.start)
        val last = Sync.minDate(w.end.minusDays(1), env.today)
        val days = m.daily(env, w.start, last)
        val bucket = m.bucket?.let { b -> if (w.range == Range.ALL) Period.ofMonths(1) else b } ?: w.range.bucket
        val agg = if (m.bucket != null && w.range == Range.ALL && m.agg == Agg.TOTAL) Agg.TOTAL else m.agg
        Buckets.of(days, w, agg, bucket)
    }

    /** For composite metrics: one series per part (part id → points); for others: just its own series. */
    fun partSeries(env: MetricEnv, id: String, w: Window): Map<String, List<Pt>> {
        val m = find(id) ?: return emptyMap()
        return if (m.parts.isEmpty()) mapOf(id to series(env, id, w)) else m.parts.associateWith { series(env, it, w) }
    }

    fun stats(points: List<Pt>): SeriesStats =
        if (points.isEmpty()) SeriesStats(null, null, null, null, null, 0)
        else SeriesStats(points.last(), points.map { it.v }.average(), points.minOf { it.v }, points.maxOf { it.v },
            if (points.size >= 2) points.last().v - points.first().v else null, points.size)

    // ------------------------------------------------------------------ suspend API

    suspend fun latest(context: Context, id: String): MetricReading? { val env = MetricEnv.load(context); return withContext(Dispatchers.IO) { latest(env, id) } }
    suspend fun series(context: Context, id: String, w: Window): List<Pt> { val env = MetricEnv.load(context); return withContext(Dispatchers.IO) { series(env, id, w) } }
    suspend fun partSeries(context: Context, id: String, w: Window): Map<String, List<Pt>> { val env = MetricEnv.load(context); return withContext(Dispatchers.IO) { partSeries(env, id, w) } }

    /** Latest values for many tiles at once (one env, one IO hop). */
    suspend fun latestAll(context: Context, ids: List<String> = TILES): Map<String, MetricReading?> {
        val env = MetricEnv.load(context)
        return withContext(Dispatchers.IO) { ids.associateWith { latest(env, it) } }
    }
}
