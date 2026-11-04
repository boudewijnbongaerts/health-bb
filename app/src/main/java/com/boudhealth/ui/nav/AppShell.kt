package com.boudhealth.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.union
import com.boudhealth.ui.activity.ACTIVITY_DETAIL_IDS
import com.boudhealth.ui.activity.ActivityDetailScreen
import com.boudhealth.ui.activity.ActivitySectionScreen
import com.boudhealth.ui.activity.SleepDetailScreen
import com.boudhealth.ui.allmetrics.AllMetricsScreen
import com.boudhealth.ui.activity.SessionScreen
import com.boudhealth.ui.activity.WorkoutsScreen
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.GlyphIcon
import com.boudhealth.ui.components.HBSnackbarHost
import com.boudhealth.ui.components.HBSnackbarState
import com.boudhealth.ui.components.LocalScreen
import com.boudhealth.ui.components.LocalShellPadding
import com.boudhealth.ui.components.LocalSnackbar
import com.boudhealth.ui.components.ScreenMetrics
import com.boudhealth.ui.components.plainClickable
import com.boudhealth.ui.components.rememberHaptics
import com.boudhealth.ui.health.MetricDetailScreen
import com.boudhealth.ui.health.TapeScreen
import com.boudhealth.ui.health.WaistScreen
import com.boudhealth.ui.home.EditHomeScreen
import com.boudhealth.ui.home.HomeScreen
import com.boudhealth.ui.settings.DataCheckScreen
import com.boudhealth.ui.settings.SettingsScreen
import com.boudhealth.ui.theme.LocalHB
import com.boudhealth.ui.theme.Space

/** Height of the bottom bar above the navigation-bar inset. */
val BottomBarHeight = 68.dp

/**
 * The app frame: screen metrics, back stack with transitions + per-entry saveable state, BackHandler,
 * the 4-tab bottom bar, and the snackbar host. Provides LocalNav, LocalScreen, LocalShellPadding, LocalSnackbar.
 */
@Composable
fun AppShell(nav: Nav) {
    val hb = LocalHB.current
    val snackbar = remember { HBSnackbarState() }
    // v3.1: failed saves (PrefsEditor) show as an error toast
    LaunchedEffect(Unit) { SaveErrors.events.collect { msg -> snackbar.toast(msg, Glyph.Alert) } }
    remember { NavHooks.install() }
    val holder = rememberSaveableStateHolder()
    val knownKeys = remember { mutableSetOf<String>() }
    BoxWithConstraints(Modifier.fillMaxSize().background(hb.colors.bgDim)) {
        // 2.0.1 (owner): native density everywhere (no zoom). The inner screen's extra width (Fold 8: ~1,088 dp) is used
        // by a wider content cap (Space.contentMaxWidth) and wider layouts (Home grid columns, All metrics 3 columns).
        val w = maxWidth.value
        run {
            val screen = ScreenMetrics(w, w >= Space.WIDE_BREAKPOINT_DP, Space.gutter(w))
            // #14: the Samsung taskbar / gesture area can sit outside navigationBars: take the largest bottom inset
            val navInset = WindowInsets.navigationBars.union(WindowInsets.systemBars).union(WindowInsets.tappableElement)
                .asPaddingValues().calculateBottomPadding()
            val current = nav.current
            val showBar = current.showsBottomBar
            val bottomPad = if (showBar) BottomBarHeight + navInset else navInset

            BackHandler(enabled = nav.canGoBack) { nav.back() }

            // drop saved state of popped entries (tab roots keep theirs across tab switches)
            LaunchedEffect(nav.stack) {
                val live = nav.stack.mapIndexed { i, r -> entryKey(i, r) }.toSet()
                knownKeys.filter { it !in live && !it.startsWith("0:") }.forEach { holder.removeState(it) }
                knownKeys.retainAll { it in live || it.startsWith("0:") }
                knownKeys.addAll(live)
            }

            CompositionLocalProvider(
                LocalNav provides nav,
                LocalScreen provides screen,
                LocalShellPadding provides PaddingValues(bottom = bottomPad),
                LocalSnackbar provides snackbar,
            ) {
                Box(Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = nav.stack,
                        modifier = Modifier.fillMaxSize(),
                        contentKey = { stack -> entryKey(stack.lastIndex, stack.last()) },
                        transitionSpec = { transition(nav.lastDirection) },
                        label = "route",
                    ) { stack ->
                        val route = stack.last()
                        holder.SaveableStateProvider(entryKey(stack.lastIndex, route)) {
                            RouteContent(route, nav)
                        }
                    }
                    if (showBar) BottomBar(nav, Modifier.align(Alignment.BottomCenter), navInset)
                    HBSnackbarHost(snackbar, bottom = bottomPad)
                }
            }
        }
    }
}

