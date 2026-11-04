package com.boudhealth.ui.home

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.boudhealth.data.TileSize

/*
 * Tile grid geometry (A_SPEC §1). V2 (D11 GO): the tile content is the widget tile art (Tiles.kt `Tile`), so the v6
 * Compose tile primitives that used to live here are gone.
 */

internal val TileGap: Dp = 8.dp

/** Tile radius: S 22 · M 24 · L/XL 28 (inside widget frames 18 / 20). */
internal fun tileShape(size: TileSize, widget: Boolean = false): Shape = RoundedCornerShape(
    when {
        widget && size == TileSize.S -> 18.dp
        widget -> 20.dp
        size == TileSize.S -> 22.dp
        size == TileSize.M -> 24.dp
        else -> 28.dp
    },
)

/** Pixel size of a tile on a grid of [columns] with the given cell size and gap. */
internal fun tileDims(size: TileSize, cellW: Dp, cellH: Dp, columns: Int, gap: Dp = TileGap): DpSize {
    val c = size.cols.coerceAtMost(columns)
    return DpSize(cellW * c + gap * (c - 1), cellH * size.rows + gap * (size.rows - 1))
}
