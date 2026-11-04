package com.boudhealth.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.ui.theme.HbShapes
import com.boudhealth.ui.theme.LocalHB
import com.boudhealth.ui.theme.RingPalette
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.math.roundToLong

// =====================================================================================================================
// Group (DESIGN §5.9 / C_SPEC §3)
// =====================================================================================================================

class SettingsGroupBuilder internal constructor() {
    internal val rows = mutableListOf<@Composable () -> Unit>()
    /** Add one row (any composable; usually one of the *Row components below). */
    fun row(content: @Composable () -> Unit) { rows += content }
}

/**
 * Group label 13/600 muted (8 above the group, inset 4) + `tile` r 20 container; rows separated by a 1 dp cut in
 * the `surface` colour, inset 16 both sides.
 *
 *     SettingsGroup("Goals") {
 *         row { StepperRow("Calories", value = g.kcal.toDouble(), onValueChange = {…}, step = 10.0, unit = "kcal") }
 *         row { SwitchRow("Show Today pill", checked = p.showTodayPill, onCheckedChange = {…}) }
 *     }
 */
@Composable
fun SettingsGroup(label: String?, modifier: Modifier = Modifier, build: SettingsGroupBuilder.() -> Unit) {
    val hb = LocalHB.current
    val rows = SettingsGroupBuilder().apply(build).rows
    Column(modifier.fillMaxWidth()) {
        if (label != null) {
            // QA 22.2: overline section label (11/600, letter-spaced, caps) as in the design
            Text(label.uppercase(), style = hb.type.overline, color = hb.colors.muted, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        }
        Column(Modifier.fillMaxWidth().background(hb.colors.card, HbShapes.group)) {
            rows.forEachIndexed { i, r ->
                if (i > 0) Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(hb.colors.surface))
                r()
            }
        }
    }
}

/**
 * Base row: min-h 64, padding 16 (12 vertical): title 15/500 + optional subtitle 12.5 muted (+ optional leading dot),
 * control at the right. Tappable when [onClick] is set.
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    dot: Color? = null,
    leadingGlyph: Glyph? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val hb = LocalHB.current
    Row(
        modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.hbClickable(shape = HbShapes.group, scaleOnPress = false, onClick = onClick) else Modifier)
            .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingGlyph != null) { GlyphIcon(leadingGlyph, size = 22.dp, color = hb.colors.text); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (dot != null) { Dot(dot); Spacer(Modifier.width(8.dp)) }
                Text(title, style = hb.type.bodyMedium, color = hb.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (subtitle != null) Text(subtitle, style = hb.type.bodyXS.copy(fontSize = 12.5.sp), color = hb.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) { Spacer(Modifier.width(12.dp)); trailing() }
    }
}

/** Row with an [HBSwitch]. The whole row toggles. */
@Composable
fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, enabled: Boolean = true) {
    SettingsRow(title, modifier.alpha(if (enabled) 1f else 0.38f), subtitle, onClick = if (enabled) ({ onCheckedChange(!checked) }) else null) {
        HBSwitch(checked, onCheckedChange, enabled = enabled)
    }
}

/** Value row: value 14 muted + chevron 18; opens a picker sheet (e.g. birth year). */
@Composable
fun ChevronRow(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, value: String? = null, dot: Color? = null, leadingGlyph: Glyph? = null) {
    val hb = LocalHB.current
    SettingsRow(title, modifier, subtitle, dot, leadingGlyph, onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (value != null) Text(value, style = hb.type.bodyS, color = hb.colors.muted, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            GlyphIcon(Glyph.ChevR, size = 18.dp, color = hb.colors.muted)
        }
    }
}

/** Row with a [Stepper] at the right. */
@Composable
fun StepperRow(
    title: String,
    value: Double,
    onValueChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    step: Double = 1.0,
    range: ClosedFloatingPointRange<Double> = 0.0..Double.MAX_VALUE,
    decimals: Int = 0,
    unit: String? = null,
    dot: Color? = null,
    onValueClick: (() -> Unit)? = null,
    valueText: ((Double) -> String)? = null,
) {
    SettingsRow(title, modifier, subtitle, dot) {
        Stepper(value, onValueChange, step = step, range = range, decimals = decimals, unit = unit, onValueClick = onValueClick, valueText = valueText)
    }
}

