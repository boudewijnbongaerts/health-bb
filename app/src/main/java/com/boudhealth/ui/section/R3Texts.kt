package com.boudhealth.ui.section

import com.boudhealth.data.ChangeForm
import com.boudhealth.data.CompareWindow
import com.boudhealth.data.Extreme
import com.boudhealth.data.R3Math
import com.boudhealth.data.ReadingChange
import com.boudhealth.data.SectionPeriod
import com.boudhealth.data.TotalChange
import com.boudhealth.data.TrendDir
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * EVERY user-facing string of the R3 Activity / Body / Vitals tabs (IMPL_PLAN §2.8, the approved lists in §3 and
 * review/REVIEW.md). Tab files contain no string literals except format patterns: ask team S for a missing one.
 *
 * Formats: minus is U+2212 "−"; a narrow no-break space before "%" ("12 %"); thousands ","; durations "2h 08" /
 * "7h 14m"; dates "4 Nov", "Wed 4 Nov", "Nov 2023", "October", "2026", "2–8 Nov", "26 Oct – 1 Nov".
 * Numbers are rounded with [R3Math.round] (HALF_UP, the same as SFmt / String.format), so a displayed value and a
 * change computed by R3Math always agree.
 */
object R3Texts {
    // ================================================================================================ number formats
    const val MINUS = "−"
    /** Narrow no-break space before "%" (mock-ups "12 %"). */
    const val PCT_SP = " "
    const val DASH = "—"
    private val L = Locale.US

    fun int(v: Double): String = String.format(L, "%,d", R3Math.round(v, 0).roundToLong())
    fun dec(v: Double, decimals: Int): String = if (decimals <= 0) int(v) else String.format(L, "%,.${decimals}f", R3Math.round(v, decimals))
    /** "+12" / "−5,293" / "0"; with decimals "+0.4" / "0.0". */
    fun signed(v: Double, decimals: Int = 0): String {
        val r = R3Math.round(v, decimals)
        if (r == 0.0) return dec(0.0, decimals)
        return (if (r < 0) MINUS else "+") + dec(abs(r), decimals)
    }
    /** Magnitude without sign (pill text next to ▲ / ▼): "0.2". */
    fun mag(v: Double, decimals: Int = 0): String = dec(abs(R3Math.round(v, decimals)), decimals)
    fun pct(v: Double): String = "${int(abs(v))}$PCT_SP%"
    /** "+12 %", "−5 %", "0 %". */
    fun signedPct(v: Double): String { val r = R3Math.round(v, 0); return if (r == 0.0) "0$PCT_SP%" else (if (r < 0) MINUS else "+") + pct(r) }
    /** "16k", "412k", "1.98M" (ring numbers, Best month, year totals). */
    fun short(v: Double): String = when {
        abs(v) >= 1_000_000 -> String.format(L, "%.2fM", v / 1_000_000.0).replace(".00M", "M")
        abs(v) >= 100_000 -> "${int(v / 1000.0)}k"
        abs(v) >= 1000 -> String.format(L, "%.1fk", v / 1000.0).replace(".0k", "k")
        else -> int(v)
    }
    /** Workout duration "2h 08" (< 1 h: "45 min"). */
    fun dur(minutes: Double): String { val m = R3Math.round(minutes, 0).toInt(); return if (m < 60) "$m min" else "${m / 60}h ${String.format(L, "%02d", m % 60)}" }
    /** Sleep duration "7h 14m". */
    fun hm(minutes: Double): String { val m = R3Math.round(minutes, 0).toInt(); return if (m < 60) "${m}m" else "${m / 60}h ${String.format(L, "%02d", m % 60)}m" }
    fun hhmm(t: LocalTime): String = String.format(L, "%02d:%02d", t.hour, t.minute)
    /** Hour tick "2h" (sleep chart axis). */
    fun hTick(hours: Int): String = "${hours}h"
    fun hh00(hour: Int): String = String.format(L, "%02d:00", hour)
    /** "hh:mm–hh:mm". */
    fun timeRange(a: LocalTime, b: LocalTime): String = "${hhmm(a)}–${hhmm(b)}"
    /** "{lo}–{hi}" with decimals. */
    fun range(lo: Double, hi: Double, decimals: Int = 0): String = "${dec(lo, decimals)}–${dec(hi, decimals)}"

