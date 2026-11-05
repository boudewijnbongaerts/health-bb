package com.boudhealth.render

import android.graphics.Paint
import android.graphics.Path
import androidx.health.connect.client.records.ExerciseSessionRecord
import com.boudhealth.data.ExerciseTypes
import com.boudhealth.data.Range
import com.boudhealth.data.TileSize
import com.boudhealth.data.Workout
import com.boudhealth.ui.components.Fmt
import com.boudhealth.render.ring.TileArt
import com.boudhealth.render.ring.TileSz
import com.boudhealth.ui.components.Glyph
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Canvas tile renderers for the home-screen widgets: every tile id (= metric id) at S / M / L / XL for any w × h,
 * following design2/screens/A_SPEC.md §2–3 and A_tiles.js. The canvas is translated to the tile's top-left and is in dp.
 * Data comes only from [TileSource] (Metrics registry / Local / Cache).
 */
class Tiles(private val k: Ink, private val s: TileSource) {
    private val t = k.t
    private val pal = t.ring

    private val v4 = V4Tiles(k, s)

    fun draw(id: String, size: TileSize, w: Float, h: Float) {
        if (v4.draw(id, size, w, h)) return
        when (id) {
            "last_workout" -> lastWorkout(size, w, h)
            "sleep" -> if (size == TileSize.S || size == TileSize.XL) V2Models.sleep(s)?.let { TileArt.sleep(k, if (size == TileSize.S) TileSz.S else TileSz.XL, w, h, it) } ?: sleep(size, w, h) else sleep(size, w, h)
            "spo2" -> spo2(size, w, h)
            // V2-101: BMI S and M from one painter (M = the S information, one scale, no duplicate line)
            "bmi" -> if (size == TileSize.S || size == TileSize.M) V2Models.bmi(s)?.let { TileArt.bmi(k, if (size == TileSize.S) TileSz.S else TileSz.M, w, h, it) } ?: empty(id, w, h, "BMI") else metricDef(id)?.let { metric(it, size, w, h) } ?: empty(id, w, h)
            else -> metricDef(id)?.let { metric(it, size, w, h) } ?: empty(id, w, h)
        }
    }

    // =================================================================== shared bits

    private fun ringCols(i: Int) = intArrayOf(pal.c0Argb(i), pal.c1Argb(i))
    private fun n0(v: Double) = Fmt.num(v, 0)
    private fun n1(v: Double) = Fmt.num(v, 1)

    /** "Today 07:12" / "Yesterday" / "Tue 27 Oct". */
    private fun whenMeta(time: Long): String {
        val d = Fmt.date(time)
        return if (d == s.today) "Today ${Fmt.hm(time)}" else Fmt.dayLabel(d, s.today)
    }

    private fun nightMeta(day: java.time.LocalDate): String =
        if (day == s.today || day == s.today.minusDays(1)) "Last night" else Fmt.dayLabel(day, s.today)

    private fun empty(id: String, w: Float, h: Float, label: String = com.boudhealth.data.Metrics.find(id)?.label ?: id) {
        val p = if (w < 120f) 12f else 14f
        k.labelRow(p, p - 1f, w - p, t.muted, k.fit(label, w - 2 * p - 14f, 13f, 600), null)
        val nfs = if (k.measure("No data yet", 12f, 500) <= w - 2 * p) 12f else max(10.5f, 12f * (w - 2 * p) / k.measure("No data yet", 12f, 500))
        k.text(k.fit("No data yet", w - 2 * p, nfs, 500), p, h - p - 2f, nfs, t.muted, 500)
    }

    private fun subLine(x: Float, y: Float, short: String, delta: Delta?, maxW: Float) {
        val dw = if (delta?.short != null) k.measure(" ${arrow(delta.dir)}${delta.short}", 11.5f, 700) else 0f
        val sw = k.text(k.fit(short, maxW - dw, 11.5f, 500), x, y, 11.5f, t.muted, 500)
        if (delta?.short != null) k.text(" ${arrow(delta.dir)}${delta.short}", x + sw, y, 11.5f, k.toneFg(delta.tone), 700)
    }

    private fun arrow(d: Ink.Dir) = when (d) { Ink.Dir.UP -> "▲"; Ink.Dir.DOWN -> "▼"; Ink.Dir.NONE -> "" }

    private class Delta(val text: String, val short: String?, val tone: Ink.Tone, val dir: Ink.Dir)

    /** Change → delta with tone from "higher is better" (null = neutral). Below [noise] = "no change". */
    private fun delta(change: Double?, higherIsBetter: Boolean?, noise: Double, decimals: Int, fmt: (String) -> String): Delta? {
        change ?: return null
        val a = abs(change)
        val txt = Fmt.num(a, decimals)
        if (a < noise) return Delta("Same", null, Ink.Tone.NEUTRAL, Ink.Dir.NONE)
        val up = change > 0
        val tone = when (higherIsBetter) { null -> Ink.Tone.NEUTRAL; true -> if (up) Ink.Tone.GOOD else Ink.Tone.BAD; false -> if (up) Ink.Tone.BAD else Ink.Tone.GOOD }
        return Delta(fmt(txt), txt, tone, if (up) Ink.Dir.UP else Ink.Dir.DOWN)
    }

    private fun change(v: List<Double>): Double? = if (v.size >= 2) v.last() - v.first() else null

    /** Item 46: last calendar month's lowest–highest (the Vitals tab's "{previous month}" row), not an average band. */
    private fun lastMonthRange(id: String): Pair<Double, Double>? {
        val ym = java.time.YearMonth.from(s.today).minusMonths(1)
        val days = java.time.temporal.ChronoUnit.DAYS.between(ym.atDay(1), s.today) + 1
        val v = s.daily(id, days).filter { !it.date.isAfter(ym.atEndOfMonth()) && !it.v.isNaN() }.map { it.v }
        return if (v.isEmpty()) null else v.min() to v.max()
    }

