package com.boudhealth.ui.activity

import androidx.compose.ui.graphics.Color
import com.boudhealth.data.R3Math
import com.boudhealth.data.SectionPeriod
import com.boudhealth.data.TileIcon
import com.boudhealth.data.Tone
import com.boudhealth.data.TotalChange
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.section.MicroVisual
import com.boudhealth.ui.section.R3Texts
import com.boudhealth.ui.section.SectionContext
import com.boudhealth.ui.section.SectionHooks
import com.boudhealth.ui.section.TrendTileModel
import com.boudhealth.ui.section.TrendsModel
import com.boudhealth.ui.theme.HB

// =====================================================================================================================
// R3 Activity Trends strip (IMPL_PLAN §3A "Trends", D4-A; port of act.py trend_tiles + basis_full). Always 6 tiles, fixed
// slots per period; the comparison basis once in the header. Numbers from the ActR3 model (the same TotalChange objects
// as the card pills, the same week cell as the "/ week" column and the Heart points rail). No averages, no sentences.
// =====================================================================================================================

/** Tile colours from the theme (ring palette + category colours). */
private class TileColors(hb: HB) {
    val workout: Color = ActivityColors.workout(hb)
    val steps: Color = ActivityColors.steps(hb)
    val heart: Color = ActivityColors.heart(hb)
    val sleep: Color = ActivityColors.sleep(hb)
    val vo2: Color = ActivityColors.vo2(hb)
}

/** SectionSpec.trends: the 6 tiles of the effective period (a selected day gives the 1D tiles). */
internal fun activityTrends(ctx: SectionContext, hb: HB): TrendsModel? {
    val m = ctx.act() ?: return null
    val c = TileColors(hb)
    val tiles = when (m.p) {
        is SectionPeriod.Day -> listOf(goals(m, c), totalTile(ctx, m.workoutMin.change, R3Texts.WORKOUT, "exercise_min", c.workout, Glyph.Stopwatch, "min"),
            totalTile(ctx, m.steps.change, R3Texts.STEPS, "steps", c.steps, Glyph.Steps, null),
            totalTile(ctx, m.heart.change, R3Texts.HEART_PTS, "zone_min", c.heart, Glyph.Heart, null),
            peakHour(ctx, m, c), streakDay(ctx, m, c))
        is SectionPeriod.Week -> listOf(goals(m, c), totalTile(ctx, m.workoutMin.change, R3Texts.WORKOUT, "exercise_min", c.workout, Glyph.Stopwatch, "min"),
            totalTile(ctx, m.steps.change, R3Texts.STEPS, "steps", c.steps, Glyph.Steps, null),
            heartWeek(ctx, m, c), bedtimes(ctx, m, c), workoutDays(ctx, m, c))
        is SectionPeriod.Month -> listOf(goals(m, c), totalTile(ctx, m.workoutMin.change, R3Texts.WORKOUT, "exercise_min", c.workout, Glyph.Stopwatch, "min"),
            totalTile(ctx, m.steps.change, R3Texts.STEPS, "steps", c.steps, Glyph.Steps, null),
            weeksTarget(ctx, m, c), bedtimes(ctx, m, c), workoutDays(ctx, m, c))
        is SectionPeriod.Year -> listOf(goals(m, c), totalTile(ctx, m.workoutMin.change, R3Texts.WORKOUT, "exercise_min", c.workout, Glyph.Stopwatch, "min"),
            totalTile(ctx, m.steps.change, R3Texts.STEPS, "steps", c.steps, Glyph.Steps, null),
            weeksTarget(ctx, m, c), vo2(ctx, m, c), workoutDays(ctx, m, c))
        is SectionPeriod.All -> allTime(ctx, m, c)
    }
    return TrendsModel(R3Texts.basis(m.window, m.today), tiles)
}

private fun tone(good: Boolean?): Tone = when (good) { true -> Tone.GOOD; false -> Tone.BAD; null -> Tone.NEUTRAL }

/** A missing fact keeps its slot: the metric glyph and "—" (IMPL_PLAN §5.6). */
private fun missing(label: String, glyph: Glyph, onClick: (() -> Unit)?) =
    TrendTileModel(TileIcon.GLYPH, Tone.NEUTRAL, label, R3Texts.DASH, glyph = glyph, a11y = R3Texts.a11yTile(TileIcon.GLYPH, label, R3Texts.DASH), onClick = onClick)

private fun tile(icon: TileIcon, tone: Tone, label: String, value: String, visual: MicroVisual?, onClick: (() -> Unit)?, glyph: Glyph? = null) =
    TrendTileModel(icon, tone, label, value, visual = visual, glyph = glyph, a11y = R3Texts.a11yTile(icon, label, value), onClick = onClick)

/** T1 Goals "{n} of 3": 3 bars (fraction of each goal) with the expected-by-now tick while running. */
private fun goals(m: ActR3, c: TileColors): TrendTileModel {
    val g = m.goals ?: return missing(R3Texts.GOALS, Glyph.Rings, null)
    val rs = m.rings!!.rings
    val cols = listOf(c.workout, c.steps, c.heart)
    val items = rs.mapIndexed { i, r -> Triple(r.progress.toDouble(), m.share, cols.getOrElse(i) { c.workout }) }
    return tile(g.icon, g.tone, R3Texts.GOALS, R3Texts.nOf3(g.count), MicroVisual.Goals(items), null)
}

