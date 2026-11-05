@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.boudhealth.ui.health

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.data.Metric
import com.boudhealth.data.Metrics
import com.boudhealth.data.Range
import com.boudhealth.data.Sync
import com.boudhealth.data.Window
import com.boudhealth.ui.components.ButtonKind
import com.boudhealth.ui.components.ChartCallout
import com.boudhealth.ui.components.ChartGrain
import com.boudhealth.ui.components.CalloutLine
import com.boudhealth.ui.components.ChartSel
import com.boudhealth.ui.components.DetailScaffold
import com.boudhealth.ui.components.EmptyState
import com.boudhealth.ui.components.Fmt
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.GlyphIcon
import com.boudhealth.ui.components.HBBottomSheet
import com.boudhealth.ui.components.HBButton
import com.boudhealth.ui.components.HeaderAction
import com.boudhealth.ui.components.LocalScreen
import com.boudhealth.ui.components.LocalSnackbar
import com.boudhealth.ui.components.MetricHero
import com.boudhealth.ui.components.MetricSkeleton
import com.boudhealth.ui.components.NormalBand
import com.boudhealth.ui.components.PeriodNavigator
import com.boudhealth.ui.components.RefLine
import com.boudhealth.ui.components.SectionHeader
import com.boudhealth.ui.components.SegmentSize
import com.boudhealth.ui.components.SegmentedRange
import com.boudhealth.ui.components.SheetActions
import com.boudhealth.ui.components.SourceChip
import com.boudhealth.ui.components.Stat
import com.boudhealth.ui.components.StatRow
import com.boudhealth.ui.components.TLevel
import com.boudhealth.ui.components.TOverlay
import com.boudhealth.ui.components.TPoint
import com.boudhealth.ui.components.TimeChart
import com.boudhealth.data.Pt
import com.boudhealth.ui.components.Dot
import com.boudhealth.ui.components.TimeChartState
import com.boudhealth.ui.components.TimeStyle
import com.boudhealth.ui.components.TwoPane
import com.boudhealth.ui.components.WeekZoneLine
import com.boudhealth.ui.components.ZoneBand
import com.boudhealth.ui.components.ZoneLabel
import com.boudhealth.ui.components.hbClickable
import com.boudhealth.ui.nav.LocalPrefs
import com.boudhealth.ui.nav.Nav
import com.boudhealth.ui.nav.Route
import com.boudhealth.ui.theme.HbShapes
import com.boudhealth.ui.theme.LocalHB
import com.boudhealth.ui.theme.lerpColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * Single-metric detail page (B_SPEC §1): one template for every metric in the registry.
 * Waist has its own specialised page; composite / workout tiles open the page that shows them best.
 */
@Composable
fun MetricDetailScreen(metricId: String, nav: Nav) {
    // V2: Activity metrics with their own detail page (T2: Heart points, Calories burned, Sleep)
    if (metricId in com.boudhealth.ui.activity.ACTIVITY_DETAIL_IDS) { com.boudhealth.ui.activity.ActivityDetailScreen(metricId, nav); return }
    when (metricId) {
        "waist" -> { WaistScreen(nav); return }
        // V2-082 (V7-R11): "lean_fat" (a v5 alias kept for stored layouts) is a composite without a series, so its page
        // always said "no lean mass in this period". It is the Lean mass page.
        "lean_fat" -> { MetricTemplate(Metrics.find("lean_mass") ?: return, nav, handoffKey = metricId); return }
        "today_rings" -> { Redirect(nav, Route.Activity); return }   // v3: Activity is the one "today" page
        "week_workouts", "streak" -> { Redirect(nav, Route.Workouts); return }
        "last_workout" -> { LastWorkoutRedirect(nav); return }
    }
    val m = Metrics.find(metricId)
    if (m == null) {
        val hb = LocalHB.current
        DetailScaffold("Not found", onBack = { nav.back() }) {
            Spacer(Modifier.height(24.dp))
            EmptyState(Glyph.Info, hb.colors.muted, "Unknown metric", "\"$metricId\" isn't a metric Health BB knows.")
        }
        return
    }
    MetricTemplate(m, nav)
}

@Composable
private fun Redirect(nav: Nav, route: Route) {
    val hb = LocalHB.current
    LaunchedEffect(route) { nav.replace(route) }
    Box(Modifier.fillMaxSize().background(hb.colors.surface))
}

@Composable
private fun LastWorkoutRedirect(nav: Nav) {
    val hb = LocalHB.current
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        val start = Metrics.latest(ctx, "last_workout")?.parts?.get("start")?.toLong()
        nav.replace(if (start != null) Route.Session(start) else Route.Workouts)
    }
    Box(Modifier.fillMaxSize().background(hb.colors.surface))
}