    // ================================================================================================ date formats
    fun monShort(d: LocalDate): String = d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    fun monthName(d: LocalDate): String = d.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    fun monthName(ym: YearMonth): String = ym.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    fun ddd(d: LocalDate): String = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    fun weekday(d: LocalDate): String = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    /** "4 Nov". */
    fun dMon(d: LocalDate): String = "${d.dayOfMonth} ${monShort(d)}"
    /** "4 Nov 2025". */
    fun dMonYyyy(d: LocalDate): String = "${dMon(d)} ${d.year}"
    /** "4 Nov", or "4 Nov 2025" when not in [ref]'s year. */
    fun dMonRel(d: LocalDate, ref: LocalDate): String = if (d.year == ref.year) dMon(d) else dMonYyyy(d)
    /** "Wed 4 Nov". */
    fun wkdDMon(d: LocalDate): String = "${ddd(d)} ${dMon(d)}"
    /** "Nov 2023". */
    fun monYyyy(d: LocalDate): String = "${monShort(d)} ${d.year}"
    /** "November 2026". */
    fun monthYear(d: LocalDate): String = "${monthName(d)} ${d.year}"
    fun yyyy(d: LocalDate): String = "${d.year}"
    /** "2–8 Nov" (same month) / "26 Oct – 1 Nov". */
    fun dRange(a: LocalDate, b: LocalDate): String =
        if (a.month == b.month && a.year == b.year) "${a.dayOfMonth}–${b.dayOfMonth} ${monShort(b)}" else "${dMon(a)} – ${dMon(b)}"
    /** Week of [monday] as "2–8 Nov". */
    fun week(monday: LocalDate): String = dRange(monday, monday.plusDays(6))

    // ================================================================================================ common
    const val TRENDS = "Trends"
    const val NO_DATA_PERIOD = "No data for this period"
    const val NO_DATA_DAY = "No data for this day"
    const val LOWEST = "Lowest"
    const val HIGHEST = "Highest"
    const val BEST_DAY = "Best day"
    const val GOAL = "Goal"
    const val SAME = "Same"
    const val TO_TARGET = "to target"
    const val TARGET_REACHED = "Target reached"
    fun all(n: Int) = "All $n"
    fun lastMeasured(d: LocalDate, today: LocalDate) = "Last measured ${dMonRel(d, today)}"
    /** "Since Nov 2023" (context lines, rail). */
    fun sinceCap(d: LocalDate) = "Since ${monYyyy(d)}"
    /** "since Nov 2023" (tile captions). */
    fun since(d: LocalDate) = "since ${monYyyy(d)}"
    /** "since 4 Nov" (VO₂ tile caption, within the year) / "Since 4 Nov" (rail). */
    fun sinceDay(d: LocalDate) = "since ${dMon(d)}"
    fun sinceDayCap(d: LocalDate) = "Since ${dMon(d)}"
    /** "Since 6 Nov 2023" (VO₂ rail on All). */
    fun sinceDayYyyyCap(d: LocalDate) = "Since ${dMonYyyy(d)}"
    /** "Since 4 Nov" in [ref]'s year, else "Since 6 Nov 2023". */
    fun sinceDayRelCap(d: LocalDate, ref: LocalDate) = "Since ${dMonRel(d, ref)}"

