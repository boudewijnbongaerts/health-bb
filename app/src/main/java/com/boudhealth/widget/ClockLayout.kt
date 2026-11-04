package com.boudhealth.widget

import kotlin.math.max
import kotlin.math.min

/**
 * V2 (D21, V2-122/126): the four clock fonts of "Health BB · Clock". A launcher clock (TextClock in RemoteViews) cannot
 * take a Typeface from code, so every font is its own layout file (res/layout/hb_clock_<name>.xml). The families are
 * Android system families, so they always resolve; the Light and Bold dates use T4's bundled Inter (res/font/inter_*,
 * "inter-<weight>" here → RingFonts.face). The setup preview uses the same faces (ClockPaint.typeface).
 *
 * [timeSample] / [dateSample] are the widest strings the formats in the layout can produce; they size the clock box.
 */
enum class ClockFont(
    val label: String,
    val family: String,
    val bold: Boolean,
    /** Hours above minutes (two TextClocks), minutes in the accent colour. */
    val stacked: Boolean,
    val dateFamily: String,
    val dateBold: Boolean,
    val dateItalic: Boolean,
    val dateCaps: Boolean,
    /** Extra letter spacing of the date, in em. */
    val dateTracking: Float,
    /** Date in the accent colour (Mono). */
    val dateAccent: Boolean,
    /** java.time patterns, 24 h (the layouts carry the same patterns; 12 h formats are never wider). */
    val timePattern: String,
    val datePattern: String,
    val timeSample: String,
    val dateSample: String,
) {
    LIGHT("Light", "sans-serif-light", false, false, "inter-400", false, false, false, 0f, false, "HH:mm", "EEE d MMM", "08:42", "Wed 30 Sep"),
    BOLD("Bold", "sans-serif-condensed", true, true, "inter-500", false, false, true, 0.12f, false, "HH", "EEE d MMM", "08", "WED 30 SEP"),
    SERIF("Serif", "serif", false, false, "serif", false, true, false, 0f, false, "H:mm", "EEEE, d MMMM", "18:42", "Wednesday, 30 September"),
    MONO("Mono", "monospace", false, false, "monospace", false, false, true, 0.18f, true, "HH:mm", "EEE dd MMM", "08:42", "WED 30 SEP");

    /** The setup preview's sample time ("8:42" style) for this font. */
    val previewTime: String get() = if (this == SERIF) "8:42" else "08:42"
}

/**
 * Font measurements in em (text size 1), taken with android.graphics.Paint on the device itself ([ClockPaint.measure]),
 * so the launcher's TextClock (includeFontPadding = false: its box is ascent + descent) lands where the layout expects.
 * [digitTop] = top of the digit "0" above the baseline; [width] = advance of the sample string.
 */
data class FontEm(val ascent: Float, val descent: Float, val digitTop: Float, val width: Float) {
    val lineH: Float get() = ascent + descent
    companion object {
        /** Roboto-like fallback when measuring fails (Roboto: ascent 0.928, descent 0.244, cap 0.711). */
        fun fallback(chars: Int, advance: Float = 0.56f) = FontEm(0.928f, 0.244f, 0.711f, chars * advance)
    }
}

data class ClockMetrics(val time: FontEm, val date: FontEm) {
    companion object {
        fun fallback(f: ClockFont) = ClockMetrics(
            FontEm.fallback(f.timeSample.length, if (f == ClockFont.MONO) 0.6f else if (f.stacked) 0.5f else 0.56f),
            FontEm.fallback(f.dateSample.length, 0.55f),
        )
    }
}

/** What the clock setup stores (a subset of WidgetConfigV2), as plain values. */
data class ClockSpec(
    val font: ClockFont,
    /** 50–100: the whole group (rings + clock) as a share of the widget. */
    val clockSize: Int,
    /** 30–70: share of the group given to the rings (the rest to the clock). */
    val ringShare: Int,
    val showDate: Boolean,
    val showRings: Boolean,
)

/** A rectangle in dp. */
data class BoxDp(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right get() = left + width
    val bottom get() = top + height
    val cx get() = left + width / 2f
    val cy get() = top + height / 2f
}

/**
 * Where everything of one clock widget goes (all dp, relative to the widget's top-left):
 *  - [rings]: the rings bitmap (square), null when rings are off;
 *  - [clock]: the clock RemoteViews box (time + date); its children start at the top, left-aligned when [wide], centred otherwise;
 *  - [timeSize] / [dateSize]: text sizes in dp; [line2Top] = top margin of the minutes line (Bold, negative pulls it up);
 *    [dateTop] = top margin of the date;
 *  - [digitLine]: the y of the digits' visual centre = the rings' centre (wide), or the x of the shared centre line (stacked).
 */
data class ClockGeometry(
    val wide: Boolean,
    val rings: BoxDp?,
    val clock: BoxDp,
    val timeSize: Float,
    val dateSize: Float,
    val line2Top: Float,
    val dateTop: Float,
    val digitLine: Float,
    /** Group bounds (rings + gap + clock), for proof sheets: equal side margins. */
    val group: BoxDp,
)

/**
 * V2-126 alignment fix (design6/v2008_clock_styles.png, "V2 rule"):
 *  1. rings and clock are ONE group, centred in the widget with equal margins on both sides;
 *  2. side by side: the centre of the digits sits on the rings' horizontal centre line (the date hangs below);
 *     stacked (narrow widgets): time, date and rings share one vertical centre line;
 *  3. gap = 6 % of the width (side by side) / 4 % of the height (stacked); the clock box height follows the time size
 *     (no fixed 50 / 66 dp box, which clipped large sizes in v6).
 * Pure: the widget (WidgetRender / DashboardWidget) and the setup preview (Renderer.renderPreview) call the same function.
 */
