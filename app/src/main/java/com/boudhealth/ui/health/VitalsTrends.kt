package com.boudhealth.ui.health

import com.boudhealth.data.R3Math
import com.boudhealth.data.TrendDir
import com.boudhealth.ui.section.MicroVisual
import com.boudhealth.ui.section.R3Texts
import com.boudhealth.ui.section.SectionContext
import com.boudhealth.ui.section.TileIcon
import com.boudhealth.ui.section.Tone
import com.boudhealth.ui.section.TrendTileModel
import com.boudhealth.ui.section.TrendsModel

// =====================================================================================================================
// Vitals R3 Trends strip (IMPL_PLAN §3V "Trends", vitals_r3.trend_tiles / _tile_viz): six fact tiles, fixed slots
// T1 Resting heart rate · T2 HRV · T3 Breathing rate · T4 Blood oxygen · T5 Heart rate · T6 record / days outside.
// Icons are always NEUTRAL (decision 9: no good / bad colour on vitals); a plain value shows the metric glyph instead of
// ● (● would say "no change"). Comparison captions ("vs 49–55", "vs Tue") sit in the micro band. Basis: "vs last week" /
// "vs October" / "vs 2025"; none on a day and on All.
// =====================================================================================================================

/** [accent] = the theme accent for the outside-day dots (mock: accent); `SectionSpec.trends` is composable and passes it. */
fun vitalsTrends(ctx: SectionContext, accent: androidx.compose.ui.graphics.Color): TrendsModel? {
    val m = VitalsModels.of(ctx) ?: return null
    val f = m.f
    val basis = if (f.ranged) R3Texts.vsPrev(f.period, f.today) else null
    val tiles = m.tiles.map { tile(ctx, m, it, accent) }
    return TrendsModel(basis, tiles)
}

private fun icon(dir: TrendDir?): TileIcon = R3Math.iconOf(dir)

private fun spark(id: String, values: List<Double>): MicroVisual? = values.takeIf { it.isNotEmpty() }?.let { MicroVisual.Spark(it, vitalColor(id)) }

/** Plain value: the metric glyph on a neutral circle, never ▲ ● ▼. */
private fun plain(ctx: SectionContext, id: String, label: String, value: String, suffix: String?, visual: MicroVisual?): TrendTileModel =
    TrendTileModel(TileIcon.GLYPH, Tone.NEUTRAL, label, value, suffix, visual, vitalGlyph(id), R3Texts.a11yTile(TileIcon.GLYPH, label, value, suffix),
        onClick = { ctx.openMetric(id) })

private fun tile(ctx: SectionContext, m: VitalsModel, t: VTile, accent: androidx.compose.ui.graphics.Color): TrendTileModel {
    val f = m.f
    val today = f.today
    return when (t) {
        is VTile.Change -> {
            val id = t.id
            val diff = t.r.diff
            val ref = t.r.reference
            if (diff == null || ref == null) {
                val v = t.r.current?.let { R3Texts.withUnit(it.value, vUnit(id), vDec(id)) } ?: R3Texts.DASH
                return plain(ctx, id, vitalTitle(id), v, null, spark(id, t.spark))
            }
            val value = R3Texts.signedUnit(diff, vUnit(id), vDec(id))
            val ic = icon(t.r.dir)
            if (f.isAll) {
                // All T1: "−3 bpm" since the first reading
                val suf = R3Texts.since(ref.day)
                TrendTileModel(ic, Tone.NEUTRAL, vitalTitle(id), value, suf, spark(id, t.spark), a11y = R3Texts.a11yTile(ic, vitalTitle(id), value, suf),
                    onClick = { ctx.openMetric(id) })
            } else {
                val cap = R3Texts.vsDdd(ref.day)
                TrendTileModel(ic, Tone.NEUTRAL, vitalTitle(id), value, null, MicroVisual.Caption(cap, spark(id, t.spark)),
                    a11y = R3Texts.a11yTile(ic, vitalTitle(id), value, cap), onClick = { ctx.openMetric(id) })
            }
        }
        is VTile.Carried -> {
            // a day without a reading (O4): the carried value as a plain fact with "Last measured {d Mon}", never "No reading"
            val at = t.at
            if (at == null) plain(ctx, t.id, vitalTitle(t.id), R3Texts.DASH, null, null)
            else plain(ctx, t.id, vitalTitle(t.id), R3Texts.withUnit(at.value, vUnit(t.id), vDec(t.id)), null,
                MicroVisual.Caption(R3Texts.lastMeasured(at.day, today)))
        }
        is VTile.Range -> {
            val id = t.id
            val cur = t.cur
            when {
                cur == null -> plain(ctx, id, vitalTitle(id), R3Texts.DASH, null, null)
                t.prev == null -> {
                    // plain range (day heart rate, All): value + unit; "%" reads "94–99 %"
                    val suf = vUnit(id).ifEmpty { null }
                    plain(ctx, id, vitalTitle(id), vRange(id, cur), suf, if (id == "hr" && f.isAll) null else spark(id, t.spark))
                }
                else -> {
                    val ic = icon(t.dir)
                    val value = vRange(id, cur)
                    val cap = R3Texts.vsRange(t.prev.lo, t.prev.hi, vDec(id))
                    TrendTileModel(ic, Tone.NEUTRAL, vitalTitle(id), value, null,
                        MicroVisual.Caption(cap, MicroVisual.RangePair(cur.lo..cur.hi, t.prev.lo..t.prev.hi, vitalColor(id))),
                        a11y = R3Texts.a11yTile(ic, vitalTitle(id), value, cap), onClick = { ctx.openMetric(id) })
                }
            }
        }
        is VTile.Record -> {
            val ic = if (t.low) TileIcon.DOWN else TileIcon.UP
            val withUnit = R3Texts.withUnit(t.at.value, vUnit(t.id), vDec(t.id))
            val (label, value, suffix) = if (t.since == null) {
                // all-time: "Lowest RHR" · "48 bpm" · "15 Aug 2026" (All) / "15 Aug" (within the year)
                val word = when { t.id == "rhr" && t.low -> R3Texts.LOWEST_RHR; t.id == "rhr" -> R3Texts.HIGHEST_RHR; t.low -> R3Texts.LOWEST_HRV; else -> R3Texts.HIGHEST_HRV }
                Triple(word, withUnit, if (f.isAll) R3Texts.extremeDate(t.at) else R3Texts.dMonRel(t.at.day, today))
            } else {
                val word = when { t.id == "rhr" && t.low -> R3Texts.LOWEST_RHR_SINCE; t.id == "rhr" -> R3Texts.HIGHEST_RHR_SINCE; t.low -> R3Texts.LOWEST_HRV_SINCE; else -> R3Texts.HIGHEST_HRV_SINCE }
                Triple(word, R3Texts.dMonRel(t.since, today), withUnit)
            }
            TrendTileModel(ic, Tone.NEUTRAL, label, value, suffix, spark(t.id, t.spark), a11y = R3Texts.a11yTile(ic, label, value, suffix),
                onClick = { ctx.openMetric(t.id) })
        }
        is VTile.Out -> {
            val label = R3Texts.outsideLabel(f.period) ?: R3Texts.DASH
            val value = R3Texts.days(t.o.days)
            val ic = icon(t.o.dir)
            TrendTileModel(ic, Tone.NEUTRAL, label, value, null, t.flags?.let { MicroVisual.Dots(it, 0, accent) },
                a11y = R3Texts.a11yTile(ic, label, value))
        }
    }
}