/** Title (+ subtitle) left, small segmented control right on the same row (QA 22.3: "Theme [System Light Dark]"). */
@Composable
fun <T> SegmentRow(
    title: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    label: (T) -> String = { it.toString() },
) {
    val hb = LocalHB.current
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = hb.type.bodyMedium, color = hb.colors.text)
            if (subtitle != null) Text(subtitle, style = hb.type.bodyXS.copy(fontSize = 12.5.sp), color = hb.colors.muted)
        }
        Spacer(Modifier.width(12.dp))
        SegmentedRange(options, selected, onSelect, modifier = Modifier.width((options.size * 76).dp), label = label, size = SegmentSize.Small, track = hb.colors.surface)
    }
}

// =====================================================================================================================
// Switch (52×32)
// =====================================================================================================================

@Composable
fun HBSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val hb = LocalHB.current
    val haptics = rememberHaptics()
    val track by animateColorAsState(if (checked) hb.colors.accent else hb.colors.tileHi, tween(150), label = "swTrack")
    val thumbColor by animateColorAsState(if (checked) hb.colors.onAccent else hb.colors.outline, tween(150), label = "swThumb")
    val thumb by animateDpAsState(if (checked) 24.dp else 16.dp, spring(dampingRatio = 0.8f, stiffness = 800f), label = "swSize")
    val x by animateDpAsState(if (checked) 52.dp - 4.dp - 24.dp else 8.dp, spring(dampingRatio = 0.8f, stiffness = 800f), label = "swX")
    Box(
        modifier.size(52.dp, 32.dp)
            .semantics { role = Role.Switch; stateDescription = if (checked) "On" else "Off" }
            .plainClickable(enabled) { haptics.segment(); onCheckedChange(!checked) }
            .background(track, CircleShape)
            .then(if (!checked) Modifier.border(2.dp, hb.colors.outline, CircleShape) else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.offset(x = x).size(thumb).background(thumbColor, CircleShape))
    }
}

// =====================================================================================================================
// Stepper (DESIGN §5.9): preferred over text fields
// =====================================================================================================================

private fun snap(v: Double, decimals: Int): Double { val f = 10.0.pow(decimals); return (v * f).roundToLong() / f }

/**
 * One press = exactly one step (Q9: 1-unit precision). While held, the step grows ×10 after ~1.5 s and ×100 after
 * ~3.5 s (capped so one jump stays ≤ 1/20 of the range); jumps land on round multiples of the bigger step.
 */
private fun stepBy(v: Double, delta: Double, range: ClosedFloatingPointRange<Double>, decimals: Int): Double {
    val k = kotlin.math.abs(delta)
    val raw = v + delta
    val rounded = if (k > 1.0) (if (delta > 0) kotlin.math.floor(raw / k) else kotlin.math.ceil(raw / k)) * k else raw
    val next = if (rounded == v) raw else rounded
    return snap(next.coerceIn(range), decimals)
}

private fun maxMult(step: Double, range: ClosedFloatingPointRange<Double>): Int {
    val span = range.endInclusive - range.start
    if (span.isInfinite() || span <= 0) return 10
    return when {
        step * 100 <= span / 20 -> 100
        step * 10 <= span / 20 -> 10
        else -> 1
    }
}

/**
 * Pill h 40, `surface` background, − / + 32 circles, value 15/700 + unit 12 muted, min-w 74.
 * Tap the value → [onValueClick] (open a numeric sheet). Long-press ± auto-repeats: 400 ms delay, then every 80 ms,
 * ×2 faster after 1 s. Steps: kcal 10, minutes 5, steps 500, waist 0.1 cm.
 */
