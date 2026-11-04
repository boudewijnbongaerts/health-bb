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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.data.CardData
import com.boudhealth.data.Grain
import com.boudhealth.data.HrZones
import com.boudhealth.data.Insights
import com.boudhealth.data.MetricEnv
import com.boudhealth.data.PeriodUnit
import com.boudhealth.data.PeriodTrend
import com.boudhealth.data.SectionPeriod
import com.boudhealth.data.SeriesBucket
import com.boudhealth.data.SeriesGap
import com.boudhealth.data.SleepNight
import com.boudhealth.data.SleepStage
import com.boudhealth.data.Sync
import com.boudhealth.data.TrendBasis
import com.boudhealth.data.TrendDir
import com.boudhealth.data.V2
import com.boudhealth.data.V2Math
import com.boudhealth.data.V2Metrics
import com.boudhealth.data.BucketAgg
import com.boudhealth.data.Workout
import com.boudhealth.data.DayRow
import com.boudhealth.data.PeriodStats
import com.boudhealth.data.ZoneLimits
import com.boudhealth.data.SessionDetail
import com.boudhealth.data.WeekPoints
import com.boudhealth.data.dayList
import com.boudhealth.data.goalDays
import com.boudhealth.data.stats
import com.boudhealth.data.zoneLimits
import com.boudhealth.data.sessionDetails
import com.boudhealth.data.weeklyHeartPoints
import com.boudhealth.data.weeklyGoals
import com.boudhealth.ui.components.EmptyState
import com.boudhealth.ui.components.Fmt
import com.boudhealth.ui.components.FilterPill
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.GlyphCircle
import com.boudhealth.ui.components.GlyphIcon
import com.boudhealth.ui.components.HBCard
import com.boudhealth.ui.components.SkeletonBox
import com.boudhealth.ui.components.Stat
import com.boudhealth.ui.components.StatRow
import com.boudhealth.ui.components.plainClickable
import com.boudhealth.ui.nav.Nav
import com.boudhealth.ui.section.DScope
import com.boudhealth.ui.section.DayHistoryList
import com.boudhealth.ui.section.DetailBars
import com.boudhealth.ui.section.DetailHeader
import com.boudhealth.ui.section.DetailNav
import com.boudhealth.ui.section.Headline
import com.boudhealth.ui.section.MetricCardFrame
import com.boudhealth.ui.section.RangeBars
import com.boudhealth.ui.section.SFmt
import com.boudhealth.ui.section.SectionDetailScaffold
import com.boudhealth.ui.section.SegPill
import com.boudhealth.ui.section.TrendArrow
import com.boudhealth.ui.section.rememberDetailNav
import com.boudhealth.ui.theme.HbShapes
import com.boudhealth.ui.theme.LocalHB
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// =====================================================================================================================
// Activity detail pages (V2-070/071/073, restore round V2_REMOVALS_AUDIT §2.3–2.7): Workout minutes, Steps, Heart points,
// Streak, Calories burned, Sleep. Periods 1D · 1W · 1M · 3M · 1Y · All; interactive chart (read-out, y values, zoom);
// Total · Best day · Peak hour · Goal; goal met on n of m days; per-day history with the change vs the day before;
// goal button. Card number = detail number: both come from V2.card for the same period (V2-076).
// =====================================================================================================================

/** Metric ids whose detail page lives here. AppShell routes `MetricDetail(id)` for these ids to [ActivityDetailScreen]. */
val ACTIVITY_DETAIL_IDS = setOf("exercise_min", "steps", "zone_min", "heart_zones", "weekly_cardio", "streak", "calories", "sleep")

private val TOTALS = setOf("exercise_min", "steps", "zone_min", "calories")

/** Everything a detail page shows, built on IO for one period (3M included). */
internal class DetailModel(
    val ds: DScope,
    val p: SectionPeriod,
    val dataStart: LocalDate,
    val card: CardData,
    val total: Double?,
    val goal: Double?,
    val trend: PeriodTrend?,
    val buckets: List<SeriesBucket>,
    val grain: Grain,
    val goalPerBucket: Double?,
    val gap: SeriesGap,
    /** Per-day rows up to the period end (≤ 120 days back, newest first; T1 dayList). */
    val history: List<DayRow>,
    /** Goal met on n of m days of the period (T1 goalDays), null on 1D / without a goal. */
    val goalDays: Pair<Int, Int>?,
    val dailyGoal: Double?,
    /** Total · best day · peak hour (T1 stats). */
    val stats: PeriodStats?,
    // Heart points extras (T1 V2Restore)
    val limits: ZoneLimits? = null,
    val sessions: List<SessionDetail> = emptyList(),
    val weeks: List<WeekPoints> = emptyList(),
    val weeklyTarget: Int? = null,
    val suggested: Int? = null,
    /** H5: this week's heart points pace (T1 weeklyGoals). */
    val pace: com.boudhealth.data.CardioPace? = null,
    // Sleep extras
    val nights: List<SleepNight> = emptyList(),
    val stages: List<SleepStage> = emptyList(),
)

