package com.boudhealth.ui.nav

import androidx.compose.runtime.Composable
import com.boudhealth.ui.health.BodySectionScreen
import com.boudhealth.ui.health.VitalsSectionScreen

/** Tab roots 3 and 4 (V2-025): the Body and Vitals section dashboards (T3, ui/health). */
@Composable
internal fun BodyTabRoot(nav: Nav) = BodySectionScreen(nav)

@Composable
internal fun VitalsTabRoot(nav: Nav) = VitalsSectionScreen(nav)
