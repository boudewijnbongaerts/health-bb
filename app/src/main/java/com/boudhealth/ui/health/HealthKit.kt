package com.boudhealth.ui.health

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.boudhealth.data.Cache
import com.boudhealth.data.Goals
import com.boudhealth.data.M
import com.boudhealth.data.Metric
import com.boudhealth.data.MetricKind
import com.boudhealth.data.Metrics
import com.boudhealth.data.Pt
import com.boudhealth.data.Range
import com.boudhealth.data.Window
import com.boudhealth.ui.components.Fmt
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.Periods
import com.boudhealth.ui.theme.Cat
import com.boudhealth.ui.theme.LocalHB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.Year
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/*
 * Shared look, copy and small calculations for the Health area (metric template, Body, Waist, Tape, Health tab).
 * Everything here is UI-side: data comes from the backend API (Metrics, Store, Cache read-only).
 */

// =====================================================================================================================
// Look per metric
// =====================================================================================================================

/** Category colour of a metric (ring metrics follow the current palette). */
@Composable
fun metricColor(id: String): Color {
    val hb = LocalHB.current
    return when (id) {
        "calories" -> LocalHB.current.energyActive // v4: calories left the rings (Q4)
        "zone_min" -> hb.palette.intensity.first
        "exercise_min" -> hb.palette.workout.first
        "steps" -> hb.palette.stepsRing.first
        "body_fat", "fat_mass" -> Cat.fat
        "lean_mass", "lean_fat" -> Cat.lean
        "waist", "whtr" -> Cat.waist
        "rhr", "hr" -> Cat.heart
        "hrv" -> Cat.hrv
        "vo2" -> Cat.vo2
        "breathing" -> Cat.breathing
        "spo2" -> Cat.spo2
        "sleep" -> Cat.sleep
        "last_workout", "week_workouts", "streak" -> Cat.cardio
        else -> hb.colors.text // weight, bmi, height: the "weight = text" role
    }
}

/** Bar gradient (c0 → c1) for bar-chart metrics. */
@Composable
fun metricBarColors(id: String): Pair<Color, Color> {
    val hb = LocalHB.current
    return when (id) {
        "calories" -> LocalHB.current.energyActive to LocalHB.current.energyActive
        "zone_min" -> hb.palette.intensity
        "steps" -> hb.palette.stepsRing
        "sleep" -> Cat.sleep to Cat.light
        "exercise_min" -> hb.palette.workout
        else -> metricColor(id).let { it to it }
    }
}

/** One glyph per metric (T4's Glyph.forMetric; "height" keeps its person glyph). */
fun metricGlyph(id: String): Glyph = if (id == "height") Glyph.User else Glyph.forMetric(id)

/** Page title (longer than the registry label where the mock-ups say so). */
fun metricTitle(m: Metric): String = when (m.id) {
    "rhr" -> "Resting heart rate"
    "vo2" -> "VO₂ max"
    "hrv" -> "HRV"
    "breathing" -> "Breathing rate"
    "exercise_min" -> "Workout minutes"
    "calories" -> "Calories burned"
    "zone_min" -> "Heart points"
    "waist" -> "Waist circumference"
    "body_fat" -> "Body fat percentage"
    "lean_fat", "lean_mass" -> "Lean mass"
    "spo2" -> "Blood oxygen"
    "week_workouts" -> "Workouts"
    "streak" -> "Streak"
    else -> m.label
}

/** Short hero label. */
fun metricLabel(m: Metric): String = when (m.id) {
    "rhr" -> "Resting heart rate"
    "vo2" -> "VO₂ max"
    "exercise_min" -> "Workout minutes"
    "calories" -> "Calories burned"
    "zone_min" -> "Heart points"
    "waist" -> "Waist circumference"
    "body_fat" -> "Body fat percentage"
    "lean_fat", "lean_mass" -> "Lean mass"
    "breathing" -> "Breathing rate"
    else -> m.label
}

