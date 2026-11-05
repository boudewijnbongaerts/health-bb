package com.boudhealth.ui.health

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.data.CardData
import com.boudhealth.data.Grain
import com.boudhealth.data.SectionPeriod
import com.boudhealth.data.SeriesBucket
import com.boudhealth.data.V2Math
import com.boudhealth.ui.section.Headline
import com.boudhealth.ui.section.SFmt
import com.boudhealth.ui.section.SectionContext
import com.boudhealth.ui.theme.LocalHB
import java.time.LocalDate

// =====================================================================================================================
// Shared by the Body and Vitals sections (T3): approved texts, headline row, graph data per period, column span.
// =====================================================================================================================

/** Approved user-facing texts (V2_TEXTS.md §2–§5), verbatim. Chart legend words come from the approved design6 sheets. */
object HealthTexts {
    const val BODY = "Body"
    const val VITALS = "Vitals"
    const val WEIGHT = "Weight"
    const val BODY_FAT = "Body fat percentage"
    const val LEAN = "Lean mass"
    const val WAIST = "Waist circumference"
    const val BMI = "BMI"
    const val RHR = "Resting heart rate"
    const val HR = "Heart rate"
    const val HRV = "HRV"
    const val BREATHING = "Breathing rate"
    const val SPO2 = "Blood oxygen"
    const val MEASURE = "Measure"
    const val EMPTY_PERIOD = "No data for this period"
    const val WHTR = "WHtR"
    const val WHTR_INFO = "WHtR = waist ÷ height"   // R5 #47: no health judgement ("healthy"); the Waist chart keeps its bands
    fun all(n: Int) = "All $n"
    fun lastMeasured(d: LocalDate, today: LocalDate) = "Last measured ${dateText(d, today)}"
    fun dateText(d: LocalDate, today: LocalDate) = SFmt.shortDate(d) + if (d.year != today.year) " ${d.year}" else ""
}

/** Card / unit / decimals per Body and Vitals metric id. */
data class HMetric(val id: String, val title: String, val unit: String, val decimals: Int)

val HMETRICS: Map<String, HMetric> = listOf(
    HMetric("weight", HealthTexts.WEIGHT, "kg", 1),
    HMetric("body_fat", HealthTexts.BODY_FAT, "%", 1),
    HMetric("lean_mass", HealthTexts.LEAN, "kg", 1),
    HMetric("waist", HealthTexts.WAIST, "cm", 1),
    HMetric("bmi", HealthTexts.BMI, "", 1),
    HMetric("rhr", HealthTexts.RHR, "bpm", 0),
    HMetric("hr", HealthTexts.HR, "bpm", 0),
    HMetric("hrv", HealthTexts.HRV, "ms", 0),
    HMetric("breathing", HealthTexts.BREATHING, "/min", 1),
    HMetric("spo2", HealthTexts.SPO2, "%", 0),
).associateBy { it.id }

fun hm(id: String): HMetric = HMETRICS[id] ?: HMetric(id, id, "", 0)

/** Extras key: 1D context buckets per metric (the days before the shown day; D14 keeps 1D, a lone dot says little). */
const val EXTRA_CONTEXT = "context"

/** Daily buckets for [from]..[to] from per-day values (1D context graphs). */
fun dayBuckets(values: Map<LocalDate, Double>, from: LocalDate, to: LocalDate): List<SeriesBucket> =
    generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }
        .map { d -> SeriesBucket(d.atStartOfDay(), d.plusDays(1).atStartOfDay(), values[d]) }.toList()

/** The graph of a card for the section's period: the period series, or on 1D the context days ending on that day. */
class CardGraph(val buckets: List<SeriesBucket>, val grain: Grain, val period: SectionPeriod, val context: Boolean)