@Composable
fun Stepper(
    value: Double,
    onValueChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    step: Double = 1.0,
    range: ClosedFloatingPointRange<Double> = 0.0..Double.MAX_VALUE,
    decimals: Int = 0,
    unit: String? = null,
    background: Color = LocalHB.current.colors.surface,
    onValueClick: (() -> Unit)? = null,
    valueText: ((Double) -> String)? = null,
) {
    val hb = LocalHB.current
    Row(
        modifier.height(40.dp).background(background, RoundedCornerShape(20.dp)).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RepeatButton(Glyph.Minus, 32.dp, 20.dp, enabled = value > range.start, value = value, onChange = onValueChange, maxMult = maxMult(step, range)) { v, k -> stepBy(v, -step * k, range, decimals) }
        Box(
            Modifier.defaultMinSize(minWidth = 74.dp).then(if (onValueClick != null) Modifier.plainClickable(onClick = onValueClick) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            ValueText(valueText?.invoke(value) ?: Fmt.num(value, decimals), unit, style = hb.type.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), unitStyle = hb.type.unit.copy(fontSize = 12.sp))
        }
        RepeatButton(Glyph.Plus, 32.dp, 20.dp, enabled = value < range.endInclusive, value = value, onChange = onValueChange, maxMult = maxMult(step, range)) { v, k -> stepBy(v, step * k, range, decimals) }
    }
}

/**
 * Big stepper well (DESIGN §5.11, "Add waist"): `sheetTile` r 22, ± 52 circles in `sheet`, value 40/700 + unit 16 muted,
 * optional caption ("Last: 95.8 cm · 23 Oct") 12 muted.
 */
@Composable
fun BigStepper(
    value: Double,
    onValueChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    step: Double = 1.0,
    range: ClosedFloatingPointRange<Double> = 0.0..Double.MAX_VALUE,
    decimals: Int = 0,
    unit: String? = null,
    caption: String? = null,
    valueText: ((Double) -> String)? = null,
) {
    val hb = LocalHB.current
    Row(
        modifier.fillMaxWidth().background(hb.colors.sheetTile, RoundedCornerShape(22.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RepeatButton(Glyph.Minus, 52.dp, 24.dp, enabled = value > range.start, value = value, bg = hb.colors.sheet, onChange = onValueChange, maxMult = maxMult(step, range)) { v, k -> stepBy(v, -step * k, range, decimals) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            ValueText(valueText?.invoke(value) ?: Fmt.num(value, decimals), unit, style = hb.type.display.copy(fontSize = 40.sp, lineHeight = 46.sp, letterSpacing = (-0.8).sp), unitStyle = hb.type.displayUnit.copy(fontSize = 16.sp))
            if (caption != null) Text(caption, style = hb.type.bodyXS.copy(fontSize = 12.sp), color = hb.colors.muted, maxLines = 1)
        }
        RepeatButton(Glyph.Plus, 52.dp, 24.dp, enabled = value < range.endInclusive, value = value, bg = hb.colors.sheet, onChange = onValueChange, maxMult = maxMult(step, range)) { v, k -> stepBy(v, step * k, range, decimals) }
    }
}

/**
 * ± button with tap + long-press auto-repeat.
 * [next] computes the new value from the latest value.
 */
@Composable
private fun RepeatButton(
    glyph: Glyph,
    size: Dp,
    glyphSize: Dp,
    enabled: Boolean,
    value: Double,
    bg: Color = Color.Transparent,
    onChange: (Double) -> Unit,
    maxMult: Int = 1,
    next: (Double, Double) -> Double,
) {
    val hb = LocalHB.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val latestValue by rememberUpdatedState(value)
    val latestChange by rememberUpdatedState(onChange)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestNext by rememberUpdatedState(next)
    // v4.1 review: 48 dp touch area around the visual circle, layout unchanged
    Box(
        Modifier.size(size).touchExpand().alpha(if (enabled) 1f else 0.38f)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    if (!latestEnabled) return@detectTapGestures
                    var current = latestValue
                    val job: Job = scope.launch {
                        current = latestNext(current, 1.0); latestChange(current); haptics.tick()
                        delay(400)
                        val start = System.currentTimeMillis()
                        while (true) {
                            val held = System.currentTimeMillis() - start
                            val mult = when {
                                held > 3_500 && maxMult >= 100 -> 100.0
                                held > 1_500 && maxMult >= 10 -> 10.0
                                else -> 1.0
                            }
                            val nv = latestNext(current, mult)
                            if (nv == current) break
                            current = nv; latestChange(current); haptics.tick()
                            delay(if (held > 1000) 60L else 90L)
                        }
                    }
                    tryAwaitRelease()
                    job.cancel()
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size).background(bg, CircleShape))
        GlyphIcon(glyph, size = glyphSize, color = hb.colors.text)
    }
}

