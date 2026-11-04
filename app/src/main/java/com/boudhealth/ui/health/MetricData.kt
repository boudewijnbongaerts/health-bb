package com.boudhealth.ui.health

import android.content.Context
import com.boudhealth.data.Goals
import com.boudhealth.data.M
import com.boudhealth.data.Metric
import com.boudhealth.data.MetricEnv
import com.boudhealth.data.MetricReading
import com.boudhealth.data.Metrics
import com.boudhealth.data.Pt
import com.boudhealth.data.Range
import com.boudhealth.data.Window
import com.boudhealth.ui.components.Bar
import com.boudhealth.ui.components.ChartGrain
import com.boudhealth.ui.components.TLevel
import com.boudhealth.ui.components.TPoint
import com.boudhealth.ui.components.Delta
import com.boudhealth.ui.components.DeltaDir
import com.boudhealth.ui.components.DeltaKind
import com.boudhealth.ui.components.Fmt
import com.boudhealth.ui.components.deltaDir
import com.boudhealth.ui.components.deltaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/*
 * Data + hero logic of the single-metric template (no Compose here, so it is easy to reason about).
 */

/** Everything one metric page shows for one window. */
class DetailData(
    val window: Window,
    /** Chart points (per hour for 1D where supported, else bucketed like the registry does). */
    val series: List<Pt>,
    /** One value per day with data inside the window (up to today). */
    val periodDaily: List<Pt>,
    /** Daily values of the previous window (1D: the 7 days before). */
    val prevDaily: List<Pt>,
    val latest: MetricReading?,
    /** Daily values of the last ~400 days, oldest first (history list). */
    val history: List<Pt>,
    /** lean_fat: "lean_mass" / "fat_mass" daily values in the window and "fat_hist" for the history list. */
    val extra: Map<String, List<Pt>> = emptyMap(),
    /** v4 Q23: real readings + level-of-detail chart levels for the interactive chart (null for lean_fat). */
    val chart: ChartModel? = null,
)

suspend fun loadDetail(context: Context, m: Metric, w: Window): DetailData {
    val env = MetricEnv.load(context)
    return withContext(Dispatchers.IO) {
        val today = env.today
        val last = HealthPeriods.lastDay(w, today)
        val inPast = !w.start.isAfter(today)
        // weekly buckets (1Y lines) start on a Monday: widen the series window to the Monday on or before its start
        val sw = if (w.range == Range.YEAR) w.copy(start = w.start.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))) else w
        val series = if (m.id == "lean_fat" || !inPast) emptyList() else Metrics.series(env, m.id, sw)
        val pd = if (!inPast) emptyList() else Metrics.daily(env, m.id, w.start, last)
        val prev = if (w.range == Range.ALL) emptyList() else HealthPeriods.previous(w).let { (a, b) -> Metrics.daily(env, m.id, a, b) }
        val latest = Metrics.latest(env, m.id)
        val hist = Metrics.daily(env, m.id, today.minusDays(400), today)
        val extra = when {
            m.id == "lean_fat" && inPast -> mapOf("lean_mass" to Metrics.daily(env, "lean_mass", w.start, last))
            m.id == "hr" && w.range != Range.DAY -> com.boudhealth.data.V4.hrDailyRange(env, w.start, last).let { r ->
                mapOf("hr_min" to r.map { Pt(it.day, it.min) }, "hr_max" to r.map { Pt(it.day, it.max) })
            }
            // v6 I6: VO₂ change vs 3 months ago (latest − before)
            m.id == "vo2" -> com.boudhealth.data.V4.vo2Change(env)?.change?.let { mapOf("vo2_change" to listOf(Pt(today, it))) } ?: emptyMap()
            // v3 D7: weight overlays, bucketed exactly like the weight line (same Withings-first body source rules)
            // v6 I4: composition per weigh-in (lean kg, fat kg = weight − lean, fat %), as delivered
            m.id == "weight" && inPast -> com.boudhealth.data.V4.composition(env, w.start, last).let { c ->
                fun at(x: com.boudhealth.data.WeighIn) = java.time.Instant.ofEpochMilli(x.time).atZone(env.zone).toLocalDateTime()
                mapOf(
                    "comp_lean" to c.mapNotNull { x -> x.leanKg?.let { Pt(at(x), it) } },
                    "comp_fat" to c.mapNotNull { x -> x.fatKg?.let { Pt(at(x), it) } },
                    "comp_pct" to c.mapNotNull { x -> x.fatPct?.let { Pt(at(x), it) } },
                )
            }
            else -> emptyMap()
        }
        val chart = if (m.id == "lean_fat" || !inPast) null else buildChartModel(env, m, w, series, today)
        DetailData(w, series, pd, prev, latest, hist, extra, chart)
    }
}

