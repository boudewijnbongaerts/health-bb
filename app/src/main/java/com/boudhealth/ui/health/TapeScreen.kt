package com.boudhealth.ui.health

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.boudhealth.data.Sample
import com.boudhealth.tape.TapeSession
import com.boudhealth.ui.components.ButtonKind
import com.boudhealth.ui.components.DeltaPill
import com.boudhealth.ui.components.EmphasizedDecelerate
import com.boudhealth.ui.components.Fmt
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.GlyphIcon
import com.boudhealth.ui.components.HBButton
import com.boudhealth.ui.components.IconCircleButton
import com.boudhealth.ui.components.LocalSnackbar
import com.boudhealth.ui.components.deltaDir
import com.boudhealth.ui.components.deltaKind
import com.boudhealth.ui.components.plainClickable
import com.boudhealth.ui.components.rememberHaptics
import com.boudhealth.ui.components.shellBottom
import com.boudhealth.ui.nav.LocalPrefs
import com.boudhealth.ui.nav.LocalStore
import com.boudhealth.ui.nav.Nav
import com.boudhealth.ui.nav.rememberPrefsEditor
import com.boudhealth.ui.theme.Cat
import com.boudhealth.ui.theme.LocalHB
import kotlinx.coroutines.delay
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

private val PERMS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

private enum class Stage { NoPermission, Searching, Connecting, Connected, Live, Saved, NotFound }

/** A reading is final when the value has been stable this long (B_SPEC §3). */
private const val STABLE_MS = 1_500L
/** "Not found" after this long searching. */
private const val SEARCH_TIMEOUT_MS = 20_000L

/**
 * Tape measuring screen (B_SPEC §3). BLE logic is tape/TapeSession.kt (unchanged); this is only the UI:
 * searching → connected → live reading (count-up) → auto-saved with 5 s Undo (no confirm) → measure again replaces.
 */
