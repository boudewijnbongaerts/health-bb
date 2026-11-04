package com.boudhealth.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.ui.theme.HbShapes
import com.boudhealth.ui.theme.LocalHB
import kotlinx.coroutines.delay

// =====================================================================================================================
// Bottom sheet (DESIGN §5.11)
// =====================================================================================================================

/**
 * Our bottom sheet: `sheet` colour, top r 28, handle 36×4 `outline`, padding 10/20/20 (+ nav inset by M3),
 * title titleL 22/700 with an optional trailing chip. Content uses `sheetTile` for wells; actions via [SheetActions].
 * Show it conditionally: `if (open) HBBottomSheet(onDismiss = { open = false }, title = "Add waist") { … }`.
 * [fullHeight] = fixed tall sheet (catalog 88 %, Today sheet): skips the half-expanded state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HBBottomSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    modifier: Modifier = Modifier,
    titleTrailing: (@Composable () -> Unit)? = null,
    // rememberModalBottomSheetState exists in every M3 release (rememberBottomSheetState only in the newest ones)
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    val hb = LocalHB.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        shape = HbShapes.sheet,
        containerColor = hb.colors.sheet,
        contentColor = hb.colors.text,
        scrimColor = hb.colors.scrim,
        dragHandle = {
            Box(Modifier.padding(top = 12.dp, bottom = 6.dp).size(36.dp, 4.dp).background(hb.colors.outline, RoundedCornerShape(2.dp)))
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 20.dp)) {
            if (title != null) {
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = hb.type.titleL, color = hb.colors.text, modifier = Modifier.weight(1f))
                    titleTrailing?.invoke()
                }
            }
            content()
        }
    }
}

/**
 * Numeric entry sheet opened by tapping a stepper value: a big stepper, a numeric text field (keyboard) and Save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NumberSheet(
    title: String,
    initial: Double,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit,
    step: Double = 1.0,
    range: ClosedFloatingPointRange<Double> = 0.0..Double.MAX_VALUE,
    decimals: Int = 0,
    unit: String? = null,
    caption: String? = null,
    saveLabel: String = "Save",
    valueText: ((Double) -> String)? = null,
) {
    val hb = LocalHB.current
    var v by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initial) }
    // v4.1 Q9: typed entry (numeric keyboard) next to the stepper; both edit the same value
    fun plain(x: Double): String = if (decimals == 0) Math.round(x).toString() else String.format(java.util.Locale.US, "%.${decimals}f", x)
    val field = androidx.compose.foundation.text.input.rememberTextFieldState(plain(initial))
    val text = field.text.toString()
    val parsed = text.trim().replace(',', '.').toDoubleOrNull()
    LaunchedEffect(text) { parsed?.takeIf { it in range }?.let { if (it != v) v = it } }
    val valid = parsed != null && parsed in range
    HBBottomSheet(onDismiss = onDismiss, title = title) {
        BigStepper(v, { x -> v = x; field.setTextAndPlaceCursorAtEnd(plain(x)) }, step = step, range = range, decimals = decimals, unit = unit, caption = caption, valueText = valueText)
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth().background(hb.colors.tile, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.text.BasicTextField(
                state = field,
                lineLimits = androidx.compose.foundation.text.input.TextFieldLineLimits.SingleLine,
                textStyle = hb.type.titleM.copy(color = hb.colors.text),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(hb.colors.accent),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = if (decimals > 0) androidx.compose.ui.text.input.KeyboardType.Decimal else androidx.compose.ui.text.input.KeyboardType.Number,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                modifier = Modifier.weight(1f).semantics { contentDescription = "Type $title" },
            )
            if (unit != null) Text(unit, style = hb.type.body, color = hb.colors.muted)
        }
        Text(
            if (valid || text.isEmpty()) "Type a value or use − / +" else "Between ${plain(range.start)} and ${plain(range.endInclusive)}",
            style = hb.type.bodyXS, color = if (valid || text.isEmpty()) hb.colors.muted else hb.colors.bad,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
        SheetActions { HBButton(saveLabel, { onSave(if (valid) parsed else v); onDismiss() }, fullWidth = true, enabled = valid || text.isEmpty()) }
    }
}

// =====================================================================================================================
// Snackbar with Undo + toast (DESIGN §5.12). One host in AppShell; get the state via LocalSnackbar.
// =====================================================================================================================

@Stable
class HBSnackbarState {
    data class Item(
        val id: Long,
        val message: String,
        val glyph: Glyph?,
        val actionLabel: String?,
        val onAction: (() -> Unit)?,
        val onTimeout: (() -> Unit)?,
        val durationMs: Long,
        val toast: Boolean,
    )

    var current by mutableStateOf<Item?>(null)
        private set
    private var seq = 0L

    /**
     * Undo snackbar: `inverse` pill, leading glyph, message, "Undo" in `inverseAccent`; 5 s.
     * [onAction] runs if the user taps the action; [onTimeout] when it times out or is replaced (commit point).
     */
    fun show(message: String, glyph: Glyph? = null, actionLabel: String? = "Undo", onAction: (() -> Unit)? = null, onTimeout: (() -> Unit)? = null, durationMs: Long = 5_000) {
        current?.onTimeout?.invoke()
        current = Item(++seq, message, glyph, actionLabel, onAction, onTimeout, durationMs, toast = false)
    }

    /** Toast: centred pill with a check glyph, 2.5 s, no action. */
    fun toast(message: String, glyph: Glyph = Glyph.Check) {
        current?.onTimeout?.invoke()
        current = Item(++seq, message, glyph, null, null, null, 2_500, toast = true)
    }

    fun dismiss(runTimeout: Boolean = true) {
        val c = current ?: return
        current = null
        if (runTimeout) c.onTimeout?.invoke()
    }

    internal fun action() {
        val c = current ?: return
        current = null
        c.onAction?.invoke()
    }

    internal fun expire(id: Long) { if (current?.id == id) dismiss(true) }
}