/** Display unit (registry units are sometimes ids, e.g. "VO2 max"). */
fun metricUnit(m: Metric): String = when (m.id) {
    "vo2" -> "ml/kg/min"
    "steps" -> "steps"
    "sleep" -> ""
    "height" -> "cm"
    else -> m.unit
}

/** Unit for stat rows / history: units longer than 5 characters are dropped (B_SPEC stat row rule). */
fun shortUnit(m: Metric): String? = metricUnit(m).takeIf { it.isNotEmpty() && it.length <= 5 }

/** Metrics whose daily value is a total for the day (bar charts, "daily average" heroes, goals per day). */
fun isTotal(id: String): Boolean = id in setOf("calories", "zone_min", "steps", "exercise_min")

/** Bar chart instead of a line. */
fun usesBars(id: String): Boolean = isTotal(id) || id == "sleep"

/** Value text for a metric (sleep in "7h 20m", height in cm). */
fun fmtValue(id: String, v: Double, decimals: Int): String = when (id) {
    "sleep" -> Fmt.duration((v * 60).toInt())
    "height" -> Fmt.num(v * 100, 0)
    else -> Fmt.num(v, decimals)
}

// =====================================================================================================================
// Ranges (B_SPEC §1 table), periods
// =====================================================================================================================

// v3 D12: every page offers the same ladder and always ends with "All", which starts at the earliest stored day.
// R6 item 1 (owner): every metric page has the same six periods 1D 1W 1M 3M 1Y All. 1D on a one-value-per-day page
// (DayView.ONE_VALUE_IDS) = that day's value + the 7 days ending on it; intraday pages keep their hourly 1D.
private val R_SIX = listOf(Range.DAY, Range.WEEK, Range.MONTH, Range.QUARTER, Range.YEAR, Range.ALL)

/** (ranges, default) for a metric's detail page: B_SPEC's table, limited to what the registry offers. Defaults unchanged. */
fun detailRanges(m: Metric): Pair<List<Range>, Range> {
    val (want, def) = when (m.id) {
        "vo2" -> R_SIX to Range.YEAR
        "rhr", "hrv", "sleep", "breathing", "spo2" -> R_SIX to Range.MONTH
        "weight", "body_fat", "fat_mass", "lean_mass", "lean_fat", "bmi" -> R_SIX to Range.YEAR
        "waist", "whtr" -> R_SIX to Range.YEAR
        "hr", "steps", "calories", "zone_min" -> R_SIX to Range.DAY
        "exercise_min" -> R_SIX to Range.MONTH
        else -> m.ranges to m.defaultRange
    }
    // the six periods of the one-value pages do not depend on the registry's (older, shorter) range lists
    val allowed = want.filter { it in m.ranges || com.boudhealth.data.DayView.isOneValue(m.id) }.ifEmpty { m.ranges }
    return allowed to (if (def in allowed) def else allowed.first())
}

/** Periods for the metric pages: calendar day / week / month / 3 months / calendar year (D8); detail pages keep 3M. */
object HealthPeriods {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val loc: Locale get() = Locale.ENGLISH

    fun window(range: Range, offset: Int, today: LocalDate = LocalDate.now(zone)): Window = when (range) {
        // D8: 1Y = the calendar year everywhere (sections and detail pages)
        Range.YEAR -> {
            val s0 = LocalDate.of(today.year - offset, 1, 1)
            Window(range, offset, s0, s0.plusYears(1))
        }
        else -> Periods.window(range, offset, today)
    }

    fun label(w: Window, today: LocalDate = LocalDate.now(zone)): Pair<String, String?> {
        val last = w.end.minusDays(1)
        val my = DateTimeFormatter.ofPattern("MMM yyyy", loc)
        return when (w.range) {
            Range.YEAR -> w.start.year.toString() to null
            Range.MONTH -> DateTimeFormatter.ofPattern(if (w.start.year == today.year) "MMMM" else "MMMM yyyy", loc).format(w.start) to
                Fmt.range(w.start, if (last.isAfter(today)) today else last)
            Range.QUARTER -> (if (w.offset == 0) "Last 3 months" else Periods.label(w, today).first) to Periods.label(w, today).let { (a, b) -> if (w.offset == 0) "$a ${b ?: ""}".trim() else b }
            else -> Periods.label(w, today)
        }
    }