/** Metrics that can be drawn over the weight line (v3 D7): % on a second axis, kg on the weight axis. */
val WEIGHT_OVERLAYS = listOf("body_fat", "lean_mass")

class HeroInfo(val label: String, val value: Double?, val context: String?, val deltas: List<Delta>)

private fun List<Pt>.avgOrNull(): Double? = if (isEmpty()) null else map { it.v }.average()

/** Unit used in delta pills ("pts" for %, nothing for VO₂). */
fun deltaUnit(m: Metric): String = when {
    m.id == "vo2" -> ""
    m.unit == "%" -> "pts"
    else -> shortUnit(m) ?: ""
}

private fun withUnit(n: String, unit: String) = if (unit.isEmpty()) n else "$n $unit"

/** Delta of a value against a reference, as a pill. [noise] keeps tiny changes neutral and arrow-less. */
fun deltaPill(change: Double, m: Metric, phrase: String, decimals: Int = m.decimals, noise: Double = 0.0, unit: String = deltaUnit(m)): Delta {
    val f = Math.pow(10.0, decimals.toDouble())
    val shown = Math.round(abs(change) * f) / f
    if (shown == 0.0) return Delta("Same as ${phrase.removePrefix("vs ")}", DeltaKind.Neutral, DeltaDir.None)
    return Delta("${withUnit(Fmt.num(shown, decimals), unit)} $phrase".trim(), deltaKind(change, m.higherIsBetter, noise), deltaDir(change, noise))
}

fun bandWord(v: Double, band: Pair<Double, Double>): String = when {
    v < band.first -> "below"
    v > band.second -> "above"
    else -> "within"
}

fun bandText(band: Pair<Double, Double>, decimals: Int = 0): String = "${Fmt.num(band.first, decimals)}–${Fmt.num(band.second, decimals)}"

/** Is a reading outside the normal band in the bad direction? (RHR above, HRV below). */
fun badOutside(m: Metric, v: Double, band: Pair<Double, Double>?): Boolean {
    if (band == null) return false
    return when (m.higherIsBetter) {
        false -> v > band.second
        true -> v < band.first
        null -> v < band.first || v > band.second
    }
}