val LocalSnackbar = staticCompositionLocalOf { HBSnackbarState() }

/** Renders the current snackbar/toast at the bottom of [BoxScope]; [bottom] = distance above the bottom edge. */
@Composable
fun BoxScope.HBSnackbarHost(state: HBSnackbarState, bottom: Dp, gutter: Dp = LocalScreen.current.gutter) {
    val hb = LocalHB.current
    val item = state.current
    LaunchedEffect(item?.id) { item?.let { delay(it.durationMs); state.expire(it.id) } }
    // keep the last item while the exit animation runs
    val holder = androidx.compose.runtime.remember { arrayOfNulls<HBSnackbarState.Item>(1) }
    if (item != null) holder[0] = item
    AnimatedVisibility(
        visible = item != null,
        modifier = Modifier.align(Alignment.BottomCenter).padding(start = gutter, end = gutter, bottom = bottom + 16.dp),
        enter = slideInVertically(tween(200)) { it / 2 } + fadeIn(tween(200)),
        exit = slideOutVertically(tween(150)) { it / 2 } + fadeOut(tween(150)),
    ) {
        val cur = holder[0] ?: return@AnimatedVisibility
        if (cur.toast) {
            Row(
                Modifier.height(40.dp).background(hb.colors.inverse, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (cur.glyph != null) { GlyphIcon(cur.glyph, size = 18.dp, color = hb.colors.inverseAccent); Spacer(Modifier.width(8.dp)) }
                Text(cur.message, style = hb.type.bodyMedium.copy(fontSize = hb.type.bodyS.fontSize), color = hb.colors.onInverse, maxLines = 1)
            }
        } else {
            Row(
                Modifier.widthIn(max = 600.dp).fillMaxWidth().height(52.dp).background(hb.colors.inverse, HbShapes.snackbar).padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (cur.glyph != null) { GlyphIcon(cur.glyph, size = 18.dp, color = hb.colors.onInverse); Spacer(Modifier.width(12.dp)) }
                Text(cur.message, style = hb.type.bodyS, color = hb.colors.onInverse, maxLines = 1, modifier = Modifier.weight(1f))
                if (cur.actionLabel != null) {
                    Text(
                        cur.actionLabel, style = hb.type.buttonS.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = hb.colors.inverseAccent,
                        modifier = Modifier.plainClickable { state.action() }.padding(horizontal = 8.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

// =====================================================================================================================
// Empty / loading / error (DESIGN §5.13)
// =====================================================================================================================

/** `tile` r 24, padding 24/20, centred: glyph circle 56 (`surface`) + glyph 26 · title 16/600 · body 13.5 muted · small tonal button. */
@Composable
fun EmptyState(
    glyph: Glyph,
    color: Color,
    title: String,
    body: String = "",
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val hb = LocalHB.current
    Column(
        modifier.fillMaxWidth().background(hb.colors.tile, HbShapes.state).padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp).background(hb.colors.surface, CircleShape), contentAlignment = Alignment.Center) {
            GlyphIcon(glyph, size = 26.dp, color = hb.legibleGlyph(color, hb.colors.surface))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = hb.type.titleS, color = hb.colors.text, textAlign = TextAlign.Center)
        if (body.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(body, style = hb.type.bodyXS.copy(fontSize = 13.5.sp), color = hb.colors.muted,
                textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 270.dp))
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            HBButton(actionLabel, onAction, kind = ButtonKind.Tonal, small = true)
        }
    }
}

/**
 * Error tile: cloudOff glyph in `bad`, title, body says what is shown instead; text "Permissions" + small primary "Retry".
 */
@Composable
fun ErrorState(
    modifier: Modifier = Modifier,
    title: String = "Couldn't reach Health Connect",
    body: String = "Showing the last data we have.",
    onRetry: (() -> Unit)? = null,
    onPermissions: (() -> Unit)? = null,
) {
    val hb = LocalHB.current
    Column(
        modifier.fillMaxWidth().background(hb.colors.tile, HbShapes.state).padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp).background(hb.colors.surface, CircleShape), contentAlignment = Alignment.Center) {
            GlyphIcon(Glyph.CloudOff, size = 26.dp, color = hb.colors.bad)
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = hb.type.titleS, color = hb.colors.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(body, style = hb.type.bodyXS, color = hb.colors.muted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 270.dp))
        if (onRetry != null || onPermissions != null) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onPermissions != null) HBButton("Permissions", onPermissions, kind = ButtonKind.Text, small = true)
                if (onRetry != null) HBButton("Retry", onRetry, small = true, glyph = Glyph.Sync)
            }
        }
    }
}

