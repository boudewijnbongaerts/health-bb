package com.boudhealth.widget

import android.app.Activity
import android.app.WallpaperManager
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.boudhealth.App
import com.boudhealth.data.MetricEnv
import com.boudhealth.data.Prefs
import com.boudhealth.data.TileSize
import com.boudhealth.data.TileSpec
import com.boudhealth.data.prefsNow
import com.boudhealth.render.RenderTheme
import com.boudhealth.render.Renderer
import com.boudhealth.render.TileSource
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.GlyphIcon
import com.boudhealth.ui.components.HBButton
import com.boudhealth.ui.components.HBSwitch
import com.boudhealth.ui.nav.TileSizes
import com.boudhealth.ui.theme.AppTheme
import com.boudhealth.ui.theme.LocalHB
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * V2 widget setup (design6/v2008_widget_setup.png), opened by the launcher when a widget is placed and on long-press →
 * reconfigure. One screen per family, chosen by the placed widget's config (old hidden widgets reconfigure as Health BB):
 *  - "Health BB": live preview, tiles (choose, form S/M/L/XL, order by dragging the handle), transparency of the widget
 *    background and of the tiles;
 *  - "Health BB · Clock": live preview, clock style (4 fonts), clock size, rings-to-clock ratio, date / rings on-off,
 *    background transparency.
 * The preview is drawn by render/Renderer (renderPreviewTiles / renderPreviewClock: the widget's own drawing and layout
 * code) 120 ms after the last change, on a wallpaper-coloured, light or checkerboard backdrop. No hint texts (owner).
 * The config is written as soon as the screen opens (a new placement never ends up without one) and again on Done.
 */
class WidgetSetupActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        // m9: exported for the launcher; never edit another app's widget id
        val owner = runCatching { AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider?.packageName }.getOrNull()
        if (owner != null && owner != packageName) { finish(); return }
        // the launcher treats a cancelled setup as "don't place": default to OK so Back still places the widget
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        val prefs = runBlocking { withTimeoutOrNull(300) { (application as App).store.prefsNow() } } ?: Prefs()
        val kind = kindOf()
        val start = WidgetConfigs.forSetup(this, appWidgetId, kind, prefs.ui)
        val size = widgetSize(start.family)
        setContent { AppTheme(prefs) { Screen(start, size, prefs) } }
    }

    /** The receiver this widget was placed from (the Clock setup alias as a fallback). */
    private fun kindOf(): WidgetKind {
        val cls = runCatching { AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider?.className }.getOrNull()
        WidgetKind.entries.firstOrNull { it.receiverClassName == cls }?.let { return it }
        return if (intent?.component?.className?.endsWith("RingsClockSetup") == true) WidgetKind.RINGS else WidgetKind.W1
    }

    /** The widget's current size on the home screen (portrait), else the family's default size. */
    private fun widgetSize(family: WidgetFamily): DpSize {
        val o = runCatching { AppWidgetManager.getInstance(this).getAppWidgetOptions(appWidgetId) }.getOrNull()
        val w = o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
        val h = o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
        return if (w > 40 && h > 40) DpSize(w.dp, h.dp) else WidgetRender.defaultSize(family)
    }

    @Composable
    private fun Screen(start: WidgetConfigV2, size: DpSize, prefs: Prefs) {
        val hb = LocalHB.current
        // M3: unsaved edits survive a recreate (theme switch, process death); fold / rotation don't recreate (manifest)
        val state = rememberSaveable(saver = configSaver(start)) { mutableStateOf(start) }
        val clock = start.family == WidgetFamily.CLOCK
        Column(Modifier.fillMaxSize().background(hb.colors.bgDim).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                // R5 #36: the back button has a TalkBack label
                com.boudhealth.ui.components.IconCircleButton(Glyph.Back, { finish() }, contentDescription = "Back", size = 48.dp, glyphSize = 24.dp,
                    background = Color.Transparent)
                Spacer(Modifier.width(8.dp))
                Text(if (clock) "Health BB · Clock" else "Health BB", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.text)
            }
            val scroll = rememberScrollState()
            com.boudhealth.ui.debug.RegisterTourScroll(scroll)   // V2-155 debug tour
            Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp)) {
                PreviewCard(state.value, size, prefs)
                if (clock) ClockSetup(state) else TilesSetup(state)
                Spacer(Modifier.height(20.dp))
                HBButton("Done", { save(state.value) }, fullWidth = true)
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    private fun save(cfg: WidgetConfigV2) {
        WidgetConfigs.put(this, appWidgetId, cfg)
        // redraw: running sessions via the bus tick, this instance (even when idle) via Glance update
        WidgetBus.request(this)
        val app = applicationContext
        val id = appWidgetId
        setupScope.launch { runCatching { DashboardWidget().update(app, GlanceAppWidgetManager(app).getGlanceIdBy(id)) } }
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }
}