/** Hero of the template: what the number is, what it means, and how it compares. */
fun heroFor(m: Metric, d: DetailData, goals: Goals, today: LocalDate = LocalDate.now()): HeroInfo {
    val w = d.window
    val current = w.offset == 0
    val r = d.latest
    val unit = deltaUnit(m)
    // v5: the hero shows the latest value, never an average
    val lastPt = d.periodDaily.lastOrNull()
    val latestV: Double? = if (current) r?.value ?: lastPt?.v else lastPt?.v
    val latestCtx: String? = if (current && r != null) Fmt.dayLabel(r.day, today) else lastPt?.let { Fmt.dayLabel(it.date, today) }
    val label = displayLabel(m)
    val goal = m.goal(goals)
    val periodName = HealthPeriods.label(w, today).first.replaceFirstChar { it.lowercase() }


    fun vsStart(v: Double?): List<Delta> {
        val first = d.periodDaily.firstOrNull() ?: return emptyList()
        if (v == null || d.periodDaily.size < 2) return emptyList()
        return listOf(deltaPill(v - first.v, m, HealthPeriods.agoPhrase(w, first.date)))
    }

    return when {
        m.id == "lean_fat" -> {
            val lean = d.extra["lean_mass"].orEmpty()
            val v = if (current) r?.parts?.get("lean_mass") ?: lean.lastOrNull()?.v else lean.lastOrNull()?.v
            HeroInfo("Lean mass", v, null, emptyList())
        }
        isTotal(m.id) -> {
            if (w.range == Range.DAY) {
                val v = d.periodDaily.firstOrNull()?.v ?: (if (current) r?.value else null) ?: 0.0
                val ctx = when {
                    goal == null -> Fmt.dayLabel(w.start, today)
                    v >= goal -> "Goal met · ${pct(v, goal)} of ${Fmt.num(goal)}"
                    else -> "${pct(v, goal)} of your ${Fmt.num(goal)} goal · ${Fmt.num(goal - v)} to go"
                }
                HeroInfo(label, v, ctx, emptyList())
            } else {
                val met = if (goal != null) d.periodDaily.count { it.v >= goal } else 0
                val ctx = listOfNotNull(lastPt?.let { Fmt.dayLabel(it.date, today) },
                    if (goal != null && d.periodDaily.isNotEmpty()) "goal met on $met of ${d.periodDaily.size} days" else null).joinToString(" · ")
                HeroInfo(label, lastPt?.v, ctx, emptyList())
            }
        }
        m.id == "sleep" -> {
            val ctx = if (current && r != null) {
                val st = r.parts["start"]?.toLong(); val e = r.parts["end"]?.toLong()
                val night = if (r.day == today) "Last night" else Fmt.dayLabel(r.day, today)
                if (st != null && e != null) "$night · ${Fmt.hm(st)}–${Fmt.hm(e)}" else night
            } else latestCtx
            HeroInfo(label, latestV, ctx, emptyList())
        }
        m.personalBand -> {
            if (current && r != null) {
                // V7-R4: facts only, no "normal" range text
                val ctx = Fmt.dayLabel(r.day, today)
                HeroInfo(label, r.value, ctx, emptyList())
            } else HeroInfo(label, latestV, latestCtx, emptyList())
        }
        m.id == "hr" -> {
            if (current && w.range == Range.DAY && r != null) {
                val mn = r.parts["min"]; val mx = r.parts["max"]
                val ctx = Fmt.timestamp(r.time, today) + if (mn != null && mx != null) " · ${Fmt.num(mn)}–${Fmt.num(mx)} bpm" else ""
                HeroInfo(label, r.value, ctx, emptyList())
            } else HeroInfo(label, latestV, latestCtx, emptyList())
        }
        m.id == "vo2" -> {
            val v = if (current) r?.value ?: d.periodDaily.lastOrNull()?.v else d.periodDaily.lastOrNull()?.v
            val ctx = v?.let { vo2Context(it, vo2Scale(goals.birthYear)) }
            // V7-S2: the change vs 3 months ago is shown with the large arrow under the hero (MetricDetailScreen)
            HeroInfo(label, v, ctx, emptyList())
        }
        m.id == "breathing" || m.id == "spo2" -> HeroInfo(label, latestV, latestCtx, emptyList())
        else -> {
            // measurements: weight, body fat, BMI, lean / fat mass, height, WHtR
            val v = if (current) r?.value ?: d.periodDaily.lastOrNull()?.v else d.periodDaily.lastOrNull()?.v
            val whenText = if (current && r != null) Fmt.rowWhen(r.time, today).replace(" · ", " ") else d.periodDaily.lastOrNull()?.let { "Last reading · ${Fmt.dayLabel(it.date, today)}" }
            val ctx = when {
                m.id == "bmi" -> whenText
                v != null && goal != null -> {
                    val gap = v - goal
                    val f = Math.pow(10.0, m.decimals.toDouble())
                    if (Math.round(abs(gap) * f) == 0L) "${whenText ?: ""} · on your goal".trimStart(' ', '·')
                    else "${whenText ?: ""} · ${withUnit(Fmt.num(abs(gap), m.decimals), shortUnit(m) ?: "")} to your goal of ${Fmt.num(goal, if (goal % 1.0 == 0.0) 0 else 1)}".trimStart(' ', '·', ' ')
                }
                else -> whenText
            }
            HeroInfo(label, v, ctx, vsStart(v))
        }
    }
}

