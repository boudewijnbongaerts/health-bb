package com.boudhealth.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/*
 * V2 status lines (D7, V2_PLAN §2.2, V2-044). Pure, JVM-tested (InsightsTest). At most 2 lines, facts only; templates
 * verbatim from V2_TEXTS.md §1 (Insight.id = template id). Priority: a ring metric behind → streak at risk →
 * done / ahead → info. A selected day counts as a past day (unless it is today).
 */
object Insights {
    const val MAX = 2

    // priorities (lower = first)
    private const val P_BEHIND = 10
    private const val P_STREAK = 20
    private const val P_DONE = 30
    private const val P_INFO = 40

    /** Goal names in sentences (V2_TEXTS §1). */
    fun goalName(metricId: String): String = when (metricId) {
        "exercise_min" -> "workout minutes"; "steps" -> "step"; "zone_min" -> "heart points"; "week_workouts" -> "workout days"
        else -> metricId
    }

    /** "Behind on {goal}" uses the plural noun: workout minutes · steps · heart points. */
    private fun goalNoun(metricId: String): String = when (metricId) { "steps" -> "steps"; else -> goalName(metricId) }

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    fun metricName(metricId: String): String = when (metricId) {
        "rhr" -> "Resting heart rate"; "hr" -> "Heart rate"; "hrv" -> "HRV"; "breathing" -> "Breathing rate"; "spo2" -> "Blood oxygen"
        "weight" -> "Weight"; "body_fat" -> "Body fat percentage"; "waist" -> "Waist circumference"; "lean_mass" -> "Lean mass"; "bmi" -> "BMI"
        else -> metricId
    }

    /** Period word (V2_TEXTS §1): today · this week · this month · this year · last week · last month · {month} · {year}. */
    fun periodWord(p: SectionPeriod, today: LocalDate): String {
        val cur = p.isCurrent(today)
        return when (p) {
            is SectionPeriod.Day -> if (cur) "today" else dayLabel(p.start)
            is SectionPeriod.Week -> when {
                cur -> "this week"
                p == SectionPeriod.of(PeriodUnit.WEEK, today, today).shift(-1) -> "last week"
                else -> weekLabel(p)
            }
            is SectionPeriod.Month -> when {
                cur -> "this month"
                p == SectionPeriod.of(PeriodUnit.MONTH, today, today).shift(-1) -> "last month"
                else -> p.start.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + if (p.year != today.year) " ${p.year}" else ""
            }
            is SectionPeriod.Year -> if (cur) "this year" else p.year.toString()
            is SectionPeriod.All -> "all time"
        }
    }

    /** "Tue 14 Oct" (the period bar label, §2.2). */
    fun dayLabel(d: LocalDate): String = "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"