    fun hasNext(w: Window): Boolean = w.range != Range.ALL && w.offset > 0

    /**
     * V2-086: the (range, offset) of a detail page opened from a section card at period [p] (B16, same period).
     * Uses the smallest range of [ranges] that holds the whole period (a day on a page without 1D → its week, …).
     */
    fun fromSection(p: com.boudhealth.data.SectionPeriod, ranges: List<Range>, today: LocalDate = LocalDate.now(zone)): Pair<Range, Int>? {
        val want = when (p) {
            is com.boudhealth.data.SectionPeriod.Day -> listOf(Range.DAY, Range.WEEK, Range.MONTH, Range.QUARTER, Range.YEAR, Range.ALL)
            is com.boudhealth.data.SectionPeriod.Week -> listOf(Range.WEEK, Range.MONTH, Range.QUARTER, Range.YEAR, Range.ALL)
            is com.boudhealth.data.SectionPeriod.Month -> listOf(Range.MONTH, Range.QUARTER, Range.YEAR, Range.ALL)
            is com.boudhealth.data.SectionPeriod.Year -> listOf(Range.YEAR, Range.ALL)
            is com.boudhealth.data.SectionPeriod.All -> listOf(Range.ALL)
        }
        val day = minOf(p.start, today)
        for (r in want) {
            if (r !in ranges) continue
            if (r == Range.ALL) return r to 0
            // the offset whose window holds the period's first day (also its last: calendar windows nest)
            var off = 0
            while (off < 600) {
                val w = window(r, off, today)
                if (!day.isBefore(w.start) && day.isBefore(w.end)) {
                    val lastIn = minOf(p.last, today)
                    if (lastIn.isBefore(w.end)) return r to off
                    break
                }
                if (day.isAfter(w.end)) break
                off++
            }
        }
        return null
    }

    /** Last day of the window that can have data (end exclusive, not after today). */
    fun lastDay(w: Window, today: LocalDate = LocalDate.now(zone)): LocalDate = w.end.minusDays(1).let { if (it.isAfter(today)) today else it }

    /** The window right before [w] (same length; for 1D: the previous 7 days, for "vs your 7-day avg"). */
    fun previous(w: Window): Pair<LocalDate, LocalDate> = when (w.range) {
        Range.DAY -> w.start.minusDays(7) to w.start.minusDays(1)
        else -> {
            val p = window(w.range, w.offset + 1)
            p.start to p.end.minusDays(1)
        }
    }
}

// =====================================================================================================================
// Goals per metric (B_SPEC §4 steps)
// =====================================================================================================================

class GoalSpec(
    val step: Double,
    val range: ClosedFloatingPointRange<Double>,
    val decimals: Int,
    /** Nullable goals can be removed ("Remove goal"); daily ring goals can only be reset to their default. */
    val removable: Boolean,
    val default: Double,
    val get: (Goals) -> Double?,
    val set: (Goals, Double?) -> Goals,
)