private fun pctDelta(v: Double, ref: Double, m: Metric, phrase: String): Delta {
    if (ref <= 0) return Delta(phrase, DeltaKind.Neutral)
    val p = (v - ref) / ref * 100.0
    if (abs(p) < 1) return Delta("Same as ${phrase.removePrefix("vs ")}", DeltaKind.Neutral)
    return Delta("${Fmt.num(abs(p))}% $phrase", deltaKind(p, m.higherIsBetter, 3.0), deltaDir(p, 1.0))
}

/** Scrub date label for a chart point of this window. */
fun scrubDate(p: Pt, w: Window, today: LocalDate = LocalDate.now()): String {
    val loc = Locale.getDefault()
    return when (w.range) {
        Range.DAY -> "${DateTimeFormatter.ofPattern("HH:mm", loc).format(p.at)}–${DateTimeFormatter.ofPattern("HH:mm", loc).format(p.at.plusHours(1))}"
        Range.WEEK, Range.MONTH, Range.QUARTER -> Fmt.dayLabel(p.date, today)
        Range.YEAR -> "Week of ${Fmt.shortDate(p.date)}"
        Range.ALL -> DateTimeFormatter.ofPattern("MMM yyyy", loc).format(p.date)
    }
}

/**
 * Bar bucket for a window (v3 D12): hours for 1D, days for 1W / 1M, weeks for 3M, months for 1Y, and for "All"
 * months while the stored data spans ≤ 3 years, else years. Axis labels and tooltips follow the bucket.
 */
fun barBucket(d: DetailData, today: LocalDate = LocalDate.now()): Period = when (d.window.range) {
    Range.DAY, Range.WEEK, Range.MONTH -> Period.ofDays(1)
    Range.QUARTER -> Period.ofWeeks(1)
    Range.YEAR -> Period.ofMonths(1)
    Range.ALL -> {
        val first = d.periodDaily.firstOrNull()?.date ?: today
        if (java.time.temporal.ChronoUnit.MONTHS.between(first.withDayOfMonth(1), today.withDayOfMonth(1)) < 36) Period.ofMonths(1) else Period.ofYears(1)
    }
}

/** Tooltip date for a bar starting at [x] with [bucket]: day label, "Week of 3 Nov", "November 2026", "2025". */
fun barDate(x: LocalDateTime, bucket: Period, w: Window, today: LocalDate = LocalDate.now()): String {
    val loc = Locale.getDefault()
    return when {
        w.range == Range.DAY -> scrubDate(Pt(x, 0.0), w, today)
        bucket.years > 0 -> x.year.toString()
        bucket.months > 0 -> DateTimeFormatter.ofPattern("MMMM yyyy", loc).format(x)
        bucket.days >= 7 -> "Week of ${Fmt.shortDate(x.toLocalDate())}"
        else -> Fmt.dayLabel(x.toLocalDate(), today)
    }
}

/**
 * Bars of a window: every bucket from the window start (All: the first stored day) to its end, empty ones at 0,
 * future ones flagged. Daily totals become "daily average" per bucket (elapsed days only); sleep averages its nights.
 */