@Composable
fun TapeScreen(nav: Nav) {
    val hb = LocalHB.current
    val ctx = LocalContext.current
    val prefs = LocalPrefs.current
    val store = LocalStore.current
    val edit = rememberPrefsEditor()
    val snackbar = LocalSnackbar.current
    val haptics = rememberHaptics()

    val session = remember { TapeSession(ctx) }
    val state by session.state.collectAsState()
    var granted by remember { mutableStateOf(PERMS.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) }
    var timedOut by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        granted = r.values.all { it }
        if (granted) { timedOut = false; session.start() }
    }
    LaunchedEffect(Unit) { if (granted) session.start() else ask.launch(PERMS) }
    DisposableEffect(Unit) { onDispose { session.stop() } }

    val entries by store.waist.collectAsState(initial = emptyList())
    val hc by produceState<Double?>(null) { value = hcHeightM(ctx) }
    val heightCm = effectiveHeightCm(hc, prefs.heightCm)

    var live by remember { mutableStateOf<Double?>(null) }
    var saved by remember { mutableStateOf<Sample?>(null) }
    val replaced = remember { mutableStateListOf<Sample>() }
    var waiting by remember { mutableStateOf(true) }
    /** Value already handled (StateFlow keeps the last reading; it must not count twice). */
    var baseline by remember { mutableStateOf<Double?>(null) }
    var undoKey by remember { mutableIntStateOf(0) }
    var undoVisible by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }

    fun startSearch() { timedOut = false; live = null; session.start() }

    fun finalize(cm: Double) {
        val t = System.currentTimeMillis()
        saved?.let { old -> edit.removeWaist(old.time); replaced.add(old) }
        edit.addWaist(cm, t)
        saved = Sample(t, cm)
        baseline = cm
        live = null
        waiting = false
        haptics.confirm()
        undoKey++
        undoVisible = true
    }

    fun undo() {
        val s = saved ?: return
        edit.removeWaist(s.time)
        val restored = replaced.removeLastOrNull()
        if (restored != null) edit.addWaist(restored.value, restored.time)
        saved = restored
        undoVisible = false
        waiting = true
    }

    // searching too long → not found
    LaunchedEffect(state) {
        if (state is TapeSession.State.Searching) {
            delay(SEARCH_TIMEOUT_MS)
            if (session.state.value is TapeSession.State.Searching) { session.stop(); timedOut = true; haptics.reject() }
        }
    }
    // connected: pop the check badge with a confirm haptic
    LaunchedEffect(state is TapeSession.State.Ready) { if (state is TapeSession.State.Ready) haptics.confirm() }
    // a new value → live; stable for 1.5 s → final, saved at once.
    // Keyed on baseline too: Cancel sets baseline = the live value, which restarts this effect and so cancels the
    // pending save (the delay is cancelled with the old coroutine); the same value then no longer qualifies.
    LaunchedEffect(state, waiting, baseline) {
        val s = state
        if (s is TapeSession.State.Ready && s.last != null && waiting && s.last != baseline) {
            live = s.last
            delay(STABLE_MS)
            if (waiting && baseline != s.last) finalize(s.last)
        }
    }
    LaunchedEffect(undoKey) { if (undoKey > 0) { delay(5_000); undoVisible = false } }

    val stage = when {
        !granted -> Stage.NoPermission
        saved != null && !waiting -> Stage.Saved
        live != null -> Stage.Live
        timedOut || state is TapeSession.State.Failed -> Stage.NotFound
        state is TapeSession.State.Connecting -> Stage.Connecting
        state is TapeSession.State.Ready -> Stage.Connected
        else -> Stage.Searching
    }
    val failMessage = (state as? TapeSession.State.Failed)?.message

    Box(Modifier.fillMaxSize().background(hb.colors.sheet)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp))
            // ---- title row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Measure", style = hb.type.titleL, color = hb.colors.text, modifier = Modifier.weight(1f))
                when (stage) {
                    Stage.Searching -> StatusPill("Searching…", PillKind.Neutral, glyph = Glyph.Bluetooth)
                    Stage.Connecting -> StatusPill("Connecting…", PillKind.Neutral, glyph = Glyph.Bluetooth)
                    Stage.Connected, Stage.Live, Stage.Saved ->
                        if (state is TapeSession.State.Ready) StatusPill("Connected", PillKind.Good, glyph = Glyph.Bluetooth)
                        else StatusPill("Disconnected", PillKind.Neutral, glyph = Glyph.Bluetooth)
                    Stage.NotFound -> StatusPill("Not found", PillKind.Bad, glyph = Glyph.Bluetooth)
                    Stage.NoPermission -> StatusPill("No access", PillKind.Bad, glyph = Glyph.Bluetooth)
                }
                Spacer(Modifier.width(10.dp))
                IconCircleButton(Glyph.Close, { nav.back() }, contentDescription = "Close", background = hb.colors.sheetTile)
            }
            Spacer(Modifier.height(16.dp))
            BodyPartChips()
            Spacer(Modifier.height(16.dp))
            // ---- stage well
            Box(
                Modifier.fillMaxWidth().height(356.dp).background(hb.colors.sheetTile, RoundedCornerShape(22.dp)).padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                when (stage) {
                    Stage.NoPermission -> StageText(
                        { TapeIllustration(112.dp, active = false, badge = Badge.Alert, pulse = false) },
                        "Allow nearby devices", "Health BB needs Bluetooth access to read your Renpho tape. Nothing else is scanned or stored.",
                    )
                    Stage.Searching -> StageText(
                        { TapeIllustration(148.dp, active = true, badge = null, pulse = true) },
                        "Press the tape's button", "We're looking for your Renpho tape. It wakes up when you press it.",
                    )
                    Stage.Connecting -> StageText(
                        { TapeIllustration(148.dp, active = true, badge = null, pulse = true) },
                        "Connecting…", "Found your tape. This takes a second.",
                    )
                    Stage.Connected -> StageText(
                        { TapeIllustration(148.dp, active = true, badge = Badge.Check, pulse = false) },
                        if (saved != null) "Ready for the next reading" else "Tape connected",
                        "Wrap it at belly-button height, breathe out, then press the button to send.",
                    )
                    Stage.Live -> LiveStage(live ?: 0.0, saved = false, heightCm = heightCm, previous = null)
                    Stage.Saved -> LiveStage(saved?.value ?: 0.0, saved = true, heightCm = heightCm,
                        previous = entries.lastOrNull { it.time != saved?.time && replaced.none { r -> r.time == it.time } })
                    Stage.NotFound -> NotFoundStage(failMessage?.takeIf { timedOut.not() && !it.startsWith("Tape not found") })
                }
            }
            Spacer(Modifier.height(20.dp))
            TodayList(entries, saved, replaced)
            Spacer(Modifier.height(200.dp + shellBottom))
        }

        // ---- actions (+ undo bar) at the bottom
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(hb.colors.sheet)
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = shellBottom + 16.dp),
        ) {
            AnimatedVisibility(undoVisible && saved != null, enter = slideInVertically(tween(200)) { it / 2 } + fadeIn(tween(200)), exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { it / 2 }) {
                UndoBar("Waist ${Fmt.num(saved?.value ?: 0.0, 1)} cm saved", undoKey) { undo() }
            }
            Spacer(Modifier.height(10.dp))
            when (stage) {
                Stage.Saved -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HBButton("Measure again", {
                        // TapeSession is a StateFlow: an identical Ready(value) is never re-emitted, so re-measuring the
                        // same circumference would be lost. Reconnecting resets the state (Searching → Ready(null)), so
                        // the next press counts even when it reads the same value. Limitation: without "Measure again",
                        // pressing the tape with an unchanged value is not seen (TapeSession.kt is not ours to change).
                        waiting = true; undoVisible = false
                        baseline = null
                        session.stop(); startSearch()
                    }, Modifier.weight(1f), kind = ButtonKind.Tonal, glyph = Glyph.Sync)
                    HBButton("Done", { nav.back() }, Modifier.weight(1f))
                }
                Stage.Live -> HBButton("Cancel", { baseline = live; live = null; haptics.reject() }, kind = ButtonKind.Text, fullWidth = true)
                Stage.NotFound -> {
                    HBButton("Try again", { startSearch() }, glyph = Glyph.Sync, fullWidth = true)
                    Spacer(Modifier.height(4.dp))
                    HBButton("Enter manually instead", { manual = true }, kind = ButtonKind.Text, glyph = Glyph.Edit, fullWidth = true)
                }
                Stage.NoPermission -> {
                    HBButton("Allow Bluetooth", { ask.launch(PERMS) }, glyph = Glyph.Bluetooth, fullWidth = true)
                    Spacer(Modifier.height(4.dp))
                    HBButton("Enter manually instead", { manual = true }, kind = ButtonKind.Text, glyph = Glyph.Edit, fullWidth = true)
                }
                else -> HBButton("Enter manually instead", { manual = true }, kind = ButtonKind.Text, glyph = Glyph.Edit, fullWidth = true)
            }
        }
    }

    if (manual) AddWaistSheet(entries.lastOrNull(), onDismiss = { manual = false }, onSave = { cm -> saveWaistWithUndo(cm, edit, snackbar) }, onTape = null)
}