/** Shimmer sweep (tileHi → tile → tileHi, every 1.2 s). Apply to skeleton shapes. */
fun Modifier.shimmer(): Modifier = composed {
    val hb = LocalHB.current
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "shimmerX")
    drawWithContent {
        val w = size.width
        drawRect(
            Brush.linearGradient(
                listOf(hb.colors.tileHi, hb.colors.tile, hb.colors.tileHi),
                start = Offset(w * x - w * 0.5f, 0f), end = Offset(w * x + w * 0.5f, size.height),
            ),
        )
    }
}

/** A skeleton block in `tileHi` with shimmer. */
@Composable
fun SkeletonBox(modifier: Modifier = Modifier, height: Dp = 12.dp, radius: Dp = 6.dp) {
    Box(modifier.height(height).clip(RoundedCornerShape(radius)).shimmer())
}

/** Skeleton mirroring the metric screen: label bar, value block, bars (DESIGN §5.13). Never a spinner for content. */
@Composable
fun MetricSkeleton(modifier: Modifier = Modifier, chartHeight: Dp = 180.dp) {
    Column(modifier.fillMaxWidth()) {
        SkeletonBox(Modifier.width(120.dp), 12.dp, 6.dp)
        Spacer(Modifier.height(12.dp))
        SkeletonBox(Modifier.width(180.dp), 40.dp, 12.dp)
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth().height(chartHeight), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            listOf(0.4f, 0.65f, 0.3f, 0.8f, 0.5f, 0.7f, 0.45f).forEach { f ->
                SkeletonBox(Modifier.weight(1f), chartHeight * f, 10.dp)
            }
        }
    }
}