fun buildBars(d: DetailData, averageNights: Boolean = false, today: LocalDate = LocalDate.now()): List<Bar> {
    val w = d.window
    val loc = Locale.getDefault()
    if (w.range == Range.DAY) {
        val byHour = d.series.associate { it.at.hour to it.v }
        val now = LocalDateTime.now()
        return (0..23).map { h ->
            val t = w.start.atTime(h, 0)
            Bar(t, if (h % 6 == 0) "$h" else "", byHour[h] ?: 0.0, future = t.isAfter(now))
        }
    }
    val bucket = barBucket(d, today)
    val daily = d.periodDaily.associate { it.date to it.v }
    val first: LocalDate = when {
        w.range == Range.ALL -> {
            val f = d.periodDaily.firstOrNull()?.date ?: today
            if (bucket.years > 0) f.withDayOfYear(1) else f.withDayOfMonth(1)
        }
        bucket.days == 7 -> w.start.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        else -> w.start
    }
    val end = if (w.range == Range.ALL) today.plusDays(1) else w.end
    val starts = generateSequence(first) { it.plus(bucket) }.takeWhile { it.isBefore(end) }.toList()
    var prevMonth = -1
    return starts.map { s ->
        val e = s.plus(bucket)
        val elapsedEnd = minOf(e, today.plusDays(1))
        val days = generateSequence(s) { it.plusDays(1) }.takeWhile { it.isBefore(e) }.toList()
        val vals = days.mapNotNull { daily[it] }
        val value = when {
            vals.isEmpty() -> 0.0
            bucket.days == 1 && bucket.months == 0 -> vals.first()
            averageNights -> vals.average()
            // daily average over the bucket's days that are inside the window and not in the future
            else -> vals.sum() / java.time.temporal.ChronoUnit.DAYS.between(if (w.range == Range.ALL) s else maxOf(s, w.start), elapsedEnd).coerceAtLeast(1)
        }
        val label = when {
            w.range == Range.WEEK -> Fmt.dayLetter(s)
            w.range == Range.MONTH -> if (s.dayOfMonth in setOf(1, 8, 15, 22, 29)) s.dayOfMonth.toString() else ""
            bucket.days == 7 -> if (s.monthValue != prevMonth) DateTimeFormatter.ofPattern("MMM", loc).format(s).also { prevMonth = s.monthValue } else ""
            bucket.months > 0 && w.range == Range.YEAR -> s.month.getDisplayName(java.time.format.TextStyle.NARROW, loc)
            bucket.months > 0 -> when {
                starts.size <= 12 -> s.month.getDisplayName(java.time.format.TextStyle.NARROW, loc)
                s.monthValue == 1 || s == first -> DateTimeFormatter.ofPattern("MMM yy", loc).format(s)
                (s.monthValue - 1) % 3 == 0 && starts.size <= 18 -> DateTimeFormatter.ofPattern("MMM", loc).format(s)
                else -> ""
            }
            else -> if (starts.size > 8) "'" + (s.year % 100).toString().padStart(2, '0') else s.year.toString()
        }
        val containsToday = !today.isBefore(s) && today.isBefore(e)
        Bar(s.atStartOfDay(), label, value, today = containsToday && label.isNotEmpty(), future = s.isAfter(today))
    }
}

/** History subtitle per metric. */
fun historySubtitle(m: Metric, v: Double, band: Pair<Double, Double>?, goal: Double?): Pair<String?, Boolean> = when {
    // V7-R4: no "outside your normal" colouring (facts only)
    m.personalBand || m.id in setOf("breathing", "spo2", "sleep") -> "Overnight" to false
    isTotal(m.id) && goal != null -> (if (v >= goal) "Goal met" else "${pct(v, goal)} of goal") to false
    m.id in setOf("weight", "body_fat", "bmi", "lean_mass", "fat_mass", "lean_fat") -> "Weigh-in" to false
    m.id == "hr" -> null to false
    else -> null to false
}

// =====================================================================================================================
// v4 Q23: interactive chart model — real readings, level-of-detail buckets, trend, overlays
// =====================================================================================================================

/** Page / hero label; v4.1 R5 Apple Health terms: Total energy burned / Active energy / Resting energy. */
fun displayLabel(m: Metric): String = when (m.id) {
    "calories" -> "Calories burned"
    else -> metricLabel(m)
}

fun displayTitle(m: Metric): String = when (m.id) {
    "calories", "active_kcal", "rest_kcal" -> displayLabel(m)
    else -> metricTitle(m)
}

/**
 * One stored value as delivered: a weigh-in, a night, a day's total or average. [at] is the measurement time when the
 * source gives one ([timed]), else noon (lines) or the start of the day (bars). [extras]: weight's body fat % / fat mass /
 * lean mass of the same day. [estimated]: calculated by Health BB (P-DATA), labelled as such.
 */
class Reading(
    val at: LocalDateTime,
    val day: LocalDate,
    val v: Double,
    val source: String?,
    val estimated: Boolean,
    val timed: Boolean,
    val extras: Map<String, Double> = emptyMap(),
    /** Extras that are calculated (fat mass, lean mass from weight × body fat %). */
    val calculatedExtras: Set<String> = emptySet(),
)

