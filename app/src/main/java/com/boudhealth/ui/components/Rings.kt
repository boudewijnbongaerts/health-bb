package com.boudhealth.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.boudhealth.render.ring.RingColors
import com.boudhealth.render.ring.RingData
import com.boudhealth.render.ring.RingLabel
import com.boudhealth.render.ring.RingLabelLayout
import com.boudhealth.render.ring.RingLabelMode
import com.boudhealth.render.ring.RingPaint
import com.boudhealth.ui.theme.HB
import com.boudhealth.ui.theme.LocalHB
import com.boudhealth.ui.theme.RingPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Emphasized-decelerate (DESIGN §6). */
val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
/** Standard decelerate. */
val StandardDecelerate = CubicBezierEasing(0f, 0f, 0f, 1f)

/**
 * v6 entry point, kept only so old call sites compile. V2 draws every ring with render/ring/RingPaint (fill fix,
 * integrated numbers, thread-safe); this object delegates there. New code: RingPaint / the Composables below.
 */
@Deprecated("V2: use com.boudhealth.render.ring.RingPaint (IC6)")
object RingPainter {
    /** One ring; [c0]/[c1] = palette pair: dark track alpha (≤ 0.25) means a dark theme → bright end colour as the fill. */
    fun ring(
        c: android.graphics.Canvas, cx: Float, cy: Float, r: Float, s: Float, p: Float, c0: Int, c1: Int,
        shadow: Boolean = true, trackAlpha: Float = 0.20f, shadowAlpha: Float = 1f, shadowScale: Float = 1f,
    ) {
        val dark = trackAlpha <= 0.25f
        val fill = if (dark) c1 else c0
        val track = RingColors.withAlpha(if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(), if (dark) 0.11f else 0.09f)
        RingPaint.ring(c, cx, cy, r, s, p, fill, RingColors.lapOf(fill), track, RingColors.withAlpha(0xFF000000.toInt(), 0.3f), shadowScale)
    }

    fun strokeFor(d: Float): Float = RingPaint.strokeFor(d, 0.12f)

    fun weekBar(c: android.graphics.Canvas, left: Float, top: Float, w: Float, h: Float, p: Float, c0: Int, c1: Int, track: Int) =
        RingPaint.bar(c, left, top, w, h, p, c1, track)
}

/** V2 ring colours of the current theme (palette slots 0..2), on a tile ([background] = the surface under the ring). */
fun HB.ringColors(background: Color = colors.tile): RingColors {
    val bg = background.toArgb()
    val fills = IntArray(3) { if (dark) RingColors.darkFill(palette.c0Argb(it), palette.c1Argb(it)) else RingColors.lightFill(palette.c0Argb(it), palette.c1Argb(it), colors.card.toArgb()) }
    return RingColors(
        fills, IntArray(3) { RingColors.lapOf(fills[it]) }, palette.trackSeed?.toArgb() ?: RingColors.trackOn(bg, colors.text.toArgb(), dark), bg,
        colors.text.toArgb(), colors.muted.toArgb(),
    )
}

/**
 * Animated progress values for rings: 0 → p on first show (900 ms emphasized-decelerate, [staggerMs] between rings),
 * then from the current value to any new target. Reduced motion: final values immediately.
 * Fires one CONFIRM haptic + returns a pulse scale when a ring crosses 100 %.
 */
@Composable
private fun animatedRings(targets: List<Float>, durationMs: Int, staggerMs: Int, animate: Boolean): Pair<List<Float>, List<Float>> {
    val hb = LocalHB.current
    val view = LocalView.current
    val anims = remember(targets.size) { List(targets.size) { Animatable(if (animate && !hb.reducedMotion) 0f else targets[it]) } }
    val pulses = remember(targets.size) { List(targets.size) { Animatable(1f) } }
    LaunchedEffect(targets) {
        targets.forEachIndexed { i, t ->
            launch {
                val a = anims[i]
                if (!animate || hb.reducedMotion) { a.snapTo(t); return@launch }
                val from = a.value
                delay((i * staggerMs).toLong())
                a.animateTo(t, tween(durationMs, easing = EmphasizedDecelerate))
                if (from < 1f && t >= 1f) {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    pulses[i].animateTo(1.04f, tween(125))
                    pulses[i].animateTo(1f, tween(125))
                }
            }
        }
    }
    return anims.map { it.value } to pulses.map { it.value }
}