internal fun loadDetail(env: MetricEnv, id: String, ds0: DScope): DetailModel {
    val today = env.today
    val dataStart = V2.dataStart(env)
    val ds = ds0.std?.let { DScope.of(it.refreshed(today, dataStart)) } ?: ds0
    val p = ds.period
    val e = V2.engine(env)
    val end = minOf(p.last, today)
    val card = V2.card(env, id, p)
    val spec = V2Metrics.get(id)
    val dailyGoal = V2Math.dailyGoal(id, env.goals)
    // 3M: daily buckets over the three months (sections never show 3M, so the engine's month grain is replaced here)
    // heart points: the zone buckets (fat burn · cardio · peak minutes per bar, points as the value), like the card
    var buckets = (if (id == "zone_min") card.zones?.perBucket else card.series?.buckets).orEmpty()
    var grain = (if (id == "zone_min") card.zones?.grain else card.series?.grain) ?: V2Math.grainFor(p)
    var goal = card.value?.goal?.value
    var trend = card.trend
    if (ds.is3M && spec != null) {
        grain = Grain.DAY
        buckets = if (id == "zone_min") {
            val months = generateSequence(p.start) { it.plusMonths(1) }.takeWhile { !it.isAfter(p.last) }.toList()
            months.flatMap { m -> V2.zones(env, SectionPeriod.Month(m.year, m.monthValue)).perBucket }
        } else if (end.isBefore(p.start)) emptyList()
        else V2Math.buckets(e.daily(id, p.start, end), p, Grain.DAY, if (id == "sleep") BucketAgg.LAST else spec.agg, today)
        val days = p.totalDays()
        goal = when (id) { "zone_min" -> env.goals.zoneMinWeek * days / 7.0; else -> dailyGoal?.let { it * days } }
        // trend vs the previous 3 months, cut at the same number of elapsed days
        if (id in TOTALS) {
            val elapsed = p.elapsedDays(today).toLong()
            val pStart = p.start.minusMonths(3)
            val cur = card.value?.value
            val prev = e.daily(id, pStart, pStart.plusDays(elapsed - 1)).values.sum()
            if (cur != null && elapsed > 0) {
                val ch = cur - prev
                trend = PeriodTrend(id, cur, prev, ch, if (prev > 0) ch / prev * 100 else null,
                    if (ch > 0) TrendDir.UP else if (ch < 0) TrendDir.DOWN else TrendDir.FLAT,
                    spec.higherIsBetter?.let { if (ch == 0.0) null else (ch > 0) == it }, TrendBasis.PREVIOUS_SAME_ELAPSED)
            }
        }
    }
    // one source per number: T1 V2Restore (dayList, goalDays, stats, zoneLimits, sessionDetails, weeklyHeartPoints, weeklyGoals)
    val now = java.time.LocalDateTime.now(env.zone)
    val history = if (id in TOTALS && !end.isBefore(dataStart)) e.dayList(id, end, minOf(120L, java.time.temporal.ChronoUnit.DAYS.between(dataStart, end) + 1).toInt())
        .filter { (it.value ?: 0.0) > 0 } else emptyList()
    val goalDays = if (id in setOf("exercise_min", "steps", "zone_min") && p !is SectionPeriod.Day && !end.isBefore(p.start))
        e.goalDays(p, today).perGoal.firstOrNull { it.metricId == id }?.let { it.met to it.days } else null
    val stats = if (id in TOTALS) runCatching { e.stats(id, p, now) }.getOrNull() else null
    var m = DetailModel(ds, p, dataStart, card, card.value?.value, goal, trend, buckets, grain,
        if (grain == Grain.DAY) card.series?.goalPerBucket ?: dailyGoal else null, card.series?.gap ?: SeriesGap.NONE, history, goalDays, dailyGoal, stats)
    if (id == "zone_min") {
        m = DetailModel(m.ds, p, dataStart, card, m.total, m.goal, m.trend, m.buckets, m.grain, m.goalPerBucket, m.gap, m.history, m.goalDays, dailyGoal, stats,
            e.zoneLimits(today), e.sessionDetails(p, today), e.weeklyHeartPoints(52, today), env.goals.zoneMinWeek, V2.suggestedHeartPointsTarget(env),
            e.weeklyGoals(now).firstOrNull { it.metricId == "zone_min" }?.status)
    }
    if (id == "sleep") {
        val nights = V2.sleep(env, p)
        val stages = if (p is SectionPeriod.Day) runCatching { env.cache.sleepStages(p.start) }.getOrDefault(emptyList()) else emptyList()
        m = DetailModel(m.ds, p, dataStart, card, m.total, m.goal, m.trend, m.buckets, m.grain, null, m.gap, m.history, null, null, null,
            nights = nights, stages = stages)
    }
    return m
}

