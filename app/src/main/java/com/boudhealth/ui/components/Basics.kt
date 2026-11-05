package com.boudhealth.ui.components

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.layout
import com.boudhealth.ui.theme.HbShapes
import com.boudhealth.ui.theme.LocalHB

// =====================================================================================================================
// Haptics
// =====================================================================================================================

/** View-based haptics (CLOCK_TICK etc. aren't in Compose's HapticFeedbackType). */
class Haptics(private val view: View) {
    fun tick() { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    fun segment() { view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK) }
    fun confirm() { view.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }
    fun reject() { view.performHapticFeedback(HapticFeedbackConstants.REJECT) }
    fun longPress() { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
}

@Composable
fun rememberHaptics(): Haptics { val v = LocalView.current; return remember(v) { Haptics(v) } }

// =====================================================================================================================
// Press feedback: scale 0.97 (100 ms) + ripple in text @ 8 % (DESIGN §6)
// =====================================================================================================================

/**
 * Clickable with our press feedback. [scaleOnPress] for tiles/cards; [shape] clips the ripple.
 * [onLongClick] optional (e.g. long-press to edit the home grid).
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.hbClickable(
    enabled: Boolean = true,
    scaleOnPress: Boolean = true,
    shape: Shape? = null,
    role: Role? = Role.Button,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val hb = LocalHB.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && scaleOnPress && enabled) 0.97f else 1f, tween(100), label = "press")
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .then(if (shape != null) Modifier.clip(shape) else Modifier)
        .combinedClickable(
            interactionSource = source,
            indication = ripple(color = hb.colors.text.copy(alpha = 0.08f)),
            enabled = enabled,
            role = role,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}

/** Clickable without ripple or scale (for segments etc.). */
fun Modifier.plainClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    this.combinedClickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}

// =====================================================================================================================
// Surfaces: tonal, no elevation, no outlines (DESIGN §4)
// =====================================================================================================================

/** Card: `card` colour, r 28, padding 20. Pass [onClick] for a tappable card (press scale). */
@Composable
fun HBCard(
    modifier: Modifier = Modifier,
    color: Color = LocalHB.current.colors.card,
    shape: Shape = HbShapes.card,
    padding: PaddingValues = PaddingValues(20.dp),
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val hb = LocalHB.current
    // V2 light depth (V2_VISUAL_QA §3): a soft 1 dp shadow + 1 dp warm border on light cards; dark cards read on the wall
    val depth = if (!hb.dark && color == hb.colors.card)
        modifier.shadow(1.dp, shape, clip = false, ambientColor = Color(0x0F1C1C19), spotColor = Color(0x141C1C19))
            .border(1.dp, Color(0xFFECE9E0), shape)
    else modifier
    val m = if (onClick != null) depth.hbClickable(shape = shape, onLongClick = onLongClick, onClick = onClick) else depth.clip(shape)
    Column(m.background(color, shape).padding(padding), content = content)
}

// =====================================================================================================================
// Icon buttons + glyph circles
// =====================================================================================================================

/** Circle icon button (DESIGN §5.1): 40 circle in `tile`, glyph 22 in `text`. Disabled = 38 % opacity. */
@Composable
fun IconCircleButton(
    glyph: Glyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    size: Dp = 40.dp,
    glyphSize: Dp = 22.dp,
    background: Color = LocalHB.current.colors.tile,
    tint: Color = LocalHB.current.colors.text,
    enabled: Boolean = true,
) {
    // v3.1 r2: the layout is exactly [size] (visuals line up with the title / gutter edge). The clickable is a pointer-
    // input node, and Compose expands the hit area of pointer-input nodes smaller than the ViewConfiguration minimum
    // touch target (48 dp) around them, so the target is 48 × 48 without growing the layout (an overflow touch area).
    Box(
        modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.38f)
            .hbClickable(enabled = enabled, shape = CircleShape, onClick = onClick)
            .background(background, CircleShape)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(glyph, size = glyphSize, color = tint) }
}

/**
 * Glyph circle (list rows, tiles, empty states): category tint background (16 % / 22 %), glyph in legible(c, 3:1).
 * [background] overrides the tint (e.g. `surface` for empty states).
 */
