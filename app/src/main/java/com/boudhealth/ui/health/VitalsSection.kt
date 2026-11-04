package com.boudhealth.ui.health

import androidx.compose.runtime.Composable
import com.boudhealth.data.MetricEnv
import com.boudhealth.data.MetricSections
import com.boudhealth.data.SectionBundle
import com.boudhealth.data.SectionId
import com.boudhealth.data.SectionScope
import com.boudhealth.ui.nav.Nav
import com.boudhealth.ui.section.CardSize
import com.boudhealth.ui.section.CardSpec
import com.boudhealth.ui.section.SectionRegistry
import com.boudhealth.ui.section.SectionScreen
import com.boudhealth.ui.section.SectionSpec
import com.boudhealth.data.R3 as R3Data

// =====================================================================================================================
// Vitals tab, R3 (IMPL_PLAN §3V, approved D3-A pages review/vitals_*.png):
//   hero    featured resting heart rate + range block | 4 lanes on one x-axis with one cursor   (VitalsHero.kt)
//   Trends  6 fact tiles, neutral icons                                                         (VitalsTrends.kt)
//   cards   [Heart rate M][Resting heart rate S] · [HRV S][Breathing rate S][Blood oxygen S]    (VitalsCards.kt)
// All numbers come from VitalsR3Data.kt (pure, JVM-tested): readings, lowest–highest, counts and differences of two
// readings only. Heart rate is a min–max or a minute sample, never the daily average. Texts come from R3Texts.
// =====================================================================================================================

object VitalsSection {
    val spec: SectionSpec by lazy {
        SectionSpec(
            section = SectionId.VITALS,
            title = HealthTexts.VITALS,
            cardMetricIds = MetricSections.VITALS,
            cards = listOf(
                CardSpec.Custom("hr", size = CardSize.M) { ctx -> HeartRateCardR3(ctx) },
                CardSpec.Custom("rhr") { ctx -> VitalCardR3(ctx, "rhr") },
                CardSpec.Custom("hrv") { ctx -> VitalCardR3(ctx, "hrv") },
                CardSpec.Custom("breathing") { ctx -> VitalCardR3(ctx, "breathing") },
                CardSpec.Custom("spo2") { ctx -> VitalCardR3(ctx, "spo2") },
            ),
            loadExtras = { env, scope, bundle -> loadVitalsExtras(env, scope, bundle) },
            hero = { ctx -> VitalsHero(ctx) },
            trends = { ctx -> vitalsTrends(ctx, com.boudhealth.ui.theme.LocalHB.current.colors.accent) },
        )
    }

    fun register() { if (SectionRegistry.get(SectionId.VITALS) == null) SectionRegistry.register(spec) }
}

/** Vitals tab root (IC4). T5: `Route.Vitals -> VitalsSectionScreen(nav)`. */
@Composable
fun VitalsSectionScreen(nav: Nav) {
    VitalsSection.register()
    SectionScreen(SectionId.VITALS, nav)
}

/** One IO hop: the four vitals' history, heart-rate lowest / highest per day, the day's minute samples (1D / selected day). */
private fun loadVitalsExtras(env: MetricEnv, scope: SectionScope, b: SectionBundle): Map<String, Any?> {
    val data = loadVitalsData(R3Data.engine(env), env.cache, scope, b.dataStart, R3Data.now(env))
    // QA R3: build the model and its facts here (IO), not lazily on the main thread during the first composition
    val model = VitalsModel(data, VFrame.of(scope, data.today, data.dataStart)).also { it.warm() }
    return mapOf(EXTRA_VITALS to data, EXTRA_VITALS_MODEL to model)
}