@Composable
private fun MetricTemplate(m: Metric, nav: Nav, handoffKey: String = m.id) {
    val hb = LocalHB.current
    val ctx = LocalContext.current
    val prefs = LocalPrefs.current
    val screen = LocalScreen.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val (ranges, defRange) = remember(m.id) { detailRanges(m) }
    // V2-086: open in the period of the section card that was tapped (B16); detail pages keep 3M, 1Y = calendar year (D8)
    val handoff = remember(handoffKey) {
        (com.boudhealth.ui.section.SectionScopes.takeHandoff(handoffKey) ?: com.boudhealth.ui.section.SectionScopes.takeHandoff(m.id))
            ?.let { HealthPeriods.fromSection(it, ranges) }
    }
    var range by rememberSaveable(m.id) { mutableStateOf(handoff?.first ?: defRange) }
    var offset by rememberSaveable(m.id) { mutableIntStateOf(handoff?.second ?: 0) }
    var showGoal by rememberSaveable { mutableStateOf(false) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    val w = remember(range, offset) { HealthPeriods.window(range, offset) }
    // Q23: zoom + selection of the chart; a new window (range chip, ‹ ›) starts unzoomed with nothing selected
    val chartState = remember(m.id, w) { TimeChartState() }
    val chartBiv = remember { BringIntoViewRequester() }
    val version by Sync.version.collectAsState()
    val data by produceState<DetailData?>(null, m.id, range, offset, version) { value = loadDetail(ctx, m, w) }
    val color = metricColor(m.id)
    val spec = goalSpec(m.id)
    val today = LocalDate.now()

    val actions = buildList {
        // D10: the zone-minutes explainer sits behind a small (i), never always visible
        if (spec != null) add(HeaderAction(Glyph.Target, "Goal") { showGoal = true })
    }
    val d = data?.takeIf { it.window == w } ?: data
    val r = d?.latest
    val subtitle = r?.let { "Updated ${Fmt.timestamp(it.time)}" }

    val rangeControl: @Composable () -> Unit = {
        if (ranges.size > 1) {
            // v4.1 review: the chips always show the range; zooming gets its own "Reset zoom" control above the chart
            SegmentedRange(
                ranges, range,
                { sel -> if (sel == range) scope.launch { chartState.resetZoom(hb.reducedMotion) } else { range = sel; offset = 0 } },
                label = { it.label },
                size = if (ranges.size >= 7) SegmentSize.Small else SegmentSize.Regular,
            )
        }
    }

    // v6 rules: no source lines, no 3-dot menu
    DetailScaffold(
        displayTitle(m), onBack = { nav.back() }, actions = actions,
        subtitle = subtitle,
        sticky = if (screen.wide) null else rangeControl,
    ) {
        // Q23: a tap anywhere outside the chart (and its callout) clears the selection
        val boxCoords = remember { arrayOfNulls<LayoutCoordinates>(1) }
        Box(
            Modifier.fillMaxWidth()
                .onGloballyPositioned { boxCoords[0] = it }
                .pointerInput(chartState) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (chartState.selection == null) return@awaitEachGesture
                        val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
                        if ((up.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
                        val c = boxCoords[0]?.takeIf { it.isAttached } ?: return@awaitEachGesture
                        if (!chartState.contains(c.localToRoot(down.position))) chartState.selection = null
                    }
                },
        ) {
            TwoPane(
                left = {
                    if (screen.wide) { Spacer(Modifier.height(20.dp)); rangeControl() }
                    Spacer(Modifier.height(16.dp))
                    val (pl, ps) = HealthPeriods.label(w, today)
                    if (range != Range.ALL) PeriodNavigator(
                        pl, ps, onPrev = { offset += 1 }, onNext = { offset = (offset - 1).coerceAtLeast(0) },
                        nextEnabled = HealthPeriods.hasNext(w),
                    )
                    Spacer(Modifier.height(24.dp))
                    if (d == null) MetricSkeleton(chartHeight = if (screen.wide) 300.dp else 240.dp)
                    else {
                        HeroAndChart(
                            m, d, color, chartState, Modifier.bringIntoViewRequester(chartBiv),
                        )
                    }
                },
                right = {
                    if (d != null) {
                        Spacer(Modifier.height(if (screen.wide) 20.dp else 24.dp))
                        MetricStats(m, d)
                        val model = d.chart
                        val useList = model != null && !model.hourly && model.readings.isNotEmpty()
                        if (useList) {
                            ReadingsList(m, d, model, chartState, color, onRowSelected = {
                                // narrow: the chart is above the list; bring it back into view to show the highlight
                                if (!screen.wide) scope.launch { chartBiv.bringIntoView() }
                            })
                        }
                        Spacer(Modifier.height(12.dp))
                        // v5: no explanation cards (facts only)
                        if (!useList) MetricHistory(m, d, showAll, onToggleAll = { showAll = !showAll })
                    }
                },
            )
        }
    }

    if (showGoal && spec != null) GoalSheet(m.id, onDismiss = { showGoal = false })
}

// =====================================================================================================================
// Hero + chart
// =====================================================================================================================

@Composable
private fun HeroAndChart(
    m: Metric, d: DetailData, color: Color, state: TimeChartState, chartModifier: Modifier,
) {
    val hb = LocalHB.current
    val prefs = LocalPrefs.current
    val screen = LocalScreen.current
    val w = d.window
    val today = LocalDate.now()
    val hero = heroFor(m, d, prefs.goals, today)
    val band = d.latest?.band
    val chartH = if (screen.wide) 300.dp else 240.dp
    val key = w.range to w.offset
    val model = d.chart
    val goal = m.goal(prefs.goals)

    // R6 item 1 (owner): 1D of a one-value metric = that day's value with its date (muted "Last measured {date}" when it
    // is from an earlier day, as VO₂ 1D did), then the chart of the 7 days ending on the day (as the tabs)
    if (d.dayView) {
        val pk = d.dayPick
        OneDayHero(displayLabel(m), color, pk?.pt?.v, metricUnit(m), m.decimals, pk?.pt?.date, pk?.carried ?: false, today, key,
            valueText = { v -> fmtValue(m.id, v, m.decimals) })
    } else SelectionHero(m, d, hero, color, state, key)
    // C1: the zone-minutes detail shows the week as the same bar as under the rings (1W)
    if (m.id == "zone_min" && w.range == Range.WEEK) {
        Spacer(Modifier.height(14.dp))
        WeekZoneLine(d.periodDaily.sumOf { it.v }, prefs.goals.zoneMinWeek, barHeight = 6.dp, fontSize = 13.sp)
    }
    Spacer(Modifier.height(12.dp))

    if (model == null || model.levels.firstOrNull()?.points.isNullOrEmpty()) {
        if (w.range == Range.DAY && !d.dayView && d.periodDaily.isNotEmpty()) EmptyState(
            metricGlyph(m.id), color, "Hourly data not stored for this day", "", modifier = Modifier.height(chartH),
        ) else NoData(m, color, chartH)
        return
    }

    val bars = model.bars
    val style = when { bars -> TimeStyle.Bars; isMeasurement(m.id) -> TimeStyle.Dots; else -> TimeStyle.Line }
    val hrBandOn = m.id == "hr" && !model.hourly && (d.extra["hr_min"].orEmpty().isNotEmpty() || d.extra["hr_max"].orEmpty().isNotEmpty())
    val zoomable = !model.hourly && !hrBandOn && w.range in setOf(Range.QUARTER, Range.YEAR, Range.ALL)
    val unitShort = shortUnit(m) ?: ""
    val barGoal = if (bars && isTotal(m.id) && !model.hourly) goal else null
    val refs = buildList {
        if (bars) { if (barGoal != null) add(RefLine(barGoal, "Goal ${Fmt.compact(barGoal)}")) }
        else if (goal != null) add(RefLine(goal, "Goal ${Fmt.num(goal, if (goal % 1.0 == 0.0) 0 else 1)} $unitShort".trim()))
    }
    // V7-S1: heart rate (1W and longer) is a FILLED min–max band (per day; per month for 1Y / All), not two dashed lines
    val hrBand = if (m.id == "hr" && !model.hourly) detailHrBand(d, today) else null
    val chartOverlays = emptyList<TOverlay>()
    val catColor: (String) -> Color = { id -> overlayColor(id) }
    val callout: (ChartSel, TLevel, TPoint) -> ChartCallout? = { _, lv, p -> buildCallout(m, model, lv, p, goal, band, catColor, today) }
    val yFormat: (Double) -> String = { if (m.id == "sleep") "${Fmt.num(it)}h" else Fmt.compact(it, if (m.decimals > 0 && it % 1.0 != 0.0) 1 else 0) }
    val description = chartDescription(m, model, w, today, zoomable)

    // v4.1 review: zoom reset is its own control above the chart (no 7th "Custom" range chip)
    if (zoomable && state.zoomed) {
        val zscope = rememberCoroutineScope()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            HBButton("Reset zoom", { zscope.launch { state.resetZoom(hb.reducedMotion) } }, kind = ButtonKind.Text, small = true, glyph = Glyph.Undo)
        }
    }
    // V7-S2: VO₂ max keeps its large trend arrow + the change vs 3 months ago
    if (m.id == "vo2" && !d.dayView) d.extra["vo2_change"]?.firstOrNull()?.v?.let { ch ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            val dir = if (abs(ch) < 0.05) 0 else if (ch > 0) 1 else -1
            BigTrendArrow(dir, if (dir == 0) null else ch > 0)
            Spacer(Modifier.width(14.dp))
            Column {
                // R5 #41: zero reads "Same" (not "±0")
                val same = isZeroDiff(m, ch)
                Text(if (same) "Same" else signedDiff(m, ch) + " " + metricUnit(m), style = hb.type.titleS, color = hb.colors.text, maxLines = 1)
                Text(if (same) "as 3 months ago" else "vs 3 months ago", style = hb.type.bodyXS, color = hb.colors.muted, maxLines = 1)
            }
        }
    }
    if (hrBand != null && hrBand.buckets.isNotEmpty()) {
        // R5 #36: the band chart gets the same TalkBack summary as TimeChart
        BandChart(hrBand.buckets, hrBand.grain, hrBand.period, today, color, height = chartH,
            modifier = chartModifier.semantics { contentDescription = description })
    } else if (m.id == "vo2") {
        Vo2Chart(model, color, chartH, prefs.goals.birthYear, callout, description, state, chartModifier, key)
    } else {
        TimeChart(
            state = state, levels = model.levels, start = model.start, end = model.end, color = color,
            callout = callout, description = description, modifier = chartModifier, height = chartH, style = style,
            barColors = metricBarColors(m.id), goal = barGoal, refs = refs,
            band = null,   // facts only: no "normal" range (V7-R4)
            area = !bars && (m.personalBand || m.id == "hr"),
            overlays = chartOverlays,
            minPad = if (m.decimals > 0) 0.5 else 1.0,
            yFormat = yFormat, secondaryFormat = { "${Fmt.num(it)}${com.boudhealth.ui.section.R3Texts.PCT_SP}%" },
            tagFormat = { fmtValue(m.id, it, m.decimals) },
            breakGaps = !isMeasurement(m.id), zoomable = zoomable, animateKey = key,
        )
    }
    // R5 #33: one zoom reset only (the "Reset zoom" above the chart); the footer keeps its 4 dp gap
    Spacer(Modifier.height(4.dp))
    if (m.id == "weight") {
        WeightComposition(d.extra["comp_lean"].orEmpty(), d.extra["comp_fat"].orEmpty(), d.extra["comp_pct"].orEmpty(), state, model.start, model.end)
    }
}

