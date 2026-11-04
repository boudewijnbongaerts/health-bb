package com.boudhealth.ui.allmetrics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.data.MetricIndexRow
import com.boudhealth.data.MetricSections
import com.boudhealth.data.PeriodUnit
import com.boudhealth.data.SectionId
import com.boudhealth.data.SectionPeriod
import com.boudhealth.data.Sync
import com.boudhealth.data.TrendDir
import com.boudhealth.data.V2
import com.boudhealth.data.V2Metrics
import com.boudhealth.ui.components.DetailScaffold
import com.boudhealth.ui.components.Fmt
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.GlyphCircle
import com.boudhealth.ui.components.GlyphIcon
import com.boudhealth.ui.components.hbClickable
import com.boudhealth.ui.components.LocalScreen
import com.boudhealth.ui.components.MetricSkeleton
import com.boudhealth.ui.components.SectionHeader
import com.boudhealth.ui.health.metricColor
import com.boudhealth.ui.nav.ConnectCard
import com.boudhealth.ui.nav.LocalPrefs
import com.boudhealth.ui.nav.Nav
import com.boudhealth.ui.nav.Route
import com.boudhealth.ui.section.SectionHooks
import com.boudhealth.ui.section.SectionScopes
import com.boudhealth.ui.theme.HbShapes
import com.boudhealth.ui.theme.LocalHB
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/** One loaded list: the index rows (T1 `V2.metricIndex`) + the best streak for the Streak row's subtitle. */
internal class AllMetricsData(val rows: List<MetricIndexRow>, val bestStreak: Int?, val today: LocalDate)

/** Row texts (V2_TEXTS wording: Workout minutes, Heart points, Body fat percentage, never "Training"). */
internal object AllMetricsText {
    val TITLES = mapOf(
        "exercise_min" to "Workout minutes", "steps" to "Steps", "zone_min" to "Heart points", "streak" to "Streak",
        "calories" to "Calories burned", "sleep" to "Sleep", "vo2" to "VO₂ max", "week_workouts" to "Workouts",
        "weight" to "Weight", "body_fat" to "Body fat percentage", "lean_mass" to "Lean mass", "waist" to "Waist circumference",
        "bmi" to "BMI", "rhr" to "Resting heart rate", "hr" to "Heart rate", "hrv" to "HRV", "breathing" to "Breathing rate",
        "spo2" to "Blood oxygen",
    )
    val SECTION_TITLES = mapOf(SectionId.ACTIVITY to "Activity", SectionId.BODY to "Body", SectionId.VITALS to "Vitals")

    private val DECIMALS = mapOf("vo2" to 1, "weight" to 1, "body_fat" to 1, "lean_mass" to 1, "waist" to 1, "bmi" to 1, "breathing" to 1)

    /** Value + unit ("80.2 kg", "1,250", "7h 23m", "2 days"); "—" without data (V2-115). */
    fun value(r: MetricIndexRow): String = valueParts(r).let { (n, u) -> if (u.isEmpty()) n else "$n $u" }

    /** (number, unit) for the row: number 16 bold, unit 11 muted (V2_VISUAL_QA 20.2). */
    fun valueParts(r: MetricIndexRow): Pair<String, String> {
        val v = r.latest ?: return "—" to ""
        return when (r.metricId) {
            "sleep" -> Fmt.duration(v.roundToInt()) to ""
            "week_workouts" -> Fmt.int(v) to ""
            "streak" -> Fmt.int(v) to (if (v.roundToInt() == 1) "day" else "days")
            else -> {
                val d = DECIMALS[r.metricId] ?: 0
                val n = if (d == 0) Fmt.int(v.roundToInt()) else Fmt.num(v, d)
                n to when (r.metricId) { "vo2", "zone_min" -> ""; else -> r.unit }
            }
        }
    }

    /** "Today" / "Yesterday" / "25 Oct"; Sleep "Last night"; Streak "Best {n}"; Workouts "This week". */
    fun subtitle(r: MetricIndexRow, best: Int?, today: LocalDate): String = when (r.metricId) {
        "streak" -> best?.let { "Best ${Fmt.int(it)}" } ?: "Workout days in a row"
        "week_workouts" -> "This week"
        else -> {
            val d = r.latestDay
            when {
                r.latest == null || d == null -> "No data"
                r.metricId == "sleep" && d == today -> "Last night"
                else -> day(d, today)
            }
        }
    }

    fun day(d: LocalDate, today: LocalDate): String = when (ChronoUnit.DAYS.between(d, today)) {
        0L -> "Today"
        1L -> "Yesterday"
        else -> DateTimeFormatter.ofPattern(if (d.year == today.year) "d MMM" else "d MMM yyyy", Locale.getDefault()).format(d)
    }
}

/**
 * V2-115 All metrics: one list grouped Activity · Body · Vitals in the section card order (MetricSections.ALL_METRICS),
 * Health-tab row design (icon, name, latest value + unit, trend ▲●▼, chevron). No period selector, no search; group
 * headers are not tappable. ≥ 1,000 dp: 3 columns; wide: Activity left, Body + Vitals right (design6/nav/SHEET_all_metrics.png).
 */