// =====================================================================================================================
// Palette swatch (picker grid): mini triple ring in the palette + caption; selected = surface fill + 2 dp text ring.
// =====================================================================================================================

@Composable
fun PaletteSwatch(palette: RingPalette, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp, caption: Boolean = true) {
    val hb = LocalHB.current
    Column(modifier.touchExpand().plainClickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size + 12.dp)
                .then(if (selected) Modifier.background(hb.colors.surface, CircleShape).border(2.dp, hb.colors.accent, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            TripleRing(listOf(0.80f, 0.92f, 0.62f), size = size, palette = palette /* raw palette colours in the picker (v3.1 r2) */, animate = false, shadow = false, gap = 1.5.dp)
        }
        if (caption) Spacer(Modifier.height(4.dp))
        if (caption) Text(palette.short, style = hb.type.micro.copy(fontSize = 10.5.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = hb.colors.muted, maxLines = 1)
    }
}

// =====================================================================================================================
// V2-138 (T7): full colour picker. Saturation/value square, hue bar, hex field, preview swatch. Pure Compose.
// =====================================================================================================================

/** Hue (0–360), saturation and value (0–1) of [c]. */
fun colorToHsv(c: Color): Triple<Float, Float, Float> {
    val r = c.red; val g = c.green; val b = c.blue
    val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
    val h = when {
        d == 0f -> 0f
        max == r -> 60f * (((g - b) / d) % 6f)
        max == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }.let { if (it < 0f) it + 360f else it }
    return Triple(h, if (max == 0f) 0f else d / max, max)
}

/** "FF9C6E" for [c] (alpha dropped). */
fun colorHex(c: Color): String = String.format("%06X", c.toArgbInt() and 0xFFFFFF)

/** Parses "FF9C6E" / "#ff9c6e"; null when it is not 6 hex digits. */
fun parseHex(s: String): Color? {
    val t = s.trim().removePrefix("#")
    if (t.length != 6) return null
    val v = t.toLongOrNull(16) ?: return null
    return Color(0xFF000000 or v)
}

private fun Color.toArgbInt(): Int {
    fun ch(x: Float) = (x.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    return (ch(alpha) shl 24) or (ch(red) shl 16) or (ch(green) shl 8) or ch(blue)
}

/**
 * Full colour picker: a saturation × brightness square for the current hue, a hue bar, a hex field and a preview
 * swatch with [trailing] next to it (e.g. a contrast check). Tap or drag anywhere on the square or the bar.
 */
@Composable
fun ColorPicker(color: Color, onColorChange: (Color) -> Unit, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val hb = LocalHB.current
    // keep the hue while saturation or brightness is 0 (a grey would otherwise reset it); re-derive on outside changes
    var hsv by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(colorToHsv(color)) }
    var emitted by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(color) }
    if (color.toArgbInt() != emitted.toArgbInt()) { hsv = colorToHsv(color); emitted = color }
    val latest by rememberUpdatedState(onColorChange)
    fun emit(h: Float, s: Float, v: Float) {
        hsv = Triple(h, s, v)
        val c = Color.hsv(h.coerceIn(0f, 359.9f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
        emitted = c
        latest(c)
    }
    val (h, s, v) = hsv
    val hueColor = Color.hsv(h.coerceIn(0f, 359.9f), 1f, 1f)
    Column(modifier.fillMaxWidth()) {
        // ---- saturation / value square
        var sq by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        fun pickSv(o: androidx.compose.ui.geometry.Offset) {
            if (sq.width == 0 || sq.height == 0) return
            emit(hsv.first, (o.x / sq.width).coerceIn(0f, 1f), 1f - (o.y / sq.height).coerceIn(0f, 1f))
        }
        Box(
            Modifier.fillMaxWidth().height(180.dp).clipRounded(18.dp)
                .onSizeChangedCompat { sq = it }
                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.White, hueColor)))
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                .pointerInput(Unit) { detectTapGestures(onTap = { pickSv(it) }) }
                .pointerInput(Unit) { detectDragGestures(onDragStart = { pickSv(it) }) { ch, _ -> pickSv(ch.position) } }
                .semantics { stateDescription = "#${colorHex(Color.hsv(h.coerceIn(0f, 359.9f), s, v))}" },
        ) {
            val d = androidx.compose.ui.platform.LocalDensity.current
            val x = with(d) { (s * sq.width).toDp() } - 11.dp
            val y = with(d) { ((1f - v) * sq.height).toDp() } - 11.dp
            Box(Modifier.offset(x, y).size(22.dp).border(3.dp, Color.White, CircleShape).background(Color.hsv(h.coerceIn(0f, 359.9f), s, v), CircleShape))
        }
        Spacer(Modifier.height(14.dp))
        // ---- hue bar
        var bar by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        fun pickHue(o: androidx.compose.ui.geometry.Offset) {
            if (bar.width == 0) return
            emit((o.x / bar.width).coerceIn(0f, 1f) * 359.9f, hsv.second, hsv.third)
        }
        Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.CenterStart) {
            Box(
                Modifier.fillMaxWidth().height(16.dp).clipRounded(8.dp)
                    .onSizeChangedCompat { bar = it }
                    .background(androidx.compose.ui.graphics.Brush.horizontalGradient((0..6).map { Color.hsv((it * 60f).coerceAtMost(359.9f), 1f, 1f) }))
                    .pointerInput(Unit) { detectTapGestures(onTap = { pickHue(it) }) }
                    .pointerInput(Unit) { detectDragGestures(onDragStart = { pickHue(it) }) { ch, _ -> pickHue(ch.position) } },
            )
            val d = androidx.compose.ui.platform.LocalDensity.current
            val x = with(d) { (h / 360f * bar.width).toDp() } - 12.dp
            Box(Modifier.offset(x, 0.dp).size(24.dp).border(3.dp, Color.White, CircleShape).background(hueColor, CircleShape))
        }
        Spacer(Modifier.height(14.dp))
        // ---- hex + preview
        Row(verticalAlignment = Alignment.CenterVertically) {
            val field = androidx.compose.foundation.text.input.rememberTextFieldState(colorHex(emitted))
            // outside change (square, bar, quick pick) → show its hex; typing a full hex → new colour
            androidx.compose.runtime.LaunchedEffect(emitted) {
                if (parseHex(field.text.toString())?.toArgbInt() != emitted.toArgbInt()) field.setTextAndPlaceCursorAtEnd(colorHex(emitted))
            }
            androidx.compose.runtime.LaunchedEffect(field) {
                androidx.compose.runtime.snapshotFlow { field.text.toString() }.collect { t ->
                    val c = parseHex(t) ?: return@collect
                    if (c.toArgbInt() != emitted.toArgbInt()) { hsv = colorToHsv(c); emitted = c; latest(c) }
                }
            }
            Row(
                Modifier.weight(1f).height(44.dp).background(hb.colors.sheetTile, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("#", style = hb.type.bodyMedium, color = hb.colors.muted)
                Spacer(Modifier.width(6.dp))
                androidx.compose.foundation.text.BasicTextField(
                    field, Modifier.weight(1f).semantics { contentDescription = "Hex colour" },
                    lineLimits = androidx.compose.foundation.text.input.TextFieldLineLimits.SingleLine,
                    textStyle = hb.type.bodyMedium.copy(color = hb.colors.text),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(hb.colors.accent),
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(44.dp).background(emitted, RoundedCornerShape(14.dp)))
            if (trailing != null) { Spacer(Modifier.width(12.dp)); trailing() }
        }
    }
}

private fun Modifier.clipRounded(r: Dp): Modifier = this.clip(RoundedCornerShape(r))
private fun Modifier.onSizeChangedCompat(f: (androidx.compose.ui.unit.IntSize) -> Unit): Modifier = this.onSizeChanged(f)