private val setupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

private val setupJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun configSaver(fallback: WidgetConfigV2) = androidx.compose.runtime.saveable.Saver<MutableState<WidgetConfigV2>, String>(
    save = { setupJson.encodeToString(WidgetConfigV2.serializer(), it.value) },
    restore = { mutableStateOf(runCatching { setupJson.decodeFromString(WidgetConfigV2.serializer(), it) }.getOrDefault(fallback)) },
)

// ===================================================================================================== live preview

private enum class Backdrop(val label: String) { WALLPAPER("Wallpaper"), LIGHT("Light"), CHECKER("Checker") }

/**
 * V2-123: the widget as it will look, at its current size, drawn by Renderer (same code as the widget) 120 ms after the
 * last change. The data is loaded once per screen.
 */
@Composable
private fun PreviewCard(cfg: WidgetConfigV2, size: DpSize, prefs: Prefs) {
    val hb = LocalHB.current
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val density = LocalDensity.current.density
    var backdrop by remember { mutableStateOf(Backdrop.WALLPAPER) }
    var src by remember { mutableStateOf<TileSource?>(null) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    val wallColors = remember { wallpaperColors(ctx) }
    LaunchedEffect(Unit) {
        src = withContext(Dispatchers.IO) {
            runCatching { TileSource(MetricEnv.load(app), (app as App).store.snapshotNow()?.updatedAt) }.getOrNull()
        }
    }
    LaunchedEffect(cfg, src, backdrop) {
        delay(120)
        val s = src
        val bmp: Bitmap? = withContext(Dispatchers.IO) {
            runCatching {
                val theme = RenderTheme.of(app, prefs.ui)
                val w = size.width.value; val h = size.height.value
                if (cfg.family == WidgetFamily.CLOCK) {
                    val ink = when (backdrop) {
                        Backdrop.LIGHT -> WidgetRender.DARK_INK
                        else -> WidgetRender.clearInk(app)
                    }
                    Renderer.renderPreviewClock(cfg.clockSpec, cfg.bgOpacity, w, h, theme, s, density, LocalDateTime.now(), ink)
                } else if (s != null) Renderer.renderPreviewTiles(cfg.tiles, cfg.bgOpacity, cfg.tileOpacity, w, h, theme, s, density)
                else null
            }.getOrNull()
        }
        if (bmp != null) image = bmp.asImageBitmap()
    }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp).background(hb.colors.card, RoundedCornerShape(24.dp)).padding(12.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(232.dp).clip(RoundedCornerShape(18.dp)).drawBackdrop(backdrop, wallColors), contentAlignment = Alignment.Center) {
            val w = size.width.value; val h = size.height.value
            val scale = min(1f, min((maxWidth.value - 16f) / w, (232f - 16f) / h))
            image?.let { Image(it, contentDescription = null, modifier = Modifier.size((w * scale).dp, (h * scale).dp)) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Live preview", fontSize = 14.sp, color = hb.colors.muted, modifier = Modifier.weight(1f).padding(start = 4.dp))
            Row(Modifier.background(hb.colors.tile, RoundedCornerShape(16.dp)).padding(3.dp)) {
                Backdrop.entries.forEach { b ->
                    val sel = b == backdrop
                    Box(Modifier.clip(RoundedCornerShape(13.dp)).background(if (sel) hb.colors.accent else Color.Transparent)
                        .clickable { backdrop = b }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(b.label, fontSize = 13.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (sel) hb.colors.onAccent else hb.colors.text)
                    }
                }
            }
        }
    }
}

/** Primary / secondary / tertiary colours of the home-screen wallpaper (no permission needed), dark fallback. */
private fun wallpaperColors(ctx: android.content.Context): List<Color> {
    val wc = runCatching { WallpaperManager.getInstance(ctx).getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }.getOrNull()
    val list = listOfNotNull(wc?.primaryColor, wc?.secondaryColor, wc?.tertiaryColor).map { Color(it.toArgb()) }
    return if (list.size >= 2) list else listOf(Color(0xFF1E2A2A), Color(0xFF2B2219), Color(0xFF3A2A1E))
}