/**
 * R6 item 1: hero of a one-value page on 1D (also the Waist page): the day's [value] with its [date]; when [carried] (from
 * an earlier day) or missing, the number is muted and the context reads "Last measured {date}". Approved texts only.
 */
@Composable
internal fun OneDayHero(
    label: String, color: Color, value: Double?, unit: String, decimals: Int, date: LocalDate?, carried: Boolean,
    today: LocalDate, key: Any, valueText: (Double) -> String = { Fmt.num(it, decimals) },
) {
    val hb = LocalHB.current
    MetricHero(
        label, color, value, unit.ifEmpty { null }, decimals = decimals,
        context = date?.let { if (carried) com.boudhealth.ui.section.R3Texts.lastMeasured(it, today) else Fmt.dayLabel(it, today) },
        animateKey = key,
        valueContent = {
            Text(buildAnnotatedString {
                withStyle(SpanStyle(fontSize = 44.sp, fontWeight = FontWeight.Bold, color = if (carried || value == null) hb.colors.muted else hb.colors.text)) {
                    append(value?.let(valueText) ?: "—")
                }
                if (unit.isNotEmpty()) withStyle(SpanStyle(fontSize = 18.sp, color = hb.colors.muted)) { append(" $unit") }
            }, maxLines = 1)
        },
    )
}