    // ---- previous-period words (ghost keys, pills, basis)
    const val YESTERDAY = "Yesterday"
    const val LAST_WEEK = "Last week"
    const val THIS_WEEK = "This week"
    /**
     * Ghost key word for [p] (the CURRENT period): "Yesterday" (1D today) · "{Wkd d Mon}" (past 1D) · "Last week" (current
     * 1W) · "{d}–{d} {Mon}" (past 1W) · "{Month}" (1M) · "{yyyy}" (1Y) · null (All).
     */
    fun ghostWord(p: SectionPeriod, today: LocalDate): String? {
        val q = p.previous() ?: return null
        return when (p) {
            is SectionPeriod.Day -> if (p.start == today) YESTERDAY else wkdDMon(q.start)
            is SectionPeriod.Week -> if (p.isCurrent(today)) LAST_WEEK else week(q.start)
            is SectionPeriod.Month -> monthName(q.start)
            is SectionPeriod.Year -> yyyy(q.start)
            is SectionPeriod.All -> null
        }
    }
    /** Vitals band short form: "Oct" (1M), "Last week" (1W), "2025" (1Y). */
    fun ghostWordShort(p: SectionPeriod): String? {
        val q = p.previous() ?: return null
        return when (p) {
            is SectionPeriod.Week -> LAST_WEEK
            is SectionPeriod.Month -> monShort(q.start)
            is SectionPeriod.Year -> yyyy(q.start)
            else -> null
        }
    }
    /** Lower-case previous word for "vs …" / "Same as …": "yesterday", "last week", "October", "2025", or the day. */
    fun prevWord(p: SectionPeriod, today: LocalDate): String? {
        val q = p.previous() ?: return null
        return when (p) {
            is SectionPeriod.Day -> if (p.start == today) "yesterday" else wkdDMon(q.start)
            is SectionPeriod.Week -> "last week"
            is SectionPeriod.Month -> monthName(q.start)
            is SectionPeriod.Year -> yyyy(q.start)
            is SectionPeriod.All -> null
        }
    }
    /** "vs last week" / "vs October" / "vs 2025" (past W/M/Y basis, Vitals basis). */
    fun vsPrev(p: SectionPeriod, today: LocalDate): String? = prevWord(p, today)?.let { "vs $it" }

    /**
     * Activity Trends basis line from the comparison window: "vs yesterday until 13:05" · "vs last week until Wed 13:05" ·
     * "vs 1–4 Oct until 13:05" · "vs 1 Jan – 4 Nov 2025 until 13:05" · past: "vs Tue 3 Nov" / "vs last week" /
     * "vs October" / "vs 2025". [CompareWindow.wholeDays]: no "until hh:mm" and the previous side ends the day before (O3).
     */
    fun basis(w: CompareWindow?, today: LocalDate): String? {
        w ?: return null
        val p = w.period
        val cut = w.cut ?: return vsPrev(p, today)
        val end = if (w.wholeDays) w.prevEndDay.minusDays(1) else w.prevEndDay
        val until = if (w.wholeDays) "" else " until ${hhmm(cut)}"
        return when (p) {
            is SectionPeriod.Day -> "vs yesterday$until"
            is SectionPeriod.Week -> if (w.wholeDays) "vs last week" else "vs last week until ${ddd(w.prevEndDay)} ${hhmm(cut)}"
            is SectionPeriod.Month -> if (end.isBefore(w.prevFrom)) "vs ${monthName(w.prevFrom)}" else "vs 1–${end.dayOfMonth} ${monShort(end)}$until"
            is SectionPeriod.Year -> if (end.isBefore(w.prevFrom)) "vs ${yyyy(w.prevFrom)}" else "vs 1 Jan – ${dMonYyyy(end)}$until"
            is SectionPeriod.All -> null
        }
    }

    // ---- pills
    /** "vs {x}" suffix. */
    fun vs(x: String) = "vs $x"
    /** "Same as {x}". */
    fun sameAs(x: String) = "Same as $x"
    /** "{n} % vs {previous}" (no sign). */
    fun pillPct(pct: Double, previous: String) = "${pct(R3Math.round(pct, 0))} vs $previous"
    /** "{n} {unit} vs {day}" (no sign; unit may be empty: "0.4 vs 1 Oct"). */
    fun pillUnit(mag: String, unit: String?, day: String) = listOfNotNull(mag, unit?.takeIf { it.isNotEmpty() }).joinToString(" ") + " vs $day"
    /** "{n} {unit} since {Mon yyyy}". */
    fun pillSince(mag: String, unit: String?, first: LocalDate) = listOfNotNull(mag, unit?.takeIf { it.isNotEmpty() }).joinToString(" ") + " ${since(first)}"
    const val THREE_MONTHS_AGO = "3 months ago"