@Composable
fun GlyphCircle(
    glyph: Glyph,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    glyphSize: Dp = 22.dp,
    background: Color? = null,
    stroke: Float = 2.2f,
) {
    val hb = LocalHB.current
    Box(modifier.size(size).background(background ?: hb.tint(color), CircleShape), contentAlignment = Alignment.Center) {
        GlyphIcon(glyph, size = glyphSize, color = hb.legibleGlyph(color, background ?: hb.colors.card), stroke = stroke)
    }
}

/** Small category dot (r 4) used in label rows. */
@Composable
fun Dot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).background(color, CircleShape))
}

// =====================================================================================================================
// Buttons (DESIGN §5.10)
// =====================================================================================================================

enum class ButtonKind { Primary, Tonal, Text }

/**
 * Pill button. Primary: palette accent fill + onAccent label (v3 C2). Tonal: accentCont + onAccentCont. Text: accent label only.
 * [small] = h 40, 14/600, padding 18; else h 52, 15/600. [fullWidth] only inside sheets. One primary per view.
 * [busy] (R6): the action is running: not clickable, drawn disabled, and its [glyph] turns (off under reduced motion).
 */
@Composable
fun HBButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    small: Boolean = false,
    glyph: Glyph? = null,
    enabled: Boolean = true,
    fullWidth: Boolean = false,
    busy: Boolean = false,
) {
    val hb = LocalHB.current
    val c = hb.colors
    val active = enabled && !busy
    // same infinite-transition pattern as the shimmer (Feedback.kt) and the edit wiggle (EditHomeScreen.kt)
    val spin: State<Float>? = if (busy && glyph != null && !hb.reducedMotion) {
        val inf = rememberInfiniteTransition(label = "buttonBusy")
        inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "buttonBusySpin")
    } else null
    val (bg, fg) = when (kind) {
        ButtonKind.Primary -> c.accent to c.onAccent
        ButtonKind.Tonal -> c.accentCont to c.onAccentCont
        ButtonKind.Text -> Color.Transparent to c.accent
    }
    val h = if (small) 40.dp else 52.dp
    val shape = RoundedCornerShape(h / 2)
    val hp = when { kind == ButtonKind.Text -> 12.dp; small -> 18.dp; else -> 24.dp }
    Row(
        modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .height(h)
            .defaultMinSize(minWidth = if (kind == ButtonKind.Text) 48.dp else 64.dp)
            .alpha(if (active) 1f else 0.38f)
            .hbClickable(enabled = active, shape = shape, onClick = onClick)
            .background(bg, shape)
            .padding(horizontal = hp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (glyph != null) {
            GlyphIcon(glyph, size = if (small) 18.dp else 20.dp, color = fg,
                modifier = if (spin != null) Modifier.graphicsLayer { rotationZ = spin.value } else Modifier)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = if (small) hb.type.buttonS else hb.type.button, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Stack of sheet actions: tonal on top, primary below, gap 10 (DESIGN §5.11). */
@Composable
fun SheetActions(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

/**
 * v4.1 review: a touch area of at least [target] (48 dp) around a smaller control WITHOUT changing the layout: the
 * following modifiers / content are measured at max(natural, target) and centred over the natural size, which is all
 * the parent sees. Put the clickable AFTER this modifier and keep visuals (background, size) on an inner element.
 */
fun Modifier.touchExpand(target: androidx.compose.ui.unit.Dp = 48.dp): Modifier = this.then(
    Modifier.layout { m, c ->
        val t = target.roundToPx()
        val w = m.maxIntrinsicWidth(c.maxHeight.coerceAtMost(100_000)).coerceIn(c.minWidth, c.maxWidth)
        val h = m.maxIntrinsicHeight(w).coerceIn(c.minHeight, c.maxHeight)
        val p = m.measure(androidx.compose.ui.unit.Constraints.fixed(maxOf(w, t), maxOf(h, t)))
        layout(w, h) { p.place((w - p.width) / 2, (h - p.height) / 2) }
    },
)
