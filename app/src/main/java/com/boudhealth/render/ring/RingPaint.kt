package com.boudhealth.render.ring

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.boudhealth.render.RenderTheme
import com.boudhealth.ui.theme.argb
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * V2.0 ring painter (IC6; V2-028, V2-090–V2-092). ONE painter for Compose (via nativeCanvas, px) and widget bitmaps
 * (pre-scaled canvas, dp): same fill, same numbers, same geometry. android.graphics only, no Compose.
 *
 * Fill fix (V2-091): neutral track (tile mixed 11 % toward text; on transparent: white 16 % / black 12 %), SOLID fill in
 * the ring colour (dark: bright end colour; light: the theme-adjusted ≥ 3 : 1 start colour), a 1 dp lighter leading
 * edge on the tip, past 100 % a second lap in a darker shade of the same colour with a tile-coloured rim behind its
 * tip. Past the goal the lap is the only cue (numbers only).
 * Numbers (V2-090, G0): integrated in the stroke along the arc (RingLabelLayout), never upside down, Inter tnum,
 * "34 / 50" with "/ 50" at 60 %, ≥ 10 sp; BESIDE fallback (legend) when the stroke is too thin.
 * Thread-safe (cause 10): all Paints are per-thread; nothing static is mutated while drawing.
 */

/** Surface under the ring: an opaque tile, or a transparent widget on the wallpaper. */
enum class RingSurface { OPAQUE, TRANSPARENT }

/**
 * Ring colours by ring position (0 Workout minutes · 1 Steps · 2 Heart points for the activity set; any count for other
 * sets). All ARGB.
 */
class RingColors(
    val fills: IntArray,
    val laps: IntArray,
    val track: Int,
    /** Behind a lap tip: the tile colour (opaque) or a soft shadow (transparent). */
    val rim: Int,
    /** Text on the track / beside the ring. */
    val text: Int,
    val muted: Int,
    /** Light and dark inks for labels on a fill; the one with the higher contrast is used. */
    val inkLight: Int = 0xFFFFF8EE.toInt(),
    val inkDark: Int = 0xFF151512.toInt(),
) {
    fun fill(i: Int): Int = fills[i.coerceIn(0, fills.size - 1)]
    fun lap(i: Int): Int = laps[i.coerceIn(0, laps.size - 1)]
    /** The ink with the higher contrast on [bg]. */
    fun inkOn(bg: Int): Int = if (contrast(inkLight, bg) >= contrast(inkDark, bg)) inkLight else inkDark
    /** Labels prefer the fill (one consistent placement in both themes, QA 02.3); the better ink is ≥ 4.0 : 1 there. */
    fun inkOk(bg: Int): Boolean = contrast(inkOn(bg), bg) >= 4.0

    /** Same theme, one colour for every ring (Streak flame ring, Body target rings, single goal rings). */
    fun single(color: Int): RingColors = RingColors(intArrayOf(color), intArrayOf(lapOf(color)), track, rim, text, muted, inkLight, inkDark)

    /** Same theme, explicit colours per ring. */
    fun with(colors: IntArray): RingColors = RingColors(colors, IntArray(colors.size) { lapOf(colors[it]) }, track, rim, text, muted, inkLight, inkDark)

    companion object {
        /** Theme ring colours (palette slots 0..2) on [surface]. [wallpaperDark] only matters on a transparent surface. */
        fun of(t: RenderTheme, surface: RingSurface = RingSurface.OPAQUE, wallpaperDark: Boolean = true): RingColors {
            val brightFill = t.dark || (surface == RingSurface.TRANSPARENT && wallpaperDark)
            // V2_VISUAL_QA #3: dark = mix(c0, c1, 0.30) (design6 kit6 fill), light = the theme-adjusted start (≥ 3 : 1)
            val fills = IntArray(3) { if (brightFill) darkFill(t.ring.c0Argb(it), t.ring.c1Argb(it)) else lightFill(t.ring.c0Argb(it), t.ring.c1Argb(it), t.card) }
            val transparent = surface == RingSurface.TRANSPARENT
            val track = when {
                transparent && wallpaperDark -> withAlpha(0xFFFFFFFF.toInt(), 0.16f)
                transparent -> withAlpha(0xFF000000.toInt(), 0.12f)
                else -> t.ring.trackSeed?.argb() ?: trackOn(t.tile, t.text, t.dark)   // V2-137 custom track
            }
            val text = if (transparent) (if (wallpaperDark) 0xFFFFFFFF.toInt() else 0xFF151512.toInt()) else t.text
            val muted = if (transparent) withAlpha(text, 0.72f) else t.muted
            return RingColors(fills, IntArray(3) { lapOf(fills[it]) }, track, if (transparent) 0x55000000 else t.tile, text, muted)
        }

        /** Second lap (QA #3): the same colour 22 % toward black, saturation kept (no muddy shade). */
        fun lapOf(c: Int): Int = mix(c, 0xFF000000.toInt(), 0.22f)

        /**
         * Light-theme fill (QA §3, "not muddy"): from the theme-adjusted start toward the end colour (at most 30 %, as
         * dark) while it keeps ≥ 3.3 : 1 on the card: Steps #6E7A75 → ≈ #7A8782, Heart ≈ #A3824F.
         */
        fun lightFill(c0: Int, c1: Int, card: Int): Int {
            var best = c0; var f = 0.02f
            while (f <= 0.30f + 1e-4f) { val m = mix(c0, c1, f); if (contrast(m, card) >= 3.3) best = m else break; f += 0.02f }
            return best
        }

        /** Dark-theme fill: 30 % of the way from the start to the end colour (design6 kit6). */
        fun darkFill(c0: Int, c1: Int): Int = mix(c0, c1, 0.30f)

        /** Neutral track on [bg]: dark = bg 8 % toward text (#2C2C27 on the card), light = bg 6.5 % toward text (#ECE9E0). */
        fun trackOn(bg: Int, text: Int, dark: Boolean): Int = mix(bg, text, if (dark) 0.08f else 0.065f)

        fun shade(c: Int, v: Float, s: Float): Int {
            val hsv = FloatArray(3); android.graphics.Color.colorToHSV(c, hsv)
            hsv[2] *= v; hsv[1] = (hsv[1] * s).coerceAtMost(1f)
            return android.graphics.Color.HSVToColor(hsv)
        }

        fun mix(a: Int, b: Int, f: Float): Int {
            fun ch(sh: Int) = (((a shr sh) and 0xFF) + ((((b shr sh) and 0xFF) - ((a shr sh) and 0xFF)) * f)).roundToInt()
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        fun withAlpha(c: Int, a: Float): Int = (c and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).roundToInt() shl 24)

        /** WCAG relative luminance of an opaque ARGB colour. */
        fun luminance(c: Int): Double {
            fun ch(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4) }
            return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
        }

        fun contrast(a: Int, b: Int): Double {
            val la = luminance(a); val lb = luminance(b)
            return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
        }
    }
}

