package com.boudhealth.ui.activity

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.data.CardData
import com.boudhealth.data.Grain
import com.boudhealth.data.PeriodSeries
import com.boudhealth.data.SectionPeriod
import com.boudhealth.data.SeriesBucket
import com.boudhealth.data.SeriesGap
import com.boudhealth.data.SleepNight
import com.boudhealth.data.TrendDir
import com.boudhealth.data.V2
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.GlyphIcon
import com.boudhealth.ui.components.plainClickable
import androidx.compose.ui.text.font.FontWeight
import com.boudhealth.ui.section.CardSpec
import com.boudhealth.ui.section.DotLine
import com.boudhealth.ui.section.Headline
import com.boudhealth.ui.section.InfoText
import com.boudhealth.ui.section.LegendValue
import com.boudhealth.ui.section.PeriodBars
import com.boudhealth.ui.section.RangeBars
import com.boudhealth.ui.section.SFmt
import com.boudhealth.ui.section.SectionContext
import com.boudhealth.ui.section.SectionHooks
import com.boudhealth.ui.section.SegPill
import com.boudhealth.ui.theme.Cat
import com.boudhealth.ui.theme.HB
import com.boudhealth.ui.theme.LocalHB
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Approved user-facing texts (V2_TEXTS.md §3–§5), verbatim. */
object ActivityTexts {
    const val WORKOUT_MIN = "Workout minutes"
    const val STEPS = "Steps"
    const val HEART_POINTS = "Heart points"
    const val STREAK = "Streak"
    const val CALORIES = "Calories burned"
    const val SLEEP = "Sleep"
    const val VO2 = "VO₂ max"
    const val WORKOUTS = "Workouts"
    val ZONES = listOf("Fat burn", "Cardio", "Peak")
    const val LOAD_HOURS = "Load hours"
    const val EMPTY_HOURS = "Hourly data not stored for this day"
    const val EMPTY_PERIOD = "No data for this period"
    const val EMPTY_WORKOUTS = "No workouts"
    const val EMPTY_SLEEP = "No sleep recorded"
    const val ROW_HEART_POINTS = "Heart points per week"
    const val ROW_SUGGESTED = "Suggested for you"
    const val ROW_STEPS = "Steps per day"
    const val ROW_WORKOUT_MIN = "Workout minutes per day"
    // restore round (V2_REMOVALS_AUDIT), V2_TEXTS §3a
    const val TOTAL = "Total"
    const val BEST_DAY = "Best day"
    const val PEAK_HOUR = "Peak hour"
    const val GOAL = "Goal"
    const val DAYS = "Days"
    const val WEEKS = "Weeks"
    const val NIGHTS = "Nights"
    const val HEART_ZONES = "Heart zones"
    const val IN_BED = "In bed"
    const val TOTAL_TIME = "Total time"
    const val AVG_HR = "Avg HR"
    const val ALL_VALUES = "All values"
    const val CALORIES_STAT = "Calories burned"

    /** st_past_days: "Step goal met on 4 of 7 days". */
    fun goalMet(metricId: String, n: Int, m: Int): String {
        val g = when (metricId) { "steps" -> "Step"; "exercise_min" -> "Workout minutes"; "zone_min" -> "Heart points"; else -> "Goal" }
        return "$g goal met on $n of $m days"
    }

    /** "Max heart rate 190 · Resting heart rate 52". */
    fun maxRest(max: Int, rest: Int) = "Max heart rate $max · Resting heart rate $rest"

    val INFO_HEART_POINTS = InfoText(
        "Heart points",
        "Heart points count how long your heart works hard. Each minute in Fat burn gives 1 point; each minute in Cardio or Peak gives 2. " +
            "The zones come from your heart rate reserve (maximum minus resting heart rate): Fat burn from 40 %, Cardio from 60 %, Peak from 85 %. " +
            "Maximum heart rate is 190 by default, the value at which heart points best match Google Health's Active Zone Minutes. " +
            "The WHO advises at least 150 minutes of moderate activity a week, about 150 heart points. Default target: 150 a week.",
    )
    /** Fallback only: the cards and the detail build the body from the saved rule (settings.infoWorkoutDays). */
    val INFO_WORKOUT_DAYS = InfoText(
        "Workout days",
        "A workout day is a day with at least one recorded workout of 20 minutes or more that earned at least 10 heart points. " +
            "Walks count only when your watch or app saved them " +
            "as a workout. You can change the rule in Settings → Profile & goals.",
    )

