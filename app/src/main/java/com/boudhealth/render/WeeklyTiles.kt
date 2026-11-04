package com.boudhealth.render

import android.graphics.Paint
import com.boudhealth.data.TileSize
import com.boudhealth.render.ring.RingData
import com.boudhealth.render.ring.TileArt
import com.boudhealth.render.ring.TileSz

/**
 * Weekly goals (D20 name, V2-099), drawn by the one painter [TileArt.weeklyGoals] from the V2 week rings
 * (V2.rings(Week), by ring id; pace = data/Pace, IC9): S rings · M/L rings next to the kept lines · XL numbers integrated
 * in the rings. No dependency on ui/home (WeeklyPace / WeekGoal are gone from this file).
 * Used by the `weekly_goals` tile and by the legacy "Weekly goals" widget (Renderer.renderWeekly, migrated by T6).
 */
internal class WeeklyRenderer(private val k: Ink, private val s: TileSource) {
    private val t = k.t
    private val m by lazy { V2Models(s) }

    enum class Size { S2x2, S4x2, S4x4 }

    /** One tap region of the widget (y range in dp) → a metric id, or a route ("route:<link>"). */
    data class Hit(val top: Float, val bottom: Float, val target: String)

    companion object {
        /** Size class from the placed size (WEEKLY_SPEC §1): w ≥ 340 → 4 wide; h ≥ 340 → 4 tall; else the largest that fits. */
        fun sizeFor(w: Float, h: Float): Size = when {
            w >= 340f && h >= 340f -> Size.S4x4
            w >= 340f -> Size.S4x2
            else -> Size.S2x2
        }
        /** Tile id sent as hb_metric (the UI router resolves tile ids, Route.forTile). */
        const val WEEK_ROUTE = "weekly_goals"
    }

    /** As a tile: every size from the same painter. */
    fun tile(size: TileSize, w: Float, h: Float) {
        val d = m.weeklyGoals(k) ?: return empty(w, h)
        TileArt.weeklyGoals(k, when (size) { TileSize.S -> TileSz.S; TileSize.M -> TileSz.M; TileSize.L -> TileSz.L; TileSize.XL -> TileSz.XL }, w, h, d)
    }

    /** Draws [size] into a w × h card (card already filled). Returns the tap regions (top to bottom). */
    fun draw(size: Size, w: Float, h: Float): List<Hit> {
        val d = m.weeklyGoals(k)
        if (d == null) { empty(w, h); return listOf(Hit(0f, h, WEEK_ROUTE)) }
        return when (size) {
            Size.S2x2 -> { TileArt.weeklyGoals(k, TileSz.L, w, h, d); listOf(Hit(0f, h, WEEK_ROUTE)) }
            else -> {
                TileArt.weeklyGoals(k, TileSz.XL, w, h, d)
                // XL lines: top = 18 + 52 + i × 38 (TileArt.goalLines); the header and the rings open the Weekly goals view
                val top0 = 18f + 52f - 8f
                listOf(Hit(0f, top0, WEEK_ROUTE)) + d.goals.indices.map { i ->
                    Hit(top0 + i * 38f, if (i == d.goals.size - 1) h else top0 + (i + 1) * 38f, RingData.ORDER.getOrElse(i) { WEEK_ROUTE })
                }
            }
        }
    }

    private fun empty(w: Float, h: Float) {
        k.text("Weekly goals", 14f, 26f, 13f, t.text, 700)
        k.text("No data", w / 2f, h / 2f + 4f, 12.5f, t.muted, 500, Paint.Align.CENTER)
    }
}
