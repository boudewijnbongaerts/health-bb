package com.boudhealth.ui.nav

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.boudhealth.data.TileCatalog
import com.boudhealth.data.TileSize
import com.boudhealth.data.TileSpec
import com.boudhealth.data.TileSurface
import com.boudhealth.data.UiPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * v4 tile layouts (Q18): the app home plus three generic widget slots, each with its own column count.
 *
 * SHARED CONTRACT with widget/ + render/ (read with [LayoutStore.read], never parse the JSON yourself):
 *   DataStore file  "hb_layouts" (preferencesDataStore, this file owns the single instance)
 *   String keys     "layout_home", "layout_w1", "layout_w2", "layout_w3"
 *   Value           JSON of [SlotLayout] = { "columns": 4 | 8, "tiles": [ TileSpec… ] }  (TileSpec as in data/Prefs.kt)
 *   Missing key     = the default ([LayoutSlots.default]): w1 = the old Inner layout at 8 columns, w2 = the old Cover
 *                     layout at 4, w3 = a small rings/activity set at 4, home = the old App home layout.
 * The widget is a vertically scrolling list of ALL tiles of its slot, packed with TilePacker at `columns`.
 *
 * Why not UiPrefs.layouts: data/Prefs.kt's TileCatalog.validate() drops the v4 tile ids (calories split, heart zones,
 * zone n, weekly goals, yesterday's workouts, workout minutes), and that file belongs to the data team. [TileSizes]
 * here is the tile catalog the UI and widgets validate against.
 */

/** One layout: the tiles in packing order and the grid width in columns (4 or 8). */
@Serializable
data class SlotLayout(
    val columns: Int,
    val tiles: List<TileSpec>,
    /** v5 W3: widget background opacity in percent, 0 (transparent) – 100 (solid). Ignored for "home". */
    val opacity: Int = 100,
    /** v5 W3: widget height in grid rows (1–8); 0 = as tall as its tiles. Width = [columns]. Ignored for "home". */
    val rows: Int = 0,
)

object LayoutSlots {
    const val HOME = "home"
    const val W1 = "w1"
    const val W2 = "w2"
    const val W3 = "w3"
    val WIDGETS: List<String> = listOf(W1, W2, W3)
    val ALL: List<String> = listOf(HOME, W1, W2, W3)

    fun label(slot: String): String = when (slot) {
        W1 -> "Widget 1"; W2 -> "Widget 2"; W3 -> "Widget 3"; else -> "App home"
    }

    fun defaultColumns(slot: String): Int = if (slot == W1) 8 else 4

    /** Defaults; the old Inner / Cover layouts carry over to Widget 1 / Widget 2. */
    fun default(slot: String, ui: UiPrefs): SlotLayout = when (slot) {
        W1 -> SlotLayout(8, v41(ui.layout(TileSurface.WIDGET_INNER)))
        W2 -> SlotLayout(4, v41(ui.layout(TileSurface.WIDGET_COVER)))
        W3 -> SlotLayout(4, listOf(TileSpec("today_rings", TileSize.L), TileSpec("zone_min", TileSize.L), TileSpec("calories", TileSize.M)))
        else -> SlotLayout(4, v41(ui.layout(TileSurface.APP)))
    }

    /** v4.1: "Last workout" → "Yesterday's workouts" in the defaults (sizes clamped to what that tile allows). */
    private fun v41(l: List<TileSpec>): List<TileSpec> = TileSizes.validate(l)
}

/** [TileSizes] (allowed sizes, renames, validate) lives in TileSizes.kt: pure, JVM-tested (scratchpad/t5/test). */

// a corrupt file is replaced by an empty one (= every slot back to its default) instead of crashing the app
private val Context.layoutDataStore by preferencesDataStore(
    name = "hb_layouts",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

object LayoutStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun key(slot: String) = stringPreferencesKey("layout_$slot")

    /** Stored layouts only (slot → layout); missing slots resolve to their default in [Layouts]. */
    fun stored(context: Context): Flow<Map<String, SlotLayout>> = context.applicationContext.layoutDataStore.data
        // an unreadable store (I/O error) must not keep the app on its blank start frame: fall back to the defaults
        .catch { e -> if (e is java.io.IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
        LayoutSlots.ALL.mapNotNull { slot ->
            p[key(slot)]?.let { s -> runCatching { json.decodeFromString(SlotLayout.serializer(), s) }.getOrNull() }
                ?.let { slot to it.copy(columns = if (it.columns >= 8) 8 else 4, tiles = TileSizes.validate(it.tiles)) }
        }.toMap()
    }

    /** For widgets / renderers: the effective layout of [slot] (stored, else the default). */
    suspend fun read(context: Context, slot: String, ui: UiPrefs): SlotLayout =
        stored(context).first()[slot] ?: LayoutSlots.default(slot, ui)

    suspend fun save(context: Context, slot: String, layout: SlotLayout) {
        val v = layout.copy(columns = if (layout.columns >= 8) 8 else 4, tiles = TileSizes.validate(layout.tiles),
            opacity = layout.opacity.coerceIn(0, 100), rows = layout.rows.coerceIn(0, 8))
        context.applicationContext.layoutDataStore.edit { it[key(slot)] = json.encodeToString(SlotLayout.serializer(), v) }
    }

    /** Change one slot, starting from its effective layout (stored or default); keeps the other fields. */
    suspend fun update(context: Context, slot: String, ui: UiPrefs, transform: (SlotLayout) -> SlotLayout) =
        save(context, slot, transform(read(context, slot, ui)))

    suspend fun reset(context: Context, slot: String) {
        context.applicationContext.layoutDataStore.edit { it.remove(key(slot)) }
    }
}

/** Effective layouts for the UI: stored ones, else defaults from the legacy UiPrefs. */
class Layouts(private val stored: Map<String, SlotLayout>, private val ui: UiPrefs) {
    fun get(slot: String): SlotLayout = stored[slot] ?: LayoutSlots.default(slot, ui)
    fun isDefault(slot: String): Boolean = slot !in stored
}

val LocalLayouts = androidx.compose.runtime.compositionLocalOf { Layouts(emptyMap(), UiPrefs()) }