    const val WORKOUT_DAY = "Workout day"
    const val NO_WORKOUT_DAY = "No workout day"
    const val SHOW_MORE = "Show more"

    fun streakLine(n: Int, best: Int) = "Streak $n · Best $best"
    fun workoutDays(n: Int, goal: Int?) = if (goal != null) "$n / $goal workout days" else "$n workout days"
}

/** Activity colours (palette rings + fixed category colours). */
object ActivityColors {
    fun workout(hb: HB): Color = hb.palette.ring(0).first
    fun steps(hb: HB): Color = hb.palette.ring(1).first
    fun heart(hb: HB): Color = hb.palette.ring(2).first
    fun zones(hb: HB): List<Color> = hb.azm.drop(1)          // fat burn, cardio, peak
    fun calories(hb: HB): Color = Cat.kcalActive
    fun sleep(hb: HB): Color = Cat.sleep
    fun vo2(hb: HB): Color = Cat.vo2
    fun flame(hb: HB): Color = Cat.zoneCardio
    fun stages(hb: HB): List<Color> = listOf(hb.deep, Cat.rem, Cat.light, Cat.awake)
}

internal fun unitOf(metricId: String): String = when (metricId) { "exercise_min" -> "min"; "calories" -> "kcal"; "zone_min" -> "pts"; else -> "" }

object ActivityCards {
    val all: List<CardSpec> by lazy {
        listOf(
            total("exercise_min", ActivityTexts.WORKOUT_MIN, Glyph.Zone, ActivityColors::workout, "min"),
            total("steps", ActivityTexts.STEPS, Glyph.Steps, ActivityColors::steps, null),
            CardSpec.Metric("zone_min", ActivityTexts.HEART_POINTS, Glyph.Heart, ActivityColors::heart, info = ActivityTexts.INFO_HEART_POINTS,
                headline = { ctx, d -> TotalHeadline(d, null); GoalDaysLine(ctx, "zone_min") },
                graph = { ctx, d -> HeartPointsGraph(ctx.period, d, ctx.today, onTap = { ctx.drill(it) }) }),
            CardSpec.Metric("streak", ActivityTexts.STREAK, Glyph.Flame, ActivityColors::flame, info = ActivityTexts.INFO_WORKOUT_DAYS, showTrend = false,
                headline = { ctx, d -> StreakHeadline(ctx.period, d) },
                graph = { ctx, d -> StreakGraph(ctx.period, d, ctx.today, onTap = { ctx.drill(it) }) }),
            total("calories", ActivityTexts.CALORIES, Glyph.Flame, ActivityColors::calories, "kcal"),
            CardSpec.Metric("sleep", ActivityTexts.SLEEP, Glyph.Moon, ActivityColors::sleep, showTrend = false,
                headline = { ctx, d -> SleepHeadline(ctx.period, d) },
                graph = { ctx, d -> SleepGraph(ctx.period, d, ctx.today, onNight = { SectionHooks.openSleep(ctx.nav, ctx.period, it) }) },
                onClick = { ctx -> SectionHooks.openSleep(ctx.nav, ctx.period, (ctx.period as? SectionPeriod.Day)?.start) }),
            CardSpec.Metric("vo2", ActivityTexts.VO2, Glyph.Vo2, ActivityColors::vo2, trendDecimals = 1,
                headline = { ctx, d -> Vo2Headline(ctx.period, d) },
                graph = { ctx, d -> Vo2Graph(ctx.period, d) }),
            // A1: every Body / Vitals value of the selected day (1D), full row on the inner screen
            CardSpec.Custom("all_values", span = true, shown = { ctx -> ctx.period is SectionPeriod.Day && ctx.extras[X_ALL_VALUES] != null }) { ctx -> AllValuesCard(ctx) },
        )
    }