/** Detail page for exercise_min, steps, zone_min (heart_zones / weekly_cardio), streak, calories; sleep → [SleepDetailScreen]. */
@Composable
fun ActivityDetailScreen(metricId: String, nav: Nav, defaultUnit: PeriodUnit = PeriodUnit.WEEK) {
    val id = when (metricId) { "heart_zones", "weekly_cardio" -> "zone_min"; else -> metricId }
    if (id == "sleep") { SleepDetailScreen(-1L, nav); return }
    val ctx = LocalContext.current
    val hb = LocalHB.current
    val version by Sync.version.collectAsState()
    // owner: "Workout minutes misses a 'today' 1D": totals open on today unless a card handed off its period
    val dn = rememberDetailNav(id, if (id == "exercise_min" || id == "steps" || id == "calories") PeriodUnit.DAY else defaultUnit)
    val scope = dn.scope
    var load by remember { mutableStateOf<DetailModel?>(null) }
    var zoneView by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(scope, version) { load = runCatching { V2.load(ctx) { env -> loadDetail(env, id, scope) } }.getOrNull() }
    val l = load
    val title = when (id) {
        "zone_min" -> ActivityTexts.HEART_POINTS; "exercise_min" -> ActivityTexts.WORKOUT_MIN; "steps" -> ActivityTexts.STEPS
        "streak" -> ActivityTexts.STREAK; else -> ActivityTexts.CALORIES
    }
    val today = LocalDate.now()
    SectionDetailScaffold(title, nav, dn, l?.dataStart ?: today) {
        if (l == null) { SkeletonBox(Modifier.fillMaxWidth(), height = 320.dp, radius = 22.dp); return@SectionDetailScaffold }
        val p = l.p
        val pick = { d: LocalDate -> dn.pick(d, today) }
        Column(Modifier.alpha(if (l.ds.period != scope.period) 0.6f else 1f)) {
            when (id) {
                "streak" -> {
                    MetricCardFrame(title, Glyph.Flame, ActivityColors.flame(hb), info = ActivityTexts.INFO_WORKOUT_DAYS) {
                        StreakHeadline(p, l.card)
                        Spacer(Modifier.height(12.dp))
                        StreakGraph(p, l.card, today, onTap = pick)
                    }
                }
                else -> {
                    val z = zoneView
                    if (id == "zone_min" && z != null) { ZoneView(l, z, today) { zoneView = null }; return@Column }
                    TotalDetail(id, title, l, today, pick, onZone = { zoneView = it })
                }
            }
            if (id in TOTALS) {
                val unit = when (id) { "exercise_min" -> "min"; "calories" -> "kcal"; "zone_min" -> "pts"; else -> "" }
                DetailHeader(ActivityTexts.DAYS)
                DayHistoryList(l.history, unit, onDay = pick)
            }
        }
    }
}

