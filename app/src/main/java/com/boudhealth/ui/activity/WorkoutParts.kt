@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.boudhealth.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.data.Workout
import com.boudhealth.ui.components.Fmt
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.GlyphCircle
import com.boudhealth.ui.components.GlyphIcon
import com.boudhealth.ui.components.HBBottomSheet
import com.boudhealth.ui.components.SingleRing
import com.boudhealth.ui.components.hbClickable
import com.boudhealth.ui.theme.Cat
import com.boudhealth.ui.theme.HbShapes
import com.boudhealth.ui.theme.LocalHB

/** Dark check colour on the green workout-goal fills (readable in both themes). */
internal val OnGoalGreen = Color(0xFF14320A)

/** 24 dp green check badge shown next to "4 / 3" when the weekly goal is met. */
@Composable
internal fun GoalCheckBadge(size: Dp = 24.dp) {
    Box(Modifier.size(size).background(Cat.workoutGoal.first, CircleShape), contentAlignment = Alignment.Center) {
        GlyphIcon(Glyph.Check, size = size * 0.62f, color = OnGoalGreen, stroke = 3f)
    }
}

/**
 * Weekly workout-days ring ([Cat.workoutGoal], stroke 0.13·D) with the streak badge in the centre:
 * flame in legible(calories) + streak number + "week streak".
 */
@Composable
internal fun WorkoutGoalRing(progress: Float, streak: Int, size: Dp, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val hb = LocalHB.current
    val m = if (onClick != null) modifier.hbClickable(shape = CircleShape, onClick = onClick) else modifier
    Box(m.size(size), contentAlignment = Alignment.Center) {
        SingleRing(progress, Cat.workoutGoal, size = size, stroke = size * 0.13f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.Flame, size = if (size < 100.dp) 16.dp else 20.dp, color = hb.legible(hb.palette.calories.first, hb.colors.surface))
                Spacer(Modifier.width(3.dp))
                Text(streak.toString(), style = hb.type.numberS.copy(fontSize = if (size < 100.dp) 18.sp else 24.sp), color = hb.colors.text)
            }
            if (size >= 84.dp) Text("Streak", style = hb.type.caption, color = hb.colors.muted, maxLines = 1)
        }
    }
}

/** The last 8 weeks as 14 dp dots (oldest first) + caption. */
@Composable
internal fun StreakDots(weeks: List<WeekMark>, modifier: Modifier = Modifier, caption: String? = "last 8 weeks") {
    val hb = LocalHB.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        weeks.forEach { w ->
            when {
                w.met -> Box(Modifier.size(14.dp).background(Cat.workoutGoal.first, CircleShape), contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.Check, size = 9.dp, color = OnGoalGreen, stroke = 3.4f)
                }
                w.current -> Box(Modifier.size(14.dp).border(2.dp, hb.colors.text, CircleShape))
                else -> Box(Modifier.size(14.dp).border(1.5.dp, hb.colors.outline, CircleShape))
            }
        }
        if (caption != null) {
            Spacer(Modifier.width(9.dp))
            Text(caption, style = hb.type.bodyXS.copy(fontSize = 12.sp), color = hb.colors.muted, maxLines = 1)
        }
    }
}

/** Ring tap: "6 days in a row with a workout. Best: 9 days." */
@Composable
internal fun StreakSheet(week: WorkoutWeek, onDismiss: () -> Unit, streakDays: Int = 0, bestDays: Int = 0) {
    val hb = LocalHB.current
    HBBottomSheet(onDismiss = onDismiss, title = "Streak") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WorkoutGoalRing(week.days.toFloat() / week.goal, streakDays, 84.dp)
            Spacer(Modifier.width(16.dp))
            Text(
                "${plural(streakDays, "day")} · Best ${plural(bestDays, "day")}",
                style = hb.type.body, color = hb.colors.text,
            )
        }
        // v4.1 review: one streak concept in the UI (days); the weekly-goal streak (weeks) is no longer shown
        Spacer(Modifier.height(20.dp))
    }
}

