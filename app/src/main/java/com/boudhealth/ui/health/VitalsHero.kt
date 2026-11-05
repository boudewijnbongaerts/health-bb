package com.boudhealth.ui.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.data.SectionPeriod
import com.boudhealth.data.TrendDir
import com.boudhealth.ui.section.DeltaPill
import com.boudhealth.ui.section.DeltaPillModel
import com.boudhealth.ui.section.Tone
import com.boudhealth.ui.section.R3Texts
import com.boudhealth.ui.section.ScrubState
import com.boudhealth.ui.section.SectionContext
import com.boudhealth.ui.section.rememberScrub
import com.boudhealth.ui.section.scrubAndTap
import com.boudhealth.ui.theme.LocalHB
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.min

// =====================================================================================================================
// Vitals R3 hero, D3-A (IMPL_PLAN §3V, out/vitals/vitals_r3.py hero_A / hero_left / range_rows / lanes):
// left = featured resting heart rate (label, 56/700 value, date, pill, range block: this period · previous period ·
// same period a year ago, on one scale, the day's value as a dot); right = four lanes on ONE x-axis with ONE cursor:
// heart rate daily / weekly range, HRV, breathing rate, blood oxygen, each over last period's lowest–highest (grey band,
// keyed with the month), outside days ringed. Inner: content 996 × 380 (frame padding 22); cover: wraps (≈ 500).
// =====================================================================================================================

/** Inner geometry (from the hero CONTENT top-left; the mock-up's numbers minus the 22 dp frame padding). */
private val LEFT_W = 296.dp
private val DIVIDER_X = 318.dp
private val LANES_X = 340.dp

