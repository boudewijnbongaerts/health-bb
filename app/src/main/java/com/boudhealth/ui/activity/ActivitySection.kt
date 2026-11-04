package com.boudhealth.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.data.MetricSections
import com.boudhealth.data.PeriodRing
import com.boudhealth.data.PeriodUnit
import com.boudhealth.data.PeriodWorkouts
import com.boudhealth.data.RingMode
import com.boudhealth.data.RingSet
import com.boudhealth.data.SectionId
import com.boudhealth.data.SectionPeriod
import com.boudhealth.data.SectionScope
import com.boudhealth.data.V2
import com.boudhealth.data.Workout
import com.boudhealth.data.goalDays
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.GlyphCircle
import com.boudhealth.ui.components.GlyphIcon
import com.boudhealth.ui.components.RingLegend
import com.boudhealth.ui.components.ringsCarryNumbers
import com.boudhealth.render.ring.RingData
import androidx.compose.ui.draw.alpha
import com.boudhealth.ui.components.TripleRing
import com.boudhealth.ui.components.plainClickable
import com.boudhealth.ui.nav.Nav
import com.boudhealth.ui.nav.Route
import com.boudhealth.ui.section.SFmt
import com.boudhealth.ui.section.SectionContext
import com.boudhealth.ui.section.SectionHooks
import com.boudhealth.ui.section.SectionRegistry
import com.boudhealth.ui.section.SectionScopes
import com.boudhealth.ui.section.SectionScreen
import com.boudhealth.ui.section.SectionSpec
import com.boudhealth.ui.section.R3
import com.boudhealth.ui.theme.LocalHB
import java.time.LocalDate

// =====================================================================================================================
// Activity tab, R3 (IMPL_PLAN §3A): hero (ring + ring calendar) · Trends · cards. Hero in ActivityHero.kt, Trends in
// ActivityTrends.kt, cards in ActivityR3Cards.kt, numbers in ActivityR3Data.kt (loadExtras).
// =====================================================================================================================

object ActivitySection {
    val spec: SectionSpec by lazy {
        SectionSpec(
            section = SectionId.ACTIVITY,
            title = "Activity",
            cardMetricIds = MetricSections.ACTIVITY,
            cards = ActivityR3Cards.all,
            loadExtras = { env, scope, bundle -> activityR3Extras(env, scope, bundle) },
            // R3 (IMPL_PLAN §3A): one hero, Trends strip, R3 card grid; no status lines, no drill strip
            hero = { ctx -> ActivityHero(ctx) },
            heroHeightR3 = { ctx -> if (!ctx.wide) null else if (ctx.scope.period is SectionPeriod.All) R3.HERO_H_ACTIVITY_ALL else R3.HERO_H },
            trends = { ctx -> activityTrends(ctx, LocalHB.current) },
        )
    }

    fun register() { if (SectionRegistry.get(SectionId.ACTIVITY) == null) SectionRegistry.register(spec) }
}

/** loadExtras (IO): the whole R3 model in one hop (bundle + R3 data layer). */
internal fun activityR3Extras(env: com.boudhealth.data.MetricEnv, scope: SectionScope, bundle: com.boudhealth.data.SectionBundle): Map<String, Any?> {
    val engine = V2.engine(env)
    val now = com.boudhealth.data.R3.now(env)
    return mapOf(X_R3 to ActivityR3Data.build(engine, scope, bundle, now))
}

/** The R3 model of this context (null while the extras are missing). */
internal fun SectionContext.act(): ActR3? = extras[X_R3] as? ActR3

/** Activity tab root (IC4). T5: `Route.Activity -> ActivitySectionScreen(nav)`. */
@Composable
fun ActivitySectionScreen(nav: Nav) {
    ActivitySection.register()
    // v6 bridge: Home tiles still call nav.openDay(day) until T5 moves them to SectionScopes / Nav.openSection
    LaunchedEffect(nav.activityDay) {
        if (nav.activityDay >= 0) {
            val d = LocalDate.ofEpochDay(nav.activityDay)
            SectionScopes.set(SectionId.ACTIVITY, SectionScope(SectionPeriod.Day(d.toEpochDay()), null, d == LocalDate.now()))
            nav.activityDay = -1L
        }
    }
    SectionScreen(SectionId.ACTIVITY, nav)
}

/** Compat entry used by today's AppShell (`Route.Activity -> ActivityScreen(nav)`). */
@Composable
fun ActivityScreen(nav: Nav) = ActivitySectionScreen(nav)

/**
 * Compat for the retired `Route.ActivityPeriod` (old History page, V2-075): decode-only redirect to the Activity tab at
 * that week / month / year (V7 S7). The old PeriodScreen is removed.
 */