    /** "2–8 Nov" / "27 Oct – 2 Nov". */
    fun weekLabel(p: SectionPeriod): String {
        val a = p.start; val b = p.last
        val ma = a.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH); val mb = b.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        return if (a.month == b.month) "${a.dayOfMonth}–${b.dayOfMonth} $mb" else "${a.dayOfMonth} $ma – ${b.dayOfMonth} $mb"
    }

    private fun n(v: Double): String = V2Math.intText(v)
    private fun dec(v: Double): String = String.format(Locale.US, "%.1f", v)

    /**
     * Builds the lines for [section] at [p] (= scope.effective). [cards] from the bundle, [rings] Activity rings,
     * [streak] current streak as of today (Activity), [todayIsWorkoutDay].
     */
    fun build(section: SectionId, p: SectionPeriod, now: LocalDateTime, cards: Map<String, CardData>, rings: RingSet?,
              goals: Goals, streak: Int = 0, todayIsWorkoutDay: Boolean = true): List<Insight> {
        val today = now.toLocalDate()
        val out = when (section) {
            SectionId.ACTIVITY -> activity(p, now, cards, rings, streak, todayIsWorkoutDay)
            SectionId.BODY -> body(p, today, cards, goals)
            SectionId.VITALS -> vitals(p, today, cards)
        }
        return out.sortedBy { it.priority }.take(MAX)
    }

    private fun activity(p: SectionPeriod, now: LocalDateTime, cards: Map<String, CardData>, rings: RingSet?, streak: Int, todayIsWorkoutDay: Boolean): List<Insight> {
        val today = now.toLocalDate()
        val out = ArrayList<Insight>()
        val rs = rings?.rings.orEmpty().filter { it.goal != null && it.goal > 0 }
        if (rs.isEmpty()) return out
        if (p.isCurrent(today)) {
            if (p is SectionPeriod.Day && rs.all { it.closed }) {
                out += Insight("st_all_done_today", null, InsightTone.DONE, "All three goals met today", P_DONE)
            } else {
                val f = Pace.fraction(p, now)
                var allOk = true
                for (r in rs) {
                    when (Pace.status(r.value, r.goal!!, f)) {
                        CardioPace.BEHIND -> {
                            allOk = false
                            out += if (p is SectionPeriod.Day)
                                Insight("st_behind_goal", r.metricId, InsightTone.BEHIND, "Behind on ${goalNoun(r.metricId)}: ${n(r.value)} of ${n(r.goal)}", P_BEHIND)
                            else Insight("st_behind_period", r.metricId, InsightTone.BEHIND, "${cap(periodWord(p, today))} you are behind on your ${goalName(r.metricId)} goal", P_BEHIND)
                        }
                        CardioPace.DONE -> out += Insight("st_goal_done", r.metricId, InsightTone.DONE, "${cap(goalName(r.metricId))} goal met", P_DONE + 1)
                        CardioPace.AHEAD -> out += Insight("st_ahead", r.metricId, InsightTone.AHEAD, "Ahead on ${goalNoun(r.metricId)}: ${n(r.value)} of ${n(r.goal)}", P_DONE + 2)
                        else -> Unit
                    }
                }
                if (allOk) out += Insight(if (p is SectionPeriod.Day) "st_on_track_today" else "st_on_track_period", null, InsightTone.ON_TRACK,
                    if (p is SectionPeriod.Day) "Today you are on track" else "${cap(periodWord(p, today))} you are on track", P_DONE)
            }
            if (streak > 0 && !todayIsWorkoutDay && (p is SectionPeriod.Day || p is SectionPeriod.Week))
                out += Insight("st_streak_risk", "streak", InsightTone.BEHIND, "Streak of $streak days: no workout yet today", P_STREAK)
        } else if (!p.isFuture(today)) {
            val met = rs.count { it.closed }
            out += Insight("st_past_goals", null, if (met == rs.size) InsightTone.DONE else InsightTone.INFO,
                "${cap(periodWord(p, today))}: $met of ${rs.size} goals met", P_DONE)
            // per-day goal (DAY grain only): "Step goal met on 18 of 30 days" for the ring with the fewest days met
            if (p is SectionPeriod.Week || p is SectionPeriod.Month) {
                val best = PeriodRing.ORDER.mapNotNull { id ->
                    val s = cards[id]?.series ?: return@mapNotNull null
                    val g = s.goalPerBucket ?: return@mapNotNull null
                    val days = s.buckets.filter { !it.future }
                    if (days.isEmpty()) null else Triple(id, days.count { (it.value ?: 0.0) >= g }, days.size)
                }.minByOrNull { it.second.toDouble() / it.third }
                if (best != null) out += Insight("st_past_days", best.first, InsightTone.INFO,
                    "${cap(goalName(best.first))} goal met on ${best.second} of ${best.third} days", P_INFO)
            }
        }
        return out
    }

    private fun body(p: SectionPeriod, today: LocalDate, cards: Map<String, CardData>, goals: Goals): List<Insight> {
        val out = ArrayList<Insight>()
        val w = cards["weight"]
        val t = w?.trend
        if (t != null && t.change != null && t.dir != TrendDir.FLAT) {
            // the icon (▲/▼ from [Insight.dir]) is drawn by the UI; the text has no arrow (QA #8)
            val kg = dec(Math.abs(t.change))
            val line: Insight? = when {
                // QA 08.5: 1D = vs the reading before ("2.2 kg since 29 Oct")
                p is SectionPeriod.Day -> t.previousDay?.let { Insight("st_body_since", "weight", InsightTone.INFO, "$kg kg since ${shortDate(it, today)}", P_DONE, t.dir, t.good) }
                // QA 12.1: All = first vs last reading ("{n} kg since {Mon yyyy}")
                p is SectionPeriod.All -> t.previousDay?.let { Insight("st_body_lifetime", "weight", InsightTone.INFO, "$kg kg since ${monthYear(it)}", P_DONE, t.dir, t.good) }
                else -> Insight("st_body_change", "weight", InsightTone.INFO, "$kg kg ${periodWord(p, today)}", P_DONE, t.dir, t.good)
            }
            if (line != null) out += line
        }
        val target = goals.targetWeightKg
        val cur = w?.value?.value
        if (target != null && cur != null) {
            val gap = Math.abs(cur - target)
            out += if (gap < 0.05) Insight("st_body_target_met", "weight", InsightTone.DONE, "Target reached", P_INFO)
            else Insight("st_body_target", "weight", InsightTone.INFO, "${dec(gap)} kg to target", P_INFO)
        }
        return out
    }

    /** "29 Oct" (with the year when not this year's). */
    fun shortDate(d: LocalDate, today: LocalDate = LocalDate.now()): String =
        "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}" + if (d.year != today.year) " ${d.year}" else ""

    /** "{Mon yyyy}", e.g. "Mar 2024". */
    fun monthYear(d: LocalDate): String = "${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${d.year}"

    private fun vitals(p: SectionPeriod, today: LocalDate, cards: Map<String, CardData>): List<Insight> {
        val out = ArrayList<Insight>()
        if (p is SectionPeriod.All) {
            // QA 18.1: lifetime line = the latest resting heart rate with its date ("Resting heart rate 59 bpm on 31 Oct")
            val v = cards["rhr"]?.value
            if (v?.value != null && v.at != null)
                out += Insight("st_vitals_latest", "rhr", InsightTone.INFO, "${metricName("rhr")} ${n(v.value)} bpm on ${shortDate(v.at, today)}", P_INFO)
            return out
        }
        val prevWord = when (p) { is SectionPeriod.Day -> "day"; is SectionPeriod.Week -> "week"; is SectionPeriod.Month -> "month"; else -> "year" }
        for ((i, id) in listOf("rhr", "hrv", "breathing", "spo2").withIndex()) {
            val t = cards[id]?.trend ?: continue
            if (t.dir == TrendDir.FLAT || t.current == null || t.previous == null) continue
            val d = Math.abs(t.current - t.previous)
            val unit = V2Metrics.get(id)?.unit ?: ""
            val num = if (id == "breathing") dec(d) else n(d)
            if (num == "0" || num == "0.0") continue
            val word = if (t.dir == TrendDir.DOWN) "lower" else "higher"
            val than = if (p is SectionPeriod.Day) "than the day before" else "than last $prevWord"
            out += Insight("st_vitals_change", id, InsightTone.INFO, "${metricName(id)} $num $unit $word $than".replace("  ", " "), P_INFO + i, t.dir, t.good)
        }
        return out
    }
}
