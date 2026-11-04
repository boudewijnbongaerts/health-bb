package com.boudhealth.ui.nav

import com.boudhealth.data.TileCatalog
import com.boudhealth.data.TileSize
import com.boudhealth.data.TileSpec

/*
 * Tile catalog the UI and the widgets validate against (data's TileCatalog + the v4/V2 tiles). Pure Kotlin (no Android),
 * so the saved-layout migration is tested on the JVM: scratchpad/t5/test/TileSizesTest.kt (V2-114).
 */

/** Allowed sizes per tile id (data's TileCatalog + the v4 tiles). */
object TileSizes {
    private val SM = listOf(TileSize.S, TileSize.M)
    private val SML = listOf(TileSize.S, TileSize.M, TileSize.L)
    private val SMLXL = TileSize.entries.toList()
    private val extra: Map<String, Pair<List<TileSize>, TileSize>> = mapOf(
        "exercise_min" to (SMLXL to TileSize.M),
        "heart_zones" to (SML to TileSize.M),
        "weekly_cardio" to (SMLXL to TileSize.L),
        "week_workouts" to (SMLXL to TileSize.M),
        "lean_mass" to (SMLXL to TileSize.M),
        "streak" to (SMLXL to TileSize.S),
        // V2-099: Weekly goals at every size (S = goals met + mini rings, TileArt.weeklyGoals)
        "weekly_goals" to (SMLXL to TileSize.L),
        "yesterday_workouts" to (SML to TileSize.M),
        "calories" to (SMLXL to TileSize.M),
    )

    fun sizes(id: String): List<TileSize> = extra[id]?.first ?: TileCatalog.sizes(id)
    fun defaultSize(id: String): TileSize = extra[id]?.second ?: TileCatalog.defaultSize(id)
    /** v4.1 R3: the % max-HR zone tiles are gone (one HRR zone system: the Heart zones tile). */
    private val RETIRED = setOf("hz1", "hz2", "hz3", "hz4", "hz5", "last_workout", "hrv_sdnn")
    fun known(id: String): Boolean = id !in RETIRED && (extra.containsKey(id) || TileCatalog.find(id) != null)

    fun clamp(id: String, size: TileSize): TileSize {
        val allowed = sizes(id)
        if (allowed.isEmpty() || size in allowed) return size
        return allowed.filter { it.ordinal < size.ordinal }.maxByOrNull { it.ordinal } ?: allowed.minBy { it.ordinal }
    }

    /**
     * Retired tile ids that have a successor (saved layouts and placed widgets carry over). Tile ids are storage keys and
     * are never renamed for wording; V2-114: the only V2 change is the Heart points merge weekly_cardio → zone_min.
     */
    val RENAMED: Map<String, String> = mapOf("last_workout" to "yesterday_workouts", "hrv_sdnn" to "hrv",
        "exercise_min" to "week_workouts", "lean_fat" to "lean_mass", "weekly_cardio" to "zone_min")

    /** Old Heart points tile id: kept as its own id when the layout also holds "zone_min" (drawn as Heart points). */
    const val HEART_POINTS_ALIAS = "weekly_cardio"

    /**
     * Swap renamed ids, drop unknown ones, clamp sizes, de-duplicate (first wins).
     * Restore round (V2_REMOVALS_AUDIT 1.1): the Heart points merge never loses a tile. A layout that holds BOTH old
     * tiles keeps both, at their own sizes and places: "zone_min" stays, and "weekly_cardio" stays under its old id,
     * which every renderer draws as the same Heart points tile (render/V4Tiles, Home via TileArt). Ids are storage keys,
     * so the two stay distinct for Arrange, taps and widgets. Without a "zone_min" it is renamed as before.
     */
    fun validate(list: List<TileSpec>): List<TileSpec> {
        val hasHeartPoints = list.any { it.tileId == "zone_min" }
        val seen = HashSet<String>()
        return list.map { t ->
            if (t.tileId == HEART_POINTS_ALIAS && hasHeartPoints) t
            else RENAMED[t.tileId]?.let { n -> t.copy(tileId = n) } ?: t
        }.mapNotNull { t ->
            if (!known(t.tileId) || !seen.add(t.tileId)) null
            else t.copy(size = clamp(t.tileId, t.size), sizeWide = t.sizeWide?.let { clamp(t.tileId, it) })
        }
    }
}

