package com.boudhealth.render

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.boudhealth.render.ring.RingColors
import com.boudhealth.render.ring.RingFonts
import com.boudhealth.render.ring.RingLabel
import com.boudhealth.render.ring.RingLabelMode
import com.boudhealth.render.ring.RingLabelPlan
import com.boudhealth.render.ring.RingPaint
import com.boudhealth.render.ring.RingSurface
import com.boudhealth.render.ring.SvgPath
import com.boudhealth.ui.components.Glyph
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Canvas drawing kit for the widget tiles. All coordinates are dp: the caller pre-scales the canvas by the density.
 * Mirrors the primitives of design2/screens/A_tiles.js (value + unit, label row, pills, sparkline, bars, rings, gauge, zone bar).
 * V2: Compose-free (glyphs via render/ring/SvgPath), text in the bundled Inter (RingFonts), rings via render/ring/RingPaint.
 */
class Ink(val c: Canvas, val t: RenderTheme) {
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { fontFeatureSettings = "tnum" }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    // ------------------------------------------------------------------ text

    private fun face(weight: Int): Typeface = RingFonts.face(weight)

    private fun setText(size: Float, weight: Int, spacing: Float = 0f) {
        tp.textSize = size; tp.typeface = face(weight); tp.letterSpacing = spacing
    }

    /** Draws [s] with its baseline at [y]; returns the advance width. */
    fun text(s: String, x: Float, y: Float, size: Float, color: Int, weight: Int = 400, align: Paint.Align = Paint.Align.LEFT, spacing: Float = 0f): Float {
        setText(size, weight, spacing); tp.color = color; tp.textAlign = align
        c.drawText(s, x, y, tp)
        return tp.measureText(s)
    }

    /** v5 pill alignment: the baseline that centres [size]/[weight] text vertically in a box from [top] of height [h]. */
    fun centerBase(top: Float, h: Float, size: Float, weight: Int = 400): Float {
        setText(size, weight)
        val fm = tp.fontMetrics
        return top + h / 2f - (fm.ascent + fm.descent) / 2f
    }

    fun measure(s: String, size: Float, weight: Int = 400, spacing: Float = 0f): Float {
        setText(size, weight, spacing)
        return tp.measureText(s)
    }

    /** Shortens with an ellipsis to fit [maxW]. */
    fun fit(s: String, maxW: Float, size: Float, weight: Int = 400): String {
        if (measure(s, size, weight) <= maxW) return s
        var r = s
        while (r.length > 1 && measure("$r…", size, weight) > maxW) r = r.dropLast(1)
        return "$r…"
    }

    /** Greedy word wrap into at most [maxLines] lines (the last one ellipsized). */
    fun wrap(s: String, maxW: Float, size: Float, weight: Int = 400, maxLines: Int = 2): List<String> {
        val words = s.split(' ').filter { it.isNotEmpty() }
        val lines = ArrayList<String>()
        var cur = ""
        for (wd in words) {
            val next = if (cur.isEmpty()) wd else "$cur $wd"
            if (measure(next, size, weight) <= maxW || cur.isEmpty()) cur = next
            else { lines += cur; cur = wd }
        }
        if (cur.isNotEmpty()) lines += cur
        if (lines.size <= maxLines) return lines
        val keep = lines.take(maxLines).toMutableList()
        keep[maxLines - 1] = fit(lines.drop(maxLines - 1).joinToString(" "), maxW, size, weight)
        return keep
    }

    private fun valueSpacing(fs: Float) = if (fs >= 30f) -0.9f / fs else -0.3f / fs

    /** Big number + muted unit on one baseline (A_tiles V()). Returns the total width. */
    fun value(v: String, unit: String?, x: Float, y: Float, fs: Float, ufs: Float = (fs * 0.5f).roundToInt().toFloat(), color: Int = t.text): Float {
        val vw = text(v, x, y, fs, color, 700, spacing = valueSpacing(fs))
        if (unit.isNullOrEmpty()) return vw
        val uw = text(unit, x + vw + 3f, y, ufs, t.muted, 500)
        return vw + 3f + uw
    }

