package com.boudhealth.render.ring

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.boudhealth.render.Ink
import com.boudhealth.render.alpha
import com.boudhealth.ui.components.Glyph
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * V2.0 tile painters (V2-094 … V2-107): pure drawing from small data models, so the SAME code draws the app tiles
 * (D11, via render/Tiles) and the widgets, and the JVM harness renders it from the fixture. Geometry = the approved
 * drafts (design6/tiles_*.png): S 98 × 98 (pad 12, glyph circle 26, value 21 sp at h − 31, mini name 11.5 sp at h − 13),
 * M 204 × 98 (pad 14), L 204 × 204 (pad 16), XL 412 × 204 (pad 18). All units dp (pre-scaled canvas).
 * Data adapters (TileSource / V2 → these models) live in render/V4Tiles.kt and render/Tiles.kt.
 */

enum class TileSz { S, M, L, XL }

/** One day of heart zones: minutes per zone and the day's heart points (null day = future). */
data class ZoneDay(val fatBurn: Double, val cardio: Double, val peak: Double, val points: Double) {
    val minutes: Double get() = fatBurn + cardio + peak
}

/** Heart points family (zone_min): today vs the daily share, the week vs the weekly target, Mon..Sun zone days. */
data class HeartPointsData(
    val today: Double, val dayTarget: Double, val week: Double, val weekTarget: Double,
    val days: List<ZoneDay?>, val letters: List<String>, val todayIndex: Int,
    /** Restored (audit 1.1): the week's pace vs the weekly target, and last week's points up to the same day. */
    val pace: PaceMark? = null,
    val lastWeekSoFar: Double? = null,
)

/** Pace status of a goal (data/Pace → CardioPace): icons ✓ ▲ ● ▼ (V2_TEXTS: done · ahead · on track · behind). */
enum class PaceMark(val word: String) { DONE("Done"), AHEAD("Ahead"), ON_TRACK("On track"), BEHIND("Behind") }

/** Heart zones tile: today's minutes per zone + the week's days. */
data class ZonesData(val today: ZoneDay, val days: List<ZoneDay?>, val letters: List<String>, val todayIndex: Int)

/** One weekly goal line (Workout minutes · Steps · Heart points). */
data class GoalLine(
    val name: String, val value: Double, val target: Double, val label: RingLabel, val color: Int,
    /** Restored (audit 1.4, owner "don't get rid of those"): where you should be by now, and the pace status. */
    val expected: Double? = null,
    val pace: PaceMark? = null,
) {
    val progress: Float get() = if (target <= 0) 0f else (value / target).toFloat()
    val met: Boolean get() = target > 0 && value >= target
}

data class WeeklyGoalsData(val goals: List<GoalLine>, val rangeText: String, val daysLeft: Int) {
    val met: Int get() = goals.count { it.met }
}

data class StreakData(val current: Int, val best: Int, val week: List<Boolean?>, val workoutDays: Int, val goal: Int,
                      val letters: List<String>, val todayIndex: Int)

data class WorkoutKind(val name: String, val glyph: Glyph, val color: Int, val count: Int)

data class WorkoutsWeekData(val sessions: Int, val minutes: Int, val perDay: List<Double?>, val kinds: List<WorkoutKind>)

data class WorkoutRow(val title: String, val glyph: Glyph, val color: Int, val whenText: String, val minutes: Int, val avgHr: Int?)

data class SleepSeg(val stage: String, val startMs: Long, val endMs: Long)

data class SleepData(val asleepMin: Int, val segs: List<SleepSeg>, val totals: List<Pair<String, Int>>, val meta: String,
                     val startText: String, val endText: String)

data class BmiData(val bmi: Double, val heightCm: Int?, val weightKg: Double?)

object TileArt {
    const val WORKOUT_COL = 0
    const val STEPS_COL = 1
    const val HEART_COL = 2

    private fun n0(v: Double) = RingLabel.grouped(v)
    private fun n1(v: Double) = String.format(java.util.Locale.US, "%.1f", v)

    // ------------------------------------------------------------------------------------------------ shared

    /** Ring colour of slot [i] in this theme (dark: bright end; light: ≥ 3 : 1 start). */
    fun slot(k: Ink, i: Int): Int = k.ringColors().fill(i)

    /**
     * Header for M/L/XL: small glyph circle + name (+ meta right). Same vertical metrics as Ink.labelRow ([top] = row
     * top = p − 1; circle centre top + 9, baseline top + 9 + 0.36 fs), so header and dot titles share one baseline.
     */
    fun header(k: Ink, g: Glyph, col: Int, x: Float, top: Float, right: Float, name: String, meta: String? = null, fs: Float = 13f) {
        k.glyphCircle(g, col, x - 2f, top - 2f, 22f, 13f)
        val base = top + 9f + fs * 0.36f
        val nw = k.text(k.fit(name, right - (x + 26f) + 6f, fs, 600), x + 26f, base, fs, k.t.text, 600)   // 6 dp of the padding allowed
        // the right-aligned meta only where it clears the name (narrow tiles drop it, never overlap)
        if (meta != null && k.measure(meta, 11f, 500) <= right - (x + 26f + nw + 2f)) k.text(meta, right, base, 11f, k.t.muted, 500, Paint.Align.RIGHT)
    }

    /**
     * Every S tile (V2-094): glyph circle top-left, optional corner visual top-right (box 40 × 26), value (+ unit, or
     * "/ target" muted), mini name. Nothing else.
     */
    fun small(k: Ink, w: Float, h: Float, g: Glyph, col: Int, value: String, unit: String?, name: String, target: String? = null,
              valueColor: Int = k.t.text, short: String? = null, corner: ((Float, Float, Float, Float) -> Unit)? = null) {
        val t = k.t; val p = 12f
        k.glyphCircle(g, col, p, p)
        // the corner box starts ≥ 6 dp right of the glyph circle (narrow cells: the corner art scales to what is left)
        val cx = max(w - p - 40f, p + 26f + 6f)
        if (w - p - cx >= 8f) corner?.invoke(cx, p, w - p - cx, 26f)   // QA_FINAL: never a negative-width corner box
        // never an ellipsis on a number: step the size down (21 → 16 sp) until value (+ unit / target) fits
        var u = if (target == null) unit else null
        var fs = 21f
        fun need() = k.valueWidth(value, u, fs, 11f) + (target?.let { k.measure(" / $it", 13f, 600) } ?: 0f)
        while (need() > w - 2 * p && fs > 16f) fs -= 0.5f
        // still running into the tile edge at 16 sp (narrowest cells): the unit goes first, then the number may go down to 13 sp
        if (need() > w - p - 4f && u != null) { u = null; fs = 21f; while (need() > w - 2 * p && fs > 16f) fs -= 0.5f }
        while (need() > w - p - 4f && fs > 13f) fs -= 0.5f
        val vw = k.value(value, u, p, h - 31f, fs, 11f, valueColor)
        if (target != null) k.text(" / $target", p + vw, h - 31f, 13f, t.muted, 600)
        // item 44: the approved short name ("Zones", "Goals" …) only where the full name doesn't fit
        val nm = if (short == null || k.measure(name, 11f, 500) <= w - 2 * p) name else short
        val nfs = if (k.measure(nm, 11.5f, 500) <= w - 2 * p) 11.5f else 11f
        k.text(k.fit(nm, w - 2 * p, nfs, 500), p, h - 13f, nfs, t.muted, 500)
    }