/** Hero that mirrors the selected point (value + date), like the old scrub; deltas fade while a point is selected. */
@Composable
private fun SelectionHero(m: Metric, d: DetailData, hero: HeroInfo, color: Color, state: TimeChartState, key: Any) {
    val sel = state.selection
    val lv = sel?.let { state.levels.getOrNull(it.level) }
    val p = state.point(sel)
    val model = d.chart
    val ctx = if (p == null || lv == null || model == null) null else when {
        model.hourly -> hourTitle(p)
        lv.bucketed -> bucketTitle(p, lv.grain) + " · ${p.n} ${readingNoun(m.id, p.n)}" + if (p.n > 1) " · ${fmtValue(m.id, p.lo, m.decimals)}–${fmtValue(m.id, p.hi, m.decimals)}" else ""
        else -> model.readings.getOrNull(p.ref)?.let { readingTitle(it) }
    }
    MetricHero(
        // v5: a selected week / month shows its range in the context, never an average as the value
        label = hero.label, color = color, value = if (lv?.bucketed == true) hero.value else p?.v ?: hero.value, unit = if (m.id == "sleep") null else metricUnit(m),
        decimals = m.decimals, context = ctx ?: hero.context, deltas = hero.deltas, scrubbing = p != null, animateKey = key,
        valueText = if (m.id == "sleep" || m.id == "height") ({ v -> fmtValue(m.id, v, m.decimals) }) else null,
    )
    // keep the pill height while a point is selected so the chart never jumps
    if (p != null && hero.deltas.isNotEmpty()) Spacer(Modifier.height(38.dp))
}

private fun hourTitle(p: TPoint): String {
    val loc = Locale.ENGLISH
    return DateTimeFormatter.ofPattern("EEE d MMM yyyy · HH:mm", loc).format(p.at) + "–" + DateTimeFormatter.ofPattern("HH:mm", loc).format(p.end)
}

/** True when [diff] rounds to zero in the metric's own format (R5 #41: shown as "Same", never "±0"). */
private fun isZeroDiff(m: Metric, diff: Double): Boolean =
    if (m.id == "sleep") Math.round(diff * 60) == 0L else Math.round(diff * Math.pow(10.0, m.decimals.toDouble())) == 0L

/** Signed difference in the metric's own format ("−0.3", "+25m"). */
private fun signedDiff(m: Metric, diff: Double): String = when (m.id) {
    "sleep" -> {
        val min = Math.round(diff * 60).toInt()
        if (min == 0) "±0m" else (if (min > 0) "+" else "−") + Fmt.duration(abs(min))
    }
    else -> {
        val f = Math.pow(10.0, m.decimals.toDouble())
        if (Math.round(diff * f) == 0L) "±0" else Fmt.delta(diff, m.decimals)
    }
}

/** Callout content for a selected point (Q23): date, exact value, overlays, source, delta vs the previous reading. */
private fun buildCallout(
    m: Metric, model: ChartModel, lv: TLevel, p: TPoint, goal: Double?, band: Pair<Double, Double>?,
    catColor: (String) -> Color, today: LocalDate,
): ChartCallout {
    val unit = if (m.id == "sleep") "" else metricUnit(m)
    val unitTail = if (unit.isEmpty()) "" else " $unit"
    fun f(v: Double) = fmtValue(m.id, v, m.decimals)
    if (model.hourly) {
        val totalNote = if (isTotal(m.id)) "this hour" else null
        return ChartCallout(hourTitle(p), f(p.v), unit, note = totalNote)
    }
    if (lv.bucketed) {
        val lines = buildList {
            val noun = readingNoun(m.id, p.n)
            if (isTotal(m.id)) add(CalloutLine("Days with data", "${p.n}"))
            else add(CalloutLine("${p.n} $noun", ""))
        }
        return ChartCallout(
            title = bucketTitle(p, lv.grain), value = if (p.n > 1) "${f(p.lo)}–${f(p.hi)}" else f(p.hi), unit = unit,
            lines = lines, delta = null, note = null,
            action = "Show ${readingNoun(m.id, 2)}",
        )
    }
    val r = model.readings.getOrNull(p.ref) ?: return ChartCallout(bucketTitle(p, lv.grain), f(p.v), unit)
    val prev = model.readings.getOrNull(p.ref - 1) ?: model.before
    val lines = buildList {
        if (m.id == "weight") {
            r.extras["body_fat"]?.let { add(CalloutLine("Body fat percentage", "${Fmt.num(it, 1)}${com.boudhealth.ui.section.R3Texts.PCT_SP}%", catColor("body_fat"))) }
            
            r.extras["lean_mass"]?.let { add(CalloutLine("Lean mass", "${Fmt.num(it, 1)} kg", catColor("lean_mass"))) }
        }
        if (isTotal(m.id) && goal != null && goal > 0) {
            add(CalloutLine("Goal ${Fmt.compact(goal)}", if (r.v >= goal) "met" else "${pct(r.v, goal)}"))
        }
    }
    val delta = prev?.let { if (isZeroDiff(m, r.v - it.v)) "Same as ${shortDay(it.day, today)}" else "${signedDiff(m, r.v - it.v)}$unitTail vs ${shortDay(it.day, today)}" }
    val note = when {
        isTotal(m.id) && r.day == today -> "So far today"
        else -> null
    }
    return ChartCallout(readingTitle(r), f(r.v), unit, lines = lines, delta = delta, note = note)
}