@Composable
private fun TotalDetail(id: String, title: String, l: DetailModel, today: LocalDate, pick: (LocalDate) -> Unit, onZone: (Int) -> Unit = {}) {
    val hb = LocalHB.current
    val p = l.p
    var goalSheet by remember { mutableStateOf(false) }
    val (glyph, color, unit) = when (id) {
        "exercise_min" -> Triple(Glyph.Zone, ActivityColors.workout(hb), "min")
        "steps" -> Triple(Glyph.Steps, ActivityColors.steps(hb), "")
        "zone_min" -> Triple(Glyph.Heart, ActivityColors.heart(hb), "pts")
        else -> Triple(Glyph.Flame, ActivityColors.calories(hb), "kcal")
    }
    val zones = id == "zone_min"
    MetricCardFrame(title, glyph, color, info = if (zones) ActivityTexts.INFO_HEART_POINTS else null, trailing = { TrendArrow(l.trend) }) {
        Headline(l.total?.let { SFmt.int(it) } ?: "—", l.goal?.takeIf { p !is SectionPeriod.All || l.ds.is3M }?.let { SFmt.int(it) },
            unit.ifEmpty { null }, size = 34f)
        l.goalDays?.let { (n, m) -> Text(ActivityTexts.goalMet(id, n, m), fontSize = 13.sp, color = hb.colors.muted, modifier = Modifier.padding(top = 2.dp)) }
        Spacer(Modifier.height(12.dp))
        if (l.gap != SeriesGap.NONE && p is SectionPeriod.Day) {
            TotalGraph(p, l.card, color, today, onTap = pick, height = 220.dp)
        } else {
            DetailBars(p, l.buckets, l.grain, color, unit, "$title chart", decimals = 0,
                goal = l.goalPerBucket?.takeIf { l.grain == Grain.DAY && !zones },
                segmentColors = if (zones) ActivityColors.zones(hb) else null,
                segmentNames = if (zones) ActivityTexts.ZONES else null)
        }
        if (zones) { Spacer(Modifier.height(10.dp)); ZoneLegend(l.card.zones?.minutes ?: IntArray(4)) }
    }
    Spacer(Modifier.height(12.dp))
    // D1: Total · Best day (Peak hour on 1D) · Goal — T1 stats
    val st = l.stats
    val u = unit.ifEmpty { null }
    val best = if (p is SectionPeriod.Day)
        Stat(ActivityTexts.PEAK_HOUR, st?.peakHour?.let { String.format(java.util.Locale.US, "%02d:00", it.hour) } ?: "—", st?.peakValue?.let { SFmt.int(it) + (u?.let { x -> " $x" } ?: "") })
    else Stat(ActivityTexts.BEST_DAY, st?.bestValue?.let { SFmt.int(it) } ?: "—", st?.bestDay?.let { SFmt.shortDate(it) })
    StatRow(listOfNotNull(
        Stat(ActivityTexts.TOTAL, (st?.total ?: l.total)?.let { SFmt.int(it) } ?: "—", u),
        best,
        l.dailyGoal?.let { g -> Stat(ActivityTexts.GOAL, SFmt.int(if (zones) (l.weeklyTarget ?: 0).toDouble() else g), if (zones) "/ week" else u) },
    ))
    // D4 / H6: goal button where you look at it
    if (id != "calories") {
        Spacer(Modifier.height(12.dp))
        val label = when (id) { "steps" -> ActivityTexts.ROW_STEPS; "exercise_min" -> ActivityTexts.ROW_WORKOUT_MIN; else -> ActivityTexts.ROW_HEART_POINTS }
        val value = if (zones) l.weeklyTarget?.toDouble() else l.dailyGoal
        GoalRow(label, value?.let { SFmt.int(it) } ?: "—", sub = if (zones) l.suggested?.let { "${ActivityTexts.ROW_SUGGESTED} ${SFmt.int(it.toDouble())}" } else null,
            pace = if (zones) l.pace else null) { goalSheet = true }
    }
    if (zones) HeartPointsExtras(l, today, pick, onZone)
    if (goalSheet) com.boudhealth.ui.health.GoalSheet(id) { goalSheet = false }
}