/**
 * Everything the interactive chart of a window needs. [levels] go from fine to coarse: the first level is the readings
 * themselves (point i ↔ readings[i]); the next are week and month buckets of the same readings. 1D: one hourly level.
 */
class ChartModel(
    val readings: List<Reading>,
    /** The last reading before the window (delta of the first reading). */
    val before: Reading?,
    val levels: List<TLevel>,
    /** Weight only: overlay id → levels with the same grains as [levels]. */
    val overlays: Map<String, List<TLevel>>,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val hourly: Boolean,
    val bars: Boolean,
    /** The day's source of an hourly (1D) chart. */
    val daySource: String? = null,
)

/** Metrics whose readings are drawn as dots of the real measurements (sparse, one per weigh-in / estimate). */
fun isMeasurement(id: String): Boolean = id in setOf("weight", "body_fat", "fat_mass", "lean_mass", "bmi", "vo2", "height", "whtr")


/** What one reading is called: "weigh-in", "night", "estimate", "day". */
fun readingNoun(id: String, n: Int): String {
    val one = when {
        id in setOf("weight", "body_fat", "fat_mass", "lean_mass", "bmi") -> "weigh-in"
        id == "vo2" -> "estimate"
        id in setOf("sleep", "rhr", "hrv", "breathing", "spo2") -> "night"
        else -> "day"
    }
    return if (n == 1) one else one + "s"
}

/** Cache key behind a metric (for the source app and the measurement time of each day). */
private fun cacheKeyOf(id: String): String? = when (id) {
    "calories" -> M.KCAL
    "active_kcal" -> M.ACTIVE_KCAL
    "rest_kcal" -> M.REST_KCAL
    "steps" -> M.STEPS
    "zone_min" -> M.ZONE_MIN
    "zone_below", "zone_fatburn", "zone_cardio", "zone_peak" -> id // M.AZM keys = registry ids
    "hz1", "hz2", "hz3", "hz4", "hz5" -> id
    "weight", "fat_mass", "lean_mass", "bmi" -> M.WEIGHT
    "body_fat" -> M.FAT_PCT
    "rhr" -> M.RHR
    "hrv" -> M.HRV
    "hr" -> M.HR_AVG
    "hrv_sdnn" -> M.HRV_SDNN
    "vo2" -> M.VO2
    "breathing" -> M.RESP
    "spo2" -> M.SPO2
    "sleep" -> M.SLEEP_MIN
    else -> null
}

/** Values Health BB calculates from other values (shown with "calculated"). */
private val CALCULATED_IDS = setOf("fat_mass", "bmi", "zone_min", "zone_below", "zone_fatburn", "zone_cardio", "zone_peak", "hz1", "hz2", "hz3", "hz4", "hz5", "rest_kcal", "whtr")

