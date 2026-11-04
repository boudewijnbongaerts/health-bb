package com.boudhealth.widget

import android.content.Context
import com.boudhealth.data.ErrorLog
import com.boudhealth.data.TileSize
import com.boudhealth.data.TileSpec
import com.boudhealth.data.UiPrefs
import com.boudhealth.ui.nav.LayoutStore
import com.boudhealth.ui.nav.TileSizes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** V2 (D12): the two widget families in the picker. Every placed widget, old or new, is one of them. */
@Serializable
enum class WidgetFamily { TILES, CLOCK }

/**
 * V2 (IC8): configuration of ONE placed widget, stored per appWidgetId in SharedPreferences "hb_widgets", key "w<id>",
 * JSON with "v": 2. "Health BB" ([WidgetFamily.TILES]) uses [tiles], [bgOpacity], [tileOpacity]; "Health BB · Clock"
 * ([WidgetFamily.CLOCK]) uses [font], [clockSize], [ringShare], [showDate], [showRings], [bgOpacity].
 */
@Serializable
data class WidgetConfigV2(
    val v: Int = 2,
    val family: WidgetFamily = WidgetFamily.TILES,
    val tiles: List<TileSpec> = DEFAULT_TILES,
    /** Widget background 0 (clear) … 100 (solid). */
    val bgOpacity: Int = 100,
    /** Tile cards 0 … 100; text and ring numbers always keep full strength. */
    val tileOpacity: Int = 100,
    val font: ClockFont = ClockFont.LIGHT,
    /**
     * 50–100 % of the widget for the rings + clock group. Default 100 with rings 50 : 50: on a 4 × 2 widget the rings are
     * then ≥ 146 dp, the smallest size whose strokes carry T4's integrated numbers (smaller rings are drawn without them).
     */
    val clockSize: Int = 100,
    /** 30–70: the rings' share of the group ("Rings and clock" 50 : 50). */
    val ringShare: Int = 50,
    val showDate: Boolean = true,
    val showRings: Boolean = true,
) {
    val clockSpec: ClockSpec get() = ClockSpec(font, clockSize, ringShare, showDate, showRings)

    /** Clamped, tiles validated and never empty (a widget must never go blank). */
    fun sanitized(validate: (List<TileSpec>) -> List<TileSpec> = { it }): WidgetConfigV2 {
        val t = runCatching { validate(tiles) }.getOrDefault(tiles)
        return copy(
            v = 2,
            tiles = t.ifEmpty { DEFAULT_TILES },
            bgOpacity = bgOpacity.coerceIn(0, 100), tileOpacity = tileOpacity.coerceIn(0, 100),
            clockSize = clockSize.coerceIn(50, 100), ringShare = ringShare.coerceIn(30, 70),
        )
    }

    companion object {
        val DEFAULT_TILES = listOf(TileSpec("today_rings", TileSize.L), TileSpec("steps", TileSize.M), TileSpec("zone_min", TileSize.M))
        fun defaults(family: WidgetFamily): WidgetConfigV2 =
            if (family == WidgetFamily.CLOCK) WidgetConfigV2(family = WidgetFamily.CLOCK, bgOpacity = 0) else WidgetConfigV2()
    }
}

/** v6 M3 clock styles: decode-only (migration of placed v6 "Rings + clock" widgets). */
@Serializable
enum class ClockStyle { BOLD, THIN, STACKED, ANALOG, INSIDE, DATE_FIRST }

/** v6 per-widget config: decode-only, migrated to [WidgetConfigV2] by [WidgetMigration]. */
@Serializable
data class WidgetConfig(
    val tiles: List<TileSpec> = emptyList(),
    val opacity: Int = 100,
    val style: ClockStyle = ClockStyle.BOLD,
    val scale: Int = 100,
    val showDate: Boolean = true,
)

/**
 * V2-031 / V2-124: old widget → V2 config, by the KIND of the placed widget (its receiver), pure and idempotent
 * (V2 JSON in → the same config out). Map (V2_PLAN 2.8):
 *  W1–W3 → Health BB with that widget's own v6 tiles + opacity, else its slot layout (LayoutStore), else defaults;
 *  RINGS → Clock (v6 style → nearest font, size, date kept); WEEKLY → weekly_goals L; CARDIO → zone_min L;
 *  RECENT → yesterday_workouts L; WORKOUTS → week_workouts M. The four single-card kinds keep their look: background
 *  clear, the tile solid. Never returns an empty tile list.
 */
object WidgetMigration {
    fun single(id: String, size: TileSize) = WidgetConfigV2(tiles = listOf(TileSpec(id, size)), bgOpacity = 0, tileOpacity = 100)

    fun fontOf(style: ClockStyle): ClockFont = when (style) {
        ClockStyle.BOLD, ClockStyle.STACKED -> ClockFont.BOLD
        ClockStyle.DATE_FIRST -> ClockFont.SERIF
        ClockStyle.THIN, ClockStyle.ANALOG, ClockStyle.INSIDE -> ClockFont.LIGHT
    }

