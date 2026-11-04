package com.boudhealth.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import com.boudhealth.ui.nav.LayoutSlots
import com.boudhealth.ui.nav.LocalLayouts
import com.boudhealth.ui.nav.TileSizes
import com.boudhealth.data.TilePacker
import com.boudhealth.data.TileSize
import com.boudhealth.data.TileSpec
import com.boudhealth.ui.components.ButtonKind
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.GlyphIcon
import com.boudhealth.ui.components.HBButton
import com.boudhealth.ui.components.LocalScreen
import com.boudhealth.ui.components.LocalSnackbar
import com.boudhealth.ui.components.SegmentSize
import com.boudhealth.ui.components.SegmentedRange
import com.boudhealth.ui.components.hbClickable
import com.boudhealth.ui.components.rememberHaptics
import com.boudhealth.ui.components.screenContent
import com.boudhealth.ui.components.shellBottom
import com.boudhealth.ui.nav.LocalPrefs
import com.boudhealth.ui.nav.Nav
import com.boudhealth.ui.nav.PrefsEditor
import com.boudhealth.ui.nav.rememberPrefsEditor
import com.boudhealth.ui.theme.LocalHB
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/** Id of the dashed "+ Drop or add here" slot, packed like a tile so it lands in the first hole that fits an M. */
private const val ADD_SLOT = "__add_slot"

/**
 * Widget frame (A_SPEC §1 / §8, v3.1 semantics shared with widget/): the widget is a vertically SCROLLING list of all
 * tiles, packed into [cols] columns at the widget's width. [h] / [rows] are its typical visible height (they only size the
 * cells and the "visible without scrolling" marker); no tile is ever hidden.
 */
private data class FrameSpec(val w: Dp, val h: Dp, val cols: Int, val rows: Int)

/** Widget slots (Q18): the frame follows the slot's column choice (8 = inner-screen size, 4 = cover / phone size). */
private fun frameFor(slot: String, columns: Int): FrameSpec? = when {
    slot == LayoutSlots.HOME -> null
    columns >= 8 -> FrameSpec(840.dp, 460.dp, 8, 4)
    else -> FrameSpec(412.dp, 560.dp, 4, 5)
}

// =====================================================================================================================
// Edit state: one working copy per surface; committed on Done, on leaving the screen, and survives rotation / fold.
// =====================================================================================================================

private class EditState(
    initial: Map<String, List<TileSpec>>,
    columnsInit: Map<String, Int>,
    dirtyInit: Collection<String> = emptyList(),
    resetInit: Collection<String> = emptyList(),
) {
    val lists = mutableStateMapOf<String, List<TileSpec>>().also { it.putAll(initial) }
    val columns = mutableStateMapOf<String, Int>().also { it.putAll(columnsInit) }
    val dirty = mutableStateListOf<String>().also { it.addAll(dirtyInit) }
    val resets = mutableStateListOf<String>().also { it.addAll(resetInit) }

    var selected by mutableStateOf<String?>(null)
    var dragId by mutableStateOf<String?>(null)
    /** Top-left (px, grid coordinates) of the tile being dragged. */
    var dragPos by mutableStateOf(Offset.Zero)
    var pulseId by mutableStateOf<String?>(null)

    // Geometry of the current grid (px), refreshed after every composition of the grid; read by gesture callbacks.
    var rects: Map<String, Rect> = emptyMap()
    var gridBottom: Float = 0f
    var lastTarget: String? = null

    fun list(s: String): List<TileSpec> = lists[s] ?: emptyList()
    fun cols(s: String): Int = columns[s] ?: LayoutSlots.defaultColumns(s)

    fun set(s: String, l: List<TileSpec>, keepReset: Boolean = false) {
        lists[s] = l
        if (s !in dirty) dirty.add(s)
        if (!keepReset) resets.remove(s)
    }

    fun setColumns(s: String, c: Int) {
        columns.put(s, c)
        if (s !in dirty) dirty.add(s)
        resets.remove(s)
    }

    fun commit(edit: PrefsEditor) {
        dirty.toList().forEach { s ->
            if (s in resets) edit.resetLayout(s) else edit.setLayout(s, list(s), cols(s))
        }
        dirty.clear()
    }
}

