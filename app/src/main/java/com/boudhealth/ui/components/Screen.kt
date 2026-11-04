package com.boudhealth.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.boudhealth.ui.theme.LocalHB
import com.boudhealth.ui.theme.Space

// =====================================================================================================================
// Screen metrics + shell padding (provided by AppShell)
// =====================================================================================================================

/** Window width class: [wide] ≥ 680 dp (inner screen) → 2-column detail layouts; [gutter] 20 / 24. */
@Immutable
data class ScreenMetrics(val widthDp: Float = 412f, val wide: Boolean = false, val gutter: Dp = 20.dp)

val LocalScreen = compositionLocalOf { ScreenMetrics() }

/**
 * Padding every screen must keep clear at the bottom: bottom bar (when shown) + navigation-bar inset.
 * Status-bar inset is NOT included (headers add it themselves).
 */
val LocalShellPadding = compositionLocalOf { PaddingValues(0.dp) }

/** Bottom padding as Dp (shortcut). */
val shellBottom: Dp @Composable get() = LocalShellPadding.current.calculateBottomPadding()

/** Content column constrained to [Space.contentMaxWidth] (1,200 dp), centred, with the screen gutter. */
fun Modifier.screenContent(gutter: Dp): Modifier = this.widthIn(max = Space.contentMaxWidth + gutter * 2).fillMaxWidth().padding(horizontal = gutter)

/**
 * Wide: two columns 7fr / 5fr with gap 24 (DESIGN §8). Narrow: stacked, left then right.
 */
@Composable
fun TwoPane(
    modifier: Modifier = Modifier,
    gap: Dp = 24.dp,
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit,
) {
    if (LocalScreen.current.wide) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            Column(Modifier.weight(7f), content = left)
            Column(Modifier.weight(5f), content = right)
        }
    } else {
        Column(modifier.fillMaxWidth()) { left(); right() }
    }
}

// =====================================================================================================================
// Header (DESIGN §5.1)
// =====================================================================================================================

data class HeaderAction(val glyph: Glyph, val contentDescription: String, val enabled: Boolean = true, val onClick: () -> Unit)

/** The header's icon row: back (if [onBack]) on the left, up to 2 actions on the right (40 circles, gap 8). */
@Composable
fun HeaderRow(onBack: (() -> Unit)?, actions: List<HeaderAction>, modifier: Modifier = Modifier, center: (@Composable () -> Unit)? = null, buttonBg: Color = LocalHB.current.colors.tile) {
    Row(modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconCircleButton(Glyph.Back, onBack, contentDescription = "Back", background = buttonBg)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { center?.invoke() }
        actions.forEachIndexed { i, a ->
            if (i > 0) Spacer(Modifier.width(8.dp))
            IconCircleButton(a.glyph, a.onClick, contentDescription = a.contentDescription, enabled = a.enabled, background = buttonBg)
        }
    }
}