/**
 * One ring's number. [value] / [target] are the full forms ("7,240" / "10,000"); [compactValue] / [compactTarget]
 * the short forms ("7.2k" / "10k") tried when the full form does not fit in the stroke. [unit] and [name] are only used
 * by the beside-ring legend ("Steps 7,240 / 10,000").
 */
data class RingLabel(
    val value: String,
    val target: String? = null,
    val unit: String = "",
    val compactValue: String = value,
    val compactTarget: String? = target,
    val name: String = "",
) {
    /** In-ring variants, best first: full, compact, value only. */
    val variants: List<Pair<String, String?>>
        get() = listOf(value to target, compactValue to compactTarget, compactValue to null).distinct()

    companion object {
        /** "7,240" → "7.2k", "10,000" → "10k" (only from 10,000 on for values, as the app's Fmt.compact). */
        fun compact(v: Double): String = when {
            v < 1000 -> v.roundToInt().toString()
            v < 10_000 -> String.format(java.util.Locale.US, "%.1fk", v / 1000).replace(".0k", "k")
            v < 1_000_000 -> "${(v / 1000).roundToInt()}k"
            else -> String.format(java.util.Locale.US, "%.2f", v / 1_000_000).trimEnd('0').trimEnd('.') + "M"
        }

        fun grouped(v: Double): String = String.format(java.util.Locale.US, "%,d", v.roundToInt())

        /** From a number and an optional target, with compact forms from 1,000 on. */
        fun of(value: Double, target: Double?, unit: String = "", name: String = ""): RingLabel = RingLabel(
            grouped(value), target?.let { grouped(it) }, unit,
            if (value >= 1000) compact(value) else grouped(value), target?.let { if (it >= 1000) compact(it) else grouped(it) }, name,
        )
    }
}

object RingPaint {
    /** Default stroke for a triple ring of diameter [d]: 0.115·d (native-density refinement; drafts 0.13, hero 0.10). */
    fun strokeFor(d: Float, ratio: Float = 0.115f): Float = (d * ratio).roundToInt().toFloat().coerceAtLeast(2f)

