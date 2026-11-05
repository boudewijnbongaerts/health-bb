package com.boudhealth.ui.section

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.render.ring.RingColors
import com.boudhealth.render.ring.RingPaint
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.drawGlyph
import com.boudhealth.ui.components.ringColors
import com.boudhealth.ui.theme.HB
import com.boudhealth.ui.theme.LocalHB
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.min

// =====================================================================================================================
// R3 ring calendar (IMPL_PLAN §2.5, port of out/activity/src/act.py month_cal / weeks_cal / cal_cell / week_col /
// year_grid / years_row). Apple-Fitness month of mini triple rings (outer → inner: workout, steps, heart points),
// the 6-week 1W variant (D1-B), the 1Y month grid and the All year row. One Canvas per grid (R9: no 42 composables);
// rings are drawn by render/ring RingPaint, the same painter as TripleRing. No ✓ and no numbers on rings.
// INNER: place them in the hero's right zone and let them fill the hero CONTENT height (the R3 hero frame already pads
// 22 dp; the mock-up offsets are measured from the hero top, so the drawing is shifted up by that padding and may
// reach into it). COVER: their box starts at the divider under the ring block (offsets from the box top).
// Unbounded height → the design height (calendar 380 / cover 346, month grid 380 / 156, years 300 / 140).
// =====================================================================================================================

/** "/ week" column cell (canonical type: com.boudhealth.data.WeekColumnCell). */
typealias WeekColumnCell = com.boudhealth.data.WeekColumnCell
/** All hero year cell (canonical type: com.boudhealth.data.YearRingCell). */
typealias YearRingCell = com.boudhealth.data.YearRingCell

@Immutable
data class CalendarModel(
    /** Mondays, 1..6 rows. */
    val weeks: List<LocalDate>,
    /** Month mode: days outside the month are blank; null = 6-week mode (all days shown). */
    val monthOnly: YearMonth?,
    /** Progress outer → inner (workout, steps, heart points); absent = no data (track only). */
    val rings: Map<LocalDate, FloatArray>,
    val today: LocalDate, val selected: LocalDate?,
    /** 1W: Monday of the selected week (tileHi band r 14). */
    val bandWeek: LocalDate?,
    /** 1W (D1-B): "26 Oct" in a left column. */
    val mondayLabels: Boolean,
    /** 1D (and cover 1D): "November 2026" above the grid. */
    val monthLabel: String?,
    /** Inner: icon + bar vs target + total; cover: icon only. null = no column. */
    val weekColumn: List<WeekColumnCell>?,
    /** Spoken text per day (R3Texts.calendarCell); absent days fall back to the date. */
    val a11y: Map<LocalDate, String> = emptyMap(),
)

/** HB "faint" text (kit theme faint ≈ muted at 60 %). */
internal val HB.faint: Color get() = colors.muted.copy(alpha = 0.6f)

/** Fixed design height when the parent gives no bounded height. */
internal fun Modifier.boundedOr(default: Dp): Modifier = layout { m, c ->
    val h = if (c.hasBoundedHeight) c.maxHeight else default.roundToPx()
    val p = m.measure(c.copy(minHeight = h, maxHeight = h))
    layout(p.width, p.height) { p.place(0, 0) }
}

private fun halfAlpha(argb: Int, k: Float): Int = (((argb ushr 24) * k).toInt().coerceIn(0, 255) shl 24) or (argb and 0xFFFFFF)

/** Mini rings without numbers, centred at (cx, cy); [alpha] < 1 → track only at that opacity (future). */
private fun DrawScope.miniRings(cx: Float, cy: Float, dDp: Float, progress: FloatArray?, col: RingColors, sDp: Float? = null, gapDp: Float? = null, alpha: Float = 1f) {
    val d = dDp * density
    val s = (sDp ?: RingPaint.strokeFor(dDp)) * density
    val g = (gapDp ?: if (dDp < 90f) 2f else 3f) * density
    val c = if (alpha < 1f) RingColors(col.fills, col.laps, halfAlpha(col.track, alpha), col.rim, col.text, col.muted, col.inkLight, col.inkDark) else col
    val p = if (alpha < 1f || progress == null) FloatArray(3) else progress
    RingPaint.rings(drawContext.canvas.nativeCanvas, cx - d / 2f, cy - d / 2f, d, p, c, s, g, density)
}

