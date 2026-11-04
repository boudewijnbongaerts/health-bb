package com.boudhealth.render.ring

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * V2.0 ring numbers (V2-028, V2-090, G0 decision): each number is INTEGRATED in its own ring's stroke, set along the
 * circle. Pure geometry (no android.*), JVM-tested (V2-093). Units = the canvas units of the caller (dp in widget
 * bitmaps, px in Compose); [unit] = canvas units per dp, so dp constants scale.
 *
 * Rules (owner, G0):
 *  1. the text sits inside the stroke, centred on the ring's radius, following the arc;
 *  2. it is never upside down: a label whose centre lies on the lower half (between 3 and 9 o'clock, exclusive) is
 *     set on a reversed path so it reads left → right; at exactly 3 / 9 o'clock it may run sideways;
 *  3. the fill tip stays visible: a label on the fill ends one gap before the tip; a label on the track starts one
 *     gap after the tip's round cap and ends one gap before the start cap at 12 o'clock;
 *  4. on the fill only when the ink reaches 4.5 : 1 on that colour ([RingGeom.fillInkOk]); otherwise on the track;
 *  5. variants (e.g. "7,240 / 10,000" → "7.2k / 10k" → "7.2k") are tried in order; the first that fits wins;
 *  6. stroke too thin for a ≥ [MIN_SP] sp label, or a label that fits nowhere → BESIDE mode for the whole set
 *     (the same numbers beside the ring), never a cramped or overlapping label.
 * Rings are concentric with distinct radii, so labels on different rings can never overlap.
 */

/** One ring: centre radius [radius], stroke [stroke], progress [progress] (1 = goal, > 1 second lap). */
data class RingGeom(
    val radius: Float,
    val stroke: Float,
    val progress: Float,
    /** Ink reaches 4.5 : 1 on the first-lap fill colour. */
    val fillInkOk: Boolean = true,
    /** Ink reaches 4.5 : 1 on the second-lap colour. */
    val lapInkOk: Boolean = true,
)

enum class RingLabelMode { INTEGRATED, BESIDE, NONE }

/** Where a label sits: what is under the text. */
enum class LabelGround { FILL, LAP, TRACK }

/**
 * A label set inside ring [ring]'s stroke. Angles in degrees clockwise from 12 o'clock; the text occupies
 * [startDeg, startDeg + sweepDeg] (startDeg may be negative around 12 o'clock). [flipped] = set on the reversed path
 * (lower half), so it reads from the END angle towards the start.
 */
data class ArcLabel(
    val ring: Int,
    val variant: Int,
    val fontSize: Float,
    val width: Float,
    val startDeg: Float,
    val sweepDeg: Float,
    val flipped: Boolean,
    val ground: LabelGround,
    val radius: Float,
) {
    val endDeg: Float get() = startDeg + sweepDeg
    /** Centre angle normalised to [0, 360). */
    val centerDeg: Float get() = RingLabelLayout.norm(startDeg + sweepDeg / 2f)
    /** Angle at which reading starts (left end of the text as seen upright). */
    val readStartDeg: Float get() = if (flipped) endDeg else startDeg
}

data class RingLabelPlan(val mode: RingLabelMode, val labels: List<ArcLabel?>, val fontSize: Float)

object RingLabelLayout {
    /** Minimum label size (sp) on widgets and in the app (owner: ≥ 10 sp). */
    const val MIN_SP = 10f
    /** Largest label size (sp), hero rings. */
    const val MAX_SP = 15f
    /** Label size as a share of the stroke. Inter cap height 0.727 em → caps fill ≈ 38 % of the stroke. */
    const val SIZE_RATIO = 0.52f
    /** Inter cap height (em). */
    const val CAP = 0.727f
    /** Clear gap between a label and a cap or the tip, as a share of the stroke. */
    const val GAP_RATIO = 0.30f

    /** Label font size for [stroke] (canvas units), rounded to 0.5 dp. */
    fun fontSize(stroke: Float, unit: Float = 1f, maxSp: Float = MAX_SP): Float {
        val dp = stroke / unit * SIZE_RATIO
        return (min(dp, maxSp) * 2f).roundToInt() / 2f * unit
    }

    /** Is the stroke thick enough for integrated labels of at least [minSp]? */
    fun canIntegrate(stroke: Float, unit: Float = 1f, minSp: Float = MIN_SP): Boolean = fontSize(stroke, unit) >= minSp * unit - 1e-3f

    /** Thinnest stroke (dp) that still carries integrated labels at [minSp]. */
    fun minStrokeDp(minSp: Float = MIN_SP): Float = (minSp - 0.25f) / SIZE_RATIO

    /** Tip angle (deg clockwise from 12) of progress [p]: the first-lap tip below 1, the lap tip above; 360 when closed. */
    fun tipDeg(p: Float): Float = when {
        p <= 0f -> 0f
        p < 1f -> 360f * p
        p >= 2f -> 360f
        else -> 360f * ((p - 1f) - floor(p - 1f))
    }

    fun norm(a: Float): Float { var x = a % 360f; if (x < 0f) x += 360f; return x }

    /** Lower half (exclusive of 3 and 9 o'clock): text there is set on the reversed path. */
    fun flips(centerDeg: Float): Boolean { val c = norm(centerDeg); return c > 90.5f && c < 269.5f }

    private fun deg(len: Float, r: Float): Float = Math.toDegrees((len / r).toDouble()).toFloat()

    /**
     * Places one label per ring. [widths]`[i]` = the label's variant widths at [fontSize] (canvas units, check mark
     * included), or null for no label on ring i. Returns BESIDE when the stroke is too thin or any label fits nowhere.
     */
    fun layout(rings: List<RingGeom>, widths: List<FloatArray?>, unit: Float = 1f, minSp: Float = MIN_SP): RingLabelPlan {
        if (rings.isEmpty() || widths.all { it == null }) return RingLabelPlan(RingLabelMode.NONE, rings.map { null }, 0f)
        val s = rings.minOf { it.stroke }
        val fs = fontSize(s, unit)
        if (!canIntegrate(s, unit, minSp)) return RingLabelPlan(RingLabelMode.BESIDE, rings.map { null }, fs)
        val out = rings.indices.map { i ->
            val w = widths.getOrNull(i) ?: return@map null
            place(i, rings[i], w, fs) ?: return RingLabelPlan(RingLabelMode.BESIDE, rings.map { null }, fs)
        }
        return RingLabelPlan(RingLabelMode.INTEGRATED, out, fs)
    }

    /** First variant of [widths] that fits on ring [g] (null: none fits). */
    /** Flip boundaries (3 and 9 o'clock, and their copies around 12 o'clock for negative starts). */
    private val BOUNDS = floatArrayOf(-270f, -90f, 90f, 270f, 450f, 630f)

    /** Does a label over [start, start + span] cross 3 or 9 o'clock (where the reading direction flips)? */
    fun crossesFlip(start: Float, span: Float): Boolean = BOUNDS.any { b -> start < b - 0.5f && start + span > b + 0.5f }

    /**
     * V2_VISUAL_QA #4: the whole label lies in ONE half, so no part is ever upside down. [s0] = preferred start; the
     * label may slide within [lo, hi] (its end ≤ hi). Returns the nearest start that crosses no flip boundary, or null.
     */
    private fun slide(s0: Float, span: Float, lo: Float, hi: Float): Float? {
        if (s0 < lo - 1e-3f || s0 + span > hi + 1e-3f) return null
        if (!crossesFlip(s0, span)) return s0
        val cands = BOUNDS.flatMap { b -> listOf(b + 0.6f, b - span - 0.6f) }
            .filter { it >= lo - 1e-3f && it + span <= hi + 1e-3f && !crossesFlip(it, span) }
        return cands.minByOrNull { abs(it - s0) }
    }

    /** Label widths are tried shortest-first when the full form would run over [MAX_FULL_SPAN] degrees (QA #4b). */
    const val MAX_FULL_SPAN = 70f

    /** First variant of [widths] that fits on ring [g] (null: none fits). */
    fun place(i: Int, g: RingGeom, widths: FloatArray, fs: Float): ArcLabel? {
        val r = g.radius; val s = g.stroke
        if (r <= s / 2f + 1f) return null
        val gap = deg(s * GAP_RATIO, r)
        val cap = deg(s / 2f, r)
        val p = g.progress
        // prefer a compact form when the full one would wrap a long arc ("2,007,226 / 2,190,000" → "2.0M / 2.2M")
        val order = widths.indices.toMutableList()
        if (widths.size > 1 && deg(widths[0], r) > MAX_FULL_SPAN) { order.remove(0); order.add(1.coerceAtMost(order.size), 0) }
        for (v in order) {
            val w = widths[v]
            val span = deg(w, r)
            if (span > 300f) continue
            fun mk(start: Float, ground: LabelGround) = ArcLabel(i, v, fs, w, start, span, flips(start + span / 2f), ground, r)
            val trackEnd = 360f - cap - gap
            fun at(s0: Float, lo: Float, hi: Float, ground: LabelGround): ArcLabel? = slide(s0, span, lo, hi)?.let { mk(it, ground) }
            val l: ArcLabel? = when {
                p <= 0.002f -> at(gap, gap, trackEnd, LabelGround.TRACK)
                p < 1f -> {
                    val tip = 360f * p
                    val fillHi = tip - gap; val fillLo = gap * 0.5f
                    val trackLo = tip + cap + gap
                    // on the fill ending one gap before the tip; else on the track after the tip cap; else the fill anyway
                    (if (g.fillInkOk) at(fillHi - span, fillLo, fillHi, LabelGround.FILL) else null)
                        ?: at(trackLo, trackLo, trackEnd, LabelGround.TRACK)
                        ?: at(fillHi - span, fillLo, fillHi, LabelGround.FILL)
                }
                else -> {
                    val lapP = p - 1f
                    if (lapP <= 0.004f || p >= 2f) {
                        // closed ring, no visible tip: centred at 12 o'clock (crosses no flip boundary)
                        mk(-span / 2f, if (p >= 2f) LabelGround.LAP else LabelGround.FILL)
                    } else {
                        val tipL = 360f * lapP
                        val lapHi = tipL - gap; val lapLo = gap * 0.5f
                        val afterLo = tipL + cap + gap
                        (if (g.lapInkOk) at(lapHi - span, lapLo, lapHi, LabelGround.LAP) else null)
                            ?: (if (g.fillInkOk) at(afterLo, afterLo, trackEnd, LabelGround.FILL) else null)
                            ?: at(lapHi - span, lapLo, lapHi, LabelGround.LAP)
                            ?: at(afterLo, afterLo, trackEnd, LabelGround.FILL)
                    }
                }
            }
            if (l != null) return l
        }
        return null
    }

    // ------------------------------------------------------------------------------------------- invariants (V2-093)

    /**
     * Checks the G0 rules for a placed label; returns the broken rule or null. Used by the JVM tests and the
     * contact sheet: upright, inside the annulus, clear of the tip / start cap, not wider than the ring allows.
     */
    fun violation(l: ArcLabel, g: RingGeom, minSp: Float, unit: Float = 1f): String? {
        if (l.fontSize < minSp * unit - 1e-3f) return "font ${l.fontSize / unit} < $minSp sp"
        if (l.fontSize * CAP > g.stroke) return "caps taller than the stroke"
        if (flips(l.centerDeg) != l.flipped) return "upside down"
        if (crossesFlip(l.startDeg, l.sweepDeg)) return "crosses 3 / 9 o'clock (part upside down)"
        if (l.sweepDeg <= 0f || l.sweepDeg > 300f) return "bad span"
        val gap = deg(g.stroke * GAP_RATIO, g.radius) * 0.99f
        val cap = deg(g.stroke / 2f, g.radius)
        val p = g.progress
        val tip = tipDeg(p)
        val hasTip = (p > 0.002f && p < 1f) || (p > 1.004f && p < 2f)
        fun angDist(a: Float, b: Float): Float { val d = abs(norm(a) - norm(b)); return min(d, 360f - d) }
        if (hasTip) {
            // the tip (centre of its round cap) must not be inside the text span, and the gap must hold
            val inside = norm(tip - l.startDeg) < l.sweepDeg
            if (inside) return "covers the tip"
            val dEnd = angDist(l.endDeg, tip); val dStart = angDist(l.startDeg, tip)
            if (l.ground == LabelGround.TRACK || (l.ground == LabelGround.FILL && p > 1f)) { if (dStart < cap + gap) return "on the tip cap" }
            else if (dEnd < gap) return "on the tip"
        }
        return null
    }
}