    private fun total(id: String, title: String, glyph: Glyph, color: (HB) -> Color, unit: String?) = CardSpec.Metric(
        id, title, glyph, color,
        headline = { ctx, d -> TotalHeadline(d, unit); GoalDaysLine(ctx, id) },
        graph = { ctx, d -> TotalGraph(ctx.period, d, color(LocalHB.current), ctx.today, onTap = { ctx.drill(it) }) },
    )
}

// =====================================================================================================================
// Totals: Workout minutes, Steps, Calories burned (and the Heart points headline)
// =====================================================================================================================

/** A2: "Step goal met on 4 of 7 days" under the headline (1W–1Y; from the section extras). */
@Composable
fun GoalDaysLine(ctx: SectionContext, metricId: String) {
    @Suppress("UNCHECKED_CAST") val m = ctx.extras[X_GOAL_DAYS] as? Map<String, Pair<Int, Int>?> ?: return
    val (n, d) = m[metricId] ?: return
    Text(ActivityTexts.goalMet(metricId, n, d), fontSize = 12.5.sp, color = LocalHB.current.colors.muted, maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
}

/** "351 / 210 min" (goal muted), or the total alone ("14,163 kcal", All = lifetime). */
@Composable
fun TotalHeadline(d: CardData, unit: String?, size: Float = 30f) {
    val v = d.value
    val goal = v?.goal?.value?.takeIf { d.value.period !is SectionPeriod.All }
    if (v?.value == null) { Headline("—", size = size); return }
    Headline(SFmt.int(v.value), goal?.let { SFmt.int(it) }, unit, size = size)
}

/**
 * Bars of a daily total for the period (per hour on 1D, per day 1W/1M, per month 1Y/All) with the goal line;
 * 1D on an old day: "Load hours" (live read) or "Hourly data not stored for this day".
 */
@Composable
fun TotalGraph(p: SectionPeriod, d: CardData, color: Color, today: LocalDate, onTap: (LocalDate) -> Unit, height: Dp = 118.dp) {
    SeriesGraph(d, p, height) { s ->
        PeriodBars(s.buckets, s.grain, p, color, today, height = height, goal = s.goalPerBucket,
            onTap = if (s.grain == Grain.HOUR) null else ({ b -> onTap(b.day) }), unit = unitOf(d.metricId))
    }
}

/** Shared gap handling for hourly series. */
@Composable
fun SeriesGraph(d: CardData, p: SectionPeriod, height: Dp, draw: @Composable (PeriodSeries) -> Unit) {
    val hb = LocalHB.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember(d.series) { mutableStateOf<PeriodSeries?>(null) }
    var busy by remember(d.series) { mutableStateOf(false) }
    val s = loaded ?: d.series
    when {
        s == null -> Box(Modifier.fillMaxWidth().height(height))
        s.gap == SeriesGap.HOURLY_NOT_CACHED && p is SectionPeriod.Day -> Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            Row(Modifier.background(hb.colors.tile, RoundedCornerShape(18.dp))
                .plainClickable(enabled = !busy) {
                    busy = true
                    scope.launch { loaded = runCatching { V2.loadHours(context, d.metricId, p.start) }.getOrNull(); busy = false }
                }.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.Zone, size = 14.dp, color = hb.colors.text)
                Spacer(Modifier.width(6.dp))
                Text(ActivityTexts.LOAD_HOURS, style = hb.type.label, color = if (busy) hb.colors.muted else hb.colors.text)
            }
        }
        s.gap == SeriesGap.NO_HOURLY_SOURCE && p is SectionPeriod.Day -> Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            Text(ActivityTexts.EMPTY_HOURS, style = hb.type.bodyS, color = hb.colors.muted)
        }
        else -> draw(s)
    }
}

// =====================================================================================================================
// Heart points: stacked Fat burn · Cardio · Peak minutes per bar, points above; zone minutes row (V2-068)
// =====================================================================================================================