private fun DrawScope.ctext(m: TextMeasurer, s: String, cx: Float, cy: Float, style: TextStyle, align: Int = 0) {
    val t = m.measure(s, style, maxLines = 1)
    val x = when (align) { 0 -> cx - t.size.width / 2f; -1 -> cx; else -> cx - t.size.width }
    drawText(t, topLeft = Offset(x, cy - t.size.height / 2f))
}

private fun DrawScope.statusIcon(cx: Float, cy: Float, size: Float, icon: TileIcon, tone: Tone, hb: HB) {
    val (bg, fg) = hb.statusColors(if (icon == TileIcon.DONE) Tone.GOOD else tone)
    drawCircle(bg, size / 2f, Offset(cx, cy))
    when (icon) {
        TileIcon.DONE -> drawCheck(cx, cy, size, fg)
        TileIcon.UP -> drawTri(cx, cy, size * 0.45f, com.boudhealth.data.TrendDir.UP, fg)
        TileIcon.DOWN -> drawTri(cx, cy, size * 0.45f, com.boudhealth.data.TrendDir.DOWN, fg)
        else -> drawTri(cx, cy, size * 0.45f, com.boudhealth.data.TrendDir.FLAT, fg)
    }
}

/** Geometry of one calendar (dp, relative to the composable's top-left). */
private class CalGeo(val m: CalendarModel, cover: Boolean, w: Float, h: Float) {
    val labW = if (m.mondayLabels) (if (cover) 40f else 48f) else 0f
    val wkW = if (m.weekColumn != null && !cover) 132f else 0f
    val calW = w - labW - when { wkW > 0 -> wkW + 18f; m.mondayLabels -> 22f; else -> 26f }
    val cw = calW / 7f
    val labelTop = if (m.monthLabel != null) (if (cover) 18f else 26f) else 0f
    val yy = (if (cover) 18f else 20f) + labelTop
    val lcy = yy + 6f
    val rtop = lcy + 18f
    val pitch = min(if (cover) 50f else 60f, (h - 16f - rtop) / 6f)
    val ringD = minOf(if (cover) 26f else 36f, pitch - if (cover) 24f else 26f, cw - 8f)
    fun rowTop(r: Int) = rtop + r * pitch + (pitch - (19f + ringD)) / 2f + 2f
    fun cellX(i: Int) = labW + cw * (i + 0.5f)
    fun day(r: Int, i: Int): LocalDate? {
        val d = m.weeks[r].plusDays(i.toLong())
        return if (m.monthOnly != null && YearMonth.from(d) != m.monthOnly) null else d
    }
}

/**
 * Month / 6-week calendar of day rings with the "/ week" column. Tap a day → [onDay] (future and blank cells ignored).
 * Inner: ring D ≈ 32–36, pitch ≈ 58–60; cover D 26, pitch ≤ 50 (act.py month_cal / weeks_cal).
 */
@Composable
fun RingCalendar(m: CalendarModel, cover: Boolean, onDay: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    val hb = LocalHB.current
    val measurer = rememberTextMeasurer()
    val col = remember(hb) { hb.ringColors(hb.colors.card) }
    val desc = remember(m) {
        m.weeks.flatMap { mon -> (0..6).map { mon.plusDays(it.toLong()) } }
            .filter { (m.monthOnly == null || YearMonth.from(it) == m.monthOnly) && !it.isAfter(m.today) }
            .joinToString("; ") { m.a11y[it] ?: R3Texts.wkdDMon(it) }
    }
    val wkByMon = remember(m) { m.weekColumn.orEmpty().associateBy { it.monday } }
    val defaultH = (if (cover) 346.dp else 380.dp) + if (cover && m.monthLabel != null) 18.dp else 0.dp
    val off = if (cover) 0f else HERO_PAD_DP
    Canvas(
        modifier.fillMaxWidth().boundedOr(defaultH)
            .semantics { contentDescription = desc }
            .pointerInput(m, cover) {
                detectTapGestures { o ->
                    val g = CalGeo(m, cover, size.width / density, size.height / density + 2 * off)
                    val x = o.x / density; val y = o.y / density + off
                    val i = ((x - g.labW) / g.cw).toInt()
                    val r = ((y - g.rtop) / g.pitch).toInt()
                    if (x < g.labW || i !in 0..6 || y < g.rtop || r !in m.weeks.indices) return@detectTapGestures
                    val d = g.day(r, i) ?: return@detectTapGestures
                    if (!d.isAfter(m.today)) onDay(d)
                }
            },
    ) {
        val dp = density
        val g = CalGeo(m, cover, size.width / dp, size.height / dp + 2 * off)
        translate(0f, -off * dp) { drawCalendar(m, cover, g, measurer, col, hb, wkByMon) }
    }
}