// =====================================================================================================================
// Pieces
// =====================================================================================================================

@Composable
private fun BodyPartChips() {
    val hb = LocalHB.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.height(36.dp).background(hb.colors.text, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphIcon(Glyph.Check, size = 16.dp, color = hb.colors.surface)
            Spacer(Modifier.width(6.dp))
            Text("Waist", style = hb.type.buttonS, color = hb.colors.surface)
        }
        for (part in listOf("Hip", "Chest", "Thigh", "Arm")) {
            Row(
                Modifier.height(36.dp).background(hb.colors.sheetTile, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(part, style = hb.type.buttonS.copy(fontWeight = FontWeight.Medium), color = hb.colors.muted)
                Spacer(Modifier.width(6.dp))
                Text("SOON", style = hb.type.micro.copy(fontSize = 9.sp, letterSpacing = 0.6.sp), color = hb.colors.muted.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
private fun StageText(illustration: @Composable () -> Unit, title: String, body: String) {
    val hb = LocalHB.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        illustration()
        Spacer(Modifier.height(20.dp))
        Text(title, style = hb.type.titleL.copy(fontSize = 20.sp), color = hb.colors.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, style = hb.type.bodyS, color = hb.colors.muted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 290.dp))
    }
}

private enum class Badge { Check, Alert }

/** Tape illustration: housing in waist tint, button, tape strip with ticks; pulse rings while searching; badge. */
@Composable
private fun TapeIllustration(size: Dp, active: Boolean, badge: Badge?, pulse: Boolean) {
    val hb = LocalHB.current
    val c = if (active) Cat.waist else hb.colors.muted
    val inf = rememberInfiniteTransition(label = "tapePulse")
    val p by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "p")
    val badgeScale = remember { Animatable(0.6f) }
    LaunchedEffect(badge) { if (badge != null) { badgeScale.snapTo(0.6f); badgeScale.animateTo(1f, tween(200, easing = EmphasizedDecelerate)) } }
    val good = hb.colors.good
    val bad = hb.colors.bad
    val surface = hb.colors.sheetTile
    val onBadge = hb.colors.surface
    Canvas(Modifier.size(size * 1.25f, size)) {
        val s = this.size.height
        val cx = s * 0.42f; val cy = s / 2f
        val r = s * 0.30f
        // tape strip (behind the housing)
        val stripTop = cy + r * 0.25f; val stripH = s * 0.09f
        drawRoundRect(surface, Offset(cx, stripTop), Size(this.size.width - cx - 2f, stripH), CornerRadius(3f))
        drawRoundRect(c, Offset(cx, stripTop), Size(this.size.width - cx - 2f, stripH), CornerRadius(3f), style = Stroke(1.6.dp.toPx()))
        var x = cx + r + 4.dp.toPx(); var k = 0
        while (x < this.size.width - 6f) {
            drawLine(c, Offset(x, stripTop), Offset(x, stripTop + stripH * (if (k % 5 == 0) 0.7f else 0.4f)), 1.2.dp.toPx())
            x += s * 0.045f; k++
        }
        // pulse rings
        if (pulse && !hb.reducedMotion) {
            for (i in 0..1) {
                val ph = ((p + i * 0.3125f) % 1f)
                val sc = 0.8f + 0.8f * ph
                drawCircle(c.copy(alpha = (1f - ph) * 0.35f), r * sc, Offset(cx, cy), style = Stroke(2.dp.toPx()))
            }
        }
        // housing
        drawCircle(c.copy(alpha = 0.16f), r, Offset(cx, cy))
        drawCircle(c, r, Offset(cx, cy), style = Stroke(2.dp.toPx()))
        drawCircle(c.copy(alpha = 0.35f), r * 0.66f, Offset(cx, cy), style = Stroke(1.5.dp.toPx()))
        drawCircle(c, r * 0.38f, Offset(cx, cy))
        // badge
        if (badge != null) {
            val br = s * 0.10f * badgeScale.value
            val bc = Offset(cx + r * 0.78f, cy - r * 0.78f)
            drawCircle(surface, br + 3.dp.toPx(), bc)
            drawCircle(if (badge == Badge.Check) good else bad, br, bc)
            val g = if (badge == Badge.Check) Glyph.Check else Glyph.Alert
            val gs = br * 1.3f
            translateGlyph(g, onBadge, Offset(bc.x - gs / 2, bc.y - gs / 2), gs)
        }
    }
}

/** Draws a badge glyph at [topLeft] with side [side] (px), in 24-grid units. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.translateGlyph(g: Glyph, color: Color, topLeft: Offset, side: Float) {
    val k = side / 24f
    translate(topLeft.x, topLeft.y) {
        scale(k, k, pivot = Offset.Zero) { drawGlyphUnits(g, color) }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlyphUnits(g: Glyph, color: Color) {
    when (g) {
        Glyph.Check -> {
            drawLine(color, Offset(6f, 12.5f), Offset(10f, 16.5f), 3f, cap = StrokeCap.Round)
            drawLine(color, Offset(10f, 16.5f), Offset(18f, 8f), 3f, cap = StrokeCap.Round)
        }
        else -> {
            drawLine(color, Offset(12f, 6f), Offset(12f, 13.5f), 3.2f, cap = StrokeCap.Round)
            drawCircle(color, 1.9f, Offset(12f, 18f))
        }
    }
}

/** Live / saved stage: status line, big number (count-up), ruler under a fixed needle, WHtR line when saved. */
@Composable
private fun LiveStage(value: Double, saved: Boolean, heightCm: Double?, previous: Sample?) {
    val hb = LocalHB.current
    val anim = remember { Animatable(value.toFloat()) }
    LaunchedEffect(value, saved) {
        if (saved || hb.reducedMotion) anim.snapTo(value.toFloat())
        else anim.animateTo(value.toFloat(), tween(600, easing = EmphasizedDecelerate))
    }
    val shown = anim.value.toDouble()
    val inf = rememberInfiniteTransition(label = "receiving")
    val dotA by inf.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dot")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (saved) StatusPill("Saved to Waist", PillKind.Good, glyph = Glyph.Check)
        else Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).alpha(dotA).background(Cat.waist, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text("Receiving", style = hb.type.label, color = hb.legible(Cat.waist, hb.colors.sheetTile))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontSize = 76.sp, fontWeight = FontWeight.Bold, color = if (saved) hb.colors.text else hb.colors.muted, letterSpacing = (-2).sp)) {
                    append(Fmt.num(Math.round(shown * 10) / 10.0, 1))
                }
                withStyle(SpanStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium, color = hb.colors.muted)) { append(" cm") }
            },
            style = hb.type.display.copy(lineHeight = 84.sp), maxLines = 1,
        )
        Spacer(Modifier.height(8.dp))
        Ruler(shown, dimmed = saved)
        Spacer(Modifier.height(14.dp))
        if (!saved) Text("Keep the tape still for a moment", style = hb.type.bodyS, color = hb.colors.muted)
        else Row(verticalAlignment = Alignment.CenterVertically) {
            if (heightCm != null) {
                Text(buildAnnotatedString {
                    append("Waist-to-height ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = hb.colors.text)) { append(Fmt.num(value / heightCm, 2)) }
                }, style = hb.type.bodyS, color = hb.colors.muted)
            } else Text("Add height to see waist-to-height", style = hb.type.bodyS, color = hb.colors.muted)
            if (previous != null) {
                val ch = value - previous.value
                Spacer(Modifier.width(8.dp))
                DeltaPill(
                    if (Math.round(abs(ch) * 10) == 0L) "Same as ${Fmt.shortDate(Fmt.date(previous.time))}" else "${Fmt.num(abs(ch), 1)} cm vs ${Fmt.shortDate(Fmt.date(previous.time))}",
                    deltaKind(ch, false, 0.2), dir = deltaDir(ch, 0.05), height = 24.dp,
                )
            }
        }
    }
}