    /**
     * [v2] = a stored V2 config (wins, unchanged apart from clamping); [legacy] = a stored v6 config; [slot] = the old
     * slot layout of W1–W3 (tiles, opacity), when there is one.
     */
    fun migrate(kind: WidgetKind, v2: WidgetConfigV2?, legacy: WidgetConfig?, slot: Pair<List<TileSpec>, Int>?): WidgetConfigV2 {
        if (v2 != null) return v2.sanitized()
        val cfg = when (kind) {
            WidgetKind.W1, WidgetKind.W2, WidgetKind.W3 -> {
                val own = legacy?.takeIf { it.tiles.isNotEmpty() }?.let { it.tiles to it.opacity }
                val (tiles, opacity) = own ?: slot?.takeIf { it.first.isNotEmpty() } ?: (WidgetConfigV2.DEFAULT_TILES to 100)
                WidgetConfigV2(tiles = tiles, bgOpacity = opacity, tileOpacity = 100)
            }
            WidgetKind.RINGS -> {
                val l = legacy ?: WidgetConfig()
                WidgetConfigV2.defaults(WidgetFamily.CLOCK).copy(
                    font = fontOf(l.style), clockSize = (50 + (l.scale.coerceIn(60, 100) - 60) * 50 / 40), showDate = l.showDate,
                )
            }
            WidgetKind.WEEKLY -> single("weekly_goals", TileSize.L)
            WidgetKind.CARDIO -> single("zone_min", TileSize.L)
            WidgetKind.RECENT -> single("yesterday_workouts", TileSize.L)
            WidgetKind.WORKOUTS -> single("week_workouts", TileSize.M)
        }
        return cfg.sanitized()
    }
}

object WidgetConfigs {
    private const val FILE = "hb_widgets"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun key(appWidgetId: Int) = "w$appWidgetId"

    /** The stored JSON decoded as (V2, legacy v6); both null when nothing (readable) is stored. */
    private fun read(context: Context, appWidgetId: Int): Pair<WidgetConfigV2?, WidgetConfig?> {
        val s = prefs(context).getString(key(appWidgetId), null) ?: return null to null
        return runCatching {
            val version = runCatching { json.parseToJsonElement(s).jsonObject["v"]?.jsonPrimitive?.int }.getOrNull()
            if (version == 2) json.decodeFromString(WidgetConfigV2.serializer(), s) to null
            else null to json.decodeFromString(WidgetConfig.serializer(), s)
        }.getOrElse { e -> ErrorLog.record("widget/config/decode", e); null to null }
    }

    /** The stored V2 config, null when there is none yet (a new placement) or only an old one. */
    fun get(context: Context, appWidgetId: Int): WidgetConfigV2? = read(context, appWidgetId).first?.sanitized(TileSizes::validate)

    fun put(context: Context, appWidgetId: Int, config: WidgetConfigV2) {
        prefs(context).edit().putString(key(appWidgetId), json.encodeToString(WidgetConfigV2.serializer(), config.sanitized(TileSizes::validate))).apply()
    }

    fun delete(context: Context, appWidgetIds: IntArray) {
        val e = prefs(context).edit()
        appWidgetIds.forEach { e.remove(key(it)) }
        e.apply()
    }

    /**
     * V2-031 (IC8): the V2 config of a placed widget. Stored V2 → returned; stored v6 or nothing → migrated by [kind]
     * ([WidgetMigration]) and written back as V2, so the next call returns the same config (idempotent). Any failure is
     * logged to ErrorLog and falls back to the kind's defaults; the result never has empty tiles.
     */
    suspend fun getOrMigrate(context: Context, appWidgetId: Int, kind: WidgetKind, ui: UiPrefs): WidgetConfigV2 = try {
        val (v2, legacy) = read(context, appWidgetId)
        // the old slot layout is read only for a W1–W3 widget that has no tiles of its own yet
        val slot = kind.slot?.takeIf { v2 == null && legacy?.tiles.isNullOrEmpty() }
            ?.let { s -> runCatching { LayoutStore.read(context, s, ui) }.getOrNull()?.let { it.tiles to it.opacity } }
        val cfg = WidgetMigration.migrate(kind, v2, legacy, slot).sanitized(TileSizes::validate)
        if (v2 == null || cfg != v2) put(context, appWidgetId, cfg)
        cfg
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        ErrorLog.record("widget/config/migrate", e)
        WidgetConfigV2.defaults(kind.family).sanitized(TileSizes::validate)
    }

    /**
     * The config the setup screen starts from: stored V2, else a migrated old widget, else (a new placement) the
     * family's defaults. Saved at once, so Back leaves the widget with a full config.
     */
    fun forSetup(context: Context, appWidgetId: Int, kind: WidgetKind, ui: UiPrefs): WidgetConfigV2 {
        val (v2, legacy) = read(context, appWidgetId)
        val cfg = when {
            v2 != null -> v2.sanitized(TileSizes::validate)
            legacy != null -> WidgetMigration.migrate(kind, null, legacy, null).sanitized(TileSizes::validate)
            else -> WidgetConfigV2.defaults(kind.family).sanitized(TileSizes::validate)
        }
        put(context, appWidgetId, cfg)
        return cfg
    }
}