/** TalkBack summary of a chart. */
private fun chartDescription(m: Metric, model: ChartModel, w: Window, today: LocalDate, zoomable: Boolean): String {
    val (pl, ps) = HealthPeriods.label(w, today)
    val pts = model.levels.firstOrNull()?.points.orEmpty()
    val unit = if (m.id == "sleep") "" else " " + metricUnit(m)
    val range = if (pts.isEmpty()) "no data" else {
        val lo = pts.minOf { it.v }; val hi = pts.maxOf { it.v }
        "${if (model.hourly) "${pts.size} hours" else "${pts.size} ${readingNoun(m.id, pts.size)}"}, from ${fmtValue(m.id, lo, m.decimals)} to ${fmtValue(m.id, hi, m.decimals)}$unit"
    }
    return "${displayLabel(m)} chart, $pl${ps?.let { ", $it" } ?: ""}: $range. Tap or drag to read a value" + if (zoomable) "; pinch or double-tap to zoom." else "."
}

private fun overlayColor(id: String): Color = when (id) {
    "body_fat" -> com.boudhealth.ui.theme.Cat.fat
    else -> com.boudhealth.ui.theme.Cat.lean
}

/**
 * v5 S1: a small chart under the weight chart, on the same time axis (follows the main chart's zoom) and with the same
 * scrub: the main chart's selected time draws a marker here with this series' value nearest to it.
 */
/**
 * v6 I4 (design5/weight_composition.png): per weigh-in a stacked bar lean kg (bottom) + fat kg (top), with the fat % line
 * on its own scale; same time axis and zoom as the weight chart, and its scrub: the selected weigh-in is highlighted.
 */
@Composable
private fun WeightComposition(lean: List<Pt>, fat: List<Pt>, pct: List<Pt>, state: TimeChartState,
                              start: java.time.LocalDateTime, end: java.time.LocalDateTime) {
    val hb = LocalHB.current
    if (lean.isEmpty()) return
    val cLean = overlayColor("lean_mass"); val cFat = com.boudhealth.ui.theme.Cat.fat
    val selAt = state.point()?.at
    val near = selAt?.let { t -> lean.minByOrNull { kotlin.math.abs(java.time.Duration.between(it.at, t).toMinutes()) } }
    val shown = near ?: lean.last()
    val fatAt = fat.firstOrNull { it.at == shown.at }?.v; val pctAt = pct.firstOrNull { it.at == shown.at }?.v
    Spacer(Modifier.height(16.dp))
    // R5 #32: the legend wraps at narrow widths (≈ 330 dp on one line)
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(cLean, size = 8.dp); Spacer(Modifier.width(4.dp)); Text("Lean mass ${Fmt.num(shown.v, 1)} kg", style = hb.type.labelS, color = hb.colors.text)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(cFat, size = 8.dp); Spacer(Modifier.width(4.dp)); Text("Fat mass ${fatAt?.let { Fmt.num(it, 1) } ?: "–"} kg", style = hb.type.labelS, color = hb.colors.text)
        }
        Text("Body fat ${pctAt?.let { Fmt.num(it, 1) } ?: "–"}${com.boudhealth.ui.section.R3Texts.PCT_SP}%", style = hb.type.labelS, color = hb.colors.muted)
    }
    Spacer(Modifier.height(6.dp))
    val t0 = com.boudhealth.ui.components.chartMinutes(start); val t1 = com.boudhealth.ui.components.chartMinutes(end)
    val txt = hb.colors.text
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val pr = size.width - 36.dp.toPx()
        val ok = state.visibleEnd > state.visibleStart
        val v0 = if (ok) com.boudhealth.ui.components.chartMinutes(state.visibleStart) else t0
        val v1 = if (ok) com.boudhealth.ui.components.chartMinutes(state.visibleEnd) else t1
        fun x(p: Pt) = (((com.boudhealth.ui.components.chartMinutes(p.at) - v0) / (v1 - v0).coerceAtLeast(1.0)) * pr).toFloat()
        val fatBy = fat.associateBy { it.at }
        val vis = lean.filter { x(it) in 0f..pr }
        val top = vis.maxOfOrNull { it.v + (fatBy[it.at]?.v ?: 0.0) }?.times(1.05) ?: 1.0
        val bw = (pr / (vis.size.coerceAtLeast(1) * 1.6f)).coerceIn(2.dp.toPx(), 10.dp.toPx())
        vis.forEach { l ->
            val cx = x(l); val f = fatBy[l.at]?.v ?: 0.0
            val hl = (size.height * (l.v / top)).toFloat(); val hf = (size.height * (f / top)).toFloat()
            val a = if (near == null || l.at == near.at) 1f else 0.45f
            drawRect(cLean.copy(alpha = a), androidx.compose.ui.geometry.Offset(cx - bw / 2, size.height - hl), androidx.compose.ui.geometry.Size(bw, hl))
            drawRect(cFat.copy(alpha = a), androidx.compose.ui.geometry.Offset(cx - bw / 2, size.height - hl - hf), androidx.compose.ui.geometry.Size(bw, hf))
        }
        val pv = pct.filter { x(it) in 0f..pr }
        if (pv.size >= 2) {
            val lo = pv.minOf { it.v } - 1; val hi = pv.maxOf { it.v } + 1
            val path = androidx.compose.ui.graphics.Path()
            pv.forEachIndexed { i, p -> val y = (size.height * (1 - (p.v - lo) / (hi - lo))).toFloat(); if (i == 0) path.moveTo(x(p), y) else path.lineTo(x(p), y) }
            drawPath(path, txt, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        }
    }
}

