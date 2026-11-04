package com.boudhealth.ui.health

import androidx.compose.runtime.Composable
import com.boudhealth.data.MetricEnv
import com.boudhealth.data.MetricSections
import com.boudhealth.data.SectionBundle
import com.boudhealth.data.SectionId
import com.boudhealth.data.SectionScope
import com.boudhealth.data.V2Reads
import com.boudhealth.ui.nav.Nav
import com.boudhealth.ui.section.SectionRegistry
import com.boudhealth.ui.section.SectionScreen
import com.boudhealth.ui.section.SectionSpec
import com.boudhealth.data.R3 as R3Data

// =====================================================================================================================
// Body tab, R3 (IMPL_PLAN §3B, approved review/body_*.png, D2-A): NO rings. Hero = weight + composition + 2×2
// ([BodyHero]) · Trends 6 facts ([bodyTrends]) · cards Body fat percentage · Lean mass · Waist circumference ·
// Measurements (M) · BMI ([bodyCards]). Every number comes from [BodyR3Math] on the R3 histories, loaded in ONE IO hop.
// Impossible readings are hidden by the data layer (Plausible, G0).
// =====================================================================================================================

object BodySection {
    val spec: SectionSpec by lazy {
        SectionSpec(
            section = SectionId.BODY,
            title = HealthTexts.BODY,
            cardMetricIds = MetricSections.BODY,
            cards = bodyCards(),
            loadExtras = { env, scope, bundle -> loadBodyExtras(env, scope, bundle) },
            hero = { ctx -> BodyHero(ctx) },
            trends = { ctx -> bodyTrends(ctx) },
        )
    }

    fun register() { if (SectionRegistry.get(SectionId.BODY) == null) SectionRegistry.register(spec) }
}

/** Body tab root (IC4). T5: `Route.Body -> BodySectionScreen(nav)`. */
@Composable
fun BodySectionScreen(nav: Nav) {
    BodySection.register()
    SectionScreen(SectionId.BODY, nav)
}

/** IO: the five Body histories (memoised per sync version), the height, the goals' targets → [BodyModel]. */
private fun loadBodyExtras(env: MetricEnv, scope: SectionScope, @Suppress("UNUSED_PARAMETER") b: SectionBundle): Map<String, Any?> {
    val series = BodySeries(
        weight = R3Data.history(env, BodyIds.WEIGHT),
        fatPct = R3Data.history(env, BodyIds.FAT_PCT),
        lean = R3Data.history(env, BodyIds.LEAN),
        waist = R3Data.history(env, BodyIds.WAIST),
        bmi = R3Data.history(env, BodyIds.BMI),
    )
    val g = env.goals
    val sc = BodyScope(scope.period, scope.selectedDay, env.today)
    val model = BodyR3Math.build(series, sc, BodyTargets(g.targetWeightKg, g.targetFatPct, g.targetWaistCm), V2Reads.heightM(env.cache))
    return mapOf(EXTRA_BODY to model)
}