    // =================================================================== generic metric tile

    private class Def(
        val col: Int, val glyph: Glyph, val label: String, val short: String, val meta: String?,
        val value: String?, val unit: String, val delta: Delta? = null,
        val ctx: String? = null, val lctx: String? = null,
        val xlGrid: Boolean = false, val xlLabels: Boolean = false, val mvw: Float = 74f, val unitL: String? = null,
        // v4 small tiles (SMALL_TILES.md): exactly one extra visual or fact at S and M
        /** S: drawn in the top-right corner box (≤ 40 × 18). */
        val sCorner: ((x: Float, y: Float, w: Float, h: Float) -> Unit)? = null,
        /** S: replaces the glyph circle (26 dp box at x, y). */
        val sGlyph: ((x: Float, y: Float) -> Unit)? = null,
        /** S: replaces the short line (and its delta). */
        val sShort: String? = null,
        /** V2-102: an (i) glyph right after the L context line (Waist: "WHtR 0.51 (i)"). */
        val info: Boolean = false,
        /** S: replaces the value (e.g. Heart rate's range "45–162"). */
        val sValue: String? = null,
        /** M: one extra line under the label (baseline y, max width). */
        val mLine: ((x: Float, base: Float, maxW: Float) -> Unit)? = null,
        /** Trailing lambda: the chart / visual at M, L and XL. */
        val viz: ((x: Float, y: Float, w: Float, h: Float, size: TileSize) -> Unit)? = null,
    )

    private fun metric(d: Def, size: TileSize, w: Float, h: Float) {
        val v = d.value ?: "–"
        // narrow tiles: the short name ("Body fat", "Waist") instead of a long label that would run past the edge
        fun label(p: Float, fs: Float = 13f) = if (k.measure(d.label, fs, 600) <= w - 2 * p - 14f + 6f) d.label else d.short
        when (size) {
            // V2-094: every S tile = glyph circle + value (+ unit) + mini name (the metric's short name), nothing else;
            // only an approved corner fact (Heart rate "bpm") stays
            TileSize.S -> TileArt.small(k, w, h, d.glyph, d.col, d.sValue ?: v, if (d.sValue != null) null else d.unit.ifEmpty { null }, d.sShort ?: d.short, corner = d.sCorner?.takeIf { d.value != null })
            TileSize.M -> {
                val p = 14f
                k.labelRow(p, p - 1f, w - p, d.col, label(p), null)
                val valW = k.value(v, d.unit, p, h - 15.5f, 24f)
                val vw = min(d.mvw, w * 0.42f); val vh = h - 2 * p - 22f
                // the chart starts right of the value + unit (never under "/min", "kg")
                val vx = max(w - p - vw, p + valW + 6f)
                // the extra line only where it clears the value below it (short cover-screen M tiles drop it)
                if (d.value != null && h >= 84f) d.mLine?.invoke(p, p + 31f, vx - p - 8f)
                // QA_FINAL: a value whose chart has no room just drops the chart (never "No data yet" over the value)
                if (d.value != null) { if (w - p - vx > 8f) d.viz?.invoke(vx, h - p - vh, w - p - vx, vh, size) }
                else k.text("No data yet", w - p, h - 15.5f, 12f, t.muted, 500, Paint.Align.RIGHT)
            }
            TileSize.L -> {
                val p = 16f
                k.labelRow(p, p - 1f, w - p, d.col, label(p), d.meta)
                // a long L unit ("ml/kg/min") falls back to the short one when it would run past the tile
                k.value(v, d.unitL?.takeIf { k.valueWidth(v, it, 32f, 16f) <= w - 2 * p } ?: d.unit, p, 66.8f, 32f, 16f)
                when {
                    d.value == null -> k.text("No data yet", p, 95f, 12.5f, t.muted)
                    d.lctx != null -> {
                        val tw = k.text(k.fit(d.lctx, w - 2 * p - if (d.info) 18f else 0f, 12.5f), p, 95f, 12.5f, t.muted)
                        if (d.info) k.glyph(Glyph.Info, p + tw + 4f, 95f - 11f, 14f, t.muted, 2.2f)
                    }
                    d.delta != null -> k.pill(pillText(d.delta, w - 2 * p), d.delta.tone, d.delta.dir, p, 82f, maxW = w - 2 * p)
                    d.ctx != null -> k.text(k.fit(d.ctx, w - 2 * p, 12.5f), p, 95f, 12.5f, t.muted)
                }
                val vy = p + 104f
                if (d.value != null && h - vy - p > 16f) d.viz?.invoke(p, vy, w - 2 * p, h - vy - p, size)
            }
            TileSize.XL -> {
                val p = 18f
                val lw = min(176f, w * 0.42f)
                val vx = p + lw + 8f; val vy = p + 34f
                val vw = w - vx - p - if (d.xlGrid) 26f else 0f
                val vh = h - vy - p - if (d.xlLabels) 16f else 0f
                k.labelRow(p, p - 1f, w - p, d.col, label(p, 14f), d.meta, 14f)
                k.value(v, d.unitL ?: d.unit, p, 87.6f, 44f, 22f)
                val ctx = if (d.value == null) "No data yet" else d.ctx
                ctx?.let { c -> k.wrap(c, lw, 12.5f).forEachIndexed { i, line -> k.text(line, p, 117f + i * 17f, 12.5f, t.muted) } }
                d.delta?.let { k.pill(pillText(it, lw), it.tone, it.dir, p, h - p - 24f, 24f, lw) }
                if (d.value != null) d.viz?.invoke(vx, vy, vw, vh, size)
            }
        }
    }

