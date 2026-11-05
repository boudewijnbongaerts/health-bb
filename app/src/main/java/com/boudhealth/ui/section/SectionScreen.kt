package com.boudhealth.ui.section

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.boudhealth.data.ErrorLog
import com.boudhealth.data.PeriodUnit
import com.boudhealth.data.SectionBundle
import com.boudhealth.data.SectionId
import com.boudhealth.data.SectionScope
import com.boudhealth.data.Sync
import com.boudhealth.data.V2
import com.boudhealth.ui.components.EmptyState
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.HBCard
import com.boudhealth.ui.components.HeaderAction
import com.boudhealth.ui.components.LocalScreen
import com.boudhealth.ui.components.ScreenHeader
import com.boudhealth.ui.components.SkeletonBox
import com.boudhealth.ui.components.screenContent
import com.boudhealth.ui.components.shellBottom
import com.boudhealth.ui.nav.ConnectCard
import com.boudhealth.ui.nav.Nav
import com.boudhealth.ui.theme.LocalHB
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Result of one section load (one IO hop: bundle + extras + strip marks). */
internal class SectionLoad(
    val scope: SectionScope,
    val bundle: SectionBundle,
    val extras: Map<String, Any?>,
    val dataStart: LocalDate,
    val today: LocalDate,
)

/** Last known first data day (so a restored All scope starts right before the first load). */
private var lastDataStart: LocalDate? = null

/** Section root by id (IC4). The section must be registered ([SectionRegistry.register]). */
@Composable
fun SectionScreen(section: SectionId, nav: Nav, actions: List<HeaderAction> = SectionHooks.actions(nav)) {
    val spec = SectionRegistry.get(section)
    if (spec == null) {
        val hb = LocalHB.current
        Box(Modifier.fillMaxSize().background(hb.colors.bgDim).padding(24.dp), contentAlignment = Alignment.Center) {
            EmptyState(Glyph.Grid, hb.colors.accent, title = section.name.lowercase().replaceFirstChar { it.uppercase() }, body = "")
        }
        return
    }
    SectionScreen(spec, nav, actions)
}

/**
 * The shared section screen (V2_PLAN §2.2): title + actions · sticky period bar · hero row (left | right) ·
 * R3 body (hero · Trends · card grid; legacy hero row, status lines and drill strip removed in P3).
 * Scope per section lives in [SectionScopes] (D15); Back clears a selected day, then a 1Y/All drill.
 */