private fun buildChartModel(env: MetricEnv, m: Metric, w: Window, series: List<Pt>, today: LocalDate): ChartModel {
    val bars = usesBars(m.id)
    val last = HealthPeriods.lastDay(w, today)
    val key = cacheKeyOf(m.id)
    val zone = env.zone

    // ---- 1D: the hourly series is all there is (Health Connect hours, not readings)
    if (w.range == Range.DAY) {
        val pts = series.map { TPoint(it.at, it.at.plusHours(1), it.v) }
        val src = key?.let { env.cache.daily(it, w.start, w.start).firstOrNull() }
        return ChartModel(
            readings = emptyList(), before = null,
            levels = listOf(TLevel(ChartGrain.HOUR, pts, bucketed = false)),
            overlays = emptyMap(),
            start = w.start.atStartOfDay(), end = w.start.plusDays(1).atStartOfDay(), hourly = true, bars = bars,
            daySource = src?.sourceLabel,
        )
    }

    val from = w.start
    val daily = Metrics.daily(env, m.id, from, last)
    val meta = key?.let { k -> env.cache.daily(k, from, last).associateBy { it.day } }.orEmpty()
    val timed = isMeasurement(m.id) && !bars

    fun timeOf(day: LocalDate): Pair<LocalDateTime, Boolean> {
        if (bars) return day.atStartOfDay() to false
        val t = meta[day]?.time
        if (timed && t != null && t > 0) {
            val at = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(t), zone)
            // an end-of-day or midnight stamp is a day value, not a measurement time
            val real = at.toLocalDate() == day && !(at.hour == 23 && at.minute == 59) && !(at.hour == 0 && at.minute == 0)
            if (real) return at to true
        }
        return day.atTime(12, 0) to false
    }

    // weight: extras of the same weigh-in (fat % as delivered; fat / lean mass calculated)
    val extraMaps: Map<String, Map<LocalDate, Double>> = if (m.id == "weight") WEIGHT_OVERLAYS.associateWith { id ->
        Metrics.daily(env, id, from, last).associate { it.date to it.v }
    } else emptyMap()

    // lean mass is calculated (weight − fat mass) on days with a body fat %, else it is the scale's own lean mass
    val leanCalcDays: Set<LocalDate> = if (m.id == "lean_mass") Metrics.daily(env, "fat_mass", from, last).map { it.date }.toHashSet() else emptySet()
    val readings = daily.map { p ->
        val (at, isTimed) = timeOf(p.date)
        val dv = meta[p.date]
        val extras = HashMap<String, Double>()
        val calc = HashSet<String>()
        extraMaps.forEach { (id, map) -> map[p.date]?.let { extras[id] = it } }
        if ("fat_mass" in extras) { calc += "fat_mass"; calc += "lean_mass" }
        Reading(
            at = at, day = p.date, v = p.v,
            source = dv?.sourceLabel,
            estimated = (dv?.estimated == true) || m.id in CALCULATED_IDS || p.date in leanCalcDays,
            timed = isTimed, extras = extras, calculatedExtras = calc,
        )
    }

    val before = if (w.range == Range.ALL) null
    else Metrics.daily(env, m.id, from.minusDays(400), from.minusDays(1)).lastOrNull()?.let { p ->
        Reading(p.date.atTime(12, 0), p.date, p.v, null, false, false)
    }

    val start = when (w.range) {
        Range.ALL -> (readings.firstOrNull()?.day ?: today.minusDays(30)).atStartOfDay()
        else -> w.start.atStartOfDay()
    }
    val end = when (w.range) {
        // long ranges stop at today; calendar week / month keep their full span (empty future days)
        Range.QUARTER, Range.YEAR, Range.ALL -> minOf(w.end, today.plusDays(1)).atStartOfDay()
        else -> w.end.atStartOfDay()
    }
    val domainStartDay = start.toLocalDate()

    val raw = readings.mapIndexed { i, r ->
        if (bars) TPoint(r.day.atStartOfDay(), r.day.plusDays(1).atStartOfDay(), r.v, ref = i) else TPoint(r.at, r.at, r.v, ref = i)
    }
    val totals = isTotal(m.id)
    val levels = listOf(
        TLevel(ChartGrain.DAY, raw, bucketed = false),
        TLevel(ChartGrain.WEEK, bucketize(raw, ChartGrain.WEEK, totals, domainStartDay, today), bucketed = true),
        TLevel(ChartGrain.MONTH, bucketize(raw, ChartGrain.MONTH, totals, domainStartDay, today), bucketed = true),
    )

    val overlays = if (m.id != "weight") emptyMap() else {
        val atByDay = readings.associate { it.day to it.at }
        WEIGHT_OVERLAYS.associateWith { id ->
            val map = extraMaps[id].orEmpty()
            val pts = map.entries.sortedBy { it.key }.map { (d, v) -> val at = atByDay[d] ?: d.atTime(12, 0); TPoint(at, at, v) }
            listOf(
                TLevel(ChartGrain.DAY, pts, bucketed = false),
                TLevel(ChartGrain.WEEK, bucketize(pts, ChartGrain.WEEK, false, domainStartDay, today), bucketed = true),
                TLevel(ChartGrain.MONTH, bucketize(pts, ChartGrain.MONTH, false, domainStartDay, today), bucketed = true),
            )
        }
    }

    return ChartModel(readings, before, levels, overlays, start, end, hourly = false, bars = bars)
}