@Composable
fun HeartPointsGraph(p: SectionPeriod, d: CardData, today: LocalDate, onTap: (LocalDate) -> Unit, height: Dp = 118.dp) {
    val hb = LocalHB.current
    val z = d.zones
    val colors = ActivityColors.zones(hb)
    val mins = z?.minutes ?: IntArray(4)
    Column(Modifier.fillMaxWidth()) {
        if (p is SectionPeriod.Day) {
            SegPill((1..3).map { mins.getOrElse(it) { 0 }.toDouble() to colors[it - 1] }, Modifier.fillMaxWidth(), h = 16.dp)
        } else if (z != null) {
            PeriodBars(z.perBucket, z.grain, p, colors[0], today, height = height, segmentColors = colors,
                valueLabels = z.perBucket.size <= 12, onTap = { b -> onTap(b.day) }, unit = "pts", segmentNames = ActivityTexts.ZONES)
        }
        Spacer(Modifier.height(12.dp))
        ZoneLegend(mins)
    }
}

/** "● Fat burn 146 min · ● Cardio 46 min · ● Peak 0 min". */
@Composable
fun ZoneLegend(mins: IntArray, modifier: Modifier = Modifier) {
    val hb = LocalHB.current
    val colors = ActivityColors.zones(hb)
    Row(modifier.fillMaxWidth()) {
        ActivityTexts.ZONES.forEachIndexed { i, name ->
            LegendValue(colors[i], name, SFmt.int(mins.getOrElse(i + 1) { 0 }.toDouble()), "min", Modifier.weight(1f))
        }
    }
}

// =====================================================================================================================
// Streak & active days (V2-069): flame-in-ring, workout-day count, streak line; day dots (1W/1M), bars per month (1Y/All)
// =====================================================================================================================

@Composable
internal fun StreakHeadline(p: SectionPeriod, d: CardData) {
    val hb = LocalHB.current
    val w = d.workouts
    val active = w?.activeDays?.size ?: 0
    val goal = w?.activeDaysGoal
    val progress = when {
        p is SectionPeriod.Day -> if (w?.dayIsActive == true) 1f else 0f
        goal != null && goal > 0 -> active / goal.toFloat()
        else -> if (active > 0) 1f else 0f
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        FlameRing(progress, 64.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            if (p is SectionPeriod.Day) {
                val on = w?.dayIsActive == true
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(26.dp).background(if (on) hb.colors.goodCont else hb.colors.tile, CircleShape), contentAlignment = Alignment.Center) {
                        if (on) GlyphIcon(Glyph.Check, size = 16.dp, color = hb.colors.good)
                        else Box(Modifier.size(8.dp).background(hb.colors.text, CircleShape))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(if (on) ActivityTexts.WORKOUT_DAY else ActivityTexts.NO_WORKOUT_DAY, style = hb.type.titleS, color = if (on) hb.colors.text else hb.colors.muted)
                }
            } else {
                val txt = ActivityTexts.workoutDays(active, if (p is SectionPeriod.Week) goal else null)
                val num = txt.substringBefore(" ")
                Text(androidx.compose.ui.text.buildAnnotatedString {
                    withStyle(androidx.compose.ui.text.SpanStyle(fontSize = hb.type.number.fontSize, fontWeight = hb.type.number.fontWeight, color = hb.colors.text)) { append(num) }
                    withStyle(androidx.compose.ui.text.SpanStyle(fontSize = hb.type.body.fontSize, color = hb.colors.muted)) { append(" " + txt.substringAfter(" ")) }
                }, maxLines = 1)
            }
            Spacer(Modifier.height(6.dp))
            Text(ActivityTexts.streakLine(w?.streak ?: 0, w?.bestStreak ?: 0), style = hb.type.bodyS, color = hb.colors.muted, maxLines = 1)
        }
    }
}

/** The flame-in-ring element (design6 streak_element): dim ring, filled share in flame colour, flame glyph in the middle. */
@Composable
fun FlameRing(progress: Float, size: Dp) {
    val hb = LocalHB.current
    val c = ActivityColors.flame(hb)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val s = size.toPx() * 0.11f
            val inset = s / 2f
            val arcSize = Size(this.size.width - s, this.size.height - s)
            drawArc(c.copy(alpha = 0.22f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(s))
            val sweep = 360f * progress.coerceIn(0f, 1f)
            if (sweep > 0f) drawArc(c, -90f, sweep, false, Offset(inset, inset), arcSize, style = Stroke(s, cap = StrokeCap.Round))
            else drawCircle(c, s / 2f, Offset(this.size.width / 2f, inset))
        }
        Box(Modifier.size(size * 0.5f).background(c.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
            GlyphIcon(Glyph.Flame, size = size * 0.36f, color = c)
        }
    }
}