    fun valueWidth(v: String, unit: String?, fs: Float, ufs: Float = (fs * 0.5f).roundToInt().toFloat()): Float =
        measure(v, fs, 700, valueSpacing(fs)) + if (unit.isNullOrEmpty()) 0f else 3f + measure(unit, ufs, 500)

    /** "7h 20m" with numbers in value style and letters in unit style (A_tiles dur()). */
    fun duration(minutes: Int, x: Float, y: Float, fs: Float): Float {
        val u = (fs * 0.55f).roundToInt().toFloat()
        val h = minutes / 60; val m = minutes % 60
        var cx = x
        if (h > 0) {
            cx += text(h.toString(), cx, y, fs, t.text, 700, spacing = valueSpacing(fs)) + 1f
            cx += text("h", cx, y, u, t.muted, 500)
            cx += (fs * 0.18f).roundToInt()
        }
        // "7h 05m": minutes padded after hours (as R3 SFmt.hm)
        val ms = if (h > 0) m.toString().padStart(2, '0') else m.toString()
        cx += text(ms, cx, y, fs, t.text, 700, spacing = valueSpacing(fs)) + 1f
        cx += text("m", cx, y, u, t.muted, 500)
        return cx - x
    }

    /** Width of [duration] at [fs] without drawing it. */
    fun durationWidth(minutes: Int, fs: Float): Float {
        val u = (fs * 0.55f).roundToInt().toFloat()
        val h = minutes / 60; val m = minutes % 60
        var w = 0f
        if (h > 0) w += measure(h.toString(), fs, 700, valueSpacing(fs)) + 1f + measure("h", u, 500) + (fs * 0.18f).roundToInt()
        return w + measure(if (h > 0) m.toString().padStart(2, '0') else m.toString(), fs, 700, valueSpacing(fs)) + 1f + measure("m", u, 500)
    }

    /** Label row: dot r4 + label (600), optional meta right-aligned at [right] (11.5 muted). [top] = row top (18 dp line). */
    fun labelRow(x: Float, top: Float, right: Float, dotColor: Int?, label: String, meta: String?, fs: Float = 13f, labelColor: Int = t.text) {
        val base = top + 9f + fs * 0.36f
        var lx = x
        if (dotColor != null) { circle(x + 4f, top + 9f, 4f, dotColor); lx = x + 14f }
        val lw = text(fit(label, right - lx + 6f, fs, 600), lx, base, fs, labelColor, 600)   // never past the tile edge (6 dp of the padding allowed)
        if (meta != null) {
            val avail = right - (lx + lw + 10f)
            // the whole meta or none (never "La…")
            if (measure(meta, 11.5f) <= avail) text(meta, right, top + 9f + 4f, 11.5f, t.muted, 400, Paint.Align.RIGHT)
        }
    }

    // ------------------------------------------------------------------ shapes

    fun rrect(l: Float, top: Float, r: Float, b: Float, rad: Float, color: Int) {
        if (r <= l || b <= top) return
        fill.shader = null; fill.color = color
        c.drawRoundRect(l, top, r, b, rad, rad, fill)
    }

    fun rect(l: Float, top: Float, r: Float, b: Float, color: Int) {
        if (r <= l || b <= top) return
        fill.shader = null; fill.color = color
        c.drawRect(l, top, r, b, fill)
    }

    fun circle(cx: Float, cy: Float, r: Float, color: Int) {
        fill.shader = null; fill.color = color
        c.drawCircle(cx, cy, r, fill)
    }

    fun ringOutline(cx: Float, cy: Float, r: Float, width: Float, color: Int) {
        stroke.shader = null; stroke.pathEffect = null; stroke.strokeWidth = width; stroke.color = color
        c.drawCircle(cx, cy, r, stroke)
    }

    fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Int, dash: FloatArray? = null) {
        stroke.shader = null; stroke.strokeWidth = width; stroke.color = color
        stroke.pathEffect = dash?.let { DashPathEffect(it, 0f) }
        c.drawLine(x0, y0, x1, y1, stroke)
        stroke.pathEffect = null
    }

    // ------------------------------------------------------------------ glyphs

    /** A design-system glyph (24-grid, round caps) in the square ([x], [y], [size]). [sw] = stroke in grid units. */
    fun glyph(g: Glyph, x: Float, y: Float, size: Float, color: Int, sw: Float = if (size <= 14f) 2.6f else 2f) {
        val k = size / 24f
        c.save()
        c.translate(x, y)
        c.scale(k, k)
        stroke.shader = null; stroke.pathEffect = null; stroke.strokeWidth = sw; stroke.color = color
        fill.shader = null; fill.color = color
        for ((p, isFill) in glyphPaths(g)) c.drawPath(p, if (isFill) fill else stroke)
        c.restore()
    }

    /** Glyph circle (A_tiles gcirc): category tint background + legible glyph; the text role uses tileHi. */
    fun glyphCircle(g: Glyph, col: Int, x: Float, y: Float, d: Float = 26f, gs: Float = 15f) {
        val isText = col == t.text
        circle(x + d / 2f, y + d / 2f, d / 2f, if (isText) t.tileHi else t.tint(col))
        glyph(g, x + (d - gs) / 2f, y + (d - gs) / 2f, gs, if (isText) t.text else t.legible(col, 3.0), if (gs <= 14f) 2.6f else 2.3f)
    }

    /** Small green check badge (goal met). */
    fun checkBadge(cx: Float, cy: Float, d: Float = 20f) {
        circle(cx, cy, d / 2f, Hue.zoneOk)
        val gs = d * 0.55f
        glyph(Glyph.Check, cx - gs / 2f, cy - gs / 2f, gs, Hue.checkInk, 3f)
    }

    // ------------------------------------------------------------------ pills

    enum class Tone { GOOD, BAD, NEUTRAL }
    enum class Dir { UP, DOWN, NONE }

    private fun toneColors(tone: Tone): Pair<Int, Int> = when (tone) {
        Tone.GOOD -> t.goodCont to t.good
        Tone.BAD -> t.badCont to t.bad
        Tone.NEUTRAL -> t.tile to t.muted
    }

    fun toneFg(tone: Tone): Int = toneColors(tone).second

    /** Delta pill (h 22): optional chevron + text, 11.5/600. Returns its width. */
    fun pill(label: String, tone: Tone, dir: Dir, x: Float, top: Float, h: Float = 22f, maxW: Float = Float.MAX_VALUE): Float {
        val (bg, fg) = toneColors(tone)
        val gw = if (dir == Dir.NONE) 0f else 15f
        val padL = if (dir == Dir.NONE) 9f else 8f   // ▲ / ▼ ink inset ≈ the 9 dp right inset
        val s = fit(label, maxW - padL - 9f - gw, 11.5f, 600)
        val w = padL + gw + measure(s, 11.5f, 600) + 9f
        rrect(x, top, x + w, top + h, h / 2f, bg)
        if (dir != Dir.NONE) {
            // QA 01.7: one direction glyph set app-wide: filled ▲ ▼ (not chevrons)
            val cx = x + padL + 6f; val cy = top + h / 2f
            val tri = Path().apply {
                if (dir == Dir.UP) { moveTo(cx - 4.5f, cy + 3.5f); lineTo(cx + 4.5f, cy + 3.5f); lineTo(cx, cy - 4f) }
                else { moveTo(cx - 4.5f, cy - 3.5f); lineTo(cx + 4.5f, cy - 3.5f); lineTo(cx, cy + 4f) }
                close()
            }
            fill.shader = null; fill.color = fg; c.drawPath(tri, fill)
        }
        text(s, x + padL + gw, centerBase(top, h, 11.5f, 600), 11.5f, fg, 600)
        return w
    }

    // ------------------------------------------------------------------ charts

    data class SparkOpts(
        val min: Double? = null, val max: Double? = null,
        val band: Pair<Double, Double>? = null, val bandLabel: String? = null,
        val grid: List<Double> = emptyList(), val gridFormat: (Double) -> String = { it.roundToInt().toString() },
        val area: Boolean = true, val areaAlpha: Float = 0.24f,
        val sw: Float = 1.8f, val pad: Float = 5f,
        val dot: Boolean = true, val dotR: Float = 4f,
        val dots: Boolean = false,
        val xLabels: List<Pair<Float, String>> = emptyList(),   // (fraction 0..1 of the x span, label)
        val ma: Int = 0,
    )

    /** Moving average (window [n]) as in A_SPEC §2: M/L sparklines read as a trend. */
    fun movingAverage(v: List<Double>, n: Int): List<Double> = if (n <= 1) v else v.indices.map { i ->
        val a = v.subList(max(0, i - n + 1), i + 1); a.sum() / a.size
    }

    /** Smooth sparkline (Catmull-Rom → cubic Bézier), optional band, gridlines, area fill, end dot. */
    fun spark(raw: List<Double>, x: Float, y: Float, w: Float, h: Float, col: Int, o: SparkOpts = SparkOpts()) {
        if (raw.isEmpty() || w <= 4f || h <= 4f) return
        val vals = movingAverage(raw, o.ma)
        val all = vals + (o.band?.toList() ?: emptyList())
        val lo = o.min ?: all.min(); val hi = o.max ?: all.max()
        val rg = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
        val pad = o.pad
        fun X(i: Int) = x + pad + (w - 2 * pad) * (if (vals.size == 1) 1f else i.toFloat() / (vals.size - 1))
        fun Y(v: Double) = y + pad + (h - 2 * pad) * (1f - ((v - lo) / rg).toFloat())
        o.band?.let { (b0, b1) ->
            val top = Y(b1).coerceIn(y, y + h); val bot = Y(b0).coerceIn(y, y + h)
            rrect(x, top, x + w, bot, 4f, col.alpha(if (t.dark) 0.12f else 0.10f))
            o.bandLabel?.let { text(it, x + 2f, top - 5f, 10.5f, t.legible(col), 600) }
        }
        for (g in o.grid) {
            val gy = Y(g)
            line(x, gy, x + w, gy, 1f, t.grid)
            text(o.gridFormat(g), x + w + 6f, gy + 3.5f, 10.5f, t.muted)
        }
        val pts = vals.mapIndexed { i, v -> X(i) to Y(v) }
        val path = smoothPath(pts)
        if (o.area && pts.size > 1) {
            val ap = Path(path)
            ap.lineTo(pts.last().first, y + h); ap.lineTo(pts.first().first, y + h); ap.close()
            fill.shader = LinearGradient(0f, y, 0f, y + h, col.alpha(o.areaAlpha), col.alpha(0f), Shader.TileMode.CLAMP)
            c.drawPath(ap, fill)
            fill.shader = null
        }
        stroke.shader = null; stroke.pathEffect = null; stroke.strokeWidth = o.sw; stroke.color = col
        if (pts.size > 1) c.drawPath(path, stroke)
        if (o.dots) pts.forEach { (px, py) -> circle(px, py, 2.6f, t.card); ringOutline(px, py, 2.6f, 1.8f, col) }
        if (o.dot) { val (px, py) = pts.last(); circle(px, py, o.dotR + 1f, t.card); circle(px, py, o.dotR, col) }
        for ((f, s) in o.xLabels) text(s, x + pad + (w - 2 * pad) * f, y + h + 15f, 10.5f, t.muted, 400, Paint.Align.CENTER)
    }

    fun smoothPath(p: List<Pair<Float, Float>>): Path {
        val path = Path()
        if (p.isEmpty()) return path
        path.moveTo(p[0].first, p[0].second)
        for (i in 0 until p.size - 1) {
            val p0 = p[max(0, i - 1)]; val p1 = p[i]; val p2 = p[i + 1]; val p3 = p[min(p.size - 1, i + 2)]
            path.cubicTo(
                p1.first + (p2.first - p0.first) / 6f, p1.second + (p2.second - p0.second) / 6f,
                p2.first - (p3.first - p1.first) / 6f, p2.second - (p3.second - p1.second) / 6f,
                p2.first, p2.second,
            )
        }
        return path
    }

    /** Day letters under bar slots; [today] gets the accent pill (as the app's DayBars), later days are faded. */
    private fun drawLetters(letters: List<String>, x: Float, slot: Float, baseY: Float, today: Int?) {
        letters.forEachIndexed { i, l ->
            val cx = x + slot * i + slot / 2f
            dayLetter(l, cx, baseY, today = i == today, faded = today != null && i > today)
        }
    }

    /** One day letter centred at [cx] (baseline [base]); today = palette-accent pill with onAccent letter (app DayLetter). */
    fun dayLetter(l: String, cx: Float, base: Float, today: Boolean, faded: Boolean = false) {
        if (today) { circle(cx, base - 4f, 8.5f, t.accent); text(l, cx, base, 10.5f, t.onAccent, 700, Paint.Align.CENTER) }
        else text(l, cx, base, 10.5f, if (faded) t.muted.alpha(0.45f) else t.muted, 500, Paint.Align.CENTER)
    }

    /** Rounded bars (null = future/no data) in a c0→c1 gradient; below-goal bars at 32 % / 42 %; dashed goal line. */
    fun bars(vals: List<Double?>, x: Float, y: Float, w: Float, h: Float, cols: IntArray, goal: Double? = null,
             letters: List<String>? = null, today: Int? = null, bw0: Float = 10f, maxV: Double? = null) {
        val n = vals.size; if (n == 0) return
        val slot = w / n; val bw = min(bw0, slot * 0.56f)
        val ph = h - if (letters != null) 18f else 0f
        val mx = (maxV ?: (max(vals.filterNotNull().maxOrNull() ?: 0.0, goal ?: 0.0) * 1.06)).takeIf { it > 0 } ?: 1.0
        vals.forEachIndexed { i, v ->
            val bx = x + slot * i + slot / 2f - bw / 2f
            if (v == null || v <= 0.0) { rrect(bx, y + ph - bw * 0.5f, bx + bw, y + ph, bw / 4f, t.grid); return@forEachIndexed }
            val bh = max(bw, ph * (min(v, mx) / mx).toFloat())
            val met = goal == null || v >= goal
            if (met) {
                fill.shader = LinearGradient(0f, y + ph, 0f, y + ph - bh, cols[0], cols[1], Shader.TileMode.CLAMP)
                fill.color = 0xFFFFFFFF.toInt()
                c.drawRoundRect(bx, y + ph - bh, bx + bw, y + ph, bw / 2f, bw / 2f, fill)
                fill.shader = null
            } else rrect(bx, y + ph - bh, bx + bw, y + ph, bw / 2f, cols[0].alpha(if (t.dark) 0.42f else 0.32f))
        }
        if (goal != null) {
            val gy = y + ph - ph * (goal / mx).toFloat()
            line(x, gy, x + w, gy, 1.3f, t.text.alpha(0.5f), floatArrayOf(3f, 3.5f))
        }
        if (letters != null) drawLetters(letters, x, slot, y + h - 3f, today)
    }

    /** Stacked day bars (bottom → top segments of (value, colour)) on a track; null day = future (faded track). */
    fun stackBars(days: List<List<Pair<Double, Int>>?>, x: Float, y: Float, w: Float, h: Float, maxV: Double,
                  letters: List<String>? = null, today: Int? = null, bw0: Float = 12f, totals: Boolean = false, track: Int = t.tile) {
        val n = days.size; if (n == 0) return
        val slot = w / n; val bw = min(bw0, slot * 0.56f)
        val ph = h - if (letters != null) 18f else 0f
        val mx = if (maxV > 0) maxV else 1.0
        days.forEachIndexed { i, d ->
            val bx = x + slot * i + slot / 2f - bw / 2f
            rrect(bx, y, bx + bw, y + ph, bw / 2f, if (d == null) track.alpha(0.55f * ((track ushr 24) / 255f)) else track)
            if (d.isNullOrEmpty()) return@forEachIndexed
            val tot = d.sumOf { it.first }
            if (tot <= 0) return@forEachIndexed
            val bh = max(bw, ph * (min(tot, mx) / mx).toFloat())
            c.save()
            val clip = Path().apply { addRoundRect(RectF(bx, y + ph - bh, bx + bw, y + ph), bw / 2f, bw / 2f, Path.Direction.CW) }
            c.clipPath(clip)
            var yy = y + ph
            d.forEachIndexed { j, (m, col) ->
                val sh = (bh * m / tot).toFloat()
                rect(bx, yy - sh, bx + bw, yy, col)
                yy -= sh
                if (j < d.size - 1) rect(bx, yy - 1f, bx + bw, yy + 1f, t.card)
            }
            c.restore()
            if (totals) text(tot.roundToInt().toString(), bx + bw / 2f, y + ph - bh - 5f, 10f, t.muted, 600, Paint.Align.CENTER)
        }
        if (letters != null) drawLetters(letters, x, slot, y + h - 3f, today)
    }

    /** V2 ring colours of this theme (palette slots 0..2) on [surface]. */
    fun ringColors(surface: RingSurface = RingSurface.OPAQUE, wallpaperDark: Boolean = true): RingColors = RingColors.of(t, surface, wallpaperDark)

    /** One goal ring (RingPaint, same as the app) in the square at ([x], [y]) of diameter [d]; colour = cols[1] on dark, cols[0] on light. */
    fun singleRing(x: Float, y: Float, d: Float, p: Float, cols: IntArray, sw: Float = max(4f, (d * 0.13f).roundToInt().toFloat())) =
        ring(x, y, d, p, if (t.dark) cols.last() else cols.first(), sw)

    /** One V2 ring (neutral track, solid fill, darker lap) in [color]. */
    fun ring(x: Float, y: Float, d: Float, p: Float, color: Int, sw: Float = max(4f, (d * 0.13f).roundToInt().toFloat()),
             col: RingColors = ringColors()) {
        RingPaint.ring(c, x + d / 2f, y + d / 2f, d / 2f - sw / 2f, sw, p, color, RingColors.lapOf(color), col.track, col.rim)
    }

    /**
     * Combined rings in palette order (0 Workout minutes · 1 Steps · 2 Heart points), V2 fill, numbers integrated in the
     * strokes (G0). Returns the label plan: BESIDE = the stroke was too thin; the caller shows [ringLegend] instead.
     */
    fun rings(left: Float, top: Float, d: Float, progress: FloatArray, labels: List<RingLabel?>?, s: Float = RingPaint.strokeFor(d),
              gap: Float = if (d < 90f) 2f else 3f, col: RingColors = ringColors()): RingLabelPlan =
        RingPaint.triple(c, left, top, d, progress, labels, col, s, gap)

    /** Beside-ring numbers (fallback of [rings]). Returns the height used. */
    fun ringLegend(x: Float, top: Float, maxW: Float, labels: List<RingLabel?>, progress: FloatArray, fs: Float = 12.5f, pitch: Float = 19f,
                   col: RingColors = ringColors(), withUnit: Boolean = true): Float =
        RingPaint.legend(c, x, top, maxW, labels, progress, col, fs, pitch, withUnit = withUnit)

    /**
     * v6 signature kept for callers outside T4: [labels] are plain value strings (no target). New code uses [rings].
     */
    fun triple(left: Float, top: Float, d: Float, progress: FloatArray, s: Float = RingPaint.strokeFor(d, 0.12f), gap: Float = 3f, shadow: Boolean = true,
               labels: List<String>? = null, labelColor: Int = t.text) {
        rings(left, top, d, progress, labels?.map { RingLabel(it) }, s, gap)
    }

    /** 240° gauge (VO2): track at 20 %, value arc, knob in card colour. */
    fun gauge(x: Float, y: Float, w: Float, h: Float, frac: Float, col: Int, sw: Float = 7f) {
        val r = min((h - sw) / 1.5f, w / 2f - sw / 2f)
        if (r <= 2f) return
        val cx = x + w / 2f; val cy = y + sw / 2f + r + (h - sw - 1.5f * r) / 2f
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        stroke.shader = null; stroke.pathEffect = null; stroke.strokeWidth = sw
        // design angles: 0° = up, clockwise; Android: 0° = right → start = -90 - 120
        stroke.color = col.alpha(0.2f)
        c.drawArc(rect, -210f, 240f, false, stroke)
        val f = frac.coerceIn(0f, 1f)
        stroke.color = col
        if (f > 0.001f) c.drawArc(rect, -210f, 240f * f, false, stroke)
        val a = Math.toRadians((-210.0 + 240.0 * f))
        circle(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), max(1f, sw / 2f - 1.2f), t.card)
    }

    /** Horizontal zone scale (BMI, WHtR): coloured segments, a marker at [v], optional tick labels below. */
    fun scaleBar(x: Float, y: Float, w: Float, zones: List<Triple<Double, Double, Int>>, lo: Double, hi: Double, v: Double,
                 h: Float = 7f, ticks: List<Pair<Double, String>> = emptyList()) {
        fun X(d: Double) = x + ((d - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f) * w
        c.save()
        c.clipPath(Path().apply { addRoundRect(RectF(x, y + 3f, x + w, y + 3f + h), h / 2f, h / 2f, Path.Direction.CW) })
        zones.forEachIndexed { i, (a, b, col) -> rect(X(a) + if (i > 0) 1f else 0f, y + 3f, X(b), y + 3f + h, col) }
        c.restore()
        val mx = X(v)
        circle(mx, y + 3f + h / 2f, h / 2f + 3f + 2.5f, t.card)
        circle(mx, y + 3f + h / 2f, h / 2f + 3f, t.text)
        ticks.forEach { (tv, s) -> text(s, X(tv), y + h + 20f, 10f, t.muted, 400, Paint.Align.CENTER) }
    }

    /** Proportional stage bar (sleep M tile): segments with 2 dp gaps, rounded. */
    fun segmentBar(parts: List<Pair<Double, Int>>, x: Float, y: Float, w: Float, h: Float = 8f) {
        val tot = parts.sumOf { it.first }
        if (tot <= 0) { rrect(x, y, x + w, y + h, h / 2f, t.tile); return }
        val shown = parts.filter { it.first > 0 }
        val avail = w - 2f * (shown.size - 1)
        var cx = x
        shown.forEach { (m, col) ->
            val sw = max(h, avail * (m / tot).toFloat())
            rrect(cx, y, min(x + w, cx + sw), y + h, h / 2f, col)
            cx += sw + 2f
        }
    }

    companion object {
        /** Android paths of a glyph (the same path data the app's GlyphIcon uses; Compose-free). */
        fun glyphPaths(g: Glyph): List<Pair<Path, Boolean>> = SvgPath.glyph(g)
    }
}
