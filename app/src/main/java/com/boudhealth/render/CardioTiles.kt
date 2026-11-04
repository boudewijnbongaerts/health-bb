package com.boudhealth.render

import android.graphics.Paint
import com.boudhealth.data.TileSize
import com.boudhealth.render.ring.TileArt
import com.boudhealth.render.ring.TileSz
import com.boudhealth.ui.components.Glyph

/**
 * V2-098: the old "Weekly heart points" tile / widget is MERGED into the Heart points family: every size is drawn by
 * [TileArt.heartPoints] (own icon, "29 / 50" today, the week look on M, stacked zone bars on a plain track — the grey
 * "last week" outlines are gone, V2-104). Kept as a class because Renderer (T6) still calls the legacy widget entry.
 */
internal class CardioRenderer(private val k: Ink, private val s: TileSource) {
    private val t = k.t
    private val m by lazy { V2Models(s) }

    enum class Size { S2x2, S4x2, S4x4 }

    companion object {
        fun sizeFor(w: Float, h: Float): Size = when {
            w >= 340f && h >= 340f -> Size.S4x4
            w >= 340f -> Size.S4x2
            else -> Size.S2x2
        }
        /** Tile id sent as hb_metric: the Heart points card (weekly_cardio is renamed to zone_min, V2-114). */
        const val TILE_ID = "zone_min"
    }

    fun tile(size: TileSize, w: Float, h: Float) {
        val d = m.heartPoints ?: return empty(w, h)
        TileArt.heartPoints(k, when (size) { TileSize.S -> TileSz.S; TileSize.M -> TileSz.M; TileSize.L -> TileSz.L; TileSize.XL -> TileSz.XL }, w, h, d)
    }

    /** The legacy widget card (card already filled): 2×2 → L, wider → XL. One tap target (→ Heart points). */
    fun widget(w: Float, h: Float) {
        val d = m.heartPoints ?: return empty(w, h)
        TileArt.heartPoints(k, if (sizeFor(w, h) == Size.S2x2) TileSz.L else TileSz.XL, w, h, d)
    }

    private fun empty(w: Float, h: Float) {
        val p = 14f
        k.glyphCircle(Glyph.HeartPlus, k.ringColors().fill(2), p, p, 22f, 13f)
        k.text("Heart points", p + 28f, p + 15f, 13f, t.text, 600)
        k.text("No data", w / 2f, h / 2f + 4f, 12.5f, t.muted, 500, Paint.Align.CENTER)
    }
}
