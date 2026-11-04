@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.boudhealth.ui.health

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.boudhealth.data.MetricEnv
import com.boudhealth.data.Metrics
import com.boudhealth.data.V2
import com.boudhealth.ui.components.BigStepper
import com.boudhealth.ui.components.ButtonKind
import com.boudhealth.ui.components.Fmt
import com.boudhealth.ui.components.HBBottomSheet
import com.boudhealth.ui.components.HBButton
import com.boudhealth.ui.components.SheetActions
import com.boudhealth.ui.nav.rememberPrefsEditor
import com.boudhealth.ui.nav.LocalPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

private fun roundStep(v: Double, step: Double): Double = Math.round(v / step) * step

/** Body targets (D9): saved with their start value via V2.withBodyTarget, so the Body rings can show progress. */
private val BODY_TARGETS = setOf("weight", "body_fat", "waist")

/** Sheet title: the body targets use the Settings wording (V2_TEXTS §6 row_targets). */
private fun goalTitle(id: String, label: String): String = when (id) {
    "weight" -> "Target weight"
    "body_fat" -> "Target body fat percentage"
    "waist" -> "Target waist circumference"
    else -> "$label goal"
}

/**
 * Goal / target sheet (V2-083, V7-R9/R10/R12): the stepper only. No preview chart, no table, no hints, no quick chips,
 * no "lower is better", no WHtR line (the WHtR (i) lives next to the WHtR value on the Waist card and page).
 * Works for weight, body fat, waist, calories, heart points and steps.
 */
@Composable
fun GoalSheet(metricId: String, onDismiss: () -> Unit) {
    val m = Metrics.find(metricId) ?: return
    val spec = goalSpec(metricId) ?: return
    val ctx = LocalContext.current
    val prefs = LocalPrefs.current
    val edit = rememberPrefsEditor()
    val current = spec.get(prefs.goals)
    val nullableGoalSet = spec.removable && current != null
    val body = metricId in BODY_TARGETS

    // latest plausible value: the start of a new target (D9) and the stepper's first value when nothing is set
    val latest by produceState<Double?>(null, metricId) {
        val env = MetricEnv.load(ctx)
        value = withContext(Dispatchers.IO) { runCatching { V2.latestValue(env, metricId) }.getOrNull() ?: Metrics.latest(env, metricId)?.value }
    }
    var v by rememberSaveable(metricId) { mutableDoubleStateOf(current ?: spec.default) }
    var touched by rememberSaveable(metricId) { mutableStateOf(current != null) }
    LaunchedEffect(latest) {
        val l = latest ?: return@LaunchedEffect
        if (!touched) v = roundStep(l, spec.step).coerceIn(spec.range)
    }

    fun save(target: Double?) {
        if (body) edit.updateGoals { V2.withBodyTarget(it, metricId, target, latest, LocalDate.now()) }
        else edit.updateGoals { spec.set(it, target) }
        onDismiss()
    }

    HBBottomSheet(onDismiss = onDismiss, title = goalTitle(metricId, metricLabel(m))) {
        BigStepper(v, { v = it; touched = true }, step = spec.step, range = spec.range, decimals = spec.decimals, unit = metricUnit(m))
        Spacer(Modifier.height(20.dp))
        SheetActions {
            HBButton("Save goal", { save(v) }, fullWidth = true)
            if (nullableGoalSet) HBButton(if (body) "Remove target" else "Remove goal", { save(null) }, kind = ButtonKind.Text, fullWidth = true)
            else if (!spec.removable && current != spec.default) HBButton("Reset to ${Fmt.num(spec.default)}", { save(spec.default) }, kind = ButtonKind.Text, fullWidth = true)
        }
    }
}
