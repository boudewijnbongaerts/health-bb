package com.boudhealth.widget

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.hardware.display.DisplayManagerCompat
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.boudhealth.App
import com.boudhealth.data.Cache
import com.boudhealth.data.ErrorLog
import com.boudhealth.data.MetricEnv
import com.boudhealth.data.Sources
import com.boudhealth.data.ThemeMode
import com.boudhealth.render.RenderTheme
import com.boudhealth.render.Renderer
import com.boudhealth.render.TileSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What one widget instance shows: one [Sized] model per launcher size (SizeMode.Exact). */
data class WidgetModel(
    val kind: WidgetKind,
    val colors: Colors,
    val state: State,
    val sizes: List<Sized>,
    val config: WidgetConfigV2 = WidgetConfigV2(),
) {
    enum class State { READY, NO_DATA }

    val family: WidgetFamily get() = config.family

    /**
     * Container colours for the light and the dark theme (equal when the theme is forced in Settings); [inkDay]/[inkNight]
     * = clock text, [accentDay]/[accentNight] = clock accent (Bold minutes, Mono date).
     */
    data class Colors(val bgDay: Int, val bgNight: Int, val textDay: Int, val textNight: Int,
                      val inkDay: Int = textDay, val inkNight: Int = textNight, val accentDay: Int = textDay, val accentNight: Int = textNight)

    /** [clock] = where rings and clock go (Clock family only). */
    data class Sized(val size: DpSize, val bands: List<BandItem>, val clock: ClockGeometry? = null)

    /**
     * One bitmap (a band of tiles, or the clock widget's rings) as a light and a dark image. The launcher picks the one
     * matching its night mode itself (RemoteViews day/night icons), so a system theme switch needs no re-render.
     * [rows] = click overlay (tile ids per grid cell run); empty for the rings (the whole clock widget is one tap target).
     */
    data class BandItem(val id: Long, val light: Uri, val dark: Uri, val heightDp: Float, val label: String, val rows: List<Renderer.OverlayRow>)

    /** The model rendered for [size], or the closest one (a resize that hasn't been re-rendered yet). */
    fun forSize(size: DpSize): Sized? = sizes.minByOrNull { abs(it.size.width.value - size.width.value) + abs(it.size.height.value - size.height.value) }

    fun hasSize(size: DpSize): Boolean = sizes.any { abs(it.size.width.value - size.width.value) < 1f && abs(it.size.height.value - size.height.value) < 1f }
}

/**
 * Renders one widget instance by its V2 family (WidgetConfigs.getOrMigrate: every old kind is migrated first):
 *  - TILES ("Health BB"): the configured tiles packed for the widget's size (Renderer.widgetLayout), cut into bands,
 *    one PNG per band, tile cards at the configured tile opacity; the container colour carries the background opacity;
 *  - CLOCK ("Health BB · Clock"): ClockLayout.compute per size, the rings as one PNG at the computed diameter
 *    (Renderer.renderRings → the ring adapter); time and date are TextClocks (ClockViews), placed by DashboardWidget.
 * PNGs live in files/widget and go out as FileProvider content:// URIs readable by the home-screen apps (bitmaps sent
 * inline would hit the RemoteViews size limit on the big inner screen). Every render writes new file names (time stamp),
 * so a launcher never shows a cached old image for a new URI.
 */
object WidgetRender {
    private const val DIR = "widget"
    /** Lowest density we render at, so a bitmap made while the lower-density screen was active stays sharp on the other. */
    private const val MIN_DENSITY = 2.75f
    private const val MAX_DENSITY = 4f
    /**
     * Pixel budget per bitmap (≈ 24 MB ARGB). Typical bands are far below it (full-width inner band 840 × 230 dp at 2.75×
     * ≈ 1.5 MP); a very tall band (many rows that no cut can split) is drawn at a lower density instead of exhausting the
     * app's or the launcher's heap. Bitmaps are drawn one at a time and recycled right after the PNG is written.
     */
    private const val MAX_PIXELS = 6_000_000f

