package com.boudhealth.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.boudhealth.ui.theme.LocalHB

/*
 * Compose side of the glyphs. The data (enum Glyph, path strings) lives Compose-free in GlyphData.kt (V2-029);
 * widgets / render/ parse the same strings with render/ring/SvgPath into android.graphics.Path.
 */

private val composePaths = HashMap<Glyph, List<Pair<Path, Boolean>>>()

/** Compose paths of a glyph (parsed once). */
internal val Glyph.paths: List<Pair<Path, Boolean>>
    get() = synchronized(composePaths) {
        composePaths.getOrPut(this) { parts.map { PathParser().parsePathString(it.d).toPath() to it.fill } }
    }

/** Default stroke (grid units) for a rendered size: 2.6 at ≤ 14 dp, else 2. Use 2.2 inside glyph circles. */
fun glyphStroke(size: Dp): Float = if (size <= 14.dp) 2.6f else 2f

/**
 * Draws a glyph. [color] defaults to the text role. [stroke] is in 24-grid units (scaled with [size]).
 */
@Composable
fun GlyphIcon(
    glyph: Glyph,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    color: Color = LocalHB.current.colors.text,
    stroke: Float = glyphStroke(size),
) {
    Canvas(modifier.size(size)) { drawGlyph(glyph, color, stroke) }
}

/** Draw a glyph into the current DrawScope, scaled to fill its size (square). */
fun DrawScope.drawGlyph(glyph: Glyph, color: Color, stroke: Float = 2f) {
    val k = size.minDimension / 24f
    scale(k, k, pivot = androidx.compose.ui.geometry.Offset.Zero) {
        val st = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        glyph.paths.forEach { (p, fill) -> drawPath(p, color, style = if (fill) Fill else st) }
    }
}