@Composable
private fun GoalRow(label: String, value: String, sub: String?, pace: com.boudhealth.data.CardioPace? = null, onClick: () -> Unit) {
    val hb = LocalHB.current
    Row(Modifier.fillMaxWidth().background(hb.colors.card, HbShapes.card).plainClickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = hb.colors.text)
            if (sub != null) Text(sub, fontSize = 12.5.sp, color = hb.colors.muted)
        }
        // H5: this week's pace as one icon (▲ ahead · ● on track · ▼ behind · ✓ met)
        if (pace != null) { com.boudhealth.ui.section.PaceChip(pace); Spacer(Modifier.width(10.dp)) }
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = hb.colors.text)
        Spacer(Modifier.width(6.dp))
        GlyphIcon(Glyph.ChevR, size = 16.dp, color = hb.colors.muted)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Heart points extras (H1 zone limits, H2 sessions with zone split, H3 weekly totals vs target, per-zone history)
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun HeartPointsExtras(l: DetailModel, today: LocalDate, pick: (LocalDate) -> Unit, onZone: (Int) -> Unit) {
    val hb = LocalHB.current
    val colors = ActivityColors.zones(hb)
    l.limits?.let { z ->
        DetailHeader(ActivityTexts.HEART_ZONES)
        val mins = l.card.zones?.minutes ?: IntArray(4)
        Column(Modifier.fillMaxWidth().background(hb.colors.card, HbShapes.card).padding(vertical = 6.dp)) {
            val rows = listOf("${z.fatBurnFrom}–${z.cardioFrom - 1}", "${z.cardioFrom}–${z.peakFrom - 1}", "≥ ${z.peakFrom}")
            ActivityTexts.ZONES.forEachIndexed { i, name ->
                // H7: tap a zone → its own view (minutes per day + the sessions that filled it)
                Row(Modifier.fillMaxWidth().plainClickable { onZone(i) }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(colors[i], CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = hb.colors.text)
                        Text("${rows[i]} bpm", fontSize = 12.5.sp, color = hb.colors.muted)
                    }
                    Text("${SFmt.int(mins.getOrElse(i + 1) { 0 }.toDouble())} min", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = hb.colors.text)
                    Spacer(Modifier.width(6.dp))
                    GlyphIcon(Glyph.ChevR, size = 16.dp, color = hb.colors.muted)
                }
            }
            Text(ActivityTexts.maxRest(z.maxHr, z.restHr), fontSize = 12.5.sp, color = hb.colors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
    }
    // H3: weekly totals vs the target (13 / 52 weeks)
    if (l.weeks.isNotEmpty()) {
        var n by remember { mutableStateOf(13) }
        DetailHeader(ActivityTexts.WEEKS, trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterPill("13", n == 13, { n = 13 }); FilterPill("52", n == 52, { n = 52 })
            }
        })
        val ws = l.weeks.takeLast(n)
        val p = SectionPeriod.All(ws.first().monday.toEpochDay(), ws.last().monday.plusDays(6).toEpochDay())
        val buckets = ws.map { w -> SeriesBucket(w.monday.atStartOfDay(), w.monday.plusWeeks(1).atStartOfDay(), w.points.toDouble().takeIf { w.hasData }, partial = w.partial) }
        HBCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            DetailBars(p, buckets, Grain.DAY, ActivityColors.heart(hb), "pts", "Weekly heart points", goal = l.weeklyTarget?.toDouble(),
                height = 180.dp, state = remember(n, l.weeks) { com.boudhealth.ui.components.TimeChartState() })
        }
    }
    // H2: sessions with their zone split
    SessionZoneList(l.sessions.take(12), null)
}

/** Sessions with their zone bar; [zone] (0 fat burn · 1 cardio · 2 peak) = only that zone's minutes, most first. */
@Composable
private fun SessionZoneList(sessions: List<com.boudhealth.data.SessionDetail>, zone: Int?) {
    val hb = LocalHB.current
    val colors = ActivityColors.zones(hb)
    val list = if (zone == null) sessions.filter { it.zoneMinutes != null }
        else sessions.filter { (it.zoneMinutes?.getOrNull(zone + 1) ?: 0) > 0 }.sortedByDescending { it.zoneMinutes!![zone + 1] }
    if (list.isEmpty()) return
    DetailHeader(ActivityTexts.WORKOUTS)
    Column(Modifier.fillMaxWidth().background(hb.colors.card, HbShapes.card).padding(vertical = 6.dp)) {
        list.forEach { sd ->
            val w = sd.workout; val z = sd.zoneMinutes!!
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphCircle(WorkoutUi.glyph(w), WorkoutUi.color(w), size = 34.dp, glyphSize = 18.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(WorkoutUi.name(w), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = hb.colors.text, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(if (zone == null) "${sd.points ?: 0} pts" else "${z[zone + 1]} min", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = hb.colors.text)
                    }
                    Text("${SFmt.shortDate(WorkoutUi.day(w))}  ${Fmt.hm(w.start)} · ${z[1]} · ${z[2]} · ${z[3]} min", fontSize = 12.sp, color = hb.colors.muted, maxLines = 1)
                    Spacer(Modifier.height(5.dp))
                    SegPill((1..3).map { k -> z[k].toDouble() to colors[k - 1].copy(alpha = if (zone == null || zone == k - 1) 1f else 0.3f) }, Modifier.fillMaxWidth(), h = 6.dp)
                }
            }
        }
    }
}

