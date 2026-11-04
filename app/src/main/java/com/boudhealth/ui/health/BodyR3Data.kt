package com.boudhealth.ui.health

import com.boudhealth.data.Chord
import com.boudhealth.data.Extreme
import com.boudhealth.data.R3Math
import com.boudhealth.data.ReadingBasis
import com.boudhealth.data.ReadingChange
import com.boudhealth.data.SectionPeriod
import com.boudhealth.data.TrendDir
import com.boudhealth.data.WeekLow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

// =====================================================================================================================
// Body tab R3 data (IMPL_PLAN §3B, port of out/body/src/body_data.py + the Axis / comp_rows / table_rows parts of
// body_r3.py). PURE Kotlin (java.time + com.boudhealth.data only): no Compose, no strings, so tools/unit can test it
// (UNIT_DATA="../ui/health/BodyR3Data.kt"). The UI files turn these facts into R3Texts strings.
// Every number is a reading, a difference of two readings, a lowest / highest or a count.
// =====================================================================================================================

/** Body metric ids (V2 ids; fat mass is derived: weight − lean). */
object BodyIds {
    const val WEIGHT = "weight"
    const val FAT_PCT = "body_fat"
    const val LEAN = "lean_mass"
    const val FAT_MASS = "fat_mass"
    const val WAIST = "waist"
    const val BMI = "bmi"
}

/** Display rules per metric (IMPL_PLAN §3B table): decimals, flat threshold, good direction (lean up = good). */
data class BodyRule(val id: String, val decimals: Int, val flat: Double, val higherIsBetter: Boolean)

object BodyRules {
    val ALL: Map<String, BodyRule> = listOf(
        BodyRule(BodyIds.WEIGHT, 1, 0.1, false),
        BodyRule(BodyIds.FAT_PCT, 1, 0.1, false),
        BodyRule(BodyIds.LEAN, 1, 0.1, true),
        BodyRule(BodyIds.FAT_MASS, 1, 0.1, false),
        BodyRule(BodyIds.WAIST, 1, 0.5, false),
        BodyRule(BodyIds.BMI, 1, 0.1, false),
    ).associateBy { it.id }
    fun of(id: String): BodyRule = ALL[id] ?: BodyRule(id, 1, 0.1, false)
}

/**
 * The Body histories (first data day..today, plausible only, from R3.history). [fatMass] = weight − lean of the same
 * weigh-in, both rounded to the shown decimal first, so lean + fat mass = weight exactly as displayed.
 */
class BodySeries(
    val weight: Map<LocalDate, Double>,
    val fatPct: Map<LocalDate, Double>,
    val lean: Map<LocalDate, Double>,
    val waist: Map<LocalDate, Double>,
    val bmi: Map<LocalDate, Double>,
) {
    val fatMass: Map<LocalDate, Double> = lean.mapNotNull { (d, l) ->
        weight[d]?.let { w -> d to R3Math.round(R3Math.round(w, 1) - R3Math.round(l, 1), 1) }
    }.toMap()

    fun of(id: String): Map<LocalDate, Double> = when (id) {
        BodyIds.WEIGHT -> weight; BodyIds.FAT_PCT -> fatPct; BodyIds.LEAN -> lean; BodyIds.FAT_MASS -> fatMass
        BodyIds.WAIST -> waist; BodyIds.BMI -> bmi; else -> emptyMap()
    }

    /** "All {n}" on the Measurements card = number of weigh-ins. */
    val weighIns: Int get() = weight.size
    val isEmpty: Boolean get() = weight.isEmpty() && waist.isEmpty() && fatPct.isEmpty()
    fun first(id: String): LocalDate? = of(id).keys.minOrNull()
    /** First data month of the tab (All axis start). */
    val firstDay: LocalDate? get() = listOfNotNull(first(BodyIds.WEIGHT), first(BodyIds.WAIST), first(BodyIds.FAT_PCT)).minOrNull()
}

