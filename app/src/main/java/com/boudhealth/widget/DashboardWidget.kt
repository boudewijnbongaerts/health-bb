package com.boudhealth.widget

import android.content.Context
import android.graphics.drawable.Icon
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.boudhealth.App
import com.boudhealth.MainActivity
import com.boudhealth.R
import com.boudhealth.data.Sync
import com.boudhealth.render.Renderer
import com.boudhealth.ui.nav.LayoutStore
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// =====================================================================================================================
// Receivers: one per picker entry (see WidgetKind). All use the same GlanceAppWidget class, so one updateAll covers them.
// =====================================================================================================================

abstract class HealthBoudWidgetReceiver(kind: WidgetKind) : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DashboardWidget(kind)

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        WidgetRender.deleteFiles(context, appWidgetIds)
        WidgetConfigs.delete(context, appWidgetIds)
    }
}

/** V2 picker entry "Health BB" (tiles). Class name kept from v1–v3 so placed widgets survive. */
class DashboardWidgetReceiver : HealthBoudWidgetReceiver(WidgetKind.W1)

/** Hidden (V2): placed old Widget 2 / Cover widgets, migrated to Health BB. */
class CoverWidgetReceiver : HealthBoudWidgetReceiver(WidgetKind.W2)

/** Hidden (V2): placed old Widget 3, migrated to Health BB. */
class Widget3Receiver : HealthBoudWidgetReceiver(WidgetKind.W3)

/** Hidden (V2): placed old weekly goals widgets → Health BB with the Weekly goals tile (L). */
class WeeklyWidgetReceiver : HealthBoudWidgetReceiver(WidgetKind.WEEKLY)

/** Hidden (V2): placed old weekly heart widgets → Health BB with the Heart points tile (L). */
class CardioWidgetReceiver : HealthBoudWidgetReceiver(WidgetKind.CARDIO)

/** Hidden (V2): placed Recent workouts widgets → Health BB with the Recent workouts tile (L). */
class RecentWidgetReceiver : HealthBoudWidgetReceiver(WidgetKind.RECENT)

/** Hidden (V2): placed Workouts widgets → Health BB with the Workouts tile (M). */
class WorkoutsWidgetReceiver : HealthBoudWidgetReceiver(WidgetKind.WORKOUTS)

/** V2 picker entry "Health BB · Clock" (class name kept so placed Rings widgets survive). */
class RingsWidgetReceiver : HealthBoudWidgetReceiver(WidgetKind.RINGS)

/**
 * The Health BB widgets: two families (V2, see [WidgetKind] / WidgetConfigV2). Tiles are drawn with android.graphics.Canvas
 * into PNGs (render/Renderer) in a light and a dark variant; "Health BB" shows its bands in a scrollable LazyColumn with a
 * transparent grid of clickable boxes over each band that maps every tile's cells to its metric detail page (tile id →
 * MainActivity EXTRA_METRIC; the router maps ids to V2 routes). "Health BB · Clock" = rings PNG + TextClocks.
 *
 * [fallback] is only used when the launcher doesn't report the provider: an instance's real kind comes from the receiver it was placed from, because
 * `DashboardWidget().updateAll(context)` (Refresh.publish, WidgetBus) runs this class for every instance of every kind.
 *
 * Updates: Refresh.publish → updateAll (after every sync, and after palette / theme / goals / widget layout edits through
 * the prefs editor, incl. PrefsEditor.setLayout for the w1–w3 slots). While a session runs it also re-renders on its own
 * when the slot layouts (LayoutStore), the store (ui prefs / goals / snapshot),
 * Sync.version, WidgetBus.tick or the launcher size changes. Light/dark switches of the phone need no update at all.
 */
class DashboardWidget(private val fallback: WidgetKind = WidgetKind.W1) : GlanceAppWidget() {

    /** One composition per launcher size (portrait / landscape, or both screens of a mirrored Fold home screen). */
    override val sizeMode: SizeMode = SizeMode.Exact

    private data class Key(val ui: Any?, val goals: Any?, val snapshotAt: Long?, val layouts: Any?, val version: Long, val tick: Long, val resize: Int)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext
        val kind = WidgetKind.of(app, id, fallback)
        val store = (app as App).store
        val model = MutableStateFlow(WidgetRender.render(app, id, kind))
        val resize = MutableStateFlow(0)
        val requested = HashSet<DpSize>()
        var lastRender = System.currentTimeMillis()

