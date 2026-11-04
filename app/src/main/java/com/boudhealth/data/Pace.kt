package com.boudhealth.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/*
 * V2 pace (IC9, replaces ui/home WeeklyPace and data CardioPaceMath for new code). Pure, JVM-tested (PaceTest).
 * V2_PLAN §2.2: expected = target × elapsed share. 1D: share of 07:00–22:00 · longer: elapsed days, today pro rata.
 * done ≥ 100 % of target · ahead ≥ 110 % of expected · on track 90–110 % · behind < 90 %.
 */
object Pace {
    const val DAY_START_H = 7
    const val DAY_END_H = 22
    const val AHEAD = 1.10
    const val BEHIND = 0.90

    /** Share of one day's active time (07:00–22:00) gone at [now]. */
    fun dayShare(now: LocalDateTime): Double {
        val h = now.hour + now.minute / 60.0 + now.second / 3600.0
        return ((h - DAY_START_H) / (DAY_END_H - DAY_START_H)).coerceIn(0.0, 1.0)
    }

    /** Share of [p] gone at [now]: 0 before it, 1 after it; inside: (full days before today + today's share) ÷ days. */
    fun fraction(p: SectionPeriod, now: LocalDateTime): Double {
        val today = now.toLocalDate()
        if (today.isBefore(p.start)) return 0.0
        if (!today.isBefore(p.endExclusive)) return 1.0
        val before = ChronoUnit.DAYS.between(p.start, today).toDouble()
        return ((before + dayShare(now)) / p.totalDays()).coerceIn(0.0, 1.0)
    }

    /** Week fraction from its Monday (drop-in for WeeklyPace.fraction / CardioPaceMath.fraction). */
    fun weekFraction(weekStart: LocalDate, now: LocalDateTime): Double = fraction(SectionPeriod.Week(weekStart.toEpochDay()), now)

    fun expected(goal: Double, fraction: Double): Double = goal * fraction

    /**
     * DONE ≥ goal · AHEAD ≥ 110 % of expected · ON_TRACK 90–110 % · BEHIND < 90 %. NO_DATA without a value or a goal.
     * Before the active time starts (expected 0) anything is ON_TRACK.
     */
    fun status(actual: Double?, goal: Double, fraction: Double): CardioPace {
        val a = actual ?: return CardioPace.NO_DATA
        if (goal <= 0.0) return CardioPace.NO_DATA
        if (a >= goal) return CardioPace.DONE
        val e = expected(goal, fraction)
        if (e <= 0.0) return CardioPace.ON_TRACK
        val r = a / e
        return when {
            r >= AHEAD -> CardioPace.AHEAD
            r < BEHIND -> CardioPace.BEHIND
            else -> CardioPace.ON_TRACK
        }
    }

    /** Full pace record for a metric with a period goal (null when there is no goal). */
    fun of(metricId: String, actual: Double?, goal: PeriodGoal?, p: SectionPeriod, now: LocalDateTime): PeriodPace? {
        val g = goal?.value ?: return null
        if (g <= 0.0) return null
        val f = fraction(p, now)
        val a = actual ?: 0.0
        return PeriodPace(metricId, status(actual, g, f), a, expected(g, f), g, (g - a).coerceAtLeast(0.0), f)
    }

    /** Icon only (v5): ▲ ahead · ● on track · ▼ behind · ✓ done; "–" no data. */
    fun icon(s: CardioPace): String = when (s) {
        CardioPace.NO_DATA -> "–"; CardioPace.DONE -> "✓"; CardioPace.AHEAD -> "▲"; CardioPace.BEHIND -> "▼"
        CardioPace.ON_TRACK, CardioPace.NEW_WEEK -> "●"
    }
}
