package com.boudhealth.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.boudhealth.ui.theme.HbShapes
import com.boudhealth.ui.theme.LocalHB
import kotlin.math.pow
import kotlin.math.roundToLong

// =====================================================================================================================
// Count-up
// =====================================================================================================================

/**
 * Animated number (DESIGN §6): counts to [value] over [durationMs] emphasized-decelerate on first show and whenever
 * [animateKey] changes (period / range change). Value changes without a key change (e.g. scrubbing) snap.
 * Integers step, decimals round to [decimals]. Reduced motion: snaps.
 */
@Composable
fun animatedNumber(value: Double?, animateKey: Any? = Unit, durationMs: Int = 900): Double? {
    val hb = LocalHB.current
    val anim = remember { Animatable(if (hb.reducedMotion) (value ?: 0.0).toFloat() else 0f) }
    val keyHolder = remember { arrayOf<Any?>(NO_KEY) }
    LaunchedEffect(value, animateKey) {
        if (value == null) return@LaunchedEffect
        val keyChanged = keyHolder[0] != animateKey
        keyHolder[0] = animateKey
        if (keyChanged && !hb.reducedMotion) anim.animateTo(value.toFloat(), tween(durationMs, easing = EmphasizedDecelerate))
        else anim.snapTo(value.toFloat())
    }
    return if (value == null) null else anim.value.toDouble()
}

private val NO_KEY = Any()

/** Value + unit on a shared baseline: number in [style], unit in [unitStyle] muted, [gap] between. */
@Composable
fun ValueText(
    value: String,
    unit: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalHB.current.type.number,
    unitStyle: TextStyle = LocalHB.current.type.unit,
    color: Color = LocalHB.current.colors.text,
    unitColor: Color = LocalHB.current.colors.muted,
    gap: String = " ",
) {
    val text = buildAnnotatedString {
        withStyle(style.toSpanStyle().copy(color = color)) { append(value) }
        if (!unit.isNullOrEmpty()) {
            withStyle(unitStyle.toSpanStyle().copy(color = unitColor)) { append(gap); append(unit) }
        }
    }
    Text(text, modifier, style = style, maxLines = 1)
}

/** "7h 20m" with numbers in [style] and letters in [unitStyle] (DESIGN §7). */
@Composable
fun DurationText(minutes: Int, modifier: Modifier = Modifier, style: TextStyle = LocalHB.current.type.number, unitStyle: TextStyle = LocalHB.current.type.unit) {
    val hb = LocalHB.current
    val text: AnnotatedString = buildAnnotatedString {
        Fmt.durationParts(minutes).forEachIndexed { i, (n, u) ->
            if (i > 0) append(" ")
            withStyle(style.toSpanStyle().copy(color = hb.colors.text)) { append(n) }
            withStyle(SpanStyle(fontSize = unitStyle.fontSize, fontWeight = unitStyle.fontWeight, color = hb.colors.muted)) { append(u) }
        }
    }
    Text(text, modifier, style = style, maxLines = 1)
}

// =====================================================================================================================
// Metric hero (DESIGN §5.4)
// =====================================================================================================================

@Immutable
data class Delta(val text: String, val kind: DeltaKind, val dir: DeltaDir = DeltaDir.None)

/**
 * 1. dot + label 13/600 in legible(category) · 2. value 56/700 + unit 20/500 muted · 3. context 14 muted ·
 * 4. delta pill(s). While [scrubbing], pass the scrubbed value + timestamp as [context]: the delta fades out and the
 * value snaps (no count-up). [valueText] overrides formatting (e.g. "7h 20m" → use [valueContent] instead).
 */