/** How ring numbers are shown (IC6). */
data class RingLabelSpec(
    /** Smallest label (sp). Below it the numbers leave the stroke (BESIDE; draw [RingLegend] next to the ring). */
    val minSp: Float = RingLabelLayout.MIN_SP,
    /** Largest label (sp). */
    val maxSp: Float = RingLabelLayout.MAX_SP,
)

/** True when rings of [size] / [stroke] carry integrated numbers (else show [RingLegend] beside them). */
fun ringsCarryNumbers(size: Dp, stroke: Dp? = null, spec: RingLabelSpec = RingLabelSpec()): Boolean =
    RingLabelLayout.canIntegrate((stroke ?: (size * 0.13f)).value, 1f, spec.minSp)

/**
 * Concentric rings (outer → inner = palette slots 0..2: Workout minutes · Steps · Heart points) in the current theme,
 * V2 fill fix, numbers INTEGRATED in the strokes (G0) when [ringLabels] are given and the stroke allows ≥ 10 sp.
 * @param progress fractions per slot (1 = goal; > 1 second lap)
 * @param size diameter; stroke defaults to 0.12·size, gap 3 dp
 * @param labels v6 form: plain value strings by slot (no target); prefer [ringLabels]
 * @param ringLabels V2 labels by slot ("34 / 50"), e.g. RingData.labels(V2.rings(env, Day(today)))
 * @param background surface under the rings (track and lap rim are derived from it)
 */
@Composable
fun TripleRing(
    progress: List<Float>,
    modifier: Modifier = Modifier,
    size: Dp = 150.dp,
    stroke: Dp? = null,
    gap: Dp = 3.dp,
    palette: RingPalette = LocalHB.current.palette,
    animate: Boolean = true,
    durationMs: Int = 900,
    staggerMs: Int = 80,
    @Suppress("UNUSED_PARAMETER") shadow: Boolean = true,
    @Suppress("UNUSED_PARAMETER") trackAlpha: Float = LocalHB.current.ringTrackAlpha,
    labels: List<String>? = null,
    ringLabels: List<RingLabel?>? = null,
    labelSpec: RingLabelSpec = RingLabelSpec(),
    background: Color = LocalHB.current.colors.tile,
) {
    val hb = LocalHB.current
    val (values, pulses) = animatedRings(progress.take(3), durationMs, staggerMs, animate)
    val pulse = pulses.maxOrNull() ?: 1f
    val col = remember(hb, palette, background) { HB(hb.colors, palette, hb.dark, hb.type, hb.reducedMotion).ringColors(background) }
    val lab = ringLabels ?: labels?.map { RingLabel(it) }
    Canvas(modifier.size(size).scale(pulse)) {
        val d = this.size.minDimension
        val s = stroke?.toPx() ?: (d * 0.12f)
        val g = gap.toPx()
        val left = (this.size.width - d) / 2f; val top = (this.size.height - d) / 2f
        val p = FloatArray(values.size) { values[it] }
        RingPaint.triple(drawContext.canvas.nativeCanvas, left, top, d, p, lab, col, s, g, density, labelSpec.minSp)
    }
}