    /** Centre radius of ring [i] (0 = outer) in a box of diameter [d]. */
    fun radius(d: Float, s: Float, gap: Float, i: Int): Float = d / 2f - s / 2f - i * (s + gap)

    private class Paints {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { fontFeatureSettings = RingFonts.FEATURES }
        val oval = RectF()
        val path = Path()
    }
    private val tl = object : ThreadLocal<Paints>() { override fun initialValue() = Paints() }

    private fun point(cx: Float, cy: Float, r: Float, deg: Float): Pair<Float, Float> {
        val a = Math.toRadians((deg - 90f).toDouble())
        return Pair(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat())
    }

    // ------------------------------------------------------------------------------------------------ one ring

    /**
     * One ring at (cx, cy), centre radius [r], stroke [s], progress [p] (1 = goal, > 1 second lap), with the V2 fill:
     * [track] neutral, [fill] solid, [lap] darker second lap, [rim] behind the lap tip. [unit] = canvas units per dp.
     */
    fun ring(c: Canvas, cx: Float, cy: Float, r: Float, s: Float, p: Float, fill: Int, lap: Int, track: Int, rim: Int, unit: Float = 1f) {
        val k = tl.get()!!
        val st = k.stroke
        st.shader = null; st.pathEffect = null; st.strokeWidth = s; st.strokeCap = Paint.Cap.BUTT; st.color = track
        c.drawCircle(cx, cy, r, st)
        if (p <= 0.002f || r <= 0f) return
        val oval = k.oval.apply { set(cx - r, cy - r, cx + r, cy + r) }
        val p1 = min(p, 1f)
        st.color = fill
        if (p1 >= 1f) c.drawCircle(cx, cy, r, st)
        else { st.strokeCap = Paint.Cap.ROUND; c.drawArc(oval, -90f, max(0.5f, 360f * p1), false, st) }
        var tipColor = fill
        if (p >= 2f) {
            // ≥ 200 %: the base fill stays readable as THIS ring; the full second lap is a 35 % veil of the lap colour
            st.strokeCap = Paint.Cap.BUTT; st.color = RingColors.withAlpha(lap, 0.35f); c.drawCircle(cx, cy, r, st)
        } else if (p > 1f) {
            // second lap drawn only over the lap arc, solid lap colour, rim behind its tip
            val lapDeg = (p - 1f) * 360f
            st.color = lap; tipColor = lap
            if (lapDeg > 0.3f) {
                val (tx, ty) = point(cx, cy, r, lapDeg)
                val a = Math.toRadians(lapDeg.toDouble())
                k.fill.shader = null; k.fill.color = rim
                c.drawCircle(tx + 0.6f * unit * cos(a).toFloat(), ty + 0.6f * unit * sin(a).toFloat(), s / 2f + 1.2f * unit, k.fill)
                st.strokeCap = Paint.Cap.ROUND
                c.drawArc(oval, -90f, lapDeg, false, st)
            }
        }
        // 1 dp lighter leading edge on the tip cap, only on arcs over 30° (QA #3: a pale rim on short arcs)
        val arcDeg = if (p < 1f) 360f * p else (p - 1f) * 360f
        if ((p < 1f || (p > 1.003f && p < 2f)) && arcDeg > 30f) {
            val tip = RingLabelLayout.tipDeg(p)
            val (tx, ty) = point(cx, cy, r, tip)
            st.strokeWidth = unit; st.color = RingColors.mix(tipColor, 0xFFFFFFFF.toInt(), 0.35f); st.strokeCap = Paint.Cap.ROUND
            val rr = s / 2f - 0.5f * unit
            k.oval.set(tx - rr, ty - rr, tx + rr, ty + rr)
            c.drawArc(k.oval, tip - 90f + 8f, 180f - 16f, false, st)
        }
        st.strokeCap = Paint.Cap.BUTT
    }

    /** Concentric rings (outer → inner) in the box (left, top, d); colour of ring i = [col].fill(i). */
    fun rings(c: Canvas, left: Float, top: Float, d: Float, progress: FloatArray, col: RingColors,
              s: Float = strokeFor(d), gap: Float = if (d < 90f) 2f else 3f, unit: Float = 1f) {
        val cx = left + d / 2f; val cy = top + d / 2f
        for (i in progress.indices) {
            val r = radius(d, s, gap, i)
            if (r <= s / 2f) break
            ring(c, cx, cy, r, s, progress[i], col.fill(i), col.lap(i), col.track, col.rim, unit)
        }
    }

