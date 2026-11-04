package com.boudhealth.ui.health

import androidx.compose.ui.graphics.Color
import com.boudhealth.data.SectionPeriod
import com.boudhealth.data.TrendDir
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.section.MicroVisual
import com.boudhealth.ui.section.R3Texts
import com.boudhealth.ui.section.SectionContext
import com.boudhealth.ui.section.TileIcon
import com.boudhealth.ui.section.Tone
import com.boudhealth.ui.section.TrendTileModel
import com.boudhealth.ui.section.TrendsModel
import com.boudhealth.ui.theme.Cat

// =====================================================================================================================
// Body Trends strip (IMPL_PLAN §3B "Trends", mock body_data.trends + body_r3.r3_tiles): 6 fixed slots from the pure
// [BodyFact]s, texts from R3Texts only. Basis header "vs {d Mon}" / "vs {d Mon yyyy}" = the last weigh-in before the
// period (none on day mode and All, as drawn). Icons ▲ ● ▼ ✓ with good / bad tone (lean up = good); plain values use
// the metric glyph. Tile tap → the metric's detail page at the period.
// =====================================================================================================================

private fun sparkColor(id: String): Color = when (id) {
    BodyIds.FAT_PCT -> Cat.fat
    BodyIds.LEAN -> Cat.lean
    BodyIds.FAT_MASS -> FAT_MASS_COLOR
    BodyIds.WAIST -> Cat.waist
    BodyIds.BMI -> Cat.bmi
    else -> Color.Unspecified          // weight = the theme's text colour (resolved by TrendsStrip)
}

/** Tile name of a metric ("Weight", "Fat mass", "Lean mass", "Waist", "Body fat percentage", "BMI"). */
private fun tileName(id: String): String = when (id) {
    BodyIds.WEIGHT -> R3Texts.WEIGHT
    BodyIds.FAT_MASS -> R3Texts.FAT_MASS
    BodyIds.LEAN -> R3Texts.LEAN_MASS
    BodyIds.WAIST -> R3Texts.WAIST_SHORT
    BodyIds.FAT_PCT -> R3Texts.BODY_FAT
    else -> R3Texts.BMI
}

/** Detail page a fact opens (fat mass has none: its weigh-ins live on Body fat percentage). */
private fun detailId(id: String): String = if (id == BodyIds.FAT_MASS) BodyIds.FAT_PCT else id

internal fun bodyTrends(ctx: SectionContext): TrendsModel? {
    val m = bodyModel(ctx) ?: return null
    if (m.series.weight.isEmpty()) return null
    val s = m.scope
    val basis = m.basisDay?.let { R3Texts.vs(R3Texts.dMonRel(it, s.end)) }
    return TrendsModel(basis, m.trends.map { tile(ctx, it, s) })
}

private fun spark(f: BodyFact): MicroVisual? = f.spark.takeIf { it.isNotEmpty() }?.let { MicroVisual.Spark(it, sparkColor(f.metricId)) }

private fun tile(ctx: SectionContext, f: BodyFact, s: BodyScope): TrendTileModel {
    val open: () -> Unit = { ctx.openMetric(detailId(f.metricId)) }
    val kg = hm(BodyIds.WEIGHT).unit
    fun make(icon: TileIcon, tone: Tone, label: String, value: String, suffix: String? = null, visual: MicroVisual? = spark(f), glyph: Glyph? = null) =
        TrendTileModel(icon, tone, label, value, suffix, visual, glyph, R3Texts.a11yTile(icon, label, value, suffix), open)
    return when (f) {
        is BodyFact.Change -> {
            val c = f.change
            val name = tileName(f.metricId)
            val label = when (f.label) {
                BodyLabel.VS_LAST_WEEKDAY -> R3Texts.weightVsLast(f.labelDay)
                BodyLabel.VS_DAY -> R3Texts.vsDayLabel(name, f.labelDay, s.end)
                BodyLabel.THIS_WEEK -> R3Texts.thisWeek(name)
                BodyLabel.WEEK_OF -> R3Texts.weekOfLabel(name, f.labelDay, s.end)
                BodyLabel.THIS_MONTH -> R3Texts.thisMonth(name)
                BodyLabel.IN_MONTH -> R3Texts.inMonth(name, f.labelDay)
                BodyLabel.YEAR -> R3Texts.inYear(name, f.labelDay)
                BodyLabel.SINCE -> R3Texts.sinceLabel(name, f.labelDay)
            }
            make(R3MathTone.icon(c.dir), R3MathTone.of(c), label, bodySigned(c.diff ?: 0.0, f.metricId))
        }
        is BodyFact.ToTarget ->
            if (f.gap > 0) make(TileIcon.GLYPH, Tone.NEUTRAL, R3Texts.WEIGHT, R3Texts.withUnit(f.gap, kg, 1), R3Texts.TO_TARGET,
                MicroVisual.Caption(R3Texts.target(f.target, kg)), Glyph.Scale)
            else make(TileIcon.DONE, Tone.GOOD, R3Texts.WEIGHT, R3Texts.TARGET_REACHED)
        is BodyFact.Plain -> make(TileIcon.GLYPH, Tone.NEUTRAL, R3Texts.waistOn(f.at.day, s.end), R3Texts.withUnit(f.at.value, hm(f.metricId).unit, 1),
            glyph = Glyph.forMetric(f.metricId))
        is BodyFact.LowestWeek -> {
            val label = if (s.period is SectionPeriod.Week && !s.current) R3Texts.LOWEST_THAT_WEEK else R3Texts.LOWEST_THIS_WEEK
            val tone = when (f.dir) { TrendDir.DOWN -> Tone.GOOD; TrendDir.UP -> Tone.BAD; else -> Tone.NEUTRAL }
            if (f.diff == null) make(TileIcon.GLYPH, Tone.NEUTRAL, label, R3Texts.withUnit(f.low.value, kg, 1), glyph = Glyph.Scale)
            else make(R3MathTone.icon(f.dir), tone, label, R3Texts.withUnit(f.low.value, kg, 1), bodySigned(f.diff, BodyIds.WEIGHT))
        }
        is BodyFact.Record -> make(TileIcon.DOWN, Tone.GOOD, R3Texts.lowestSince(f.sinceMonth), R3Texts.withUnit(f.at.value, kg, 1), R3Texts.dMonRel(f.at.day, s.end))
        is BodyFact.WeeksDown -> {
            val good = f.n * 2 >= f.m
            make(if (good) TileIcon.DOWN else TileIcon.UP, if (good) Tone.GOOD else Tone.BAD, R3Texts.WEEKS_DOWN, R3Texts.nOfM(f.n, f.m),
                visual = MicroVisual.Ticks(f.flags, false, Color.Unspecified))
        }
        is BodyFact.Extremum ->
            if (f.low) make(TileIcon.DOWN, Tone.GOOD, R3Texts.LOWEST, R3Texts.withUnit(f.at.value, kg, 1), R3Texts.extremeDate(f.at))
            else make(TileIcon.UP, Tone.NEUTRAL, R3Texts.HIGHEST, R3Texts.withUnit(f.at.value, kg, 1), R3Texts.extremeDate(f.at))
        is BodyFact.Missing -> make(TileIcon.GLYPH, Tone.NEUTRAL, tileName(f.metricId), R3Texts.DASH, visual = null, glyph = Glyph.forMetric(f.metricId))
    }
}