private fun entryKey(index: Int, route: Route): String = "$index:${Route.encode(route)}"

private fun transition(direction: Int): ContentTransform = when (direction) {
    1 -> (slideInHorizontally(tween(260)) { it / 6 } + fadeIn(tween(200))) togetherWith
        (slideOutHorizontally(tween(260)) { -it / 12 } + fadeOut(tween(160)))
    -1 -> (slideInHorizontally(tween(260)) { -it / 12 } + fadeIn(tween(200))) togetherWith
        (slideOutHorizontally(tween(260)) { it / 6 } + fadeOut(tween(160)))
    else -> fadeIn(tween(180)) togetherWith fadeOut(tween(120))
}

/** Route → screen. Each area owns its screen file; signatures are fixed (see FOUNDATION.md, V2_API.md). */
@Composable
private fun RouteContent(route: Route, nav: Nav) {
    when (route) {
        Route.Home -> HomeScreen(nav)
        Route.Activity -> ActivitySectionScreen(nav)
        Route.Body -> BodyTabRoot(nav)
        Route.Vitals -> VitalsTabRoot(nav)
        Route.AllMetrics -> AllMetricsScreen(nav)
        is Route.MetricDetail ->
            if (route.metricId in ACTIVITY_DETAIL_IDS && route.metricId != "sleep") ActivityDetailScreen(route.metricId, nav)
            else if (route.metricId == "sleep") SleepDetailScreen(-1L, nav)
            else MetricDetailScreen(route.metricId, nav)
        Route.Workouts -> WorkoutsScreen(nav)
        is Route.Session -> SessionScreen(route.start, nav)
        Route.Waist -> WaistScreen(nav)
        Route.Tape -> TapeScreen(nav)
        Route.Settings -> SettingsScreen(nav)
        Route.DataCheck -> DataCheckScreen(nav)
        is Route.EditHome -> EditHomeScreen(LayoutSlots.HOME, nav)
        is Route.Sleep -> SleepDetailScreen(route.wakeEpochDay, nav)
        // decode-only (Nav.sanitize / resolve redirect them before they reach the stack; kept as a safety net)
        Route.Health, Route.Calories, Route.HeartZones, Route.WeeklyCardio, is Route.ActivityPeriod -> Redirect(route, nav)
    }
}

@Composable
private fun Redirect(route: Route, nav: Nav) {
    LaunchedEffect(route) { nav.replace(Nav.resolve(route)) }
    Box(Modifier.fillMaxSize().background(LocalHB.current.colors.surface))
}

// =====================================================================================================================
// Bottom bar: 4 tabs (V2), `tile` bar with top r 24, accent-container pill indicator (56×32) behind the selected glyph.
// =====================================================================================================================

private data class Tab(val route: Route, val label: String, val glyph: Glyph)

private val TABS = listOf(
    Tab(Route.Home, "Home", Glyph.Home),
    Tab(Route.Activity, "Activity", Glyph.Rings),
    Tab(Route.Body, "Body", Glyph.Body),
    Tab(Route.Vitals, "Vitals", Glyph.Heart),
)

@Composable
private fun BottomBar(nav: Nav, modifier: Modifier, navInset: androidx.compose.ui.unit.Dp) {
    val hb = LocalHB.current
    val haptics = rememberHaptics()
    Box(
        modifier.fillMaxWidth()
            .background(hb.colors.tile, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .padding(bottom = navInset),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().height(BottomBarHeight).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TABS.forEach { tab ->
                val selected = nav.currentTab == tab.route
                // v3 C2: selected indicator in the palette accent container, glyph in its on-colour
                val pill by animateColorAsState(if (selected) hb.colors.accentCont else Color.Transparent, tween(180), label = "tabPill")
                val glyphColor by animateColorAsState(if (selected) hb.colors.onAccentCont else hb.colors.muted, tween(180), label = "tabGlyph")
                Column(
                    Modifier.weight(1f)
                        .semantics { role = Role.Tab; this.selected = selected; contentDescription = tab.label }
                        .plainClickable {
                            if (nav.currentTab == tab.route && nav.stack.size == 1) return@plainClickable
                            haptics.segment()
                            nav.selectTab(tab.route)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(56.dp, 32.dp).background(pill, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                        GlyphIcon(tab.glyph, size = 22.dp, color = glyphColor)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tab.label,
                        style = if (selected) hb.type.labelS else hb.type.labelS.copy(fontWeight = FontWeight.Medium),
                        color = if (selected) hb.colors.text else hb.colors.muted,
                    )
                }
            }
        }
    }
}
