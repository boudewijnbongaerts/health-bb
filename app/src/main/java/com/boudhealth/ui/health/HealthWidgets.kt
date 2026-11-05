@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.boudhealth.ui.health

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.data.Pt
import com.boudhealth.data.Sample
import com.boudhealth.ui.components.BigStepper
import com.boudhealth.ui.components.ButtonKind
import com.boudhealth.ui.components.Fmt
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.GlyphIcon
import com.boudhealth.ui.components.HBBottomSheet
import com.boudhealth.ui.components.HBButton
import com.boudhealth.ui.components.SheetActions
import com.boudhealth.ui.components.hbClickable
import com.boudhealth.ui.theme.Cat
import com.boudhealth.ui.theme.HbShapes
import com.boudhealth.ui.theme.LocalHB
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// =====================================================================================================================
// About card (B_SPEC §1): tile r 20, pad 16, info glyph 20 + titleS, body 14/20 muted, optional extra content.
// =====================================================================================================================

// =====================================================================================================================
// History row (glyph-less list row, B_SPEC §1)
// =====================================================================================================================

@Composable
fun HistoryRow(
    title: String,
    value: String,
    unit: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleBad: Boolean = false,
    change: String? = null,
    divider: Boolean = true,
    struck: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val hb = LocalHB.current
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (onLongClick != null) Modifier.hbClickable(scaleOnPress = false, shape = HbShapes.tileS, onLongClick = onLongClick, onClick = {}) else Modifier)
                .heightIn(min = 60.dp).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = hb.type.titleS, color = hb.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(
                    subtitle, style = if (subtitleBad) hb.type.label.copy(fontSize = 13.sp) else hb.type.bodyXS,
                    color = if (subtitleBad) hb.colors.bad else hb.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.text,
                            textDecoration = if (struck) TextDecoration.LineThrough else null)) { append(value) }
                        if (!unit.isNullOrEmpty()) withStyle(SpanStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = hb.colors.muted)) { append(" "); append(unit) }
                    },
                    style = hb.type.titleS, maxLines = 1,
                )
                if (change != null) Text(change, style = hb.type.bodyXS.copy(fontSize = 12.sp), color = hb.colors.muted, maxLines = 1)
            }
        }
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(hb.colors.grid))
    }
}

/** Change text for history rows: "+1", "−0.4", "Same". */
fun changeText(v: Double, prev: Double?, decimals: Int): String? {
    if (prev == null) return null
    val d = v - prev
    val f = Math.pow(10.0, decimals.toDouble())
    return if (Math.round(d * f) == 0L) "Same" else Fmt.delta(d, decimals)
}

/** Swipe left to delete (with a trash reveal) + long-press to delete. */
@Composable
fun SwipeToDelete(onDelete: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val hb = LocalHB.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val latest by rememberUpdatedState(onDelete)
    Box(modifier.fillMaxWidth()) {
        if (offset.value < -1f) {
            Row(Modifier.matchParentSize().background(hb.colors.badCont, HbShapes.tileS).padding(end = 20.dp),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.Trash, size = 20.dp, color = hb.colors.bad)
            }
        }
        Box(
            Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }
                .background(hb.colors.surface)
                .pointerInput(Unit) {
                    val threshold = size.width * 0.35f
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (offset.value < -threshold) {
                                    offset.animateTo(-size.width.toFloat(), tween(150))
                                    latest()
                                    offset.snapTo(0f)
                                } else offset.animateTo(0f, tween(200))
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f, tween(200)) } },
                    ) { change, dx ->
                        change.consume()
                        scope.launch { offset.snapTo(min(0f, offset.value + dx)) }
                    }
                },
        ) { content() }
    }
}

// =====================================================================================================================
// Pills / chips
// =====================================================================================================================

enum class PillKind { Good, Bad, Neutral, Waist }

/** Small status pill: h 26, r 13, optional glyph 14 + 12.5/700 text (e.g. "✓ Recomp", "Searching…"). */
@Composable
fun StatusPill(text: String, kind: PillKind, modifier: Modifier = Modifier, glyph: Glyph? = null, trailing: (@Composable () -> Unit)? = null) {
    val hb = LocalHB.current
    val (bg, fg) = when (kind) {
        PillKind.Good -> hb.colors.goodCont to hb.colors.good
        PillKind.Bad -> hb.colors.badCont to hb.colors.bad
        PillKind.Neutral -> hb.colors.tile to hb.colors.muted
        PillKind.Waist -> hb.tint(Cat.waist) to hb.legible(Cat.waist, hb.colors.surface)
    }
    Row(modifier.height(26.dp).background(bg, RoundedCornerShape(13.dp)).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (glyph != null) { GlyphIcon(glyph, size = 14.dp, color = fg, stroke = 2.6f); Spacer(Modifier.width(5.dp)) }
        Text(text, style = hb.type.labelS.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold), color = fg, maxLines = 1)
        if (trailing != null) { Spacer(Modifier.width(6.dp)); trailing() }
    }
}

// =====================================================================================================================
// WHtR gauge (B_SPEC §2): 12 h bar 0.40–0.65 in three zones, ink ticks at 0.5 / 0.6, marker r 8 + value bubble.
// =====================================================================================================================

// =====================================================================================================================
// Recomposition chart (B_SPEC §2): change since the period start, lean + fat lines from a shared dotted zero,
// area between zero and the fat line tinted, end labels.
// =====================================================================================================================

// =====================================================================================================================
// Add waist sheet (DESIGN §5.11 example): big stepper + "Read from tape" tonal + "Save measurement" primary.
// =====================================================================================================================

@Composable
fun AddWaistSheet(last: Sample?, onDismiss: () -> Unit, onSave: (Double) -> Unit, onTape: (() -> Unit)?) {
    val hb = LocalHB.current
    var v by rememberSaveable { mutableDoubleStateOf(last?.value?.let { Math.round(it * 10) / 10.0 } ?: 95.0) }
    HBBottomSheet(onDismiss = onDismiss, title = "Add waist") {
        BigStepper(
            v, { v = it }, step = 0.1, range = 40.0..200.0, decimals = 1, unit = "cm",
            caption = last?.let { "Last: ${Fmt.num(it.value, 1)} cm · ${Fmt.shortDate(Fmt.date(it.time))}" } ?: "Measure at belly-button height, after breathing out",
        )
        Spacer(Modifier.height(20.dp))
        SheetActions {
            if (onTape != null) HBButton("Read from tape", { onDismiss(); onTape() }, kind = ButtonKind.Tonal, glyph = Glyph.Bluetooth, fullWidth = true)
            HBButton("Save measurement", { onSave(v); onDismiss() }, fullWidth = true)
        }
        Spacer(Modifier.height(4.dp))
        Text("Stays on this phone.", style = hb.type.bodyXS, color = hb.colors.muted, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}
