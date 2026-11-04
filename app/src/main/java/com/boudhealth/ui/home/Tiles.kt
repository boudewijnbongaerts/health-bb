package com.boudhealth.ui.home

import android.graphics.Picture
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.boudhealth.data.ErrorLog
import com.boudhealth.data.TileSize
import com.boudhealth.render.Ink
import com.boudhealth.render.RenderTheme
import com.boudhealth.render.TileSource
import com.boudhealth.render.Tiles
import com.boudhealth.ui.components.SkeletonBox
import com.boudhealth.ui.components.shimmer
import com.boudhealth.ui.nav.LocalPrefs
import com.boudhealth.ui.theme.Cat
import com.boudhealth.ui.theme.LocalHB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil

// =====================================================================================================================
// Catalog metadata (A_SPEC §3)
// =====================================================================================================================

internal enum class TileGroup(val label: String) { ACTIVITY("Activity"), BODY("Body"), VITALS("Vitals") }

internal data class TileInfo(val id: String, val name: String, val short: String, val group: TileGroup, val desc: String, val note: String)

internal object TileInfos {
    // V2 wording (V2_TEXTS): Heart points (never "Weekly heart points"), Weekly goals (D20), Workout minutes; ids unchanged.
    // "weekly_cardio" is merged into "zone_min" (TileSizes.RENAMED) and not offered any more; a layout that held both
    // keeps it as a second Heart points tile (TileSizes.HEART_POINTS_ALIAS, see `hidden`).
    val all: List<TileInfo> = listOf(
        TileInfo("today_rings", "Today", "Today", TileGroup.ACTIVITY, "", ""),
        TileInfo("weekly_goals", "Weekly goals", "Weekly goals", TileGroup.ACTIVITY, "", ""),
        TileInfo("steps", "Steps", "Steps", TileGroup.ACTIVITY, "", ""),
        TileInfo("zone_min", "Heart points", "Heart points", TileGroup.ACTIVITY, "", ""),
        TileInfo("heart_zones", "Heart zones", "Zones", TileGroup.ACTIVITY, "", ""),
        TileInfo("week_workouts", "Workouts", "Workouts", TileGroup.ACTIVITY, "", ""),
        TileInfo("yesterday_workouts", "Recent workouts", "Recent", TileGroup.ACTIVITY, "", ""),
        TileInfo("streak", "Streak", "Streak", TileGroup.ACTIVITY, "", ""),
        TileInfo("calories", "Calories burned", "Calories", TileGroup.ACTIVITY, "", ""),
        TileInfo("sleep", "Sleep", "Sleep", TileGroup.ACTIVITY, "", ""),
        TileInfo("vo2", "VO₂ max", "VO₂ max", TileGroup.ACTIVITY, "", ""),
        TileInfo("weight", "Weight", "Weight", TileGroup.BODY, "", ""),
        TileInfo("body_fat", "Body fat percentage", "Body fat", TileGroup.BODY, "", ""),
        TileInfo("lean_mass", "Lean mass", "Lean mass", TileGroup.BODY, "", ""),
        TileInfo("waist", "Waist circumference", "Waist", TileGroup.BODY, "", ""),
        TileInfo("bmi", "BMI", "BMI", TileGroup.BODY, "", ""),
        TileInfo("rhr", "Resting heart rate", "Resting HR", TileGroup.VITALS, "", ""),
        TileInfo("hr", "Heart rate", "Heart rate", TileGroup.VITALS, "", ""),
        TileInfo("hrv", "HRV", "HRV", TileGroup.VITALS, "", ""),
        TileInfo("breathing", "Breathing rate", "Breathing", TileGroup.VITALS, "", ""),
        TileInfo("spo2", "Blood oxygen", "Blood oxygen", TileGroup.VITALS, "", ""),
    )
    /** Ids kept on saved layouts but not offered in the catalog (TileSizes.HEART_POINTS_ALIAS: a second Heart points tile). */
    private val hidden = listOf(TileInfo("weekly_cardio", "Heart points", "Heart points", TileGroup.ACTIVITY, "", ""))
    private val byId = (all + hidden).associateBy { it.id }
    fun get(id: String): TileInfo = byId[id] ?: TileInfo(id, id, id, TileGroup.ACTIVITY, "", "")
}