@Composable
fun SectionScreen(spec: SectionSpec, nav: Nav, actions: List<HeaderAction> = SectionHooks.actions(nav)) {
    val hb = LocalHB.current
    val ctx = LocalContext.current
    val screen = LocalScreen.current
    val density = LocalDensity.current
    val version by Sync.version.collectAsState()

    val today = LocalDate.now()
    val dataStart = lastDataStart ?: today
    val scope = SectionScopes.get(spec.section) ?: SectionScope.today(today, dataStart)
    val parents = SectionScopes.parents(spec.section)
    fun setScope(s: SectionScope) { SectionScopes.setParents(spec.section, emptyList()); SectionScopes.set(spec.section, s) }
    fun drillTo(s: SectionScope) {
        // 1Y → month and All → year keep the parent so Back returns to it
        if (s.selectedDay == null && s.unit != scope.unit) SectionScopes.setParents(spec.section, parents + scope)
        SectionScopes.set(spec.section, s)
    }

    var load by remember { mutableStateOf<SectionLoad?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(scope, version) {
        // D15: back to 1D today after more than an hour without use
        val start = if (SectionScopes.resetIfIdle()) SectionScope.today(LocalDate.now(), dataStart) else scope
        val r = runCatching {
            V2.load(ctx) { env ->
                val ds = V2.dataStart(env)
                val sc = start.refreshed(env.today, ds)
                val b = V2.bundle(env, spec.section, sc, spec.cardMetricIds)
                val extras = spec.loadExtras?.invoke(env, sc, b) ?: emptyMap()
                SectionLoad(sc, b, extras, ds, env.today)
            }
        }
        r.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; ErrorLog.record("section ${spec.section}", it); failed = load == null }
        r.getOrNull()?.let { l ->
            lastDataStart = l.dataStart
            failed = false
            load = l
            if (l.scope != scope) SectionScopes.set(spec.section, l.scope)
            launch { runCatching { V2.load(ctx) { env -> V2.prefetchPrevious(env, spec.section, l.scope, spec.cardMetricIds) } } }
        }
    }

    // m7: only while this section is the visible route (not the outgoing screen of a transition)
    val onTop = nav.current == com.boudhealth.ui.nav.Route.sectionRoute(spec.section)
    BackHandler(enabled = onTop && (scope.selectedDay != null || parents.isNotEmpty())) {
        if (scope.selectedDay != null) SectionScopes.set(spec.section, scope.clearSelection(today))
        else { SectionScopes.set(spec.section, parents.last()); SectionScopes.setParents(spec.section, parents.dropLast(1)) }
    }

    val scroll = rememberScrollState()
    com.boudhealth.ui.debug.RegisterTourScroll(scroll)   // V2-155 debug tour
    var barTop by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    val statusPx = with(density) { WindowInsets.statusBars.asPaddingValues().calculateTopPadding().toPx() }
    // M1: derived, so scrolling recomposes only when the bar pins / unpins (not the whole section every frame)
    val pinned by remember(statusPx) { derivedStateOf { scroll.value > barTop - statusPx } }
    val ds = load?.dataStart ?: dataStart
    val wide = screen.wide
    val contentW = (minOf(screen.widthDp, com.boudhealth.ui.theme.Space.contentMaxWidth.value + 2 * screen.gutter.value) - 2 * screen.gutter.value)
    val narrow = contentW < 360f
    val columns = r3Columns(minOf(contentW, R3.CONTENT_MAX.value), wide)
    val bar: @Composable () -> Unit = { PeriodBar(scope, ds, today, ::setScope, wide, units = spec.units) }

    // R3 (IMPL_PLAN §2.1): the content column is capped at 1,040 dp on the inner screen (app gutter untouched)
    val r3 = true   // P3: every section is on the R3 path
    val cap = if (r3 && wide) Modifier.widthIn(max = R3.CONTENT_MAX + screen.gutter * 2) else Modifier
    Box(Modifier.fillMaxSize().background(hb.colors.bgDim)) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(cap.screenContent(screen.gutter)) {
                // design6 section.render: header · 6 · period bar · 14 (cover 12) · hero · 14 · status · 16 · strip · 14 · cards
                ScreenHeader(spec.title, actions = actions)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().onGloballyPositioned { barTop = it.positionInParent().y }.then(if (r3) Modifier.periodSwipe(scope, ds, today, ::setScope) else Modifier)) { bar() }
                Spacer(Modifier.height(if (wide) 14.dp else 12.dp))
                ConnectCard()
                val l = load
                when {
                    l == null && failed -> EmptyState(Glyph.Alert, hb.colors.bad, title = "No data for this period", body = "")
                    l == null -> {
                        SkeletonBox(Modifier.fillMaxWidth(), height = if (wide) (if (r3) R3.HERO_H else 300.dp) else 176.dp, radius = 26.dp)
                        Spacer(Modifier.height(12.dp))
                        SkeletonBox(Modifier.fillMaxWidth(), height = 232.dp, radius = 22.dp)
                    }
                    else -> {
                        // while a new scope loads, the previous content stays (dimmed) instead of flashing skeletons
                        val stale = l.scope.effective != scope.refreshed(l.today, l.dataStart).effective
                        val sctx = SectionContext(spec.section, l.scope, l.bundle, l.extras, l.today, nav, wide, narrow, androidx.compose.ui.unit.Dp(contentW), columns, ::setScope, ::drillTo)
                        R3Body(sctx, spec, Modifier.alpha(if (stale) 0.6f else 1f), Modifier.periodSwipe(scope, ds, today, ::setScope))
                    }
                }
                Spacer(Modifier.height(shellBottom + 24.dp))
            }
        }
        AnimatedVisibility(pinned && !com.boudhealth.ui.debug.TourScroll.active, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
            Column(Modifier.fillMaxWidth().background(hb.colors.bgDim), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp))
                Box(cap.screenContent(screen.gutter).padding(bottom = 8.dp)) { bar() }
            }
        }
    }
}

/**
 * R3 body (IMPL_PLAN §2.1): hero (full width, one slot, HBCard r 26, padding 22 / cover 16, fixed height inner) → 16 →
 * Trends → 16 → cards (R3 grid). Cover gaps 12. No HeroRow, no status lines, no drill strip.
 */
@Composable
private fun R3Body(ctx: SectionContext, spec: SectionSpec, modifier: Modifier, swipe: Modifier) {
    val gap = if (ctx.wide) R3.GAP else R3.GAP_COVER
    // plain Text(fontSize = …) would inherit M3 bodyLarge (lineHeight 24 sp, letterSpacing 0.5 sp): app font, natural line height, no tracking
    // (copy, not merge: merge keeps the inherited lineHeight when the new one is Unspecified)
    androidx.compose.material3.ProvideTextStyle(androidx.compose.material3.LocalTextStyle.current.copy(
        fontFamily = com.boudhealth.ui.theme.AppFont.family, lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified, letterSpacing = androidx.compose.ui.unit.TextUnit(0f, androidx.compose.ui.unit.TextUnitType.Sp))) {
    Column(modifier.fillMaxWidth()) {
        val h = spec.heroHeightR3(ctx)
        HBCard(swipe.fillMaxWidth().then(if (h != null) Modifier.height(h) else Modifier), shape = androidx.compose.foundation.shape.RoundedCornerShape(R3.HERO_RADIUS),
            padding = androidx.compose.foundation.layout.PaddingValues(if (ctx.wide) R3.HERO_PAD else R3.HERO_PAD_COVER)) {
            spec.hero?.invoke(ctx)
        }
        val t = spec.trends?.invoke(ctx)
        if (t != null) {
            Spacer(Modifier.height(gap))
            TrendsStrip(t, cover = !ctx.wide)
        }
        Spacer(Modifier.height(gap))
        R3CardHost(ctx, spec.cards)
    }
    }
}

/** The units a section shows (all five by default). */
val ALL_UNITS: List<PeriodUnit> = PeriodUnit.entries.toList()
