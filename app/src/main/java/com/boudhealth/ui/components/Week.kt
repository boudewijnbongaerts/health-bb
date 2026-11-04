package com.boudhealth.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.ui.theme.LocalHB
import kotlin.math.roundToInt

/*
 * v3 shared pieces (UI_PLAN C1 / D10):
 *  - WeekZoneLine: "Week 96 / 150 zone min · 64 %" + WeekBar. Used under the rings only (Today tile L/XL, Activity hero,
 *    Zone minutes detail): the weekly goal is a bar, never a ring.
 */

/**
 * "Week 96 / 150 zone min" (Week 600 in the zone label colour, number 700 text, rest muted), optional "64 %" right,
 * then the [WeekBar]. [short] = "Week 96 / 150 min" (L tiles).
 */
@Composable
fun WeekZoneLine(
    week: Double,
    goal: Int,
    modifier: Modifier = Modifier,
    short: Boolean = false,
    showPct: Boolean = true,
    barHeight: Dp = 6.dp,
    fontSize: TextUnit = 12.5.sp,
    gap: Dp = 6.dp,
    animate: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val hb = LocalHB.current
    val p = if (goal <= 0) 0f else (week / goal).toFloat().coerceAtLeast(0f)
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = hb.ringLabel(1), fontWeight = FontWeight.SemiBold)) { append("Week ") }
                    withStyle(SpanStyle(color = hb.colors.text, fontWeight = FontWeight.Bold)) { append(Fmt.num(week)) }
                    append(" / ${Fmt.int(goal)} ${if (short) "min" else "zone min"}")
                },
                fontSize = fontSize, color = hb.colors.muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                modifier = Modifier.weight(1f),
            )
            if (showPct) Text("${(p * 100).roundToInt()} %", fontSize = fontSize, color = hb.colors.muted, maxLines = 1)
            if (trailing != null) { Spacer(Modifier.width(6.dp)); trailing() }
        }
        Spacer(Modifier.height(gap))
        WeekBar(p, height = barHeight, animate = animate)
    }
}

private fun ordinal(n: Int): String = n.toString() + when {
    n % 100 in 11..13 -> "th"
    n % 10 == 1 -> "st"
    n % 10 == 2 -> "nd"
    n % 10 == 3 -> "rd"
    else -> "th"
}

/**
 * The one factual line under the rings (C4 rules, v4 rings; first rule that applies, never advice).
 * [values] / [goals] in ring index order (TodayRingSet: workout min today, intensity this week, steps today).
 * no data → "No activity synced yet today"; all closed → "All three rings closed · 3rd day this week";
 * else the two biggest remaining gaps → "12 workout min and 3,150 steps to go" (intensity says "… this week").
 */
fun ringsInsight(values: List<Double>, goals: List<Int>, closedThisWeek: Int): String {
    if (values.all { it <= 0.0 }) return "No activity synced yet today"
    val p = values.indices.map { if (goals[it] <= 0) 0.0 else values[it] / goals[it] }
    if (p.all { it >= 1.0 }) return "All three rings closed · ${ordinal(closedThisWeek.coerceAtLeast(1))} day this week"
    val units = listOf("min", "%", "steps")
    val open = p.indices.filter { p[it] < 1.0 }.sortedByDescending { 1.0 - p[it] }.take(2).sorted()
    return open.joinToString(" and ") { i -> "${Fmt.num((goals[i] - values[i]).coerceAtLeast(0.0))} ${units[i]}" } + " to go"
}
