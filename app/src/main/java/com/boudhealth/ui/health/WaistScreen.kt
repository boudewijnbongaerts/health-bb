package com.boudhealth.ui.health

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import com.boudhealth.ui.components.GlyphIcon
import com.boudhealth.ui.components.plainClickable
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import com.boudhealth.ui.components.CalloutLine
import com.boudhealth.ui.components.ChartCallout
import com.boudhealth.ui.components.ChartGrain
import com.boudhealth.ui.components.ChartSel
import com.boudhealth.ui.components.TLevel
import com.boudhealth.ui.components.TPoint
import com.boudhealth.ui.components.TimeChart
import com.boudhealth.ui.components.TimeChartState
import com.boudhealth.ui.components.TimeStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.boudhealth.data.DEFAULT_HEIGHT_CM
import com.boudhealth.data.Pt
import com.boudhealth.data.Range
import com.boudhealth.data.Sample
import com.boudhealth.data.Sync
import com.boudhealth.ui.components.ButtonKind
import com.boudhealth.ui.components.ChartSeries
import com.boudhealth.ui.components.ChartTooltip
import com.boudhealth.ui.components.Delta
import com.boudhealth.ui.components.DetailScaffold
import com.boudhealth.ui.components.EmptyState
import com.boudhealth.ui.components.Fmt
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.HBButton
import com.boudhealth.ui.components.HeaderAction
import com.boudhealth.ui.components.LineChart
import com.boudhealth.ui.components.LocalScreen
import com.boudhealth.ui.components.LocalSnackbar
import com.boudhealth.ui.components.MetricHero
import com.boudhealth.ui.components.PeriodNavigator
import com.boudhealth.ui.components.RefLine
import com.boudhealth.ui.components.SectionHeader
import com.boudhealth.ui.components.SegmentedRange
import com.boudhealth.ui.components.SourceChip
import com.boudhealth.ui.components.Stat
import com.boudhealth.ui.components.StatRow
import com.boudhealth.ui.components.TwoPane
import com.boudhealth.ui.components.ZoneBand
import com.boudhealth.ui.components.deltaDir
import com.boudhealth.ui.components.deltaKind
import com.boudhealth.ui.nav.LocalPrefs
import com.boudhealth.ui.nav.LocalStore
import com.boudhealth.ui.nav.Nav
import com.boudhealth.ui.nav.Route
import com.boudhealth.ui.nav.rememberPrefsEditor
import com.boudhealth.ui.theme.Cat
import com.boudhealth.ui.theme.LocalHB
import java.time.LocalDate
import kotlin.math.abs

private val WAIST_RANGES = listOf(Range.MONTH, Range.QUARTER, Range.YEAR, Range.ALL)

/**
 * Waist page (UI_PLAN #8): the metric template specialised for waist. Entries come straight from the Store
 * (manual + tape), so a new reading or a delete shows at once; WHtR 0.5 / 0.6 lines from the height.
 */