@Serializable
private data class EditSnap(val lists: Map<String, List<TileSpec>>, val columns: Map<String, Int>, val dirty: List<String>, val resets: List<String>)

private val editJson = Json { ignoreUnknownKeys = true }

private val EditSaver = Saver<EditState, String>(
    save = { editJson.encodeToString(EditSnap.serializer(), EditSnap(it.lists.toMap(), it.columns.toMap(), it.dirty.toList(), it.resets.toList())) },
    restore = { s -> runCatching { editJson.decodeFromString(EditSnap.serializer(), s) }.getOrNull()?.let { EditState(it.lists, it.columns, it.dirty, it.resets) } },
)

/** Apply a size picked in edit mode: on the 8-column app grid it becomes the inner-screen override (A_SPEC §9). */
private fun TileSpec.withSize(slot: String, wideApp: Boolean, s: TileSize): TileSpec = when {
    slot == LayoutSlots.HOME && wideApp -> copy(sizeWide = s)
    slot == LayoutSlots.HOME -> copy(size = s)
    else -> copy(size = s, sizeWide = null)
}

// =====================================================================================================================
// Screen
// =====================================================================================================================

/**
 * Edit mode (A_SPEC §5): surface switcher (App home · Inner widget · Cover widget), wiggling tiles with "–" badges,
 * long-press-and-drag to reorder (live dense reflow), tap to select + size popover, corner-handle resize, Add tile
 * catalog (§6), Reset with undo, Done. Widget surfaces show the grid at the widget's real aspect inside a frame.
 */
