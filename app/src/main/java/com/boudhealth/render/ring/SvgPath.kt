package com.boudhealth.render.ring

import android.graphics.Path
import com.boudhealth.ui.components.Glyph
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Compose-free SVG path-data parser (V2-029) → android.graphics.Path, for the glyphs in widgets and render/.
 * Supports M L H V C S Q T A Z (absolute and relative). Arcs are converted to cubic Béziers (≤ 90° each).
 */
object SvgPath {
    private val cache = HashMap<String, Path>()
    private val glyphCache = HashMap<Glyph, List<Pair<Path, Boolean>>>()

    /** Cached path for [d]. Do not mutate the result. */
    fun of(d: String): Path = synchronized(cache) { cache.getOrPut(d) { parse(d) } }

    /** Android paths of a glyph (same data as the app's GlyphIcon): (path, isFilledDot). */
    fun glyph(g: Glyph): List<Pair<Path, Boolean>> = synchronized(glyphCache) {
        glyphCache.getOrPut(g) { g.parts.map { parse(it.d) to it.fill } }
    }

    fun parse(d: String): Path {
        val p = Path()
        val t = Tokens(d)
        var cx = 0f; var cy = 0f          // current point
        var sx = 0f; var sy = 0f          // subpath start
        var lcx = 0f; var lcy = 0f        // last control point (for S / T)
        var prev = ' '
        var cmd = ' '
        while (true) {
            t.skipSep()
            if (t.done()) break
            if (t.isCmd()) cmd = t.cmd() else if (cmd == ' ') break
            val rel = cmd.isLowerCase()
            val ox = if (rel) cx else 0f; val oy = if (rel) cy else 0f
            when (cmd.uppercaseChar()) {
                'M' -> {
                    cx = ox + t.num(); cy = oy + t.num(); p.moveTo(cx, cy); sx = cx; sy = cy
                    cmd = if (rel) 'l' else 'L'   // implicit lineto after the first pair
                }
                'L' -> { cx = ox + t.num(); cy = oy + t.num(); p.lineTo(cx, cy) }
                'H' -> { cx = (if (rel) cx else 0f) + t.num(); p.lineTo(cx, cy) }
                'V' -> { cy = (if (rel) cy else 0f) + t.num(); p.lineTo(cx, cy) }
                'C' -> {
                    val x1 = ox + t.num(); val y1 = oy + t.num(); val x2 = ox + t.num(); val y2 = oy + t.num()
                    cx = ox + t.num(); cy = oy + t.num()
                    p.cubicTo(x1, y1, x2, y2, cx, cy); lcx = x2; lcy = y2
                }
                'S' -> {
                    val (x1, y1) = if (prev.uppercaseChar() == 'C' || prev.uppercaseChar() == 'S') Pair(2 * cx - lcx, 2 * cy - lcy) else Pair(cx, cy)
                    val x2 = ox + t.num(); val y2 = oy + t.num()
                    cx = ox + t.num(); cy = oy + t.num()
                    p.cubicTo(x1, y1, x2, y2, cx, cy); lcx = x2; lcy = y2
                }
                'Q' -> {
                    val x1 = ox + t.num(); val y1 = oy + t.num()
                    cx = ox + t.num(); cy = oy + t.num()
                    p.quadTo(x1, y1, cx, cy); lcx = x1; lcy = y1
                }
                'T' -> {
                    val (x1, y1) = if (prev.uppercaseChar() == 'Q' || prev.uppercaseChar() == 'T') Pair(2 * cx - lcx, 2 * cy - lcy) else Pair(cx, cy)
                    cx = ox + t.num(); cy = oy + t.num()
                    p.quadTo(x1, y1, cx, cy); lcx = x1; lcy = y1
                }
                'A' -> {
                    val rx = t.num(); val ry = t.num(); val rot = t.num()
                    val large = t.flag(); val sweep = t.flag()
                    val x = ox + t.num(); val y = oy + t.num()
                    arc(p, cx, cy, x, y, rx, ry, rot, large, sweep)
                    cx = x; cy = y
                }
                'Z' -> { p.close(); cx = sx; cy = sy }
                else -> break
            }
            prev = cmd
            if (cmd.uppercaseChar() == 'Z') { t.skipSep(); if (!t.done() && !t.isCmd()) break }
        }
        return p
    }