private fun DrawScope.drawCalendar(m: CalendarModel, cover: Boolean, g: CalGeo, measurer: TextMeasurer, col: RingColors, hb: HB, wkByMon: Map<LocalDate, WeekColumnCell>) {
        val dp = density
        val letter = R3Base.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = hb.faint)
        m.monthLabel?.let { lab ->
            val t = measurer.measure(lab, R3Base.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.muted))
            val base = if (cover) 25f else (20f + 12f)
            drawText(t, topLeft = Offset((if (cover) 6f else 4f) * dp, base * dp - t.firstBaseline))
        }
        for (i in 0..6) ctext(measurer, "MTWTFSS"[i].toString(), g.cellX(i) * dp, g.lcy * dp, letter)
        // week column header: heart points glyph + "/ week" (inner) / glyph only (cover)
        val hpCol = hb.palette.label(2, hb.dark, hb.colors.card, hb.colors.text)
        if (m.weekColumn != null) {
            val gx = if (g.wkW > 0) g.labW + g.calW + 18f + 8f else g.labW + g.calW + if (m.mondayLabels) 14f else 16f
            val gs = 13f * dp
            val k = gs / size.minDimension
            translate(gx * dp - gs / 2f, g.lcy * dp - gs / 2f) { scale(k, k, Offset.Zero) { drawGlyph(Glyph.Heart, hpCol, 2.2f) } }
            if (g.wkW > 0) ctext(measurer, R3Texts.PER_WEEK, (gx + 12f) * dp, g.lcy * dp, letter.copy(color = hb.colors.muted), -1)
        }
        m.weeks.forEachIndexed { r, mon ->
            val top = g.rowTop(r)
            val rcy = top + 19f + g.ringD / 2f
            val band = m.bandWeek == mon
            if (band) {
                // clear of the week above, optically even around the digits / rings; inner 12 / 12 side insets, cover 6 from the card edge
                val gap = g.pitch - 19f - g.ringD                 // inner 7, cover 5
                val bt = top - (gap - 1.5f)
                val bb = top + 19f + g.ringD + gap + 1f
                val bx = if (cover) -6f else -10f
                drawRoundRect(hb.colors.tileHi, Offset(bx * dp, bt * dp), Size((size.width / dp - 2f * bx + 2f * (if (cover) 0f else 1f)) * dp, (bb - bt) * dp), CornerRadius(14f * dp))
            }
            if (m.mondayLabels) ctext(measurer, R3Texts.dMon(mon), 2f * dp, rcy * dp,
                R3Base.copy(fontSize = (if (cover) 10f else 10.5f).sp, fontWeight = FontWeight.SemiBold, color = if (band) hb.colors.text else hb.colors.muted), -1)
            for (i in 0..6) {
                val d = g.day(r, i) ?: continue
                val cx = g.cellX(i)
                val fut = d.isAfter(m.today)
                val sel = m.selected == d
                if (sel && !band) {
                    val p = min(5f, (g.pitch - 19f - g.ringD) / 2f + 0.5f)   // cover 3, inner 4.5: clear of the neighbouring rows
                    drawRoundRect(hb.colors.tileHi, Offset((cx - g.cw / 2f + 3f) * dp, (top - p) * dp), Size((g.cw - 6f) * dp, (19f + g.ringD + 2f * p) * dp), CornerRadius(12f * dp))
                }
                val ny = (top + 7f) * dp
                val fs = 11.5f
                when {
                    d == m.today -> { drawCircle(hb.colors.accent, 9.5f * dp, Offset(cx * dp, ny)); ctext(measurer, "${d.dayOfMonth}", cx * dp, ny, R3Base.copy(fontSize = (fs - 0.5f).sp, fontWeight = FontWeight.Bold, color = hb.colors.onAccent)) }
                    sel -> { drawCircle(hb.colors.text, 9.5f * dp, Offset(cx * dp, ny)); ctext(measurer, "${d.dayOfMonth}", cx * dp, ny, R3Base.copy(fontSize = (fs - 0.5f).sp, fontWeight = FontWeight.Bold, color = hb.colors.card)) }
                    else -> ctext(measurer, "${d.dayOfMonth}", cx * dp, ny, R3Base.copy(fontSize = fs.sp, fontWeight = FontWeight.Bold, color = if (fut) hb.faint else hb.colors.muted))
                }
                miniRings(cx * dp, rcy * dp, g.ringD, m.rings[d], col, alpha = if (fut) 0.5f else 1f)
            }
            val wk = wkByMon[mon] ?: return@forEachIndexed
            if (g.wkW > 0) {
                val x = g.labW + g.calW + 18f
                statusIcon((x + 8f) * dp, rcy * dp, 16f * dp, wk.icon, wk.tone, hb)
                val bx = x + 24f; val bw = g.wkW - 24f - 34f
                drawRoundRect(hb.colors.tileHi, Offset(bx * dp, (rcy - 3f) * dp), Size(bw * dp, 6f * dp), CornerRadius(3f * dp))
                val q = if (wk.target > 0) min(1f, wk.points.toFloat() / wk.target) else 0f
                if (q > 0f) drawRoundRect(hpCol, Offset(bx * dp, (rcy - 3f) * dp), Size(maxOf(6f, bw * q) * dp, 6f * dp), CornerRadius(3f * dp))
                ctext(measurer, R3Texts.int(wk.points.toDouble()), (x + g.wkW) * dp, rcy * dp, R3Base.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = hb.colors.text, fontFeatureSettings = "tnum"), 1)
            } else {
                val x = g.labW + g.calW + if (m.mondayLabels) 6f else 8f
                statusIcon((x + 8f) * dp, rcy * dp, 16f * dp, wk.icon, wk.tone, hb)
            }
        }
}