    /** S-tile corner ring (26 dp, stroke 4). No ✓ (owner, 3 Nov): past the goal the lap is the cue. */
    fun cornerRing(k: Ink, x: Float, y: Float, p: Float, col: Int) {
        k.ring(x, y, 26f, p, col, 3.5f)
    }

    /** "value / target" with the target muted; returns width. */
    fun ratio(k: Ink, v: String, tgt: String, x: Float, base: Float, fs: Float, align: Paint.Align = Paint.Align.LEFT, col: Int = k.t.text): Float {
        val a = k.measure(v, fs, 700); val b = k.measure(" / $tgt", fs * 0.86f, 500)
        val x0 = when (align) { Paint.Align.LEFT -> x; Paint.Align.RIGHT -> x - a - b; Paint.Align.CENTER -> x - (a + b) / 2f }
        k.text(v, x0, base, fs, col, 700)
        k.text(" / $tgt", x0 + a, base, fs * 0.86f, k.t.muted, 500)
        return a + b
    }

    /** Thin goal bar: track = tileHi, solid fill. */
    fun bar(k: Ink, x: Float, y: Float, w: Float, h: Float, p: Float, col: Int) = RingPaint.bar(k.c, x, y, w, h, p, col, k.t.tileHi)

    /** One ring of any colour with an integrated "value / target" (stroke ≥ 18.8 dp) — XL tiles. */
    fun labelledRing(k: Ink, x: Float, y: Float, d: Float, p: Float, col: Int, label: RingLabel?, s: Float = RingPaint.strokeFor(d)) {
        RingPaint.triple(k.c, x, y, d, floatArrayOf(p), label?.let { listOf(it) }, k.ringColors().single(col), s, 0f)
    }

    // ------------------------------------------------------------------------------------------------ Today rings

