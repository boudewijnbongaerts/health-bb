package com.boudhealth.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

/*
 * UI preferences: theme, ring palette, Today pill, tile layouts per surface (A_SPEC §9),
 * plus a combined read-only view [Prefs] that also carries the goals (incl. birth year) and the manual height.
 *
 * Persistence: one JSON string in the existing "healthboud" DataStore (Store.ui / Store.updateUi).
 * Goals stay in their own key (the backend reads them); height stays in Store's manual height samples.
 */

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Where a tile layout lives. (Not called "Surface" to avoid clashing with Compose's Surface.) */
@Serializable
enum class TileSurface(val label: String) { APP("App home"), WIDGET_INNER("Inner widget"), WIDGET_COVER("Cover widget") }

/** Tile span in grid cells: S 1×1 · M 2×1 · L 2×2 · XL 4×2. */
@Serializable
enum class TileSize(val cols: Int, val rows: Int, val label: String) {
    S(1, 1, "1×1"), M(2, 1, "2×1"), L(2, 2, "2×2"), XL(4, 2, "4×2");
}

/**
 * One tile on a surface. Order in the list = packing order (dense first-fit), so no coordinates are stored.
 * [tileId] is a metric id from the registry ([Metrics]): "today_rings", "calories", "zone_min", "steps", "last_workout",
 * "week_workouts", "streak", "weight", "body_fat", "lean_fat", "waist", "bmi", "rhr", "hrv", "hr", "vo2", "breathing",
 * "spo2", "sleep". [sizeWide] optionally overrides the size on 8-column surfaces (inner screen, A_SPEC §9).
 */
@Serializable
data class TileSpec(val tileId: String, val size: TileSize, val sizeWide: TileSize? = null) {
    fun sizeFor(wide: Boolean): TileSize = if (wide) sizeWide ?: size else size
}

/** Which sizes each tile supports, and the size used when it is added without choosing (A_SPEC §3). */
object TileCatalog {
    data class Entry(val id: String, val sizes: List<TileSize>, val default: TileSize)

    private val SMLXL = listOf(TileSize.S, TileSize.M, TileSize.L, TileSize.XL)
    private val SML = listOf(TileSize.S, TileSize.M, TileSize.L)

    val entries: List<Entry> = listOf(
        Entry("today_rings", SMLXL, TileSize.L),
        Entry("calories", SMLXL, TileSize.M),
        Entry("zone_min", SMLXL, TileSize.M),
        Entry("steps", SMLXL, TileSize.M),
        Entry("last_workout", SMLXL, TileSize.M),
        Entry("week_workouts", SMLXL, TileSize.M),
        Entry("streak", SML, TileSize.S),
        Entry("weight", SMLXL, TileSize.M),
        Entry("body_fat", SML, TileSize.S),
        Entry("lean_fat", listOf(TileSize.M, TileSize.L, TileSize.XL), TileSize.L),
        Entry("waist", SML, TileSize.S),
        Entry("bmi", listOf(TileSize.S, TileSize.M), TileSize.S),
        Entry("rhr", SMLXL, TileSize.M),
        Entry("hrv", SML, TileSize.S),
        Entry("hr", SML, TileSize.S),
        Entry("vo2", SML, TileSize.S),
        Entry("breathing", SML, TileSize.S),
        Entry("spo2", SML, TileSize.S),
        Entry("sleep", SMLXL, TileSize.L),
    )
    private val byId = entries.associateBy { it.id }

    fun find(id: String): Entry? = byId[id]
    fun sizes(id: String): List<TileSize> = byId[id]?.sizes ?: emptyList()
    fun defaultSize(id: String): TileSize = byId[id]?.default ?: TileSize.M

    /** The requested size if allowed, else the nearest smaller allowed size, else the smallest allowed one. */
    fun clamp(id: String, size: TileSize): TileSize {
        val allowed = sizes(id)
        if (allowed.isEmpty() || size in allowed) return size
        return allowed.filter { it.ordinal < size.ordinal }.maxByOrNull { it.ordinal } ?: allowed.minBy { it.ordinal }
    }

    /** Default layouts (A_SPEC §4 and §8). App: 412 list; the inner-screen sizes ride along as [TileSpec.sizeWide]. */
    fun defaultLayout(surface: TileSurface): List<TileSpec> = when (surface) {
        TileSurface.APP -> listOf(
            TileSpec("sleep", TileSize.L),
            TileSpec("weight", TileSize.M),
            TileSpec("rhr", TileSize.M),
            TileSpec("last_workout", TileSize.M, sizeWide = TileSize.XL),
            TileSpec("week_workouts", TileSize.M, sizeWide = TileSize.L),
            TileSpec("hrv", TileSize.S, sizeWide = TileSize.M),
            TileSpec("vo2", TileSize.S, sizeWide = TileSize.M),
            TileSpec("body_fat", TileSize.S, sizeWide = TileSize.M),
            TileSpec("waist", TileSize.S, sizeWide = TileSize.M),
        )
        TileSurface.WIDGET_INNER -> listOf(
            TileSpec("today_rings", TileSize.XL),
            TileSpec("sleep", TileSize.L),
            TileSpec("weight", TileSize.M),
            TileSpec("rhr", TileSize.M),
            TileSpec("week_workouts", TileSize.XL),
            TileSpec("last_workout", TileSize.L),
            TileSpec("hrv", TileSize.S),
            TileSpec("vo2", TileSize.S),
            TileSpec("body_fat", TileSize.S),
            TileSpec("waist", TileSize.S),
        )
        TileSurface.WIDGET_COVER -> listOf(
            TileSpec("today_rings", TileSize.L),
            TileSpec("last_workout", TileSize.M),
            TileSpec("weight", TileSize.M),
            TileSpec("sleep", TileSize.M),
            TileSpec("rhr", TileSize.M),
            TileSpec("week_workouts", TileSize.XL),
        )
    }

    /** Validation on load (A_SPEC §9): drop unknown ids, clamp sizes, de-duplicate (first wins). */
    fun validate(list: List<TileSpec>): List<TileSpec> {
        val seen = HashSet<String>()
        return list.mapNotNull { t ->
            if (find(t.tileId) == null || !seen.add(t.tileId)) null
            else t.copy(size = clamp(t.tileId, t.size), sizeWide = t.sizeWide?.let { clamp(t.tileId, it) })
        }
    }
}

/** Dense first-fit packing (CSS grid-auto-flow: row dense). Pure Kotlin: used by the app grid and the widget renderer. */
object TilePacker {
    /** A packed tile: [col]/[row] are the top-left cell, [size] the effective size on this surface. */
    data class Placed(val index: Int, val spec: TileSpec, val size: TileSize, val col: Int, val row: Int) {
        val cols: Int get() = size.cols
        val rows: Int get() = size.rows
    }

    data class Result(val placed: List<Placed>, val rows: Int, val hidden: Int)

    /**
     * @param columns 4 (phone/cover, widget cover) or 8 (inner app, inner widget)
     * @param maxRows null = unlimited (scrolling app home); a number for widgets (tiles that don't fit are hidden, never squeezed)
     * Tiles wider than [columns] are clamped to [columns] (XL on 4 columns = full width).
     */
    fun pack(specs: List<TileSpec>, columns: Int, maxRows: Int? = null): Result {
        val wide = columns >= 8
        val occupied = ArrayList<BooleanArray>()
        fun row(r: Int): BooleanArray { while (occupied.size <= r) occupied.add(BooleanArray(columns)); return occupied[r] }
        fun fits(c: Int, r: Int, w: Int, h: Int): Boolean {
            if (c + w > columns) return false
            for (y in r until r + h) for (x in c until c + w) if (row(y)[x]) return false
            return true
        }
        val out = ArrayList<Placed>()
        var hidden = 0
        specs.forEachIndexed { i, spec ->
            val size = spec.sizeFor(wide)
            val w = size.cols.coerceAtMost(columns)
            val h = size.rows
            var placed = false
            var r = 0
            while (!placed) {
                if (maxRows != null && r + h > maxRows) break
                for (c in 0..(columns - w)) {
                    if (fits(c, r, w, h)) {
                        for (y in r until r + h) for (x in c until c + w) row(y)[x] = true
                        out.add(Placed(i, spec, size, c, r))
                        placed = true
                        break
                    }
                }
                r++
                if (maxRows == null && r > 400) break // safety
            }
            if (!placed) hidden++
        }
        val rows = out.maxOfOrNull { it.row + it.size.rows } ?: 0
        return Result(out, rows, hidden)
    }
}

/** Persisted UI preferences. All fields have defaults, so old/missing JSON decodes fine. */
@Serializable
data class UiPrefs(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val paletteId: String = DEFAULT_PALETTE_ID,
    val showTodayPill: Boolean = true,
    /** Missing surface = default layout for that surface. An explicitly empty list stays empty. */
    val layouts: Map<TileSurface, List<TileSpec>> = emptyMap(),
    /** First-run hint under the home grid ("Long-press any tile…"); set true after the first edit. */
    val homeHintSeen: Boolean = false,
    val version: Int = 1,
) {
    fun layout(surface: TileSurface): List<TileSpec> = layouts[surface] ?: TileCatalog.defaultLayout(surface)

    fun withLayout(surface: TileSurface, tiles: List<TileSpec>): UiPrefs = copy(layouts = layouts + (surface to TileCatalog.validate(tiles)))

    fun resetLayout(surface: TileSurface): UiPrefs = copy(layouts = layouts - surface)

    fun normalized(): UiPrefs = copy(layouts = layouts.mapValues { TileCatalog.validate(it.value) })

    companion object {
        const val DEFAULT_PALETTE_ID = "copper_pine_marigold"
    }
}

/** Neutral height used to prefill the height stepper when nothing is stored (only saved when the user confirms/edits). */
const val DEFAULT_HEIGHT_CM = 175

/**
 * Everything the UI reads, in one immutable value. Built by [Store.prefs].
 * Goals (incl. birth year) come from [Store.goals]; [heightCm] is the latest MANUAL height (Health Connect height wins for
 * calculations; read it via Metrics.latest(ctx, "height") when you need the effective value).
 */
data class Prefs(
    val ui: UiPrefs = UiPrefs(),
    val goals: Goals = Goals(),
    val heightCm: Double? = null,
) {
    val themeMode: ThemeMode get() = ui.themeMode
    val paletteId: String get() = ui.paletteId
    val showTodayPill: Boolean get() = ui.showTodayPill
    val birthYear: Int? get() = goals.birthYear
    /** Manual height or the neutral default for prefilling steppers. */
    val heightCmOrDefault: Int get() = heightCm?.let { Math.round(it).toInt() } ?: DEFAULT_HEIGHT_CM
    fun layout(surface: TileSurface): List<TileSpec> = ui.layout(surface)
}

// ------------------------------------------------------------------ Store extensions

/** Combined, always-valid preferences flow. */
val Store.prefs: Flow<Prefs>
    get() = combine(ui, goals, height) { u, g, h -> Prefs(u, g, h.lastOrNull()?.value?.let { it * 100.0 }) }

suspend fun Store.prefsNow(): Prefs = prefs.first()

suspend fun Store.setThemeMode(mode: ThemeMode) = updateUi { it.copy(themeMode = mode) }
suspend fun Store.setPalette(id: String) = updateUi { it.copy(paletteId = id) }
suspend fun Store.setShowTodayPill(show: Boolean) = updateUi { it.copy(showTodayPill = show) }
suspend fun Store.setHomeHintSeen() = updateUi { it.copy(homeHintSeen = true) }
suspend fun Store.setLayout(surface: TileSurface, tiles: List<TileSpec>) = updateUi { it.withLayout(surface, tiles) }
suspend fun Store.resetLayout(surface: TileSurface) = updateUi { it.resetLayout(surface) }
suspend fun Store.setBirthYear(year: Int?) = updateGoals { it.copy(birthYear = year) }