    /** Pill model for a total change: PERCENT "12 % vs October" / ABSOLUTE "30 min vs yesterday"; FLAT "Same as …". */
    fun totalPill(c: TotalChange, unit: String?, today: LocalDate): DeltaPillModel? {
        val prev = prevWord(c.window.period, today) ?: return null
        val tone = when (c.good) { true -> Tone.GOOD; false -> Tone.BAD; null -> Tone.NEUTRAL }
        if (c.dir == TrendDir.FLAT) return DeltaPillModel(TrendDir.FLAT, sameAs(prev), Tone.NEUTRAL)
        val text = when (c.form) {
            ChangeForm.PERCENT -> c.pct?.let { pillPct(abs(it), prev) } ?: pillUnit(mag(c.diff), unit, prev)
            ChangeForm.ABSOLUTE -> pillUnit(mag(c.diff), unit, prev)
        }
        return DeltaPillModel(c.dir, text, tone)
    }

    /** Tile value of a total change: "+12 %", "−5,293", "−30 min", "0 %"; prev = 0 (PERCENT): "+5,293" absolute. */
    fun tileChange(c: TotalChange, unit: String?): String = when (c.form) {
        ChangeForm.PERCENT -> c.pct?.let { signedPct(it) } ?: (signed(c.diff) + (unit?.let { " $it" } ?: ""))
        ChangeForm.ABSOLUTE -> signed(c.diff) + (unit?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: "")
    }

    /** Pill of a reading change: "0.2 kg vs 31 Oct" / "Same as 31 Oct" (day = the reference's date, year when needed). */
    fun readingPill(r: ReadingChange, unit: String?, decimals: Int, ref: LocalDate, neutral: Boolean = false): DeltaPillModel? {
        val d = r.diff ?: return null
        val refDay = r.reference?.day ?: return null
        val day = dMonRel(refDay, ref)
        if (r.dir == TrendDir.FLAT) return DeltaPillModel(TrendDir.FLAT, sameAs(day), Tone.NEUTRAL)
        val tone = if (neutral) Tone.NEUTRAL else when (r.good) { true -> Tone.GOOD; false -> Tone.BAD; null -> Tone.NEUTRAL }
        return DeltaPillModel(r.dir, pillUnit(mag(d, decimals), unit, day), tone)
    }

    // ---- a11y (direction words only + visible texts, §5.7)
    const val A11Y_UP = "up"
    const val A11Y_DOWN = "down"
    const val A11Y_SAME = "no change"
    const val A11Y_DONE = "done"
    fun a11yIcon(icon: TileIcon): String? = when (icon) {
        TileIcon.UP -> A11Y_UP; TileIcon.DOWN -> A11Y_DOWN; TileIcon.SAME -> A11Y_SAME; TileIcon.DONE -> A11Y_DONE; TileIcon.GLYPH -> null
    }
    /** Spoken tile: "{label}, {up|down|no change|done}, {value} {suffix}". */
    fun a11yTile(icon: TileIcon, label: String, value: String, suffix: String? = null): String =
        listOfNotNull(label, a11yIcon(icon), value, suffix).joinToString(", ")
    /** Calendar cell: "{Wkd d Mon}: workout {n} of {g} min, {n} of {g} steps, {n} of {g} heart points". */
    fun calendarCell(d: LocalDate, wm: Double, wg: Double, st: Double, sg: Double, hp: Double, hg: Double): String =
        "${wkdDMon(d)}: workout ${int(wm)} of ${int(wg)} min, ${int(st)} of ${int(sg)} steps, ${int(hp)} of ${int(hg)} heart points"
    fun yearCell(y: YearRingCell): String = "${y.year}: ${hWorkouts(y.hours)}, ${int(y.steps)} steps, ${heartPoints(y.points)}"