/** The period the tab shows plus the selected day. Day mode = 1D or a selected day (cards and Trends take 1D content). */
class BodyScope(val period: SectionPeriod, val selected: LocalDate?, val today: LocalDate) {
    val dayMode: Boolean = period is SectionPeriod.Day || selected != null
    /** The day of day mode. */
    val day: LocalDate? = selected ?: (period as? SectionPeriod.Day)?.start
    /** Last day that counts: the day, else min(period end, today). */
    val end: LocalDate = day ?: minOf(period.last, today)
    /** The period cards / pills are scoped to (the selected day as a Day). */
    val effective: SectionPeriod = day?.let { SectionPeriod.Day(it.toEpochDay()) } ?: period
    val current: Boolean get() = period.isCurrent(today)
}

// ---------------------------------------------------------------------------------------------------------------- axis

enum class BodyAxisKind { WINDOW7, WEEK, MONTH, YEAR, ALL, WAIST8 }

/**
 * x-axis of a Body chart (mock `Axis` / `WaistAxis`): one bucket per day (WINDOW7 = 7 days ending on the day; W / M / Y =
 * the period's days), per month (ALL) or per tape reading (WAIST8 = the last 8 waist readings). [hl] = the highlighted
 * bucket of the day window (others drawn at 55 %); [todayI] / [selI] = the circled axis labels; [labelIdx] = the buckets
 * that carry a label (the UI formats them per [kind]).
 */