@Composable
fun PeriodScreen(kindName: String, anchorEpochDay: Long, nav: Nav) {
    LaunchedEffect(kindName, anchorEpochDay) {
        val today = LocalDate.now()
        val anchor = if (anchorEpochDay < 0) today else LocalDate.ofEpochDay(anchorEpochDay)
        val unit = when (kindName.uppercase()) { "MONTH" -> PeriodUnit.MONTH; "YEAR" -> PeriodUnit.YEAR; else -> PeriodUnit.WEEK }
        val p = SectionPeriod.of(unit, anchor, minOf(anchor, today), today)
        SectionScopes.set(SectionId.ACTIVITY, SectionScope(p, null, p.isCurrent(today)))
        nav.selectTab(Route.Activity)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Ring adapter: the ONE place the Activity section draws rings (T4 swaps the drawing here, IC6).
// ---------------------------------------------------------------------------------------------------------------------

/** Progress outer → inner (Workout minutes · Steps · Heart points), read BY ID (T4 RingData). */
fun ringProgress(rs: RingSet): List<Float> = RingData.progress(rs).toList()

/**
 * Big period rings with the numbers integrated in each stroke (T4, G0). Where the stroke is too thin for ≥ 10 sp the
 * numbers move beside the ring ([RingLegend], T4 fallback).
 */
@Composable
fun SectionRings(rings: RingSet, size: Dp, modifier: Modifier = Modifier) {
    val hb = LocalHB.current
    val progress = ringProgress(rings)
    val labels = RingData.labels(rings)
    // design6 rings_hero: stroke 0.10 × D (cover 0.12), gap 4 dp
    val stroke = size * (if (size >= 200.dp) 0.10f else 0.12f)
    val gap = 4.dp
    val desc = PeriodRing.ORDER.mapNotNull { rings.ring(it) }.joinToString(", ") { "${ringName(it.id)} ${ringLabel(it)}" }
    if (ringsCarryNumbers(size, stroke)) {
        TripleRing(progress, modifier.semantics { contentDescription = desc }, size = size, stroke = stroke, gap = gap,
            ringLabels = labels, background = hb.colors.card)
    } else {
        Column(modifier.semantics { contentDescription = desc }, horizontalAlignment = Alignment.CenterHorizontally) {
            TripleRing(progress, size = size, stroke = stroke, gap = gap, background = hb.colors.card)
            Spacer(Modifier.height(10.dp))
            RingLegend(labels, progress, Modifier.width(size.coerceAtLeast(140.dp)))
        }
    }
}

/** "351 / 210" (accessibility text; the drawn numbers come from RingData.labels). */
fun ringLabel(r: PeriodRing): String = SFmt.compact(r.value) + (r.goal?.let { " / " + SFmt.compact(it) } ?: "")

/**
 * Small rings for the strips and the 1M calendar (T4 TripleRing, no numbers, no animation). Stroke 0.12 × size and a
 * gap of 3 % keep all three rings visible down to ~20 dp (0.16 dropped the inner ring below ~34 dp).
 */
@Composable
fun MiniRings(progress: List<Float>, size: Dp, faded: Boolean = false, modifier: Modifier = Modifier) {
    val hb = LocalHB.current
    TripleRing(progress.take(3), modifier = modifier.alpha(if (faded) 0.4f else 1f), size = size, stroke = size * 0.12f,
        gap = maxOf(1.dp, size * 0.03f), animate = false, background = hb.colors.card)
}

private fun ringName(id: String): String = when (id) { PeriodRing.WORKOUT -> "Workout minutes"; PeriodRing.STEPS -> "Steps"; else -> "Heart points" }

// ---------------------------------------------------------------------------------------------------------------------
// Extras (IO, next to the bundle): goal met on n of m days per ring goal (restore A2) and, on a day, "All values" (A1)
// ---------------------------------------------------------------------------------------------------------------------

internal const val X_GOAL_DAYS = "goalDays"
internal const val X_ALL_VALUES = "allValues"

/** One row of "All values": name, value text, unit. */
internal data class DayValue(val metricId: String, val name: String, val value: String, val unit: String)

/** "All values" of the selected day / 1D (A1): every Body and Vitals reading of that day in one list; tap → its page. */
@Composable
internal fun AllValuesCard(ctx: SectionContext) {
    val hb = LocalHB.current
    @Suppress("UNCHECKED_CAST") val rows = ctx.extras[X_ALL_VALUES] as? List<DayValue> ?: return
    com.boudhealth.ui.section.MetricCardFrame(ActivityTexts.ALL_VALUES, Glyph.Grid, hb.colors.muted) {
        if (rows.isEmpty()) { Text("—", fontSize = 14.sp, color = hb.colors.muted); return@MetricCardFrame }
        val cols = if (ctx.columns >= 2) 2 else 1
        rows.chunked(cols).forEach { r ->
            Row(Modifier.fillMaxWidth()) {
                r.forEach { d ->
                    Row(Modifier.weight(1f).plainClickable { ctx.openMetric(d.metricId) }.padding(vertical = 6.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(d.name, fontSize = 13.5.sp, color = hb.colors.muted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = hb.colors.text)) { append(d.value) }
                            if (d.unit.isNotEmpty()) withStyle(SpanStyle(color = hb.colors.muted, fontSize = 11.5.sp)) { append(" ${d.unit}") }
                        }, fontSize = 14.sp, maxLines = 1)
                        if (cols == 2) Spacer(Modifier.width(16.dp))
                    }
                }
                if (r.size < cols) Spacer(Modifier.weight(1f))
            }
        }
    }
}
