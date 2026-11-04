package com.boudhealth.ui.debug

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.boudhealth.data.ErrorLog
import com.boudhealth.data.PeriodUnit
import com.boudhealth.data.SectionId
import com.boudhealth.data.SectionPeriod
import com.boudhealth.data.SectionScope
import com.boudhealth.data.TileSize
import com.boudhealth.data.UiPrefs
import com.boudhealth.render.RenderTheme
import com.boudhealth.ui.components.ChevronRow
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.LocalScreen
import com.boudhealth.ui.home.HomeLoader
import com.boudhealth.ui.home.HomeTileArt
import com.boudhealth.ui.home.TileGap
import com.boudhealth.ui.home.TileInfos
import com.boudhealth.ui.home.tileDims
import com.boudhealth.ui.nav.LocalPrefs
import com.boudhealth.ui.nav.Nav
import com.boudhealth.ui.nav.Route
import com.boudhealth.ui.nav.TileSizes
import com.boudhealth.ui.theme.LocalHB
import com.boudhealth.widget.WidgetFamily
import com.boudhealth.widget.WidgetKind
import com.boudhealth.widget.WidgetSetupActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

/**
 * V2-155 (T8): debug-only screenshot tour for the owner's on-device QA (spec: V2_VISUAL_QA.md §4). One row at the end
 * of Data check, shown only when the app is debuggable (FLAG_DEBUGGABLE; release builds never see it).
 *
 * Every page starts at scroll top and is walked down in steps of 60 % of the viewport ([TourScroll], registered by
 * each scrolling root). Per page: numbered full-resolution parts `NN_name_pK.png` plus one stitched page at 50 %
 * (`NN_name_full.png`, split into `_full_a/_b/…` above 9,000 px). Pinned overlays are hidden while it runs.
 *
 * Pages: Home · every Home tile × every allowed size (dark and light sheets, drawn by the real tile art) · Activity /
 * Body / Vitals × 1D 1W 1M 1Y All (current) + 1D 1W 1M 1Y (previous) + 1W with a selected day · detail pages (Steps,
 * Heart points, Weight, Resting heart rate at 1W, the newest Session, Sleep) · All metrics · Workouts · Settings ·
 * Data check · the widget setup of one placed "Health BB" and one placed "Health BB · Clock" widget (skipped when none
 * is placed). Output: Pictures/HealthBB-QA/<run>_<dark|light>_<inner|cover>/ + Documents/HealthBB-QA/<same>/index.txt.
 * Read-only apart from navigation, section scopes and the widget setup screen re-saving the placed widget's own config.
 */
object ScreenshotTour {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var running = false

    private const val SETTLE_MS = 1800L
    private const val SCROLL_SETTLE_MS = 700L
    private const val MAX_PARTS = 15
    private const val STITCH_MAX_H = 9000