    /**
     * Today rings (V2-106): S rings + "Today" (no numbers: no room for 10 sp); M rings + numbers beside; L rings with
     * integrated numbers, no labels under the rings; XL integrated rings + the last 7 days as mini rings, day letters
     * centred under each circle. [week] = 7 progress arrays oldest first (XL only), [letters] their day letters.
     */
    fun today(k: Ink, sz: TileSz, w: Float, h: Float, progress: FloatArray, labels: List<RingLabel?>, time: String? = null,
              week: List<FloatArray> = emptyList(), letters: List<String> = emptyList(), col: RingColors = k.ringColors()) {
        val t = k.t
        when (sz) {
            TileSz.S -> {
                val d = 60f
                RingPaint.rings(k.c, (w - d) / 2f, 9f, d, progress, col, 8f, 2f)
                k.text("Today", w / 2f, h - 13f, 11.5f, col.muted, 600, Paint.Align.CENTER)
            }
            TileSz.M -> {
                val p = 10f; val d = h - 2 * p
                val plan = RingPaint.triple(k.c, p, p, d, progress, labels, col, RingPaint.strokeFor(d), 2f)
                if (plan.mode != RingLabelMode.INTEGRATED) {
                    val x = p + d + 12f
                    RingPaint.legend(k.c, x, (h - 3 * 19f) / 2f, w - x - 8f, labels, progress, col)
                }
            }
            TileSz.L -> {
                val d0 = min(w, h) - 24f
                if (RingLabelLayout.canIntegrate(RingPaint.strokeFor(d0))) {
                    val plan = RingPaint.triple(k.c, (w - d0) / 2f, (h - d0) / 2f, d0, progress, labels, col)
                    if (plan.mode == RingLabelMode.INTEGRATED) return
                }
                // thin rings (small cells): the numbers go BESIDE or UNDER the rings, never on top of them
                val legendW = 104f
                if (w - 24f - d0 - 12f >= legendW) {
                    val d = d0; val x0 = (w - d - 12f - legendW) / 2f
                    RingPaint.rings(k.c, x0, (h - d) / 2f, d, progress, col, RingPaint.strokeFor(d), 2f)
                    RingPaint.legend(k.c, x0 + d + 12f, (h - 3 * 17f) / 2f, legendW, labels, progress, col, 11f, 17f, withUnit = false)
                } else {
                    val pitch = 15f; val legendH = 3 * pitch
                    val d = min(w - 24f, h - 12f - 8f - legendH - 6f)
                    RingPaint.rings(k.c, (w - d) / 2f, 10f, d, progress, col, RingPaint.strokeFor(d), 2f)
                    RingPaint.legend(k.c, 12f, 10f + d + 8f, w - 24f, labels, progress, col, 10.5f, pitch, withUnit = false)
                }
            }
            TileSz.XL -> {
                val p = 18f
                val d = min(h - 2 * p, w * 0.42f)
                val plan = RingPaint.triple(k.c, p, (h - d) / 2f, d, progress, labels, col)
                val sx = p + d + 16f; val right = w - p
                k.text("Today", sx, p + 10f, 12.5f, t.text, 600)
                if (time != null) k.text(time, right, p + 10f, 11f, t.muted, 400, Paint.Align.RIGHT)
                // thin rings (small cells): the numbers are not in the rings → the lines carry them
                if (plan.mode == RingLabelMode.BESIDE) RingPaint.legend(k.c, sx, p + 18f, right - sx, labels, progress, col, 11f, 16f)
                // ring names only (the numbers are in the rings; no duplicate lines)
                else labels.forEachIndexed { i, l ->
                    val b = p + 38f + i * 18f
                    k.circle(sx + 3.5f, b - 4f, 3.5f, col.fill(i))
                    k.text(k.fit(l?.name ?: "", right - sx - 12f, 11.5f, 600), sx + 12f, b, 11.5f, t.text, 600)
                }
                if (week.isNotEmpty()) {
                    val cw = (right - sx) / week.size
                    val md = min(24f, cw - 4f)
                    // below the lines (BESIDE: 3 × 16 dp from p + 18), never on them
                    val top = max(h - p - md - 22f, if (plan.mode == RingLabelMode.BESIDE) p + 18f + 48f + 4f else 0f)
                    week.forEachIndexed { i, ps ->
                        val cx = sx + cw * i + cw / 2f
                        RingPaint.rings(k.c, cx - md / 2f, top, md, ps, col, max(2f, md * 0.13f), 1.5f)
                        // day letter centred in its own circle under the ring (today = accent pill)
                        k.dayLetter(letters.getOrElse(i) { "" }, cx, top + md + 4f + 8.5f + 4f, today = i == week.size - 1)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ zone bars (V2-104)

    fun zoneCols(k: Ink) = intArrayOf(k.t.zones[1], k.t.zones[2], k.t.zones[3])

    /**
     * Stacked heart-zone bars per day (fat burn · cardio · peak, bottom → top) on a plain tileHi track, no outlines and
     * no "last week" ghost bars (V2-104); points above the bars when [totals]; day letters under them.
     */
    fun zoneStack(k: Ink, days: List<ZoneDay?>, letters: List<String>?, todayIndex: Int, x: Float, y: Float, w: Float, h: Float,
                  totals: Boolean, bw: Float = 10f) {
        val zc = zoneCols(k)
        val stacks = days.map { d -> d?.let { listOf(it.fatBurn to zc[0], it.cardio to zc[1], it.peak to zc[2]) } }
        val ph = h - if (letters != null) 18f else 0f
        // totals: room for the day's points above its own bar (label ≈ 12 dp), never on the fill
        val mx = (days.maxOfOrNull { it?.minutes ?: 0.0 } ?: 0.0).coerceAtLeast(10.0) * if (totals && ph > 24f) max(1.1, ph / (ph - 12.0)) else 1.1
        k.stackBars(stacks, x, y, w, h, mx, letters, todayIndex, bw, totals = false, track = k.t.tileHi)
        if (!totals) return
        val slot = w / days.size
        days.forEachIndexed { i, d ->
            d ?: return@forEachIndexed
            if (d.points <= 0) return@forEachIndexed
            val cx = x + slot * i + slot / 2f
            val top = y + ph - max(min(bw, slot * 0.56f), ph * (d.minutes / mx).toFloat())
            k.text(n0(d.points), cx, max(y + 8f, top - 4f), 9.5f, k.t.muted, 700, Paint.Align.CENTER)
        }
    }

    /** Three columns sized to their own text (dot + name, minutes under it), free space split evenly; never overlaps. */
    fun zoneLegend(k: Ink, x: Float, base: Float, w: Float, vals: List<Double>, fs0: Float = 11f, unit: String = "min") {
        val names = listOf("Fat burn", "Cardio", "Peak"); val zc = zoneCols(k)
        var fs = fs0
        fun widths() = names.indices.map { i -> 11f + max(k.measure(names[i], fs, 500), k.measure("${vals[i].roundToInt()} $unit", fs + 1.5f, 700)) }
        var ws = widths()
        while ((w - ws.sum()) / 2f < 10f && fs > 9.5f) { fs -= 0.5f; ws = widths() }
        // still overlapping at 9.5 sp (narrow tiles): keep stepping down to 8.5 sp until the columns clear each other
        while (w - ws.sum() < 4f && fs > 8.5f) { fs -= 0.5f; ws = widths() }
        val gap = (w - ws.sum()) / 2f
        var cx = x
        names.forEachIndexed { i, n ->
            k.circle(cx + 3.5f, base - 4f, 3.5f, zc[i])
            k.text(n, cx + 11f, base, fs, k.t.muted, 500)
            k.text("${vals[i].roundToInt()} $unit", cx + 11f, base + fs + 5f, fs + 1.5f, k.t.text, 700)
            cx += ws[i] + gap
        }
    }

    // ------------------------------------------------------------------------------------------------ Heart points family

    /** Heart points S / M / L / XL (V2-096 … V2-098, V2-103): own icon, "29 / 50" today, week look kept on M. */
    fun heartPoints(k: Ink, sz: TileSz, w: Float, h: Float, d: HeartPointsData) {
        val t = k.t
        val col = slot(k, HEART_COL)
        val today = n0(d.today); val dayT = n0(d.dayTarget)
        when (sz) {
            TileSz.S -> small(k, w, h, Glyph.HeartPlus, col, today, null, "Heart points", target = dayT, short = "Heart pts") { x, y, ww, _ ->
                cornerRing(k, x + ww - 26f, y, (d.today / d.dayTarget).toFloat(), col)
            }
            TileSz.M -> {
                val p = 14f
                header(k, Glyph.HeartPlus, col, p, p - 1f, w - p, "Heart points", "this week")
                // very short M (h < 72, cover screen): the ratio takes the "today" line instead of running into the header
                val short = h < 72f
                var rf = 22f   // narrow M: "29 / 50" steps down (≥ 14 sp) rather than run under the week bars
                while (k.measure(today, rf, 700) + k.measure(" / $dayT", rf * 0.86f, 500) > w - 2 * p - 64f - 6f && rf > 14f) rf -= 1f
                ratio(k, today, dayT, p, if (short) h - 15f else h - 34f, rf)
                val tw = if (short) 0f else k.text("today", p, h - 15f, 11.5f, t.muted, 500)
                val bw = 64f
                // restored: the week's pace, where it fits left of the bars
                d.pace?.let { m -> if (!short && p + tw + 8f + k.measure(m.word, 11f, 600) + 31f <= w - p - bw - 6f) paceChip(k, m, p + tw + 8f, h - 28f, 18f) }
                zoneStack(k, d.days, null, d.todayIndex, w - p - bw, p + 20f, bw, h - p - 20f - 2f, totals = false, bw = 6f)
            }
            TileSz.L -> {
                val p = 16f
                header(k, Glyph.HeartPlus, col, p, p - 1f, w - p, "Heart points")
                val tw0 = ratio(k, today, dayT, p, 64f, 30f)
                // narrow tiles: the right-aligned words go before anything overlaps
                if (p + tw0 + 2f + k.measure("today", 12f, 500) <= w - p) k.text("today", w - p, 64f, 12f, t.muted, 500, Paint.Align.RIGHT)
                val ww0 = ratio(k, n0(d.week), n0(d.weekTarget), w - p, 88f, 13f, Paint.Align.RIGHT)
                if (p + k.measure("This week", 12f, 500) + 2f <= w - p - ww0) k.text("This week", p, 88f, 12f, t.muted, 500)
                bar(k, p, 95f, w - 2 * p, 6f, (d.week / d.weekTarget).toFloat(), col)
                // restored: pace chip + "Last week N so far" (same day of last week)
                var top = 112f
                if (d.pace != null || d.lastWeekSoFar != null) {
                    val cw = d.pace?.let { paceChip(k, it, p, 106f, 18f) + 8f } ?: 0f
                    d.lastWeekSoFar?.let {
                        val room = w - 2 * p - cw
                        val full = "Last week ${n0(it)} so far"
                        val txt = if (k.measure(full, 11f, 500) <= room) full else "Last week ${n0(it)}"
                        // never "Last week…" (the number is the only fact): no room → the line goes
                        if (k.measure(txt, 11f, 500) <= room) k.text(txt, w - p, 119f, 11f, t.muted, 500, Paint.Align.RIGHT)
                    }
                    top = 130f
                }
                // short L (cover screen): no week bars when they would be squeezed under 30 dp
                if (h - top - p + 2f >= 30f) zoneStack(k, d.days, d.letters, d.todayIndex, p, top, w - 2 * p, h - top - p + 2f, totals = true)
            }
            TileSz.XL -> {
                // narrow XL (tall cells on 8 columns): the ring gives up width so the right column keeps its room
                val p = 18f; val dd = min(h - 2 * p, w * 0.42f); val ry = (h - dd) / 2f
                // a shrunk ring keeps a stroke thick enough for its "104 / 350" (never loses the number)
                val rs = if (dd < h - 2 * p) max(RingPaint.strokeFor(dd, 0.13f), kotlin.math.ceil(RingLabelLayout.minStrokeDp())) else RingPaint.strokeFor(dd, 0.13f)
                labelledRing(k, p, ry, dd, (d.week / d.weekTarget).toFloat(), col, RingLabel.of(d.week, d.weekTarget, "pts", "Heart points"), rs)
                k.glyph(Glyph.HeartPlus, p + dd / 2f - 16f, ry + dd / 2f - 22f, 32f, col, 2f)
                k.text("this week", p + dd / 2f, ry + dd / 2f + 24f, 11f, t.muted, 500, Paint.Align.CENTER)
                d.lastWeekSoFar?.let { k.text("last week ${n0(it)}", p + dd / 2f, ry + dd / 2f + 39f, 10f, t.muted, 500, Paint.Align.CENTER) }
                val x = p + dd + 20f; val rw = w - x - p
                // the chip goes icon-only (▼) before the title would be cut
                val iconOnly = d.pace != null && k.measure("Heart points", 14f, 600) > rw - (paceChipWidth(k, d.pace) + 6f)
                val cw = d.pace?.let { paceChip(k, it, w - p, p - 4f, 20f, alignRight = true, iconOnly = iconOnly) + 6f } ?: 0f
                k.text(k.fit("Heart points", rw - cw, 14f, 600), x, p + 9f, 14f, t.text, 600)
                k.text("Today", x, p + 34f, 11.5f, t.muted, 500)
                val tw0 = ratio(k, today, dayT, x + 40f, p + 34f, 15f)
                if (x + 40f + tw0 + 2f + k.measure("This week", 11.5f, 500) <= x + rw) k.text("This week", x + rw, p + 34f, 11.5f, t.muted, 500, Paint.Align.RIGHT)
                // short XL (cover screen): the bars end above the zone legend
                val sh = min(92f, h - 2 * p - 76f)
                zoneStack(k, d.days, d.letters, d.todayIndex, x, p + 46f, rw, sh, totals = sh >= 40f, bw = 14f)
                val wk = d.days.filterNotNull()
                zoneLegend(k, x, h - p - 16f, rw, listOf(wk.sumOf { it.fatBurn }, wk.sumOf { it.cardio }, wk.sumOf { it.peak }))
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ Heart zones

    /** Heart zones (V2-100): M today (segment bar + 3 columns), L today + week bars; S total; XL = L wide. */
    fun heartZones(k: Ink, sz: TileSz, w: Float, h: Float, d: ZonesData) {
        val t = k.t; val zc = zoneCols(k)
        val v = listOf(d.today.fatBurn, d.today.cardio, d.today.peak)
        val parts = v.mapIndexed { i, m -> m to zc[i] }
        when (sz) {
            TileSz.S -> small(k, w, h, Glyph.Pulse, t.zones[2], n0(d.today.minutes), "min", "Heart zones", short = "Zones") { x, y, ww, _ ->
                k.segmentBar(parts, x, y + 10f, ww, 6f)
            }
            TileSz.M -> {
                val p = 14f
                header(k, Glyph.Pulse, t.zones[2], p, p - 1f, w - p, "Heart zones", "today")
                // short M (cover screen): the legend's minutes stay inside the tile; the bar goes when there's no room
                val base = min(62f, h - 22f); val by = min(36f, base - 18f)
                if (by >= 30f) k.segmentBar(parts, p, by, w - 2 * p, 7f)
                zoneLegend(k, p, base, w - 2 * p, v)
            }
            TileSz.L, TileSz.XL -> {
                val p = if (sz == TileSz.XL) 18f else 16f
                val lw = if (sz == TileSz.XL) min(w * 0.48f, 200f) else w - 2 * p
                header(k, Glyph.Pulse, t.zones[2], p, p - 1f, p + lw, "Heart zones")
                val vw = k.value(n0(d.today.minutes), "min in zones", p, 62f, 30f, 13f)
                if (p + vw + 2f + k.measure("today", 12f, 500) <= p + lw) k.text("today", p + lw, 62f, 12f, t.muted, 500, Paint.Align.RIGHT)
                k.segmentBar(parts, p, 74f, lw, 8f)
                zoneLegend(k, p, 102f, lw, v)
                // short L (cover screen): no room for the week bars → today only
                if (sz == TileSz.L && h - 146f - p + 4f < 30f) return
                if (sz == TileSz.L) {
                    k.text("This week", p, 140f, 11.5f, t.muted, 500)
                    zoneStack(k, d.days, d.letters, d.todayIndex, p, 146f, w - 2 * p, h - 146f - p + 4f, totals = false, bw = 10f)
                } else {
                    val x = p + lw + 24f
                    k.text("This week", x, p + 9f, 11.5f, t.muted, 500)
                    zoneStack(k, d.days, d.letters, d.todayIndex, x, p + 20f, w - x - p, h - 2 * p - 20f, totals = true, bw = 14f)
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ Weekly goals

    private fun goalProgress(d: WeeklyGoalsData) = FloatArray(d.goals.size) { d.goals[it].progress }

    /** The kept week lines: dot · name · "value / target" · ✓ · bar. [compact] = no name (M). */
    /** Colour of a pace mark: ahead / done = good, on track = muted, behind = bad. */
    fun paceColor(k: Ink, m: PaceMark): Int = when (m) { PaceMark.DONE, PaceMark.AHEAD -> k.t.good; PaceMark.ON_TRACK -> k.t.muted; PaceMark.BEHIND -> k.t.bad }

    /** Pace icon centred at (cx, cy), size [s]: filled ▲ / ● / ▼, or ✓ for done. */
    fun paceIcon(k: Ink, m: PaceMark, cx: Float, cy: Float, s: Float = 9f) {
        val col = paceColor(k, m)
        when (m) {
            PaceMark.DONE -> k.glyph(Glyph.Check, cx - s * 0.7f, cy - s * 0.7f, s * 1.4f, col, 3.2f)
            PaceMark.ON_TRACK -> k.circle(cx, cy, s * 0.36f, col)
            else -> {
                val up = m == PaceMark.AHEAD; val hw = s * 0.5f; val hh = s * 0.42f
                val p = Path().apply {
                    if (up) { moveTo(cx - hw, cy + hh); lineTo(cx + hw, cy + hh); lineTo(cx, cy - hh) }
                    else { moveTo(cx - hw, cy - hh); lineTo(cx + hw, cy - hh); lineTo(cx, cy + hh) }
                    close()
                }
                val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = col }
                k.c.drawPath(p, pt)
            }
        }
    }

    /** Small pace chip: icon + word on the status container colour. Returns its width. */
    fun paceChipWidth(k: Ink, m: PaceMark): Float = 8f + 10f + 4f + k.measure(m.word, 11f, 600) + 9f

    fun paceChip(k: Ink, m: PaceMark, x: Float, top: Float, h: Float = 20f, alignRight: Boolean = false, iconOnly: Boolean = false): Float {
        val fs = 11f
        // icon-only: a round chip with the status icon (rule 3: status by icon); the word stays in the a11y text
        val w = if (iconOnly) h + 6f else paceChipWidth(k, m)
        val x0 = if (alignRight) x - w else x
        val bg = when (m) { PaceMark.DONE, PaceMark.AHEAD -> k.t.goodCont; PaceMark.BEHIND -> k.t.badCont; PaceMark.ON_TRACK -> k.t.tileHi }
        k.rrect(x0, top, x0 + w, top + h, h / 2f, bg)
        if (iconOnly) { paceIcon(k, m, x0 + w / 2f, top + h / 2f, 9f); return w }
        paceIcon(k, m, x0 + 8f + 5f, top + h / 2f, 9f)
        k.text(m.word, x0 + 8f + 10f + 4f, k.centerBase(top, h, fs, 600), fs, if (m == PaceMark.ON_TRACK) k.t.text else paceColor(k, m), 600)
        return w
    }

    /**
     * The kept week lines (owner: "don't get rid of those"): dot · name · "value / target" · pace icon (✓ ▲ ● ▼) ·
     * bar with the PACE MARK (where you should be by now). [compact] = no name (M).
     */
    fun goalLines(k: Ink, d: WeeklyGoalsData, x: Float, top: Float, w: Float, pitch: Float, fs: Float = 11.5f, barH: Float = 4f, compact: Boolean = false) {
        d.goals.forEachIndexed { i, g ->
            val y = top + i * pitch
            k.circle(x + 3.5f, y + 5f, 3.5f, g.color)
            val mark = g.pace ?: if (g.met) PaceMark.DONE else null
            val right = x + w - if (mark != null) 15f else 0f
            // never collide: full "17,953 / 70,000" → compact "18k / 70k" when the row is too narrow
            fun rw(v: String, tg: String) = k.measure(v, fs + 0.5f, 700) + k.measure(" / $tg", (fs + 0.5f) * 0.86f, 500)
            val tFull = g.label.target ?: ""; val tComp = g.label.compactTarget ?: tFull
            if (compact) {
                val room = right - (x + 11f) - 4f
                val (v, tg) = if (rw(g.label.value, tFull) <= room) g.label.value to tFull else g.label.compactValue to tComp
                // narrow M: step the numbers down (floor 8 sp) rather than run into the pace icon
                var f = fs + 0.5f
                while (k.measure(v, f, 700) + k.measure(" / $tg", f * 0.86f, 500) > room && f > 8f) f -= 0.5f
                ratio(k, v, tg, x + 11f, y + 9f, f)
            } else {
                // name shortens before anything collides: "Workout minutes" → "Workout min"
                // item 44: "Workout minutes" → "Workout min" → "Workout", "Heart points" → "Heart pts", only where the name doesn't fit
                fun fits(nm: String) = k.measure(nm, fs, 600) + 8f + rw(g.label.compactValue, tComp) <= right - (x + 11f)
                val name = listOf(g.name, g.name.replace("minutes", "min"), g.name.replace(" minutes", ""), g.name.replace("Heart points", "Heart pts"))
                    .firstOrNull { fits(it) } ?: g.name.replace(" minutes", "").replace("Heart points", "Heart pts")
                val nameW = k.measure(name, fs, 600)
                val room = right - (x + 11f) - nameW - 8f
                val (v, tg) = if (rw(g.label.value, tFull) <= room) g.label.value to tFull else g.label.compactValue to tComp
                // the (short) name whole or not at all: never "W…" (the dot colour still names the line)
                if (nameW <= right - (x + 11f) - rw(v, tg) - 8f) k.text(name, x + 11f, y + 9f, fs, k.t.text, 600)
                ratio(k, v, tg, right, y + 9f, fs + 0.5f, Paint.Align.RIGHT)
            }
            if (mark != null) paceIcon(k, mark, x + w - 5.5f, y + 5f, if (mark == PaceMark.DONE) 8.5f else 9f)
            val bx = x + 11f; val bw = w - 11f; val by = y + 15f
            bar(k, bx, by, bw, barH, g.progress, g.color)
            // pace mark: a fine tick at "expected by now" (not on a finished goal)
            val e = g.expected
            if (e != null && g.target > 0 && !g.met && e > 0) {
                val mx = bx + (bw * (e / g.target).toFloat().coerceIn(0f, 1f))
                k.line(mx, by - 1.5f, mx, by + barH + 2f, 1.5f, k.t.text)
            }
        }
    }

    /** Weekly goals (D20, V2-099): S rings; M/L/XL rings next to the kept lines; XL numbers integrated in the rings. */
    fun weeklyGoals(k: Ink, sz: TileSz, w: Float, h: Float, d: WeeklyGoalsData) {
        val t = k.t
        val prog = goalProgress(d)
        val col = k.ringColors().with(d.goals.map { it.color }.toIntArray())
        val n = d.goals.size.toString()
        val left = if (d.daysLeft == 1) "1 day left" else "${d.daysLeft} days left"
        when (sz) {
            TileSz.S -> small(k, w, h, Glyph.WeekCheck, t.text, d.met.toString(), null, "Weekly goals", target = n, short = "Goals") { x, y, ww, _ ->
                // centred on the glyph circle's centre line (y + 13), like cornerRing
                val dr = min(ww, 30f)
                RingPaint.rings(k.c, x + ww - dr, y + 13f - dr / 2f, dr, prog, col, 4f, 1f)
            }
            TileSz.M -> {
                // narrow M: the rings give up width (≥ 44 dp) so "value / target" fits beside them
                val need = 30f + d.goals.maxOf { g -> k.measure(g.label.compactValue, 11f, 700) + k.measure(" / ${g.label.compactTarget ?: g.label.target ?: ""}", 11f * 0.86f, 500) }
                val p = 12f; val dd = min(h - 2 * p, max(44f, w - 2 * p - 12f - need))
                RingPaint.rings(k.c, p, (h - dd) / 2f, dd, prog, col, 9f, 2f)
                val x = p + dd + 12f
                goalLines(k, d, x, 14f, w - x - 12f, 25f, 10.5f, 3.5f, compact = true)
            }
            TileSz.L -> {
                val p = 16f
                header(k, Glyph.WeekCheck, t.text, p, p - 1f, w - p, "Weekly goals")
                // narrow L: the rings (≥ 56 dp), then the "0 / 3" (≥ 20 sp) give way so it ends ≥ 4 dp inside the tile
                fun mw(f: Float) = k.measure(d.met.toString(), f, 700) + k.measure(" / $n", f * 0.86f, 500)
                val dd = min(82f, max(56f, w - 2 * p + 12f - 14f - mw(28f)))
                RingPaint.rings(k.c, p, 34f, dd, prog, col, 11f, 2f)
                val x = p + dd + 14f
                var mf = 28f
                while (x + mw(mf) > w - p + 12f && mf > 20f) mf -= 1f
                ratio(k, d.met.toString(), n, x, 66f, mf)
                // Narrow L tiles: shrink both lines together so "goals met" / "{n} days left" stay inside the tile.
                val room = w - p - x
                val widest = max(k.measure("goals met", 12f, 500), k.measure(left, 12f, 500))
                val fs = if (widest > room) max(9.5f, 12f * room / widest) else 12f
                k.text(k.fit("goals met", room, fs, 500), x, 84f, fs, t.muted, 500)
                // "4 days" when "4 days left" doesn't fit (never "4 days l…")
                k.text(k.fit(if (k.measure(left, fs, 500) <= room) left else left.removeSuffix(" left"), room, fs, 500), x, 101f, fs, t.muted, 500)
                goalLines(k, d, p, 128f, w - 2 * p, 22f, 11f, 4f)
            }
            TileSz.XL -> {
                // the rings give up width (≤ 42 % of the tile) so the goal names keep their room
                val p = 18f; val dd = min(h - 2 * p, w * 0.42f)
                val plan = RingPaint.triple(k.c, p, (h - dd) / 2f, dd, prog, d.goals.map { it.label }, col, RingPaint.strokeFor(dd, 0.13f))   // XL keeps the approved 0.13 stroke for its numbers
                if (plan.mode == RingLabelMode.BESIDE) Unit   // lines on the right carry the numbers anyway
                val x = p + dd + 22f; val rw = w - x - p
                header(k, Glyph.WeekCheck, t.text, x, p - 1f, w - p, "Weekly goals", fs = 14f)
                val range = "${d.rangeText} · $left"   // narrow XL: "4 days left" alone before anything is cut
                k.text(k.fit(if (k.measure(range, 11.5f, 500) <= w - p - x + 6f) range else left, w - p - x + 6f, 11.5f, 500), x, p + 32f, 11.5f, t.muted, 500)
                goalLines(k, d, x, p + 52f, rw, 38f, 12f, 5f)
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ Streak

    /** Flame inside a ring (ring = workout days this week vs goal), V2 fill. */
    fun flameRing(k: Ink, x: Float, y: Float, d: Float, p: Float, col: Int) {
        val s = max(4f, (d * 0.12f).roundToInt().toFloat())
        k.ring(x, y, d, p, col, s)
        val g = d * 0.46f
        k.glyph(Glyph.Flame, x + (d - g) / 2f, y + (d - g) / 2f - d * 0.01f, g, col, if (g < 20f) 2.6f else 2.2f)
    }

    private fun weekDots(k: Ink, d: StreakData, col: Int, x: Float, rw: Float, cy: Float, letterBase: Float, r: Float = 6.5f) {
        val t = k.t; val slot = rw / d.week.size
        val r = min(r, slot / 2f - 0.5f)   // narrow tiles: the dots shrink to their slot instead of overlapping
        d.week.forEachIndexed { i, on ->
            val cx = x + slot * i + slot / 2f
            when (on) {
                true -> { k.circle(cx, cy, r, col); k.glyph(Glyph.Check, cx - r * 0.62f, cy - r * 0.62f, r * 1.24f, com.boudhealth.render.Hue.checkInk, 3.2f) }
                false -> k.ringOutline(cx, cy, r - 0.7f, 1.4f, t.muted.alpha(0.7f))
                null -> k.circle(cx, cy, r, t.tileHi)
            }
            val l = d.letters.getOrElse(i) { "" }
            k.text(l, cx, letterBase, 9.5f, if (i == d.todayIndex) t.text else t.muted, if (i == d.todayIndex) 700 else 500, Paint.Align.CENTER)
        }
    }

    /** Streak (V2-107): flame-in-ring; S number + best; M/L/XL + week dots. */
    fun streak(k: Ink, sz: TileSz, w: Float, h: Float, d: StreakData, col: Int) {
        val t = k.t
        val p0 = if (d.goal <= 0) 0f else d.workoutDays / d.goal.toFloat()
        val unit = if (d.current == 1) "day" else "days"
        when (sz) {
            TileSz.S -> {
                val p = 12f
                flameRing(k, p, p - 2f, 34f, p0, col)
                k.value(d.current.toString(), unit, p, h - 31f, 21f, 11f)
                k.text("Streak", p, h - 13f, 11.5f, t.muted, 500)
                // "Best" only where it clears the flame ring (narrow S tiles drop it)
                if (w - p - k.measure("Best ${d.best}", 11f, 600) >= p + 33f) k.text("Best ${d.best}", w - p, p + 18f, 11f, t.muted, 600, Paint.Align.RIGHT)
            }
            TileSz.M -> {
                // narrow M: the ring gives up width so the week dots keep their slots
                val dd = min(66f, w * 0.33f)
                flameRing(k, 14f, (h - dd) / 2f, dd, p0, col)
                val x = 14f + dd + 14f; val rw = w - x - 12f
                // "day streak" → "days" when it would run past the tile; the best line drops its week part before a number is cut
                k.value(d.current.toString(), if (k.valueWidth(d.current.toString(), "day streak", 24f, 12f) <= rw) "day streak" else unit, x, 34f, 24f, 12f)
                val best = "Best ${d.best} · ${d.workoutDays} / ${d.goal} days"
                // short M (cover screen): the best line goes before it runs into the week dots
                if (h >= 89f) k.text(k.fit(if (k.measure(best, 11f, 500) <= rw) best else "Best ${d.best}", rw, 11f, 500), x, 52f, 11f, t.muted, 500)
                weekDots(k, d, col, x, rw, h - 29f, h - 10f)
            }
            TileSz.L, TileSz.XL -> {
                val p = if (sz == TileSz.XL) 18f else 16f
                header(k, Glyph.Flame, col, p, p - 1f, w - p, "Streak")
                // narrow L: the flame ring gives up width (≥ 46 dp) so "5 days" stays inside the tile
                val dd = if (sz == TileSz.XL) h - 2 * p - 30f else min(92f, max(46f, w - 2 * p - 16f - k.valueWidth(d.current.toString(), unit, 32f, 14f)))
                flameRing(k, p, p + 30f, dd, p0, col)
                val x = p + dd + 16f; val rw = w - x - p
                val vb = if (sz == TileSz.XL) p + 30f + dd * 0.36f else p + 30f + dd / 2f
                k.value(d.current.toString(), unit, x, vb, 32f, 14f)
                if (h - p - 27.5f - (vb + 20f) >= 3f) k.text("Best ${d.best}", x, vb + 20f, 12f, t.muted, 500)   // short XL: never on the week dots
                val wx = if (sz == TileSz.XL) x else p; val ww = if (sz == TileSz.XL) rw else w - 2 * p
                val wd = "${d.workoutDays} / ${d.goal} workout days this week"
                // short XL: the line goes (the dots under it say the same) rather than touch "Best"
                if (h - p - 36f - (vb + 20f) >= 13f) k.text(k.fit(if (k.measure(wd, 11.5f, 500) <= ww) wd else wd.removeSuffix(" this week"), ww, 11.5f, 500), wx, h - p - 36f, 11.5f, t.muted, 500)
                weekDots(k, d, col, wx, ww, h - p - 20f, h - p + 2f, 7.5f)
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ Workouts

    /** Workouts S / M (V2-107): sessions this week; S mini bars per day; M type chips (kind icon + count). */
    fun workouts(k: Ink, sz: TileSz, w: Float, h: Float, d: WorkoutsWeekData, col: Int, todayIndex: Int) {
        val t = k.t
        when (sz) {
            TileSz.S -> small(k, w, h, Glyph.Dumbbell, col, d.sessions.toString(), null, "Workouts") { x, y, ww, hh ->
                k.bars(d.perDay, x, y, ww, hh, intArrayOf(col, col), bw0 = 4f)
            }
            else -> {
                val p = when (sz) { TileSz.L -> 16f; TileSz.XL -> 18f; else -> 14f }   // same insets as the other L / XL tiles
                header(k, Glyph.Dumbbell, col, p, p - 1f, w - p, "Workouts", "this week")
                val sw = k.value(d.sessions.toString(), if (d.sessions == 1) "session" else "sessions", p, 56f, 24f, 12f)
                val hm = hm(d.minutes)
                if (p + sw + 2f + k.measure(hm, 13f, 700) <= w - p) k.text(hm, w - p, 56f, 13f, t.text, 700, Paint.Align.RIGHT)
                var cx = p
                // short M (cover screen): the type chips only where they clear "2 sessions"
                if (h >= 92f) for (kd in d.kinds) {
                    val txt = kd.count.toString()
                    val cw = k.measure(txt, 11.5f, 700) + 30f
                    if (cx + cw > w - p) break
                    k.rrect(cx, h - 34f, cx + cw, h - 12f, 11f, kd.color.alpha(if (t.dark) 0.22f else 0.16f))
                    k.glyph(kd.glyph, cx + 6f, h - 30f, 14f, t.legible(kd.color, 3.0), 2.4f)
                    k.text(txt, cx + 23f, h - 18.5f, 11.5f, t.text, 700)
                    cx += cw + 6f
                }
                if (sz != TileSz.M && h > 140f) {
                    // L / XL: minutes per day under the summary (same data as S)
                    k.bars(d.perDay, p, 70f, w - 2 * p, h - 70f - 44f, intArrayOf(col, col), bw0 = 10f, today = todayIndex)
                }
            }
        }
    }

    /** Recent workouts XL (V2-107): last 6 sessions in 2 columns (title · day + time · minutes · avg HR). */
    fun recentXL(k: Ink, w: Float, h: Float, rows: List<WorkoutRow>) {
        val t = k.t; val p = 18f
        header(k, Glyph.History, t.text, p, p - 1f, w - p, "Recent workouts", fs = 14f)
        val colW = (w - 2 * p - 16f) / 2f
        val pitch = min(52f, (h - 36f - p + 4f) / 3f)
        rows.take(6).forEachIndexed { i, wk ->
            val x = p + (i / 3) * (colW + 16f); val y = 36f + (i % 3) * pitch
            k.glyphCircle(wk.glyph, wk.color, x, y + 4f, 32f, 17f)
            k.text(k.fit(wk.title, colW - 42f, 13f, 600), x + 42f, y + 17f, 13f, t.text, 600)
            var cx = x + 42f
            cx += k.text("${wk.whenText} · ", cx, y + 34f, 11f, t.muted, 500)
            cx += k.text(wk.minutes.toString(), cx, y + 34f, 11.5f, t.text, 700)
            cx += k.text(" min", cx, y + 34f, 11f, t.muted, 500)
            val hr = wk.avgHr?.let { " · $it bpm" }
            if (hr != null && cx + k.measure(hr, 11f, 500) <= x + colW) k.text(hr, cx, y + 34f, 11f, t.muted, 500)
            if (i % 3 < 2) k.line(x + 42f, y + pitch - 5f, x + colW, y + pitch - 5f, 1f, t.grid)
        }
    }

    // ------------------------------------------------------------------------------------------------ Sleep

    fun stageCol(k: Ink, s: String): Int = when (s) {
        "deep" -> k.t.deep; "rem" -> com.boudhealth.render.Hue.rem; "light" -> com.boudhealth.render.Hue.light
        "awake" -> com.boudhealth.render.Hue.awake; else -> com.boudhealth.render.Hue.sleep
    }

    /** Hypnogram: rows awake · rem · light · deep (asleep without stage → light row in the sleep colour). */
    fun hypno(k: Ink, d: SleepData, x: Float, y: Float, w: Float, h: Float, labels: Boolean) {
        val st = d.segs; if (st.isEmpty()) return
        val t0 = st.minOf { it.startMs }; val t1 = st.maxOf { it.endMs }
        if (t1 <= t0) return
        val rows = listOf("awake", "rem", "light", "deep"); val rh = h / 4f
        if (labels) rows.indices.forEach { i -> k.line(x, y + rh * i + rh / 2f, x + w, y + rh * i + rh / 2f, 1f, k.t.grid, floatArrayOf(2f, 3f)) }
        for (s in st) {
            val i = rows.indexOf(s.stage).let { if (it < 0) 2 else it }
            val sx = x + w * ((s.startMs - t0).toFloat() / (t1 - t0)); val ex = x + w * ((s.endMs - t0).toFloat() / (t1 - t0))
            val inset = if (labels) 3f else 0.8f
            k.rrect(sx, y + rh * i + inset, max(sx + if (labels) 2f else 1f, ex), y + rh * (i + 1) - inset, if (labels) 3f else 1f, stageCol(k, s.stage))
        }
        if (!labels) return
        k.text(d.startText, x, y + h + 13f, 10.5f, k.t.muted, 500)
        k.text(d.endText, x + w, y + h + 13f, 10.5f, k.t.muted, 500, Paint.Align.RIGHT)
    }

    /** "45m", "7h 05m" (minutes padded after hours, as R3 SFmt.hm). */
    private fun hm(min: Int): String = if (min < 60) "${min}m" else "${min / 60}h ${(min % 60).toString().padStart(2, '0')}m"

    /** Sleep S (moon + duration + mini hypnogram) and XL (duration, stage totals, hypnogram). */
    fun sleep(k: Ink, sz: TileSz, w: Float, h: Float, d: SleepData) {
        val t = k.t; val col = com.boudhealth.render.Hue.sleep
        when (sz) {
            TileSz.S -> {
                val p = 12f
                k.glyphCircle(Glyph.Moon, col, p, p)
                // ≥ 6 dp right of the moon circle (narrow cells: the hypnogram takes what is left)
                val hx = max(w - p - 42f, p + 26f + 6f)
                if (w - p - hx >= 8f) hypno(k, d, hx, p + 1f, w - p - hx, 24f, labels = false)   // QA_FINAL: no negative width
                // never cut the duration: step it down (21 → 16 sp) like the other S values
                var fs = 21f
                while (k.durationWidth(d.asleepMin, fs) > w - 2 * p && fs > 16f) fs -= 0.5f
                k.duration(d.asleepMin, p, h - 31f, fs)
                k.text("Sleep", p, h - 13f, 11.5f, t.muted, 500)
            }
            else -> {
                val p = 18f
                header(k, Glyph.Moon, col, p, p - 1f, w - p, "Sleep", d.meta, fs = 14f)
                val lw = 128f
                k.duration(d.asleepMin, p, 74f, 34f)
                // short XL (cover screen): rows close up (≥ 14 dp) and the ones below the tile are left out
                val pitch = min(21f, max(14f, (h - p - 102f) / 3f))
                d.totals.take(4).forEachIndexed { i, (s, m) ->
                    val y = 102f + i * pitch
                    if (y > h - p + 2f) return@forEachIndexed
                    k.circle(p + 4f, y - 4f, 4f, stageCol(k, s))
                    k.text(stageName(s), p + 13f, y, 12f, t.muted, 500)
                    k.text(hm(m), p + lw, y, 12.5f, t.text, 700, Paint.Align.RIGHT)
                }
                val x = p + lw + 26f
                hypno(k, d, x, 44f, w - x - p, h - 44f - p - 16f, labels = true)
            }
        }
    }

    private fun stageName(s: String) = when (s) { "deep" -> "Deep"; "rem" -> "REM"; "light" -> "Light"; "awake" -> "Awake"; else -> "Asleep" }

    // ------------------------------------------------------------------------------------------------ BMI

    fun bmiScale(k: Ink, x: Float, y: Float, w: Float, bmi: Double, ticks: Boolean) {
        val t = k.t; val hz = com.boudhealth.render.Hue
        k.scaleBar(x, y, w, listOf(Triple(17.0, 18.5, t.tileHi), Triple(18.5, 25.0, hz.zoneOk.alpha(0.5f)), Triple(25.0, 30.0, hz.zoneWarn.alpha(0.6f)), Triple(30.0, 34.0, hz.zoneHigh.alpha(0.5f))),
            17.0, 34.0, bmi.coerceIn(17.0, 34.0), ticks = if (ticks) listOf(18.5 to "18.5", 25.0 to "25", 30.0 to "30") else emptyList())
    }

    /** BMI S (scale tick corner, "BMI · 175 cm") and M (V2-101: one scale, same info as S, no duplicate line). */
    fun bmi(k: Ink, sz: TileSz, w: Float, h: Float, d: BmiData) {
        val t = k.t; val hz = com.boudhealth.render.Hue
        val name = d.heightCm?.let { "BMI · $it cm" } ?: "BMI"
        when (sz) {
            TileSz.S -> small(k, w, h, Glyph.Gauge, hz.bmi, n1(d.bmi), null, name) { x, y, ww, _ ->
                val segs = listOf(Triple(17.0, 18.5, t.tileHi), Triple(18.5, 25.0, hz.zoneOk.alpha(0.6f)), Triple(25.0, 30.0, hz.zoneWarn.alpha(0.7f)), Triple(30.0, 34.0, hz.zoneHigh.alpha(0.6f)))
                fun X(v: Double) = x + ((v - 17.0) / 17.0).toFloat() * ww
                k.c.save(); k.c.clipPath(Path().apply { addRoundRect(RectF(x, y + 10f, x + ww, y + 16f), 3f, 3f, Path.Direction.CW) })
                segs.forEachIndexed { i, (a, b, c) -> k.rect(X(a) + if (i > 0) 1f else 0f, y + 10f, X(b), y + 16f, c) }
                k.c.restore()
                val tx = X(d.bmi.coerceIn(17.0, 34.0))
                k.line(tx, y + 6.5f, tx, y + 19.5f, 2.4f, t.card); k.line(tx, y + 7.5f, tx, y + 18.5f, 1.6f, t.text)
            }
            else -> {
                val p = 14f
                val meta = listOfNotNull(d.heightCm?.let { "$it cm" }, d.weightKg?.let { "${n1(it)} kg" }).joinToString(" · ").ifEmpty { null }
                header(k, Glyph.Gauge, hz.bmi, p, p - 1f, w - p, "BMI", meta)
                k.value(n1(d.bmi), null, p, h - 16f, 26f)
                val sx = p + 70f
                bmiScale(k, sx, h - 50f, w - sx - p, d.bmi, true)
            }
        }
    }
}