    suspend fun render(context: Context, glanceId: GlanceId, kind: WidgetKind): WidgetModel = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val store = (app as App).store
        val ui = store.uiNow()
        // Theme: forced in Settings → one variant; SYSTEM → light + dark, the launcher switches between them.
        val variants: List<RenderTheme> = when (ui.themeMode) {
            ThemeMode.SYSTEM -> listOf(RenderTheme.of(app, ui, dark = false), RenderTheme.of(app, ui, dark = true))
            ThemeMode.LIGHT -> listOf(RenderTheme.of(app, ui, dark = false))
            ThemeMode.DARK -> listOf(RenderTheme.of(app, ui, dark = true))
        }
        val day = variants.first(); val night = variants.last()
        val manager = GlanceAppWidgetManager(app)
        val appWidgetId = runCatching { manager.getAppWidgetId(glanceId) }.getOrDefault(glanceId.hashCode())
        // V2 (IC8): per-instance config; old kinds and v6 configs migrated on first render (idempotent, never empty)
        val config = WidgetConfigs.getOrMigrate(app, appWidgetId, kind, ui)
        val clockFamily = config.family == WidgetFamily.CLOCK
        val colors = if (clockFamily) {
            val clear = config.bgOpacity < 50
            val wall = clearInk(app)
            WidgetModel.Colors(
                Renderer.withOpacity(day.card, config.bgOpacity), Renderer.withOpacity(night.card, config.bgOpacity), day.text, night.text,
                inkDay = if (clear) wall else day.text, inkNight = if (clear) wall else night.text,
                accentDay = day.ring.c0Argb(0), accentNight = night.ring.c0Argb(0),
            )
        } else WidgetModel.Colors(Renderer.withOpacity(day.bgDim, config.bgOpacity), Renderer.withOpacity(night.bgDim, config.bgOpacity), day.text, night.text)
        fun model(state: WidgetModel.State, sizes: List<WidgetModel.Sized> = emptyList()) = WidgetModel(kind, colors, state, sizes, config)

        val sizes = sizesOf(app, manager, glanceId, config.family)
        if (!Cache.get(app).hasAnyData()) return@withContext model(WidgetModel.State.NO_DATA, sizes.map { WidgetModel.Sized(it, emptyList()) })

        val env = MetricEnv.load(app)
        Sources.init(app)   // human app names for sources (V4 API)
        val src = TileSource(env, store.snapshotNow()?.updatedAt)
        val density = renderDensity(app)
        val dir = File(app.filesDir, DIR).apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val readers = readerPackages(app)

        /** [density], lowered when a [wDp] × [hDp] bitmap would exceed [MAX_PIXELS]. */
        fun densityFor(wDp: Float, hDp: Float): Float = min(density, sqrt(MAX_PIXELS / max(1f, wDp * hDp)))