/** Beside-ring numbers (R-A fallback when [ringsCarryNumbers] is false): dot · "45 / 30 min" per ring. */
@Composable
fun RingLegend(ringLabels: List<RingLabel?>, progress: List<Float>, modifier: Modifier = Modifier, fontSize: Dp = 12.5.dp, pitch: Dp = 19.dp) {
    val hb = LocalHB.current
    val col = remember(hb) { hb.ringColors(hb.colors.tile) }
    Canvas(modifier.fillMaxWidth().height(pitch * ringLabels.size)) {
        RingPaint.legend(drawContext.canvas.nativeCanvas, 0f, 0f, this.size.width, ringLabels, progress.toFloatArray(), col,
            fontSize.toPx(), pitch.toPx(), density)
    }
}

/** A single ring with any colours (e.g. a goal ring, workout-days ring, SpO2): fill = [colors].second on dark, .first on light. */
@Composable
fun SingleRing(
    progress: Float,
    colors: Pair<Color, Color>,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    stroke: Dp? = null,
    animate: Boolean = true,
    durationMs: Int = 900,
    @Suppress("UNUSED_PARAMETER") shadow: Boolean = true,
    @Suppress("UNUSED_PARAMETER") trackAlpha: Float = LocalHB.current.ringTrackAlpha,
    /** Optional integrated number ("34 / 50"). */
    label: RingLabel? = null,
    background: Color = LocalHB.current.colors.tile,
) {
    val hb = LocalHB.current
    val (values, pulses) = animatedRings(listOf(progress), durationMs, 0, animate)
    val fill = (if (hb.dark) colors.second else colors.first).toArgb()
    val col = remember(hb, fill, background) { hb.ringColors(background).single(fill) }
    Canvas(modifier.size(size).scale(pulses[0])) {
        val d = this.size.minDimension
        val s = stroke?.toPx() ?: (d * 0.12f)
        RingPaint.triple(drawContext.canvas.nativeCanvas, (this.size.width - d) / 2f, (this.size.height - d) / 2f, d,
            floatArrayOf(values[0]), label?.let { listOf(it) }, col, s, 0f, density)
    }
}

/** Ring i of the current palette as a single ring (0 Workout minutes, 1 Steps, 2 Heart points). */
@Composable
fun PaletteRing(index: Int, progress: Float, modifier: Modifier = Modifier, size: Dp = 32.dp, stroke: Dp? = null, animate: Boolean = true) {
    val pal = LocalHB.current.palette
    SingleRing(progress, pal.ring(index), modifier, size, stroke, animate, durationMs = if (size < 60.dp) 500 else 900)
}

// =====================================================================================================================
// v3 canonical ring set (UI_PLAN "v3 creative decisions" §1)
// =====================================================================================================================

/**
 * v4 ring set (P-RINGS): Steps · Workout minutes (daily) · Intensity minutes (weekly). Index = palette ring index
 * (RingPalette.ring(i)), drawn outer → inner, and kept on the v3 colour slots (WEEKLY_SPEC §3):
 *   0 = workout minutes (old Calories slot, Copper) · 1 = weekly intensity (old Zone slot, Pine) · 2 = steps (Marigold).
 * Rows / legends list them in the owner's order [displayOrder] = steps, workout minutes, intensity.
 * Ring 3 is the WEEK's intensity (Mon..today) vs Goals.zoneMinWeek; the other two are today's values vs daily goals.
 * Progress lists are always in INDEX order: [workout, intensity, steps] (see [progressOf]).
 */