/** VO₂ chart with the category zones (B_SPEC §1 Chart), on the shared interactive time chart. */
@Composable
private fun Vo2Chart(
    model: ChartModel, color: Color, chartH: androidx.compose.ui.unit.Dp, birthYear: Int?,
    callout: (ChartSel, TLevel, TPoint) -> ChartCallout?, description: String, state: TimeChartState, modifier: Modifier, key: Any,
) {
    val hb = LocalHB.current
    val scale = vo2Scale(birthYear)
    val cuts = scale.cuts
    val pts = model.levels.first().points
    val dataLo = pts.minOf { it.v }; val dataHi = pts.maxOf { it.v }
    val lo = minOf(cuts[0] - 4.0, dataLo - 1.0)
    val hi = maxOf(cuts[2] + 3.0, dataHi + 1.0)
    val current = scale.zoneOf(pts.last().v)
    val steps = if (hb.dark) listOf(0.03f, 0.06f, 0.10f, 0.15f) else listOf(0.025f, 0.045f, 0.075f, 0.11f)
    val bounds = listOf(lo, cuts[0], cuts[1], cuts[2], hi)
    val zones = buildList {
        for (i in 0..3) {
            val c = if (i == current) color.copy(alpha = if (hb.dark) 0.14f else 0.12f) else hb.colors.text.copy(alpha = steps[i])
            add(ZoneBand(bounds[i], bounds[i + 1], c))
        }
        // 2 dp surface cuts between zones
        val cut = (hi - lo) * 0.006
        for (c in cuts) add(ZoneBand(c - cut, c + cut, hb.colors.surface))
    }
    val labels = (0..3).map { i ->
        val cur = i == current
        ZoneLabel(bounds[i], bounds[i + 1], scale.names[i], if (cur) hb.legible(color, hb.colors.surface) else hb.colors.muted, bold = cur)
    }
    // light: lerp the green towards text until it reads on the green band (raw in dark)
    val line = if (hb.dark) color else lerpColor(color, hb.colors.text, 0.45f)
    TimeChart(
        state = state, levels = model.levels, start = model.start, end = model.end, color = line,
        callout = callout, description = description, modifier = modifier, height = chartH, style = TimeStyle.Dots,
        zones = zones, zoneLabels = labels, yMin = lo, yMax = hi, yTicks = cuts, gridLines = false,
        yFormat = { Fmt.num(it) }, tagFormat = { Fmt.num(it, 1) }, showExtremes = false,
        zoomable = model.levels.first().points.size > 1 && ChronoUnit.DAYS.between(model.start, model.end) > 60, animateKey = key,
    )
}

// =====================================================================================================================
// Measurements list (Q23): every reading of the visible range, newest first, grouped by month with month averages.
// Months collapse (only open months compose their rows), so 2+ years stay fast. Row ↔ chart selection is shared.
// =====================================================================================================================

