package com.boudhealth.ui.home

import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.data.Sync
import com.boudhealth.data.TilePacker
import com.boudhealth.data.TileSpec
import com.boudhealth.ui.nav.LayoutSlots
import com.boudhealth.ui.nav.LocalLayouts
import com.boudhealth.data.TileSize
import com.boudhealth.render.ring.RingData
import com.boudhealth.render.ring.RingLabel
import com.boudhealth.ui.components.EmptyState
import com.boudhealth.ui.components.Fmt
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.GlyphIcon
import com.boudhealth.ui.components.IconCircleButton
import com.boudhealth.ui.components.LocalScreen
import com.boudhealth.ui.components.PaletteRing
import com.boudhealth.ui.components.hbClickable
import com.boudhealth.ui.components.rememberHaptics
import com.boudhealth.ui.components.screenContent
import com.boudhealth.ui.components.shellBottom
import com.boudhealth.ui.nav.ConnectCard
import com.boudhealth.ui.nav.LocalAppStatus
import com.boudhealth.ui.nav.LocalPrefs
import com.boudhealth.ui.nav.Nav
import com.boudhealth.ui.nav.Route
import com.boudhealth.ui.theme.HbShapes
import com.boudhealth.ui.theme.LocalHB
import com.boudhealth.ui.theme.Space
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt


/** Where a tile tap goes: the shared tile router ([Route.forTile]; widgets use the same). Taps go through [Nav.openTile]. */
internal fun routeForTile(id: String): Route = Route.forTile(id) ?: Route.MetricDetail(id)

internal fun greeting(now: LocalTime = LocalTime.now()): String = when (now.hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    in 18..22 -> "Good evening"
    else -> "Good night"
}

/** Grid columns for a content width (A_SPEC §1: 4 below 600 dp, 8 at ≥ 600). */
internal fun columnsFor(widthDp: Float): Int {
    if (widthDp < 600f) return 4
    // 2.0.1: native density + a 1,200 dp content cap. Keep the cell near the 840 dp design's (~97 dp) instead of
    // stretching 8 columns: 8 up to the 840 dp design width, then one more column per ~104 dp (Fold inner ~1,088 dp → 10).
    val content = minOf(widthDp - 2 * Space.gutter(widthDp).value, Space.contentMaxWidth.value)
    return maxOf(8, ((content + TileGap.value) / (96f + TileGap.value)).toInt())
}