private fun Modifier.drawBackdrop(b: Backdrop, wall: List<Color>): Modifier = when (b) {
    Backdrop.WALLPAPER -> background(Brush.linearGradient(wall))
    Backdrop.LIGHT -> background(Color(0xFFE9E7DC))
    Backdrop.CHECKER -> background(Color(0xFFBDBDB5)).drawBehind {
        val cell = 10.dp.toPx()
        val cols = (size.width / cell).toInt() + 1
        val rows = (size.height / cell).toInt() + 1
        for (r in 0 until rows) for (c in 0 until cols) if ((r + c) % 2 == 0) drawRect(Color(0xFF8E8E86), Offset(c * cell, r * cell), Size(cell, cell))
    }
}

// ===================================================================================================== shared controls

@Composable
private fun SectionLabel(text: String, trailing: (@Composable () -> Unit)? = null) {
    val hb = LocalHB.current
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.muted, letterSpacing = 0.8.sp, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    val hb = LocalHB.current
    Column(Modifier.fillMaxWidth().background(hb.colors.card, RoundedCornerShape(22.dp)).padding(vertical = 4.dp)) { content() }
}

/**
 * A slider row: glyph, title, value at the right, then the track with its two end captions. Drag or tap anywhere on
 * the track; [steps] = snapping (e.g. 5 %).
 */
@Composable
private fun SliderRow(glyph: Glyph, title: String, valueText: String, value: Int, range: IntRange, step: Int, left: String, right: String, onChange: (Int) -> Unit) {
    val hb = LocalHB.current
    val accent = hb.colors.accent
    val track = hb.colors.tileHi
    val knob = hb.colors.onAccent
    fun valueAt(x: Float, width: Float): Int {
        val f = (x / width).coerceIn(0f, 1f)
        val raw = range.first + f * (range.last - range.first)
        return ((raw / step).roundToInt() * step).coerceIn(range.first, range.last)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(glyph, size = 22.dp, color = hb.colors.muted)
            Spacer(Modifier.width(14.dp))
            Text(title, fontSize = 16.sp, color = hb.colors.text, modifier = Modifier.weight(1f))
            Text(valueText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = accent)
        }
        val frac = (value - range.first).toFloat() / (range.last - range.first).coerceAtLeast(1)
        Canvas(
            Modifier.fillMaxWidth().height(36.dp)
                .pointerInput(range, step) { detectTapGestures { o -> onChange(valueAt(o.x, size.width.toFloat())) } }
                .pointerInput(range, step) { detectDragGestures { change, _ -> change.consume(); onChange(valueAt(change.position.x, size.width.toFloat())) } },
        ) {
            val r = 9.dp.toPx(); val th = 4.dp.toPx()
            val x0 = r; val x1 = size.width - r; val y = size.height / 2f
            val x = x0 + (x1 - x0) * frac
            drawLine(track, Offset(x0, y), Offset(x1, y), strokeWidth = th, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(accent, Offset(x0, y), Offset(x, y), strokeWidth = th, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawCircle(accent, r, Offset(x, y))
            drawCircle(knob, r * 0.42f, Offset(x, y))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(left, fontSize = 13.sp, color = hb.colors.muted, modifier = Modifier.weight(1f))
            Text(right, fontSize = 13.sp, color = hb.colors.muted)
        }
    }
}

@Composable
private fun Divider() {
    val hb = LocalHB.current
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(hb.colors.outline.copy(alpha = 0.35f)))
}

@Composable
private fun ToggleRow(glyph: Glyph, title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val hb = LocalHB.current
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        GlyphIcon(glyph, size = 22.dp, color = hb.colors.muted)
        Spacer(Modifier.width(14.dp))
        Text(title, fontSize = 16.sp, color = hb.colors.text, modifier = Modifier.weight(1f))
        HBSwitch(checked, onChange)
    }
}

// ===================================================================================================== Health BB

/**
 * The widget's tile list = the Home catalog (ui/home TileInfos.all: same ids, names and order), so no tile can drop out
 * of one list only (audit 1.2: Heart zones had); sizes come from the same TileSizes as Home; glyph = Glyph.forMetric.
 */
private val TILE_CHOICES: List<Triple<String, String, Glyph>>
    get() = com.boudhealth.ui.home.TileInfos.all.map { Triple(it.id, it.name, Glyph.forMetric(it.id)) }
        .filter { runCatching { TileSizes.known(it.first) }.getOrDefault(false) }