@Composable
fun WaistScreen(nav: Nav) {
    val hb = LocalHB.current
    val ctx = LocalContext.current
    val prefs = LocalPrefs.current
    val screen = LocalScreen.current
    val store = LocalStore.current
    val edit = rememberPrefsEditor()
    val snackbar = LocalSnackbar.current
    // V2-086: open in the period of the Waist card that was tapped (B16)
    val handoff = remember { com.boudhealth.ui.section.SectionScopes.takeHandoff("waist")?.let { HealthPeriods.fromSection(it, WAIST_RANGES) } }
    var range by rememberSaveable { mutableStateOf(handoff?.first ?: Range.YEAR) }
    var offset by rememberSaveable { mutableIntStateOf(handoff?.second ?: 0) }
    var whtrInfo by remember { mutableStateOf(false) }
    // v4.1: TimeChart (tap / scrub / callout); a new window starts with nothing selected
    var showGoal by rememberSaveable { mutableStateOf(false) }
    var addWaist by rememberSaveable { mutableStateOf(false) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val entries by store.waist.collectAsState(initial = emptyList())
    val version by Sync.version.collectAsState()
    val hc by produceState<Double?>(null, version) { value = hcHeightM(ctx) }
    // v3: WHtR lines always draw; without a stored height they use the profile default height
    val storedHeight = effectiveHeightCm(hc, prefs.heightCm)
    val heightIsDefault = storedHeight == null
    val heightCm: Double = storedHeight ?: DEFAULT_HEIGHT_CM.toDouble()
    val w = remember(range, offset) { HealthPeriods.window(range, offset) }
    val chartState = remember(range, offset) { TimeChartState() }
    val today = LocalDate.now()
    val target = prefs.goals.targetWaistCm

    val all = entries.map { Pt(Fmt.dateTime(it.time), it.value) }
    val inWin = all.filter { !it.date.isBefore(w.start) && it.date.isBefore(w.end) }
    val last = entries.lastOrNull()
    val prev = entries.getOrNull(entries.size - 2)

    val rangeControl: @Composable () -> Unit = { SegmentedRange(WAIST_RANGES, range, { range = it; offset = 0 }, label = { it.label }) }

    DetailScaffold(
        "Waist circumference", onBack = { nav.back() },
        actions = listOf(HeaderAction(Glyph.Target, "Waist target") { showGoal = true }),
        subtitle = last?.let { "Measured ${Fmt.timestamp(it.time)}" },
        sticky = if (screen.wide) null else rangeControl,
    ) {
        // tap outside the chart clears its selection (as on the metric pages)
        val boxCoords = remember { arrayOfNulls<LayoutCoordinates>(1) }
        Box(
            Modifier.fillMaxWidth().onGloballyPositioned { boxCoords[0] = it }.pointerInput(chartState) {
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
                if (range != Range.ALL) PeriodNavigator(pl, ps, onPrev = { offset += 1 },
                    onNext = { offset = (offset - 1).coerceAtLeast(0) }, nextEnabled = HealthPeriods.hasNext(w))
                Spacer(Modifier.height(24.dp))

                // ---- hero (follows the chart selection)
                val scrub: TPoint? = chartState.point()
                val value = if (w.offset == 0) last?.value else inWin.lastOrNull()?.v
                val whtr = value?.let { it / heightCm }
                // v5 S3: values + coloured bands, no text
                // the WHtR with its (i) sits under the hero (below)
                val context: String? = if (value == null) "No data for this period" else null
                val deltas = if (w.offset == 0 && last != null && prev != null) {
                    val ch = last.value - prev.value
                    listOf(Delta(if (Math.round(abs(ch) * 10) == 0L) "Same as ${Fmt.shortDate(Fmt.date(prev.time))}" else "${Fmt.num(abs(ch), 1)} cm vs ${Fmt.shortDate(Fmt.date(prev.time))}",
                        deltaKind(ch, false, 0.2), deltaDir(ch, 0.05)))
                } else emptyList()
                MetricHero(
                    "Waist circumference", Cat.waist, scrub?.v ?: value, "cm", decimals = 1,
                    context = scrub?.let { p -> Fmt.dayLabel(p.at.toLocalDate(), today) + " · WHtR ${Fmt.num(p.v / heightCm, 2)}" } ?: context,
                    deltas = deltas, scrubbing = scrub != null, animateKey = range to offset,
                )
                if (scrub != null && deltas.isNotEmpty()) Spacer(Modifier.height(38.dp))
                // WHtR (i) next to the WHtR value (V2_TEXTS §5), not in the goal sheet
                if (whtr != null && scrub == null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(HealthTexts.WHTR + " ", style = hb.type.bodyS, color = hb.colors.muted)
                    Text(Fmt.num(whtr, 2), style = hb.type.bodyS, color = hb.colors.text)
                    Box(Modifier.plainClickable { whtrInfo = true }.padding(6.dp)) { GlyphIcon(Glyph.Info, size = 16.dp, color = hb.colors.muted) }
                }
                Spacer(Modifier.height(12.dp))

                // ---- chart
                val chartH = if (screen.wide) 280.dp else 216.dp
                if (inWin.isEmpty()) {
                    EmptyState(Glyph.Tape, Cat.waist, "No data for this period", "", modifier = Modifier.height(chartH))
                } else {
                    // waist-to-height reference lines (0.4 / 0.5 / 0.6 × height) over light WHtR bands
                    // good < 0.5 × height · attention 0.5–0.6 · high ≥ 0.6 (coloured bands, names only)
                    val refs = buildList { if (target != null) add(RefLine(target, "Target ${Fmt.num(target, 1)}")) }
                    val a = if (hb.dark) 0.16f else 0.12f
                    val lo = minOf(heightCm * 0.35, inWin.minOf { it.v } - 2); val hi = maxOf(heightCm * 0.7, inWin.maxOf { it.v } + 2)
                    val zones = listOf(
                        ZoneBand(lo, heightCm * 0.5, hb.colors.good.copy(alpha = a)),
                        ZoneBand(heightCm * 0.5, heightCm * 0.6, Cat.zoneFatBurn.copy(alpha = a)),
                        ZoneBand(heightCm * 0.6, hi, hb.colors.bad.copy(alpha = a)),
                    )
                    val zoneLabels = listOf(
                        com.boudhealth.ui.components.ZoneLabel(lo, heightCm * 0.5, "Good", hb.colors.good),
                        com.boudhealth.ui.components.ZoneLabel(heightCm * 0.5, heightCm * 0.6, "Attention", hb.legible(Cat.zoneFatBurn)),
                        com.boudhealth.ui.components.ZoneLabel(heightCm * 0.6, hi, "High", hb.colors.bad),
                    )
                    val raw = inWin.mapIndexed { k, pt -> TPoint(pt.at, pt.at, pt.v, ref = k) }
                    val dom = if (range == Range.ALL) inWin.first().date else w.start
                    val levels = remember(inWin, range, offset) {
                        listOf(
                            TLevel(ChartGrain.DAY, raw, bucketed = false),
                            TLevel(ChartGrain.WEEK, bucketize(raw, ChartGrain.WEEK, false, dom, today), bucketed = true),
                            TLevel(ChartGrain.MONTH, bucketize(raw, ChartGrain.MONTH, false, dom, today), bucketed = true),
                        )
                    }
                    val callout: (ChartSel, TLevel, TPoint) -> ChartCallout? = { _, lv, p ->
                        if (lv.bucketed) ChartCallout(
                            (if (lv.grain == ChartGrain.WEEK) "Week of " else "") + Fmt.range(p.at.toLocalDate(), p.end.toLocalDate().minusDays(1)),
                            if (p.n > 1) "${Fmt.num(p.lo, 1)}–${Fmt.num(p.hi, 1)}" else Fmt.num(p.hi, 1), "cm",
                            note = "${p.n} measurement${if (p.n == 1) "" else "s"}",
                            action = "Show measurements",
                        ) else {
                            val prevPt = inWin.getOrNull(p.ref - 1)
                            ChartCallout(
                                Fmt.dayLabel(p.at.toLocalDate(), today) + " · " + java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(p.at), Fmt.num(p.v, 1), "cm",
                                lines = listOf(CalloutLine("WHtR", Fmt.num(p.v / heightCm, 2))),
                                delta = prevPt?.let { q -> val ch = p.v - q.v; "${if (ch >= 0) "+" else "−"}${Fmt.num(abs(ch), 1)} cm vs ${Fmt.shortDate(q.date)}" },
                            )
                        }
                    }
                    TimeChart(
                        state = chartState, levels = levels,
                        start = if (range == Range.ALL) inWin.first().at.minusDays(3) else w.start.atStartOfDay(),
                        end = if (range == Range.ALL) inWin.last().at.plusDays(3) else w.end.atStartOfDay(),
                        color = Cat.waist, callout = callout,
                        description = "Waist circumference, ${inWin.size} measurements, latest ${Fmt.num(inWin.last().v, 1)} cm",
                        height = chartH, style = TimeStyle.Dots, refs = refs, zones = zones, zoneLabels = zoneLabels,
                        minPad = 0.5, yFormat = { Fmt.num(it) }, tagFormat = { Fmt.num(it, 1) },
                        zoomable = range == Range.YEAR || range == Range.ALL, minSpanDays = 14.0, animateKey = range to offset,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HBButton("Measure with tape", { nav.push(Route.Tape) }, glyph = Glyph.Tape)
                    Spacer(Modifier.width(8.dp))
                    HBButton("Add manually", { addWaist = true }, kind = ButtonKind.Text)
                }
            },
            right = {
                Spacer(Modifier.height(24.dp))
                val vals = inWin.map { it.v }
                StatRow(listOf(
                    Stat("Lowest", vals.minOrNull()?.let { Fmt.num(it, 1) } ?: "–", "cm"),
                    Stat("Highest", vals.maxOrNull()?.let { Fmt.num(it, 1) } ?: "–", "cm"),
                ))
                Spacer(Modifier.height(12.dp))
                WaistHistory(entries, showAll, { showAll = !showAll }) { s ->
                    edit.removeWaist(s.time)
                    snackbar.show("Deleted ${Fmt.num(s.value, 1)} cm", Glyph.Trash, onAction = { edit.addWaist(s.value, s.time) })
                }
            },
        )
        }
    }

    if (showGoal) GoalSheet("waist", onDismiss = { showGoal = false })
    if (whtrInfo) com.boudhealth.ui.section.InfoSheet(com.boudhealth.ui.section.InfoText(HealthTexts.WHTR, HealthTexts.WHTR_INFO)) { whtrInfo = false }
    if (addWaist) AddWaistSheet(last, onDismiss = { addWaist = false }, onSave = { cm -> saveWaistWithUndo(cm, edit, snackbar) }, onTape = { nav.push(Route.Tape) })
}

@Composable
private fun WaistHistory(entries: List<Sample>, showAll: Boolean, onToggle: () -> Unit, onDelete: (Sample) -> Unit) {
    val hb = LocalHB.current
    if (entries.isEmpty()) return
    val rows = entries.reversed()
    SectionHeader("History", action = if (rows.size > 7) (if (showAll) "Show less" else "See all") else null, onAction = onToggle)
    val shown = if (showAll) rows else rows.take(7)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Top) {
        shown.forEachIndexed { i, s ->
            val prev = rows.getOrNull(i + 1)
            androidx.compose.runtime.key(s.time) {
                SwipeToDelete(onDelete = { onDelete(s) }) {
                    HistoryRow(
                        title = Fmt.dayLabel(Fmt.date(s.time)), value = Fmt.num(s.value, 1), unit = "cm",
                        subtitle = Fmt.hm(s.time), change = changeText(s.value, prev?.value, 1),
                        divider = i < shown.lastIndex, onLongClick = { onDelete(s) },
                    )
                }
            }
        }
    }
}