@Composable
internal fun StreakGraph(p: SectionPeriod, d: CardData, today: LocalDate, onTap: (LocalDate) -> Unit) {
    val hb = LocalHB.current
    val w = d.workouts ?: return
    val c = ActivityColors.flame(hb)
    when (p) {
        is SectionPeriod.Day -> Unit
        is SectionPeriod.Week, is SectionPeriod.Month -> {
            val days = generateSequence(p.start) { it.plusDays(1) }.takeWhile { !it.isAfter(p.last) }.toList()
            val dense = days.size > 7
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                days.forEach { day ->
                    Column(Modifier.weight(1f).plainClickable(enabled = !day.isAfter(today)) { onTap(day) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        val dot = if (dense) 8.dp else 24.dp
                        Canvas(Modifier.size(dot)) {
                            val r = this.size.minDimension / 2f
                            when {
                                day.isAfter(today) -> drawCircle(hb.colors.tileHi, r - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                                day in w.activeDays -> drawCircle(c, r)
                                else -> drawCircle(hb.colors.tileHi, r)
                            }
                            if (day == today) drawCircle(hb.colors.text, r, style = Stroke(1.5.dp.toPx()))
                        }
                        if (!dense) {
                            Spacer(Modifier.height(10.dp))
                            Text(SFmt.dayLetter(day), style = hb.type.labelS, color = hb.colors.muted)
                        }
                    }
                }
            }
        }
        else -> {
            // workout days per month (1Y / All)
            val first = p.start.withDayOfMonth(1)
            val months = generateSequence(first) { it.plusMonths(1) }.takeWhile { !it.isAfter(p.last) }.toList()
            val counts = w.activeDays.groupingBy { it.withDayOfMonth(1) }.eachCount()
            val buckets = months.map { m ->
                SeriesBucket(m.atStartOfDay(), m.plusMonths(1).atStartOfDay(), counts[m]?.toDouble() ?: 0.0, future = m.isAfter(today))
            }
            PeriodBars(buckets, Grain.MONTH, p, c, today, onTap = { b -> onTap(b.day) })
        }
    }
}

// =====================================================================================================================
// Sleep (V2-071): 1D last night + stage bar; 1W/1M range + per-night stage bars; 1Y/All per-month min–max
// =====================================================================================================================

fun nightOf(d: CardData, p: SectionPeriod): SleepNight? = d.sleep?.lastOrNull { !it.date.isAfter(p.last) }

@Composable
private fun SleepHeadline(p: SectionPeriod, d: CardData) {
    val hb = LocalHB.current
    if (p is SectionPeriod.Day) {
        val n = nightOf(d, p)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Headline(n?.let { SFmt.hm(it.info.asleepMin) } ?: "—")
            Spacer(Modifier.weight(1f))
            if (n != null) Text("${com.boudhealth.ui.components.Fmt.hm(n.info.start)}–${com.boudhealth.ui.components.Fmt.hm(n.info.end)}",
                style = hb.type.bodyS, color = hb.colors.muted)
        }
        return
    }
    val v = d.value
    val nights = d.sleep?.size ?: v?.daysWithData ?: 0
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        val mn = v?.min; val mx = v?.max
        Headline(if (mn != null && mx != null && nights > 0) "${SFmt.hm(mn.toInt())} – ${SFmt.hm(mx.toInt())}" else "—", size = 26f)
        Spacer(Modifier.weight(1f))
        if (nights > 0) Text(if (nights == 1) "1 night" else "$nights nights", style = hb.type.bodyS, color = hb.colors.muted)
    }
}

