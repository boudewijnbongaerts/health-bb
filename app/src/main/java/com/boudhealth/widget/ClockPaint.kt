package com.boudhealth.widget

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import com.boudhealth.render.ring.RingFonts
import kotlin.math.max
import kotlin.math.min

/**
 * V2-122/126: the clock fonts measured and drawn with android.graphics (no Compose, no RemoteViews).
 *  - [measure]: the em metrics ClockLayout needs, read from the same system family the layout XML names, on this device
 *    (a One UI font or a user font changes the metrics; measuring here keeps the digits on the ring line anyway);
 *  - [draw]: the setup preview's clock, placed exactly like the TextClocks (box top + ascent = baseline), so the preview
 *    shows the widget's alignment.
 */
object ClockPaint {
    private val cache = HashMap<ClockFont, ClockMetrics>()

    /** A system family, or "inter-<weight>" = T4's bundled Inter (RingFonts; the layouts use @font/inter_*). */
    fun typeface(family: String, bold: Boolean, italic: Boolean): Typeface =
        if (family.startsWith("inter-")) RingFonts.face(family.removePrefix("inter-").toIntOrNull() ?: 400)
        else Typeface.create(family, (if (bold) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0))

    private fun timePaint(f: ClockFont, size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = typeface(f.family, f.bold, false); textSize = size
    }

    private fun datePaint(f: ClockFont, size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = typeface(f.dateFamily, f.dateBold, f.dateItalic); textSize = size; letterSpacing = f.dateTracking
    }

    private fun em(p: Paint, sample: String): FontEm {
        val fm = p.fontMetrics
        val b = Rect()
        p.getTextBounds("0", 0, 1, b)
        val s = p.textSize
        return FontEm(-fm.ascent / s, fm.descent / s, -b.top / s, p.measureText(sample) / s, b.left / s)
    }

    /**
     * Wide layout: the date's start indent = the time font's left side bearing, so the date's left edge meets the digits'
     * ink (it grows with the time size); capped so the widest date still fits the clock box. 0 in the centred layouts.
     */
    fun dateIndent(g: ClockGeometry, m: ClockMetrics): Float =
        if (!g.wide) 0f else min(max(0f, m.time.lsb * g.timeSize), max(0f, g.clock.width - m.date.width * g.dateSize))

    fun measure(f: ClockFont): ClockMetrics = synchronized(cache) {
        cache.getOrPut(f) {
            runCatching { ClockMetrics(em(timePaint(f, 100f), f.timeSample), em(datePaint(f, 100f), f.dateSample)) }
                .getOrElse { ClockMetrics.fallback(f) }
        }
    }

    /**
     * Draws time (+ date) into [g].clock on [c] (dp canvas). [lines] = the time text per line ("08:42", or "08" and "42"
     * for Bold). [ink] = text colour, [accent] = Bold minutes / Mono date colour, [shadow] = soft shadow on wallpaper.
     */
    fun draw(c: Canvas, g: ClockGeometry, f: ClockFont, m: ClockMetrics, lines: List<String>, date: String?, ink: Int, accent: Int, shadow: Boolean) {
        val box = g.clock
        fun x(p: Paint, text: String): Float = if (g.wide) box.left else box.cx - p.measureText(text) / 2f
        fun shade(p: Paint) { if (shadow) p.setShadowLayer(4f, 0f, 1f, 0x66000000) }
        val tp = timePaint(f, g.timeSize).also { shade(it) }
        val lineH = m.time.lineH * g.timeSize
        var top = box.top
        lines.forEachIndexed { i, t ->
            if (i > 0) top += g.line2Top
            tp.color = if (i == 1 && f.stacked) accent else ink
            c.drawText(t, x(tp, t), top + m.time.ascent * g.timeSize, tp)
            top += lineH
        }
        if (date != null) {
            val dp = datePaint(f, g.dateSize).also { shade(it) }
            dp.color = if (f.dateAccent) accent else ink
            val text = if (f.dateCaps) date.uppercase() else date
            top += g.dateTop
            // wide: the date's left edge on the digits' ink edge (the time font's side bearing grows with its size)
            c.drawText(text, x(dp, text) + dateIndent(g, m), top + m.date.ascent * g.dateSize, dp)
        }
    }
}