internal fun plural(n: Int, word: String): String = if (n == 1) "1 $word" else "$n ${word}s"

/** Day header above a session group: "Yesterday  Thu 29 Oct ............ 77 min". */
@Composable
internal fun DayGroupHeader(title: String, sub: String?, total: String, modifier: Modifier = Modifier) {
    val hb = LocalHB.current
    Row(modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontSize = 14.sp, fontWeight = hb.type.titleS.fontWeight, color = hb.colors.text)) { append(title) }
                if (sub != null) withStyle(SpanStyle(fontSize = 13.sp, color = hb.colors.muted)) { append("  "); append(sub) }
            },
            style = hb.type.bodyS, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(total, style = hb.type.bodyXS, color = hb.colors.muted)
    }
}

/** A `tile` group (r 20) of session rows separated by 1 dp `surface` cuts inset from 72. */
@Composable
internal fun SessionGroup(sessions: List<Workout>, headerSource: String?, onOpen: (Workout) -> Unit, modifier: Modifier = Modifier) {
    val hb = LocalHB.current
    Column(modifier.fillMaxWidth().background(hb.colors.tile, HbShapes.group)) {
        sessions.forEachIndexed { i, w ->
            SessionRow(w, showSource = false, onClick = { onOpen(w) })   // V2: no source lines / badges
            if (i < sessions.lastIndex) Box(Modifier.padding(start = 72.dp, end = 16.dp).fillMaxWidth().height(1.dp).background(hb.colors.surface))
        }
    }
}

/**
 * Session row (≈ 88): glyph circle 44 · title 16/600 · time range 13 muted (+ source badge when it differs) ·
 * metric line (heart + bpm, zone + zone min) · right: duration 16/600 + kcal 12 muted · chevron.
 */
@Composable
internal fun SessionRow(w: Workout, showSource: Boolean, onClick: () -> Unit) {
    val hb = LocalHB.current
    val color = Cat.workoutColor(w)
    Row(
        Modifier.fillMaxWidth().hbClickable(shape = HbShapes.group, scaleOnPress = false, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphCircle(WorkoutUi.glyph(w), color, size = 44.dp, glyphSize = 22.dp, background = hb.tint(color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(WorkoutUi.name(w), style = hb.type.titleS, color = hb.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(WorkoutUi.timeRange(w), style = hb.type.bodyXS, color = hb.colors.muted, maxLines = 1)
            }
            val hasHr = w.avgHr != null
            val hasZone = (w.zoneMin ?: 0) > 0
            if (hasHr || hasZone) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (hasHr) MetricBit(Glyph.Heart, hb.legibleGlyph(Cat.heart, hb.colors.tile), w.avgHr.toString(), "bpm")
                    if (hasHr && hasZone) Spacer(Modifier.width(14.dp))
                    if (hasZone) MetricBit(Glyph.Zone, hb.legibleGlyph(hb.palette.intensity.first, hb.colors.tile), (w.zoneMin ?: 0).toString(), "pts")
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text("${w.minutes} min", style = hb.type.titleS, color = hb.colors.text, maxLines = 1)
            if (w.kcal != null) Text("${Fmt.num(w.kcal)} kcal", style = hb.type.bodyXS.copy(fontSize = 12.sp), color = hb.colors.muted, maxLines = 1)
        }
        Spacer(Modifier.width(6.dp))
        GlyphIcon(Glyph.ChevR, size = 18.dp, color = hb.colors.muted)
    }
}

@Composable
private fun MetricBit(glyph: Glyph, color: Color, value: String, unit: String) {
    val hb = LocalHB.current
    GlyphIcon(glyph, size = 14.dp, color = color)
    Spacer(Modifier.width(4.dp))
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = hb.type.labelS.fontWeight, color = hb.colors.text)) { append(value) }
            withStyle(SpanStyle(color = hb.colors.muted)) { append(" "); append(unit) }
        },
        style = hb.type.labelS.copy(fontSize = 12.5.sp), maxLines = 1,
    )
}