/** The R3 hero frame padding (inner) the hero-zone grids compensate for. */
private const val HERO_PAD_DP = 22f

/** 1Y: 12 month rings, inner 4 × 3 (D ≤ 84, "Jan" 11/700), cover 6 × 2 (month letters); current month in an accent pill; future months tracks at 35 %. */
@Composable
fun MonthRingGrid(year: Int, rings: Map<YearMonth, FloatArray>, today: LocalDate, cover: Boolean, onMonth: (YearMonth) -> Unit) {
    val hb = LocalHB.current
    val measurer = rememberTextMeasurer()
    val col = remember(hb) { hb.ringColors(hb.colors.card) }
    val ncol = if (cover) 6 else 4; val nrow = if (cover) 2 else 3
    val desc = remember(year, rings) { (1..12).filter { !YearMonth.of(year, it).atDay(1).isAfter(today) }.joinToString("; ") { R3Texts.monthName(YearMonth.of(year, it)) } }
    val off = if (cover) 0f else HERO_PAD_DP
    Canvas(
        Modifier.fillMaxWidth().boundedOr(if (cover) 156.dp else 380.dp)
            .semantics { contentDescription = desc }
            .pointerInput(year, cover) {
                detectTapGestures { o ->
                    val hh = size.height + 2 * off * density; val oy = o.y + off * density
                    val cw = size.width / ncol; val rh = (hh - (if (cover) 20f else 24f) * density) / nrow
                    val c = (o.x / cw).toInt().coerceIn(0, ncol - 1); val r = ((oy - (if (cover) 10f else 14f) * density) / rh).toInt().coerceIn(0, nrow - 1)
                    val ym = YearMonth.of(year, r * ncol + c + 1)
                    if (!ym.atDay(1).isAfter(today)) onMonth(ym)
                }
            },
    ) {
        val dp = density
        val w = size.width / dp; val h = size.height / dp + 2 * off
        val cw = w / ncol; val rh = (h - if (cover) 20f else 24f) / nrow
        val d = minOf(cw - 22f, rh - 30f, 84f)
        translate(0f, -off * dp) {
        for (m in 0 until 12) {
            val r = m / ncol; val c = m % ncol
            val ccx = cw * (c + 0.5f); val top = (if (cover) 10f else 14f) + r * rh
            val rcy = top + (rh - d - 24f) / 2f + d / 2f
            val ym = YearMonth.of(year, m + 1)
            val lab = if (cover) "JFMAMJJASOND"[m].toString() else R3Texts.monShort(ym.atDay(1))
            val ly = rcy + d / 2f + 15f
            val style = R3Base.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = hb.colors.muted)
            if (ym.atDay(1).isAfter(today)) {
                miniRings(ccx * dp, rcy * dp, d, null, col, if (cover) 4f else 8f, if (cover) 1.5f else 2f, alpha = 0.35f)
                ctext(measurer, lab, ccx * dp, ly * dp, style.copy(color = hb.faint)); continue
            }
            miniRings(ccx * dp, rcy * dp, d, rings[ym] ?: FloatArray(3), col, if (cover) 4f else 8f, if (cover) 1.5f else 2f)
            if (ym == YearMonth.from(today)) {
                val t = measurer.measure(lab, style)
                val tw = t.size.width / dp
                drawRoundRect(hb.colors.accent, Offset((ccx - tw / 2f - 8f) * dp, (ly - 9f) * dp), Size((tw + 16f) * dp, 18f * dp), CornerRadius(9f * dp))
                ctext(measurer, lab, ccx * dp, ly * dp, style.copy(color = hb.colors.onAccent))
            } else ctext(measurer, lab, ccx * dp, ly * dp, style)
        }
        }
    }
}