/** H7: one zone for the selected period: minutes per bucket (read-out, zoom) and the sessions that contributed. */
@Composable
private fun ZoneView(l: DetailModel, zone: Int, today: LocalDate, onClose: () -> Unit) {
    val hb = LocalHB.current
    val c = ActivityColors.zones(hb)[zone]
    val name = ActivityTexts.ZONES[zone]
    androidx.activity.compose.BackHandler(onBack = onClose)
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        com.boudhealth.ui.components.IconCircleButton(Glyph.Back, onClose, contentDescription = "Back", size = 36.dp, glyphSize = 18.dp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(10.dp).background(c, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = hb.colors.text)
    }
    val mins = l.card.zones?.minutes?.getOrNull(zone + 1) ?: 0
    MetricCardFrame(name, Glyph.Heart, c) {
        Headline(SFmt.int(mins.toDouble()), null, "min", size = 34f)
        l.limits?.let { z ->
            val r = listOf("${z.fatBurnFrom}–${z.cardioFrom - 1}", "${z.cardioFrom}–${z.peakFrom - 1}", "≥ ${z.peakFrom}")[zone]
            Text("$r bpm", fontSize = 13.sp, color = hb.colors.muted)
        }
        Spacer(Modifier.height(12.dp))
        val buckets = l.buckets.map { b -> b.copy(value = b.segments?.getOrNull(zone), segments = null) }
        DetailBars(l.p, buckets, l.grain, c, "min", "$name minutes", state = remember(zone, l) { com.boudhealth.ui.components.TimeChartState() })
    }
    SessionZoneList(l.sessions, zone)
}

// =====================================================================================================================
// Sleep detail (design6 sleep_detail_*, restore S1/S2): 1D hypnogram (stages at real times) + share per stage + In bed
// and Asleep (no stages) separately; ≥ 1W: nights chart (read-out) + nights list; 3M included.
// =====================================================================================================================

/** Sleep detail; [wakeEpochDay] ≥ 0 opens that night (Route.Sleep). */
@Composable
fun SleepDetailScreen(wakeEpochDay: Long, nav: Nav) {
    val ctx = LocalContext.current
    val hb = LocalHB.current
    val version by Sync.version.collectAsState()
    val today = LocalDate.now()
    val handoff = remember { com.boudhealth.ui.section.SectionScopes.takeHandoff("sleep") }
    val dn = remember(wakeEpochDay) {
        val base = handoff?.let { com.boudhealth.data.SectionScope(it, null, it.isCurrent(today)) }
            ?: com.boudhealth.data.SectionScope(SectionPeriod.current(PeriodUnit.WEEK, today, today.minusYears(10)), null, true)
        val night = if (wakeEpochDay >= 0) LocalDate.ofEpochDay(wakeEpochDay) else null
        DetailNav(DScope.of(when {
            night == null -> base
            handoff != null && handoff !is SectionPeriod.Day && handoff.contains(night) -> base.select(night)
            else -> com.boudhealth.data.SectionScope(SectionPeriod.Day(night.toEpochDay()), null, night == today)
        }))
    }
    val scope = dn.scope
    var load by remember { mutableStateOf<DetailModel?>(null) }
    LaunchedEffect(scope, version) { load = runCatching { V2.load(ctx) { env -> loadDetail(env, "sleep", scope) } }.getOrNull() }
    val l = load
    SectionDetailScaffold(ActivityTexts.SLEEP, nav, dn, l?.dataStart ?: today) {
        if (l == null) { SkeletonBox(Modifier.fillMaxWidth(), height = 320.dp, radius = 22.dp); return@SectionDetailScaffold }
        val p = l.p
        val pick = { d: LocalDate -> dn.pick(d, today) }
        Column(Modifier.alpha(if (l.ds.period != scope.period) 0.6f else 1f)) {
            val nights = l.nights
            if (nights.isEmpty() && l.buckets.none { it.value != null }) {
                EmptyState(Glyph.Moon, ActivityColors.sleep(hb), title = ActivityTexts.EMPTY_SLEEP, body = "")
                return@Column
            }
            HBCard(Modifier.fillMaxWidth()) {
                GlyphIcon(Glyph.Moon, size = 20.dp, color = ActivityColors.sleep(hb))
                Spacer(Modifier.height(10.dp))
                SleepHero(p, l.card, nights)
                Spacer(Modifier.height(14.dp))
                when {
                    p is SectionPeriod.Day -> nights.lastOrNull()?.let { n ->
                        Hypnogram(l.stages, n)
                        Spacer(Modifier.height(14.dp))
                        StageTable(n)
                    }
                    p.unit == PeriodUnit.YEAR || (p is SectionPeriod.All && !l.ds.is3M) ->
                        l.card.series?.let { s -> RangeBars(s.buckets, s.grain, p, ActivityColors.sleep(hb), today, height = 220.dp) }
                    else -> {
                        val byDay = nights.associateBy { it.date }
                        val buckets = generateSequence(p.start) { it.plusDays(1) }.takeWhile { !it.isAfter(p.last) }.map { day ->
                            val n = byDay[day]
                            SeriesBucket(day.atStartOfDay(), day.plusDays(1).atStartOfDay(), n?.info?.asleepMin?.toDouble(),
                                segments = n?.let { night -> stageParts(night, hb).map { it.first }.toDoubleArray() }, future = day.isAfter(today))
                        }.toList()
                        DetailBars(p, buckets, Grain.DAY, ActivityColors.sleep(hb), "min", "Sleep per night",
                            segmentColors = ActivityColors.stages(hb), segmentNames = listOf("Deep", "REM", "Light", "Awake"))
                    }
                }
            }
            if (p !is SectionPeriod.Day && nights.isNotEmpty()) {
                DetailHeader(ActivityTexts.NIGHTS)
                NightsList(nights.sortedByDescending { it.date }, onNight = { pick(it.date) })
            }
        }
    }
}