@Composable
fun MetricHero(
    label: String,
    color: Color,
    value: Double?,
    unit: String?,
    modifier: Modifier = Modifier,
    decimals: Int = 0,
    context: String? = null,
    deltas: List<Delta> = emptyList(),
    scrubbing: Boolean = false,
    animateKey: Any? = Unit,
    valueText: ((Double) -> String)? = null,
    valueContent: (@Composable () -> Unit)? = null,
) {
    val hb = LocalHB.current
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(color)
            Spacer(Modifier.width(8.dp))
            Text(label, style = hb.type.label, color = hb.legible(color, hb.colors.surface), maxLines = 1)
        }
        Spacer(Modifier.height(2.dp))
        if (valueContent != null) valueContent()
        else {
            // always composed (also while scrubbing) so the animation state survives; scrubbed values snap
            val shown = animatedNumber(value, animateKey)
            val txt = when {
                shown == null -> "–"
                valueText != null -> valueText(shown)
                else -> Fmt.num(roundTo(shown, decimals), decimals)
            }
            ValueText(txt, unit, style = hb.type.display, unitStyle = hb.type.displayUnit, gap = " ")
        }
        if (context != null) {
            Text(context, style = hb.type.bodyS, color = hb.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        AnimatedVisibility(!scrubbing && deltas.isNotEmpty(), enter = fadeIn(tween(150)), exit = fadeOut(tween(100))) {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                deltas.forEach { DeltaPill(it.text, it.kind, dir = it.dir) }
            }
        }
    }
}

private fun roundTo(v: Double, decimals: Int): Double {
    val f = 10.0.pow(decimals)
    return (v * f).roundToLong() / f
}

// =====================================================================================================================
// Stat row (DESIGN §5.6)
// =====================================================================================================================

@Immutable
data class Stat(val label: String, val value: String, val unit: String? = null)

/** `tile`, r 18, 3 (or n) equal columns with 1 dp `grid` dividers (inset 4); label 12 muted, value 22/700 + unit 13 muted. */
@Composable
fun StatRow(stats: List<Stat>, modifier: Modifier = Modifier, color: Color = LocalHB.current.colors.tile) {
    val hb = LocalHB.current
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min).background(color, HbShapes.tile).padding(vertical = 14.dp),
    ) {
        stats.forEachIndexed { i, s ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 4.dp).background(hb.colors.grid))
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(s.label, style = hb.type.bodyXS.copy(fontSize = hb.type.labelS.fontSize), color = hb.colors.muted, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                ValueText(s.value, s.unit, style = hb.type.numberS, unitStyle = hb.type.unit)
            }
        }
    }
}

// =====================================================================================================================
// List row (DESIGN §5.8)
// =====================================================================================================================

/**
 * Min-h 68: glyph circle 44 · gap 14 · title 16/600 + subtitle 13 muted · right value 16/600 + sub 12 muted · chevron 18.
 * Divider 1 dp `grid` inset 58 from the row start; pass [divider] = false on the last row.
 * Pressed: `tile` background r 16 with 8 dp bleed.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    glyph: Glyph? = null,
    color: Color = LocalHB.current.colors.muted,
    subtitle: String? = null,
    subtitleColor: Color? = null,
    value: String? = null,
    valueSub: String? = null,
    chevron: Boolean = true,
    divider: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val hb = LocalHB.current
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (onClick != null) Modifier.hbClickable(shape = HbShapes.tileS, scaleOnPress = false, onClick = onClick) else Modifier)
                .heightIn(min = 68.dp)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                leading != null -> leading()
                glyph != null -> GlyphCircle(glyph, color)
            }
            if (leading != null || glyph != null) Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = hb.type.titleS, color = hb.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = hb.type.bodyXS, color = subtitleColor ?: hb.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (value != null || valueSub != null) {
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    if (value != null) Text(value, style = hb.type.titleS, color = hb.colors.text, maxLines = 1, textAlign = TextAlign.End)
                    if (valueSub != null) Text(valueSub, style = hb.type.bodyXS.copy(fontSize = hb.type.labelS.fontSize), color = hb.colors.muted, maxLines = 1)
                }
            }
            trailing?.invoke()
            if (chevron && onClick != null) {
                Spacer(Modifier.width(8.dp))
                GlyphIcon(Glyph.ChevR, size = 18.dp, color = hb.colors.muted)
            }
        }
        if (divider) {
            val inset: Dp = if (glyph != null || leading != null) 58.dp else 0.dp
            Box(Modifier.padding(start = inset).fillMaxWidth().height(1.dp).background(hb.colors.grid))
        }
    }
}