/**
 * Home tab: calm header (date, greeting, edit + settings), slim Today pill, the user's tile grid (A_SPEC §4).
 * Pull to refresh runs the foreground sync via LocalAppStatus.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: Nav) {
    val hb = LocalHB.current
    val prefs = LocalPrefs.current
    val status = LocalAppStatus.current
    val screen = LocalScreen.current
    val haptics = rememberHaptics()
    val data = rememberHomeData()
    val layout = LocalLayouts.current.get(LayoutSlots.HOME)
    val specs = layout.tiles
    val sync by Sync.state.collectAsState()
    val ptr = rememberPullToRefreshState()
    val scroll = rememberScrollState()
    com.boudhealth.ui.debug.RegisterTourScroll(scroll)   // V2-155 debug tour

    PullToRefreshBox(
        isRefreshing = status.refreshing,
        onRefresh = { status.refresh() },
        modifier = Modifier.fillMaxSize().background(hb.colors.bgDim),
        state = ptr,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = ptr,
                isRefreshing = status.refreshing,
                modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars),
                containerColor = hb.colors.card,
                color = hb.colors.accent,
            )
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Column(Modifier.screenContent(screen.gutter)) {
                Spacer(Modifier.height(20.dp))
                HomeHeader(
                    onEdit = { nav.push(Route.EditHome(LayoutSlots.HOME)) },
                    onAllMetrics = { nav.push(Route.AllMetrics) },
                    onSettings = { nav.push(Route.Settings) },
                )
                if (status.needsConnect) {
                    Spacer(Modifier.height(16.dp))
                    ConnectCard()
                }
                // v3 D11: the pill repeats the Today tile, so it steps aside when the tile is on Home
                // v6 L8: the toggle alone decides (it used to be silently hidden while the Today tile was on Home)
                if (prefs.showTodayPill) {
                    Spacer(Modifier.height(18.dp))
                    TodayPill(data?.rings, wide = screen.widthDp >= 600f, onClick = { nav.openTile("today_rings") })
                }
                Spacer(Modifier.height(if (screen.widthDp >= 600f) 20.dp else 16.dp))
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val cols = columnsFor(screen.widthDp)
                    if (specs.isEmpty()) {
                        EmptyState(
                            Glyph.Grid, hb.colors.accent,
                            title = "Your home is empty",
                            actionLabel = "Add tiles",
                            onAction = { nav.push(Route.EditHome(LayoutSlots.HOME)) },
                        )
                    } else {
                        HomeGrid(
                            specs = specs, columns = cols, width = maxWidth, data = data,
                            onTap = { id -> nav.openTile(id, large = specs.firstOrNull { it.tileId == id }?.size.let { it == TileSize.L || it == TileSize.XL }) },
                            onLongPress = { haptics.longPress(); nav.push(Route.EditHome(LayoutSlots.HOME)) },
                        )
                    }
                }
                if (false && !prefs.ui.homeHintSeen && specs.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        GlyphIcon(Glyph.Grid, size = 18.dp, color = hb.colors.muted)
                        Spacer(Modifier.width(8.dp))
                        Text("Long-press any tile to move, resize or add tiles", style = hb.type.bodyS, color = hb.colors.muted, textAlign = TextAlign.Center)
                    }
                }
                val bf = sync.backfill
                if (bf != null && !bf.done) {
                    Text(
                        if (bf.paused != null) "Reading history paused · continues later" else "Reading history · ${(bf.fraction * 100).roundToInt()} %",
                        style = hb.type.bodyXS, color = hb.colors.muted, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
                Spacer(Modifier.height(shellBottom + 24.dp))
            }
        }
    }
}

@Composable
private fun HomeHeader(onEdit: () -> Unit, onAllMetrics: () -> Unit, onSettings: () -> Unit) {
    val hb = LocalHB.current
    val today = LocalDate.now()
    val date = remember(today) { DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()).format(today) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(date, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = hb.colors.muted, maxLines = 1)
            Text(LocalPrefs.current.goals.name?.takeIf { it.isNotBlank() }?.let { "${greeting()}, $it" } ?: greeting(), fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, lineHeight = 32.sp, color = hb.colors.text, maxLines = 2)
        }
        Spacer(Modifier.width(12.dp))
        IconCircleButton(Glyph.Grid, onEdit, contentDescription = "Arrange home", background = hb.colors.card)
        Spacer(Modifier.width(8.dp))
        // V2-026: All metrics next to Settings on every tab root (TabActions); Home keeps Arrange in front
        IconCircleButton(Glyph.List, onAllMetrics, contentDescription = "All metrics", background = hb.colors.card)
        Spacer(Modifier.width(8.dp))
        IconCircleButton(Glyph.Settings, onSettings, contentDescription = "Settings", background = hb.colors.card)
    }
}

// =====================================================================================================================
// Grid (read-only): dense first-fit packing from the foundation TilePacker, positions spring on reflow
// =====================================================================================================================

@Composable
internal fun HomeGrid(
    specs: List<TileSpec>,
    columns: Int,
    width: Dp,
    data: HomeData?,
    onTap: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    val cell = (width - TileGap * (columns - 1)) / columns
    val packed = remember(specs, columns) { TilePacker.pack(specs, columns) }
    val totalH = if (packed.rows == 0) 0.dp else cell * packed.rows + TileGap * (packed.rows - 1)
    val density = LocalDensity.current
    Box(Modifier.width(width).height(totalH)) {
        packed.placed.forEach { p ->
            key(p.spec.tileId) {
                val target = with(density) { IntOffset(((cell + TileGap) * p.col).roundToPx(), ((cell + TileGap) * p.row).roundToPx()) }
                val pos by animateIntOffsetAsState(target, spring(dampingRatio = 0.8f, stiffness = 500f), label = "tilePos")
                val dims = tileDims(p.size, cell, cell, columns)
                Tile(
                    p.spec.tileId, p.size, dims.width, dims.height, data,
                    Modifier
                        .offset { pos }
                        .hbClickable(shape = tileShape(p.size), onLongClick = { onLongPress(p.spec.tileId) }) { onTap(p.spec.tileId) },
                )
            }
        }
    }
}

// =====================================================================================================================
// Today pill (A_SPEC §4, v3): 3 mini rings + today's numbers. The week figure lives under the big rings only (C1).
// =====================================================================================================================

@Composable
internal fun TodayPill(r: RingsData?, wide: Boolean, onClick: () -> Unit) {
    val hb = LocalHB.current
    val shape = HbShapes.pill
    // V2-111: everything is read BY RING ID (RingData slots = Workout minutes · Steps · Heart points). The v6 pill built
    // its texts in a fixed order [min, heart %, steps] but read them by ring index (STEPS = 1, INTENSITY = 2), so the
    // Steps slot showed the heart % and the Heart points slot showed the steps ("7.2K"). Steps are a plain number.
    val p = r?.let { RingData.progress(it.day) } ?: FloatArray(RingData.ORDER.size)
    val labels = r?.let { RingData.labels(it.day) } ?: List(RingData.ORDER.size) { null }
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (wide) 72.dp else 64.dp)
            .hbClickable(shape = shape, onClick = onClick)
            .background(hb.colors.card, shape)
            .padding(start = if (wide) 24.dp else 12.dp, end = if (wide) 12.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (wide) {
            Column(Modifier.padding(end = 20.dp)) {
                Text("Today", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = hb.colors.text, maxLines = 1)
                Text(r?.updated?.let { "Updated ${Fmt.hm(it)}" } ?: "Not synced yet", fontSize = 12.5.sp, color = hb.colors.muted, maxLines = 1)
            }
        }
        RingData.ORDER.forEachIndexed { slot, id ->
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                PaletteRing(slot, p[slot], size = if (wide) 36.dp else 32.dp, stroke = if (wide) 6.dp else 5.5.dp)
                Spacer(Modifier.width(if (wide) 10.dp else 8.dp))
                Column(verticalArrangement = Arrangement.Center) {
                    if (wide) Text(RingData.NAMES[id] ?: "", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.muted, maxLines = 1, lineHeight = 15.sp)   // 01.13: one label colour; only the ring is coloured
                    Text(pillValue(id, labels[slot]), fontSize = if (wide) 17.sp else 15.5.sp, fontWeight = FontWeight.Bold, color = hb.colors.text, maxLines = 1, softWrap = false, lineHeight = 19.sp)
                }
            }
        }
        Box(Modifier.size(if (wide) 48.dp else 28.dp).background(hb.colors.tile, HbShapes.pill), contentAlignment = Alignment.Center) {
            GlyphIcon(Glyph.ChevR, size = if (wide) 22.dp else 16.dp, color = hb.colors.text)
        }
    }
}

/** "34 min" · "7,240" · "12 pts" for one ring id; "–" without a value. Never a percentage (V2-111). */
internal fun pillValue(id: String, l: RingLabel?): String {
    l ?: return "–"
    val unit = RingData.UNITS[id].orEmpty()
    return if (unit.isEmpty()) l.value else "${l.value} $unit"
}