        coroutineScope {
            // Re-render when anything the tiles depend on changes (first emission = what was just rendered: skipped).
            launch {
                combine(
                    combine(store.ui, store.goals, store.snapshot.map { it?.updatedAt }) { u, g, s -> Triple(u, g, s) },
                    LayoutStore.stored(app), Sync.version, WidgetBus.tick, resize,
                ) { (u, g, s), l, v, t, r -> Key(u, g, s, l, v, t, r) }
                    .distinctUntilChanged()
                    .drop(1)
                    // M4: at most one re-render per THROTTLE_MS (history backfill bumps Sync.version many times a
                    // minute); changes in between are conflated to the latest, and a render in progress is never discarded
                    .conflate()
                    .collect {
                        val wait = lastRender + THROTTLE_MS - System.currentTimeMillis()
                        if (wait > 0) delay(wait)
                        model.value = WidgetRender.render(app, id, kind)
                        lastRender = System.currentTimeMillis()
                    }
            }
            provideContent {
                val m by model.collectAsState()
                val size = LocalSize.current
                if (m.state == WidgetModel.State.READY && !m.hasSize(size)) {
                    // launcher resized while this session was running: render for the new sizes once
                    SideEffect { if (requested.add(size)) resize.value = resize.value + 1 }
                }
                Content(m, size)
            }
        }
    }

    @Composable
    private fun Content(m: WidgetModel, size: DpSize) {
        val c = m.colors
        val clock = m.family == WidgetFamily.CLOCK
        Box(
            GlanceModifier.fillMaxSize()
                .background(ColorProvider(day = Color(c.bgDay), night = Color(c.bgNight)))
                .cornerRadius(28.dp)
                .padding(if (clock) 0.dp else Renderer.PAD.dp),
            contentAlignment = Alignment.TopStart,
        ) {
            val sized = m.forSize(size)
            when {
                m.state == WidgetModel.State.NO_DATA -> Message(m, "Open Health BB", actionStartActivity<MainActivity>())
                sized == null -> Message(m, "Open Health BB", actionStartActivity<MainActivity>())
                clock -> Clock(sized, m)
                sized.bands.isEmpty() -> Message(m, "Open Health BB", actionStartActivity<MainActivity>())
                else -> LazyColumn(GlanceModifier.fillMaxSize()) {
                    items(sized.bands, itemId = { it.id }) { band -> BandView(band) }
                }
            }
        }
    }

    @Composable
    private fun Message(m: WidgetModel, text: String, action: Action) {
        Box(GlanceModifier.fillMaxSize().clickable(action).padding(12.dp), contentAlignment = Alignment.Center) {
            Text(
                text,
                style = TextStyle(
                    color = ColorProvider(day = Color(m.colors.textDay), night = Color(m.colors.textNight)),
                    fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                ),
            )
        }
    }

    /**
     * The band image as plain RemoteViews: an ImageView whose icon has a day and a night variant
     * (RemoteViews.setIcon(…, notNight, night), API 31+). The launcher applies the one for its current night mode and
     * re-applies on a theme switch, also while our process is dead.
     */
    @Composable
    private fun BandImage(band: WidgetModel.BandItem, modifier: GlanceModifier) {
        val ctx = LocalContext.current
        val rv = RemoteViews(ctx.packageName, R.layout.hb_widget_image).apply {
            if (band.light == band.dark) setIcon(R.id.hb_widget_image, "setImageIcon", Icon.createWithContentUri(band.light))
            else setIcon(R.id.hb_widget_image, "setImageIcon", Icon.createWithContentUri(band.light), Icon.createWithContentUri(band.dark))
            setContentDescription(R.id.hb_widget_image, band.label)
        }
        AndroidRemoteViews(rv, modifier)
    }

    /**
     * V2-122/126 "Health BB · Clock": rings bitmap and the clock RemoteViews placed at the boxes of ClockLayout.compute
     * (absolute positions through start/top padding), so rings and clock are one centred group and the digits sit on the
     * rings' centre line. One tap → today_rings.
     */
    @Composable
    private fun Clock(sized: WidgetModel.Sized, m: WidgetModel) {
        val ctx = LocalContext.current
        val g = sized.clock ?: return
        val c = m.colors
        val views = ClockViews.build(ctx.packageName, m.config.font, g, m.config.showDate, c.inkDay, c.inkNight, c.accentDay, c.accentNight)
        Box(GlanceModifier.fillMaxSize().clickable(actionStartActivity<MainActivity>(actionParametersOf(METRIC to "today_rings")))) {
            val band = sized.bands.firstOrNull()
            val r = g.rings
            if (band != null && r != null) Box(GlanceModifier.fillMaxSize().padding(start = r.left.dp, top = r.top.dp)) {
                BandImage(band, GlanceModifier.width(r.width.dp).height(r.height.dp))
            }
            // +2 dp: TextClock rounding never clips the last digit or the date's descenders
            Box(GlanceModifier.fillMaxSize().padding(start = g.clock.left.dp, top = g.clock.top.dp)) {
                AndroidRemoteViews(views, GlanceModifier.width((g.clock.width + 2f).dp).height((g.clock.height + 2f).dp))
            }
        }
    }

    /**
     * Band bitmap + a grid of clickable boxes, kept as shallow as possible so taps land reliably:
     *  - consecutive grid rows with the same tiles at the same widths are merged into one taller row (a band of L/XL
     *    tiles side by side becomes a single Row), so most bands are Box › Row › Box;
     *  - only bands whose rows really differ get a Column (Box › Column › Row › Box);
     *  - more than 10 rows (Glance's child limit) are chunked as a last resort.
     */
    @Composable
    private fun BandView(band: WidgetModel.BandItem) {
        val rows = mergeRows(band.rows)
        Box(GlanceModifier.fillMaxWidth().height(band.heightDp.dp)) {
            BandImage(band, GlanceModifier.fillMaxSize())
            when {
                rows.isEmpty() -> {}
                rows.size == 1 -> SegmentRow(rows[0], GlanceModifier.fillMaxSize())
                rows.size <= MAX_CHILDREN -> Column(GlanceModifier.fillMaxSize()) {
                    rows.forEach { SegmentRow(it, GlanceModifier.fillMaxWidth().height(it.heightDp.dp)) }
                }
                else -> Column(GlanceModifier.fillMaxSize()) {
                    rows.chunked(MAX_CHILDREN).take(MAX_CHILDREN).forEach { chunk ->
                        Column(GlanceModifier.fillMaxWidth().height(chunk.sumOf { it.heightDp.toDouble() }.toFloat().dp)) {
                            chunk.forEach { SegmentRow(it, GlanceModifier.fillMaxWidth().height(it.heightDp.dp)) }
                        }
                    }
                }
            }
        }
    }

    /** One overlay row: a clickable box per tile run (the last one takes the remaining width), spacers for empty cells. */
    @Composable
    private fun SegmentRow(row: Renderer.OverlayRow, modifier: GlanceModifier) {
        Row(modifier) {
            row.segments.forEachIndexed { i, seg ->
                val base = if (i == row.segments.lastIndex) GlanceModifier.defaultWeight().fillMaxHeight()
                else GlanceModifier.width(seg.widthDp.dp).fillMaxHeight()
                val tile = seg.tileId
                if (tile != null) {
                    // "route:<link>" opens a route (e.g. the week view), anything else a metric detail
                    // V2-027: "<id>@<size>" (Route.parseTileLink): Heart points L/XL opens the week, S/M the day
                    val action = if (tile.startsWith(ROUTE_PREFIX)) actionStartActivity<MainActivity>(actionParametersOf(ROUTE to tile.removePrefix(ROUTE_PREFIX)))
                    else actionStartActivity<MainActivity>(actionParametersOf(METRIC to (seg.label ?: tile)))
                    Box(base.clickable(action)) {}
                } else Spacer(base)
            }
        }
    }

    /** Merges consecutive rows with identical segments (same tile ids and widths) into one row of the summed height. */
    private fun mergeRows(rows: List<Renderer.OverlayRow>): List<Renderer.OverlayRow> {
        val out = ArrayList<Renderer.OverlayRow>()
        for (r in rows) {
            val last = out.lastOrNull()
            if (last != null && last.segments == r.segments) out[out.lastIndex] = Renderer.OverlayRow(last.heightDp + r.heightDp, r.segments)
            else out += r
        }
        return out
    }

    companion object {
        private const val MAX_CHILDREN = 10
        private const val THROTTLE_MS = 3_000L
        private const val ROUTE_PREFIX = "route:"
        /** MainActivity deep-link extras (FOUNDATION §3): metric detail, or a route link such as "edit_widget_inner". */
        private val METRIC = ActionParameters.Key<String>(MainActivity.EXTRA_METRIC)
        private val ROUTE = ActionParameters.Key<String>(MainActivity.EXTRA_ROUTE)
    }
}
