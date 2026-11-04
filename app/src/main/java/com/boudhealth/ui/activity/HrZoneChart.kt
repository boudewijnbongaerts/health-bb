package com.boudhealth.ui.activity

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.ui.components.Fmt
import com.boudhealth.ui.components.rememberHaptics
import com.boudhealth.ui.theme.Cat
import com.boudhealth.ui.theme.HB
import com.boudhealth.ui.theme.LocalHB
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Zone colours for this theme (v4.1: ≥ 3 : 1 on the card in light mode, [com.boudhealth.ui.theme.zoneColorsForTheme]), index 0 below … 3 peak. */
internal val ZoneColors: List<Color> @Composable get() = LocalHB.current.azm
internal val ZoneNames = listOf("Below zones", "Fat burn", "Cardio", "Peak")

/**
 * Session heart-rate chart (C_SPEC §2): zone bands (fat burn / cardio / peak, r 6, 12 % / 14 %, 2 dp gaps) across the
 * plot, zone names in a 58 dp right gutter, the HR line stroked with a vertical hard-stop gradient so it takes the
 * colour of the zone it is in, dashed average line with an "Avg" pill, max point with an inverse pill, x labels,
 * scrub (horizontal drag) with an inverse tooltip "132 bpm · 12:17 · Cardio" and a haptic tick per minute.
 * Fewer than 5 samples: dots only.
 */
@Composable
internal fun HrZoneChart(
    samples: List<HrSample>,
    limits: ZoneLimits,
    avg: Int?,
    startMs: Long,
    endMs: Long,
    modifier: Modifier = Modifier,
    height: Dp = 250.dp,
) {
    val hb = LocalHB.current
    val ZoneColors = hb.azm
    val measurer = rememberTextMeasurer()
    val haptics = rememberHaptics()
    var scrub by remember { mutableStateOf<Int?>(null) }
    val n = samples.size

    Canvas(
        modifier.fillMaxWidth().height(height).pointerInput(samples) {
            val gutter = 58.dp.toPx()
            val slop = viewConfiguration.touchSlop
            fun indexAt(x: Float): Int? {
                if (n == 0) return null
                val plotW = size.width - gutter
                val t = startMs + ((x / plotW).coerceIn(0f, 1f) * (endMs - startMs)).toLong()
                var best = 0; var bd = Long.MAX_VALUE
                samples.forEachIndexed { i, s -> val d = abs(s.ms + 30_000L - t); if (d < bd) { bd = d; best = i } }
                return best
            }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var decided = false
                var scrubbing = false
                val start = down.position
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    if (!decided) {
                        val dx = abs(ch.position.x - start.x); val dy = abs(ch.position.y - start.y)
                        if (dx > slop || dy > slop) {
                            decided = true
                            scrubbing = dx >= dy
                        }
                    }
                    if (scrubbing) {
                        ch.consume()
                        val i = indexAt(ch.position.x)
                        if (i != scrub) { scrub = i; if (i != null) haptics.tick() }
                    } else if (decided) break
                }
                scrub = null
            }
        },
    ) {
        val gutter = 58.dp.toPx()
        val plotW = size.width - gutter
        val top = 30.dp.toPx()
        val bottom = size.height - 30.dp.toPx()
        val minB = samples.minOfOrNull { it.bpm } ?: limits.fat
        val maxB = samples.maxOfOrNull { it.bpm } ?: limits.cardio
        val yLo = floor(min(limits.fat - 15, minB - 5) / 10.0) * 10.0
        val yHi = ceil(max(limits.peak + 12, maxB + 6) / 5.0) * 5.0
        fun y(v: Double): Float = (bottom - (v - yLo) / (yHi - yLo) * (bottom - top)).toFloat()
        fun x(ms: Long): Float = ((ms - startMs).toDouble() / max(1L, endMs - startMs) * plotW).toFloat().coerceIn(0f, plotW)
        val bandAlpha = if (hb.dark) 0.14f else 0.12f
        val gap = 1.dp.toPx()
        val r6 = CornerRadius(6.dp.toPx())

        // zone bands + gutter names
        val bands = listOf(
            Triple(1, limits.fat.toDouble(), limits.cardio.toDouble()),
            Triple(2, limits.cardio.toDouble(), limits.peak.toDouble()),
            Triple(3, limits.peak.toDouble(), yHi),
        )
        val nameStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
        for ((z, lo, hi) in bands) {
            val yt = y(hi) + gap; val yb = y(lo) - gap
            if (yb <= yt) continue
            drawRoundRect(ZoneColors[z].copy(alpha = bandAlpha), Offset(0f, yt), Size(plotW, yb - yt), r6)
            val r = measurer.measure(ZoneNames[z], nameStyle.copy(color = hb.legible(ZoneColors[z], hb.colors.surface)))
            drawText(r, topLeft = Offset(size.width - r.size.width, (yt + yb) / 2f - r.size.height / 2f))
        }

        val pts = samples.map { Offset(x(it.ms + 30_000L), y(it.bpm.toDouble())) }

        // HR line with hard-stop zone gradient (or dots for very few samples)
        if (n in 1..4) {
            samples.forEachIndexed { i, s -> drawCircle(ZoneColors[limits.zoneOf(s.bpm)], 4.dp.toPx(), pts[i]) }
        } else if (n >= 5) {
            fun f(v: Int) = (y(v.toDouble()) / size.height).coerceIn(0f, 1f)
            val fp = f(limits.peak); val fc = f(limits.cardio); val ff = f(limits.fat)
            val brush = Brush.verticalGradient(
                0f to ZoneColors[3], fp to ZoneColors[3],
                fp to ZoneColors[2], fc to ZoneColors[2],
                fc to ZoneColors[1], ff to ZoneColors[1],
                ff to ZoneColors[0], 1f to ZoneColors[0],
                startY = 0f, endY = size.height,
            )
            // break the line where minutes are missing for > 3 minutes
            var run = ArrayList<Offset>()
            val runs = ArrayList<List<Offset>>()
            samples.forEachIndexed { i, s ->
                if (i > 0 && s.ms - samples[i - 1].ms > 3 * 60_000L) { runs += run; run = ArrayList() }
                run += pts[i]
            }
            runs += run
            val st = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            for (rp in runs) {
                if (rp.size == 1) drawCircle(brush, 2.dp.toPx(), rp[0]) else drawPath(smoothPath(rp), brush, style = st)
            }
        }

        // average line + pill
        if (avg != null && n > 0) {
            val ya = y(avg.toDouble())
            drawLine(hb.colors.text.copy(alpha = 0.5f), Offset(0f, ya), Offset(plotW, ya), 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
            pill(measurer, hb, "Avg $avg", 8.dp.toPx(), ya - 6.dp.toPx(), hb.colors.tile, hb.colors.text, alignCenter = false)
        }

        // max point
        val maxI = samples.indices.maxByOrNull { samples[it].bpm }
        if (maxI != null && n >= 2 && scrub == null) {
            val p = pts[maxI]
            drawCircle(hb.colors.surface, 7.5.dp.toPx(), p)
            drawCircle(ZoneColors[limits.zoneOf(samples[maxI].bpm)], 5.dp.toPx(), p)
            pill(measurer, hb, "${samples[maxI].bpm} max", clampF(p.x, 36.dp.toPx(), plotW - 36.dp.toPx()), p.y - 10.dp.toPx(),
                hb.colors.inverse, hb.colors.onInverse, alignCenter = true)
        }

        // x labels
        val durMin = (endMs - startMs) / 60_000L
        val step = when { durMin <= 30 -> 5L; durMin <= 75 -> 15L; durMin <= 180 -> 30L; else -> 60L }
        val lblStyle = TextStyle(fontSize = 11.sp, color = hb.colors.muted, fontFeatureSettings = "tnum")
        var t = startMs
        while (t <= endMs) {
            val r = measurer.measure(Fmt.hm(t), lblStyle)
            val cx = x(t)
            drawText(r, topLeft = Offset(clampF(cx - r.size.width / 2f, 0f, plotW - r.size.width), bottom + 12.dp.toPx()))
            t += step * 60_000L
        }

        // scrub
        val sel = scrub?.takeIf { it in samples.indices }
        if (sel != null) {
            val s = samples[sel]; val p = pts[sel]
            val z = limits.zoneOf(s.bpm)
            drawLine(hb.colors.text.copy(alpha = 0.35f), Offset(p.x, top - 4.dp.toPx()), Offset(p.x, bottom), 1.5.dp.toPx())
            drawCircle(hb.colors.surface, 7.dp.toPx(), p)
            drawCircle(ZoneColors[z], 5.dp.toPx(), p)
            pill(measurer, hb, "${s.bpm} bpm · ${Fmt.hm(s.ms)} · ${ZoneNames[z]}", clampF(p.x, 80.dp.toPx(), plotW - 80.dp.toPx()), top - 4.dp.toPx(),
                hb.colors.inverse, hb.colors.onInverse, alignCenter = true)
        }
    }
}