@Composable
private fun SleepHero(p: SectionPeriod, d: CardData, nights: List<SleepNight>) {
    val hb = LocalHB.current
    if (p is SectionPeriod.Day) {
        val n = nights.lastOrNull()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Headline(n?.let { SFmt.hm(it.info.asleepMin) } ?: "—", size = 36f)
            Spacer(Modifier.weight(1f))
            if (n != null) Text("${Fmt.hm(n.info.start)} – ${Fmt.hm(n.info.end)}", fontSize = 14.sp, color = hb.colors.muted)
        }
        return
    }
    val mins = nights.map { it.info.asleepMin }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        val mn = mins.minOrNull() ?: d.value?.min?.toInt(); val mx = mins.maxOrNull() ?: d.value?.max?.toInt()
        Headline(if (mn != null && mx != null) "${SFmt.hm(mn)} – ${SFmt.hm(mx)}" else "—", size = 32f)
        Spacer(Modifier.weight(1f))
        val k = nights.size
        if (k > 0) Text(if (k == 1) "1 night" else "$k nights", fontSize = 14.sp, color = hb.colors.muted)
    }
}

/** S1: the night's stages at their real times: one lane per stage (Awake · REM · Light · Deep), hour ticks. */
@Composable
private fun Hypnogram(stages: List<SleepStage>, night: SleepNight) {
    val hb = LocalHB.current
    val measurer = rememberTextMeasurer()
    val c = ActivityColors.stages(hb)
    val lanes = listOf("awake" to c[3], "rem" to c[1], "light" to c[2], "deep" to c[0])
    val segs = stages.filter { it.end > it.start }
    if (segs.isEmpty()) { SegPill(stageParts(night, hb), Modifier.fillMaxWidth(), h = 14.dp); return }
    val t0 = minOf(night.info.start, segs.minOf { it.start }); val t1 = maxOf(night.info.end, segs.maxOf { it.end })
    val label = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = hb.colors.muted)
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val left = 44.dp.toPx(); val axisH = 18.dp.toPx()
        val w = size.width - left; val laneH = (size.height - axisH) / lanes.size
        fun x(t: Long) = left + (t - t0).toFloat() / (t1 - t0).coerceAtLeast(1L).toFloat() * w
        lanes.forEachIndexed { i, (name, col) ->
            val y = i * laneH
            drawLine(hb.colors.grid.copy(alpha = 0.4f), Offset(left, y + laneH / 2f), Offset(size.width, y + laneH / 2f), 0.6.dp.toPx())
            val t = measurer.measure(when (name) { "rem" -> "REM"; else -> name.replaceFirstChar { it.uppercase() } }, label)
            drawText(t, topLeft = Offset(0f, y + laneH / 2f - t.size.height / 2f))
            segs.filter { s -> s.stage == name || (name == "light" && s.stage == "asleep") }.forEach { s ->
                drawRoundRect(col.copy(alpha = if (s.stage == "asleep") 0.55f else 1f), Offset(x(s.start), y + laneH * 0.18f),
                    Size((x(s.end) - x(s.start)).coerceAtLeast(1.5f), laneH * 0.64f), CornerRadius(2.dp.toPx()))
            }
        }
        // hour ticks
        val zone = ZoneId.systemDefault()
        var h = Instant.ofEpochMilli(t0).atZone(zone).withMinute(0).withSecond(0).withNano(0).plusHours(1)
        while (h.toInstant().toEpochMilli() < t1) {
            if (h.hour % 2 == 0) {
                val tx = x(h.toInstant().toEpochMilli())
                val t = measurer.measure(String.format(java.util.Locale.US, "%02d", h.hour), label)
                drawText(t, topLeft = Offset(tx - t.size.width / 2f, size.height - axisH + 3.dp.toPx()))
            }
            h = h.plusHours(1)
        }
    }
}