/**
 * Expanded screen header, placed as the first thing in a scrolling column (adds the status-bar inset itself).
 * V2 (design6 top_bar, V2_VISUAL_QA #5): ONE row at top inset + 8: [back] · title 28/700 (weight 1, ellipsized) ·
 * actions (40 circles, gap 8); subtitle 14 muted 4 below + optional trailing (source chip, gap 8).
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: List<HeaderAction> = emptyList(),
    subtitle: String? = null,
    subtitleTrailing: (@Composable () -> Unit)? = null,
    onTitlePositioned: ((bottomPx: Float) -> Unit)? = null,
    buttonBg: Color = LocalHB.current.colors.tile,
) {
    val hb = LocalHB.current
    Column(modifier.fillMaxWidth()) {
        Spacer(Modifier.height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp))
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconCircleButton(Glyph.Back, onBack, contentDescription = "Back", background = buttonBg)
                Spacer(Modifier.width(12.dp))
            }
            Text(
                title, style = hb.type.headlineM, color = hb.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).onGloballyPositioned { c -> onTitlePositioned?.invoke(c.positionInParent().y + c.size.height) },
            )
            actions.forEachIndexed { i, a ->
                Spacer(Modifier.width(if (i == 0) 12.dp else 8.dp))
                IconCircleButton(a.glyph, a.onClick, contentDescription = a.contentDescription, enabled = a.enabled, background = buttonBg)
            }
        }
        if (subtitle != null || subtitleTrailing != null) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (subtitle != null) Text(subtitle, style = hb.type.bodyS, color = hb.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null && subtitleTrailing != null) Spacer(Modifier.width(8.dp))
                subtitleTrailing?.invoke()
            }
        }
    }
}

/** The collapsed header bar: surface background, back + centred 20/700 title + actions. Includes the status-bar inset. */
@Composable
fun CollapsedHeaderBar(title: String, onBack: (() -> Unit)?, actions: List<HeaderAction>, modifier: Modifier = Modifier, background: Color = LocalHB.current.colors.surface, gutter: Dp = LocalScreen.current.gutter) {
    val hb = LocalHB.current
    Column(modifier.fillMaxWidth().background(background)) {
        Spacer(Modifier.height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp))
        HeaderRow(onBack, actions, Modifier.padding(horizontal = gutter), center = {
            Text(title, style = hb.type.headlineSmall, color = hb.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        })
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Standard detail-screen scaffold (DESIGN §8): scrolling column, expanded header that collapses into a pinned bar
 * (150 ms crossfade), optional [sticky] content (e.g. the SegmentedRange) that pins under the collapsed bar,
 * content constrained to 840 dp with the gutter, bottom padding for the shell.
 *
 *     DetailScaffold("Resting heart rate", onBack = nav::back, subtitle = "Updated 11:34",
 *         subtitleTrailing = { SourceChip("Google Health") },
 *         sticky = { SegmentedRange(ranges, range, { range = it }, label = { it.label }) }) {
 *         PeriodNavigator(...); MetricHero(...); LineChart(...)
 *     }
 */
@Composable
fun DetailScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: List<HeaderAction> = emptyList(),
    subtitle: String? = null,
    subtitleTrailing: (@Composable () -> Unit)? = null,
    /** V2 (#12): one elevation model: page = bgDim everywhere, groups / cards = card. */
    background: Color = LocalHB.current.colors.bgDim,
    scrollState: ScrollState = rememberScrollState(),
    sticky: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val hb = LocalHB.current
    val screen = LocalScreen.current
    val density = LocalDensity.current
    // v5 (facts only): no "(i)" explainer actions anywhere
    @Suppress("NAME_SHADOWING") val actions = actions.filter { it.glyph != Glyph.Info }
    var titleBottom by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    var stickyTop by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    val statusPx = with(density) { WindowInsets.statusBars.asPaddingValues().calculateTopPadding().toPx() }
    val barPx = statusPx + with(density) { 56.dp.toPx() }
    com.boudhealth.ui.debug.RegisterTourScroll(scrollState)   // V2-155 debug tour (no-op unless the tour runs)
    val collapsed = scrollState.value > titleBottom - barPx * 0.5f && !com.boudhealth.ui.debug.TourScroll.active
    val pinSticky = sticky != null && scrollState.value > stickyTop - barPx
    Box(modifier.fillMaxSize().background(background)) {
        Column(Modifier.fillMaxSize().verticalScroll(scrollState), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.screenContent(screen.gutter)) {
                ScreenHeader(title, onBack = onBack, actions = actions, subtitle = subtitle, subtitleTrailing = subtitleTrailing,
                    onTitlePositioned = { titleBottom = it })
                if (sticky != null) {
                    Spacer(Modifier.height(20.dp))
                    Box(Modifier.fillMaxWidth().onGloballyPositioned { stickyTop = it.positionInParent().y }) { sticky() }
                }
                content()
                Spacer(Modifier.height(shellBottom + 24.dp))
            }
        }
        AnimatedVisibility(collapsed, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
            Column(Modifier.fillMaxWidth().background(background)) {
                CollapsedHeaderBar(title, onBack, actions, background = background)
                if (pinSticky) {
                    Box(Modifier.screenContent(screen.gutter).align(Alignment.CenterHorizontally).padding(bottom = 8.dp)) { sticky() }
                }
            }
        }
    }
}

// =====================================================================================================================
// Section header (DESIGN §5.7)
// =====================================================================================================================

/** titleM 18/700 left; optional action 14/600 accent right ("See all"). [top] 32 above, 12 below. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    top: Dp = 32.dp,
    bottom: Dp = 12.dp,
    trailing: (@Composable () -> Unit)? = null,
) {
    val hb = LocalHB.current
    Row(modifier.fillMaxWidth().padding(top = top, bottom = bottom), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = hb.type.titleM, color = hb.colors.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing?.invoke()
        if (action != null && onAction != null) {
            Text(action, style = hb.type.buttonS, color = hb.colors.accent,
                modifier = Modifier.plainClickable(onClick = onAction).padding(vertical = 4.dp, horizontal = 2.dp))
        }
    }
}

/** For screens that need the padding values explicitly (e.g. a LazyColumn). */
@Composable
fun screenContentPadding(top: Dp = 0.dp): PaddingValues {
    val g = LocalScreen.current.gutter
    return PaddingValues(start = g, end = g, top = top, bottom = shellBottom + 24.dp)
}
