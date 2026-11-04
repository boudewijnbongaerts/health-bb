package com.boudhealth.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import com.boudhealth.data.ErrorLog
import com.boudhealth.data.TilePacker
import com.boudhealth.data.TileSize
import com.boudhealth.data.TileSpec
import com.boudhealth.data.TileSurface
import com.boudhealth.render.ring.RingColors
import com.boudhealth.render.ring.RingData
import com.boudhealth.render.ring.RingLabel
import com.boudhealth.render.ring.RingPaint
import com.boudhealth.render.ring.RingSurface
import com.boudhealth.widget.ClockFont
import com.boudhealth.widget.ClockLayout
import com.boudhealth.widget.ClockMetrics
import com.boudhealth.widget.ClockPaint
import com.boudhealth.widget.ClockSpec
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Widget tile grid (A_SPEC §1 and §8): the surface's tile list packed with the shared dense first-fit packer,
 * cut into horizontal bands (no tile crosses a band edge), each band drawn into one bitmap with android.graphics.Canvas.
 *
 * All geometry is in dp. The widget container (bgDim, r 28, pad 12) is drawn by Glance; bitmaps hold only the tiles
 * (transparent gaps), so the launcher's own rounding and padding stay crisp.
 */
object Renderer {
    /** Widget padding and grid gap (A_SPEC §1, widget rows). */
    const val PAD = 12f
    const val GAP = 8f
    /** v6 M2: a "Health BB" widget at least this wide lays its tiles out on 8 columns. */
    const val WIDE_DP = 600f
    // The surface is chosen by the widget the user placed (widget/WidgetKind: Inner / Cover / Rings), never guessed from
    // the width: a 600 dp threshold sent most inner-screen widgets the cover layout.
    fun columnsFor(surface: TileSurface): Int = if (surface == TileSurface.WIDGET_INNER) 8 else 4

    data class Geometry(val columns: Int, val contentW: Float, val cellW: Float, val cellH: Float, val rows: Int) {
        fun x(col: Int) = col * (cellW + GAP)
        fun span(cells: Int, cell: Float) = cells * cell + (cells - 1) * GAP
        /** Height of grid row [r] in the overlay/bitmaps: the gap below belongs to the row above (none after the last row). */
        fun rowH(r: Int) = cellH + if (r < rows - 1) GAP else 0f
        /** Width of column [c] in the click overlay: the gap to its right belongs to it (none after the last column). */
        fun colW(c: Int) = cellW + if (c < columns - 1) GAP else 0f
    }

    /** A run of cells in one grid row owned by one tile (or empty: [tileId] null). */
    data class Segment(val tileId: String?, val widthDp: Float, val label: String?)
    data class OverlayRow(val heightDp: Float, val segments: List<Segment>)

    /** Rows [rowStart, rowEnd) and the tiles fully inside them. */
    data class Band(val rowStart: Int, val rowEnd: Int, val tiles: List<TilePacker.Placed>, val heightDp: Float, val overlay: List<OverlayRow>)

    data class Layout(val surface: TileSurface, val geo: Geometry, val bands: List<Band>, val tileCount: Int)