    fun isDebug(ctx: Context): Boolean = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun start(ctx: Context, nav: Nav, ui: UiPrefs, dark: Boolean, wide: Boolean) {
        val main = ctx.findActivity() ?: return
        if (running || !isDebug(ctx)) return
        running = true
        val startMs = System.currentTimeMillis()
        val run = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()) +
            (if (dark) "_dark" else "_light") + (if (wide) "_inner" else "_cover")
        val pics = "Pictures/HealthBB-QA/$run"
        val docs = "Documents/HealthBB-QA/$run"
        TourScroll.active = true
        scope.launch {
            val index = StringBuilder("Health BB QA tour $run\n")
            var n = 0
            var files = 0
            suspend fun saveBmp(name: String, b: Bitmap) { if (save(main, pics, name, b)) files++ }

            /** One page: scroll top, numbered parts down the page, one stitched copy. */
            suspend fun page(name: String) {
                delay(SETTLE_MS)
                n++
                val act = TourScroll.activity?.takeIf { !it.isFinishing } ?: main
                val s = TourScroll.current
                if (s != null) { s.scrollTo(0); delay(400) }
                val pieces = ArrayList<Bitmap>()     // stitched pieces at 50 %
                var first: Bitmap? = null            // part 1 (full res) until the fixed bottom band is known
                var prev: Bitmap? = null
                var prevVal = 0
                var fixed = -1                       // rows of the fixed bottom band (tab bar + inset)
                var parts = 0
                while (parts < MAX_PARTS) {
                    val bmp = capture(act) ?: break
                    val v = s?.value ?: 0
                    parts++
                    saveBmp("%02d_%s_p%d.png".format(n, name, parts), bmp)
                    if (prev == null) {
                        first = bmp
                    } else {
                        if (fixed < 0) {
                            fixed = bottomFixed(prev, bmp)
                            first?.let { f -> pieces += half(f, 0, f.height - fixed) }
                        }
                        val delta = v - prevVal
                        val y1 = bmp.height - fixed
                        val y0 = max(0, y1 - delta)
                        if (y1 > y0) pieces += half(bmp, y0, y1 - y0)
                        if (prev !== first) prev.recycle()
                    }
                    prev = bmp; prevVal = v
                    if (s == null || s.value >= s.maxValue) break
                    val step = max(1, (s.viewportSize * 0.6f).toInt())
                    s.scrollTo(min(s.value + step, s.maxValue)); delay(SCROLL_SETTLE_MS)
                    if (s.value == prevVal) break
                }
                if (parts > 1 && prev != null && fixed >= 0) {
                    pieces += half(prev, prev.height - fixed, fixed)   // the fixed bottom band, once
                    stitch(pieces).forEachIndexed { i, b ->
                        val suffix = if (i == 0 && pieces.sumOf { it.height } <= STITCH_MAX_H) "" else "_" + ('a' + i)
                        saveBmp("%02d_%s_full%s.png".format(n, name, suffix), b); b.recycle()
                    }
                }
                pieces.forEach { it.recycle() }
                if (prev != null && prev !== first) prev.recycle()
                first?.recycle()
                index.append("%02d %-34s parts=%d scrollMax=%s\n".format(n, name, parts, s?.maxValue?.toString() ?: "-"))
                if (s != null) { s.scrollTo(0) }
            }

            try {
                val today = LocalDate.now()
                val start = LocalDate.of(2000, 1, 1)

                // ---- Home + every Home tile
                nav.selectTab(Route.Home); page("home")
                for (d in listOf(true, false)) {
                    n++
                    val sheet = withContext(Dispatchers.IO) { tileSheet(main, ui, d) }
                    if (sheet != null) { saveBmp("%02d_home_tiles_%s.png".format(n, if (d) "dark" else "light"), sheet); sheet.recycle() }
                    index.append("%02d home_tiles_%s\n".format(n, if (d) "dark" else "light"))
                }

                // ---- sections: current periods, previous periods, a selected day
                for (section in listOf(SectionId.ACTIVITY, SectionId.BODY, SectionId.VITALS)) {
                    val sn = section.name.lowercase()
                    nav.selectTab(Route.sectionRoute(section))
                    for (unit in PeriodUnit.entries) {
                        val p = SectionPeriod.of(unit, today, start, today)
                        nav.setScope(section, SectionScope(p, null, true))
                        page("${sn}_${unit.name.lowercase()}")
                    }
                    for (unit in PeriodUnit.entries.filter { it != PeriodUnit.ALL }) {
                        val p = SectionPeriod.of(unit, today, start, today).shift(-1)
                        nav.setScope(section, SectionScope(p, null, false))
                        page("${sn}_${unit.name.lowercase()}_past")
                    }
                    // selected day: yesterday inside this week (Monday: today itself), followToday off (V2_VISUAL_QA #15)
                    val day = if (today.dayOfWeek == DayOfWeek.MONDAY) today else today.minusDays(1)
                    val week = SectionPeriod.of(PeriodUnit.WEEK, today, start, today)
                    nav.setScope(section, SectionScope(week, null, true).select(day))
                    page("${sn}_week_selected_${day.dayOfWeek.name.lowercase().take(3)}")
                    nav.setScope(section, SectionScope(SectionPeriod.of(PeriodUnit.DAY, today, start, today), null, true))
                }

                // ---- detail pages the cards open (at 1W), with the real back stack
                val week = SectionPeriod.of(PeriodUnit.WEEK, today, start, today)
                for ((id, tab) in listOf("steps" to Route.Activity, "zone_min" to Route.Activity, "weight" to Route.Body, "rhr" to Route.Vitals)) {
                    nav.selectTab(tab); nav.openMetric(id, week); page("detail_$id")
                }
                nav.selectTab(Route.Activity); nav.push(Route.MetricDetail("last_workout")); page("session_latest")
                nav.selectTab(Route.Activity); nav.push(Route.Sleep()); page("sleep")

                // ---- pushed pages under their own tab
                nav.selectTab(Route.Home); nav.push(Route.AllMetrics); page("all_metrics")
                nav.selectTab(Route.Activity); nav.push(Route.Workouts); page("workouts")
                nav.selectTab(Route.Home); nav.push(Route.Settings); page("settings")
                nav.push(Route.DataCheck); page("data_check")

                // ---- widget setup (one placed widget per family; none placed → skipped)
                for (family in WidgetFamily.entries) {
                    val id = placedWidget(main, family)
                    if (id == null) { index.append("widget setup ${family.name.lowercase()}: no placed widget, skipped\n"); continue }
                    main.startActivity(Intent(main, WidgetSetupActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                    page("widget_setup_${family.name.lowercase()}")
                    TourScroll.activity?.takeIf { it !== main }?.finish()
                    delay(600)
                }

                // ---- errors logged during the run
                val errs = ErrorLog.errors.value.filter { it.time >= startMs }
                index.append("errors during the run: ${errs.size}\n")
                errs.forEach { index.append("  ${it.step} · ${it.kind} · ${it.type}: ${it.message}\n") }
            } catch (e: Exception) {
                ErrorLog.record("debug/tour", e)
                index.append("tour stopped: ${e.javaClass.simpleName}: ${e.message}\n")
            } finally {
                TourScroll.active = false
                running = false
                saveText(main, docs, "index.txt", index.toString())
                nav.selectTab(Route.Home); nav.push(Route.Settings); nav.push(Route.DataCheck)
                Toast.makeText(main, "QA tour: $files images in $pics", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ capture + stitch

    /** The whole window as drawn (Compose included), via PixelCopy. */
    private suspend fun capture(activity: Activity): Bitmap? {
        val view = activity.window.decorView
        if (view.width <= 0 || view.height <= 0) return null
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val ok = suspendCancellableCoroutine { c ->
            PixelCopy.request(activity.window, bmp, { r -> if (c.isActive) c.resume(r == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
        }
        return if (ok) bmp else { bmp.recycle(); null }
    }

    /** Rows at the bottom that did not move between two shots (tab bar + inset); 12 % of the height if none match. */
    private fun bottomFixed(a: Bitmap, b: Bitmap): Int {
        if (a.width != b.width || a.height != b.height) return (b.height * 0.12f).toInt()
        val w = a.width; val ra = IntArray(w); val rb = IntArray(w)
        var rows = 0
        for (y in a.height - 1 downTo (a.height * 0.6f).toInt()) {
            a.getPixels(ra, 0, w, 0, y, w, 1); b.getPixels(rb, 0, w, 0, y, w, 1)
            if (!ra.contentEquals(rb)) break
            rows++
        }
        return if (rows >= 8) rows else (b.height * 0.12f).toInt()
    }

    /** Rows [y, y + h) of [src] at 50 %. */
    private fun half(src: Bitmap, y: Int, h: Int): Bitmap {
        val hh = h.coerceIn(1, src.height - y.coerceIn(0, src.height - 1))
        val crop = Bitmap.createBitmap(src, 0, y.coerceIn(0, src.height - 1), src.width, hh)
        val out = Bitmap.createScaledBitmap(crop, max(1, src.width / 2), max(1, hh / 2), true)
        if (out !== crop) crop.recycle()
        return out
    }

    /** Pieces stacked top to bottom, split into images of at most [STITCH_MAX_H] px. */
    private fun stitch(pieces: List<Bitmap>): List<Bitmap> {
        if (pieces.isEmpty()) return emptyList()
        val groups = ArrayList<List<Bitmap>>()
        var cur = ArrayList<Bitmap>(); var h = 0
        for (p in pieces) {
            if (h + p.height > STITCH_MAX_H && cur.isNotEmpty()) { groups += cur; cur = ArrayList(); h = 0 }
            cur += p; h += p.height
        }
        if (cur.isNotEmpty()) groups += cur
        return groups.map { g ->
            val out = Bitmap.createBitmap(g.maxOf { it.width }, g.sumOf { it.height }, Bitmap.Config.ARGB_8888)
            val c = Canvas(out); var y = 0f
            g.forEach { c.drawBitmap(it, 0f, y, null); y += it.height }
            out
        }
    }

    // ------------------------------------------------------------------------------------------------ Home tile sheet

    /** Every Home tile × every allowed size, drawn by the real tile art (HomeTileArt = widget art) on one sheet. */
    private suspend fun tileSheet(ctx: Context, ui: UiPrefs, dark: Boolean): Bitmap? {
        val src = runCatching { HomeLoader.load(ctx).src }.getOrNull() ?: return null
        val theme = RenderTheme.of(ctx.applicationContext, ui, dark)
        val dpx = ctx.resources.displayMetrics.density
        val cell = 84f; val gap = TileGap.value; val pad = 16f; val label = 22f
        val sizes = TileSize.entries
        val colX = FloatArray(sizes.size)
        var x = pad
        sizes.forEachIndexed { i, s -> colX[i] = x; x += tileDims(s, cell.dp(), cell.dp(), 4).width.value + 2 * gap }
        val ids = TileInfos.all.map { it.id }
        val rowH = tileDims(TileSize.L, cell.dp(), cell.dp(), 4).height.value + label + gap
        val wDp = x + pad; val hDp = pad + ids.size * rowH + pad
        val out = Bitmap.createBitmap((wDp * dpx).toInt(), (hDp * dpx).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(theme.bgDim)
        c.scale(dpx, dpx)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.text; textSize = 12f }
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.card }
        ids.forEachIndexed { r, id ->
            val top = pad + r * rowH
            c.drawText(TileInfos.get(id).name + "  ($id)", pad, top + 14f, text)
            val allowed = runCatching { TileSizes.sizes(id) }.getOrDefault(sizes)
            sizes.forEachIndexed { i, s ->
                if (s !in allowed) return@forEachIndexed
                val d = tileDims(s, cell.dp(), cell.dp(), 4)
                val tw = d.width.value; val th = d.height.value
                c.save(); c.translate(colX[i], top + label)
                c.drawRoundRect(0f, 0f, tw, th, 20f, 20f, card)
                c.drawPicture(HomeTileArt.record(src, theme, id, s, tw, th))
                c.restore()
            }
        }
        return out
    }

    private fun Float.dp() = androidx.compose.ui.unit.Dp(this)

    // ------------------------------------------------------------------------------------------------ widgets, files

    /** One placed widget of [family] owned by this app, or null. */
    private fun placedWidget(ctx: Context, family: WidgetFamily): Int? = runCatching {
        val m = AppWidgetManager.getInstance(ctx)
        WidgetKind.entries.filter { it.family == family }.firstNotNullOfOrNull { k ->
            m.getAppWidgetIds(ComponentName(ctx.packageName, k.receiverClassName)).firstOrNull()
        }
    }.getOrNull()

    private suspend fun save(ctx: Context, folder: String, name: String, bmp: Bitmap): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val r = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
            r.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: return@runCatching false
            r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            true
        }.getOrDefault(false)
    }

    /** index.txt (Documents accepts non-media files through MediaStore.Files). */
    private suspend fun saveText(ctx: Context, folder: String, name: String, text: String) = withContext(Dispatchers.IO) {
        runCatching {
            val r = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            }
            val uri = r.insert(MediaStore.Files.getContentUri("external"), values) ?: return@runCatching
            r.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        }
    }

    private fun Context.findActivity(): Activity? = activityOrNull()
}

/** The Data check row (debug builds only; renders nothing in release). */
@Composable
fun DebugTourRow(nav: Nav) {
    val ctx = LocalContext.current
    if (!ScreenshotTour.isDebug(ctx)) return
    val ui = LocalPrefs.current.ui
    val dark = LocalHB.current.dark
    val wide = LocalScreen.current.wide
    ChevronRow("QA screenshot tour (debug)", { ScreenshotTour.start(ctx, nav, ui, dark, wide) }, leadingGlyph = Glyph.Grid)
}