@Composable
fun EditHomeScreen(slot: String, nav: Nav) {
    val hb = LocalHB.current
    val prefs = LocalPrefs.current
    val layouts = LocalLayouts.current
    val screen = LocalScreen.current
    val edit = rememberPrefsEditor()
    val snackbar = LocalSnackbar.current
    val haptics = rememberHaptics()
    val data = rememberHomeData()
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    // V2-116: Arrange edits the Home layout only; there is no slot picker. [slot] is accepted for old links / saved
    // stacks (EditHome("w1") is redirected to "home" by Nav); the w1–w3 layouts are only READ, by the widget migration.
    val st = rememberSaveable(saver = EditSaver) {
        EditState(mapOf(LayoutSlots.HOME to layouts.get(LayoutSlots.HOME).tiles), mapOf(LayoutSlots.HOME to layouts.get(LayoutSlots.HOME).columns))
    }
    val current = LayoutSlots.HOME
    var catalogOpen by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            st.commit(edit)
            edit.setHomeHintSeen()
        }
    }

    val list = st.list(current)
    val frame = frameFor(current, st.cols(current))
    val wideApp = current == LayoutSlots.HOME && columnsFor(screen.widthDp) >= 8

    Box(Modifier.fillMaxSize().background(hb.colors.bgDim)) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll, enabled = st.dragId == null), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Column(Modifier.screenContent(screen.gutter)) {
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Arrange Home", fontSize = 30.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp, lineHeight = 36.sp, color = hb.colors.text, maxLines = 1)
                        Text("${list.size} ${if (list.size == 1) "tile" else "tiles"}", style = hb.type.bodyMedium, color = hb.colors.muted, maxLines = 1)
                    }
                    HBButton("Reset", {
                        val prev = list
                        val wasReset = current in st.resets
                        val s = current
                        val d = LayoutSlots.default(s, prefs.ui)
                        st.set(s, d.tiles)
                        st.columns.put(s, d.columns)
                        st.resets.add(s)
                        st.selected = null
                        snackbar.show("Layout reset to default", Glyph.Undo, onAction = {
                            st.set(s, prev)
                            if (wasReset && s !in st.resets) st.resets.add(s)
                        })
                    }, kind = ButtonKind.Text)
                    Spacer(Modifier.width(4.dp))
                    HBButton("Done", { st.commit(edit); nav.back() }, small = true)
                }
                Spacer(Modifier.height(20.dp))
                // v6 L8: "Arrange Home" = the Home tiles + the Today pill (widgets have their own setup on placement)
                val prefsNow = com.boudhealth.ui.nav.LocalPrefs.current
                Row(Modifier.fillMaxWidth().background(hb.colors.card, androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).padding(start = 16.dp, end = 8.dp)
                    .heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Today pill", style = hb.type.bodyMedium, color = hb.colors.text, modifier = Modifier.weight(1f))
                    com.boudhealth.ui.components.HBSwitch(prefsNow.showTodayPill, { edit.setShowTodayPill(it) })
                }
                Spacer(Modifier.height(20.dp))

                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val avail = maxWidth
                    // Keyed on the surface: every surface gets fresh tile nodes and gesture handlers.
                    key(current, st.cols(current)) {
                    if (frame == null) {
                        val cols = columnsFor(screen.widthDp)
                        val cell = (avail - TileGap * (cols - 1)) / cols
                        EditGrid(st, current, list, cols, cell, cell, null, avail, false, wideApp, data, onOpenCatalog = { catalogOpen = true })
                    } else {
                        val s = minOf(1f, avail / frame.w)
                        val inner = frame.w - 24.dp
                        val cellW = (inner - TileGap * (frame.cols - 1)) / frame.cols
                        val cellH = (frame.h - 24.dp - TileGap * (frame.rows - 1)) / frame.rows
                        // the preview grows with the list, exactly like the scrolling widget (same width, same packing)
                        val rows = remember(list, frame) { TilePacker.pack(list + TileSpec(ADD_SLOT, TileSize.M), frame.cols).rows }
                        val contentH = maxOf(frame.h, cellH * rows + TileGap * (rows - 1).coerceAtLeast(0) + 24.dp)
                        Box(Modifier.size(frame.w * s, contentH * s)) {
                            Box(
                                Modifier
                                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                                    .requiredSize(frame.w, contentH)
                                    .graphicsLayer { scaleX = s; scaleY = s; transformOrigin = TransformOrigin(0f, 0f) }
                                    .background(hb.colors.tileHi, RoundedCornerShape(28.dp))
                                    .padding(12.dp),
                            ) {
                                EditGrid(st, current, list, frame.cols, cellW, cellH, null, inner, true, false, data, onOpenCatalog = { catalogOpen = true }, scale = s)
                                if (contentH > frame.h) {
                                    // where the widget's typical visible area ends; the rest is reached by scrolling it
                                    val fold = frame.h - 12.dp - TileGap / 2
                                    Canvas(Modifier.offset(y = fold).size(inner, 2.dp).zIndex(30f)) {
                                        drawLine(hb.colors.outline, Offset(0f, 0f), Offset(size.width, 0f), 2.dp.toPx(),
                                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())))
                                    }
                                }
                            }
                        }
                    }
                    }
                }
                if (frame != null) {
                    Text(
                        "The widget shows all these tiles in this order and scrolls. The dashed line marks what fits " +
                            "without scrolling at the usual widget size.",
                        style = hb.type.bodyXS, color = hb.colors.muted,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
                Spacer(Modifier.height(shellBottom + 150.dp))
            }
        }

        // ---- bottom: hint + Add tile pill
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(hb.colors.bgDim.copy(alpha = 0f), hb.colors.bgDim, hb.colors.bgDim)))
                .padding(top = 28.dp, bottom = shellBottom + 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            HBButton("Add tile", { catalogOpen = true }, glyph = Glyph.Plus)
        }
    }

    if (catalogOpen) {
        CatalogSheet(
            slot = current,
            present = list.map { it.tileId }.toSet(),
            data = data,
            onDismiss = { catalogOpen = false },
            onAdd = { id, size ->
                val s = current
                val spec = TileSpec(id, TileSizes.clamp(id, size))
                st.set(s, st.list(s).filter { it.tileId != id } + spec)
                st.selected = null
                st.pulseId = id
                catalogOpen = false
                haptics.confirm()
                scope.launch { delay(120); scroll.animateScrollTo(scroll.maxValue) }
            },
        )
    }
}

