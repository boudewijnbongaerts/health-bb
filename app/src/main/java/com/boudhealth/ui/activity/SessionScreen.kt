package com.boudhealth.ui.activity

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.data.ExerciseTypes
import com.boudhealth.data.Sync
import com.boudhealth.ui.components.CollapsedHeaderBar
import com.boudhealth.ui.components.Dot
import com.boudhealth.ui.components.EmptyState
import com.boudhealth.ui.components.Fmt
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.GlyphCircle
import com.boudhealth.ui.components.GlyphIcon
import com.boudhealth.ui.components.HeaderRow
import com.boudhealth.ui.components.LocalScreen
import com.boudhealth.ui.components.MetricSkeleton
import com.boudhealth.ui.components.SectionHeader
import com.boudhealth.ui.components.SkeletonBox
import com.boudhealth.ui.components.ValueText
import com.boudhealth.ui.components.animatedNumber
import com.boudhealth.ui.components.hbClickable
import com.boudhealth.ui.components.screenContent
import com.boudhealth.ui.components.shellBottom
import com.boudhealth.ui.nav.Nav
import com.boudhealth.ui.nav.Route
import com.boudhealth.ui.theme.Cat
import com.boudhealth.ui.theme.HbShapes
import com.boudhealth.ui.theme.LocalHB
import kotlin.math.roundToInt

/**
 * Session detail (C_SPEC §2). [start] = Workout.start epoch ms.
 * Identity → hero (duration) → 2×2 metric tiles → heart-rate chart with zone bands → time in zones → details.
 */
@Composable
fun SessionScreen(start: Long, nav: Nav) {
    val hb = LocalHB.current
    val ctx = LocalContext.current
    val screen = LocalScreen.current
    val density = LocalDensity.current
    val version by Sync.version.collectAsState()
    var data by remember { mutableStateOf<SessionData?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(start, version) {
        // C2: a failed load shows "Session not found" instead of crashing the app
        data = runCatching { loadSession(ctx, start) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; com.boudhealth.data.ErrorLog.record("session", it) }
            .getOrNull()
        loaded = true
    }
    val scroll = rememberScrollState()
    com.boudhealth.ui.debug.RegisterTourScroll(scroll)   // V2-155 debug tour
    val collapsed by remember(density) { derivedStateOf { with(density) { scroll.value > 120.dp.toPx() && !com.boudhealth.ui.debug.TourScroll.active } } }
    val d = data

    Box(Modifier.fillMaxSize().background(hb.colors.bgDim)) {   // R5 #34: bgDim page + card groups, as every detail page
        Column(Modifier.fillMaxSize().verticalScroll(scroll), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.screenContent(screen.gutter)) {
                Spacer(Modifier.height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp))
                HeaderRow(onBack = { nav.back() }, actions = emptyList())
                Spacer(Modifier.height(16.dp))
                when {
                    d != null -> SessionContent(d, nav)
                    loaded -> EmptyState(Glyph.Dumbbell, Cat.strength, title = "Session not found", body = "")
                    else -> { SkeletonBox(Modifier.fillMaxWidth(), height = 56.dp, radius = 28.dp); Spacer(Modifier.height(24.dp)); MetricSkeleton() }
                }
                Spacer(Modifier.height(shellBottom + 24.dp))
            }
        }
        AnimatedVisibility(collapsed, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
            CollapsedHeaderBar(d?.let { WorkoutUi.name(it.workout) } ?: "Workout", { nav.back() }, emptyList(), background = hb.colors.bgDim)
        }
    }
}

@Composable
private fun SessionContent(d: SessionData, nav: Nav) {
    val hb = LocalHB.current
    val w = d.workout
    val color = Cat.workoutColor(w)

    // ---- identity
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlyphCircle(WorkoutUi.glyph(w), color, size = 56.dp, glyphSize = 28.dp, background = hb.tint(color))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(ExerciseTypes.typeName(w), style = hb.type.label, color = hb.legible(color, hb.colors.surface), maxLines = 1)
            Text(WorkoutUi.name(w), style = hb.type.headline.copy(fontSize = 28.sp, lineHeight = 34.sp), color = hb.colors.text,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        val day = WorkoutUi.dayHeader(WorkoutUi.day(w)).first
        Text("$day · ${WorkoutUi.timeRange(w)}", style = hb.type.bodyS.copy(fontSize = 15.sp), color = hb.colors.muted, maxLines = 1)
    }

    // ---- hero: duration
    Spacer(Modifier.height(28.dp))
    val shown = animatedNumber(w.minutes.toDouble(), w.start) ?: 0.0
    ValueText(shown.roundToInt().toString(), "min", style = hb.type.display, unitStyle = hb.type.displayUnit)
    val top = (1..3).maxByOrNull { d.zoneMinutes[it] }?.takeIf { d.zoneMinutes[it] > 0 }
    val totalHrMin = d.zoneMinutes.sum()
    Text(
        when {
            d.hr.isEmpty() -> "No heart rate recorded"
            top == null -> "Mostly below the zones · ${d.zoneMinutes[0]} of $totalHrMin min"
            else -> "Mostly in ${ZoneNames[top].lowercase()} zone · ${d.zoneMinutes[top]} of $totalHrMin min"
        },
        style = hb.type.bodyS.copy(fontSize = 15.sp), color = hb.colors.muted,
    )

    // ---- 2×2 metric tiles
    Spacer(Modifier.height(20.dp))
    // v3 D11: the per-zone breakdown (×1 / ×2) is in "Time in zones" below, not repeated on the tile
    val zoneSub: String? = null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("Calories burned", LocalHB.current.energyActive, w.kcal?.let { Fmt.num(it) } ?: "–", "kcal", null,
                Modifier.weight(1f), onClick = { nav.openMetric("calories") })
            MetricTile("Heart points", hb.palette.intensity.first, d.zonePoints.toString(), "pts", zoneSub,
                Modifier.weight(1f), onClick = { nav.openMetric("zone_min") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("Avg HR", Cat.heart, d.avgHr?.toString() ?: "–", "bpm", null, Modifier.weight(1f))
            MetricTile("Max heart rate", Cat.heart, d.max?.bpm?.toString() ?: "–", "bpm", d.max?.let { "at ${Fmt.hm(it.ms)}" }, Modifier.weight(1f))
        }
    }

    // ---- heart rate
    SectionHeader("Heart rate")   // zone limits: see "Heart-rate zones" in Details
    if (d.hr.isEmpty()) {
        EmptyState(Glyph.Heart, Cat.heart, title = "No heart rate for this session")
    } else {
        HrZoneChart(d.hr, d.limits, d.avgHr, w.start, w.end)

        // ---- time in zones
        SectionHeader("Time in zones", trailing = {
            Text("$totalHrMin min", style = hb.type.bodyS, color = hb.colors.muted)
        })
        ZoneBar(d.zoneMinutes)
        Spacer(Modifier.height(12.dp))
        ZoneList(d)
    }

    // ---- details
    Text("Details", style = hb.type.label, color = hb.colors.muted, modifier = Modifier.padding(top = 28.dp, bottom = 8.dp, start = 4.dp))
    Column(Modifier.fillMaxWidth().background(hb.colors.card, HbShapes.group)) {
        DetailRow("Activity type", null, WorkoutUi.name(w), onClick = null)
        Cut()
        DetailRow("Heart-rate zones", "Max ${d.limits.hrMax} · Rest ${d.limits.rest.roundToInt()} bpm", null,
            onClick = { nav.push(Route.Settings) })
    }
}

