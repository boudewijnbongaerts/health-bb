package com.boudhealth.ui.activity

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.data.Grain
import com.boudhealth.data.SectionPeriod
import com.boudhealth.data.SeriesBucket
import com.boudhealth.data.SleepNight
import com.boudhealth.data.SleepStage
import com.boudhealth.data.TotalChange
import com.boudhealth.data.TrendDir
import com.boudhealth.data.V2Metrics
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.GlyphCircle
import com.boudhealth.ui.components.GlyphIcon
import com.boudhealth.ui.components.drawGlyph
import com.boudhealth.ui.components.niceTicks
import com.boudhealth.ui.components.plainClickable
import com.boudhealth.ui.health.metricColor
import com.boudhealth.ui.health.vo2Scale
import com.boudhealth.ui.nav.Route
import com.boudhealth.ui.section.CardSize
import com.boudhealth.ui.section.CardSpec
import com.boudhealth.ui.section.CoverStatRow
import com.boudhealth.ui.section.DeltaPillModel
import com.boudhealth.ui.section.Ghost
import com.boudhealth.ui.section.GhostKey
import com.boudhealth.ui.section.GhostKeyModel
import com.boudhealth.ui.section.GhostKind
import com.boudhealth.ui.section.GhostStyle
import com.boudhealth.ui.section.MRow
import com.boudhealth.ui.section.PeriodBars
import com.boudhealth.ui.section.R3
import com.boudhealth.ui.section.R3CardFrame
import com.boudhealth.ui.section.R3StatusIcon
import com.boudhealth.ui.section.R3Texts
import com.boudhealth.ui.section.RailCells
import com.boudhealth.ui.section.SFmt
import com.boudhealth.ui.section.SectionContext
import com.boudhealth.ui.section.SectionHooks
import com.boudhealth.ui.section.SegPill
import com.boudhealth.ui.section.StatCell
import com.boudhealth.ui.section.Tone
import com.boudhealth.ui.section.ValueBlock
import com.boudhealth.ui.section.ValueSize
import com.boudhealth.ui.section.axisLabel
import com.boudhealth.ui.section.faint
import com.boudhealth.ui.section.isTodayBucket
import com.boudhealth.ui.theme.LocalHB
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

// =====================================================================================================================
// R3 Activity cards (IMPL_PLAN §3A "Cards", port of act.py c_total_bars / c_workouts / c_hp / c_sleep / c_streak / c_vo2 /
// c_all_values). Inner rows (288 dp): [Workout minutes M][Workouts S] · [Steps S][Heart points M] · [Sleep M][Streak S] ·
// [Calories S][VO₂ max M] (1D: [Calories S][VO₂ max S][All values S]). Cover order: Workout minutes · Workouts · Steps ·
// Heart points · Streak · Calories · Sleep · VO₂ max · (1D) All values — Sleep is listed twice with a `shown` filter.
// Card geometry (card top = 0): header 52 · value text line S 86 / M 90 / cover 82 · context +22 · pill +44 · chart from
// value line + 62 (+ 2) to the bottom − 14; M cards: chart width = card − 236, rail 188 from card top 18 to bottom − 18.
// =====================================================================================================================