        /** Draws [draw] once per theme variant, saves the PNGs and returns (light, dark) URIs (the same URI twice when forced). */
        fun save(name: String, draw: (RenderTheme) -> Bitmap): Pair<Uri, Uri> {
            val uris = variants.mapIndexed { vi, theme ->
                val bmp = draw(theme)
                val file = File(dir, "w${appWidgetId}_${stamp}_${name}_$vi.png")
                try {
                    FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally {
                    bmp.recycle()
                }
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
                readers.forEach { pkg -> runCatching { app.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
                uri
            }
            return uris.first() to uris.last()
        }

        val metrics = if (clockFamily) ClockPaint.measure(config.font) else null
        val out = sizes.mapIndexed { si, size ->
            val w = size.width.value; val h = size.height.value
            if (clockFamily) {
                val geo = ClockLayout.compute(w, h, config.clockSpec, metrics!!)
                val bands = geo.rings?.let { r ->
                    try {
                        val (light, dark) = save("${si}_r") { theme ->
                            val clear = config.bgOpacity < 50
                            Renderer.renderRings(r.width, theme, src, densityFor(r.width, r.width), clear, if (clear) clearInk(app) != DARK_INK else theme.dark)
                        }
                        listOf(WidgetModel.BandItem(stamp * 1000 + si * 100, light, dark, r.width, "Today rings", emptyList()))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ErrorLog.record("widget/render/rings", e)
                        emptyList()
                    }
                } ?: emptyList()
                WidgetModel.Sized(size, bands, geo)
            } else {
                // V2-120: free size; the layout adapts to the dragged size (8 columns on wide widgets, else 4)
                val layout = Renderer.widgetLayout(config.tiles, w, h)
                val alpha = config.tileOpacity / 100f
                val bands = layout.bands.mapIndexedNotNull { bi, band ->
                    try {
                        val (light, dark) = save("${si}_$bi") { theme -> Renderer.renderBand(layout, band, theme, src, densityFor(layout.geo.contentW, band.heightDp), alpha) }
                        val label = band.tiles.joinToString(", ") { com.boudhealth.data.Metrics.find(it.spec.tileId)?.label ?: it.spec.tileId }
                        WidgetModel.BandItem(stamp * 1000 + si * 100 + bi, light, dark, band.heightDp, label, band.overlay)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ErrorLog.record("widget/render", e)
                        null
                    }
                }
                WidgetModel.Sized(size, bands)
            }
        }
        cleanup(app, dir, appWidgetId)
        model(WidgetModel.State.READY, out)
    }

    /**
     * Clock text on a clear background: dark ink when the wallpaper says dark text reads better on it
     * (WallpaperColors.HINT_SUPPORTS_DARK_TEXT, no permission needed), else white (with the layout's soft shadow).
     */
    fun clearInk(context: Context): Int {
        val hints = runCatching { WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.colorHints ?: 0 }.getOrDefault(0)
        return if (hints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0) DARK_INK else 0xFFFFFFFF.toInt()
    }
    const val DARK_INK = 0xFF1C1B17.toInt()

    /**
     * Pixel density to render at: the highest of the active displays, at least [MIN_DENSITY]. On a Fold the cover and the
     * inner screen can differ in density while only one is active, so a small oversample keeps both sharp (downscaling
     * stays crisp, upscaling would blur).
     */
    private fun renderDensity(context: Context): Float {
        val fromDisplays = runCatching {
            DisplayManagerCompat.getInstance(context).displays.maxOfOrNull { d -> context.createDisplayContext(d).resources.displayMetrics.density }
        }.getOrNull() ?: 0f
        return max(max(fromDisplays, context.resources.displayMetrics.density), MIN_DENSITY).coerceAtMost(MAX_DENSITY)
    }

    /**
     * The launcher sizes of this instance (portrait / landscape, or inner and cover screen when the home screen is mirrored),
     * de-duplicated, at most 3. Glance composes once per entry of this same list (SizeMode.Exact), matched in WidgetModel.forSize.
     */
    private suspend fun sizesOf(context: Context, manager: GlanceAppWidgetManager, id: GlanceId, family: WidgetFamily): List<DpSize> {
        val raw = runCatching { manager.getAppWidgetSizes(id) }.getOrDefault(emptyList())
            .filter { it.width.value > 0f && it.height.value > 0f }
        val list = raw.distinct()
        if (list.isEmpty()) return listOf(defaultSize(family))
        return list.take(3)
    }

    /** Size assumed before the launcher reports one (and for the setup preview of a new placement): 4 × 4 / 4 × 2 cells. */
    fun defaultSize(family: WidgetFamily): DpSize = if (family == WidgetFamily.CLOCK) DpSize(352.dp, 170.dp) else DpSize(352.dp, 352.dp)

    /**
     * Keeps this widget's two newest generations (the launcher may still show the previous one), deletes the rest.
     * The read grants given to the launcher / System UI for a file are revoked before the file goes.
     */
    private fun cleanup(context: Context, dir: File, appWidgetId: Int) {
        val prefix = "w${appWidgetId}_"
        val files = dir.listFiles { f -> f.name.startsWith(prefix) } ?: return
        val stamps = files.mapNotNull { it.name.removePrefix(prefix).substringBefore('_').toLongOrNull() }.distinct().sortedDescending()
        val keep = stamps.take(2).toSet()
        files.filter { (it.name.removePrefix(prefix).substringBefore('_').toLongOrNull() ?: -1L) !in keep }.forEach { delete(context, it) }
        // images of the old section widget (before the tile rebuild) never start with "w<id>_"
        dir.listFiles { f -> !f.name.startsWith("w") }?.forEach { delete(context, it) }
    }

    /** Revokes every read grant on [file]'s content URI, then deletes it. */
    private fun delete(context: Context, file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        file.delete()
    }

    /** Removes all images (and their grants) of deleted widget instances. */
    fun deleteFiles(context: Context, appWidgetIds: IntArray) {
        val app = context.applicationContext
        val dir = File(app.filesDir, DIR)
        appWidgetIds.forEach { id -> dir.listFiles { f -> f.name.startsWith("w${id}_") }?.forEach { delete(app, it) } }
    }

    /**
     * Packages that must be able to read the images: every home-screen app (One UI Home draws both the inner and the
     * cover home screen) plus System UI (lock-screen / hub widget hosts).
     */
    private fun readerPackages(context: Context): List<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homes = runCatching {
            context.packageManager.queryIntentActivities(home, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())
        return (homes + "com.android.systemui").distinct()
    }
}