@Composable
fun VitalsHero(ctx: SectionContext) {
    val m = VitalsModels.of(ctx) ?: return
    val hb = LocalHB.current
    val scrub = rememberScrub(ctx.scope to "vitals-hero")
    val ld = remember(m) { LaneData(m) }
    if (ld.buckets.none { it.value != null } && m.reading("rhr").at == null) {
        Box(Modifier.fillMaxWidth().height(if (ctx.wide) 380.dp else 120.dp), contentAlignment = Alignment.Center) {
            Text(R3Texts.NO_DATA_PERIOD, fontSize = 13.sp, color = hb.colors.muted)
        }
        return
    }
    if (ctx.wide) {
        Row(Modifier.fillMaxSize()) {
            HeroLeft(m, cover = false, modifier = Modifier.width(LEFT_W).fillMaxHeight())
            Spacer(Modifier.width(DIVIDER_X - LEFT_W))
            Box(Modifier.width(1.dp).fillMaxHeight().background(hb.colors.grid.copy(alpha = 0.8f)))
            Spacer(Modifier.width(LANES_X - DIVIDER_X - 1.dp))
            VitalsLanes(ctx, m, ld, scrub, cover = false, modifier = Modifier.weight(1f).fillMaxHeight().padding(top = 2.dp))
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            HeroLeft(m, cover = true, modifier = Modifier.fillMaxWidth().height(if (m.f.isAll) 250.dp else 220.dp))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(hb.colors.grid.copy(alpha = 0.8f)))
            Spacer(Modifier.height(13.dp))
            VitalsLanes(ctx, m, ld, scrub, cover = true, modifier = Modifier.fillMaxWidth().height(LANES_H_COVER))
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Left: featured resting heart rate + range block
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun HeroLeft(m: VitalsModel, cover: Boolean, modifier: Modifier) {
    val hb = LocalHB.current
    val f = m.f
    val c = vitalColor("rhr")
    val rd = m.reading("rhr")
    val vp = m.pill("rhr")
    // both days above and below the range: one pill each on two rows (pitch 30) instead of one ellipsized pill
    val split: List<DeltaPillModel>? = (vp as? VPill.Out)?.o?.takeIf { it.above.isNotEmpty() && it.below.isNotEmpty() }?.let { o ->
        listOfNotNull(
            R3Texts.outsidePill(o.above.size, 0, f.period)?.let { DeltaPillModel(TrendDir.UP, it, Tone.NEUTRAL) },
            R3Texts.outsidePill(0, o.below.size, f.period)?.let { DeltaPillModel(TrendDir.DOWN, it, Tone.NEUTRAL) },
        ).takeIf { it.size == 2 }
    }
    val pills = split ?: listOfNotNull(vPill("rhr", vp, f))
    // text lines (baselines from the content top): label 16.5 · value 86 (cover 52.4) · date +28 (+22) · pill +58 (+46)
    val vfs = if (cover) 40f else 56f
    val vb = if (cover) 18f + vfs * 0.86f else 86f
    val ctxB = vb + if (cover) 22f else 28f
    val pillC = vb + if (cover) 46f else 58f
    val blockTop = pillC + (if (cover) 26f else 30f) + 30f * (pills.size - 1).coerceAtLeast(0)
    Box(modifier) {
        Box(Modifier.offset(0.dp, 8.dp).size(8.dp).background(c, CircleShape))
        Text(R3Texts.RESTING_HR, fontSize = (if (cover) 12.5f else 13f).sp, fontWeight = FontWeight.SemiBold, color = hb.legible(c), maxLines = 1,
            modifier = Modifier.padding(start = 14.dp).paddingFromBaseline(top = 16.5.dp))
        Text(buildAnnotatedString {
            withStyle(SpanStyle(fontSize = vfs.sp, fontWeight = FontWeight.Bold, color = if (rd.carried || rd.at == null) hb.colors.muted else hb.colors.text)) {
                append(rd.at?.let { vVal("rhr", it.value) } ?: R3Texts.DASH)
            }
            withStyle(SpanStyle(fontSize = (if (cover) 16f else 20f).sp, color = hb.colors.muted)) { append(" ${vUnit("rhr")}") }
        }, maxLines = 1, style = TextStyle(fontFeatureSettings = "tnum"), modifier = Modifier.offset((-2).dp, 0.dp).paddingFromBaseline(top = vb.dp))
        rd.at?.let { at ->
            Text(if (rd.carried) R3Texts.lastMeasured(at.day, f.today) else vDay(at.day, f.today), fontSize = (if (cover) 12f else 12.5f).sp,
                color = hb.colors.muted, maxLines = 1, modifier = Modifier.paddingFromBaseline(top = ctxB.dp))
        }
        pills.forEachIndexed { k, pl -> Box(Modifier.offset(0.dp, (pillC + 30f * k - 12f).dp).height(24.dp)) { DeltaPill(pl) } }
        RangeBlock(m, cover, Modifier.padding(top = blockTop.dp).fillMaxWidth().fillMaxHeight())
    }
}

private fun rowLabel(l: RowLabel): String = when (l) {
    RowLabel.ThisWeek -> R3Texts.THIS_WEEK
    RowLabel.LastWeek -> R3Texts.LAST_WEEK
    is RowLabel.Month -> R3Texts.monthName(l.ym)
    is RowLabel.MonYear -> R3Texts.rowMonthYear(l.d)
    is RowLabel.Week -> R3Texts.week(l.monday)
    is RowLabel.Year -> R3Texts.rowYear(l.year)
}

/** vitals_r3.range_rows: resting HR lowest–highest per span on one scale; current row coloured, the reading as a dot. */
@Composable
private fun RangeBlock(m: VitalsModel, cover: Boolean, modifier: Modifier) {
    val ink = rememberInk()
    val spans = m.spans
    if (spans.isEmpty()) return
    val c = vitalColor("rhr")
    val dot = m.reading("rhr").at
    val labels = spans.map { rowLabel(it.label) }
    val a11y = spans.zip(labels).joinToString(", ") { (s, l) -> "$l ${R3Texts.range(s.mm.lo.value, s.mm.hi.value)}" }
    Canvas(modifier.semantics { contentDescription = a11y }) {
        val avail = size.height          // block top (under the pill) .. content bottom
        val lo = spans.minOf { it.mm.lo.value } - 1.5; val hi = spans.maxOf { it.mm.hi.value } + 1.5
        val lw = labels.maxOf { textWidth(ink, it, 11.5f, FontWeight.SemiBold, density) } + u(if (cover) 30 else 34)
        val tx = lw; val tw = size.width - lw - u(22)
        fun X(v: Double) = (tx + (v - lo) / (hi - lo) * tw).toFloat()
        val pitch = min(u(if (cover) 32 else 46), avail / spans.size)
        val y0 = (avail - pitch * spans.size) / 2f + pitch / 2f
        spans.forEachIndexed { i, s ->
            val cy = y0 + i * pitch
            val col = if (s.current) ink.text else ink.muted
            textAt(ink, labels[i], 0f, cy + u(4), 11.5f, col, FontWeight.SemiBold, tnum = false)
            drawRoundRect(ink.tile, Offset(tx - u(4), cy - u(3)), Size(tw + u(8), u(6)), CornerRadius(u(3)))
            val a = X(s.mm.lo.value); val b = X(s.mm.hi.value)
            drawRoundRect(if (s.current) c else ink.outline, Offset(a - u(3), cy - u(3)), Size(b - a + u(6), u(6)), CornerRadius(u(3)))
            textAt(ink, R3Texts.int(s.mm.lo.value), a - u(10), cy + u(4), 11f, col, FontWeight.Bold, 1f)
            textAt(ink, R3Texts.int(s.mm.hi.value), b + u(10), cy + u(4), 11f, col, FontWeight.Bold, 0f)
            if (s.current && dot != null && !dot.day.isBefore(s.from) && !dot.day.isAfter(s.to)) {
                drawCircle(ink.card, u(6.5), Offset(X(dot.value), cy)); drawCircle(ink.text, u(4.5), Offset(X(dot.value), cy))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Right: four lanes, one x-axis, one cursor
// ---------------------------------------------------------------------------------------------------------------------

private val LANES_H_COVER: Dp = (60 + 3 * 44 + 3 * 8 + 18).dp

private class Lane(val id: String, val h: Float)

/** Lane heights (mock: HERO_H 424 → heart rate 118, others 70, gap 10; cover 60 / 44 / 8). */
private fun lanes(cover: Boolean): List<Lane> = if (cover) listOf(Lane("hr", 60f), Lane("hrv", 44f), Lane("breathing", 44f), Lane("spo2", 44f))
    else listOf(Lane("hr", 118f), Lane("hrv", 70f), Lane("breathing", 70f), Lane("spo2", 70f))

/** Everything the lanes draw, computed once per model. */
private class LaneData(m: VitalsModel) {
    val dom = m.laneDomain
    val month = dom.kind == DomainKind.MONTH
    val weeks: HrWeeks? = if (month) VitalsMath.hrWeeks(m.data.hrMin, m.data.hrMax, dom, m.today) else null
    /** Heart rate rows as drawn (weekly on month domains) and per domain bucket (label column). */
    val hrDrawn: List<VRow?> = weeks?.rows ?: VitalsMath.hrRows(m.data.hrMin, m.data.hrMax, dom, m.today)
    val hrBucket: List<VRow?> = VitalsMath.hrRows(m.data.hrMin, m.data.hrMax, dom, m.today)
    val rows: Map<String, List<VRow?>> = listOf("hrv", "breathing", "spo2").associateWith { VitalsMath.rows(m.data.s(it), dom, m.today) }
    val bands: Map<String, VRow?> = listOf("hrv", "breathing", "spo2").associateWith { m.laneBand(it) }
    val yr: Map<String, Pair<Double, Double>> = buildMap {
        put("hr", VitalsMath.yRange(hrDrawn.filterNotNull().flatMap { listOf(it.lo, it.hi) }, pad = 0.08))
        for (id in listOf("hrv", "breathing", "spo2")) put(id, VitalsMath.yRange(rows[id]!!.filterNotNull().flatMap { listOf(it.lo, it.hi) },
            bands[id]?.let { listOf(it.lo, it.hi) }.orEmpty(), 0.24, VitalsSpecs.of(id).laneSpan))
    }
    /** Label column values without a marked day: the period's heart-rate range and each vital's latest reading. */
    val latest: Map<String, String> = buildMap {
        val hr = hrDrawn.filterNotNull()
        put("hr", if (hr.isEmpty()) R3Texts.DASH else vRange("hr", VRow(hr.minOf { it.lo }, hr.maxOf { it.hi })))
        for (id in listOf("hrv", "breathing", "spo2")) put(id, VitalsMath.lastOnOrBefore(m.data.s(id), m.f.end)?.let { vVal(id, it.value) } ?: R3Texts.DASH)
    }
    val buckets = domainBuckets(dom, { i -> hrBucket[i] != null || rows.values.any { it[i] != null } }, m.today)

    fun valueAt(id: String, i: Int): String = (if (id == "hr") hrBucket[i] else rows[id]?.get(i))?.let { vRange(id, it) } ?: R3Texts.DASH
}

@Composable
private fun VitalsLanes(ctx: SectionContext, m: VitalsModel, ld: LaneData, scrub: ScrubState, cover: Boolean, modifier: Modifier) {
    val ink = rememberInk()
    val hb = LocalHB.current
    val dom = ld.dom
    val f = m.f
    val labW = if (cover) 96f else 128f
    val specs = lanes(cover)
    val gap = if (cover) 8f else 10f
    val totalH = specs.sumOf { it.h.toDouble() }.toFloat() + gap * (specs.size - 1)
    val si = scrubIndex(scrub, dom)
    val mark = si ?: dom.selIndex
    val keyWord = if (f.periodRanged && f.period !is SectionPeriod.Day) R3Texts.ghostWordShort(f.period) else null
    val values = specs.associate { it.id to (mark?.let { i -> ld.valueAt(it.id, i) } ?: ld.latest[it.id]!!) }
    val a11y = specs.joinToString(", ") { "${vitalTitle(it.id)} ${values[it.id]} ${vUnit(it.id)}" }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = a11y }) {
            val px = u(labW); val pw = size.width - px
            val n = dom.n
            val p0 = VPlot(px, 0f, pw, u(totalH), n, 0.0, 1.0)
            if (mark != null) vDayColumn(ink, p0, mark, -u(4), u(totalH) + u(4))
            var yy = 0f
            specs.forEachIndexed { li, lane ->
                val id = lane.id
                val lh = u(lane.h)
                val c = vitalColor(id)
                val (lo, hi) = ld.yr[id]!!
                if (li < specs.lastIndex) drawLine(ink.grid.copy(alpha = 0.6f), Offset(px, yy + lh + u(gap) / 2f), Offset(px + pw, yy + lh + u(gap) / 2f), u(0.6))
                if (id == "hr") {
                    val rows = ld.hrDrawn
                    // 1Y / All: the Monday–Sunday weeks mapped onto the month domain by date (same x-scale as the cursor)
                    val p = if (ld.weeks != null) {
                        val first = dom.keys.first()
                        val span = ChronoUnit.DAYS.between(first, VitalsMath.monthEnd(dom.keys.last()).plusDays(1)).toFloat()
                        val d0 = ChronoUnit.DAYS.between(first, SectionPeriod.mondayOf(first)).toFloat()
                        VPlot(px + pw * d0 / span, yy + u(3), pw * rows.size * 7f / span, lh - u(6), rows.size, lo, hi)
                    } else VPlot(px, yy + u(3), pw, lh - u(6), rows.size, lo, hi)
                    clipRect(px, yy, px + pw, yy + lh) {
                        if (rows.count { it != null } < 6) vCapsules(p, rows, c, 8f, 0.85f) else vBandFill(p, rows, c, 0.30f)
                    }
                } else {
                    val rows = ld.rows[id]!!
                    val band = ld.bands[id]
                    val p = VPlot(px, yy + u(3), pw, lh - u(6), rows.size, lo, hi)
                    if (band != null) vGhostBand(ink, p, band)
                    if (ld.month) vCapsules(p, rows, c, if (n > 20) 6f else 8f)
                    else vPoints(ink, p, rows.map { it?.lo }, c, dom.futureFrom, mark, dim = dom.window || si != null, band = band, sw = 1.6f,
                        lastRing = mark == null, rmul = 0.9f)
                }
                // label column: name (metric colour) · value 15/700 + unit · previous range key / heart-rate caption
                val nameFs = if (cover) 10.5f else 11.5f
                textAt(ink, vitalTitle(id), 0f, yy + u(12), nameFs, hb.legible(c), FontWeight.SemiBold, tnum = false)
                val vfs = if (cover) 13f else 15f
                val vbl = yy + u(12 + vfs + 6)
                val ufs = if (cover) 10f else 11f
                var fit = vfs
                if (cover) {
                    val uw = textWidth(ink, vUnit(id), ufs, FontWeight.Normal, density)
                    while (fit > 11f && textWidth(ink, values[id]!!, fit, FontWeight.Bold, density) + u(3) + uw > u(labW - 4)) fit -= 0.5f
                }
                val vw = textAt(ink, values[id]!!, 0f, vbl, fit, ink.text, FontWeight.Bold)
                textAt(ink, vUnit(id), vw + u(3), vbl, ufs, ink.muted)
                if (!cover) {
                    val band = ld.bands[id]
                    if (id == "hr") textAt(ink, if (ld.month) R3Texts.WEEKLY_RANGE else R3Texts.DAILY_RANGE, 0f, yy + u(51), 10.5f, ink.muted, tnum = false)
                    else if (band != null && keyWord != null && lane.h >= 56f) {
                        drawRoundRect(ink.muted.copy(alpha = GHOST_KEY_A), Offset(0f, yy + lh - u(13)), Size(u(8), u(8)), CornerRadius(u(2)))
                        textAt(ink, R3Texts.keyRange(keyWord, band.lo, band.hi, vDec(id)), u(13), yy + lh - u(5.5), 10.5f, ink.muted)
                    }
                }
                yy += lh + u(gap)
            }
            yy -= u(gap)
            if (mark != null) { val cx = p0.X(mark); drawLine(ink.text.copy(alpha = 0.35f), Offset(cx, -u(2)), Offset(cx, yy + u(2)), u(1)) }
            val ap = VPlot(px, 0f, pw, yy, n, 0.0, 1.0)
            val labels = dom.labels.toMutableList()
            var ti = dom.todayIndex
            if (mark != null && ti != null && abs(ti - mark) <= 1 && ti != mark && ap.slot < u(20)) { labels[ti] = null; ti = null }
            // narrow slots (1Y on the cover): the today circle would touch its neighbouring month letters
            ti?.let { t -> if (ap.slot < u(20)) { if (t > 0) labels[t - 1] = null; if (t < labels.lastIndex) labels[t + 1] = null } }
            vAxisLabels(ink, ap, labels, ti, if (mark != null && mark != ti) mark else null, dom.futureFrom)
        }
        // one gesture layer over the plot area: tap = select the day (1Y → month, All → year), hold + slide = shared cursor
        Box(Modifier.padding(start = labW.dp).fillMaxWidth().height(totalH.dp).scrubAndTap(ld.buckets, scrub, { b -> ctx.drill(b.day) }))
    }
}

/** Lane key swatch alpha (r3ui ghost_key: GHOST_FILL_A × 1.6). */
private const val GHOST_KEY_A = 0.32f