    // ================================================================================================ Activity
    // card titles (approved earlier)
    const val WORKOUT_MINUTES = "Workout minutes"
    const val WORKOUTS = "Workouts"
    const val STEPS = "Steps"
    const val HEART_POINTS = "Heart points"
    const val SLEEP = "Sleep"
    const val STREAK = "Streak"
    const val CALORIES = "Calories burned"
    const val VO2 = "VO₂ max"
    const val ALL_VALUES = "All values"
    // chips / tiles
    const val WORKOUT = "Workout"
    const val HEART_PTS = "Heart pts"
    val CHIPS = listOf(WORKOUT, STEPS, HEART_PTS)
    val ZONES = listOf("Fat burn", "Cardio", "Peak")
    val STAGES = listOf("Deep", "REM", "Light", "Awake")
    const val PEAK_HOUR = "Peak hour"
    fun peakHour(hour: Int) = "$PEAK_HOUR ${hh00(hour)}"
    /** "{Goal} goal met on {n} of {m} days". */
    fun goalMet(metricId: String, n: Int, m: Int): String {
        val g = when (metricId) { "steps" -> "Step"; "exercise_min" -> "Workout minutes"; "zone_min" -> "Heart points"; else -> "Goal" }
        return "$g goal met on $n of $m days"
    }
    fun streakLine(n: Int, best: Int) = "Streak $n · Best $best"
    fun workoutDaysOf(n: Int, goal: Int) = "$n / $goal workout days"
    const val WORKOUT_DAY = "Workout day"
    const val NO_WORKOUT_DAY = "No workout day"
    const val NO_WORKOUTS = "No workouts"
    const val NO_SLEEP = "No sleep recorded"
    const val HEART_POINTS_PER_WEEK = "Heart points per week"
    fun hWorkouts(hours: Double) = "${int(hours)} h workouts"
    const val H_WORKOUTS = "h workouts"
    const val STEPS_LC = "steps"
    const val HEART_POINTS_LC = "heart points"
    fun heartPoints(n: Double) = "${int(n)} heart points"
    fun avgHr(n: Int) = "Avg HR $n"
    // new and approved
    const val GOALS = "Goals"
    fun nOf3(n: Int) = "$n of 3"
    const val HEART_PTS_WEEK = "Heart pts / week"
    /** "{n} / {goal}". */
    fun ofGoal(n: Double, goal: Double) = "${int(n)} / ${int(goal)}"
    const val HEART_PTS_TARGET = "Heart pts target"
    /** "{n} of {m} weeks" / "{n} of 1 week". */
    fun weeksOf(n: Int, m: Int) = "$n of $m week${if (m == 1) "" else "s"}"
    const val BEDTIMES = "Bedtimes"
    const val WORKOUT_DAYS = "Workout days"
    /** "{n} · Best {n}". */
    fun streakValue(n: Int, best: Int) = "$n · Best $best"
    const val NO_WORKOUT_YET = "No workout yet today"
    /** "{hh}:00 · {n} steps". */
    fun peakHourSteps(hour: Int, steps: Double) = "${hh00(hour)} · ${int(steps)} steps"
    const val BEST_YEAR = "Best year"
    const val BEST_MONTH = "Best month"
    const val BEST_WEEK = "Best week"
    const val BEST_STREAK = "Best streak"
    /** "{n}k steps". */
    fun kSteps(v: Double) = "${short(v)} steps"
    fun days(n: Int) = "$n day${if (n == 1) "" else "s"}"
    const val PER_WEEK = "/ week"
    const val LONGEST = "Longest"
    /** "{dur} · {workout}". */
    fun longest(minutes: Double, workout: String) = "${dur(minutes)} · $workout"
    /** "Best day {d Mon} · {n} kcal". */
    fun bestDayKcal(d: LocalDate, kcal: Double) = "$BEST_DAY ${dMon(d)} · ${int(kcal)} kcal"
    fun nights(n: Int) = "$n night${if (n == 1) "" else "s"}"
    /** Goal rail "{n} min / day". */
    fun minPerDay(n: Int) = "$n min / day"
    /** Workouts card type chip "Strength {n}". */
    fun typeChip(type: String, n: Int) = "$type $n"
    /** Workout row "{d Mon hh:mm}". */
    fun sessionTime(d: LocalDate, t: LocalTime) = "${dMon(d)} ${hhmm(t)}"
    fun minutes(n: Double) = "${int(n)} min"