private fun clampF(v: Float, lo: Float, hi: Float): Float = if (hi < lo) v else v.coerceIn(lo, hi)

/** Catmull–Rom through [p] as cubic Béziers. */
private fun smoothPath(p: List<Offset>): Path {
    val path = Path()
    path.moveTo(p[0].x, p[0].y)
    for (i in 0 until p.size - 1) {
        val p0 = p[max(0, i - 1)]; val p1 = p[i]; val p2 = p[i + 1]; val p3 = p[min(p.size - 1, i + 2)]
        val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
        val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
        path.cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
    }
    return path
}

/** Small label pill (h 22, r 11) whose bottom edge sits at [bottom]; [x] = left edge or centre. */
private fun DrawScope.pill(m: TextMeasurer, hb: HB, text: String, x: Float, bottom: Float, bg: Color, fg: Color, alignCenter: Boolean) {
    val r = m.measure(text, TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = fg, fontFeatureSettings = "tnum"))
    val padH = 9.dp.toPx()
    val h = 22.dp.toPx()
    val w = r.size.width + padH * 2
    val left = (if (alignCenter) x - w / 2f else x).coerceIn(0f, max(0f, size.width - w))
    val topY = bottom - h
    drawRoundRect(bg, Offset(left, topY), Size(w, h), CornerRadius(h / 2f))
    drawText(r, topLeft = Offset(left + padH, topY + (h - r.size.height) / 2f))
}
