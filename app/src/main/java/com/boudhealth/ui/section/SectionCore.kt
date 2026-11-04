package com.boudhealth.ui.section

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import com.boudhealth.data.CardData
import com.boudhealth.data.MetricEnv
import com.boudhealth.data.PeriodUnit
import com.boudhealth.data.SectionBundle
import com.boudhealth.data.SectionId
import com.boudhealth.data.SectionPeriod
import com.boudhealth.data.SectionScope
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.HeaderAction
import com.boudhealth.ui.nav.Nav
import com.boudhealth.ui.nav.Route
import com.boudhealth.ui.theme.HB
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.LocalDate

// =====================================================================================================================
// IC4 Section framework: types, registry, per-section scope store, hooks. Documented in V2_API.md "Section framework".
// =====================================================================================================================

/**
 * Everything a hero / card / strip composable needs. [period] = the scope's effective period (a selected day or the
 * period): every ring, list and card loads it (B14/B15).
 */
@Stable
class SectionContext internal constructor(
    val section: SectionId,
    val scope: SectionScope,
    val bundle: SectionBundle,
    /** Spec-specific data loaded on IO next to the bundle ([SectionSpec.loadExtras]). */
    val extras: Map<String, Any?>,
    val today: LocalDate,
    val nav: Nav,
    /** Inner (unfolded) layout. False = cover screen. */
    val wide: Boolean,
    /** Content width below 360 dp. */
    val narrow: Boolean,
    /** Content column width (dp), for sizing hero art without intrinsic-unsafe BoxWithConstraints. */
    val contentWidth: androidx.compose.ui.unit.Dp,
    /** R3 card columns ([r3Columns]): 1 cover, 2 from 600 dp, 3 from 900 dp content width. */
    val columns: Int,
    private val scopeSink: (SectionScope) -> Unit,
    private val onDrill: (SectionScope) -> Unit = scopeSink,
) {
    val period: SectionPeriod get() = scope.effective
    val unit: PeriodUnit get() = scope.unit
    val dataStart: LocalDate get() = bundle.dataStart
    val selectedDay: LocalDate? get() = scope.selectedDay
    fun card(metricId: String): CardData? = bundle.cards[metricId]

    /** Tap a day (strip, card bar, list row): re-scope the WHOLE section to that day; 1Y → month, All → year. */
    fun drill(day: LocalDate) { SectionDrill.drill(this, day)?.let(onDrill) }

    fun setScope(s: SectionScope) = scopeSink(s)

    /** Card tap → detail page at the same period (B16). */
    fun openMetric(metricId: String) = SectionHooks.openMetric(nav, metricId, period)
}

/** What a tap on a bucket / strip cell does for each unit (V2_PLAN §2.2 drill-down). */
object SectionDrill {
    fun drill(ctx: SectionContext, day: LocalDate): SectionScope? = drill(ctx.scope, day, ctx.dataStart, ctx.today)

    fun drill(scope: SectionScope, day: LocalDate, dataStart: LocalDate, today: LocalDate): SectionScope? {
        if (day.isAfter(today)) return null
        return when (scope.unit) {
            PeriodUnit.DAY -> null
            PeriodUnit.WEEK, PeriodUnit.MONTH -> if (scope.selectedDay == day) scope.clearSelection(today) else scope.select(day)
            PeriodUnit.YEAR -> SectionPeriod.of(PeriodUnit.MONTH, day, dataStart, today).let { SectionScope(it, null, it.isCurrent(today)) }
            PeriodUnit.ALL -> SectionPeriod.of(PeriodUnit.YEAR, day, dataStart, today).let { SectionScope(it, null, it.isCurrent(today)) }
        }
    }
}

/** (i) popup text (only the 4 approved ones, V2_PLAN §2.9). */
data class InfoText(val title: String, val body: String)

/** A card in a section, placed by [CardHost] in [SectionSpec.cards] order. */
sealed class CardSpec {
    abstract val id: String
    /** Inner screen: the card takes the full row (both columns). Cover screen: no effect (1 column anyway). */
    open val span: Boolean get() = false
    /** R3 grid span (IMPL_PLAN §2.1): S = 1 column, M = 2 columns on the inner screen. Legacy path uses [span]. */
    open val size: CardSize get() = CardSize.S

    /**
     * Standard metric card: header (glyph · title · (i) · trend pill) + [headline] + [graph]; tap → [onClick]
     * (default: detail page of [id] at the same period). Data = `bundle.cards[id]`.
     */
    class Metric(
        override val id: String,
        val title: String,
        val glyph: Glyph,
        val color: (HB) -> Color,
        val info: InfoText? = null,
        val trendDecimals: Int = 0,
        val headline: @Composable (SectionContext, CardData) -> Unit,
        val graph: @Composable (SectionContext, CardData) -> Unit,
        val onClick: ((SectionContext) -> Unit)? = null,
        /** False = no trend pill (e.g. Sleep). */
        val showTrend: Boolean = true,
        override val span: Boolean = false,
        override val size: CardSize = CardSize.S,
    ) : CardSpec()

    /** Free-form card (still sized / placed by CardHost). [shown] = false hides it for this context (e.g. only on a day). */
    class Custom(
        override val id: String,
        override val span: Boolean = false,
        val shown: ((SectionContext) -> Boolean)? = null,
        override val size: CardSize = CardSize.S,
        /** R3 path: the card size for this context (e.g. VO₂ max M, S on 1D); null = [size]. */
        val sizeFor: ((SectionContext) -> CardSize)? = null,
        val content: @Composable (SectionContext) -> Unit,
    ) : CardSpec()
}