/** T2–T4 (1D: absolute "−30 min" / "−5,293"; 1W–1Y: "+12 %") with this vs previous bars. */
private fun totalTile(ctx: SectionContext, ch: TotalChange?, label: String, metricId: String, color: Color, glyph: Glyph, unit: String?): TrendTileModel {
    val open = { ctx.openMetric(metricId) }
    if (ch == null) return missing(label, glyph, open)
    return tile(R3Math.iconOf(ch.dir), tone(ch.good), label, R3Texts.tileChange(ch, unit), MicroVisual.Cmp(ch.current, ch.previous, color), open)
}

/** 1D T5 "Peak hour · {hh}:00 · {n} steps" + the 24 hourly bars, the peak highlighted. */
private fun peakHour(ctx: SectionContext, m: ActR3, c: TileColors): TrendTileModel {
    val open = { ctx.openMetric("steps") }
    val pk = m.peak ?: return missing(R3Texts.PEAK_HOUR, Glyph.Steps, open)
    return tile(TileIcon.SAME, Tone.NEUTRAL, R3Texts.PEAK_HOUR, R3Texts.peakHourSteps(pk.hour, pk.value),
        MicroVisual.MiniBars(m.hourlySteps, pk.hour.takeIf { it in m.hourlySteps.indices }, c.steps), open)
}

/** 1D T6 "Streak · {n} · Best {n}": 14 workout-day dots; at risk (today, no workout yet) ▼ BAD + "No workout yet today". */
private fun streakDay(ctx: SectionContext, m: ActR3, c: TileColors): TrendTileModel {
    val open = { ctx.openMetric("streak") }
    val v = R3Texts.streakValue(m.streak.current, m.streak.best)
    if (m.atRisk) return tile(TileIcon.DOWN, Tone.BAD, R3Texts.STREAK, v, MicroVisual.Note(R3Texts.NO_WORKOUT_YET, Tone.BAD), open)
    val wd = m.streak.dayIsWorkout
    return tile(if (wd) TileIcon.DONE else TileIcon.DOWN, if (wd) Tone.GOOD else Tone.BAD, R3Texts.STREAK, v,
        MicroVisual.Dots(m.streakFlags, 0, c.workout), open)
}

/** 1W T4 "Heart pts / week · {n} / 350" + pace bar (the same cell as the "/ week" column). */
private fun heartWeek(ctx: SectionContext, m: ActR3, c: TileColors): TrendTileModel {
    val open = { ctx.openMetric("zone_min") }
    val w = m.heart.week ?: return missing(R3Texts.HEART_PTS_WEEK, Glyph.Heart, open)
    val share = m.share?.takeIf { it < 1.0 }
    return tile(w.icon, w.tone, R3Texts.HEART_PTS_WEEK, R3Texts.ofGoal(w.points.toDouble(), w.target.toDouble()),
        MicroVisual.Pace(w.points.toDouble(), w.target.toDouble(), share, c.heart), open)
}

/**
 * 1M / 1Y T4 "Heart pts target · {n} of {m} weeks": complete weeks met, the running week an open square. 1M: ✓ all met ·
 * ▼ some missed · ● no complete week yet. 1Y: ▲ when at least half met, else ▼, neutral tone.
 */
private fun weeksTarget(ctx: SectionContext, m: ActR3, c: TileColors): TrendTileModel {
    val open = { ctx.openMetric("zone_min") }
    val n = m.weeksMet.size; val met = m.weeksMet.count { it.met }
    val (icon, tone) = if (m.p is SectionPeriod.Year) {
        (if (n > 0 && met * 2 >= n) TileIcon.UP else TileIcon.DOWN) to Tone.NEUTRAL
    } else when {
        n == 0 -> TileIcon.SAME to Tone.NEUTRAL
        met == n -> TileIcon.DONE to Tone.GOOD
        else -> TileIcon.DOWN to Tone.BAD
    }
    return tile(icon, tone, R3Texts.HEART_PTS_TARGET, R3Texts.weeksOf(met, n), MicroVisual.Ticks(m.weeksMet.map { it.met }, m.running, c.heart), open)
}

/** 1W / 1M T5 "Bedtimes · {hh:mm}–{hh:mm}" ● on an 18:00–06:00 axis (bar 20:30–00:30 region, act.py mv_range 150..390). */
private fun bedtimes(ctx: SectionContext, m: ActR3, c: TileColors): TrendTileModel {
    val open = { SectionHooks.openSleep(ctx.nav, ctx.period, null) }
    val b = m.sleep.bedtimes ?: return missing(R3Texts.BEDTIMES, Glyph.Moon, open)
    val lo = R3Math.minutesAfter18(b.earliest).toDouble(); val hi = R3Math.minutesAfter18(b.latest).toDouble()
    return tile(TileIcon.SAME, Tone.NEUTRAL, R3Texts.BEDTIMES, R3Texts.timeRange(b.earliest, b.latest),
        MicroVisual.Span(lo, hi, minOf(150.0, lo), maxOf(390.0, hi), c.sleep), open)
}