// =====================================================================================================================
// Edit grid
// =====================================================================================================================

@Composable
private fun EditGrid(
    st: EditState,
    surface: String,
    list: List<TileSpec>,
    columns: Int,
    cellW: Dp,
    cellH: Dp,
    maxRows: Int?,
    width: Dp,
    widget: Boolean,
    wideApp: Boolean,
    data: HomeData?,
    onOpenCatalog: () -> Unit,
    /** Preview scale of a widget frame (1 on the app grid): root-space finger travel is divided by it. */
    scale: Float = 1f,
) {
    val hb = LocalHB.current
    val density = LocalDensity.current
    val haptics = rememberHaptics()
    val snackbar = LocalSnackbar.current

    // Pack with the add slot appended (M), so it lands in the first hole that fits, like a tile would.
    val withSlot = list + TileSpec(ADD_SLOT, TileSize.M)
    val packed = remember(withSlot, columns, maxRows) { TilePacker.pack(withSlot, columns, maxRows) }
    val rows = maxRows ?: packed.rows
    val totalH = if (rows == 0) 0.dp else cellH * rows + TileGap * (rows - 1)

    val cellWpx = with(density) { cellW.toPx() }
    val cellHpx = with(density) { cellH.toPx() }
    val gapPx = with(density) { TileGap.toPx() }
    fun rectOf(p: TilePacker.Placed): Rect {
        val c = p.size.cols.coerceAtMost(columns)
        val x = p.col * (cellWpx + gapPx); val y = p.row * (cellHpx + gapPx)
        return Rect(x, y, x + c * cellWpx + (c - 1) * gapPx, y + p.size.rows * cellHpx + (p.size.rows - 1) * gapPx)
    }
    val placedById = packed.placed.associateBy { it.spec.tileId }
    SideEffect {
        st.rects = packed.placed.filter { it.spec.tileId != ADD_SLOT }.associate { it.spec.tileId to rectOf(it) }
        st.gridBottom = packed.placed.filter { it.spec.tileId != ADD_SLOT }.maxOfOrNull { rectOf(it).bottom } ?: 0f
    }

    // Wiggle (A_SPEC §5): ±0.7° at 2.4 Hz with a random phase per tile; off under reduced motion.
    val wiggle: State<Float>? = if (hb.reducedMotion) null else {
        val inf = rememberInfiniteTransition(label = "wiggle")
        inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1250, easing = LinearEasing), RepeatMode.Restart), label = "wiggleT")
    }

    fun reorder(center: Offset) {
        val id = st.dragId ?: return
        val own = st.rects[id]
        if (own != null && own.contains(center)) { st.lastTarget = null; return }
        val hit = st.rects.entries.firstOrNull { it.key != id && it.value.contains(center) }?.key
        val cur = st.list(surface)
        if (hit == null) {
            st.lastTarget = null
            if (center.y > st.gridBottom && cur.lastOrNull()?.tileId != id) {
                st.set(surface, cur.filter { it.tileId != id } + cur.first { it.tileId == id })
                haptics.tick()
            }
            return
        }
        if (hit == st.lastTarget) return
        st.lastTarget = hit
        val from = cur.indexOfFirst { it.tileId == id }
        val to = cur.indexOfFirst { it.tileId == hit }
        if (from < 0 || to < 0 || from == to) return
        val m = cur.toMutableList()
        val spec = m.removeAt(from)
        m.add(to, spec)
        st.set(surface, m)
        haptics.tick()
    }

    fun remove(id: String) {
        val cur = st.list(surface)
        val idx = cur.indexOfFirst { it.tileId == id }
        if (idx < 0) return
        val spec = cur[idx]
        st.set(surface, cur.filter { it.tileId != id })
        if (st.selected == id) st.selected = null
        haptics.tick()
        snackbar.show("Removed ${TileInfos.get(id).name}", Glyph.Trash, onAction = {
            val now = st.list(surface).filter { it.tileId != id }.toMutableList()
            now.add(idx.coerceAtMost(now.size), spec)
            st.set(surface, now)
        })
    }

    fun resize(id: String, size: TileSize) {
        val cur = st.list(surface)
        st.set(surface, cur.map { if (it.tileId == id) it.withSize(surface, wideApp, size) else it })
    }

    Box(Modifier.width(width).height(totalH)) {
        // Placeholder at the dragged tile's slot.
        val dragged = st.dragId?.let { placedById[it] }
        if (dragged != null) {
            val r = rectOf(dragged)
            Canvas(Modifier.offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }.size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })) {
                drawRoundRect(hb.colors.outline, cornerRadius = CornerRadius(24.dp.toPx()),
                    style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))))
            }
        }

        // Add slot.
        placedById[ADD_SLOT]?.let { p ->
            if (st.dragId == null) {
                val r = rectOf(p)
                Box(
                    Modifier
                        .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                        .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
                        .hbClickable(shape = RoundedCornerShape(24.dp), onClick = onOpenCatalog),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawRoundRect(hb.colors.outline, cornerRadius = CornerRadius(24.dp.toPx()),
                            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlyphIcon(Glyph.Plus, size = 20.dp, color = hb.colors.muted)
                        Spacer(Modifier.width(8.dp))
                        Text("Drop or add here", style = hb.type.bodyMedium, color = hb.colors.muted, maxLines = 1)
                    }
                }
            }
        }

        // Tiles, composed in a stable (id) order so a reorder never moves a node mid-gesture; zIndex does the layering.
        list.sortedBy { it.tileId }.forEach { spec ->
            val p = placedById[spec.tileId] ?: return@forEach   // hidden on a widget surface
            key(spec.tileId) {
                EditTile(
                    st = st, p = p, rect = rectOf(p), columns = columns, cellW = cellW, cellH = cellH, widget = widget, data = data,
                    allowed = TileSizes.sizes(spec.tileId), wiggle = wiggle, gridWidth = width,
                    onReorder = ::reorder, onRemove = ::remove, onResize = ::resize, scale = scale,
                )
            }
        }
    }
}