fun goalSpec(id: String): GoalSpec? = when (id) {
    "weight" -> GoalSpec(0.5, 40.0..200.0, 1, true, 85.0, { it.targetWeightKg }, { g, v -> g.copy(targetWeightKg = v) })
    "body_fat" -> GoalSpec(0.5, 3.0..50.0, 1, true, 15.0, { it.targetFatPct }, { g, v -> g.copy(targetFatPct = v) })
    "waist" -> GoalSpec(0.5, 50.0..150.0, 1, true, 90.0, { it.targetWaistCm }, { g, v -> g.copy(targetWaistCm = v) })
    // v4 Q9/Q15: intensity goal is weekly, 1-unit steps (long-press accelerates)
    "zone_min" -> GoalSpec(1.0, 10.0..1_000.0, 0, false, Goals().zoneMinWeek.toDouble(), { it.zoneMinWeek.toDouble() }, { g, v -> g.copy(zoneMinWeek = (v ?: Goals().zoneMinWeek.toDouble()).toInt()) })
    "exercise_min" -> GoalSpec(1.0, 5.0..300.0, 0, false, Goals().workoutMinDay.toDouble(), { it.workoutMinDay.toDouble() }, { g, v -> g.copy(workoutMinDay = (v ?: Goals().workoutMinDay.toDouble()).toInt()) })
    "steps" -> GoalSpec(1.0, 1_000.0..40_000.0, 0, false, Goals().steps.toDouble(), { it.steps.toDouble() }, { g, v -> g.copy(steps = (v ?: Goals().steps.toDouble()).toInt()) })
    else -> null
}

// =====================================================================================================================
// VO2 max categories (typical ranges for men; B_SPEC uses 40–49: 36 / 41 / 47)
// =====================================================================================================================

data class Vo2Scale(val cuts: List<Double>, val ageLabel: String?) {
    val names: List<String> get() = listOf("Low", "Fair", "Good", "High")
    fun zoneOf(v: Double): Int = cuts.count { v >= it }
    fun rangeText(i: Int): String = when (i) {
        0 -> "<${Fmt.num(cuts[0])}"
        3 -> "${Fmt.num(cuts[2])}+"
        else -> "${Fmt.num(cuts[i - 1])}–${Fmt.num(cuts[i])}"
    }
}

fun vo2Scale(birthYear: Int?): Vo2Scale {
    val age = birthYear?.let { Year.now().value - it }
    val cuts = when {
        age == null -> listOf(36.0, 41.0, 47.0)
        age < 30 -> listOf(40.0, 45.0, 51.0)
        age < 40 -> listOf(38.0, 43.0, 49.0)
        age < 50 -> listOf(36.0, 41.0, 47.0)
        age < 60 -> listOf(33.0, 38.0, 44.0)
        else -> listOf(30.0, 35.0, 41.0)
    }
    val label = age?.let { a -> val d = (a / 10) * 10; if (a >= 60) "60+" else "$d–${d + 9}" }
    return Vo2Scale(cuts, label)
}

/** "Above average for your age · 2 to High". */
fun vo2Context(v: Double, scale: Vo2Scale): String {
    val z = scale.zoneOf(v)
    // v5: the category only (facts, no coaching text)
    return scale.names.getOrNull(z) ?: ""
}

// =====================================================================================================================
// About texts ("what it is, why it matters, how it is measured"; max ~3 lines)
// =====================================================================================================================

// =====================================================================================================================
// Small calculations
// =====================================================================================================================

/** Trailing 7-day mean per point (weight trend line). */
fun trend7(points: List<Pt>): List<Pt> = points.mapIndexed { i, p ->
    val from = p.date.minusDays(6)
    var s = 0.0; var n = 0
    var j = i
    while (j >= 0 && !points[j].date.isBefore(from)) { s += points[j].v; n++; j-- }
    Pt(p.at, s / n)
}

/** Height from Health Connect only (manual height comes from prefs, so a fresh edit shows before the cache mirror). Read-only. */
suspend fun hcHeightM(context: Context): Double? = withContext(Dispatchers.IO) {
    runCatching { Cache.get(context.applicationContext).latest(M.HEIGHT)?.value?.takeIf { it in 0.5..2.6 } }.getOrNull()
}

/** Effective height in cm: Health Connect first, else the manual height (prefs), else null. */
fun effectiveHeightCm(hcM: Double?, manualCm: Double?): Double? = hcM?.let { it * 100.0 } ?: manualCm

/** Percent string for a goal fraction. */
fun pct(v: Double, goal: Double): String = if (goal <= 0) "–" else "${Fmt.num(v / goal * 100.0)}${com.boudhealth.ui.section.R3Texts.PCT_SP}%"