/** S2: minutes and share of each stage, plus In bed and Asleep (no stages) as separate totals. */
@Composable
private fun StageTable(n: SleepNight) {
    val hb = LocalHB.current
    val c = ActivityColors.stages(hb)
    val i = n.info
    val tot = (i.deepMin + i.remMin + i.lightMin + i.awakeMin + i.asleepUnspecifiedMin).coerceAtLeast(1)
    fun pct(m: Int) = "${Math.round(m * 100.0 / tot)} %"
    val rows = buildList {
        add(Triple("Deep", i.deepMin, c[0])); add(Triple("REM", i.remMin, c[1])); add(Triple("Light", i.lightMin, c[2])); add(Triple("Awake", i.awakeMin, c[3]))
        if (i.asleepUnspecifiedMin > 0) add(Triple(com.boudhealth.data.SleepInfo.UNSPECIFIED_LABEL, i.asleepUnspecifiedMin, c[2].copy(alpha = 0.55f)))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { (name, m, col) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(col, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(name, fontSize = 13.5.sp, color = hb.colors.text, modifier = Modifier.weight(1f))
                Text(pct(m), fontSize = 13.sp, color = hb.colors.muted, modifier = Modifier.padding(end = 14.dp))
                Text(SFmt.hm(m), fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = hb.colors.text)
            }
        }
        Row(Modifier.padding(top = 4.dp)) {
            Text(ActivityTexts.IN_BED, fontSize = 13.5.sp, color = hb.colors.muted, modifier = Modifier.weight(1f))
            Text(SFmt.hm(((i.end - i.start) / 60_000L).toInt()), fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = hb.colors.text)
        }
    }
}

@Composable
private fun NightsList(nights: List<SleepNight>, onNight: (SleepNight) -> Unit) {
    val hb = LocalHB.current
    val longest = nights.maxOfOrNull { it.info.end - it.info.start }?.coerceAtLeast(1L) ?: 1L
    Column(Modifier.fillMaxWidth().background(hb.colors.card, HbShapes.card).padding(vertical = 6.dp)) {
        nights.forEachIndexed { i, n ->
            if (i > 0) Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(1.dp).background(hb.colors.tileHi))
            Row(Modifier.fillMaxWidth().plainClickable { onNight(n) }.padding(horizontal = 20.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Insights.dayLabel(n.date), fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = hb.colors.text, maxLines = 1)
                    Text("${Fmt.hm(n.info.start)} – ${Fmt.hm(n.info.end)}", fontSize = 12.5.sp, color = hb.colors.muted, maxLines = 1)
                }
                Column(Modifier.weight(1.2f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(SFmt.hm(n.info.asleepMin), fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = hb.colors.text)
                    // S3: bar length follows the night's length (short nights look short)
                    val f = ((n.info.end - n.info.start).toFloat() / longest).coerceIn(0.15f, 1f)
                    SegPill(stageParts(n, hb), Modifier.fillMaxWidth(f), h = 6.dp)
                }
                Spacer(Modifier.width(10.dp))
                GlyphIcon(Glyph.ChevR, size = 16.dp, color = hb.colors.muted)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Compat entries for retired routes (decode-only per V2_PLAN §2.1): they open the new pages.
// ---------------------------------------------------------------------------------------------------------------------

/** `Route.Calories` → Calories burned detail. */
@Composable fun CaloriesScreen(nav: Nav) = ActivityDetailScreen("calories", nav)

/** `Route.HeartZones` → Heart points detail. */
@Composable fun HeartZonesScreen(nav: Nav) = ActivityDetailScreen("zone_min", nav)

/** `Route.WeeklyCardio` → Heart points detail at 1W. */
@Composable fun WeeklyCardioScreen(nav: Nav) = ActivityDetailScreen("zone_min", nav, PeriodUnit.WEEK)

