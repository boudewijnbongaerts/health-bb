package com.boudhealth.render

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import com.boudhealth.data.ExerciseTypes
import com.boudhealth.data.Workout
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/*
 * v4 mini primitives (design4/SMALL_TILES.md, WEEKLY_SPEC.md §4.1) for the widget Canvas, the same shapes as the app's
 * ui/home/MiniKit.kt. All in dp on the pre-scaled canvas. One implementation each, used by many tiles.
 */

/** Rounded rect filled with a horizontal (or vertical) c0 → c1 gradient. */
internal fun Ink.gradRect(l: Float, top: Float, r: Float, b: Float, rad: Float, c0: Int, c1: Int, vertical: Boolean = false) {
    if (r <= l || b <= top) return
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = if (vertical) LinearGradient(0f, b, 0f, top, c0, c1, Shader.TileMode.CLAMP)
        else LinearGradient(l, 0f, r, 0f, c0, c1, Shader.TileMode.CLAMP)
    }
    c.drawRoundRect(l, top, r, b, rad, rad, p)
}

/**
 * mini7: 7 day bars (3 dp wide, gap 2), right-aligned in the box, max height [h]. The last bar is today in [col] at 100 %,
 * the others at 45 %; missing days are a 1.5 dp stub in tileHi. [goal] = 1 dp line in text @ 35 %.
 */
internal fun Ink.mini7(vals: List<Double?>, x: Float, y: Float, w: Float, h: Float, col: Int, goal: Double? = null, barW: Float = 3f, gap: Float = 2f) {
    val n = vals.size
    if (n == 0 || h <= 2f) return
    var bw = barW; var g = gap
    val need = n * bw + (n - 1) * g
    if (need > w) { val k = w / need; bw *= k; g *= k }
    val total = n * bw + (n - 1) * g
    val x0 = x + w - total
    val top = max(vals.filterNotNull().maxOrNull() ?: 0.0, goal ?: 0.0).takeIf { it > 0 } ?: 1.0
    vals.forEachIndexed { i, v ->
        val bx = x0 + i * (bw + g)
        if (v == null || v <= 0.0) { rrect(bx, y + h - 1.5f, bx + bw, y + h, bw / 2f, t.tileHi); return@forEachIndexed }
        val bh = max(1.5f, h * (min(v, top) / top).toFloat())
        rrect(bx, y + h - bh, bx + bw, y + h, bw / 2f, if (i == n - 1) col else col.alpha(0.45f))
    }
    if (goal != null && goal > 0) {
        val gy = y + h - h * (goal / top).toFloat()
        line(x0 - 1f, gy, x0 + total + 1f, gy, 1f, t.text.alpha(0.35f))
    }
}

/** deltaChip: "▲12 % vs last wk", h 18, 10.5/700 on goodCont / badCont / tile. Returns its width. */
internal fun Ink.deltaChip(label: String, tone: Ink.Tone, dir: Ink.Dir, x: Float, top: Float, maxW: Float = Float.MAX_VALUE): Float {
    val (bg, fg) = when (tone) {
        Ink.Tone.GOOD -> t.goodCont to t.good
        Ink.Tone.BAD -> t.badCont to t.bad
        Ink.Tone.NEUTRAL -> t.tile to t.muted
    }
    val arrow = when (dir) { Ink.Dir.UP -> "▲"; Ink.Dir.DOWN -> "▼"; Ink.Dir.NONE -> "" }
    val s = fit(arrow + label, maxW - 14f, 10.5f, 700)
    val w = measure(s, 10.5f, 700) + 14f
    rrect(x, top, x + w, top + 18f, 9f, bg)
    text(s, x + 7f, centerBase(top, 18f, 10.5f, 700), 10.5f, fg, 700)
    return w
}

/** bandTick: a pill on [lo]..[hi] with the personal normal [band] in [col] @ 20 % and a 2 dp tick at [v]. */
internal fun Ink.bandTick(x: Float, y: Float, w: Float, h: Float, lo: Double, hi: Double, band: Pair<Double, Double>?, v: Double, col: Int) {
    fun X(d: Double) = x + ((d - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f) * w
    rrect(x, y, x + w, y + h, h / 2f, t.tileHi)
    band?.let { (a, b) -> rrect(X(a), y, max(X(a) + h, X(b)), y + h, h / 2f, col.alpha(if (t.dark) 0.45f else 0.35f)) }
    val tx = X(v)
    line(tx, y - 2.5f, tx, y + h + 2.5f, 2f, t.text)
}

/**
 * Stacked pill (calories split, zones, stages): segments ∝ value with 1 dp gaps; indices in [hatched] are drawn as
 * diagonal hatching in their colour (= estimated / calculated, P-KCAL).
 */
internal fun Ink.segPill(parts: List<Pair<Double, Int>>, x: Float, y: Float, w: Float, h: Float, hatched: Set<Int> = emptySet()) {
    val tot = parts.sumOf { max(0.0, it.first) }
    rrect(x, y, x + w, y + h, h / 2f, t.tileHi)
    if (tot <= 0) return
    c.save()
    c.clipPath(Path().apply { addRoundRect(RectF(x, y, x + w, y + h), h / 2f, h / 2f, Path.Direction.CW) })
    var cx = x
    parts.forEachIndexed { i, (v, col) ->
        if (v <= 0) return@forEachIndexed
        val sw = w * (v / tot).toFloat()
        val r = min(x + w, cx + sw) - if (cx + sw < x + w - 0.5f) 1f else 0f
        if (i in hatched) {
            rect(cx, y, r, y + h, col.alpha(0.28f))
            c.save()
            c.clipRect(cx, y, r, y + h)
            var hx = cx - h
            while (hx < r) { line(hx, y + h, hx + h, y, 1.2f, col); hx += 3.5f }
            c.restore()
        } else rect(cx, y, r, y + h, col)
        cx += sw
    }
    c.restore()
}

/** Day dots: filled = [col], empty = tileHi, [today] ringed in text, [future] (index ≥) faded. */
internal fun Ink.dayDots(filled: List<Boolean>, x: Float, y: Float, d: Float, gap: Float, col: Int, today: Int? = null, future: Int? = null) {
    filled.forEachIndexed { i, f ->
        val cx = x + i * (d + gap) + d / 2f; val cy = y + d / 2f
        val fade = future != null && i >= future
        circle(cx, cy, d / 2f, if (f) col else if (fade) t.tileHi.alpha(0.5f) else t.tileHi)
        if (i == today) ringOutline(cx, cy, d / 2f + 1.2f, 1.2f, t.text)
    }
}

/** One-day timeline 06–24 h (h 6): track tileHi, each workout a segment in its category colour at its real time. */
internal fun Ink.workoutTimeline(ws: List<Workout>, x: Float, y: Float, w: Float, h: Float, zone: ZoneId) {
    rrect(x, y, x + w, y + h, h / 2f, t.tileHi)
    fun X(ms: Long): Float {
        val lt = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
        val hours = lt.hour + lt.minute / 60f
        return x + ((hours - 6f) / 18f).coerceIn(0f, 1f) * w
    }
    ws.forEach { wk ->
        val a = X(wk.start); val b = max(a + h, X(wk.end))
        rrect(a, y, min(x + w, b), y + h, h / 2f, Hue.workoutGroup(ExerciseTypes.group(wk)))
    }
}