/** Ruler sliding under a fixed waist-coloured needle; ticks fade at the edges. */
@Composable
private fun Ruler(value: Double, dimmed: Boolean) {
    val hb = LocalHB.current
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(50.dp).alpha(if (dimmed) 0.55f else 1f)) {
        val cx = size.width / 2f
        val px = 8.dp.toPx() // per 0.1 cm
        val first = floor((value - cx / px / 10.0) * 10).toInt()
        val last = ((value + cx / px / 10.0) * 10).toInt() + 1
        for (t in first..last) {
            val x = cx + ((t / 10.0 - value) * 10 * px).toFloat()
            if (x < 0 || x > size.width) continue
            val fade = (1f - abs(x - cx) / cx).coerceIn(0f, 1f)
            val major = t % 10 == 0
            val half = t % 5 == 0
            val h = when { major -> 20.dp.toPx(); half -> 14.dp.toPx(); else -> 8.dp.toPx() }
            drawLine(hb.colors.muted.copy(alpha = fade * 0.9f), Offset(x, 4.dp.toPx()), Offset(x, 4.dp.toPx() + h), if (major) 1.5.dp.toPx() else 1.dp.toPx())
            if (major) {
                val r = measurer.measure((t / 10).toString(), TextStyle(fontSize = 10.sp, color = hb.colors.muted.copy(alpha = fade), fontFeatureSettings = "tnum"))
                drawText(r, topLeft = Offset(x - r.size.width / 2f, 28.dp.toPx()))
            }
        }
        drawLine(Cat.waist, Offset(cx, 0f), Offset(cx, 30.dp.toPx()), 2.dp.toPx(), cap = StrokeCap.Round)
    }
}