    /**
     * Packs [specs] for a widget of [widthDp] × [heightDp]. Cells are as wide as the columns allow; their height stretches
     * so the first screenful of rows fills the widget (cover 4×5 → 5 rows, inner 7×4 → 4 rows), clamped to 0.8–1.3 × width.
     * Tiles are never hidden: content taller than the widget scrolls.
     */
    fun layout(specs: List<TileSpec>, surface: TileSurface, widthDp: Float, heightDp: Float, columns: Int = columnsFor(surface), rows: Int = 0): Layout {
        val cols = columns
        // V2 free size: the content is the widget's width (v6 forced ≥ 160 dp, wider than a 2 × 2 widget)
        val cw = max(40f, widthDp - 2 * PAD)
        val cellW = (cw - GAP * (cols - 1)) / cols
        val pack = TilePacker.pack(specs, cols)
        val avail = max(cellW, heightDp - 2 * PAD)
        // V2-120: rounded, not floored, so a 4 × 2 widget gets 2 rows and its L tile fits without scrolling
        val nominal = max(1, ((avail + GAP) / (cellW * 1.05f + GAP)).roundToInt())
        // v5 W3: a slot with a row count fills the widget height with exactly that many rows (more tiles scroll)
        val shown = if (rows > 0) rows else max(1, min(nominal, pack.rows))
        val cellH = if (rows > 0) ((avail - GAP * (rows - 1)) / rows).coerceAtLeast(48f)
        else ((avail - GAP * (shown - 1)) / shown).let { v -> val lo = max(64f, cellW * 0.8f); v.coerceIn(lo, max(lo, cellW * 1.3f)) }
        val geo = Geometry(cols, cw, cellW, cellH, pack.rows)

        // owner of each cell
        val owner = Array(pack.rows) { arrayOfNulls<TilePacker.Placed>(cols) }
        for (p in pack.placed) {
            val w = min(p.cols, cols)
            for (r in p.row until p.row + p.rows) for (c in p.col until p.col + w) owner[r][c] = p
        }
        // band cuts: a row boundary no tile crosses
        val bands = ArrayList<Band>()
        var start = 0
        for (r in 1..pack.rows) {
            val free = r == pack.rows || pack.placed.none { it.row < r && it.row + it.rows > r }
            if (!free) continue
            val tiles = pack.placed.filter { it.row >= start && it.row < r }
            val overlay = (start until r).map { row ->
                val segs = ArrayList<Segment>()
                var c = 0
                while (c < cols) {
                    val o = owner[row][c]
                    var w = 0f
                    var e = c
                    while (e < cols && owner[row][e] === o) { w += geo.colW(e); e++ }
                    // label = the tap link "<id>@<size>" (V2-027: the router opens Heart points L/XL on the week)
                    segs += Segment(o?.spec?.tileId, w, o?.let { "${it.spec.tileId}@${it.size.name}" })
                    c = e
                }
                OverlayRow(geo.rowH(row), segs)
            }
            bands += Band(start, r, tiles, overlay.sumOf { it.heightDp.toDouble() }.toFloat(), overlay)
            start = r
        }
        return Layout(surface, geo, bands, pack.placed.size)
    }

    /**
     * Columns for a widget of [widthDp] showing [surface]'s layout. The inner layout is designed on 8 columns; when the
     * widget is too narrow for readable 8-column cells (< 56 dp) it reflows the same list on 4 columns, like the app.
     */
    fun columnsFor(surface: TileSurface, widthDp: Float): Int = fitColumns(columnsFor(surface), widthDp)

    /**
     * Columns for a widget [widthDp] wide: [columns] (4 or 8), reflowed to 4 when 8-column cells would be under 56 dp and
     * to 2 when 4-column cells would be under 64 dp.
     */
    fun fitColumns(columns: Int, widthDp: Float): Int {
        fun cell(n: Int) = (widthDp - 2 * PAD - GAP * (n - 1)) / n
        var cols = if (columns >= 8) 8 else 4
        if (cols == 8 && cell(8) < 56f) cols = 4
        // V2-120 free size: a narrow widget (2 × 2 and smaller) packs on 2 columns, so an L tile fills it (v6 crashed here:
        // cells under 49 dp made the row-height clamp empty)
        if (cols == 4 && cell(4) < 64f) cols = 2
        return cols
    }