    // ================================================================================================ Body
    const val WEIGHT = "Weight"
    const val BODY_FAT = "Body fat percentage"
    const val LEAN_MASS = "Lean mass"
    const val FAT_MASS = "Fat mass"
    const val WAIST = "Waist circumference"
    const val WAIST_SHORT = "Waist"
    const val BMI = "BMI"
    const val MEASUREMENTS = "Measurements"
    const val MEASURE = "Measure"
    const val WHTR = "WHtR"
    // tile labels
    fun thisWeek(metric: String) = "$metric this week"
    fun thisMonth(metric: String) = "$metric this month"
    fun inMonth(metric: String, d: LocalDate) = "$metric in ${monthName(d)}"
    fun inYear(metric: String, d: LocalDate) = "$metric ${d.year}"
    fun sinceLabel(metric: String, first: LocalDate) = "$metric ${since(first)}"
    fun vsDayLabel(metric: String, d: LocalDate, ref: LocalDate) = "$metric vs ${dMonRel(d, ref)}"
    /** "Weight vs last Wednesday". */
    fun weightVsLast(d: LocalDate) = "$WEIGHT vs last ${weekday(d)}"
    const val LOWEST_THIS_WEEK = "Lowest this week"
    /** Past week (owner OK 4 Nov, OPEN_TEXTS 3). */
    const val LOWEST_THAT_WEEK = "Lowest that week"
    /** "Weight week of 26 Oct" (past week, owner OK 4 Nov, OPEN_TEXTS 3). */
    fun weekOfLabel(metric: String, monday: LocalDate, ref: LocalDate) = "$metric week of ${dMonRel(monday, ref)}"
    const val WEEKS_DOWN = "Weeks down"
    /** "{n} of {m}". */
    fun nOfM(n: Int, m: Int) = "$n of $m"
    /** "Lowest since Nov 2023". */
    fun lowestSince(d: LocalDate) = "Lowest since ${monYyyy(d)}"
    /** "Waist · 29 Oct". */
    fun waistOn(d: LocalDate, ref: LocalDate) = "$WAIST_SHORT · ${dMonRel(d, ref)}"
    /** "{±n} kg per month". */
    fun perMonth(v: Double) = "${signed(v, 1)} kg per month"
    /** "Target 78 kg" / "Target 15 %" / "Target 86 cm". */
    fun target(v: Double, unit: String, decimals: Int = 1) =
        "Target ${dec(v, if (R3Math.round(v, 0) == R3Math.round(v, decimals)) 0 else decimals)}${if (unit == "%") "$PCT_SP%" else " $unit"}"
    /** "{n} kg to target". */
    fun kgToTarget(v: Double) = "${dec(v, 1)} kg $TO_TARGET"
    /** Legend "Lean mass 62.1 kg". */
    fun legend(name: String, v: Double, unit: String) = "$name ${dec(v, 1)}${if (unit == "%") "$PCT_SP%" else " $unit"}"
    /** Table rows on 1Y / All. */
    fun rowMonth(ym: YearMonth) = monthName(ym)
    fun rowYear(y: Int) = "$y"