@Composable
private fun ReadingsList(m: Metric, d: DetailData, model: ChartModel, state: TimeChartState, color: Color, onRowSelected: () -> Unit) {
    val hb = LocalHB.current
    val prefs = LocalPrefs.current
    val today = LocalDate.now()
    val full = model.start.toLocalDate() to model.end.toLocalDate().minusDays(1)
    // follows the chart's zoom, settled for 200 ms so a pinch doesn't rebuild the list every frame
    var visible by remember(model) { mutableStateOf(full) }
    LaunchedEffect(state, model) {
        snapshotFlow { if (state.zoomed) state.visibleStart.toLocalDate() to state.visibleEnd.minusMinutes(1).toLocalDate() else full }
            .collectLatest { delay(200); visible = it }
    }
    val months = remember(model, visible) { readingMonths(model.readings, visible.first, visible.second) }
    if (months.isEmpty()) return
    var open by remember(model) { mutableStateOf(setOfNotNull(months.firstOrNull()?.month)) }
    var monthLimit by remember(model) { mutableIntStateOf(12) }
    // the selected reading (from the chart or a row); its month opens automatically
    val sel = state.selection
    val selRef: Int? = sel?.let { s -> state.levels.getOrNull(s.level)?.takeIf { !it.bucketed }?.points?.getOrNull(s.index)?.ref?.takeIf { it >= 0 } }
    val selMonth = selRef?.let { model.readings.getOrNull(it) }?.let { java.time.YearMonth.from(it.day) }
    val shownOpen = open + listOfNotNull(selMonth)
    val count = months.sumOf { it.indices.size }
    val unit = if (m.id == "sleep") null else shortUnit(m)
    val noun = readingNoun(m.id, count)
    val goal = m.goal(prefs.goals)
    val band = d.latest?.band
    val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

    SectionHeader(if (isMeasurement(m.id)) "Measurements" else "History")
    Text(
        "$count $noun · ${Fmt.range(visible.first, minOf(visible.second, today))}" + if (state.zoomed) " · zoomed" else "",
        style = hb.type.bodyXS, color = hb.colors.muted, modifier = Modifier.padding(bottom = 4.dp),
    )
    Column {
        months.take(monthLimit).forEach { mo ->
            val isOpen = mo.month in shownOpen
            val n = mo.indices.size
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .hbClickable(scaleOnPress = false, shape = HbShapes.tileS) { open = if (mo.month in open) open - mo.month else open + mo.month }
                    .semantics { stateDescription = if (isOpen) "Expanded" else "Collapsed" }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(monthFmt.format(mo.month), style = hb.type.titleS, color = hb.colors.text, maxLines = 1)
                    Text("$n ${readingNoun(m.id, n)}", style = hb.type.bodyXS, color = hb.colors.muted, maxLines = 1)
                }
                Column(horizontalAlignment = Alignment.End) {
                    val vs = mo.indices.mapNotNull { model.readings.getOrNull(it)?.v }
                    Text(if (vs.size > 1) fmtValue(m.id, vs.min(), m.decimals) + "–" + fmtValue(m.id, vs.max(), m.decimals) + (unit?.let { " $it" } ?: "")
                        else vs.firstOrNull()?.let { fmtValue(m.id, it, m.decimals) + (unit?.let { u -> " $u" } ?: "") } ?: "", style = hb.type.bodyMedium, color = hb.colors.text, maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
                GlyphIcon(if (isOpen) Glyph.ChevU else Glyph.ChevD, size = 20.dp, color = hb.colors.muted)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(hb.colors.grid))
            if (isOpen) mo.indices.forEach { i ->
                val r = model.readings[i]
                val prev = model.readings.getOrNull(i - 1) ?: model.before
                val isSel = i == selRef
                val sub = buildList {
                    if (m.id == "weight") r.extras["body_fat"]?.let { add("Body fat ${Fmt.num(it, 1)}${com.boudhealth.ui.section.R3Texts.PCT_SP}%") }
                    else historySubtitle(m, r.v, band, goal).first?.takeIf { it != "Weigh-in" && it != "Overnight" }?.let { add(it) }
                }.joinToString(" · ")
                val title = Fmt.dayLabel(r.day, today) + if (r.timed) " · " + DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH).format(r.at) else ""
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(HbShapes.tileS)
                        .background(if (isSel) hb.tint(if (color == hb.colors.text) hb.colors.accent else color) else Color.Transparent)
                        .hbClickable(scaleOnPress = false, shape = HbShapes.tileS) { state.selectReading(i); onRowSelected() }
                        .semantics { if (isSel) stateDescription = "Selected on the chart" }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = hb.type.titleS, color = hb.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (sub.isNotEmpty()) Text(sub, style = hb.type.bodyXS, color = hb.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(fmtValue(m.id, r.v, m.decimals) + (unit?.let { " $it" } ?: ""), style = hb.type.titleS, color = hb.colors.text, maxLines = 1)
                        val ch = prev?.let { changeText(r.v, it.v, m.decimals) }
                        if (ch != null && m.id != "sleep") Text(ch, style = hb.type.bodyXS.copy(fontSize = 12.sp), color = hb.colors.muted, maxLines = 1)
                    }
                }
            }
        }
        if (months.size > monthLimit) {
            Spacer(Modifier.height(8.dp))
            HBButton("Show older months (${months.size - monthLimit})", { monthLimit += 12 }, kind = ButtonKind.Tonal, small = true)
        }
    }
}

@Composable
private fun NoData(m: Metric, color: Color, height: androidx.compose.ui.unit.Dp = 216.dp) {
    // V2_TEXTS §4: icon + one line, no second sentence
    EmptyState(metricGlyph(m.id), color, "No data for this period", "", modifier = Modifier.height(height))
}

// =====================================================================================================================
// Stats, about, history
// =====================================================================================================================

@Composable
private fun MetricStats(m: Metric, d: DetailData) {
    val prefs = LocalPrefs.current
    val u = shortUnit(m)
    fun f(v: Double?) = v?.let { fmtValue(m.id, it, m.decimals) } ?: "–"
    val unitFor: String? = if (m.id == "sleep") null else u
    val stats = when {
        // v5 D2: no fat mass kg, no averages
        m.id == "lean_fat" -> {
            val l = d.extra["lean_mass"]?.lastOrNull()?.v
            listOf(Stat("Lean mass", f(l), "kg"))
        }
        isTotal(m.id) && d.window.range == Range.DAY -> {
            val total = d.periodDaily.firstOrNull()?.v ?: d.series.sumOf { it.v }
            val peak = d.series.maxOfOrNull { it.v }
            val goal = m.goal(prefs.goals)
            listOf(Stat("Total", Fmt.compact(total), unitFor), Stat("Peak hour", peak?.let { Fmt.compact(it) } ?: "–", unitFor),
                Stat("Goal", goal?.let { Fmt.compact(it) } ?: "–", unitFor))
        }
        isTotal(m.id) -> {
            val vals = d.periodDaily.map { it.v }
            listOf(
                Stat("Total", if (vals.isEmpty()) "–" else Fmt.compact(vals.sum()), unitFor),
                Stat("Best day", vals.maxOrNull()?.let { Fmt.compact(it) } ?: "–", unitFor),
            )
        }
        else -> {
            val s = Metrics.stats(if (d.window.range == Range.DAY || d.periodDaily.isEmpty()) d.series else d.periodDaily)
            listOf(Stat("Lowest", f(s.min), unitFor), Stat("Highest", f(s.max), unitFor))
        }
    }
    StatRow(stats)
}