object TodayRingSet {
    /** Ring positions outer → inner (= palette slots, RingData.ORDER): Workout minutes · Steps · Heart points. */
    const val WORKOUT = 0
    const val STEPS = 1
    const val INTENSITY = 2
    @Deprecated("v4: calories left the rings", ReplaceWith("WORKOUT")) const val CALORIES = 0
    @Deprecated("v6: ring 3 is Heart points", ReplaceWith("INTENSITY")) const val ZONE = 2
    val displayOrder: List<Int> = listOf(WORKOUT, STEPS, INTENSITY)
    /** Names by ring position (V2 wording, V2_TEXTS: never "Training", never "Weekly heart points"). */
    val labels: List<String> = listOf("Workout minutes", "Steps", "Heart points")
    val shortLabels: List<String> = labels
    val units: List<String> = listOf("min", "steps", "pts")
    val metricIds: List<String> = RingData.ORDER
    fun progressOf(d: com.boudhealth.data.RingDay): List<Float> = RingData.progress(d).toList()
    /** Values by position: workout minutes, steps, the DAY's heart points (D3; was the week %). */
    fun valuesOf(d: com.boudhealth.data.RingDay): List<Double> = listOf(d.trainingMin.toDouble(), d.steps ?: 0.0, (d.intensity ?: 0).toDouble())
    fun goalsOf(d: com.boudhealth.data.RingDay): List<Int> = listOf(d.workoutMinGoal, d.stepsGoal, d.intensityGoal)
    /** Plain value texts by position ("34", "7,240", "29"). */
    fun arcLabels(d: com.boudhealth.data.RingDay): List<String> = RingData.labels(d).map { it?.value ?: "" }
    /** V2 labels by position ("34 / 30", "7,240 / 10,000", "29 / 50"), matched by ring id. */
    fun ringLabels(d: com.boudhealth.data.RingDay): List<RingLabel?> = RingData.labels(d)
}

/**
 * The canonical big combined rings (Today tile L/XL, Activity hero, Rings widget): Workout minutes · Steps · Heart
 * points, design stroke 0.10·size and gap 4 dp (design6 rings_hero, QA #2), numbers integrated in the strokes (G0). IC6:
 *
 *     val set = V2.rings(env, SectionPeriod.Day(today))
 *     TodayRings(RingData.progress(set).toList(), RingData.labels(set), size = 200.dp)
 *     if (!ringsCarryNumbers(size)) RingLegend(RingData.labels(set), progress)   // thin rings only
 */
@Composable
fun TodayRings(
    progress: List<Float>,
    labels: List<RingLabel?>?,
    modifier: Modifier = Modifier,
    labelSpec: RingLabelSpec = RingLabelSpec(),
    size: Dp = 200.dp,
    stroke: Dp? = null,
    gap: Dp = 4.dp,
    animate: Boolean = true,
) = TripleRing(
    progress = progress.take(3), modifier = modifier, size = size, stroke = stroke ?: (size * 0.10f), gap = gap,
    animate = animate, durationMs = 900, staggerMs = 80, ringLabels = labels, labelSpec = labelSpec,
)

/** v6 signature (plain value strings), kept for existing callers. */
@Composable
fun TodayRings(
    progress: List<Float>,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    stroke: Dp? = null,
    gap: Dp = 3.dp,
    palette: RingPalette = LocalHB.current.palette,
    animate: Boolean = true,
    shadow: Boolean = true,
    labels: List<String>? = null,
) = TripleRing(
    progress = progress.take(3), modifier = modifier, size = size, stroke = stroke ?: (size * 0.125f), gap = gap,
    palette = palette, animate = animate, durationMs = 900, staggerMs = 80, shadow = shadow, labels = labels,
)

/**
 * v3 weekly goal bar (replaces the weekly halo ring). Thin pill: track = tile, fill = [colors] gradient to [progress].
 * Default colours = the palette's Zone ring. Animates like the rings (900 ms emphasized-decelerate).
 */
@Composable
fun WeekBar(
    progress: Float,
    modifier: Modifier = Modifier,
    colors: Pair<Color, Color> = LocalHB.current.palette.heartPoints,
    height: Dp = 6.dp,
    animate: Boolean = true,
) {
    val hb = LocalHB.current
    val (values, _) = animatedRings(listOf(progress), 900, 0, animate)
    val track = hb.colors.tileHi.toArgb()
    val fill = (if (hb.dark) colors.second else colors.first).toArgb()
    Canvas(modifier.fillMaxWidth().height(height)) {
        RingPaint.bar(drawContext.canvas.nativeCanvas, 0f, 0f, this.size.width, this.size.height, values[0], fill, track)
    }
}