    /**
     * One tile at [size] drawn into a [widthDp] × [heightDp] bitmap (Rings widget): no grid, the tile fills the box.
     * [radius] = tile corner radius (0 when the widget container already provides the rounding).
     */
    /**
     * Weekly goals widget (WEEKLY_SPEC): the card in one [widthDp] × [heightDp] bitmap, plus its tap regions.
     */
    internal fun renderWeekly(widthDp: Float, heightDp: Float, theme: RenderTheme, src: TileSource, density: Float): Pair<Bitmap, List<WeeklyRenderer.Hit>> {
        val w = max(40f, widthDp); val h = max(40f, heightDp)
        val bmp = Bitmap.createBitmap(max(1, (w * density).roundToInt()), max(1, (h * density).roundToInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(density, density)
        val ink = Ink(c, theme)
        ink.rrect(0f, 0f, w, h, 0f, theme.card)
        val hits = try {
            WeeklyRenderer(ink, src).draw(WeeklyRenderer.sizeFor(w, h), w, h)
        } catch (e: Exception) {
            ErrorLog.record("widget/weekly", e)
            ink.text("Couldn't draw", 14f, h / 2f + 4f, 12f, theme.muted)
            listOf(WeeklyRenderer.Hit(0f, h, WeeklyRenderer.WEEK_ROUTE))
        }
        return bmp to hits
    }

    /** Weekly cardio widget (v4.1 R2): the card in one [widthDp] × [heightDp] bitmap; the whole card is one tap target. */
    internal fun renderCardio(widthDp: Float, heightDp: Float, theme: RenderTheme, src: TileSource, density: Float): Bitmap {
        val w = max(40f, widthDp); val h = max(40f, heightDp)
        val bmp = Bitmap.createBitmap(max(1, (w * density).roundToInt()), max(1, (h * density).roundToInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(density, density)
        val ink = Ink(c, theme)
        ink.rrect(0f, 0f, w, h, 0f, theme.card)
        try {
            CardioRenderer(ink, src).widget(w, h)
        } catch (e: Exception) {
            ErrorLog.record("widget/cardio", e)
            ink.text("Couldn't draw", 14f, h / 2f + 4f, 12f, theme.muted)
        }
        return bmp
    }

    /**
     * V2 Clock widget: the three rings with their numbers on a transparent [dDp] square (through [paintRings]).
     * [wallpaperDark] = the clock text is light (white ink on a dark wallpaper); false on a light wallpaper or a solid
     * light card. [transparent] = background under 50 %.
     */
    internal fun renderRings(dDp: Float, theme: RenderTheme, src: TileSource, density: Float, transparent: Boolean, wallpaperDark: Boolean): Bitmap {
        val d = max(40f, dDp)
        val bmp = Bitmap.createBitmap(max(1, (d * density).roundToInt()), max(1, (d * density).roundToInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(density, density)
        try {
            paintRings(c, theme, 0f, 0f, d, ringInput(src), transparent, wallpaperDark)
        } catch (e: Exception) {
            ErrorLog.record("widget/rings", e)
        }
        return bmp
    }

    // ================================================================================================ ring adapter

    /** Today's three rings as the painter wants them, by ring id (RingData.ORDER: Workout minutes · Steps · Heart points). */
    class RingInput(val progress: FloatArray, val labels: List<RingLabel?>)

    /** Ring data for the widget / preview rings: V2.rings(Day(today)) (ring 3 = the day's heart points, D3); the v6 RingDay if V2 fails. */
    fun ringInput(src: TileSource): RingInput {
        val set = src.todayRingSet
        if (set != null) return RingInput(RingData.progress(set), RingData.labels(set))
        return RingInput(RingData.progress(src.ringToday), RingData.labels(src.ringToday))
    }

    /**
     * THE single entry point for painting rings in widgets and setup previews (IC6): T4's RingPaint.triple, numbers
     * integrated in each ring's stroke ("34 / 50", Inter tnum, ≥ 10 sp). On a stroke too thin for 10 sp numbers the plan is
     * BESIDE: the clock widget has no room for a legend, so those rings are drawn without numbers.
     */
    fun paintRings(c: Canvas, theme: RenderTheme, left: Float, top: Float, d: Float, input: RingInput, transparent: Boolean, wallpaperDark: Boolean) {
        val col = RingColors.of(theme, if (transparent) RingSurface.TRANSPARENT else RingSurface.OPAQUE, wallpaperDark)
        RingPaint.triple(c, left, top, d, input.progress, input.labels, col, RingPaint.strokeFor(d), if (d < 90f) 2f else 3f)
    }

    // ================================================================================================ live preview

    /**
     * The tile grid of a "Health BB" widget of [widthDp] × [heightDp] (V2-120: 8 columns from [WIDE_DP], else 4, as v6);
     * the widget (WidgetRender) and the setup preview ([renderPreviewTiles]) both use it.
     */
    fun widgetLayout(tiles: List<TileSpec>, widthDp: Float, heightDp: Float): Layout {
        val cols = fitColumns(if (widthDp >= WIDE_DP) 8 else 4, widthDp)
        val surface = if (cols >= 8) TileSurface.WIDGET_INNER else TileSurface.WIDGET_COVER   // grid label only
        return layout(tiles, surface, widthDp, heightDp, cols)
    }

    /** Container alpha 0–100 % applied to [c]. */
    fun withOpacity(c: Int, pct: Int): Int = (c and 0x00FFFFFF) or (((pct.coerceIn(0, 100) * 255 + 50) / 100) shl 24)

    /**
     * V2-123 live preview of a "Health BB" widget, drawn by the same code as the widget: container (bgDim at
     * [bgOpacity], r 28, pad 12) + the bands of [widgetLayout] drawn by [drawBand] with tiles at [tileOpacity].
     * Bands below the widget's height are cut off, as on the home screen before scrolling.
     */
    fun renderPreviewTiles(tiles: List<TileSpec>, bgOpacity: Int, tileOpacity: Int, widthDp: Float, heightDp: Float,
                           theme: RenderTheme, src: TileSource, density: Float): Bitmap {
        val w = max(60f, widthDp); val h = max(60f, heightDp)
        val bmp = Bitmap.createBitmap(max(1, (w * density).roundToInt()), max(1, (h * density).roundToInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(density, density)
        c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, w, h), 28f, 28f, Path.Direction.CW) })
        Ink(c, theme).rrect(0f, 0f, w, h, 28f, withOpacity(theme.bgDim, bgOpacity))
        val layout = widgetLayout(tiles, w, h)
        var y = PAD
        for (band in layout.bands) {
            if (y >= h) break
            c.save(); c.translate(PAD, y)
            drawBand(c, layout, band, theme, src, tileOpacity / 100f)
            c.restore()
            y += band.heightDp
        }
        return bmp
    }

    /**
     * V2-123 live preview of a "Health BB · Clock" widget: background at [bgOpacity], rings through [paintRings], time and
     * date drawn by ClockPaint at the boxes of the same ClockLayout.compute the widget uses ([now] = the preview time).
     */
    fun renderPreviewClock(spec: ClockSpec, bgOpacity: Int, widthDp: Float, heightDp: Float, theme: RenderTheme, src: TileSource?,
                           density: Float, now: LocalDateTime, ink: Int? = null, metrics: ClockMetrics = ClockPaint.measure(spec.font)): Bitmap {
        val w = max(60f, widthDp); val h = max(40f, heightDp)
        val bmp = Bitmap.createBitmap(max(1, (w * density).roundToInt()), max(1, (h * density).roundToInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(density, density)
        drawClock(c, spec, bgOpacity, w, h, theme, src?.let { ringInput(it) }, now, metrics, ink)
        return bmp
    }

    /**
     * Clock widget composition on [c] (dp); [rings] null = rings drawn empty (no data yet); [ink] = text colour on a clear
     * background (white by default; dark on a light wallpaper). Shared by the preview and the proof sheets.
     */
    fun drawClock(c: Canvas, spec: ClockSpec, bgOpacity: Int, w: Float, h: Float, theme: RenderTheme, rings: RingInput?, now: LocalDateTime,
                  metrics: ClockMetrics, ink: Int? = null) {
        if (bgOpacity > 0) Ink(c, theme).rrect(0f, 0f, w, h, 28f, withOpacity(theme.card, bgOpacity))
        val g = ClockLayout.compute(w, h, spec, metrics)
        val clear = bgOpacity < 50
        val text = if (clear) (ink ?: 0xFFFFFFFF.toInt()) else theme.text
        val lightInk = ((text shr 16 and 0xFF) + (text shr 8 and 0xFF) + (text and 0xFF)) > 3 * 128
        g.rings?.let { r -> paintRings(c, theme, r.left, r.top, r.width, rings ?: RingInput(FloatArray(3), listOf(null, null, null)), clear, if (clear) lightInk else theme.dark) }
        val f = spec.font
        val lines = if (f.stacked) listOf(now.format(DateTimeFormatter.ofPattern("HH")), now.format(DateTimeFormatter.ofPattern("mm")))
        else listOf(now.format(DateTimeFormatter.ofPattern(f.timePattern)))
        val date = if (spec.showDate) now.format(DateTimeFormatter.ofPattern(f.datePattern)) else null
        ClockPaint.draw(c, g, f, metrics, lines, date, text, theme.ring.c0Argb(0), clear && lightInk)
    }

    fun renderSingle(tileId: String, size: TileSize, widthDp: Float, heightDp: Float, theme: RenderTheme, src: TileSource, density: Float, radius: Float): Bitmap {
        val w = max(40f, widthDp); val h = max(40f, heightDp)
        val bmp = Bitmap.createBitmap(max(1, (w * density).roundToInt()), max(1, (h * density).roundToInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(density, density)
        val ink = Ink(c, theme)
        if (radius > 0f) c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, w, h), radius, radius, Path.Direction.CW) })
        ink.rrect(0f, 0f, w, h, radius, theme.card)
        try {
            Tiles(ink, src).draw(tileId, size, w, h)
        } catch (e: Exception) {
            ErrorLog.record("widget/single/$tileId", e)
            ink.text("Couldn't draw", 14f, h / 2f + 4f, 12f, theme.muted)
        }
        return bmp
    }

    /** Tile corner radius inside widgets (A_SPEC §1: 18 for S, 20 otherwise, nested in the 28 widget radius). */
    private fun radius(size: TileSize) = if (size == TileSize.S) 18f else 20f

    /**
     * Draws one band at [density] px/dp. The caller compresses and recycles the bitmap.
     * A tile that throws is logged and drawn empty, so one bad metric never blanks the widget.
     */
    fun renderBand(layout: Layout, band: Band, theme: RenderTheme, src: TileSource, density: Float, tileAlpha: Float = 1f): Bitmap {
        val g = layout.geo
        val wPx = max(1, (g.contentW * density).roundToInt())
        val hPx = max(1, (band.heightDp * density).roundToInt())
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(density, density)
        drawBand(c, layout, band, theme, src, tileAlpha)
        return bmp
    }

    /**
     * The tiles of one band on [c] (dp, band top-left at 0,0). V2-121: the tile card at [tileAlpha] (0 = clear … 1 = solid);
     * text, numbers and rings are drawn at full strength on top.
     */
    fun drawBand(c: Canvas, layout: Layout, band: Band, theme: RenderTheme, src: TileSource, tileAlpha: Float = 1f) {
        val g = layout.geo
        val ink = Ink(c, theme)
        val tiles = Tiles(ink, src)
        val card = withOpacity(theme.card, (tileAlpha.coerceIn(0f, 1f) * 100f).roundToInt())
        for (p in band.tiles) {
            val cols = min(p.cols, g.columns)
            val x = g.x(p.col)
            var y = 0f
            for (r in band.rowStart until p.row) y += g.cellH + GAP
            val w = g.span(cols, g.cellW)
            val h = g.span(p.rows, g.cellH)
            // XL squeezed into 4 columns stays XL (full width), as in the app
            val size = p.size
            c.save()
            c.translate(x, y)
            val rad = radius(size)
            c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, w, h), rad, rad, Path.Direction.CW) })
            if (card ushr 24 != 0) ink.rrect(0f, 0f, w, h, rad, card)
            try {
                tiles.draw(p.spec.tileId, size, w, h)
            } catch (e: Exception) {
                ErrorLog.record("widget/tile/${p.spec.tileId}", e)
                ink.rrect(0f, 0f, w, h, rad, theme.card)
                ink.text("Couldn't draw", 14f, h / 2f + 4f, 12f, theme.muted)
            }
            c.restore()
        }
    }
}