@Composable
internal fun groupColor(g: TileGroup): Color = when (g) {
    TileGroup.ACTIVITY -> LocalHB.current.palette.calories.first
    TileGroup.BODY -> Cat.lean
    TileGroup.VITALS -> Cat.heart
}

// =====================================================================================================================
// Tile frame (V2, D11 GO / V2-113): the widget tile art, drawn in Compose
// =====================================================================================================================

/**
 * One tile at an exact size: `card` colour, size radius, no shadow. [data] null = skeleton.
 *
 * The content is the widgets' own drawing code (render/Tiles → render/ring/TileArt, the same [TileSource] data and the
 * same ring numbers), so a Home tile and a widget tile are the same pixels (T6 parity, design7/t6/t6_tiles_widget.png).
 * The tile is recorded once on IO into a [Picture] (TileSource reads the cache, never on the main thread) and replayed
 * in a Compose Canvas with `drawIntoCanvas { it.nativeCanvas.drawPicture(…) }`, scaled from dp to px.
 * The caller adds click handling (pass it in [modifier]); this composable only draws.
 */
@Composable
internal fun Tile(
    id: String,
    size: TileSize,
    w: Dp,
    h: Dp,
    data: HomeData?,
    modifier: Modifier = Modifier,
    widget: Boolean = false,
) {
    val hb = LocalHB.current
    val shape = tileShape(size, widget)
    val src = data?.src
    val ctx = LocalContext.current
    val ui = LocalPrefs.current.ui
    val theme = remember(ui, hb.dark) { RenderTheme.of(ctx.applicationContext, ui, hb.dark) }
    val pic by produceState<Picture?>(null, data?.loadedAt ?: 0L, theme, id, size, w, h) {
        value = if (src == null) null else withContext(Dispatchers.IO) { HomeTileArt.record(src, theme, id, size, w.value, h.value) }
    }
    Box(modifier.size(w, h).clip(shape).background(hb.colors.card, shape).semantics { contentDescription = TileInfos.get(id).name }) {
        val p = pic
        if (p == null) TileSkeleton(size)
        else Canvas(Modifier.fillMaxSize()) {
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                val save = nc.save()
                nc.scale(density, density)
                nc.drawPicture(p)
                nc.restoreToCount(save)
            }
        }
    }
}

/** Records tiles with the widget renderer. One at a time: a TileSource memoises in plain maps. */
internal object HomeTileArt {
    private val lock = Any()

    fun record(src: TileSource, theme: RenderTheme, id: String, size: TileSize, wDp: Float, hDp: Float): Picture = synchronized(lock) {
        val pic = Picture()
        val c = pic.beginRecording(ceil(wDp).toInt().coerceAtLeast(1), ceil(hDp).toInt().coerceAtLeast(1))
        val ink = Ink(c, theme)
        try {
            Tiles(ink, src).draw(id, size, wDp, hDp)
        } catch (e: Exception) {
            ErrorLog.record("home/tile/$id", e)
            ink.text("Couldn't draw", 14f, hDp / 2f + 4f, 12f, theme.muted)
        } finally {
            pic.endRecording()
        }
        pic
    }
}

@Composable
private fun TileSkeleton(size: TileSize) {
    Column(Modifier.fillMaxSize().shimmer().padding(14.dp)) {
        SkeletonBox(Modifier.fillMaxWidth(0.5f), height = 12.dp)
        Spacer(Modifier.weight(1f))
        SkeletonBox(Modifier.fillMaxWidth(if (size == TileSize.S) 0.7f else 0.4f), height = 22.dp, radius = 8.dp)
    }
}