@Composable
private fun NotFoundStage(message: String?) {
    val hb = LocalHB.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        TapeIllustration(112.dp, active = false, badge = Badge.Alert, pulse = false)
        Spacer(Modifier.height(14.dp))
        Text(if (message != null) "Couldn't connect" else "Tape not found", style = hb.type.titleL.copy(fontSize = 20.sp), color = hb.colors.text)
        Spacer(Modifier.height(10.dp))
        if (message != null) Text(message, style = hb.type.bodyS, color = hb.colors.muted, textAlign = TextAlign.Center)
        else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Tip(1, "Close the Renpho app.", " It holds on to the tape.")
            Tip(2, "Press the tape's button", " to wake it.")
            Tip(3, "Keep it close,", " within 1 m of the phone.")
        }
    }
}

@Composable
private fun Tip(n: Int, bold: String, rest: String) {
    val hb = LocalHB.current
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(20.dp).background(hb.colors.sheet, CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", style = hb.type.micro, color = hb.colors.text)
        }
        Spacer(Modifier.width(10.dp))
        Text(buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = hb.colors.text)) { append(bold) }
            append(rest)
        }, style = hb.type.bodyS, color = hb.colors.muted)
    }
}

/** Today's readings + the last reading before today; replaced readings struck through. */
@Composable
private fun TodayList(entries: List<Sample>, saved: Sample?, replaced: List<Sample>) {
    val hb = LocalHB.current
    val today = LocalDate.now()
    val todays = (entries.filter { Fmt.date(it.time) == today } + replaced.filter { r -> entries.none { it.time == r.time } })
        .sortedByDescending { it.time }
    val before = entries.lastOrNull { Fmt.date(it.time).isBefore(today) }
    Text("Today", style = hb.type.label, color = hb.colors.muted)
    Spacer(Modifier.height(6.dp))
    if (todays.isEmpty()) {
        Text("No readings yet", style = hb.type.bodyS, color = hb.colors.muted, modifier = Modifier.padding(vertical = 8.dp))
    }
    todays.forEach { s ->
        val isReplaced = replaced.any { it.time == s.time } && entries.none { it.time == s.time }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Fmt.hm(s.time), style = hb.type.bodyS, color = hb.colors.muted, modifier = Modifier.width(56.dp))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = if (isReplaced) hb.colors.muted else hb.colors.text,
                        textDecoration = if (isReplaced) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)) { append(Fmt.num(s.value, 1)) }
                    withStyle(SpanStyle(fontSize = 12.sp, color = hb.colors.muted)) { append(" cm") }
                },
                style = hb.type.body, modifier = Modifier.weight(1f),
            )
            when {
                isReplaced -> Text("Replaced", style = hb.type.bodyXS, color = hb.colors.muted)
                saved?.time == s.time -> Row(verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(Glyph.Check, size = 14.dp, color = hb.colors.good)
                    Spacer(Modifier.width(4.dp))
                    Text("Saved", style = hb.type.labelS, color = hb.colors.good)
                }
                else -> {}
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(hb.colors.grid))
    }
    if (before != null) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Fmt.shortDate(Fmt.date(before.time)), style = hb.type.bodyS, color = hb.colors.muted, modifier = Modifier.width(56.dp))
            Text("Last reading", style = hb.type.body, color = hb.colors.text, modifier = Modifier.weight(1f))
            Text(buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = hb.colors.text)) { append(Fmt.num(before.value, 1)) }
                withStyle(SpanStyle(fontSize = 12.sp, color = hb.colors.muted)) { append(" cm") }
            }, style = hb.type.body)
        }
    }
}