/** All: one ring per year (D ≤ 128 inner, 60 cover), year 13/700 (current year in an accent pill); inner: h / steps / pts under each ring in the ring colours. */
@Composable
fun YearRingRow(years: List<YearRingCell>, today: LocalDate, cover: Boolean, onYear: (Int) -> Unit) {
    val hb = LocalHB.current
    val measurer = rememberTextMeasurer()
    val col = remember(hb) { hb.ringColors(hb.colors.card) }
    val desc = remember(years) { years.joinToString("; ") { R3Texts.yearCell(it) } }
    val off = if (cover) 0f else HERO_PAD_DP
    Canvas(
        Modifier.fillMaxWidth().boundedOr(if (cover) 140.dp else 300.dp)
            .semantics { contentDescription = desc }
            .pointerInput(years) {
                detectTapGestures { o ->
                    if (years.isEmpty()) return@detectTapGestures
                    val i = (o.x / (size.width / years.size)).toInt().coerceIn(0, years.lastIndex)
                    onYear(years[i].year)
                }
            },
    ) {
        if (years.isEmpty()) return@Canvas
        val dp = density
        val w = size.width / dp; val h = size.height / dp + 2 * off
        val cw = w / years.size
        val d = min(cw - 24f, if (cover) 60f else 128f).coerceAtLeast(12f)
        val cy = h / 2f - if (cover) 12f else 46f
        val cols = (0..2).map { hb.palette.label(it, hb.dark, hb.colors.card, hb.colors.text) }
        translate(0f, -off * dp) {
        years.forEachIndexed { i, y ->
            val c = cw * (i + 0.5f)
            miniRings(c * dp, cy * dp, d, y.progress, col, if (cover) 6f else 11f, if (cover) 1.5f else 3f)
            val ly = cy + d / 2f + 22f
            if (!cover) {
                listOf("${R3Texts.int(y.hours)} h", R3Texts.short(y.steps), R3Texts.short(y.points)).forEachIndexed { k, t ->
                    ctext(measurer, t, c * dp, (ly + 26f + k * 18f) * dp, R3Base.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = cols[k], fontFeatureSettings = "tnum"))
                }
            }
            val style = R3Base.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = hb.colors.text)
            if (y.year == today.year) {
                val tw = measurer.measure("${y.year}", style).size.width / dp
                drawRoundRect(hb.colors.accent, Offset((c - tw / 2f - 9f) * dp, (ly - 10f) * dp), Size((tw + 18f) * dp, 20f * dp), CornerRadius(10f * dp))
                ctext(measurer, "${y.year}", c * dp, ly * dp, style.copy(color = hb.colors.onAccent))
            } else ctext(measurer, "${y.year}", c * dp, ly * dp, style)
        }
        }
    }
}