    /** SVG endpoint arc → centre parameterisation → cubic segments (W3C SVG implementation notes F.6.5). */
    private fun arc(p: Path, x1: Float, y1: Float, x2: Float, y2: Float, rx0: Float, ry0: Float, rotDeg: Float, large: Boolean, sweep: Boolean) {
        if (x1 == x2 && y1 == y2) return
        var rx = abs(rx0.toDouble()); var ry = abs(ry0.toDouble())
        if (rx == 0.0 || ry == 0.0) { p.lineTo(x2, y2); return }
        val phi = Math.toRadians(rotDeg.toDouble()); val cp = cos(phi); val sp = sin(phi)
        val dx = (x1 - x2) / 2.0; val dy = (y1 - y2) / 2.0
        val x1p = cp * dx + sp * dy; val y1p = -sp * dx + cp * dy
        val lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lam > 1) { val s = sqrt(lam); rx *= s; ry *= s }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coef = if (den == 0.0) 0.0 else sqrt(maxOf(0.0, num / den))
        if (large == sweep) coef = -coef
        val cxp = coef * rx * y1p / ry; val cyp = -coef * ry * x1p / rx
        val cx = cp * cxp - sp * cyp + (x1 + x2) / 2.0
        val cy = sp * cxp + cp * cyp + (y1 + y2) / 2.0
        fun ang(ux: Double, uy: Double, vx: Double, vy: Double): Double = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        val th1 = ang(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var dth = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && dth > 0) dth -= 2 * PI else if (sweep && dth < 0) dth += 2 * PI
        val n = ceil(abs(dth) / (PI / 2)).toInt().coerceAtLeast(1)
        val step = dth / n
        val k = 4.0 / 3.0 * tan(step / 4)
        var th = th1
        for (i in 0 until n) {
            val c1 = cos(th); val s1 = sin(th); val c2 = cos(th + step); val s2 = sin(th + step)
            // unit-circle control points, then scale/rotate/translate
            fun mapX(ux: Double, uy: Double) = (cx + rx * ux * cp - ry * uy * sp).toFloat()
            fun mapY(ux: Double, uy: Double) = (cy + rx * ux * sp + ry * uy * cp).toFloat()
            val ax = c1 - k * s1; val ay = s1 + k * c1
            val bx = c2 + k * s2; val by = s2 - k * c2
            p.cubicTo(mapX(ax, ay), mapY(ax, ay), mapX(bx, by), mapY(bx, by), mapX(c2, s2), mapY(c2, s2))
            th += step
        }
    }

    private class Tokens(val s: String) {
        var i = 0
        fun done() = i >= s.length
        fun skipSep() { while (i < s.length && (s[i] == ' ' || s[i] == ',' || s[i] == '\n' || s[i] == '\t')) i++ }
        fun isCmd(): Boolean = i < s.length && s[i].isLetter() && s[i] != 'e' && s[i] != 'E'
        fun cmd(): Char = s[i++]
        fun flag(): Boolean { skipSep(); val c = s[i++]; return c == '1' }
        fun num(): Float {
            skipSep()
            val st = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            var dot = false
            while (i < s.length) {
                val c = s[i]
                if (c.isDigit()) i++
                else if (c == '.' && !dot) { dot = true; i++ }
                else if ((c == 'e' || c == 'E') && i + 1 < s.length) { i++; if (s[i] == '-' || s[i] == '+') i++ }
                else break
            }
            return s.substring(st, i).toFloat()
        }
    }
}