/** Undo bar anchored above the actions: inverse pill, check, message, "Undo" with a 5 s countdown ring. */
@Composable
private fun UndoBar(message: String, key: Int, onUndo: () -> Unit) {
    val hb = LocalHB.current
    val progress = remember(key) { Animatable(1f) }
    LaunchedEffect(key) { progress.snapTo(1f); progress.animateTo(0f, tween(5_000, easing = LinearEasing)) }
    Row(
        Modifier.fillMaxWidth().height(52.dp).background(hb.colors.inverse, RoundedCornerShape(16.dp)).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(Glyph.Check, size = 18.dp, color = hb.colors.onInverse)
        Spacer(Modifier.width(12.dp))
        Text(message, style = hb.type.bodyS, color = hb.colors.onInverse, modifier = Modifier.weight(1f), maxLines = 1)
        Row(Modifier.plainClickable(onClick = onUndo).padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val accent = hb.colors.inverseAccent
            Canvas(Modifier.size(16.dp)) {
                drawCircle(accent.copy(alpha = 0.25f), size.minDimension / 2 - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
                drawArc(accent, -90f, 360f * progress.value, false, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round),
                    topLeft = Offset(1.dp.toPx(), 1.dp.toPx()), size = Size(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()))
            }
            Spacer(Modifier.width(6.dp))
            Text("Undo", style = hb.type.buttonS.copy(fontWeight = FontWeight.Bold), color = accent)
        }
    }
}

@Suppress("unused")
private fun roundCm(v: Double): Double = (v * 10).roundToInt() / 10.0
