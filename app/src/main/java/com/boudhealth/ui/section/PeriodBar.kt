package com.boudhealth.ui.section

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.data.Insights
import com.boudhealth.data.PeriodUnit
import com.boudhealth.data.SectionPeriod
import com.boudhealth.data.SectionScope
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.GlyphIcon
import com.boudhealth.ui.components.IconCircleButton
import com.boudhealth.ui.components.plainClickable
import com.boudhealth.ui.components.rememberHaptics
import com.boudhealth.ui.theme.LocalHB
import java.time.LocalDate
import kotlin.math.abs

/** Period label (V2_PLAN §2.2): "Sat 31 Oct", "26 Oct – 1 Nov", "October 2026", "2026", "All time". */
fun periodLabel(p: SectionPeriod): String = when (p) {
    is SectionPeriod.Day -> Insights.dayLabel(p.start)
    is SectionPeriod.Week -> Insights.weekLabel(p)
    is SectionPeriod.Month -> SFmt.monthYear(p.year, p.month)
    is SectionPeriod.Year -> p.year.toString()
    is SectionPeriod.All -> "All time"
}

/** ‹ / › enabled? With a selected day the day moves; else the period (clamped to [dataStart, today]). */
fun canMove(scope: SectionScope, n: Int, dataStart: LocalDate, today: LocalDate): Boolean = scope.shift(n, dataStart, today) != scope

/**
 * The sticky period bar. Inner screen: "‹ label › [day ×]" left, unit chips right. Cover screen: unit chips on top
 * (full width), "‹ label › [day ×]" under them (approved design6 cover_* sheets).
 * Label tap → the current period of the unit. Swipe: [Modifier.periodSwipe].
 */
@Composable
fun PeriodBar(
    scope: SectionScope,
    dataStart: LocalDate,
    today: LocalDate,
    onScope: (SectionScope) -> Unit,
    wide: Boolean,
    modifier: Modifier = Modifier,
    units: List<PeriodUnit> = PeriodUnit.entries.toList(),
    /** Extra unit chips on detail pages are not supported (periods are calendar periods only, D8). */
) {
    val haptics = rememberHaptics()
    val nav: @Composable () -> Unit = {
        PeriodNav(scope, dataStart, today, onScope = { haptics.segment(); onScope(it) })
    }
    val chips: @Composable (Modifier) -> Unit = { m ->
        UnitChips(units.map { it.label }, units.indexOf(scope.unit), m) { i ->
            val u = units[i]
            if (u == scope.unit && scope.selectedDay == null && scope.period.isCurrent(today)) return@UnitChips
            haptics.segment()
            onScope(if (u == scope.unit) SectionScope(SectionPeriod.current(u, today, dataStart), null, true) else scope.withUnit(u, dataStart, today))
        }
    }
    if (wide) {
        Row(modifier.fillMaxWidth().periodSwipe(scope, dataStart, today, onScope), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { nav() }
            Spacer(Modifier.width(12.dp))
            chips(Modifier.width(300.dp))
        }
    } else {
        Column(modifier.fillMaxWidth().periodSwipe(scope, dataStart, today, onScope)) {
            chips(Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            nav()
        }
    }
}

@Composable
private fun PeriodNav(scope: SectionScope, dataStart: LocalDate, today: LocalDate, onScope: (SectionScope) -> Unit) {
    val hb = LocalHB.current
    val back = canMove(scope, -1, dataStart, today)
    val fwd = canMove(scope, 1, dataStart, today)
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconCircleButton(Glyph.ChevL, { onScope(scope.shift(-1, dataStart, today)) }, contentDescription = "Previous", size = 36.dp, glyphSize = 18.dp,
            enabled = back, tint = if (back) hb.colors.text else hb.colors.muted.copy(alpha = 0.4f))
        Text(
            periodLabel(scope.period), style = hb.type.titleS.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = hb.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp).plainClickable {
                // label tap → current period of this unit
                onScope(SectionScope(SectionPeriod.current(scope.unit, today, dataStart), null, true))
            }.semantics { contentDescription = "Period ${periodLabel(scope.period)}, tap for the current one" },
        )
        IconCircleButton(Glyph.ChevR, { onScope(scope.shift(1, dataStart, today)) }, contentDescription = "Next", size = 36.dp, glyphSize = 18.dp,
            enabled = fwd, tint = if (fwd) hb.colors.text else hb.colors.muted.copy(alpha = 0.4f))
        scope.selectedDay?.let { d ->
            Spacer(Modifier.width(10.dp))
            SelectedDayChip(d) { onScope(scope.clearSelection(today)) }
        }
    }
}

/** "Thu 22 Oct ×" (chip_selected_day). */
@Composable
fun SelectedDayChip(day: LocalDate, onClear: () -> Unit) {
    val hb = LocalHB.current
    Row(
        Modifier.height(34.dp).background(hb.colors.accentCont, RoundedCornerShape(17.dp)).plainClickable(onClick = onClear)
            .padding(start = 14.dp, end = 10.dp).semantics { contentDescription = "Selected ${Insights.dayLabel(day)}, tap to clear" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(Insights.dayLabel(day), style = hb.type.label, color = hb.colors.onAccentCont, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        GlyphIcon(Glyph.Close, size = 14.dp, color = hb.colors.onAccentCont)
    }
}

@Composable
internal fun UnitChips(labels: List<String>, selected: Int, modifier: Modifier, onSelect: (Int) -> Unit) {
    val hb = LocalHB.current
    // design6 kit6.seg: 300 × 40 (cover full width × 38), selected pill inset 4, labels 13.5 bold
    Row(modifier.height(40.dp).background(hb.colors.tile, RoundedCornerShape(20.dp)).padding(4.dp)) {
        labels.forEachIndexed { i, label ->
            val sel = i == selected
            val bg by animateColorAsState(if (sel) hb.colors.accent else Color.Transparent, tween(160), label = "unit")
            Box(
                Modifier.weight(1f).height(32.dp).background(bg, RoundedCornerShape(16.dp))
                    .semantics { role = Role.Tab; this.selected = sel; contentDescription = label }
                    .plainClickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontSize = 13.5.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = if (sel) hb.colors.onAccent else hb.colors.muted, maxLines = 1)
            }
        }
    }
}

/** Horizontal swipe → previous / next period (or day when one is selected). Use on the period bar and hero only. */
fun Modifier.periodSwipe(scope: SectionScope, dataStart: LocalDate, today: LocalDate, onScope: (SectionScope) -> Unit): Modifier =
    this.pointerInput(Triple(scope, dataStart, today)) {
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                val threshold = 56.dp.toPx()
                if (abs(total) > threshold) {
                    val n = if (total < 0) 1 else -1
                    val next = scope.shift(n, dataStart, today)
                    if (next != scope) onScope(next)
                }
            },
            onHorizontalDrag = { change, dx -> total += dx; change.consume() },
        )
    }