    private fun metricDef(id: String): Def? {
        val r = s.reading(id)
        return when (id) {
            "weight" -> {
                val v30 = s.values("weight", 30)
                val dl = delta(change(v30), false, 0.05, 1) { "$it kg · 30 d" }
                val yr = s.series("weight", Range.YEAR).filter { !it.v.isNaN() }
                val yc = change(yr.map { it.v })
                val ctx = buildString {
                    // owner-approved texts: facts, no sentences ("−1.2 kg · 12 months", "No change · 12 months")
                    if (yc != null && yr.size >= 4) append(if (abs(yc) < 0.05) "No change · 12 months" else "${if (yc < 0) "−" else "+"}${n1(abs(yc))} kg · 12 months")
                }.ifEmpty { null }
                Def(t.text, Glyph.Scale, "Weight", "Weight", r?.let { whenMeta(it.time) }, r?.let { n1(it.value) }, "kg", dl, ctx = ctx,
                    xlGrid = true, xlLabels = true, mLine = chipLine(dl)) { x, y, w, h, sz ->
                    if (sz == TileSize.XL && yr.size >= 2) {
                        val vals = yr.map { it.v }
                        val ticks = niceTicks(vals.min(), vals.max())
                        val idx = listOf(0, yr.size / 3, 2 * yr.size / 3, yr.size - 1).distinct()
                        val mf = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)
                        k.spark(vals, x, y, w, h, t.text, Ink.SparkOpts(area = true, areaAlpha = 0.12f, grid = ticks, pad = 4f,
                            min = min(vals.min(), ticks.first()) - 0.3, max = max(vals.max(), ticks.last()) + 0.3,
                            xLabels = idx.map { i -> (i.toFloat() / (yr.size - 1)) to mf.format(yr[i].date) }))
                    } else k.spark(v30, x, y, w, h, t.text, Ink.SparkOpts(areaAlpha = 0.12f, sw = 2f, ma = 5))
                }
            }
            "body_fat" -> {
                val v30 = s.values("body_fat", 30)
                val bfd = delta(change(v30), false, 0.05, 1) { "$it % · 30 d" }
                Def(Hue.fat, Glyph.Percent, "Body fat percentage", "Body fat", "30 days", r?.let { n1(it.value) }, "%", bfd,
                    mLine = chipLine(bfd)) { x, y, w, h, _ ->
                    k.spark(v30, x, y, w, h, Hue.fat, Ink.SparkOpts(sw = 2f, ma = 5))
                }
            }
            "waist" -> {
                val entries = s.values("waist", 365).takeLast(7)
                val whtr = r?.parts?.get("whtr")
                val prev = if (entries.size >= 2) entries.last() - entries[entries.size - 2] else null
                Def(Hue.waist, Glyph.Tape, "Waist circumference", "Waist", r?.let { Fmt.dayLabel(it.day, s.today) },
                    r?.let { n1(it.value) }, "cm", delta(prev, false, 0.05, 1) { "$it cm" },
                    lctx = whtr?.let { "WHtR ${Fmt.num(it, 2)}" }, info = whtr != null,
                    mLine = prev?.takeIf { abs(it) >= 0.05 }?.let { pv ->
                        val prevDay = s.daily("waist", 365).takeLast(2).firstOrNull()?.date
                        factLine("${if (pv < 0) "▼" else "▲"} ${n1(abs(pv))} cm${prevDay?.let { " vs ${Fmt.dayLabel(it, s.today).let { d -> if (d == "Yesterday" || d == "Today") d.lowercase() else d }}" } ?: ""}")
                    }) { x, y, w, h, sz ->
                    if (sz == TileSize.M && whtr != null) {
                        val top = y + max(0f, h - 30f)
                        val lw = k.text("WHtR ", x, top + 11f, 11f, t.muted, 400)
                        val vw2 = k.text(Fmt.num(whtr, 2), x + lw, top + 11f, 11f, t.text, 700)
                        k.glyph(Glyph.Info, x + lw + vw2 + 3f, top + 1f, 12f, t.muted, 2.2f)
                        whtrBar(x, top + 14f, w, whtr, false)
                    } else if (sz == TileSize.M) {
                        k.spark(entries, x, y, w, h, Hue.waist, Ink.SparkOpts(sw = 2f, dots = true))
                    } else {
                        val sh = if (whtr != null) max(16f, h - 36f) else h
                        k.spark(entries, x, y, w, sh, Hue.waist, Ink.SparkOpts(sw = 2f, dots = true))
                        if (whtr != null) whtrBar(x, y + sh + 10f, w, whtr, true)
                    }
                }
            }
            "bmi" -> {
                val hm = s.heightM
                val short = hm?.let { "BMI · ${(it * 100).roundToInt()} cm" } ?: "BMI"
                Def(Hue.bmi, Glyph.Gauge, "BMI", short, null, r?.let { n1(it.value) }, "",
                    sCorner = r?.value?.let { bmi -> { x, y, w, _ ->
                        k.segPill(listOf(1.5 to t.tileHi, 6.5 to Hue.zoneOk.alpha(0.6f), 5.0 to Hue.zoneWarn.alpha(0.7f), 4.0 to Hue.zoneHigh.alpha(0.6f)), x, y + 6f, w, 6f)
                        val tx = x + ((bmi.coerceIn(17.0, 34.0) - 17.0) / 17.0).toFloat() * w
                        k.line(tx, y + 3.5f, tx, y + 14.5f, 2f, t.text)
                    } }) { x, y, w, h, _ ->
                    r?.value?.let { bmi ->
                        k.scaleBar(x, y + max(0f, h - 26f), w, listOf(
                            Triple(17.0, 18.5, t.tileHi), Triple(18.5, 25.0, Hue.zoneOk.alpha(0.5f)),
                            Triple(25.0, 30.0, Hue.zoneWarn.alpha(0.6f)), Triple(30.0, 34.0, Hue.zoneHigh.alpha(0.5f))),
                            17.0, 34.0, bmi.coerceIn(17.0, 34.0), ticks = listOf(25.0 to "25", 30.0 to "30"))
                    }
                }
            }
            "rhr" -> {
                val v14 = s.values("rhr", 14)
                val band = lastMonthRange("rhr")   // item 46: last month's lowest–highest (was mean ± 1 SD of 60 days)
                val bandTxt = band?.let { "${n0(it.first)}–${n0(it.second)}" }
                // V2 (no averages): latest reading vs the previous reading, with that reading's day
                val rd30 = s.daily("rhr", 30)
                val prevPt = rd30.dropLast(1).lastOrNull()
                val chg = if (rd30.size >= 2) rd30.last().v - rd30[rd30.size - 2].v else null
                // QA 01.11: lower-case relative days inside a sentence ("Same as yesterday")
                val prevTxt = prevPt?.let { Fmt.dayLabel(it.date, s.today).let { d -> if (d == "Yesterday" || d == "Today") d.lowercase() else d } } ?: ""
                // QA 01.5 / 14.1: Heart glyph (design), header meta = the reading's day (was "14 days", truncated)
                Def(Hue.heart, Glyph.Heart, "Resting heart rate", "Resting HR", r?.let { Fmt.dayLabel(it.day, s.today) }, r?.let { n0(it.value) }, "bpm",
                    delta(chg, false, 1.0, 0) { "$it bpm vs $prevTxt" }?.let { if (it.short == null) Delta("Same as $prevTxt", null, it.tone, it.dir) else it },
                    xlGrid = true) { x, y, w, h, sz ->
                    val lo = min(v14.minOrNull() ?: 0.0, band?.first ?: Double.MAX_VALUE) - 2
                    val hi = max(v14.maxOrNull() ?: 0.0, band?.second ?: -Double.MAX_VALUE) + 2
                    k.spark(v14, x, y, w, h, Hue.heart, Ink.SparkOpts(band = band, min = lo, max = hi, sw = 2f,
                        grid = if (sz == TileSize.XL) band?.let { listOf(it.first.roundToInt().toDouble(), it.second.roundToInt().toDouble()) } ?: emptyList() else emptyList()))
                }
            }
            "hrv" -> {
                val v14 = s.values("hrv", 14)
                val band = lastMonthRange("hrv")   // item 46: last month's lowest–highest (was mean ± 1 SD of 60 days)
                // restored (audit 1.4): latest night vs the previous reading ("N ms vs <day>"), no judgement colour
                val hp = s.daily("hrv", 30)
                val hChg = if (hp.size >= 2) hp.last().v - hp[hp.size - 2].v else null
                val hPrev = hp.dropLast(1).lastOrNull()?.let { Fmt.dayLabel(it.date, s.today).let { d -> if (d == "Yesterday" || d == "Today") d.lowercase() else d } } ?: ""
                val hd = delta(hChg, null, 1.0, 0) { "$it ms vs $hPrev" }?.let { if (it.short == null) Delta("Same as $hPrev", null, it.tone, it.dir) else it }
                Def(Hue.hrv, Glyph.Hrv, "HRV", "HRV", r?.let { nightMeta(it.day) }, r?.let { n0(it.value) }, "ms", hd, mLine = chipLine(hd)) { x, y, w, h, sz ->
                    // M: the last 7 nights as bars (nightly values are discrete nights)
                    if (sz == TileSize.M) k.mini7(s.rolling7("hrv"), x, y, w, h, Hue.hrv, barW = 7f, gap = 3f)
                    else {
                        val lo = min(v14.minOrNull() ?: 0.0, band?.first ?: Double.MAX_VALUE) - 3
                        val hi = max(v14.maxOrNull() ?: 0.0, band?.second ?: -Double.MAX_VALUE) + 3
                        k.spark(v14, x, y, w, h, Hue.hrv, Ink.SparkOpts(band = band, sw = 2f, ma = 3, min = lo, max = hi))
                    }
                }
            }
            "hr" -> {
                val day = s.series("hr", Range.DAY).map { it.v }.filter { !it.isNaN() }
                val mn = r?.parts?.get("min"); val mx = r?.parts?.get("max")
                // v6 H12: S = "68 bpm" + today's line only
                // V2-094 / V7-R15: S = heart icon, today's range "45–162", "bpm" in the corner, mini name
                Def(Hue.heart, Glyph.Heart, "Heart rate", "Heart rate", r?.let { Fmt.hm(it.time) },
                    r?.let { n0(it.value) }, "bpm", ctx = if (mn != null && mx != null) "Range today ${n0(mn)}–${n0(mx)}" else null,
                    sValue = if (mn != null && mx != null) "${n0(mn)}–${n0(mx)}" else null,
                    sCorner = { x, y, w, _ -> k.text("bpm", x + w, y + 17f, 11f, t.muted, 600, Paint.Align.RIGHT) }) { x, y, w, h, _ ->
                    k.spark(day, x, y, w, h, Hue.heart, Ink.SparkOpts(sw = 1.8f, areaAlpha = 0.18f))
                }
            }
            "vo2" -> {
                val v90 = s.values("vo2", 91)
                val frac = r?.let { ((it.value - 20.0) / 40.0).toFloat() } ?: 0f
                val vd = delta(change(v90), true, 0.1, 1) { "$it vs 3 months ago" }
                Def(Hue.vo2, Glyph.Vo2Max, "VO₂ max", "VO₂ max", r?.let { Fmt.dayLabel(it.day, s.today) }, r?.let { n1(it.value) }, "", vd,
                    mvw = 60f, unitL = "ml/kg/min",
                    // V7-R17 "VO₂ S/M arrows": the 3-month direction as ▲ / ▼ in the S corner (M keeps its chip)
                    sCorner = vd?.short?.let { sh -> { x, y, w, _ -> k.text("${arrow(vd.dir)}$sh", x + w, y + 17f, 12f, k.toneFg(vd.tone), 700, Paint.Align.RIGHT) } },
                    mLine = chipLine(vd?.let { Delta("${it.short ?: ""} · 3 mo", it.short, it.tone, it.dir) })) { x, y, w, h, sz ->
                    if (sz == TileSize.M) k.gauge(x, y, w, h, frac, Hue.vo2, 7f)
                    else {
                        val gw = min(70f, h * 1.25f)
                        k.gauge(x, y, gw, h, frac, Hue.vo2, 8f)
                        if (v90.size >= 2) k.spark(v90, x + gw + 12f, y, w - gw - 12f, h - 14f, Hue.vo2, Ink.SparkOpts(sw = 2f))
                        val lt = if (k.measure("Last 3 months", 11f) <= w - gw - 12f) "Last 3 months" else "3 months"
                        k.text(k.fit(lt, w - gw - 12f, 11f), x + gw + 12f, y + h - 1f, 11f, t.muted)
                    }
                }
            }
            "breathing" -> {
                val v14 = s.values("breathing", 14)
                val band = lastMonthRange("breathing")   // item 46: last month's lowest–highest (was mean ± 1 SD of 60 days)
                Def(Hue.resp, Glyph.Lungs, "Breathing rate", "Breathing", r?.let { nightMeta(it.day) }, r?.let { n1(it.value) }, "/min",
                    ) { x, y, w, h, _ ->
                    k.spark(v14, x, y, w, h, Hue.resp, Ink.SparkOpts(band = band, sw = 2f))
                }
            }
            "lean_fat", "lean_mass" -> {
                val rl = s.reading("lean_fat")
                val lean = rl?.let { it.parts["lean_mass"] ?: it.value }
                val fatPct = rl?.parts?.get("body_fat")
                val ls = s.series("lean_mass", Range.YEAR).map { it.v }.filter { !it.isNaN() }
                // v6 H16 Lean mass S: glyph circle, change vs the previous weigh-in top-right, value, "Lean mass"
                val ld = delta(if (ls.size >= 2) ls.last() - ls[ls.size - 2] else null, true, 0.05, 1) { "$it kg" }
                Def(Hue.lean, Glyph.Layers, "Lean mass", "Lean mass", rl?.let { Fmt.dayLabel(it.day, s.today) }, lean?.let { n1(it) }, "kg",
                    // restored (audit 1.4): change since the previous weigh-in (M chip, L pill, XL)
                    delta = ld, sShort = "Lean mass", mLine = chipLine(ld)) { x, y, w, h, _ ->
                    if (ls.size >= 2) k.spark(ls, x, y, w, h, Hue.lean, Ink.SparkOpts(sw = 2f, ma = 5))   // QA 01.9: smoothed like weight
                }
            }
            else -> null
        }
    }

    // ------------------------------------------------------------------ v4 small-tile helpers (SMALL_TILES.md)

    /** spark14: a thin line (1.6 dp, no area) with the last point as a dot r 2, in the S corner box. */
    private fun spark14(vals: List<Double>, col: Int, ma: Int = 0): (Float, Float, Float, Float) -> Unit = { x, y, w, h ->
        if (vals.size >= 2) k.spark(vals, x, y, w, h, col, Ink.SparkOpts(area = false, sw = 1.6f, dotR = 2f, pad = 2.5f, ma = ma))
    }

    /** deltaChip as the M extra line; nothing under the noise threshold. */
    private fun chipLine(dl: Delta?): ((Float, Float, Float) -> Unit)? =
        dl?.takeIf { it.short != null }?.let { d -> { x, base, mw ->
            // the chip sizes to its text: full "1.2 kg · 30 d", else "1.2 kg", else the number; never "1.2 k…"
            val arrow = when (d.dir) { Ink.Dir.UP -> "▲"; Ink.Dir.DOWN -> "▼"; Ink.Dir.NONE -> "" }
            val label = listOf(d.text, d.text.substringBefore(" · "), d.text.substringBefore(" vs "), d.short!!)
                .firstOrNull { k.measure(arrow + it, 10.5f, 700) + 14f <= mw } ?: d.short!!
            k.deltaChip(label, d.tone, d.dir, x, base - 14f, mw)
        } }

    /** L / XL pill text: full, else the part before " · " / " vs ", else the number; never "2 bpm vs yesterd…". */
    private fun pillText(d: Delta, maxW: Float): String {
        val chrome = if (d.dir == Ink.Dir.NONE) 18f else 32f   // pill pads (+ ▲ / ▼), as Ink.pill
        return listOfNotNull(d.text, d.text.substringBefore(" · "), d.text.substringBefore(" vs "), d.short)
            .firstOrNull { k.measure(it, 11.5f, 600) + chrome <= maxW } ?: d.text
    }

    /** A muted fact as the M extra line. */
    private fun factLine(text: String?): ((Float, Float, Float) -> Unit)? =
        text?.let { s0 -> { x, base, mw -> k.text(k.fit(s0, mw, 11.5f), x, base, 11.5f, t.muted) } }

    /** bandTick in the S corner: [band] in [col] with a tick at [v]; the scale covers band, values and v. */
    private fun bandCorner(v: Double?, band: Pair<Double, Double>?, values: List<Double>, col: Int): ((Float, Float, Float, Float) -> Unit)? {
        v ?: return null
        val lo = (listOfNotNull(v, band?.first) + values).min(); val hi = (listOfNotNull(v, band?.second) + values).max()
        val pad = max(1.0, (hi - lo) * 0.15)
        return { x, y, w, _ -> k.bandTick(x, y + 6f, w, 6f, lo - pad, hi + pad, band, v, col) }
    }

    private fun whtrBar(x: Float, y: Float, w: Float, v: Double, ticks: Boolean) = k.scaleBar(x, y, w, listOf(
        Triple(0.4, 0.5, Hue.zoneOk.alpha(0.55f)), Triple(0.5, 0.6, Hue.zoneWarn.alpha(0.6f)), Triple(0.6, 0.65, Hue.zoneHigh.alpha(0.55f))),
        0.4, 0.65, v.coerceIn(0.4, 0.65), ticks = if (ticks) listOf(0.4 to "0.4", 0.5 to "0.5", 0.6 to "0.6") else emptyList())

    private fun niceTicks(lo: Double, hi: Double): List<Double> {
        val span = (hi - lo).coerceAtLeast(1.0)
        val step = listOf(0.5, 1.0, 2.0, 2.5, 5.0, 10.0, 20.0, 50.0, 100.0, 500.0, 1000.0).firstOrNull { span / it <= 2.5 } ?: 1000.0
        val a = Math.ceil(lo / step) * step
        val out = generateSequence(a) { it + step }.takeWhile { it <= hi + 1e-9 }.toList()
        return if (out.isEmpty()) listOf(Math.round(lo).toDouble()) else out.take(3)
    }

    // =================================================================== workouts

    private fun workoutGlyph(w: Workout): Glyph = when (w.type) {
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> Glyph.Bike
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> Glyph.Run
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING, ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> Glyph.Walk
        else -> if (ExerciseTypes.category(w.type) == 0) Glyph.Dumbbell else Glyph.Pulse
    }

    private fun lastWorkout(size: TileSize, w: Float, h: Float) {
        val wk = s.lastWorkout ?: return empty("last_workout", w, h, "Last workout")
        val col = Hue.workoutGroup(ExerciseTypes.group(wk))
        val gl = workoutGlyph(wk)
        val title = V2Models.workoutName(wk)
        val day = Fmt.date(wk.start)
        val dayTxt = Fmt.dayLabel(day, s.today)
        val stats = buildList { add("${wk.minutes} min"); wk.kcal?.let { add("${Fmt.int(it)} kcal") } }.joinToString(" · ")
        when (size) {
            TileSize.S -> {
                val p = 12f
                k.glyphCircle(gl, col, p, p)
                k.value(wk.minutes.toString(), "min", p, h - 31f, 21f, 11f)
                k.text(k.fit(title, w - 2 * p, 11.5f, 500), p, h - 13f, 11.5f, t.muted, 500)
            }
            TileSize.M -> {
                val p = 14f; val gd = 40f
                k.glyphCircle(gl, col, p, (h - gd) / 2f, gd, 22f)
                val x = p + gd + 12f; val top = (h - 52f) / 2f; val mw = w - p - x
                k.text(k.fit(title, mw, 14.5f, 700), x, top + 15f, 14.5f, t.text, 700)
                k.text(k.fit(Fmt.rowWhen(wk.start, s.today), mw, 12f), x, top + 32f, 12f, t.muted)
                k.text(k.fit(stats, mw, 12f, 600), x, top + 50f, 12f, t.text, 600)
            }
            TileSize.L -> {
                val p = 16f
                k.labelRow(p, p - 1f, w - p, col, "Last workout", dayTxt)
                k.text(k.fit(title, w - 2 * p, 20f, 700), p, 58f, 20f, t.text, 700)
                k.text("${Fmt.hm(wk.start)} · ${wk.minutes} min", p, 75f, 12f, t.muted)
                hrKcalRow(wk, p, 99f, w - p)
                val hr = s.workoutHr(wk)
                if (hr.size >= 2) k.spark(hr, p, p + 100f, w - 2 * p, h - p - 100f - p, Hue.heart, Ink.SparkOpts(dot = false, sw = 2f, pad = 2f))
            }
            TileSize.XL -> {
                val p = 18f; val lw = 168f
                k.labelRow(p, p - 1f, w - p, col, "Last workout", "${Fmt.timestamp(wk.start, s.today)} · heart rate", 14f)
                k.glyphCircle(gl, col, p, p + 28f, 44f, 24f)
                k.wrap(title, lw - 56f, 20f, 700).forEachIndexed { i, line -> k.text(line, p + 56f, p + 28f + 18f + i * 23f, 20f, t.text, 700) }
                k.text("Time", p, h - p - 46f, 11.5f, t.muted)
                k.value(wk.minutes.toString(), "min", p, h - p - 24f, 18f, 12f)
                wk.kcal?.let {
                    k.text(k.fit("Energy", lw - 86f, 11.5f), p + 86f, h - p - 46f, 11.5f, t.muted)
                    k.value(Fmt.int(it), "kcal", p + 86f, h - p - 24f, 18f, 12f)
                }
                val hr = s.workoutHr(wk)
                val sub = buildList { wk.avgHr?.let { add("Avg HR $it") }; hr.maxOrNull()?.let { add("max ${n0(it)}") } }.joinToString(" · ")
                if (sub.isNotEmpty()) k.text(k.fit(sub, lw, 12f), p, h - p - 3f, 12f, t.muted)
                val cx = p + lw + 8f; val cw = w - cx - p - 26f; val ch = h - p - 34f - p - 16f
                if (hr.size >= 2 && cw > 40f) {
                    val ticks = niceTicks(hr.min(), hr.max())
                    val m = wk.minutes
                    k.spark(hr, cx, p + 34f, cw, ch, Hue.heart, Ink.SparkOpts(dot = false, sw = 2.2f, pad = 0f, grid = ticks,
                        min = min(hr.min(), ticks.first()) - 2, max = max(hr.max(), ticks.last()) + 2,
                        xLabels = listOf(0f to "0′", 0.5f to "${m / 2}′", 1f to "$m′")))
                }
            }
        }
    }

    private fun hrKcalRow(wk: Workout, x: Float, base: Float, right: Float) {
        var cx = x
        wk.kcal?.let {
            k.circle(cx + 3.5f, base - 4f, 3.5f, t.energy[0]); cx += 10f
            cx += k.text("${Fmt.int(it)} kcal", cx, base, 12f, t.text, 600) + 14f
        }
        wk.avgHr?.let {
            if (cx + 60f > right) return
            k.circle(cx + 3.5f, base - 4f, 3.5f, Hue.heart); cx += 10f
            k.text("$it bpm", cx, base, 12f, t.text, 600)
        }
    }

    /** Right-aligned legend "• Lean • Fat" ending at [right]; skipped when it would cross [minX]. */
    private fun legend(items: List<Pair<String, Int>>, right: Float, base: Float, minX: Float, fs: Float = 11.5f) {
        val widths = items.map { 9f + k.measure(it.first, fs) }
        var x = right - (widths.sum() + 10f * (items.size - 1))
        if (x < minX) return
        items.forEachIndexed { i, (label, col) -> k.circle(x + 3f, base - 4f, 3f, col); k.text(label, x + 9f, base, fs, t.muted); x += widths[i] + 10f }
    }

    // =================================================================== body composition

    // =================================================================== blood oxygen (v6 H16, design5/misc.png)

    /** Nightly values as vertical tracks with the value as a dot (scale: lowest − 2 … 100 %). */
    private fun nightDots(vals: List<Double?>, x: Float, y: Float, w: Float, h: Float, lo: Double) {
        val n = vals.size; if (n == 0) return
        val slot = w / n; val tw = min(7f, slot * 0.5f)
        vals.forEachIndexed { i, v ->
            val cx = x + slot * i + slot / 2f
            k.rrect(cx - tw / 2f, y, cx + tw / 2f, y + h, tw / 2f, Hue.spo2.alpha(if (t.dark) 0.35f else 0.22f))
            if (v != null) {
                val f = ((v - lo) / (100.0 - lo)).toFloat().coerceIn(0f, 1f)
                k.circle(cx, y + h - f * h, tw * 0.75f, Hue.spo2)
            }
        }
    }

    private fun spo2(size: TileSize, w: Float, h: Float) {
        val r = s.reading("spo2") ?: return empty("spo2", w, h, "Blood oxygen")
        val v14 = s.values("spo2", 14)
        val range = if (v14.size >= 2) "${n0(v14.min())}–${n0(v14.max())}" else null
        val lo = (v14.minOrNull() ?: r.value) - 2.0
        when (size) {
            TileSize.S -> TileArt.small(k, w, h, Glyph.Drop, Hue.spo2, n1(r.value), "%", "Blood oxygen", short = "Blood O₂")
            TileSize.M -> {
                val p = 14f
                k.labelRow(p, p - 1f, w - p, Hue.spo2, "Blood oxygen", null)   // QA 01.6: colour dot like every tile
                k.value(n0(r.value), "%", p, h - 29f, 26f, 13f)
                k.text(nightMeta(r.day), p, h - 12f, 11.5f, t.muted)
                // the nights start below the 18 dp title row (never over "Blood oxygen"); wider pitch for the day letters
                val vMetaW = max(k.valueWidth(n0(r.value), "%", 26f, 13f), k.measure(nightMeta(r.day), 11.5f))
                val cw = min(min(110f, w * 0.5f), max(w * 0.42f, w - 2 * p - vMetaW - 8f)); val nh = h - 2 * p - 38f
                val n7 = s.rolling7("spo2")
                if (nh >= 10f) nightDots(n7, w - p - cw, p + 20f, cw, nh, lo)
                val slot = cw / 7f
                if (nh >= 10f) s.rollingLetters.forEachIndexed { i, l -> k.text(l, w - p - cw + slot * i + slot / 2f, h - p + 2f, 10f, t.muted, 600, Paint.Align.CENTER) }
            }
            else -> {
                val p = 16f
                k.labelRow(p, p - 1f, w - p, Hue.spo2, "Blood oxygen", nightMeta(r.day), 13.5f)
                k.value(n0(r.value), "%", p, 72f, 40f, 18f)
                range?.let { k.text(k.fit("$it %", w - 2 * p, 13f), p, 94f, 13f, t.muted) }
                val n14 = run { val m = s.daily("spo2", 14).associate { it.date to it.v }; (13 downTo 0).map { m[s.today.minusDays(it.toLong())] } }
                nightDots(n14, p, 112f, w - 2 * p, h - 112f - p - 22f, lo)
                k.text("14 nights", p, h - p, 11.5f, t.muted)
            }
        }
    }

    // =================================================================== sleep

    /** Compact stage legend (Awake · REM · Light · Deep), right-aligned at [right]; skipped when it would cross [minX]. */
    private fun stageLegend(right: Float, base: Float, minX: Float) =
        legend(listOf("Awake" to Hue.awake, "REM" to Hue.rem, "Light" to Hue.light, "Deep" to t.deep), right, base, minX, 10.5f)

    private fun sleep(size: TileSize, w: Float, h: Float) {
        val r = s.reading("sleep") ?: return empty("sleep", w, h, "Sleep")
        val pr = r.parts
        val asleep = (r.value * 60).roundToInt()
        val deep = (pr["deep"] ?: 0.0).roundToInt(); val rem = (pr["rem"] ?: 0.0).roundToInt()
        val light = (pr["light"] ?: 0.0).roundToInt(); val awake = (pr["awake"] ?: 0.0).roundToInt()
        // minutes asleep without stage information ("Asleep (no stages)"), its own segment as in the app
        val unspec = (pr["asleep"] ?: 0.0).roundToInt()
        val stageParts = listOf(deep.toDouble() to t.deep, rem.toDouble() to Hue.rem, light.toDouble() to Hue.light,
            unspec.toDouble() to Hue.sleep, awake.toDouble() to Hue.awake)
        val noStages = deep + rem + light == 0 && unspec > 0
        val span = run {
            val st = pr["start"]?.toLong(); val en = pr["end"]?.toLong()
            if (st != null && en != null && st > 0 && en > 0) "${Fmt.hm(st)}–${Fmt.hm(en)}" else null
        }
        val c = Hue.sleep
        val deepTxt = Fmt.duration(deep)
        // deep-sleep segments in this theme's deep colour (lifted on dark, as the app)
        val nights = s.sleepNights.map { n -> n?.map { (v, c) -> v to (if (c == Hue.deep) t.deep else c) } }
        val nightsMax = max(9.0 * 60, s.sleepNights.filterNotNull().maxOfOrNull { n -> n.sumOf { it.first } } ?: 0.0)
        fun deepLine(x: Float, base: Float) {
            k.circle(x + 3f, base - 4f, 3f, t.deep)
            val lw = k.text("Deep ", x + 10f, base, 12f, t.muted)
            k.text(deepTxt, x + 10f + lw, base, 12f, t.text, 700)
        }
        when (size) {
            TileSize.S -> {
                val p = 12f
                k.glyphCircle(Glyph.Moon, c, p, p)
                // v6 H11 S: moon, duration, one stage bar
                k.duration(asleep, p, h - 25f, 21f)
                k.segPill(stageParts, p, h - p - 6f, w - 2 * p, 6f)
            }
            TileSize.M -> {
                val p = 14f
                k.labelRow(p, p - 1f, w - p, c, "Sleep", null)
                val mw = k.measure("Deep ", 11f) + k.measure(deepTxt, 11f, 700) + 10f
                if (!noStages && w - p - mw > p + 60f) {
                    val x = w - p - mw
                    k.circle(x + 3f, p + 8f - 4f + 5f, 3f, t.deep)
                    val lw = k.text("Deep ", x + 10f, p + 13f, 11f, t.muted)
                    k.text(deepTxt, x + 10f + lw, p + 13f, 11f, t.text, 700)
                }
                k.duration(asleep, p, 57.6f, 24f)
                k.segmentBar(stageParts, p, h - p - 7f, w - 2 * p, 7f)
                stageLegend(w - p, 57.6f, p + 90f)
            }
            TileSize.L -> {
                val p = 16f
                k.labelRow(p, p - 1f, w - p, c, "Sleep", span ?: nightMeta(r.day))
                k.duration(asleep, p, 66.8f, 32f)
                if (noStages) k.text("Asleep (no stages)", p, 85f, 12f, t.muted) else deepLine(p, 85f)
                stageLegend(w - p, 85f, p + 110f)
                k.stackBars(nights, p, p + 86f, w - 2 * p, h - p - 86f - p, nightsMax, s.rollingLetters, 6, 10f)
            }
            TileSize.XL -> {
                // v6 H11 XL (design5/sleep.png): duration, span, ONE stage bar + a 2×2 stage legend left; 7 nights right
                val p = 18f; val lw = min(w * 0.55f, 270f)
                k.labelRow(p, p - 1f, p + lw, c, "Sleep", nightMeta(r.day), 14f)
                k.duration(asleep, p, 74f, 40f)
                span?.let { k.text(it, p, 94f, 12f, t.muted) }
                k.segPill(stageParts, p, 108f, lw - 10f, 9f)
                val cells = if (noStages) listOf(Triple("Asleep", unspec, Hue.sleep), Triple("Awake", awake, Hue.awake))
                    else listOf(Triple("Deep", deep, t.deep), Triple("REM", rem, Hue.rem), Triple("Light", light, Hue.light), Triple("Awake", awake, Hue.awake))
                val cw = (lw - 10f) / 2f
                cells.forEachIndexed { i, (n, m, col) ->
                    val cx = p + (i % 2) * cw
                    val base = 138f + (i / 2) * 20f
                    k.circle(cx + 3.5f, base - 4f, 3.5f, col)
                    val nw = k.text(n, cx + 11f, base, 12f, t.muted)
                    k.text(k.fit(Fmt.duration(m), cw - nw - 22f, 12f, 700), cx + cw - 8f, base, 12f, t.text, 700, Paint.Align.RIGHT)
                }
                k.stackBars(nights, p + lw + 10f, p + 6f, w - 2 * p - lw - 10f, h - 2 * p - 6f, nightsMax, s.rollingLetters, 6, 12f)
            }
        }
    }

}