@Composable
private fun Cut() {
    val hb = LocalHB.current
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(hb.colors.grid))
}

@Composable
private fun MetricTile(label: String, dot: Color, value: String, unit: String, sub: String?, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val hb = LocalHB.current
    val m = if (onClick != null) modifier.hbClickable(shape = HbShapes.tile, onClick = onClick) else modifier
    Column(m.background(hb.colors.card, HbShapes.tile).heightIn(min = 96.dp).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(dot)
            Spacer(Modifier.width(8.dp))
            Text(label, style = hb.type.bodyXS.copy(fontSize = 12.5.sp), color = hb.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(4.dp))
        ValueText(value, unit, style = hb.type.number, unitStyle = hb.type.unit)
        if (sub != null) Text(sub, style = hb.type.bodyXS.copy(fontSize = 12.sp), color = hb.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 16 dp pill: segments proportional to minutes (below → peak), 2 dp gaps, zero segments omitted. */
@Composable
private fun ZoneBar(minutes: IntArray) {
    val total = minutes.sum().coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(16.dp).clip(HbShapes.pill), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (z in 0..3) {
            if (minutes[z] > 0) Box(Modifier.weight(minutes[z].toFloat() / total).height(16.dp).background(ZoneColors[z]))
        }
    }
}

/** One row per zone, peak first: dot · name + range · multiplier chip · minutes. Zero-minute zones at 40 %. */
@Composable
private fun ZoneList(d: SessionData) {
    val hb = LocalHB.current
    val l = d.limits
    val ranges = listOf("under ${l.fat} bpm", "${l.fat}–${l.cardio - 1} bpm", "${l.cardio}–${l.peak - 1} bpm", "≥ ${l.peak} bpm")
    Column(Modifier.fillMaxWidth().background(hb.colors.card, HbShapes.group)) {
        for ((i, z) in listOf(3, 2, 1, 0).withIndex()) {
            val mins = d.zoneMinutes[z]
            Row(
                Modifier.fillMaxWidth().alpha(if (mins == 0) 0.4f else 1f).padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(ZoneColors[z], size = 10.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(ZoneNames[z], style = hb.type.bodyMedium, color = hb.colors.text, maxLines = 1)
                    Text(ranges[z], style = hb.type.bodyXS.copy(fontSize = 12.5.sp), color = hb.colors.muted, maxLines = 1)
                }
                if (z > 0 && mins > 0) {
                    val mult = if (z == 1) "×1 pts" else "×2 pts"
                    Text(
                        mult, style = hb.type.labelS, color = hb.legible(hb.palette.intensity.first, hb.colors.card),
                        modifier = Modifier.background(hb.tint(hb.palette.intensity.first), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                }
                ValueText(mins.toString(), "min", style = hb.type.titleS, unitStyle = hb.type.unit,
                    color = if (mins == 0) hb.colors.muted else hb.colors.text)
            }
            if (i < 3) Cut()
        }
    }
}

@Composable
private fun DetailRow(title: String, sub: String?, value: String?, onClick: (() -> Unit)?) {
    val hb = LocalHB.current
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.hbClickable(shape = HbShapes.group, scaleOnPress = false, onClick = onClick) else Modifier)
            .heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = hb.type.titleS, color = hb.colors.text, maxLines = 1)
            if (sub != null) Text(sub, style = hb.type.bodyXS, color = hb.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            Text(value, style = hb.type.body, color = hb.colors.muted, maxLines = 1)
        }
        if (onClick != null) {
            Spacer(Modifier.width(8.dp))
            GlyphIcon(Glyph.ChevR, size = 18.dp, color = hb.colors.muted)
        }
    }
}