/** T6 "Workout days": 1W "{n} / {goal}" (pace) + dots with the days to come; 1M "{n}" vs the previous window + dots; 1Y per month. */
private fun workoutDays(ctx: SectionContext, m: ActR3, c: TileColors): TrendTileModel {
    val open = { ctx.openMetric("streak") }
    val s = m.streak
    val st = m.workoutDaysStatus ?: Status(TileIcon.SAME, Tone.NEUTRAL)
    return when (m.p) {
        is SectionPeriod.Week -> {
            val elapsed = s.dots.filter { !it.first.isAfter(m.today) }
            tile(st.icon, st.tone, R3Texts.WORKOUT_DAYS, R3Texts.ofGoal(s.days.toDouble(), (s.goal ?: 0).toDouble()),
                MicroVisual.Dots(elapsed.map { it.second }, s.dots.size - elapsed.size, c.workout), open)
        }
        is SectionPeriod.Year -> tile(st.icon, st.tone, R3Texts.WORKOUT_DAYS, R3Texts.int(s.days.toDouble()),
            MicroVisual.MiniBars(s.perMonth.map { it.second }, null, c.workout, baseIsColor = true), open)
        else -> {
            val elapsed = s.dots.filter { !it.first.isAfter(m.today) }
            tile(st.icon, st.tone, R3Texts.WORKOUT_DAYS, R3Texts.int(s.days.toDouble()),
                MicroVisual.Dots(elapsed.map { it.second }, s.dots.size - elapsed.size, c.workout), open)
        }
    }
}

/** 1Y T5 "VO₂ max · {±n}" since the first reading of the year + spark of the monthly last readings. */
private fun vo2(ctx: SectionContext, m: ActR3, c: TileColors): TrendTileModel {
    val open = { ctx.openMetric("vo2") }
    val r = m.vo2.tile
    val d = r?.diff ?: return missing(R3Texts.VO2, Glyph.Vo2, open)
    val ref = r.reference!!.day
    val caption = if (m.p is SectionPeriod.All) R3Texts.since(ref) else R3Texts.sinceDay(ref)
    return tile(R3Math.iconOf(r.dir), tone(r.good), R3Texts.VO2, R3Texts.signed(d, 1),
        MicroVisual.Caption(caption, MicroVisual.Spark(m.vo2.spark, c.vo2)), open)
}

/** All: Workouts · Best year · Best month · Best week · VO₂ max · Best streak (records ▲ GOOD). */
private fun allTime(ctx: SectionContext, m: ActR3, c: TileColors): List<TrendTileModel> {
    val a = m.allTime ?: return List(6) { missing(R3Texts.WORKOUTS, Glyph.Run, null) }
    val workouts = tile(TileIcon.SAME, Tone.NEUTRAL, R3Texts.WORKOUTS, R3Texts.int(a.sessions.toDouble()),
        MicroVisual.Caption(R3Texts.since(a.firstDay), MicroVisual.MiniBars(a.sessionsPerMonth, null, c.workout, baseIsColor = true)),
        { SectionHooks.openWorkouts(ctx.nav, ctx.period) })
    val bestYear = a.bestYear?.let { b ->
        tile(TileIcon.UP, Tone.GOOD, R3Texts.BEST_YEAR, R3Texts.hWorkouts(b.value / 60.0),
            MicroVisual.Caption(R3Texts.yyyy(b.start), MicroVisual.MiniBars(a.yearHours, a.bestYearIndex, c.workout)), { ctx.openMetric("exercise_min") })
    } ?: missing(R3Texts.BEST_YEAR, Glyph.Stopwatch, null)
    val bestMonth = a.bestMonth?.let { b ->
        tile(TileIcon.UP, Tone.GOOD, R3Texts.BEST_MONTH, R3Texts.kSteps(b.value),
            MicroVisual.Caption(R3Texts.monYyyy(b.start), MicroVisual.MiniBars(a.monthSteps, a.bestMonthIndex, c.steps)), { ctx.openMetric("steps") })
    } ?: missing(R3Texts.BEST_MONTH, Glyph.Steps, null)
    val bestWeek = a.bestWeek?.let { b ->
        tile(TileIcon.UP, Tone.GOOD, R3Texts.BEST_WEEK, R3Texts.heartPoints(b.value),
            MicroVisual.Caption(R3Texts.dMonYyyy(b.start), MicroVisual.MiniBars(a.weekPoints, a.bestWeekIndex, c.heart)), { ctx.openMetric("zone_min") })
    } ?: missing(R3Texts.BEST_WEEK, Glyph.Heart, null)
    val streak = tile(TileIcon.UP, Tone.GOOD, R3Texts.BEST_STREAK, R3Texts.days(m.streak.best),
        MicroVisual.Cmp(m.streak.current.toDouble(), m.streak.best.toDouble(), c.workout), { ctx.openMetric("streak") })
    return listOf(workouts, bestYear, bestMonth, bestWeek, vo2(ctx, m, c), streak)
}