/**
 * One section (Activity, Body, Vitals). Register once with [SectionRegistry.register]; render with [SectionScreen].
 *
 * @param cardMetricIds ids loaded by `V2.bundle` (usually `MetricSections.of(section)`)
 * @param loadExtras IO: extra data next to the bundle (available as [SectionContext.extras])
 * @param hero R3 (IMPL_PLAN §2.1): the one full-width hero; the tab draws everything inside the hero frame, including
 *   its own divider. Then the Trends strip and the R3 card grid (no status lines, no drill strip since P3).
 * @param heroHeightR3 fixed hero height (inner 424, Activity All 344); null = wrap content (cover)
 * @param trends R3 Trends strip model (always 6 tiles); null = no strip. Composable, so it can read the theme
 *   (`LocalHB.current`) for the micro visual colours.
 */
class SectionSpec(
    val section: SectionId,
    val title: String,
    val cardMetricIds: List<String>,
    val cards: List<CardSpec>,
    val loadExtras: ((MetricEnv, SectionScope, SectionBundle) -> Map<String, Any?>)? = null,
    /** Units shown on the period bar. */
    val units: List<PeriodUnit> = PeriodUnit.entries.toList(),
    // ---- R3 (IMPL_PLAN §2.1)
    val hero: (@Composable (SectionContext) -> Unit)? = null,
    val heroHeightR3: (SectionContext) -> androidx.compose.ui.unit.Dp? = { if (it.wide) R3.HERO_H else null },
    val trends: (@Composable (SectionContext) -> TrendsModel?)? = null,
)

object SectionRegistry {
    private val specs = mutableMapOf<SectionId, SectionSpec>()
    fun register(spec: SectionSpec) { specs[spec.section] = spec }
    fun get(section: SectionId): SectionSpec? = specs[section]
}

/**
 * Per-section scope store (D15): survives pushes of detail pages and tab switches; resets to 1D today after a cold
 * start (process memory) or after more than 1 h without use ([resetIfIdle], called by SectionScreen before each load).
 * T5 can persist it through the Nav Saver with [encode] / [decode].
 */
object SectionScopes {
    private val scopes = mutableStateMapOf<SectionId, SectionScope>()
    /** Drill parents (1Y → month, All → year): Back returns to them. */
    private val parents = mutableStateMapOf<SectionId, List<SectionScope>>()
    private val handoff = mutableMapOf<String, SectionPeriod>()
    private var lastSeenMs: Long = 0L
    const val RESET_AFTER_MS = 60 * 60 * 1000L

    fun get(section: SectionId): SectionScope? = scopes[section]
    fun set(section: SectionId, scope: SectionScope) { scopes.put(section, scope); touch() }
    fun parents(section: SectionId): List<SectionScope> = parents[section].orEmpty()
    fun setParents(section: SectionId, l: List<SectionScope>) { parents.put(section, l) }

    /** Marks the sections as in use (every load / scope change). */
    fun touch(nowMs: Long = System.currentTimeMillis()) { lastSeenMs = nowMs }

    /**
     * D15: after more than an hour without use (app in the background), every section returns to 1D today. Called by
     * SectionScreen before each load (a sync on resume triggers one). Returns true when it reset.
     */
    fun resetIfIdle(nowMs: Long = System.currentTimeMillis()): Boolean {
        val idle = lastSeenMs > 0 && nowMs - lastSeenMs > RESET_AFTER_MS
        if (idle) { scopes.clear(); parents.clear() }
        lastSeenMs = nowMs
        return idle
    }

    /** Detail-page hand-off: the period a card was showing when tapped (read once by the detail page). */
    fun putHandoff(metricId: String, p: SectionPeriod) { handoff[metricId] = p }
    fun takeHandoff(metricId: String): SectionPeriod? = handoff.remove(metricId)
    fun peekHandoff(metricId: String): SectionPeriod? = handoff[metricId]

    private val json = Json { ignoreUnknownKeys = true }
    fun encode(): String = json.encodeToString(MapSerializer(String.serializer(), SectionScope.serializer()), scopes.mapKeys { it.key.name })
    fun decode(s: String) {
        runCatching { json.decodeFromString(MapSerializer(String.serializer(), SectionScope.serializer()), s) }.getOrNull()
            ?.forEach { (k, v) -> runCatching { SectionId.valueOf(k) }.getOrNull()?.let { scopes.put(it, v) } }
    }
}

/**
 * Integration points owned by other teams, with safe defaults. T5 replaces them once IC5 exists, e.g.
 *     SectionHooks.actions = { nav -> TabActions(nav) }
 *     SectionHooks.openMetric = { nav, id, p -> nav.openMetric(id, p) }
 */
object SectionHooks {
    /** Top-bar actions of a section root (All metrics + Settings). */
    var actions: (Nav) -> List<HeaderAction> = { nav -> listOf(HeaderAction(Glyph.Settings, "Settings") { nav.push(Route.Settings) }) }

    /** Open a metric's detail page at [period] (hand-off stored in [SectionScopes]). */
    var openMetric: (Nav, String, SectionPeriod) -> Unit = { nav, id, p ->
        SectionScopes.putHandoff(id, p)
        nav.push(Route.MetricDetail(id))
    }

    /** Open the Workouts sub-page filtered to [period]. */
    var openWorkouts: (Nav, SectionPeriod) -> Unit = { nav, p ->
        SectionScopes.putHandoff(WORKOUTS_KEY, p)
        nav.push(Route.Workouts)
    }

    /** Open the Sleep detail for the night ending on [wakeDay] (null = the period). */
    var openSleep: (Nav, SectionPeriod, LocalDate?) -> Unit = { nav, p, wake ->
        SectionScopes.putHandoff("sleep", p)
        nav.push(Route.Sleep(wake?.toEpochDay() ?: -1L))
    }

    const val WORKOUTS_KEY = "workouts"
}