object ClockLayout {
    const val PAD = 8f
    const val MIN_RINGS = 36f

    /** Side by side when the widget is clearly wider than tall. */
    fun isWide(w: Float, h: Float): Boolean = w >= h * 1.3f

    fun dateSizeFor(f: ClockFont, time: Float): Float = (time * (if (f.dateCaps) 0.2f else 0.24f)).coerceIn(10f, 20f)
    fun dateGap(time: Float): Float = (time * 0.08f).coerceIn(2f, 8f)
    /** Space between the digits of the two stacked lines (Bold). */
    private fun lineGap(time: Float): Float = time * 0.1f

    /** Time block (one line, or two for Bold) for text size [s]: height, digit centre from its top, minutes-line margin. */
    private data class Block(val h: Float, val digitC: Float, val line2Top: Float, val w: Float, val dateTop: Float, val dateSize: Float)

    /** [maxW] = width the box may take: the date shrinks to fit it (never the time; a long serif date must not shrink the digits). */
    private fun block(f: ClockFont, m: ClockMetrics, s: Float, showDate: Boolean, maxW: Float): Block {
        val t = m.time
        val lineH = t.lineH * s
        var h: Float
        val digitC: Float
        var line2Top = 0f
        if (f.stacked) {
            // line 2 starts so that the gap between the two rows of digits is lineGap
            val line2 = (t.digitTop * s + lineGap(s)) - lineH            // margin added to the natural position (negative)
            line2Top = line2
            val top2 = lineH + line2
            h = top2 + lineH
            val digitTop1 = (t.ascent - t.digitTop) * s
            val base2 = top2 + t.ascent * s
            digitC = (digitTop1 + base2) / 2f
        } else {
            h = lineH
            digitC = (t.ascent - t.digitTop / 2f) * s
        }
        var w = t.width * s
        var dateTop = 0f
        val ds = min(dateSizeFor(f, s), maxW / m.date.width.coerceAtLeast(0.1f)).coerceAtLeast(7f)
        if (showDate) {
            // the date box starts right under the digits' baseline (the time box's descent counts towards the gap)
            dateTop = dateGap(s) - t.descent * s
            h += dateTop + m.date.lineH * ds
            w = max(w, m.date.width * ds)
        }
        return Block(h, digitC, line2Top, w, dateTop, ds)
    }

    fun compute(w: Float, h: Float, spec: ClockSpec, m: ClockMetrics): ClockGeometry {
        val availW = max(40f, w - 2 * PAD)
        val availH = max(40f, h - 2 * PAD)
        val scale = spec.clockSize.coerceIn(50, 100) / 100f
        val share = if (spec.showRings) spec.ringShare.coerceIn(30, 70) / 100f else 0f
        val wide = isWide(w, h)

        /** Largest time size with [fits] true (binary search, 6–400 dp). */
        fun largest(maxW: Float, fits: (Block, Float) -> Boolean): Pair<Float, Block> {
            var lo = 6f; var hi = 400f
            repeat(28) {
                val mid = (lo + hi) / 2f
                if (fits(block(spec.font, m, mid, spec.showDate, maxW), mid)) lo = mid else hi = mid
            }
            return lo to block(spec.font, m, lo, spec.showDate, maxW)
        }

        if (!spec.showRings) {
            // time (and date) alone, centred; digit centre on the widget's centre line when the date fits under it
            val bw = availW * scale; val bh = availH * scale
            val (s, b) = largest(bw) { b, _ -> b.w <= bw && b.h <= bh }
            val cy = h / 2f
            val top = (cy - b.digitC).coerceIn(PAD, max(PAD, h - PAD - b.h))
            val left = (w - b.w) / 2f
            val clock = BoxDp(left, top, b.w, b.h)
            return ClockGeometry(false, null, clock, s, b.dateSize, b.line2Top, b.dateTop, w / 2f, clock)
        }

        if (wide) {
            val gap = w * 0.06f
            val inner = availW * scale
            val ringsMaxH = availH * scale
            val d = min(max(MIN_RINGS, (inner - gap) * share), ringsMaxH)
            val clockW = max(24f, inner - gap - d)
            // the digit centre sits on the ring centre (h / 2): the block must fit above and below it
            val half = availH / 2f
            val (s, b) = largest(clockW) { b, _ -> b.w <= clockW && b.digitC <= half && (b.h - b.digitC) <= half }
            val groupW = d + gap + b.w
            val left = (w - groupW) / 2f
            val cy = h / 2f
            val rings = BoxDp(left, cy - d / 2f, d, d)
            val clock = BoxDp(left + d + gap, cy - b.digitC, b.w, b.h)
            return ClockGeometry(true, rings, clock, s, b.dateSize, b.line2Top, b.dateTop, cy, BoxDp(left, min(rings.top, clock.top), groupW, max(rings.bottom, clock.bottom) - min(rings.top, clock.top)))
        }

        // stacked: time + date on top, rings below, one vertical centre line
        val gap = h * 0.04f
        val inner = availH * scale
        val d = min(max(MIN_RINGS, (inner - gap) * share), availW * scale)
        val clockH = max(16f, inner - gap - d)
        val (s, b) = largest(availW * scale) { b, _ -> b.w <= availW * scale && b.h <= clockH }
        val groupH = b.h + gap + d
        val top = (h - groupH) / 2f
        val cx = w / 2f
        val clock = BoxDp(cx - b.w / 2f, top, b.w, b.h)
        val rings = BoxDp(cx - d / 2f, top + b.h + gap, d, d)
        val gw = max(b.w, d)
        return ClockGeometry(false, rings, clock, s, b.dateSize, b.line2Top, b.dateTop, cx, BoxDp(cx - gw / 2f, top, gw, groupH))
    }
}