    // ------------------------------------------------------------------------------------------------ integrated labels

    private fun measure(k: Paints, txt: String, fs: Float, weight: Int): Float {
        k.text.textSize = fs; k.text.typeface = RingFonts.face(weight); k.text.letterSpacing = 0f
        return k.text.measureText(txt)
    }

    private const val VALUE_W = 700
    private const val TARGET_W = 600
    /** "/ target" ink alpha (QA #4c: 60 % was ~2 : 1 on light fills). */
    private const val TARGET_ALPHA = 0.75f


    /** Width of variant (v, t) at [fs]. */
    private fun variantWidth(k: Paints, v: String, t: String?, fs: Float): Float =
        measure(k, v, fs, VALUE_W) + (t?.let { measure(k, " / $it", fs, TARGET_W) } ?: 0f)

    /**
     * Plans the labels of a concentric ring set (no drawing): integrated if the stroke and every label allow it, else
     * BESIDE. [minSp] = 10 on widgets and in the app.
     */
    fun plan(d: Float, progress: FloatArray, labels: List<RingLabel?>, col: RingColors, s: Float = strokeFor(d),
             gap: Float = if (d < 90f) 2f else 3f, unit: Float = 1f, minSp: Float = RingLabelLayout.MIN_SP): RingLabelPlan {
        val k = tl.get()!!
        val fs = RingLabelLayout.fontSize(s, unit)
        val geoms = progress.indices.map { i ->
            val p = progress[i]
            RingGeom(radius(d, s, gap, i), s, p, col.inkOk(col.fill(i)), col.inkOk(col.lap(i)))
        }
        val widths = progress.indices.map { i ->
            val l = labels.getOrNull(i) ?: return@map null
            l.variants.map { (v, t) -> variantWidth(k, v, t, fs) }.toFloatArray()
        }
        val plan = RingLabelLayout.layout(geoms, widths, unit, minSp)
        if (plan.mode != RingLabelMode.INTEGRATED) return plan
        // a ring that could only take the value ("150") keeps its target ("150 / 350") at a smaller size, down to minSp
        val out = plan.labels.toMutableList()
        out.forEachIndexed { i, l ->
            l ?: return@forEachIndexed
            val lab = labels.getOrNull(i) ?: return@forEachIndexed
            if (lab.target == null || lab.variants[l.variant].second != null) return@forEachIndexed
            var f = fs - 0.5f * unit
            while (f >= minSp * unit - 1e-3f) {
                val ws = lab.variants.filter { it.second != null }.map { (v, t) -> variantWidth(k, v, t, f) }.toFloatArray()
                val alt = RingLabelLayout.place(i, geoms[i], ws, f)
                if (alt != null) { out[i] = alt; break }
                f -= 0.5f * unit
            }
        }
        return RingLabelPlan(plan.mode, out, plan.fontSize)
    }

    /** Draws the labels of an INTEGRATED [plan] (no-op otherwise). */
    fun drawLabels(c: Canvas, left: Float, top: Float, d: Float, progress: FloatArray, labels: List<RingLabel?>, col: RingColors,
                   plan: RingLabelPlan, unit: Float = 1f) {
        if (plan.mode != RingLabelMode.INTEGRATED) return
        val k = tl.get()!!
        val cx = left + d / 2f; val cy = top + d / 2f
        for (l in plan.labels) {
            l ?: continue
            val lab = labels.getOrNull(l.ring) ?: continue
            val (v, t) = lab.variants[l.variant]
            val i = l.ring
            val bg = when (l.ground) { LabelGround.FILL -> col.fill(i); LabelGround.LAP -> col.lap(i); LabelGround.TRACK -> col.track }
            val onTrack = l.ground == LabelGround.TRACK
            val ink = if (onTrack) col.text else col.inkOn(bg)
            val inkMuted = if (onTrack) col.muted else RingColors.withAlpha(ink, TARGET_ALPHA)
            drawArcText(c, k, cx, cy, l, v, t, ink, inkMuted)
        }
    }