@Composable
fun AllMetricsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val screen = LocalScreen.current
    val version by Sync.version.collectAsState()
    val goals = LocalPrefs.current.goals
    val data by produceState<AllMetricsData?>(null, version, goals) {
        value = runCatching {
            V2.load(ctx) { env -> AllMetricsData(V2.metricIndex(env), runCatching { V2.streakAsOf(env, env.today).second }.getOrNull(), env.today) }
        }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; AllMetricsData(emptyList(), null, LocalDate.now()) }
    }

    DetailScaffold("All metrics", onBack = { nav.back() }) {
        Spacer(Modifier.height(8.dp))
        ConnectCard()
        val d = data
        if (d == null) { Spacer(Modifier.height(16.dp)); MetricSkeleton(); return@DetailScaffold }
        val bySection = MetricSections.ALL_METRICS.mapValues { (section, ids) ->
            ids.map { id -> d.rows.firstOrNull { it.metricId == id } ?: MetricIndexRow(id, section, null, null, "", null) }
        }
        val group: @Composable (SectionId, Boolean) -> Unit = { s, first ->
            Group(AllMetricsText.SECTION_TITLES.getValue(s), bySection[s].orEmpty(), d, first) { id -> open(nav, id, d.today) }
        }
        if (screen.widthDp >= 1000f) {
            // 2.0.1: the Fold inner screen at native density (~1,088 dp): one column per section, no empty bands
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f)) { group(SectionId.ACTIVITY, true) }
                Column(Modifier.weight(1f)) { group(SectionId.BODY, true) }
                Column(Modifier.weight(1f)) { group(SectionId.VITALS, true) }
            }
        } else if (screen.wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f)) { group(SectionId.ACTIVITY, true) }
                Column(Modifier.weight(1f)) { group(SectionId.BODY, true); group(SectionId.VITALS, false) }
            }
        } else {
            group(SectionId.ACTIVITY, true); group(SectionId.BODY, false); group(SectionId.VITALS, false)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Row tap → that metric's page (Waist page, Workouts sub-page at this week, Sleep detail, else the metric detail). */
private fun open(nav: Nav, id: String, today: LocalDate) {
    when (id) {
        "waist" -> nav.push(Route.Waist)
        "week_workouts" -> {
            SectionScopes.putHandoff(SectionHooks.WORKOUTS_KEY, SectionPeriod.of(PeriodUnit.WEEK, today, today, today))
            nav.push(Route.Workouts)
        }
        "sleep" -> nav.push(Route.Sleep())
        else -> nav.openMetric(id)
    }
}

@Composable
private fun Group(title: String, rows: List<MetricIndexRow>, d: AllMetricsData, first: Boolean, onOpen: (String) -> Unit) {
    val hb = LocalHB.current
    SectionHeader(title, top = if (first) 16.dp else 28.dp)
    // V2_VISUAL_QA #12 / 20.5: page = bgDim, groups = card (one elevation model with the sections)
    Column(Modifier.fillMaxWidth().background(hb.colors.card, HbShapes.group).padding(horizontal = 16.dp)) {
        rows.forEachIndexed { i, r -> MetricRow(r, d, divider = i < rows.lastIndex) { onOpen(r.metricId) } }
    }
}

/**
 * One row (design6/nav/SHEET_all_metrics.png, V2_VISUAL_QA 20.2): ≈ 52 dp min height (64 with the 40 dp icon and
 * padding), name 15 + subtitle 12 muted, value 16 bold + unit 11 muted, coloured ▲▼ 12 sp, chevron.
 */
@Composable
private fun MetricRow(r: MetricIndexRow, d: AllMetricsData, divider: Boolean, onClick: () -> Unit) {
    val hb = LocalHB.current
    val (num, unit) = AllMetricsText.valueParts(r)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().hbClickable(shape = HbShapes.tileS, scaleOnPress = false, onClick = onClick)
                .heightIn(min = 60.dp).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphCircle(Glyph.forMetric(r.metricId), metricColor(r.metricId), size = 40.dp, glyphSize = 20.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(AllMetricsText.TITLES[r.metricId] ?: r.metricId, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    color = hb.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(AllMetricsText.subtitle(r, d.bestStreak, d.today), fontSize = 12.sp, color = hb.colors.muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = hb.colors.text)) { append(num) }
                    if (unit.isNotEmpty()) withStyle(SpanStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = hb.colors.muted)) { append(" $unit") }
                },
                maxLines = 1,
            )
            // fixed slot so values line up whether or not a row has a trend
            Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) {
                r.trend?.takeIf { r.latest != null }?.let { TrendMark(r.metricId, it) }
            }
            GlyphIcon(Glyph.ChevR, size = 18.dp, color = hb.colors.muted)
        }
        if (divider) Box(Modifier.padding(start = 54.dp).fillMaxWidth().height(1.dp).background(hb.colors.grid))
    }
}

/**
 * ▲ ● ▼ direction mark, 12 sp, coloured by whether the direction is good for that metric (V2Metrics.higherIsBetter):
 * good green, bad red, neutral muted. Direction only: V2 daily values never show a number for their trend.
 */
@Composable
private fun TrendMark(metricId: String, dir: TrendDir) {
    val hb = LocalHB.current
    val hib = V2Metrics.get(metricId)?.higherIsBetter
    val color = when {
        dir == TrendDir.FLAT || hib == null -> hb.colors.muted
        (dir == TrendDir.UP) == hib -> hb.colors.good
        else -> hb.colors.bad
    }
    Text(
        when (dir) { TrendDir.UP -> "▲"; TrendDir.DOWN -> "▼"; TrendDir.FLAT -> "●" },
        fontSize = if (dir == TrendDir.FLAT) 9.sp else 12.sp, fontWeight = FontWeight.Bold, color = color,
    )
}