@Composable
private fun EditTile(
    st: EditState,
    p: TilePacker.Placed,
    rect: Rect,
    columns: Int,
    cellW: Dp,
    cellH: Dp,
    widget: Boolean,
    data: HomeData?,
    allowed: List<TileSize>,
    wiggle: State<Float>?,
    gridWidth: Dp,
    onReorder: (Offset) -> Unit,
    onRemove: (String) -> Unit,
    onResize: (String, TileSize) -> Unit,
    scale: Float = 1f,
) {
    val hb = LocalHB.current
    val density = LocalDensity.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val id = p.spec.tileId
    val dims = tileDims(p.size, cellW, cellH, columns)
    val shape = tileShape(p.size, widget)
    // room for the 48 dp remove / resize targets that stick out of the tile (v3.1 r2: no dead edges)
    val margin = 18.dp
    val marginPx = with(density) { margin.toPx() }
    val isDrag = st.dragId == id
    val selected = st.selected == id
    // v3 D8: a tile with one allowed size gets no handle and no size popover
    val resizable = allowed.size > 1
    val target = IntOffset(rect.left.roundToInt(), rect.top.roundToInt())

    val anim = remember { Animatable(target, IntOffset.VectorConverter) }
    LaunchedEffect(target, isDrag) {
        if (!isDrag) anim.animateTo(target, spring(dampingRatio = 0.8f, stiffness = 500f))
    }
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(st.pulseId) {
        if (st.pulseId == id) {
            delay(250)
            pulse.animateTo(1.04f, tween(140)); pulse.animateTo(1f, tween(160))
            if (st.pulseId == id) st.pulseId = null
        }
    }
    val phase = remember(id) { (id.hashCode() and 0xFFFF) / 65535f }

    // Current values for the gesture callbacks (the pointerInput blocks are keyed on the id only).
    val tileSizePx = Offset(with(density) { dims.width.toPx() }, with(density) { dims.height.toPx() })
    val geo = remember { arrayOf<Any?>(null, null) }
    geo[0] = tileSizePx
    geo[1] = p

    Box(
        Modifier
            .offset {
                val base = if (isDrag) st.dragPos.round() else anim.value
                IntOffset(base.x - marginPx.roundToInt(), base.y - marginPx.roundToInt())
            }
            .zIndex(if (isDrag) 10f else if (selected) 5f else 0f)
            .graphicsLayer {
                val w = wiggle?.value
                rotationZ = when {
                    isDrag -> 3f
                    w != null -> 0.7f * sin(2f * PI.toFloat() * (3f * w + phase))
                    else -> 0f
                }
                val s = if (isDrag) 1.06f else pulse.value
                scaleX = s; scaleY = s
            }
            .padding(margin)
            // v3.1 r2: the tile gestures live on the tile itself (inside the 18 dp margin), so the margin only carries
            // the badge / handle touch targets and never steals touches from a neighbouring tile
            .pointerInput(id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        st.selected = null
                        st.lastTarget = null
                        st.dragPos = Offset(anim.value.x.toFloat(), anim.value.y.toFloat())
                        st.dragId = id
                        haptics.longPress()
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        st.dragPos += amount
                        val sz = geo[0] as Offset
                        onReorder(st.dragPos + Offset(sz.x / 2f, sz.y / 2f))
                    },
                    onDragEnd = {
                        val end = st.dragPos.round()
                        scope.launch { anim.snapTo(end); st.dragId = null }
                    },
                    onDragCancel = {
                        val end = st.dragPos.round()
                        scope.launch { anim.snapTo(end); st.dragId = null }
                    },
                )
            }
            .pointerInput(id, resizable) {
                detectTapGestures(onTap = {
                    st.selected = if (st.selected == id || !resizable) null else id
                    haptics.tick()
                })
            },
    ) {
        Box(Modifier.then(if (isDrag) Modifier.shadow(14.dp, shape, ambientColor = hb.colors.text, spotColor = hb.colors.text) else Modifier)) {
            Tile(id, p.size, dims.width, dims.height, data, widget = widget)
            when {
                selected -> Box(Modifier.size(dims.width, dims.height).border(2.5.dp, hb.colors.accent, shape))
                hb.reducedMotion -> Box(Modifier.size(dims.width, dims.height).border(2.dp, hb.colors.outline, shape))
            }
        }
        // Remove badge (24, text fill, minus in card, 2.5 ring in bgDim) at (−6, −6).
        if (!isDrag) {
            // 48 dp touch target, same 29 dp visual badge at (−6, −6)
            Box(
                Modifier
                    .offset(x = (-15.5).dp, y = (-15.5).dp)
                    .size(48.dp)
                    .hbClickable(shape = CircleShape, scaleOnPress = false) { onRemove(id) }
                    .semantics { contentDescription = "Remove tile" },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(29.dp).background(hb.colors.bgDim, CircleShape).padding(2.5.dp).background(hb.colors.text, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { GlyphIcon(Glyph.Minus, size = 14.dp, color = hb.colors.card, stroke = 3f) }
            }
        }
        // Resize handle (v3 D8 / v3.1): always on a resizable tile. 48 dp touch target around a 27 dp dot. The handle owns
        // the whole gesture (down consumed, so the tile's long-press move never starts), and the wanted size is computed
        // from the drag ORIGIN in root coordinates: start size + finger travel, so a reflow mid-drag can't flip it.
        if (resizable && !isDrag) {
            val handleRoot = remember { arrayOf(Offset.Zero) }
            Box(
                Modifier
                    .offset(x = dims.width - 30.dp, y = dims.height - 30.dp)
                    .size(48.dp)
                    .onGloballyPositioned { handleRoot[0] = it.positionInRoot() }
                    .semantics { contentDescription = "Resize" }
                    .pointerInput(id, listOf(columns, cellW, cellH, scale)) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            // root-space finger position: the handle's root position + local offset × preview scale
                            val k = scale.coerceAtLeast(0.05f)
                            val origin = handleRoot[0] + down.position * k
                            val s0 = (geo[1] as TilePacker.Placed).size
                            val gap = TileGap.toPx()
                            val c0 = s0.cols.coerceAtMost(columns)
                            val w0 = c0 * cellW.toPx() + (c0 - 1) * gap
                            val h0 = s0.rows * cellH.toPx() + (s0.rows - 1) * gap
                            st.selected = id
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) { c.consume(); break }
                                c.consume()
                                val travel = (handleRoot[0] + c.position * k - origin) / k   // back to grid px
                                val wantC = ((w0 + travel.x + gap) / (cellW.toPx() + gap)).roundToInt().coerceIn(1, minOf(4, columns))
                                val wantR = ((h0 + travel.y + gap) / (cellH.toPx() + gap)).roundToInt().coerceIn(1, 2)
                                val best = allowed.minByOrNull { abs(it.cols.coerceAtMost(columns) - wantC) * 2 + abs(it.rows - wantR) * 2 + if (it.cols > columns) 1 else 0 }
                                val now = (geo[1] as TilePacker.Placed).size
                                if (best != null && best != now) { onResize(id, best); haptics.tick() }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(32.dp).background(hb.colors.bgDim, CircleShape).padding(2.5.dp)
                        .background(if (selected) hb.colors.accent else hb.colors.text, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    GlyphIcon(Glyph.ArrowUp, size = 15.dp, color = if (selected) hb.colors.onAccent else hb.colors.card, stroke = 2.6f, modifier = Modifier.graphicsLayer { rotationZ = 135f })
                }
            }
        }
        if (resizable && selected && !isDrag) {
            SizePopover(
                current = p.size, allowed = allowed, columns = columns,
                tileX = with(density) { rect.left.toDp() }, tileY = with(density) { rect.top.toDp() },
                tileW = dims.width, gridWidth = gridWidth, tileH = dims.height, margin = margin,
                onPick = { s -> if (s != p.size) { onResize(id, s); haptics.segment() } },
            )
        }
    }
}