    private fun drawArcText(c: Canvas, k: Paints, cx: Float, cy: Float, l: ArcLabel, v: String, t: String?, ink: Int, muted: Int) {
        val r = l.radius
        val fs = l.fontSize
        val oval = k.oval.apply { set(cx - r, cy - r, cx + r, cy + r) }
        val path = k.path
        path.reset()
        // a slightly longer path than the text so the last glyph is never clipped by the path end
        val extra = 6f
        if (!l.flipped) path.addArc(oval, l.startDeg - 90f, l.sweepDeg + extra)
        else path.addArc(oval, l.endDeg - 90f, -(l.sweepDeg + extra))
        val vOff = fs * RingLabelLayout.CAP / 2f
        var h = 0f
        k.text.textAlign = Paint.Align.LEFT
        k.text.textSize = fs; k.text.typeface = RingFonts.face(VALUE_W); k.text.color = ink
        c.drawTextOnPath(v, path, h, vOff, k.text)
        h += k.text.measureText(v)
        if (t != null) {
            k.text.typeface = RingFonts.face(TARGET_W); k.text.color = muted
            c.drawTextOnPath(" / $t", path, h, vOff, k.text)
        }
    }

    /**
     * Rings + their numbers. Integrated in the strokes where possible; when the plan is BESIDE the caller draws
     * [legend] next to the ring (the returned plan says so). [labels] = null → rings only.
     */
    fun triple(c: Canvas, left: Float, top: Float, d: Float, progress: FloatArray, labels: List<RingLabel?>?, col: RingColors,
               s: Float = strokeFor(d), gap: Float = if (d < 90f) 2f else 3f, unit: Float = 1f,
               minSp: Float = RingLabelLayout.MIN_SP): RingLabelPlan {
        rings(c, left, top, d, progress, col, s, gap, unit)
        if (labels == null) return RingLabelPlan(RingLabelMode.NONE, progress.map { null }, 0f)
        val plan = plan(d, progress, labels, col, s, gap, unit, minSp)
        drawLabels(c, left, top, d, progress, labels, col, plan, unit)
        return plan
    }

    /**
     * BESIDE fallback (R-A): one line per ring: dot · "45 / 30 min" (target muted). Long forms first, compact when
     * narrow. [x], [top], [maxW], [fs], [pitch] in canvas units. Returns the height used.
     */
    fun legend(c: Canvas, x: Float, top: Float, maxW: Float, labels: List<RingLabel?>, progress: FloatArray, col: RingColors,
               fs: Float = 12.5f, pitch: Float = 19f, unit: Float = 1f, withUnit: Boolean = true): Float {
        val k = tl.get()!!
        labels.forEachIndexed { i, l ->
            l ?: return@forEachIndexed
            val base = top + i * pitch + fs * RingLabelLayout.CAP + (pitch - fs * RingLabelLayout.CAP) / 2f - unit
            val cyDot = base - fs * RingLabelLayout.CAP / 2f
            k.fill.shader = null; k.fill.color = col.fill(i)
            c.drawCircle(x + 4f * unit, cyDot, 4f * unit, k.fill)
            var cx = x + 14f * unit
            val u = if (withUnit && l.unit.isNotEmpty()) " ${l.unit}" else ""
            val room = maxW - 14f * unit
            val forms = listOf(l.value to l.target?.let { " / $it$u" }, l.value to l.target?.let { " / $it" },
                l.compactValue to l.compactTarget?.let { " / $it$u" }, l.compactValue to l.compactTarget?.let { " / $it" }, l.compactValue to null)
            val (v, tg) = forms.firstOrNull { (a, b) -> measure(k, a, fs, VALUE_W) + (b?.let { measure(k, it, fs, 500) } ?: 0f) <= room } ?: forms.last()
            k.text.textAlign = Paint.Align.LEFT
            k.text.textSize = fs; k.text.typeface = RingFonts.face(VALUE_W); k.text.color = col.text
            c.drawText(v, cx, base, k.text); cx += k.text.measureText(v)
            if (tg != null) { k.text.typeface = RingFonts.face(500); k.text.color = col.muted; c.drawText(tg, cx, base, k.text); cx += k.text.measureText(tg) }
        }
        return labels.size * pitch
    }

    // ------------------------------------------------------------------------------------------------ week bar

    /** Thin goal bar: track, solid fill to [p] (clamped to 1). Box (left, top, w, h). */
    fun bar(c: Canvas, left: Float, top: Float, w: Float, h: Float, p: Float, fill: Int, track: Int) {
        val k = tl.get()!!
        val r = h / 2f
        k.fill.shader = null; k.fill.color = track
        c.drawRoundRect(left, top, left + w, top + h, r, r, k.fill)
        val q = p.coerceIn(0f, 1f)
        if (q <= 0f) return
        k.fill.color = fill
        c.drawRoundRect(left, top, left + max(h, w * q), top + h, r, r, k.fill)
    }

}