    // ================================================================================================ Vitals
    const val RESTING_HR = "Resting heart rate"
    const val HEART_RATE = "Heart rate"
    const val HRV = "HRV"
    const val BREATHING = "Breathing rate"
    const val SPO2 = "Blood oxygen"
    const val RESTING_HR_SHORT = "Resting HR"
    const val RHR = "RHR"
    const val DAILY_RANGE = "Daily range"
    const val WEEKLY_RANGE = "Weekly range"
    /** "Range today {lo}–{hi}". */
    fun rangeToday(lo: Double, hi: Double) = "Range today ${range(lo, hi)}"
    /** Lane / band key "{word} {lo}–{hi}" (word: "Oct", "Last week", "2025"). */
    fun keyRange(word: String, lo: Double, hi: Double, decimals: Int = 0) = "$word ${range(lo, hi, decimals)}"
    /** HR card context "{Mon} {lo}–{hi} bpm". */
    fun rangeBpm(word: String, lo: Double, hi: Double) = "$word ${range(lo, hi)} bpm"
    /** Range-row word per period: This week · Last week · {Month} · {Mon yyyy} · {d–d Mon} · {yyyy}. */
    fun rowMonthYear(d: LocalDate) = monYyyy(d)
    /** "vs {lo}–{hi}". */
    fun vsRange(lo: Double, hi: Double, decimals: Int = 0) = "vs ${range(lo, hi, decimals)}"
    /** "vs Wed". */
    fun vsDdd(d: LocalDate) = "vs ${ddd(d)}"
    /** Range words: "last week's range" / "Oct range" / "2025 range". */
    fun rangeWord(p: SectionPeriod): String? = when (p) {
        is SectionPeriod.Week -> "last week's range"
        is SectionPeriod.Month, is SectionPeriod.Year -> ghostWordShort(p)?.let { "$it range" }
        else -> null
    }
    /** Vitals pill: "1 day above Oct range · 2 days below Oct range" / "Within Oct range". */
    fun outsidePill(above: Int, below: Int, p: SectionPeriod): String? {
        val w = rangeWord(p) ?: return null
        if (above == 0 && below == 0) return "Within $w"
        return listOfNotNull(
            above.takeIf { it > 0 }?.let { "${days(it)} above $w" },
            below.takeIf { it > 0 }?.let { "${days(it)} below $w" },
        ).joinToString(" · ")
    }
    /** Outside tile label: "Outside Oct range" / "Outside last week" / "Outside 2025 range". */
    fun outsideLabel(p: SectionPeriod): String? = when (p) {
        is SectionPeriod.Week -> "Outside last week"
        is SectionPeriod.Month, is SectionPeriod.Year -> ghostWordShort(p)?.let { "Outside $it range" }
        else -> null
    }
    const val LOWEST_RHR_SINCE = "Lowest RHR since"
    const val HIGHEST_RHR_SINCE = "Highest RHR since"
    const val HIGHEST_HRV_SINCE = "Highest HRV since"
    const val LOWEST_HRV_SINCE = "Lowest HRV since"
    const val LOWEST_RHR = "Lowest RHR"
    const val HIGHEST_RHR = "Highest RHR"
    const val HIGHEST_HRV = "Highest HRV"
    const val LOWEST_HRV = "Lowest HRV"
    /** "{n} {unit}" with the metric's decimals. */
    fun withUnit(v: Double, unit: String, decimals: Int = 0) = "${dec(v, decimals)}${if (unit == "%") "$PCT_SP%" else if (unit.isEmpty()) "" else " $unit"}"
    /** Signed tile value "+3 bpm" / "−0.4 /min" / "Same". */
    fun signedUnit(v: Double, unit: String, decimals: Int = 0): String =
        if (R3Math.round(v, decimals) == 0.0) SAME else signed(v, decimals) + if (unit == "%") "$PCT_SP%" else if (unit.isEmpty()) "" else " $unit"

    /** Extreme with its date "{n} {unit}" + "{d Mon yyyy}". */
    fun extremeDate(e: Extreme) = dMonYyyy(e.day)
}