/** Size popover above the selected tile: `inverse` pill with only the tile's allowed sizes, current in `inverseAccent`. */
@Composable
private fun SizePopover(
    current: TileSize,
    allowed: List<TileSize>,
    columns: Int,
    tileX: Dp,
    tileY: Dp,
    tileW: Dp,
    tileH: Dp,
    gridWidth: Dp,
    margin: Dp,
    onPick: (TileSize) -> Unit,
) {
    val hb = LocalHB.current
    val segW = 52.dp
    val offered = TileSize.entries.filter { it in allowed }   // v3 D8: unsupported sizes are not offered
    val popW = segW * offered.size + 12.dp
    val popH = 56.dp
    // Position relative to the tile box (which starts at tile − margin).
    val desiredX = (tileW - popW) / 2
    val minX = -tileX
    val maxX = gridWidth - tileX - popW
    val x = desiredX.coerceIn(minX, maxOf(minX, maxX))
    val above = tileY >= popH + 8.dp
    val y = if (above) -(popH + 8.dp) else tileH + 8.dp
    Row(
        Modifier
            .offset(x = margin + x, y = margin + y)
            .zIndex(20f)
            .size(popW, popH)
            .background(hb.colors.inverse, RoundedCornerShape(28.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        offered.forEach { s ->
            val sel = s == current
            Box(
                Modifier
                    .width(segW)
                    .height(popH - 12.dp)
                    .hbClickable(shape = RoundedCornerShape(22.dp), scaleOnPress = false) { onPick(s) }
                    .background(if (sel) hb.colors.inverseAccent else hb.colors.inverse, RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    s.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = if (sel) hb.colors.inverse else hb.colors.onInverse,
                )
            }
        }
    }
}