@Composable
private fun MetricHistory(m: Metric, d: DetailData, showAll: Boolean, onToggleAll: () -> Unit) {
    val hb = LocalHB.current
    val prefs = LocalPrefs.current
    val today = LocalDate.now()
    val rows = d.history.reversed()
    if (rows.isEmpty()) return
    val fatByDay = d.extra["fat_hist"]?.associate { it.date to it.v }.orEmpty()
    SectionHeader("History", action = if (rows.size > 7) (if (showAll) "Show less" else "See all") else null, onAction = onToggleAll)
    val shown = if (showAll) rows.take(120) else rows.take(7)
    val goal = m.goal(prefs.goals)
    val unit = if (m.id == "sleep") null else shortUnit(m)
    var lastMonth: String? = null
    val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
    Column {
        shown.forEachIndexed { i, p ->
            if (showAll) {
                val mLabel = monthFmt.format(p.date)
                if (mLabel != lastMonth) {
                    lastMonth = mLabel
                    Text(mLabel, style = hb.type.label, color = hb.colors.muted, modifier = Modifier.padding(top = if (i == 0) 4.dp else 20.dp, bottom = 4.dp))
                }
            }
            val prev = rows.getOrNull(i + 1)?.v   // shown is a prefix of rows (m12: was rows.indexOf, quadratic)
            val (sub, bad) = if (m.id == "lean_fat") ((fatByDay[p.date]?.let { "Fat ${Fmt.num(it, 1)} kg" }) to false)
            else historySubtitle(m, p.v, d.latest?.band, goal)
            HistoryRow(
                title = Fmt.dayLabel(p.date, today), value = fmtValue(m.id, p.v, m.decimals), unit = unit,
                subtitle = sub, subtitleBad = bad,
                change = if (m.kind == com.boudhealth.data.MetricKind.MEASUREMENT || m.personalBand) changeText(p.v, prev, m.decimals) else null,
                divider = i < shown.lastIndex,
            )
        }
    }
}


/** Heart rate band of a detail window: daily min–max (1W · 1M · 3M), monthly min–max (1Y · All). */
private class DetailBand(val buckets: List<com.boudhealth.data.SeriesBucket>, val grain: com.boudhealth.data.Grain, val period: com.boudhealth.data.SectionPeriod)

private fun detailHrBand(d: DetailData, today: LocalDate): DetailBand? {
    val lo = d.extra["hr_min"].orEmpty().associate { it.at.toLocalDate() to it.v }
    val hi = d.extra["hr_max"].orEmpty().associate { it.at.toLocalDate() to it.v }
    if (lo.isEmpty() && hi.isEmpty()) return null
    val w = d.window
    val first = (lo.keys + hi.keys).minOrNull() ?: return null
    val start = if (w.range == Range.ALL) first else w.start
    val end = minOf(w.end.minusDays(1), today)
    val monthly = w.range == Range.YEAR || w.range == Range.ALL
    val period: com.boudhealth.data.SectionPeriod = when (w.range) {
        Range.WEEK -> com.boudhealth.data.SectionPeriod.Week(w.start.toEpochDay())
        Range.MONTH -> com.boudhealth.data.SectionPeriod.Month(w.start.year, w.start.monthValue)
        Range.YEAR -> com.boudhealth.data.SectionPeriod.Year(w.start.year)
        Range.ALL -> com.boudhealth.data.SectionPeriod.All(start.toEpochDay(), today.toEpochDay())
        else -> com.boudhealth.data.SectionPeriod.Day(w.start.toEpochDay())
    }
    val out = ArrayList<com.boudhealth.data.SeriesBucket>()
    if (monthly) {
        var m0 = start.withDayOfMonth(1)
        val lastM = if (w.range == Range.YEAR) w.start.plusMonths(11) else end.withDayOfMonth(1)
        while (!m0.isAfter(lastM)) {
            val m1 = m0.plusMonths(1)
            val mins = lo.filterKeys { !it.isBefore(m0) && it.isBefore(m1) }.values
            val maxs = hi.filterKeys { !it.isBefore(m0) && it.isBefore(m1) }.values
            out += com.boudhealth.data.SeriesBucket(m0.atStartOfDay(), m1.atStartOfDay(), null, mins.minOrNull(), maxs.maxOrNull(), future = m0.isAfter(today))
            m0 = m1
        }
    } else {
        var day = start
        val last = w.end.minusDays(1)
        while (!day.isAfter(last)) {
            out += com.boudhealth.data.SeriesBucket(day.atStartOfDay(), day.plusDays(1).atStartOfDay(), null, lo[day], hi[day], future = day.isAfter(today))
            day = day.plusDays(1)
        }
    }
    return DetailBand(out, if (monthly) com.boudhealth.data.Grain.MONTH else com.boudhealth.data.Grain.DAY, period)
}