/** Stage parts in drawing order: Deep · REM · Light (+ asleep without stages) · Awake. */
fun stageParts(n: SleepNight, hb: HB): List<Pair<Double, Color>> {
    val c = ActivityColors.stages(hb)
    return listOf(n.info.deepMin.toDouble() to c[0], n.info.remMin.toDouble() to c[1],
        (n.info.lightMin + n.info.asleepUnspecifiedMin).toDouble() to c[2], n.info.awakeMin.toDouble() to c[3])
}

@Composable
fun SleepGraph(p: SectionPeriod, d: CardData, today: LocalDate, onNight: (LocalDate) -> Unit, height: Dp = 118.dp) {
    val hb = LocalHB.current
    when (p) {
        is SectionPeriod.Day -> {
            val n = nightOf(d, p) ?: return
            SegPill(stageParts(n, hb), Modifier.fillMaxWidth(), h = 16.dp)
            Spacer(Modifier.height(12.dp))
            StageLegend(n)
        }
        is SectionPeriod.Week, is SectionPeriod.Month -> {
            val byDay = d.sleep.orEmpty().associateBy { it.date }
            val buckets = generateSequence(p.start) { it.plusDays(1) }.takeWhile { !it.isAfter(p.last) }.map { day ->
                val n = byDay[day]
                SeriesBucket(day.atStartOfDay(), day.plusDays(1).atStartOfDay(), n?.info?.asleepMin?.toDouble(),
                    segments = n?.let { night -> stageParts(night, hb).map { it.first }.toDoubleArray() }, future = day.isAfter(today))
            }.toList()
            PeriodBars(buckets, Grain.DAY, p, ActivityColors.sleep(hb), today, height = height, segmentColors = ActivityColors.stages(hb),
                onTap = { b -> if (byDay.containsKey(b.day)) onNight(b.day) })
        }
        else -> {
            val s = d.series ?: return
            RangeBars(s.buckets, s.grain, p, ActivityColors.sleep(hb), today, height = height)
        }
    }
}

@Composable
fun StageLegend(n: SleepNight) {
    val hb = LocalHB.current
    val c = ActivityColors.stages(hb)
    val items = listOf("Deep" to n.info.deepMin, "REM" to n.info.remMin, "Light" to n.info.lightMin + n.info.asleepUnspecifiedMin, "Awake" to n.info.awakeMin)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(2).forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEachIndexed { i, (name, m) ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(c[r * 2 + i], CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(androidx.compose.ui.text.buildAnnotatedString {
                            withStyle(androidx.compose.ui.text.SpanStyle(color = hb.colors.muted)) { append("$name ") }
                            withStyle(androidx.compose.ui.text.SpanStyle(color = hb.colors.text, fontWeight = hb.type.label.fontWeight)) { append(SFmt.hm(m)) }
                        }, style = hb.type.bodyS, maxLines = 1)
                    }
                }
            }
        }
    }
}

// =====================================================================================================================
// VO₂ max (V2-072): latest (+ date on 1D / carried), dots + line, large trend arrow on 1D
// =====================================================================================================================

@Composable
private fun Vo2Headline(p: SectionPeriod, d: CardData) {
    val v = d.value
    val value = v?.value
    if (value == null) { Headline("—"); return }
    val showDate = v.at != null && (p is SectionPeriod.Day || v.carried)
    Headline(SFmt.dec(value, 1), null, if (showDate) SFmt.shortDate(v.at) else null, muted = v.carried)
}

@Composable
private fun Vo2Graph(p: SectionPeriod, d: CardData) {
    val hb = LocalHB.current
    val c = ActivityColors.vo2(hb)
    if (p is SectionPeriod.Day || (d.series?.buckets?.count { it.value != null } ?: 0) == 0) {
        val dir = d.trend?.dir
        Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
            val (bg, fg) = when (d.trend?.good) {
                true -> hb.colors.goodCont to hb.colors.good
                false -> hb.colors.badCont to hb.colors.bad
                null -> hb.colors.tile to hb.colors.text
            }
            Box(Modifier.size(56.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
                Text(when (dir) { TrendDir.UP -> "▲"; TrendDir.DOWN -> "▼"; else -> "●" }, color = fg, style = hb.type.titleM)
            }
        }
        return
    }
    DotLine(d.series!!.buckets.map { it.value }, c, dots = d.series.buckets.size <= 31)
}