private fun choice(id: String): Triple<String, String, Glyph> =
    TILE_CHOICES.firstOrNull { it.first == id } ?: Triple(id, com.boudhealth.ui.home.TileInfos.get(id).name, Glyph.forMetric(id))

private val ROW_H: Dp = 60.dp

@Composable
private fun TilesSetup(state: MutableState<WidgetConfigV2>) {
    val hb = LocalHB.current
    var adding by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragAcc by remember { mutableStateOf(0f) }
    val cfg = state.value
    fun setTiles(t: List<TileSpec>) { if (t.isNotEmpty()) state.value = state.value.copy(tiles = t) }

    SectionLabel("Tiles") {
        Text(if (adding) "Done" else "Add tile", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.accent,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { adding = !adding }.padding(horizontal = 6.dp, vertical = 4.dp))
    }
    Group {
        cfg.tiles.forEachIndexed { i, spec ->
            val id = spec.tileId
            val (_, name, glyph) = choice(id)
            if (i > 0) Divider()
            Row(
                Modifier.fillMaxWidth().height(ROW_H).background(if (dragging == id) hb.colors.tileHi else Color.Transparent).padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // drag handle: moving it past half a row swaps with the neighbour
                Box(
                    Modifier.size(44.dp, ROW_H).pointerInput(id) {
                        detectDragGestures(
                            onDragStart = { dragging = id; dragAcc = 0f },
                            onDragEnd = { dragging = null },
                            onDragCancel = { dragging = null },
                        ) { change, amount ->
                            change.consume()
                            dragAcc += amount.y
                            val row = ROW_H.toPx()
                            val list = state.value.tiles
                            val at = list.indexOfFirst { it.tileId == id }
                            if (dragAcc > row / 2f && at in 0 until list.lastIndex) {
                                setTiles(list.toMutableList().apply { add(at + 1, removeAt(at)) }); dragAcc -= row
                            } else if (dragAcc < -row / 2f && at > 0) {
                                setTiles(list.toMutableList().apply { add(at - 1, removeAt(at)) }); dragAcc += row
                            }
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) { GlyphIcon(Glyph.Drag, size = 20.dp, color = hb.colors.muted) }
                Text("${i + 1}", fontSize = 14.sp, color = hb.colors.muted, modifier = Modifier.width(18.dp))
                Box(Modifier.size(34.dp).background(hb.colors.tileHi, RoundedCornerShape(17.dp)), contentAlignment = Alignment.Center) {
                    GlyphIcon(glyph, size = 18.dp, color = hb.colors.text)
                }
                Spacer(Modifier.width(10.dp))
                Text(name, fontSize = 16.sp, color = hb.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))   // R5 #32
                SizeChips(id, spec.size) { sz -> setTiles(state.value.tiles.map { if (it.tileId == id) it.copy(size = sz, sizeWide = null) else it }) }
            }
        }
    }
    if (adding) {
        Spacer(Modifier.height(12.dp))
        Group {
            TILE_CHOICES.forEachIndexed { i, (id, name, glyph) ->
                val on = cfg.tiles.any { it.tileId == id }
                if (i > 0) Divider()
                Row(
                    Modifier.fillMaxWidth().clickable {
                        val t = state.value.tiles
                        setTiles(if (on) t.filter { it.tileId != id } else t + TileSpec(id, TileSizes.defaultSize(id)))
                    }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(34.dp).background(hb.colors.tileHi, RoundedCornerShape(17.dp)), contentAlignment = Alignment.Center) {
                        GlyphIcon(glyph, size = 18.dp, color = hb.colors.text)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(name, fontSize = 16.sp, color = hb.colors.text, modifier = Modifier.weight(1f))
                    Box(
                        Modifier.size(24.dp).background(if (on) hb.colors.accent else Color.Transparent, RoundedCornerShape(12.dp))
                            .border(1.5.dp, if (on) hb.colors.accent else hb.colors.outline, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) { if (on) GlyphIcon(Glyph.Check, size = 14.dp, color = hb.colors.onAccent) }
                }
            }
        }
    }

    SectionLabel("Transparency")
    Group {
        SliderRow(Glyph.Widget, "Widget background", "${cfg.bgOpacity}${com.boudhealth.ui.section.R3Texts.PCT_SP}%", cfg.bgOpacity, 0..100, 5, "Clear", "Solid") { state.value = state.value.copy(bgOpacity = it) }
        Divider()
        SliderRow(Glyph.Grid, "Tiles", "${cfg.tileOpacity}${com.boudhealth.ui.section.R3Texts.PCT_SP}%", cfg.tileOpacity, 0..100, 5, "Clear", "Solid") { state.value = state.value.copy(tileOpacity = it) }
    }
}

/** S · M · L · XL; forms a tile does not have are shown faded and do nothing. */
@Composable
private fun SizeChips(id: String, current: TileSize, onPick: (TileSize) -> Unit) {
    val hb = LocalHB.current
    val allowed = runCatching { TileSizes.sizes(id) }.getOrDefault(TileSize.entries.toList())
    Row(Modifier.background(hb.colors.tile, RoundedCornerShape(16.dp)).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        TileSize.entries.forEach { sz ->
            val ok = sz in allowed
            val sel = sz == current
            Box(
                Modifier.size(32.dp, 30.dp).clip(RoundedCornerShape(15.dp)).background(if (sel) hb.colors.accent else Color.Transparent)
                    .then(if (ok) Modifier.clickable { onPick(sz) } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(sz.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = when { sel -> hb.colors.onAccent; ok -> hb.colors.text; else -> hb.colors.muted.copy(alpha = 0.35f) })
            }
        }
    }
}

// ===================================================================================================== Health BB · Clock

@Composable
private fun ClockSetup(state: MutableState<WidgetConfigV2>) {
    val hb = LocalHB.current
    val cfg = state.value
    SectionLabel("Clock style")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ClockFont.entries.forEach { f ->
            val sel = f == cfg.font
            Column(
                Modifier.weight(1f).height(104.dp).clip(RoundedCornerShape(18.dp)).background(hb.colors.card)
                    .border(if (sel) 2.dp else 0.dp, if (sel) hb.colors.accent else Color.Transparent, RoundedCornerShape(18.dp))
                    .clickable { state.value = state.value.copy(font = f) }.padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { FontSample(f) }
                Text(f.label, fontSize = 14.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, color = if (sel) hb.colors.accent else hb.colors.text)
            }
        }
    }

    SectionLabel("Size")
    Group {
        SliderRow(Glyph.History, "Clock size", "${cfg.clockSize}${com.boudhealth.ui.section.R3Texts.PCT_SP}%", cfg.clockSize, 50..100, 5, "Small", "Large") { state.value = state.value.copy(clockSize = it) }
        Divider()
        SliderRow(Glyph.Rings, "Rings and clock", "${cfg.ringShare} : ${100 - cfg.ringShare}", cfg.ringShare, 30..70, 2, "More clock", "More rings") {
            state.value = state.value.copy(ringShare = it)
        }
    }

    SectionLabel("Show")
    Group {
        ToggleRow(Glyph.Calendar, "Date", cfg.showDate) { state.value = state.value.copy(showDate = it) }
        Divider()
        ToggleRow(Glyph.Rings, "Rings", cfg.showRings) { state.value = state.value.copy(showRings = it) }
    }

    SectionLabel("Transparency")
    Group {
        SliderRow(Glyph.Widget, "Widget background", "${cfg.bgOpacity}${com.boudhealth.ui.section.R3Texts.PCT_SP}%", cfg.bgOpacity, 0..100, 5, "Clear", "Solid") { state.value = state.value.copy(bgOpacity = it) }
    }
}

/** "08:42" in the style's own system family (Bold: stacked, minutes in the accent colour). */
@Composable
private fun FontSample(f: ClockFont) {
    val hb = LocalHB.current
    val family = when (f) {
        ClockFont.LIGHT -> FontFamily.SansSerif
        ClockFont.BOLD -> FontFamily.SansSerif
        ClockFont.SERIF -> FontFamily.Serif
        ClockFont.MONO -> FontFamily.Monospace
    }
    val weight = when (f) { ClockFont.LIGHT -> FontWeight.Light; ClockFont.BOLD -> FontWeight.Bold; else -> FontWeight.Normal }
    if (f.stacked) Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("08", fontSize = 22.sp, lineHeight = 22.sp, fontFamily = family, fontWeight = weight, color = hb.colors.text)
        Text("42", fontSize = 22.sp, lineHeight = 22.sp, fontFamily = family, fontWeight = weight, color = hb.colors.accent)
    } else Text(f.previewTime, fontSize = if (f == ClockFont.MONO) 19.sp else 24.sp, fontFamily = family, fontWeight = weight,
        fontStyle = FontStyle.Normal, color = hb.colors.text)
}