object ActivityR3Cards {
    val all: List<CardSpec> by lazy {
        listOf(
            CardSpec.Custom("exercise_min", size = CardSize.M) { ctx -> WorkoutMinutesCard(ctx) },
            CardSpec.Custom("workouts", size = CardSize.S) { ctx -> WorkoutsCard(ctx) },
            CardSpec.Custom("steps", size = CardSize.S) { ctx -> StepsCard(ctx) },
            CardSpec.Custom("zone_min", size = CardSize.M) { ctx -> HeartCard(ctx) },
            CardSpec.Custom("sleep", size = CardSize.M, shown = { it.wide }) { ctx -> SleepCardView(ctx) },
            CardSpec.Custom("streak", size = CardSize.S) { ctx -> StreakCardView(ctx) },
            CardSpec.Custom("calories", size = CardSize.S) { ctx -> CaloriesCard(ctx) },
            CardSpec.Custom("sleep_cover", size = CardSize.M, shown = { !it.wide }) { ctx -> SleepCardView(ctx) },
            CardSpec.Custom("vo2", size = CardSize.M, sizeFor = { if (it.period is SectionPeriod.Day) CardSize.S else CardSize.M }) { ctx -> Vo2CardView(ctx) },
            CardSpec.Custom("all_values", size = CardSize.S, shown = { it.period is SectionPeriod.Day }) { ctx -> AllValuesR3Card(ctx) },
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------- geometry

private val CARD_H = R3.CARD_H.value

/** Chart height under a value block (inner 288 card): bottom − 14 − (value line + 62 / + 42 without pill) − 2. */
private fun chartH(size: ValueSize, pill: Boolean): Dp = (CARD_H - 14f - (size.valueY.value + if (pill) 62f else 42f) - 2f).dp

/** "/ {goal}" (value block rest). */
private fun slashGoal(goal: Double): String = "/ ${R3Texts.int(goal)}"

private fun ghostKey(m: ActR3, has: Boolean): GhostKeyModel? =
    if (!has) null else R3Texts.ghostWord(m.p, m.today)?.let { GhostKeyModel(it, GhostKind.BAR) }

private fun pillOf(c: TotalChange?, unit: String?, today: LocalDate): DeltaPillModel? = c?.let { R3Texts.totalPill(it, unit, today) }

/** Bars of a total (hourly 1D · days W/M with the goal · months Y/All) with the previous period behind. */
@Composable
private fun TotalBars(ctx: SectionContext, m: ActR3, t: TotalCard, color: Color, height: Dp) {
    val d = ctx.card(t.metricId) ?: run { Spacer(Modifier.height(height)); return }
    val goalChart = m.p is SectionPeriod.Week || m.p is SectionPeriod.Month
    SeriesGraph(d, m.p, height) { s ->
        val goal = if (goalChart) s.goalPerBucket else null
        PeriodBars(s.buckets, s.grain, m.p, color, m.today, height = height, goal = goal,
            onTap = if (s.grain == Grain.HOUR) null else ({ b -> ctx.drill(b.day) }), unit = unitOf(t.metricId),
            ghost = t.ghost, goalTickBold = goal != null, tagHighest = true, dimBelowGoal = goal != null)
    }
}

// ---------------------------------------------------------------------------------------------------------------- totals

@Composable
private fun WorkoutMinutesCard(ctx: SectionContext) {
    val m = ctx.act() ?: return
    val hb = LocalHB.current
    val t = m.workoutMin
    val color = ActivityColors.workout(hb)
    val unit = unitOf("exercise_min")
    val pill = pillOf(t.change, unit, m.today)
    val context = when {
        m.p is SectionPeriod.Day -> if (m.sessionRows.isEmpty()) R3Texts.NO_WORKOUTS else t.peakHour?.let { R3Texts.peakHour(it) }
        m.p is SectionPeriod.All -> R3Texts.sinceCap(m.dataStart)
        else -> t.goalDays?.let { (n, d) -> R3Texts.goalMet("exercise_min", n, d) }
    }
    val goalTxt = t.goal?.let { slashGoal(it) }
    val key = ghostKey(m, t.ghost != null)
    val goalDay = ctx.card("exercise_min")?.series?.goalPerBucket ?: 0.0
    val lng = m.longest
    val longest = StatCell(R3Texts.LONGEST, lng?.let { R3Texts.dur(it.minutes.toDouble()) } ?: R3Texts.DASH,
        lng?.let { "· " + WorkoutUi.name(it) })
    val goalCell = StatCell(R3Texts.GOAL, R3Texts.int(goalDay), R3Texts.minPerDay(Math.round(goalDay).toInt()).substringAfter(' '))
    val first = if (m.p is SectionPeriod.Day) StatCell(R3Texts.WORKOUTS, R3Texts.int((m.workouts?.sessionCount ?: 0).toDouble()))
        else StatCell(R3Texts.BEST_DAY, t.bestDay?.let { R3Texts.int(it.value) } ?: R3Texts.DASH, t.bestDay?.let { "$unit · ${R3Texts.dMon(it.start)}" })
    R3CardFrame(R3Texts.WORKOUT_MINUTES, Glyph.Stopwatch, color, onClick = { ctx.openMetric("exercise_min") }) {
        if (ctx.wide) {
            MRow(left = {
                Column(Modifier.fillMaxSize()) {
                    ValueBlock(R3Texts.int(t.total), goalTxt, unit, context, pill, ValueSize.M, trailingKey = key)
                    Spacer(Modifier.height(2.dp))
                    TotalBars(ctx, m, t, color, chartH(ValueSize.M, pill != null))
                }
            }, rail = { RailCells(listOf(first, longest, goalCell), Modifier.fillMaxSize()) })
        } else {
            ValueBlock(R3Texts.int(t.total), goalTxt, unit, context, pill, ValueSize.COVER, trailingKey = key)
            Spacer(Modifier.height(2.dp))
            TotalBars(ctx, m, t, color, 120.dp)
            Spacer(Modifier.height(14.dp))
            CoverStatRow(listOf(StatCell(first.label, first.value, if (m.p is SectionPeriod.Day) null else unit),
                StatCell(R3Texts.LONGEST, longest.value), StatCell(R3Texts.GOAL, goalCell.value, unit)))
        }
    }
}

@Composable
private fun TotalSCard(ctx: SectionContext, m: ActR3, t: TotalCard, title: String, glyph: Glyph, color: Color, unit: String?, context: String?) {
    val pill = pillOf(t.change, unit, m.today)
    val key = ghostKey(m, t.ghost != null)
    val size = if (ctx.wide) ValueSize.S else ValueSize.COVER
    R3CardFrame(title, glyph, color, onClick = { ctx.openMetric(t.metricId) }) {
        ValueBlock(R3Texts.int(t.total), t.goal?.let { slashGoal(it) }, unit, context, pill, size, trailingKey = key)
        Spacer(Modifier.height(2.dp))
        TotalBars(ctx, m, t, color, if (ctx.wide) chartH(ValueSize.S, pill != null) else 104.dp)
    }
}

@Composable
private fun StepsCard(ctx: SectionContext) {
    val m = ctx.act() ?: return
    val t = m.steps
    val context = when (m.p) {
        is SectionPeriod.Day -> t.peakHour?.let { R3Texts.peakHour(it) }
        is SectionPeriod.All -> R3Texts.sinceCap(m.dataStart)
        else -> t.goalDays?.let { (n, d) -> R3Texts.goalMet("steps", n, d) }
    }
    TotalSCard(ctx, m, t, R3Texts.STEPS, Glyph.Steps, ActivityColors.steps(LocalHB.current), null, context)
}

@Composable
private fun CaloriesCard(ctx: SectionContext) {
    val m = ctx.act() ?: return
    val t = m.calories
    val context = when (m.p) {
        is SectionPeriod.Day -> t.peakHour?.let { R3Texts.peakHour(it) }
        else -> t.bestDay?.let { R3Texts.bestDayKcal(it.start, it.value) }
    }
    TotalSCard(ctx, m, t, R3Texts.CALORIES, Glyph.Flame, ActivityColors.calories(LocalHB.current), unitOf("calories"), context)
}

// ---------------------------------------------------------------------------------------------------------------- workouts

@Composable
private fun WorkoutsCard(ctx: SectionContext) {
    val m = ctx.act() ?: return
    val hb = LocalHB.current
    val pw = m.workouts
    val n = pw?.sessionCount ?: 0
    val cover = !ctx.wide
    val fs = if (cover) 30f else 32f
    val b0 = (if (cover) 82f else 86f) - 52f                // value text line inside the content
    R3CardFrame(R3Texts.WORKOUTS, Glyph.Run, ActivityColors.workout(hb), onClick = { SectionHooks.openWorkouts(ctx.nav, ctx.period) }) {
        if (n > 0) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(R3Texts.int(n.toDouble()), fontSize = fs.sp, fontWeight = FontWeight.Bold, color = hb.colors.text, maxLines = 1,
                    style = TextStyle(fontFeatureSettings = "tnum"), modifier = Modifier.paddingFromBaseline(top = b0.dp))
                Spacer(Modifier.width(14.dp))
                pw!!.types.take(2).forEach { (g, k) ->
                    Text(R3Texts.typeChip(g.label, k), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.text, maxLines = 1,
                        modifier = Modifier.padding(bottom = 7.dp).height(22.dp).background(hb.colors.tile, RoundedCornerShape(11.dp)).padding(horizontal = 9.dp, vertical = 3.dp))
                    Spacer(Modifier.width(6.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            m.sessionRows.forEach { r -> SessionRowView(ctx, r, withDate = m.p !is SectionPeriod.Day) }
            if (n > 3 || m.p !is SectionPeriod.Day) {
                if (ctx.wide) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(8.dp))
                Row(Modifier.align(Alignment.End).height(26.dp).plainClickable { SectionHooks.openWorkouts(ctx.nav, ctx.period) },
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(R3Texts.all(n), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.accent)
                    Spacer(Modifier.width(3.dp))
                    GlyphIcon(Glyph.ChevR, size = 13.dp, color = hb.colors.accent)
                }
            }
        } else {
            // empty day: the approved "No workouts", then the previous day's sessions faint
            Row(Modifier.fillMaxWidth().padding(top = (b0 - 27f).dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphCircle(Glyph.Run, hb.colors.muted, size = 30.dp, glyphSize = 16.dp, background = hb.colors.tile)
                Spacer(Modifier.width(10.dp))
                Text(R3Texts.NO_WORKOUTS, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.muted)
            }
            if (m.prevSessionRows.isNotEmpty()) {
                R3Texts.ghostWord(m.p, m.today)?.let { w ->
                    Text(w, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.muted, modifier = Modifier.paddingFromBaseline(top = 19.dp))
                }
                Spacer(Modifier.height(4.dp))
                Column(Modifier.alpha(0.55f)) { m.prevSessionRows.forEach { r -> SessionRowView(ctx, r, withDate = false) } }
            }
        }
    }
}

/** One session row (pitch 46): glyph circle 30 · name 13.5/700 + time 12 muted · "{n} min" 13.5/700 + "Avg HR {n}" 12 muted. */
@Composable
private fun SessionRowView(ctx: SectionContext, r: SessionRow, withDate: Boolean) {
    val hb = LocalHB.current
    val w = r.workout
    val time = if (withDate) R3Texts.sessionTime(r.day, r.start) else R3Texts.hhmm(r.start)
    Row(Modifier.fillMaxWidth().height(46.dp).plainClickable { ctx.nav.push(Route.Session(w.start)) }, verticalAlignment = Alignment.CenterVertically) {
        GlyphCircle(WorkoutUi.glyph(w), WorkoutUi.color(w), size = 30.dp, glyphSize = 16.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(WorkoutUi.name(w), fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = hb.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(time, fontSize = 12.sp, color = hb.colors.muted, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(R3Texts.minutes(w.minutes.toDouble()), fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = hb.colors.text, maxLines = 1)
            r.avgHr?.let { Text(R3Texts.avgHr(it), fontSize = 12.sp, color = hb.colors.muted, maxLines = 1) }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------- heart points

@Composable
private fun HeartCard(ctx: SectionContext) {
    val m = ctx.act() ?: return
    val hb = LocalHB.current
    val h = m.heart
    val color = ActivityColors.heart(hb)
    val pill = pillOf(h.change, null, m.today)
    val context = if (m.p is SectionPeriod.Week || m.p is SectionPeriod.Month) h.goalDays?.let { (n, d) -> R3Texts.goalMet("zone_min", n, d) } else null
    val key = ghostKey(m, h.ghost != null)
    val goalTxt = h.goal?.let { slashGoal(it) }
    val headerIcon: (@Composable () -> Unit)? = if (!ctx.wide && (m.p is SectionPeriod.Day || m.p is SectionPeriod.Week) && h.week != null) {
        { R3StatusIcon(h.week.icon, h.week.tone, 20.dp) }
    } else null
    R3CardFrame(R3Texts.HEART_POINTS, Glyph.Heart, color, info = ActivityTexts.INFO_HEART_POINTS, headerTrailing = headerIcon,
        onClick = { ctx.openMetric("zone_min") }) {
        if (ctx.wide) {
            MRow(left = {
                Column(Modifier.fillMaxSize()) {
                    ValueBlock(R3Texts.int(h.points), goalTxt, null, context, pill, ValueSize.M, trailingKey = key)
                    Spacer(Modifier.height(2.dp))
                    ZoneBars(ctx, m, chartH(ValueSize.M, pill != null))
                }
            }, rail = { ZoneRail(m) })
        } else {
            ValueBlock(R3Texts.int(h.points), goalTxt, null, context, pill, ValueSize.COVER, trailingKey = key)
            Spacer(Modifier.height(2.dp))
            ZoneBars(ctx, m, 120.dp)
            Spacer(Modifier.height(14.dp))
            CoverStatRow(R3Texts.ZONES.mapIndexed { i, z -> StatCell(z, R3Texts.int(h.zoneMinutes[i].toDouble()), unitOf("exercise_min")) })
        }
    }
}

/** Stacked zone POINTS per bucket (fat ×1, cardio ×2, peak ×2: bar totals = heart points) + the previous period. */
@Composable
private fun ZoneBars(ctx: SectionContext, m: ActR3, height: Dp) {
    val hb = LocalHB.current
    val z = m.heart.zones ?: run { Spacer(Modifier.height(height)); return }
    val buckets = remember(m) { z.perBucket.mapIndexed { i, b -> b.copy(segments = m.heart.pointSegments.getOrNull(i) ?: b.segments) } }
    PeriodBars(buckets, z.grain, m.p, ActivityColors.heart(hb), m.today, height = height, segmentColors = ActivityColors.zones(hb),
        onTap = if (z.grain == Grain.HOUR) null else ({ b -> ctx.drill(b.day) }), ghost = m.heart.ghost,
        tagHighest = m.p is SectionPeriod.Day || m.p is SectionPeriod.Week || m.p is SectionPeriod.Month)
}

/** act.py zone_rail: Fat burn · Cardio · Peak minutes (pitch 34), divider, "Heart points per week" + icon + "{n} / 350" + week. */
@Composable
private fun ZoneRail(m: ActR3) {
    val hb = LocalHB.current
    val cols = ActivityColors.zones(hb)
    val unit = unitOf("exercise_min")
    Column(Modifier.fillMaxSize().padding(top = 2.dp)) {
        R3Texts.ZONES.forEachIndexed { i, name ->
            if (i > 0) Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().height(28.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(cols[i], CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.text, modifier = Modifier.weight(1f), maxLines = 1)
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = hb.colors.text)) { append(R3Texts.int(m.heart.zoneMinutes[i].toDouble())) }
                    withStyle(SpanStyle(fontSize = 11.5.sp, color = hb.colors.muted)) { append(" $unit") }
                }, maxLines = 1, style = TextStyle(fontFeatureSettings = "tnum"))
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(hb.colors.grid))
        val w = m.heart.week ?: return@Column
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Text(R3Texts.HEART_POINTS_PER_WEEK, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.muted, maxLines = 1,
                modifier = Modifier.paddingFromBaseline(top = 24.dp))
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                R3StatusIcon(w.icon, w.tone, 18.dp)
                Spacer(Modifier.width(8.dp))
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = hb.colors.text)) { append(R3Texts.int(w.points.toDouble())) }
                    withStyle(SpanStyle(fontSize = 12.sp, color = hb.colors.muted)) { append(" ${slashGoal(w.target.toDouble())}") }
                }, maxLines = 1, style = TextStyle(fontFeatureSettings = "tnum"))
                Spacer(Modifier.weight(1f))
                Text(R3Texts.week(w.monday), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.muted, maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------- sleep

/** "7h 14m" → [("7","h"), ("14","m")] (number + muted unit letter, act.py c_sleep 1D value). */
private fun hmParts(minutes: Double): List<Pair<String, String>> =
    R3Texts.hm(minutes).split(' ').map { it.dropLast(1) to it.takeLast(1) }

private fun localTime(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime()

@Composable
private fun SleepCardView(ctx: SectionContext) {
    val m = ctx.act() ?: return
    val hb = LocalHB.current
    val s = m.sleep
    val color = ActivityColors.sleep(hb)
    val cover = !ctx.wide
    val open = { SectionHooks.openSleep(ctx.nav, ctx.period, (m.p as? SectionPeriod.Day)?.start) }
    R3CardFrame(R3Texts.SLEEP, Glyph.Moon, color, onClick = open) {
        val empty = if (m.p is SectionPeriod.Day) s.night == null else s.nights.isEmpty()
        if (empty) {
            Text(R3Texts.NO_SLEEP, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.muted, modifier = Modifier.paddingFromBaseline(top = 32.dp))
            return@R3CardFrame
        }
        if (m.p is SectionPeriod.Day) {
            val n = s.night!!
            val left: @Composable () -> Unit = { SleepDayLeft(n, s.stages, cover) }
            if (!cover) MRow(left = left, rail = { StageRail(n) })
            else {
                left()
                Spacer(Modifier.height(14.dp))
                CoverStatRow(stageMinutes(n).mapIndexed { i, v -> StatCell(R3Texts.STAGES[i], R3Texts.hm(v.toDouble())) })
            }
        } else {
            val left: @Composable () -> Unit = { SleepPeriodLeft(ctx, m, cover) }
            if (!cover) MRow(left = left, rail = { SleepRail(m) })
            else {
                left()
                Spacer(Modifier.height(14.dp))
                val b = s.bedtimes
                val last = s.nights.maxByOrNull { it.date }
                CoverStatRow(listOfNotNull(b?.let { StatCell(R3Texts.BEDTIMES, R3Texts.timeRange(it.earliest, it.latest)) },
                    last?.let { StatCell(R3Texts.wkdDMon(it.date), R3Texts.hm(it.info.asleepMin.toDouble())) }))
            }
        }
    }
}

/** Deep · REM · Light (+ asleep without stages) · Awake minutes. */
private fun stageMinutes(n: SleepNight): List<Int> = listOf(n.info.deepMin, n.info.remMin, n.info.lightMin + n.info.asleepUnspecifiedMin, n.info.awakeMin)

@Composable
private fun SleepDayLeft(n: SleepNight, stages: List<SleepStage>, cover: Boolean) {
    val hb = LocalHB.current
    val big = if (cover) 30.sp else 40.sp; val small = if (cover) 15.sp else 18.sp
    val b0 = (if (cover) 82 else 90) - 52
    Column(Modifier.fillMaxWidth().then(if (cover) Modifier else Modifier.fillMaxHeight())) {
        Text(buildAnnotatedString {
            hmParts(n.info.asleepMin.toDouble()).forEachIndexed { i, (num, u) ->
                if (i > 0) withStyle(SpanStyle(fontSize = small)) { append("  ") }
                withStyle(SpanStyle(fontSize = big, fontWeight = FontWeight.Bold, color = hb.colors.text)) { append(num) }
                withStyle(SpanStyle(fontSize = small, color = hb.colors.muted)) { append(" $u") }
            }
        }, maxLines = 1, modifier = Modifier.paddingFromBaseline(top = b0.dp), style = TextStyle(fontFeatureSettings = "tnum"))
        Text(R3Texts.timeRange(localTime(n.info.start), localTime(n.info.end)), fontSize = 12.5.sp, color = hb.colors.muted, maxLines = 1,
            modifier = Modifier.paddingFromBaseline(top = 22.dp))
        Spacer(Modifier.height(10.dp))
        SegPill(stageParts(n, hb), Modifier.fillMaxWidth(), h = 10.dp)
        Spacer(Modifier.height(12.dp))
        Hypnogram(stages, n, if (cover) Modifier.fillMaxWidth().height(120.dp) else Modifier.fillMaxWidth().weight(1f))
    }
}

/** act.py c_sleep 1D rail: Deep / REM / Light / Awake, dot + name 13/600 + share 11.5 right, duration 16/700 under (pitch 54). */
@Composable
private fun StageRail(n: SleepNight) {
    val hb = LocalHB.current
    val c = ActivityColors.stages(hb)
    val mins = stageMinutes(n)
    val tot = mins.sum().coerceAtLeast(1)
    Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
        mins.forEachIndexed { i, v ->
            Column(Modifier.fillMaxWidth().height(54.dp).semantics(mergeDescendants = true) {}) {
                Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(c[i], CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(R3Texts.STAGES[i], fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.text, modifier = Modifier.weight(1f))
                    Text(R3Texts.pct(v * 100.0 / tot), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.muted)
                }
                Text(R3Texts.hm(v.toDouble()), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = hb.colors.text,
                    modifier = Modifier.padding(start = 18.dp).paddingFromBaseline(top = 16.dp))
            }
        }
    }
}

/** ≥ 1W: "{shortest} – {longest}" 30/700, "{n} nights", ghost key (W/M), then stage bars per night (W/M) or month ranges (Y/All). */
@Composable
private fun SleepPeriodLeft(ctx: SectionContext, m: ActR3, cover: Boolean) {
    val hb = LocalHB.current
    val s = m.sleep
    val fs = if (cover) 26.sp else 30.sp
    val b0 = (if (cover) 82 else 90) - 52
    val perNight = m.p is SectionPeriod.Week || m.p is SectionPeriod.Month
    Column(Modifier.fillMaxWidth().then(if (cover) Modifier else Modifier.fillMaxHeight())) {
        Text(buildAnnotatedString {
            withStyle(SpanStyle(fontSize = fs, fontWeight = FontWeight.Bold, color = hb.colors.text)) { append(R3Texts.hm(s.shortest ?: 0.0)) }
            withStyle(SpanStyle(fontSize = fs * 0.62f, fontWeight = FontWeight.Bold, color = hb.colors.muted)) { append("  –  ") }
            withStyle(SpanStyle(fontSize = fs, fontWeight = FontWeight.Bold, color = hb.colors.text)) { append(R3Texts.hm(s.longest ?: 0.0)) }
        }, maxLines = 1, modifier = Modifier.paddingFromBaseline(top = b0.dp), style = TextStyle(fontFeatureSettings = "tnum"))
        Row(Modifier.fillMaxWidth().paddingFromBaseline(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(R3Texts.nights(s.nights.size), fontSize = 12.5.sp, color = hb.colors.muted, maxLines = 1, modifier = Modifier.weight(1f))
            if (perNight && s.ghost != null) R3Texts.ghostWord(m.p, m.today)?.let { GhostKey(GhostKeyModel(it, GhostKind.BAR)) }
        }
        Spacer(Modifier.height(12.dp))
        val chart = if (cover) Modifier.fillMaxWidth().height(120.dp) else Modifier.fillMaxWidth().weight(1f)
        if (perNight) SleepBars(ctx, m, chart) else SleepRanges(ctx, m, chart)
    }
}

/** ≥ 1W rail: "Bedtimes" + range 18/700, divider, the 3 latest nights (day, duration, stage bar; pitch 46). */
@Composable
private fun SleepRail(m: ActR3) {
    val hb = LocalHB.current
    val s = m.sleep
    Column(Modifier.fillMaxSize()) {
        Text(R3Texts.BEDTIMES, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.muted, modifier = Modifier.paddingFromBaseline(top = 20.dp))
        Text(s.bedtimes?.let { R3Texts.timeRange(it.earliest, it.latest) } ?: R3Texts.DASH, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            color = hb.colors.text, modifier = Modifier.paddingFromBaseline(top = 21.dp), style = TextStyle(fontFeatureSettings = "tnum"))
        Spacer(Modifier.height(13.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(hb.colors.grid))
        Spacer(Modifier.height(11.dp))
        s.nights.sortedByDescending { it.date }.take(3).forEach { n ->
            Column(Modifier.fillMaxWidth().height(46.dp).semantics(mergeDescendants = true) {}) {
                Row(Modifier.fillMaxWidth().paddingFromBaseline(top = 12.dp)) {
                    Text(R3Texts.wkdDMon(n.date), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.text, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(R3Texts.hm(n.info.asleepMin.toDouble()), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = hb.colors.text, maxLines = 1)
                }
                Spacer(Modifier.height(7.dp))
                SegPill(stageParts(n, hb), Modifier.fillMaxWidth(), h = 7.dp, gap = 1.5.dp)
            }
        }
    }
}

private const val CHART_GUTTER = 30f

/** W/M: stacked stage bars per night (asleep minutes; awake on top) with hour ticks and last period's nights behind. */
@Composable
private fun SleepBars(ctx: SectionContext, m: ActR3, modifier: Modifier) {
    val hb = LocalHB.current
    val measurer = rememberTextMeasurer()
    val byDay = remember(m) { m.sleep.nights.associateBy { it.date } }
    val days = remember(m) { (0 until m.p.totalDays()).map { m.p.start.plusDays(it.toLong()) } }
    val cols = ActivityColors.stages(hb)
    val labelStyle = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = hb.colors.muted)
    val tickStyle = TextStyle(fontSize = 9.5.sp, fontWeight = FontWeight.Medium, color = hb.colors.muted.copy(alpha = 0.8f))
    val buckets = remember(m) { days.map { d -> SeriesBucket(d.atStartOfDay(), d.plusDays(1).atStartOfDay(), byDay[d]?.info?.asleepMin?.toDouble(), future = d.isAfter(m.today)) } }
    Canvas(modifier.pointerInput(m) {
        detectTapGestures { o ->
            val slot = (size.width - CHART_GUTTER * density) / days.size
            val i = (o.x / slot).toInt().coerceIn(0, days.lastIndex)
            if (!days[i].isAfter(m.today)) ctx.drill(days[i])
        }
    }) {
        val dp = density
        val n = days.size
        val gutter = CHART_GUTTER * dp; val axisH = 18f * dp; val top = 6f * dp
        val pw = size.width - gutter; val ph = size.height - axisH - top
        val tot = days.map { d -> byDay[d]?.let { stageMinutes(it).sum().toDouble() } ?: 0.0 }
        val vmax = (listOf(600.0) + tot + m.sleep.ghost.orEmpty().map { it ?: 0.0 }).max()
        val slot = pw / n
        val gapF = if (n > 12) 0.22f else 0.30f
        val bw = min(slot * (1 - gapF), 44f * dp)
        val rad = min(bw / 2f, 2.5f * dp + bw * 0.18f)
        val base = top + ph
        fun y(v: Double) = base - (v / vmax * ph).toFloat()
        for (h in listOf(2, 4, 6, 8)) {
            val v = h * 60.0
            if (v >= vmax) continue
            drawLine(hb.colors.grid.copy(alpha = 0.45f), Offset(0f, y(v)), Offset(pw, y(v)), 0.6f * dp)
            val t = measurer.measure(R3Texts.hTick(h), tickStyle)
            drawText(t, topLeft = Offset(pw + 4f * dp, y(v) - t.size.height / 2f))
        }
        drawLine(hb.colors.grid, Offset(0f, base + 0.5f), Offset(pw, base + 0.5f), 1f * dp)
        m.sleep.ghost?.forEachIndexed { i, g ->
            if (g == null || g <= 0.0 || i >= n) return@forEachIndexed
            val hh = max((g / vmax * ph).toFloat(), 3f * dp)
            drawRoundRect(hb.colors.muted.copy(alpha = GhostStyle.FILL_A), Offset(slot * (i + 0.5f) - bw / 2f, base - hh), Size(bw, hh), CornerRadius(min(rad, hh / 2f)))
        }
        days.forEachIndexed { i, d ->
            val cx = slot * (i + 0.5f); val x = cx - bw / 2f
            val night = byDay[d]
            if (night == null) {
                drawRoundRect(hb.colors.tileHi.copy(alpha = if (d.isAfter(m.today)) 0.5f else 1f), Offset(x, base - 3f * dp), Size(bw, 3f * dp), CornerRadius(1.5f * dp))
            } else {
                val parts = stageMinutes(night)
                val hh = max((parts.sum() / vmax * ph).toFloat(), 3f * dp)
                clipPath(Path().apply { addRoundRect(RoundRect(x, base - hh, x + bw, base, CornerRadius(rad))) }) {
                    var yy = base
                    parts.forEachIndexed { k, v ->
                        if (v <= 0) return@forEachIndexed
                        val sh = (v.toDouble() / parts.sum() * hh).toFloat()
                        drawRect(cols[k], Offset(x, yy - sh), Size(bw, sh)); yy -= sh
                    }
                }
            }
            val b = buckets[i]
            val lbl = axisLabel(b, i, n, Grain.DAY, m.p) ?: return@forEachIndexed
            val today = isTodayBucket(b, Grain.DAY, m.today)
            val t = measurer.measure(lbl, labelStyle.copy(color = if (today) hb.colors.onAccent else hb.colors.muted))
            val ly = base + 3f * dp
            if (today) drawCircle(hb.colors.accent, max(t.size.width, t.size.height) / 2f + 3f * dp, Offset(cx, ly + t.size.height / 2f))
            drawText(t, topLeft = Offset(cx - t.size.width / 2f, ly))
        }
    }
}

/** 1Y / All: shortest–longest night per month as a capsule (single night: a dot), hour ticks at 6h / 7h / 8h. */
@Composable
private fun SleepRanges(ctx: SectionContext, m: ActR3, modifier: Modifier) {
    val hb = LocalHB.current
    val measurer = rememberTextMeasurer()
    val s = ctx.card("sleep")?.series ?: return
    val color = ActivityColors.sleep(hb)
    val labelStyle = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = hb.colors.muted)
    val tickStyle = TextStyle(fontSize = 9.5.sp, fontWeight = FontWeight.Medium, color = hb.colors.muted.copy(alpha = 0.8f))
    val bs = s.buckets
    Canvas(modifier.pointerInput(bs) {
        detectTapGestures { o ->
            val slot = (size.width - CHART_GUTTER * density) / bs.size.coerceAtLeast(1)
            val i = (o.x / slot).toInt().coerceIn(0, (bs.size - 1).coerceAtLeast(0))
            bs.getOrNull(i)?.takeIf { !it.future }?.let { ctx.drill(it.day) }
        }
    }) {
        if (bs.isEmpty()) return@Canvas
        val dp = density
        val n = bs.size
        val lo0 = bs.mapNotNull { it.min ?: it.value }.minOrNull() ?: return@Canvas
        val hi0 = bs.mapNotNull { it.max ?: it.value }.maxOrNull() ?: return@Canvas
        val vmin = lo0 - 30; val vmax = hi0 + 30
        val gutter = CHART_GUTTER * dp; val axisH = 18f * dp; val top = 6f * dp
        val pw = size.width - gutter; val ph = size.height - axisH - top
        val base = top + ph
        fun y(v: Double) = base - ((v - vmin) / (vmax - vmin) * ph).toFloat()
        for (h in listOf(6, 7, 8)) {
            val v = h * 60.0
            if (v <= vmin || v >= vmax) continue
            drawLine(hb.colors.grid.copy(alpha = 0.45f), Offset(0f, y(v)), Offset(pw, y(v)), 0.6f * dp)
            val t = measurer.measure(R3Texts.hTick(h), tickStyle)
            drawText(t, topLeft = Offset(pw + 4f * dp, y(v) - t.size.height / 2f))
        }
        drawLine(hb.colors.grid, Offset(0f, base + 0.5f), Offset(pw, base + 0.5f), 1f * dp)
        val slot = pw / n
        val bw = min(slot * 0.5f, 12f * dp)
        bs.forEachIndexed { i, b ->
            val cx = slot * (i + 0.5f)
            val mn = b.min ?: b.value; val mx = b.max ?: b.value
            if (mn != null && mx != null) {
                if (mx - mn < 1e-6) drawCircle(color, bw / 2f + 1f * dp, Offset(cx, y(mx)))
                else drawLine(color, Offset(cx, y(mn)), Offset(cx, y(mx)), bw, cap = StrokeCap.Round)
            }
            val lbl = axisLabel(b, i, n, s.grain, m.p) ?: return@forEachIndexed
            val today = isTodayBucket(b, s.grain, m.today)
            val t = measurer.measure(lbl, labelStyle.copy(color = if (today) hb.colors.onAccent else if (b.future) hb.faint else hb.colors.muted))
            val ly = base + 3f * dp
            if (today) drawCircle(hb.colors.accent, max(t.size.width, t.size.height) / 2f + 3f * dp, Offset(cx, ly + t.size.height / 2f))
            drawText(t, topLeft = Offset(cx - t.size.width / 2f, ly))
        }
    }
}

/** The night's stages at their real times, one lane each (Awake · REM · Light · Deep), labels left 44, ticks every 2 h. */
@Composable
private fun Hypnogram(stages: List<SleepStage>, night: SleepNight, modifier: Modifier) {
    val hb = LocalHB.current
    val measurer = rememberTextMeasurer()
    val c = ActivityColors.stages(hb)
    // R3Texts.STAGES = Deep, REM, Light, Awake; lanes top → bottom: Awake, REM, Light, Deep
    val lanes = listOf(Triple("awake", R3Texts.STAGES[3], c[3]), Triple("rem", R3Texts.STAGES[1], c[1]),
        Triple("light", R3Texts.STAGES[2], c[2]), Triple("deep", R3Texts.STAGES[0], c[0]))
    val segs = stages.filter { it.end > it.start }
    if (segs.isEmpty()) { Box(modifier); return }
    val t0 = minOf(night.info.start, segs.minOf { it.start }); val t1 = maxOf(night.info.end, segs.maxOf { it.end })
    val label = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = hb.colors.muted)
    Canvas(modifier) {
        val left = 44.dp.toPx(); val axisH = 18.dp.toPx()
        val w = size.width - left; val laneH = (size.height - axisH) / lanes.size
        fun x(t: Long) = left + (t - t0).toFloat() / (t1 - t0).coerceAtLeast(1L).toFloat() * w
        lanes.forEachIndexed { i, (key, name, col) ->
            val y = i * laneH
            drawLine(hb.colors.grid.copy(alpha = 0.4f), Offset(left, y + laneH / 2f), Offset(size.width, y + laneH / 2f), 0.6.dp.toPx())
            val t = measurer.measure(name, label)
            drawText(t, topLeft = Offset(0f, y + laneH / 2f - t.size.height / 2f))
            segs.filter { s -> s.stage == key || (key == "light" && s.stage == "asleep") }.forEach { s ->
                drawRoundRect(col.copy(alpha = if (s.stage == "asleep") 0.55f else 1f), Offset(x(s.start), y + laneH * 0.18f),
                    Size((x(s.end) - x(s.start)).coerceAtLeast(1.5f), laneH * 0.64f), CornerRadius(2.dp.toPx()))
            }
        }
        val zone = ZoneId.systemDefault()
        var h = Instant.ofEpochMilli(t0).atZone(zone).withMinute(0).withSecond(0).withNano(0).plusHours(1)
        while (h.toInstant().toEpochMilli() < t1) {
            if (h.hour % 2 == 0) {
                val tx = x(h.toInstant().toEpochMilli())
                val t = measurer.measure(R3Texts.hh00(h.hour).take(2), label)
                drawText(t, topLeft = Offset(tx - t.size.width / 2f, size.height - axisH + 3.dp.toPx()))
            }
            h = h.plusHours(1)
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------- streak

/**
 * act.py c_streak: flame ring 56 at (20, 54), then "Workout day" / "{n} / {goal} workout days" and "Streak {n} · Best {n}";
 * from card y 132: 7 day dots (1D last 7 days, 1W the week), month dot grid (1M), workout days per month (1Y + last
 * year behind, All).
 */
@Composable
private fun StreakCardView(ctx: SectionContext) {
    val m = ctx.act() ?: return
    val hb = LocalHB.current
    val s = m.streak
    val flame = ActivityColors.flame(hb)
    R3CardFrame(R3Texts.STREAK, Glyph.Flame, flame, info = ActivityTexts.INFO_WORKOUT_DAYS, onClick = { ctx.openMetric("streak") }) {
        Row(Modifier.fillMaxWidth().height(80.dp)) {
            Box(Modifier.padding(top = 2.dp)) { FlameRing((s.current.toFloat() / max(s.best, 1)).coerceIn(0f, 1f), 56.dp) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                if (m.p is SectionPeriod.Day) {
                    Row(Modifier.padding(top = 12.dp).height(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (s.dayIsWorkout) { R3StatusIcon(com.boudhealth.data.TileIcon.DONE, Tone.GOOD, 18.dp); Spacer(Modifier.width(6.dp)) }
                        Text(if (s.dayIsWorkout) R3Texts.WORKOUT_DAY else R3Texts.NO_WORKOUT_DAY, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = hb.colors.text, maxLines = 1)
                    }
                    Text(R3Texts.streakLine(s.current, s.best), fontSize = 12.5.sp, color = hb.colors.muted, maxLines = 1, modifier = Modifier.paddingFromBaseline(top = 14.dp))
                } else {
                    // "{n} / {goal} workout days" (1W) / "{n} workout days": the number big, "/ goal" 0.62×, the words 0.5×
                    val num = R3Texts.int(s.days.toDouble())
                    val goalPart = s.goal?.let { slashGoal(it.toDouble()) }
                    val words = if (s.goal != null) R3Texts.workoutDaysOf(s.days, s.goal).substringAfter("${s.days} / ${s.goal} ")
                        else ActivityTexts.workoutDays(s.days, null).substringAfter("${s.days} ")
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, color = hb.colors.text)) { append(num) }
                        if (goalPart != null) withStyle(SpanStyle(fontSize = 18.6.sp, fontWeight = FontWeight.Bold, color = hb.colors.muted)) { append(" $goalPart") }
                        withStyle(SpanStyle(fontSize = 15.sp, color = hb.colors.muted)) { append(" $words") }
                    }, maxLines = 1, modifier = Modifier.paddingFromBaseline(top = 34.dp), style = TextStyle(fontFeatureSettings = "tnum"))
                    Text(R3Texts.streakLine(s.current, s.best), fontSize = 12.5.sp, color = hb.colors.muted, maxLines = 1, modifier = Modifier.paddingFromBaseline(top = 12.dp))
                }
            }
        }
        val chartMod = if (ctx.wide) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().height(110.dp)
        when (m.p) {
            is SectionPeriod.Day, is SectionPeriod.Week -> StreakDots(ctx, m, chartMod)
            is SectionPeriod.Month -> StreakMonth(ctx, m, chartMod)
            else -> {
                val buckets = remember(m) {
                    s.perMonth.map { (ym, v) ->
                        SeriesBucket(ym.atDay(1).atStartOfDay(), ym.plusMonths(1).atDay(1).atStartOfDay(), v ?: 0.0, future = v == null)
                    }
                }
                val h = if (ctx.wide) (CARD_H - 14f - 132f).dp else 110.dp
                PeriodBars(buckets, Grain.MONTH, m.p, flame, m.today, height = h, onTap = { b -> ctx.drill(b.day) },
                    ghost = s.perMonthGhost?.let { Ghost(it) }, tagHighest = m.p is SectionPeriod.Year)
            }
        }
    }
}

/** 1D (last 7 days) / 1W (the week): flame dots r 14 + day letters (today accent, the selected day in text colour). */
@Composable
private fun StreakDots(ctx: SectionContext, m: ActR3, modifier: Modifier) {
    val hb = LocalHB.current
    val measurer = rememberTextMeasurer()
    val col = ActivityColors.flame(hb)
    val dots = m.streak.dots
    val ink = if (hb.dark) hb.colors.onAccent else Color.White
    Canvas(modifier.pointerInput(dots) {
        detectTapGestures { o ->
            val i = (o.x / (size.width.toFloat() / dots.size.coerceAtLeast(1))).toInt().coerceIn(0, (dots.size - 1).coerceAtLeast(0))
            dots.getOrNull(i)?.first?.takeIf { !it.isAfter(m.today) }?.let { if (m.p !is SectionPeriod.Day) ctx.drill(it) }
        }
    }) {
        if (dots.isEmpty()) return@Canvas
        val dp = density
        val cw = size.width / 7f
        val cy = size.height / 2f - 10f * dp
        dots.forEachIndexed { i, (d, on) ->
            val c = cw * (i + 0.5f)
            when {
                d.isAfter(m.today) -> drawCircle(hb.colors.tileHi, 13f * dp, Offset(c, cy), style = Stroke(1.5f * dp))
                on -> {
                    drawCircle(col, 14f * dp, Offset(c, cy))
                    val gs = 14f * dp
                    translate(c - gs / 2f, cy - gs / 2f) { scale(gs / size.minDimension, gs / size.minDimension, Offset.Zero) { drawGlyph(Glyph.Flame, ink, 1.3f) } }
                }
                else -> drawCircle(hb.colors.tileHi, 14f * dp, Offset(c, cy))
            }
            val lab = SFmt.dayLetter(d)
            val ly = cy + 30f * dp
            val sel = d == m.selected || (m.p is SectionPeriod.Day && d == m.p.start)
            val (bg, fg) = when {
                d == m.today -> hb.colors.accent to hb.colors.onAccent
                sel -> hb.colors.text to hb.colors.card
                else -> null to if (d.isAfter(m.today)) hb.faint else hb.colors.muted
            }
            if (bg != null) drawCircle(bg, 9f * dp, Offset(c, ly))
            val t = measurer.measure(lab, TextStyle(fontSize = (if (bg != null) 10f else 10.5f).sp, fontWeight = FontWeight.Bold, color = fg))
            drawText(t, topLeft = Offset(c - t.size.width / 2f, ly - t.size.height / 2f))
        }
    }
}

/** 1M: Mon–Sun dot grid of the month (workout day = flame colour; future outlined; today ringed in accent). */
@Composable
private fun StreakMonth(ctx: SectionContext, m: ActR3, modifier: Modifier) {
    val hb = LocalHB.current
    val col = ActivityColors.flame(hb)
    val dots = m.streak.dots
    val first = (m.p.start.dayOfWeek.value - 1)
    val rows = (first + dots.size + 6) / 7
    Canvas(modifier.pointerInput(dots) {
        detectTapGestures { o ->
            val cw = size.width / 7f; val rh = min(size.height.toFloat() / rows, 22f * density)
            val c = (o.x / cw).toInt().coerceIn(0, 6); val r = (o.y / rh).toInt()
            val k = r * 7 + c - first
            dots.getOrNull(k)?.first?.takeIf { !it.isAfter(m.today) }?.let { ctx.drill(it) }
        }
    }) {
        val dp = density
        val cw = size.width / 7f
        val rh = min(size.height / rows, 22f * dp)
        val rad = min(7f * dp, rh * 0.36f)
        dots.forEachIndexed { i, (d, on) ->
            val r = (first + i) / 7; val c = (first + i) % 7
            val px = cw * (c + 0.5f); val py = rh * (r + 0.5f)
            when {
                d.isAfter(m.today) -> drawCircle(hb.colors.tileHi, rad - 0.6f * dp, Offset(px, py), style = Stroke(1.2f * dp))
                on -> drawCircle(col, rad, Offset(px, py))
                else -> drawCircle(hb.colors.tileHi, rad, Offset(px, py))
            }
            if (d == m.today) drawCircle(hb.colors.accent, rad + 2.5f * dp, Offset(px, py), style = Stroke(1.6f * dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------- VO2 max

@Composable
private fun Vo2CardView(ctx: SectionContext) {
    val m = ctx.act() ?: return
    val hb = LocalHB.current
    val v = m.vo2
    val color = ActivityColors.vo2(hb)
    val ch = v.change
    val cur = ch?.current
    val pill = ch?.diff?.let { d ->
        if (ch.dir == TrendDir.FLAT) DeltaPillModel(TrendDir.FLAT, R3Texts.sameAs(R3Texts.THREE_MONTHS_AGO), Tone.NEUTRAL)
        else DeltaPillModel(ch.dir, R3Texts.pillUnit(R3Texts.mag(d, 1), null, R3Texts.THREE_MONTHS_AGO),
            when (ch.good) { true -> Tone.GOOD; false -> Tone.BAD; null -> Tone.NEUTRAL })
    }
    val value = cur?.let { R3Texts.dec(it.value, 1) } ?: R3Texts.DASH
    val context = cur?.let { R3Texts.dMonRel(it.day, m.today) }
    val muted = cur != null && cur.day.isBefore(m.p.start)
    fun ext(e: com.boudhealth.data.Extreme?, label: String) = StatCell(label, e?.let { R3Texts.dec(it.value, 1) } ?: R3Texts.DASH, e?.let { "· ${R3Texts.dMonRel(it.day, m.today)}" })
    val sinceLabel = v.since?.let { R3Texts.sinceDayRelCap(it, m.today) }
    val cells = listOf(ext(v.lowest, R3Texts.LOWEST), ext(v.highest, R3Texts.HIGHEST),
        StatCell(sinceLabel ?: R3Texts.DASH, v.sinceChange?.let { R3Texts.signed(it, 1) } ?: R3Texts.DASH))
    val big = ctx.wide && m.p !is SectionPeriod.Day
    R3CardFrame(R3Texts.VO2, Glyph.Vo2, color, onClick = { ctx.openMetric("vo2") }) {
        when {
            big -> MRow(left = {
                Column(Modifier.fillMaxSize()) {
                    ValueBlock(value, null, null, context, pill, ValueSize.M, muted = muted)
                    Spacer(Modifier.height(2.dp))
                    Vo2Chart(m, color, Modifier.fillMaxWidth().height(chartH(ValueSize.M, pill != null)))
                }
            }, rail = { RailCells(cells, Modifier.fillMaxSize()) })
            ctx.wide -> {
                ValueBlock(value, null, null, context, pill, ValueSize.S, muted = muted)
                Spacer(Modifier.height(2.dp))
                Vo2Chart(m, color, Modifier.fillMaxWidth().height(chartH(ValueSize.S, pill != null)))
            }
            else -> {
                ValueBlock(value, null, null, context, pill, ValueSize.COVER, muted = muted)
                Spacer(Modifier.height(2.dp))
                Vo2Chart(m, color, Modifier.fillMaxWidth().height(120.dp))
                Spacer(Modifier.height(14.dp))
                CoverStatRow(cells.map { StatCell(it.label, it.value) })
            }
        }
    }
}

/**
 * VO₂ line on the detail page's category bands (vo2Scale cuts, unlabelled, current band in the metric colour), the
 * period shaded inside the 91-day window (1D/1W/1M), highest tagged above and lowest below, ticks in a 30 dp gutter.
 */
@Composable
private fun Vo2Chart(m: ActR3, color: Color, modifier: Modifier) {
    val hb = LocalHB.current
    val measurer = rememberTextMeasurer()
    val v = m.vo2
    val cuts = remember(v.birthYear) { vo2Scale(v.birthYear).cuts }
    val tickStyle = TextStyle(fontSize = 9.5.sp, fontWeight = FontWeight.Medium, color = hb.colors.muted.copy(alpha = 0.85f), fontFeatureSettings = "tnum")
    val labelStyle = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = hb.colors.muted)
    val tagStyle = TextStyle(fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = hb.colors.text, fontFeatureSettings = "tnum")
    Canvas(modifier.semantics { contentDescription = R3Texts.VO2 }) {
        val vals = v.values
        val pts = vals.withIndex().filter { it.value != null }.map { it.index to it.value!! }
        if (pts.isEmpty()) return@Canvas
        val dp = density
        val lo0 = pts.minOf { it.second }; val hi0 = pts.maxOf { it.second }
        val mid = (lo0 + hi0) / 2; val span = max(hi0 - lo0 + 1.6, 4.0)
        val lo = mid - span / 2; val hi = mid + span / 2
        val gutter = CHART_GUTTER * dp; val axisH = 18f * dp; val top = 6f * dp
        val pw = size.width - gutter; val ph = size.height - axisH - top
        val n = vals.size
        val slot = pw / n
        fun x(i: Int) = slot * (i + 0.5f)
        fun y(a: Double) = top + ph * (1f - ((a - lo) / (hi - lo)).toFloat())
        // category bands (4 zones from the 3 cuts), the current zone in the metric colour
        val bounds = listOf(Double.NEGATIVE_INFINITY) + cuts + listOf(Double.POSITIVE_INFINITY)
        val curZone = bounds.indices.lastOrNull { k -> k < 4 && pts.last().second >= bounds[k] } ?: 0
        val steps = if (hb.dark) listOf(0.03f, 0.06f, 0.10f, 0.15f) else listOf(0.025f, 0.045f, 0.075f, 0.11f)
        clipRect(0f, top, pw, top + ph) {
            for (k in 0..3) {
                val a = max(bounds[k], lo); val b = min(bounds[k + 1], hi)
                if (b <= a) continue
                val c = if (k == curZone) color.copy(alpha = if (hb.dark) 0.14f else 0.12f) else hb.colors.text.copy(alpha = steps[k])
                drawRect(c, Offset(0f, y(b)), Size(pw, y(a) - y(b)))
            }
            v.shade?.let { r ->
                drawRoundRect(hb.colors.text.copy(alpha = 0.07f), Offset(slot * r.first, top), Size(slot * (r.last - r.first + 1), ph), CornerRadius(3f * dp))
            }
        }
        niceTicks(lo, hi, 3).filter { it > lo && it < hi }.forEach { t ->
            val tl = measurer.measure(R3Texts.int(t), tickStyle)
            drawText(tl, topLeft = Offset(pw + 4f * dp, y(t) - tl.size.height / 2f))
        }
        drawLine(hb.colors.grid, Offset(0f, top + ph + 0.5f), Offset(pw, top + ph + 0.5f), 1f * dp)
        val line = if (hb.dark) color else color.copy(red = color.red * 0.75f, green = color.green * 0.75f, blue = color.blue * 0.75f)
        val path = Path()
        pts.forEachIndexed { k, (i, a) -> if (k == 0) path.moveTo(x(i), y(a)) else path.lineTo(x(i), y(a)) }
        if (pts.size > 1) drawPath(path, line, style = Stroke(2f * dp, cap = StrokeCap.Round))
        val dots = pts.size <= 120
        pts.forEachIndexed { k, (i, a) ->
            if (dots || k == pts.lastIndex) drawCircle(line, if (k == pts.lastIndex) 4f * dp else 2.5f * dp, Offset(x(i), y(a)))
        }
        // extremes (ties → latest)
        val iHi = pts.lastOrNull { it.second == hi0 }; val iLo = pts.lastOrNull { it.second == lo0 }
        if (iHi != null) measurer.measure(R3Texts.dec(iHi.second, 1), tagStyle).let { t -> drawText(t, topLeft = Offset(x(iHi.first) - t.size.width / 2f, y(iHi.second) - t.size.height - 5f * dp)) }
        if (iLo != null && iLo != iHi) measurer.measure(R3Texts.dec(iLo.second, 1), tagStyle.copy(color = hb.colors.muted)).let { t -> drawText(t, topLeft = Offset(x(iLo.first) - t.size.width / 2f, y(iLo.second) + 5f * dp)) }
        // x labels: month on its 1st (91 days), month letter on its first week (1Y), the year in January (All)
        v.bucketDays.forEachIndexed { i, d ->
            val prev = v.bucketDays.getOrNull(i - 1)
            val lab = when (m.p) {
                is SectionPeriod.Year -> if (prev == null || prev.month != d.month || (i == 0)) SFmt.monthLetter(if (d.year < m.p.start.year) 1 else d.monthValue) else null
                is SectionPeriod.All -> if (d.monthValue == 1 || i == 0) R3Texts.yyyy(d) else null
                else -> if (d.dayOfMonth == 1) R3Texts.monShort(d) else null
            } ?: return@forEachIndexed
            val t = measurer.measure(lab, labelStyle)
            drawText(t, topLeft = Offset((x(i) - t.size.width / 2f).coerceIn(0f, pw - t.size.width), top + ph + 3f * dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------- all values

private fun decimalsOf(id: String): Int = when (id) { "weight", "body_fat", "breathing" -> 1; else -> 0 }

private fun nameOf(id: String): String = when (id) {
    "weight" -> R3Texts.WEIGHT; "body_fat" -> R3Texts.BODY_FAT; "rhr" -> R3Texts.RESTING_HR; "hrv" -> R3Texts.HRV
    "breathing" -> R3Texts.BREATHING; else -> R3Texts.SPO2
}

/** 1D: Weight · Body fat % · RHR · HRV · Breathing rate · Blood oxygen; a carried value is muted with its date. */
@Composable
private fun AllValuesR3Card(ctx: SectionContext) {
    val m = ctx.act() ?: return
    val hb = LocalHB.current
    R3CardFrame(R3Texts.ALL_VALUES, Glyph.List, hb.colors.muted, onClick = null) {
        val rows = m.allValues
        if (rows.isEmpty()) { Text(R3Texts.DASH, fontSize = 14.sp, color = hb.colors.muted); return@R3CardFrame }
        Column(Modifier.fillMaxWidth().then(if (ctx.wide) Modifier.fillMaxHeight() else Modifier).padding(top = 6.dp)) {
            rows.forEachIndexed { i, r ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(hb.colors.grid))
                val unit = V2Metrics.get(r.metricId)?.unit.orEmpty()
                val dec = decimalsOf(r.metricId)
                Row(Modifier.fillMaxWidth().then(if (ctx.wide) Modifier.weight(1f) else Modifier.height(36.dp)).plainClickable { ctx.openMetric(r.metricId) }
                    .semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.padding(start = 1.dp).size(8.dp).background(metricColor(r.metricId), CircleShape))
                    Spacer(Modifier.width(7.dp))
                    Text(nameOf(r.metricId), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.text, modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (r.carried) {
                        Text(R3Texts.dMonRel(r.day, m.today), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = hb.faint, maxLines = 1)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = if (r.carried) hb.colors.muted else hb.colors.text)) { append(R3Texts.dec(r.value, dec)) }
                        if (unit.isNotEmpty()) withStyle(SpanStyle(fontSize = 11.5.sp, color = hb.colors.muted)) { append(" $unit") }
                    }, maxLines = 1, style = TextStyle(fontFeatureSettings = "tnum"))
                }
            }
        }
    }
}