/**
 * Week (Monday) or calendar-month buckets of [pts] (sorted). Lines: the average of the readings in the bucket.
 * Daily totals: the daily average over the bucket's elapsed days inside the chart (missing days count as 0, like the
 * registry's "daily average per week"). [TPoint.ref] = index of the bucket's first point; lo / hi = its range.
 */
fun bucketize(pts: List<TPoint>, grain: ChartGrain, totals: Boolean, domainStart: LocalDate, today: LocalDate): List<TPoint> {
    if (pts.isEmpty()) return emptyList()
    fun keyOf(d: LocalDate): LocalDate = when (grain) {
        ChartGrain.WEEK -> d.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        ChartGrain.MONTH -> d.withDayOfMonth(1)
        else -> d
    }
    fun next(d: LocalDate): LocalDate = when (grain) { ChartGrain.WEEK -> d.plusWeeks(1); ChartGrain.MONTH -> d.plusMonths(1); else -> d.plusDays(1) }
    val out = ArrayList<TPoint>()
    var i = 0
    while (i < pts.size) {
        val k = keyOf(pts[i].at.toLocalDate())
        var j = i
        var sum = 0.0; var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        while (j < pts.size && keyOf(pts[j].at.toLocalDate()) == k) {
            val v = pts[j].v
            sum += v; lo = minOf(lo, v); hi = maxOf(hi, v); j++
        }
        val n = j - i
        val e = next(k)
        val v = if (totals) {
            val a = maxOf(k, domainStart)
            val b = minOf(e, today.plusDays(1))
            sum / java.time.temporal.ChronoUnit.DAYS.between(a, b).coerceAtLeast(1)
        } else sum / n
        out += TPoint(k.atStartOfDay(), e.atStartOfDay(), v, n = n, ref = pts[i].ref.takeIf { it >= 0 } ?: i, lo = lo, hi = hi)
        i = j
    }
    return out
}

private val LOC: Locale get() = Locale.getDefault()

/** "Thu 3 Oct 2026" (+ " · 07:12" when the source gives a time). */
fun readingTitle(r: Reading): String =
    DateTimeFormatter.ofPattern("EEE d MMM yyyy", LOC).format(r.day) + if (r.timed) " · " + DateTimeFormatter.ofPattern("HH:mm", LOC).format(r.at) else ""

/** "Tue 1 Oct" (year added when not this year). */
fun shortDay(d: LocalDate, today: LocalDate = LocalDate.now()): String =
    DateTimeFormatter.ofPattern(if (d.year == today.year) "EEE d MMM" else "EEE d MMM yyyy", LOC).format(d)

/** Bucket title: "Week of 3 Mar 2025", "March 2025". */
fun bucketTitle(p: TPoint, grain: ChartGrain): String = when (grain) {
    ChartGrain.WEEK -> "Week of " + DateTimeFormatter.ofPattern("d MMM yyyy", LOC).format(p.at)
    ChartGrain.MONTH -> DateTimeFormatter.ofPattern("MMMM yyyy", LOC).format(p.at)
    ChartGrain.HOUR -> DateTimeFormatter.ofPattern("EEE d MMM yyyy · HH:mm", LOC).format(p.at)
    ChartGrain.DAY -> DateTimeFormatter.ofPattern("EEE d MMM yyyy", LOC).format(p.at)
}

/** One month of the readings list (newest first) with its average. */
class ReadingMonth(val month: java.time.YearMonth, val indices: List<Int>, val average: Double)

/** Readings inside [from]..[to] grouped by month, newest month first, newest reading first. */
fun readingMonths(readings: List<Reading>, from: LocalDate, to: LocalDate): List<ReadingMonth> {
    val idx = readings.indices.filter { !readings[it].day.isBefore(from) && !readings[it].day.isAfter(to) }.reversed()
    return idx.groupBy { java.time.YearMonth.from(readings[it].day) }.map { (ym, list) ->
        ReadingMonth(ym, list, list.map { readings[it].v }.average())
    }
}