class BodyAxis(
    val kind: BodyAxisKind,
    val days: List<LocalDate>,
    val today: LocalDate,
    val hl: Int?,
    val todayI: Int?,
    val selI: Int?,
    val labelIdx: List<Int>,
    /** First day of the previous period (ghost alignment), null = no ghost. */
    val prevStart: LocalDate?,
) {
    val n: Int get() = days.size
    val monthly: Boolean get() = kind == BodyAxisKind.ALL
    private val pos: Map<LocalDate, Int> = days.withIndex().associate { it.value to it.index }

    fun idx(d: LocalDate): Int? = if (monthly) pos[d.withDayOfMonth(1)] else pos[d]

    /** Weekend buckets (shaded columns): day axes only. */
    val weekend: List<Int> get() = if (kind == BodyAxisKind.WINDOW7 || kind == BodyAxisKind.WEEK || kind == BodyAxisKind.MONTH)
        days.indices.filter { days[it].dayOfWeek == DayOfWeek.SATURDAY || days[it].dayOfWeek == DayOfWeek.SUNDAY } else emptyList()

    /** Lines break where two readings are more than this many buckets apart (4 days; 1Y 6; All 1 month; tape never). */
    val maxGap: Int get() = when (kind) { BodyAxisKind.ALL -> 1; BodyAxisKind.YEAR -> 6; BodyAxisKind.WAIST8 -> Int.MAX_VALUE; else -> 4 }

    /** The bucket of [d] when it is a future day (labels faint, no data). */
    val futureFrom: Int? get() = if (monthly) null else days.indexOfFirst { it.isAfter(today) }.takeIf { it >= 0 }

    /** Readings on this axis, (bucket, value), oldest first. All: the last reading of each month. */
    fun points(s: Map<LocalDate, Double>): List<Pair<Int, Double>> {
        if (days.isEmpty()) return emptyList()
        if (monthly) {
            val out = sortedMapOf<Int, Double>()
            s.entries.sortedBy { it.key }.forEach { (d, v) -> if (!d.isAfter(today)) idx(d)?.let { out[it] = v } }
            return out.map { it.key to it.value }
        }
        return s.entries.filter { !it.key.isAfter(today) }.mapNotNull { e -> idx(e.key)?.let { it to e.value } }.sortedBy { it.first }
    }

    /** The day each [points] bucket comes from (All: the month's last reading day). */
    fun pointDays(s: Map<LocalDate, Double>): Map<Int, LocalDate> {
        if (!monthly) return points(s).associate { it.first to days[it.first] }
        val out = HashMap<Int, LocalDate>()
        s.keys.sorted().forEach { d -> if (!d.isAfter(today)) idx(d)?.let { out[it] = d } }
        return out
    }

    /** All only: the lowest–highest of each month (null = no reading). */
    fun band(s: Map<LocalDate, Double>): List<Pair<Double, Double>?>? {
        if (!monthly) return null
        val rows = arrayOfNulls<Pair<Double, Double>>(n)
        s.forEach { (d, v) -> if (!d.isAfter(today)) idx(d)?.let { i -> val r = rows[i]; rows[i] = if (r == null) v to v else minOf(r.first, v) to maxOf(r.second, v) } }
        return rows.toList()
    }

    /**
     * The FULL previous period as (bucket, value) aligned by index: 1W the same weekday, 1M the same day of the month,
     * 1Y the same day of the year (29 Feb left out). Day window / All / tape: null.
     */
    fun ghost(s: Map<LocalDate, Double>): List<Pair<Int, Double>>? {
        val a = prevStart ?: return null
        val b = days.firstOrNull()?.minusDays(1) ?: return null
        val out = s.entries.filter { !it.key.isBefore(a) && !it.key.isAfter(b) }.mapNotNull { (k, v) ->
            val i = when (kind) {
                BodyAxisKind.MONTH -> k.dayOfMonth - 1
                BodyAxisKind.YEAR -> if (k.monthValue == 2 && k.dayOfMonth == 29) null else ChronoUnit.DAYS.between(a, k).toInt()
                else -> ChronoUnit.DAYS.between(a, k).toInt()
            }
            if (i != null && i in 0 until n) i to v else null
        }.sortedBy { it.first }
        return out.ifEmpty { null }
    }

    companion object {
        private fun window7(day: LocalDate, today: LocalDate): BodyAxis {
            val days = (6 downTo 0).map { day.minusDays(it.toLong()) }
            return BodyAxis(BodyAxisKind.WINDOW7, days, today, hl = 6, todayI = if (day == today) 6 else null,
                selI = if (day != today) 6 else null, labelIdx = days.indices.toList(), prevStart = null)
        }

        /** The hero axis: always the PERIOD (a selected day is marked, the period stays). 1D: the 7-day window. */
        fun hero(s: BodyScope, firstDay: LocalDate?): BodyAxis {
            val p = s.period
            val today = s.today
            return when (p) {
                is SectionPeriod.Day -> window7(p.start, today)
                is SectionPeriod.Week, is SectionPeriod.Month -> {
                    val days = (0 until p.totalDays()).map { p.start.plusDays(it.toLong()) }
                    val todayI = days.indexOf(today).takeIf { it >= 0 }
                    val selI = s.selected?.let { days.indexOf(it) }?.takeIf { it >= 0 }
                    val labels: List<Int> = if (p is SectionPeriod.Week) days.indices.toList() else {
                        val base = days.indices.filter { days[it].dayOfMonth in setOf(1, 8, 15, 22, 29) }.toMutableSet()
                        listOfNotNull(todayI, selI).forEach { m ->
                            base.removeAll { it != todayI && it != selI && kotlin.math.abs(it - m) <= 2 }
                            base += m
                        }
                        base.sorted()
                    }
                    BodyAxis(if (p is SectionPeriod.Week) BodyAxisKind.WEEK else BodyAxisKind.MONTH, days, today, null, todayI, selI, labels,
                        p.previous()?.start)
                }
                is SectionPeriod.Year -> {
                    val days = (0 until p.totalDays()).map { p.start.plusDays(it.toLong()) }
                    val todayI = if (p.isCurrent(today)) days.indexOf(LocalDate.of(today.year, today.monthValue, 15)).takeIf { it >= 0 } else null
                    BodyAxis(BodyAxisKind.YEAR, days, today, null, todayI, null, days.indices.filter { days[it].dayOfMonth == 15 }, p.previous()?.start)
                }
                is SectionPeriod.All -> {
                    val f = YearMonth.from(firstDay ?: p.start); val t = YearMonth.from(today)
                    val months = generateSequence(f) { it.plusMonths(1) }.takeWhile { !it.isAfter(t) }.map { it.atDay(1) }.toList()
                    BodyAxis(BodyAxisKind.ALL, months, today, null, months.lastIndex.takeIf { it >= 0 }, null,
                        months.indices.filter { months[it].monthValue == 1 }, null)
                }
            }
        }

        /** A card axis: day mode = the 7-day window ending on the day; waist on day / 1W / 1M = the last 8 tape readings. */
        fun card(s: BodyScope, metricId: String, series: BodySeries, firstDay: LocalDate?): BodyAxis {
            if (metricId == BodyIds.WAIST && (s.dayMode || s.period is SectionPeriod.Week || s.period is SectionPeriod.Month)) {
                val ks = series.waist.keys.filter { !it.isAfter(s.end) }.sorted().takeLast(8)
                return BodyAxis(BodyAxisKind.WAIST8, ks, s.today, null, null, null, listOf(0, ks.lastIndex).filter { it >= 0 }.distinct(), null)
            }
            val d = s.day
            if (d != null) return window7(d, s.today)
            return hero(s, firstDay)
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------- hero

/** One composition bar: lean (bottom) + fat mass (top) of a weigh-in, fat % on its own scale. */
data class CompRow(val i: Int, val lean: Double, val fatKg: Double, val fatPct: Double?, val day: LocalDate)

/** Weekly ▼ / ▲ / ● mark at the week's Thursday (complete weeks, lowest vs the week before's lowest). */
data class WeekMark(val i: Int, val dir: TrendDir?)

/** Measurements table row: a weigh-in day, a month (1Y) or a year (All). Cells per metric id: value + change vs the previous reading. */
data class BodyTableCell(val value: Double, val change: Double?)
data class BodyTableRow(val day: LocalDate?, val month: YearMonth?, val year: Int?, val cells: Map<String, BodyTableCell>, val highlighted: Boolean)

// ---------------------------------------------------------------------------------------------------------------- trends

/** Label forms of the Body Trends tiles (approved list, IMPL_PLAN §3B "Body texts"). */
enum class BodyLabel { VS_LAST_WEEKDAY, VS_DAY, THIS_WEEK, WEEK_OF, THIS_MONTH, IN_MONTH, YEAR, SINCE }

/** One Trends fact (T1–T6). [spark] = the metric's readings in the tile's scope (the tile's micro visual). */
sealed class BodyFact {
    abstract val metricId: String
    abstract val spark: List<Double>

    /** "{Metric} this week / … / vs {d Mon}" · "{±n} kg". [labelDay] = the day of VS_DAY / the month / year / first reading. */
    data class Change(override val metricId: String, val label: BodyLabel, val labelDay: LocalDate, val change: ReadingChange, override val spark: List<Double>) : BodyFact()
    /** Weight · "{gap} kg" + "to target", caption "Target 78 kg"; [gap] ≤ 0 → ✓ "Target reached". */
    data class ToTarget(val gap: Double, val target: Double, override val spark: List<Double>) : BodyFact() { override val metricId get() = BodyIds.WEIGHT }
    /** "Waist · {d Mon}" plain value (glyph). */
    data class Plain(override val metricId: String, val at: Extreme, override val spark: List<Double>) : BodyFact()
    /** "Lowest this week" · "{v} kg" + "{±n} kg" vs the week before's lowest. */
    data class LowestWeek(val low: Extreme, val prev: Extreme?, val diff: Double?, val dir: TrendDir?, override val spark: List<Double>) : BodyFact() { override val metricId get() = BodyIds.WEIGHT }
    /** "Lowest since {Mon yyyy}" · "{v} kg" + "{d Mon}" (▼ good). */
    data class Record(val at: Extreme, val sinceMonth: LocalDate, override val spark: List<Double>) : BodyFact() { override val metricId get() = BodyIds.WEIGHT }
    /** "Weeks down" · "{n} of {m}" (▼ good when n ≥ m/2, else ▲ bad), one tick per complete week. */
    data class WeeksDown(val n: Int, val m: Int, val flags: List<Boolean>) : BodyFact() { override val metricId get() = BodyIds.WEIGHT; override val spark get() = emptyList<Double>() }
    /** All: "Lowest" (▼ good) / "Highest" (▲ neutral) · "{v} kg" + "{d Mon yyyy}". */
    data class Extremum(val at: Extreme, val low: Boolean, override val spark: List<Double>) : BodyFact() { override val metricId get() = BodyIds.WEIGHT }
    /** No fact for this slot: the slot keeps the metric glyph and "—" (IMPL_PLAN §5.6). */
    data class Missing(override val metricId: String) : BodyFact() { override val spark get() = emptyList<Double>() }
}

// ---------------------------------------------------------------------------------------------------------------- model

/** Targets from the user's goals (null = none: no target line / tile shows "—"). */
data class BodyTargets(val weight: Double?, val fatPct: Double?, val waist: Double?)

/** Everything the Body tab draws for one scope, computed on IO by [BodyR3Math.build]. */
class BodyModel(
    val scope: BodyScope,
    val series: BodySeries,
    val targets: BodyTargets,
    val heightM: Double?,
    /** Hero / card change per metric with the period's basis (day: previous reading · W/M/Y: last before the period · All: first ever). */
    val changes: Map<String, ReadingChange>,
    val heroAxis: BodyAxis,
    val comp: List<CompRow>,
    val weekMarks: List<WeekMark>,
    val ringed: Set<Int>,
    val chord: Chord?,
    val trends: List<BodyFact>,
    /** The Trends header day ("vs 30 Sep"); null = no header (day mode, All, no earlier reading). */
    val basisDay: LocalDate?,
    val table: List<BodyTableRow>,
    /** Weight minus target, rounded as shown; null without a target or a reading. */
    val toTarget: Double?,
)

object BodyR3Math {
    private fun rule(id: String) = BodyRules.of(id)

    /** The change of [id] with the period's basis. */
    fun change(series: BodySeries, id: String, s: BodyScope): ReadingChange {
        val r = rule(id)
        val m = series.of(id)
        return when {
            s.dayMode -> R3Math.readingChange(id, m, s.effective, s.day, s.today, ReadingBasis.PREVIOUS_READING, r.decimals, r.flat, r.higherIsBetter)
            s.period is SectionPeriod.All -> R3Math.readingChange(id, m, s.period, null, s.today, ReadingBasis.FIRST_EVER, r.decimals, r.flat, r.higherIsBetter)
            else -> R3Math.readingChange(id, m, s.period, null, s.today, ReadingBasis.LAST_BEFORE_PERIOD, r.decimals, r.flat, r.higherIsBetter)
        }
    }

    /** Weight − target as shown ("1.5 kg to target"); ≤ 0 = target reached. */
    fun toTarget(series: BodySeries, end: LocalDate, target: Double?): Double? {
        target ?: return null
        val w = series.weight.entries.filter { !it.key.isAfter(end) }.maxByOrNull { it.key }?.value ?: return null
        return R3Math.round(R3Math.round(w, 1) - target, 1)
    }

    /** Fat mass of a weigh-in = weight − lean (as displayed). */
    fun fatMass(weight: Double, lean: Double): Double = R3Math.round(R3Math.round(weight, 1) - R3Math.round(lean, 1), 1)

    /** BMI = weight ÷ height². */
    fun bmi(weight: Double, heightM: Double): Double = weight / (heightM * heightM)

    /** Waist-to-height ratio (2 decimals shown). */
    fun whtr(waistCm: Double, heightM: Double): Double = waistCm / 100.0 / heightM

    /** Composition bars on [ax]: per weigh-in; 1Y: the month's last weigh-in at mid-month; All: per month. */
    fun compRows(ax: BodyAxis, s: BodySeries): List<CompRow> {
        if (ax.kind == BodyAxisKind.YEAR) {
            return ax.days.filter { it.dayOfMonth == 15 }.mapNotNull { mid ->
                val k = s.lean.keys.filter { it.year == mid.year && it.month == mid.month && !it.isAfter(ax.today) }.maxOrNull() ?: return@mapNotNull null
                val fm = s.fatMass[k] ?: return@mapNotNull null
                CompRow(ax.idx(mid) ?: return@mapNotNull null, s.lean[k]!!, fm, s.fatPct[k], k)
            }
        }
        val days = ax.pointDays(s.lean)
        return ax.points(s.lean).mapNotNull { (i, l) ->
            val k = days[i] ?: return@mapNotNull null
            val fm = s.fatMass[k] ?: return@mapNotNull null
            CompRow(i, l, fm, s.fatPct[k], k)
        }
    }

    /** Spark values of a tile (mock `_tile_viz`). */
    fun spark(s: BodySeries, id: String, sc: BodyScope, firstDay: LocalDate?): List<Double> {
        val m = s.of(id)
        val (a, b) = when {
            sc.dayMode -> sc.end.minusDays(6) to sc.end
            sc.period is SectionPeriod.All -> (firstDay ?: sc.period.start) to sc.today
            else -> sc.period.start to sc.end
        }
        if (id == BodyIds.WAIST && (sc.dayMode || sc.period is SectionPeriod.Week || sc.period is SectionPeriod.Month))
            return m.entries.filter { !it.key.isAfter(b) }.sortedBy { it.key }.takeLast(8).map { it.value }
        val inRange = m.entries.filter { !it.key.isBefore(a) && !it.key.isAfter(b) }.sortedBy { it.key }
        if (!sc.dayMode && (sc.period is SectionPeriod.All || sc.period is SectionPeriod.Year))
            return inRange.groupBy { YearMonth.from(it.key) }.toSortedMap().values.map { it.last().value }
        return inRange.map { it.value }
    }

    /** Lowest of the Mon–Sun week of [monday] vs the week before (mock `lowest_week_tile`). */
    fun lowestWeek(s: BodySeries, monday: LocalDate, spark: List<Double>): BodyFact {
        val cur = R3Math.weekLow(s.weight, monday) ?: return BodyFact.Missing(BodyIds.WEIGHT)
        val prv = R3Math.weekLow(s.weight, monday.minusDays(7))
        val d = prv?.let { R3Math.round(R3Math.round(cur.value, 1) - R3Math.round(it.value, 1), 1) }
        val dir = d?.let { when { kotlin.math.abs(it) < 0.1 - 1e-9 -> TrendDir.FLAT; it > 0 -> TrendDir.UP; else -> TrendDir.DOWN } }
        return BodyFact.LowestWeek(cur, prv, d, dir, spark)
    }

    /** The 6 Trends facts (IMPL_PLAN §3B Trends table; mock `trends`). */
    fun trends(s: BodySeries, sc: BodyScope, changes: Map<String, ReadingChange>, targets: BodyTargets): List<BodyFact> {
        val first = s.firstDay
        fun sp(id: String) = spark(s, id, sc, first)
        val p = sc.period
        fun chg(id: String, label: BodyLabel, day: LocalDate, c: ReadingChange = changes.getValue(id)): BodyFact =
            if (c.diff == null || c.carried && !sc.dayMode) BodyFact.Missing(id) else BodyFact.Change(id, label, day, c, sp(id))
        fun vsDay(id: String): BodyFact { val c = changes.getValue(id); val r = c.reference ?: return BodyFact.Missing(id); return chg(id, BodyLabel.VS_DAY, r.day, c) }
        val target: BodyFact = toTarget(s, sc.end, targets.weight)?.let { BodyFact.ToTarget(it, targets.weight!!, sp(BodyIds.WEIGHT)) } ?: BodyFact.Missing(BodyIds.WEIGHT)
        fun waistPlain(): BodyFact = s.waist.entries.filter { !it.key.isAfter(sc.end) }.maxByOrNull { it.key }
            ?.let { BodyFact.Plain(BodyIds.WAIST, Extreme(it.value, it.key), sp(BodyIds.WAIST)) } ?: BodyFact.Missing(BodyIds.WAIST)

        if (sc.dayMode) {
            val day = sc.day!!
            val r = rule(BodyIds.WEIGHT)
            val t1c = R3Math.readingChange(BodyIds.WEIGHT, s.weight, sc.effective, day, sc.today, ReadingBasis.SAME_WEEKDAY_LAST_WEEK, r.decimals, r.flat, r.higherIsBetter)
            val ref = t1c.reference
            val t1 = when {
                t1c.diff == null || ref == null -> BodyFact.Missing(BodyIds.WEIGHT)
                ref.day == day.minusDays(7) && t1c.current?.day == day -> BodyFact.Change(BodyIds.WEIGHT, BodyLabel.VS_LAST_WEEKDAY, day, t1c, sp(BodyIds.WEIGHT))
                else -> BodyFact.Change(BodyIds.WEIGHT, BodyLabel.VS_DAY, ref.day, t1c, sp(BodyIds.WEIGHT))
            }
            return listOf(t1, target, vsDay(BodyIds.FAT_MASS), vsDay(BodyIds.LEAN), waistPlain(), vsDay(BodyIds.FAT_PCT))
        }
        if (p is SectionPeriod.All) {
            fun since(id: String): BodyFact = s.first(id)?.let { chg(id, BodyLabel.SINCE, it) } ?: BodyFact.Missing(id)
            val lo = R3Math.allTimeExtreme(s.weight, low = true); val hi = R3Math.allTimeExtreme(s.weight, low = false)
            return listOf(
                since(BodyIds.WEIGHT),
                lo?.let { BodyFact.Extremum(it, true, sp(BodyIds.WEIGHT)) } ?: BodyFact.Missing(BodyIds.WEIGHT),
                since(BodyIds.FAT_MASS), since(BodyIds.LEAN), since(BodyIds.WAIST),
                hi?.let { BodyFact.Extremum(it, false, sp(BodyIds.WEIGHT)) } ?: BodyFact.Missing(BodyIds.WEIGHT),
            )
        }
        // W / M / Y: "this week" / "week of {d Mon}" (past week, owner OK 4 Nov) / "this month" / "in {Month}" / "{yyyy}"
        fun periodFact(id: String): BodyFact = when (p) {
            is SectionPeriod.Week -> chg(id, if (sc.current) BodyLabel.THIS_WEEK else BodyLabel.WEEK_OF, p.start)
            is SectionPeriod.Month -> chg(id, if (sc.current) BodyLabel.THIS_MONTH else BodyLabel.IN_MONTH, p.start)
            else -> chg(id, BodyLabel.YEAR, p.start)
        }
        val t5: BodyFact = when (p) {
            is SectionPeriod.Week -> lowestWeek(s, p.start, sp(BodyIds.WEIGHT))
            else -> periodFact(BodyIds.WAIST).let { if (it is BodyFact.Missing) waistPlain() else it }
        }
        val weeks = if (p is SectionPeriod.Month || p is SectionPeriod.Year) R3Math.weeksDown(s.weight, p.start, p.last, sc.today) else emptyList()
        val rec = when (p) {
            is SectionPeriod.Week -> R3Math.periodRecord(s.weight, BodyIds.WEIGHT, p, sc.today, low = true, minBackDays = 56)
            is SectionPeriod.Month -> R3Math.periodRecord(s.weight, BodyIds.WEIGHT, p, sc.today, low = true, minBackDays = 240)
            else -> null
        }
        val t6: BodyFact = when {
            rec != null -> BodyFact.Record(rec.at, rec.lastAtLeast?.plusDays(1) ?: (s.first(BodyIds.WEIGHT) ?: rec.at.day), sp(BodyIds.WEIGHT))
            weeks.size >= 2 -> weeksDownFact(weeks)
            p is SectionPeriod.Month -> lowestWeek(s, SectionPeriod.mondayOf(sc.end), sp(BodyIds.WEIGHT))
            else -> periodFact(BodyIds.FAT_PCT)
        }
        return listOf(periodFact(BodyIds.WEIGHT), target, periodFact(BodyIds.FAT_MASS), periodFact(BodyIds.LEAN), t5, t6)
    }

    fun weeksDownFact(weeks: List<WeekLow>): BodyFact.WeeksDown =
        BodyFact.WeeksDown(weeks.count { it.dir == TrendDir.DOWN }, weeks.size, weeks.map { it.dir == TrendDir.DOWN })

    /** Measurements rows, newest first (mock `table_rows`); [n] rows (inner 5, cover 3). */
    fun table(s: BodySeries, sc: BodyScope, n: Int = 5): List<BodyTableRow> {
        val ids = listOf(BodyIds.WEIGHT, BodyIds.FAT_PCT, BodyIds.LEAN, BodyIds.WAIST)
        fun prevBefore(m: Map<LocalDate, Double>, d: LocalDate) = m.entries.filter { it.key.isBefore(d) }.maxByOrNull { it.key }?.value
        fun diff(v: Double, pv: Double?) = pv?.let { R3Math.round(R3Math.round(v, 1) - R3Math.round(it, 1), 1) }
        val p = sc.period
        if (p is SectionPeriod.Day || p is SectionPeriod.Week || p is SectionPeriod.Month) {
            val end = if (p is SectionPeriod.Day) sc.end else minOf(p.last, sc.today)
            val keys = (s.weight.keys + s.waist.keys).filter { !it.isAfter(end) }.sorted().takeLast(n).reversed()
            return keys.map { k ->
                val cells = ids.mapNotNull { id -> s.of(id)[k]?.let { v -> id to BodyTableCell(v, diff(v, prevBefore(s.of(id), k))) } }.toMap()
                BodyTableRow(k, null, null, cells, highlighted = sc.selected == k)
            }
        }
        val groups: List<Triple<LocalDate, LocalDate, Any>> = if (p is SectionPeriod.Year) {
            val last = YearMonth.from(minOf(p.last, sc.today))
            (0 until n).map { last.minusMonths(it.toLong()) }.filter { it.year == p.year }.map { Triple(it.atDay(1), it.atEndOfMonth(), it) }
        } else {
            val firstYear = s.firstDay?.year ?: sc.today.year
            (0 until n).map { sc.today.year - it }.filter { it >= firstYear }.map { Triple(LocalDate.of(it, 1, 1), LocalDate.of(it, 12, 31), it) }
        }
        return groups.mapNotNull { (a, b, key) ->
            val cells = ids.mapNotNull { id ->
                val m = s.of(id)
                val last = m.entries.filter { !it.key.isBefore(a) && !it.key.isAfter(minOf(b, sc.today)) }.maxByOrNull { it.key } ?: return@mapNotNull null
                id to BodyTableCell(last.value, diff(last.value, prevBefore(m, a)))
            }.toMap()
            if (cells.isEmpty() && p is SectionPeriod.Year) null
            else BodyTableRow(null, key as? YearMonth, key as? Int, cells, false)
        }
    }

    /** The whole tab for one scope. */
    fun build(series: BodySeries, sc: BodyScope, targets: BodyTargets, heightM: Double?): BodyModel {
        val ids = listOf(BodyIds.WEIGHT, BodyIds.FAT_PCT, BodyIds.LEAN, BodyIds.FAT_MASS, BodyIds.WAIST, BodyIds.BMI)
        val changes = ids.associateWith { change(series, it, sc) }
        val ax = BodyAxis.hero(sc, series.firstDay)
        val p = sc.period
        val dayAxis = ax.hl != null
        // 1W / 1M: each week's lowest ringed (weeks with > 1 weigh-in)
        val ringed = if ((p is SectionPeriod.Week || p is SectionPeriod.Month) && !dayAxis) {
            ax.points(series.weight).map { SectionPeriod.mondayOf(ax.days[it.first]) }.distinct().mapNotNull { mon ->
                val n = series.weight.keys.count { !it.isBefore(mon) && !it.isAfter(mon.plusDays(6)) }
                if (n > 1) R3Math.weekLow(series.weight, mon)?.let { ax.idx(it.day) } else null
            }.toSet()
        } else emptySet()
        val marks = if ((p is SectionPeriod.Month || p is SectionPeriod.Year) && !dayAxis)
            R3Math.weeksDown(series.weight, p.start, p.last, sc.today).mapNotNull { w -> ax.idx(w.monday.plusDays(3))?.let { WeekMark(it, w.dir) } }
        else emptyList()
        val chord = if (p is SectionPeriod.Year) R3Math.chord(series.weight, p.start, minOf(p.last, sc.today)) else null
        val basisDay = if (sc.dayMode || p is SectionPeriod.All) null else changes.getValue(BodyIds.WEIGHT).reference?.day
        return BodyModel(
            scope = sc, series = series, targets = targets, heightM = heightM, changes = changes, heroAxis = ax,
            comp = compRows(ax, series), weekMarks = marks, ringed = ringed, chord = chord,
            trends = trends(series, sc, changes, targets), basisDay = basisDay, table = table(series, sc),
            toTarget = toTarget(series, sc.end, targets.weight),
        )
    }

    /** Readings of the scrubbed / shown day for the hero 2×2 (waist: the last tape reading on or before). */
    fun valueOn(series: BodySeries, id: String, day: LocalDate): Extreme? {
        val m = series.of(id)
        m[day]?.let { return Extreme(it, day) }
        if (id != BodyIds.WAIST) return null
        return m.entries.filter { !it.key.isAfter(day) }.maxByOrNull { it.key }?.let { Extreme(it.value, it.key) }
    }
}