@Suppress("UNCHECKED_CAST")
fun cardGraph(ctx: SectionContext, d: CardData): CardGraph {
    val p = ctx.period
    if (p is SectionPeriod.Day) {
        val c = (ctx.extras[EXTRA_CONTEXT] as? Map<String, List<SeriesBucket>>)?.get(d.metricId)
        if (c != null) return CardGraph(c, Grain.DAY, p, true)
    }
    val s = d.series
    return CardGraph(s?.buckets.orEmpty(), s?.grain ?: V2Math.grainFor(p), p, false)
}

/** Tap on a graph bucket → re-scope the section (1W/1M select the day, 1Y → month, All → year). */
fun graphTap(ctx: SectionContext, g: CardGraph): ((SeriesBucket) -> Unit)? =
    if (g.context || g.grain == Grain.HOUR) null else ({ b -> ctx.drill(b.day) })

/**
 * Headline row of a Body / Vitals card: big latest value + unit (muted when carried from before the period, D14),
 * the period's lowest–highest on the right (D6, only with ≥ 2 readings), "Last measured {date}" under a carried value.
 */
@Composable
fun ValueHeadline(ctx: SectionContext, d: CardData, m: HMetric = hm(d.metricId)) {
    val hb = LocalHB.current
    val v = d.value
    val carried = v?.carried == true
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Headline(v?.value?.let { SFmt.num(it, m.decimals) } ?: "—", unit = m.unit.ifEmpty { null }, muted = carried || v?.value == null)
        Spacer(Modifier.weight(1f))
        val mn = v?.min; val mx = v?.max
        if (!carried && mn != null && mx != null && (v.daysWithData >= 2) && mx > mn) {
            Text("${SFmt.num(mn, m.decimals)} – ${SFmt.num(mx, m.decimals)}", style = hb.type.bodyS.copy(fontFeatureSettings = "tnum"),
                color = hb.colors.muted, maxLines = 1, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
    if (carried) v.at?.let { at ->
        Text(HealthTexts.lastMeasured(at, ctx.today), style = hb.type.bodyXS, color = hb.colors.muted, maxLines = 1)
    }
}

/** Extras key of a metric's change vs its previous reading ([com.boudhealth.data.LatestChange]). */
fun extraChange(id: String) = "change_$id"

/**
 * "▲ 3.1 kg vs 27 Oct" / "Same as 27 Oct": the latest reading vs the one before (Lean mass, HRV; V2_REMOVALS_AUDIT 1.4).
 * A fact, neutral colour (direction only, no judgement).
 */
@Composable
fun ChangeLine(ctx: SectionContext, id: String) {
    val hb = LocalHB.current
    val c = ctx.extras[extraChange(id)] as? com.boudhealth.data.LatestChange ?: return
    val prevDay = c.previousDay ?: return
    val ch = c.change ?: return
    val m = hm(id)
    val same = Math.round(kotlin.math.abs(ch) * Math.pow(10.0, m.decimals.toDouble())) == 0L
    val day = HealthTexts.dateText(prevDay, ctx.today)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        if (!same) {
            Text(if (ch > 0) "▲" else "▼", fontSize = 9.sp, color = hb.colors.muted)
            Spacer(Modifier.width(5.dp))
        }
        Text(if (same) "Same as $day" else "${SFmt.num(kotlin.math.abs(ch), m.decimals)} ${m.unit} vs $day".replace("  ", " "),
            style = hb.type.bodyXS.copy(fontFeatureSettings = "tnum"), color = hb.colors.muted, maxLines = 1)
    }
}

/** A number for strip cells (Vitals: resting heart rate per day), 11.5 sp bold in the metric colour (design6). */
@Composable
fun StripNumber(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, style = TextStyle(fontSize = 11.5.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontFeatureSettings = "tnum"),
        color = color, maxLines = 1, softWrap = false)
}

@Composable
fun VGap(h: Int) = Spacer(Modifier.height(h.dp))

@Composable
fun CardColumn(content: @Composable () -> Unit) = Column(Modifier.fillMaxWidth()) { content() }
