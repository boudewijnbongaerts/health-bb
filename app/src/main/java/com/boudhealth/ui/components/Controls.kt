package com.boudhealth.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.data.Range
import com.boudhealth.data.Window
import com.boudhealth.ui.theme.Cat
import com.boudhealth.ui.theme.LocalHB
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

// =====================================================================================================================
// Segmented range control (DESIGN §5.2)
// =====================================================================================================================

enum class SegmentSize(val height: Dp, val pad: Dp) { Regular(44.dp, 4.dp), Small(36.dp, 3.dp) }

/**
 * Pill segmented control with a sliding accent indicator (v3 C2: palette accent / onAccent) (spring stiffness 500, damping 0.8), equal segments.
 * Generic over the option type. Haptic segment tick on change.
 *
 *     SegmentedRange(listOf(Range.WEEK, Range.MONTH), range, { range = it }, label = { it.label })
 */
@Composable
fun <T> SegmentedRange(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: (T) -> String = { it.toString() },
    size: SegmentSize = SegmentSize.Regular,
    track: Color = LocalHB.current.colors.tile,
) {
    if (options.isEmpty()) return
    val hb = LocalHB.current
    val haptics = rememberHaptics()
    val index = options.indexOf(selected).coerceAtLeast(0)
    BoxWithConstraints(
        modifier.fillMaxWidth().height(size.height).background(track, RoundedCornerShape(size.height / 2)).padding(size.pad)
    ) {
        val segW = maxWidth / options.size
        val x by animateDpAsState(
            segW * index,
            spring(dampingRatio = 0.8f, stiffness = 500f),
            label = "segment",
        )
        Box(
            Modifier.offset(x = x).width(segW).fillMaxHeight()
                .background(hb.colors.accent, RoundedCornerShape((size.height - size.pad * 2) / 2))
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { i, o ->
                val sel = i == index
                val col by animateColorAsState(if (sel) hb.colors.onAccent else hb.colors.muted, tween(120), label = "segLabel")
                Box(
                    Modifier.weight(1f).fillMaxHeight().plainClickable {
                        if (!sel) { haptics.segment(); onSelect(o) }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    val st = if (size == SegmentSize.Small) hb.type.label else hb.type.buttonS
                    Text(label(o), style = if (sel) st else st.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = col, maxLines = 1)
                }
            }
        }
    }
}

// =====================================================================================================================
// Period navigator (DESIGN §5.3) + calendar-aligned periods
// =====================================================================================================================

/** ‹ label / sub › row. › disabled (38 %) on the current period. */
@Composable
fun PeriodNavigator(
    label: String,
    sub: String?,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    prevEnabled: Boolean = true,
    nextEnabled: Boolean = true,
) {
    val hb = LocalHB.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconCircleButton(Glyph.ChevL, onPrev, size = 36.dp, glyphSize = 20.dp, enabled = prevEnabled, contentDescription = "Previous period")
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = hb.type.titleS, color = hb.colors.text, textAlign = TextAlign.Center, maxLines = 1)
            if (sub != null) Text(sub, style = hb.type.bodyXS.copy(fontSize = hb.type.labelS.fontSize), color = hb.colors.muted, maxLines = 1)
        }
        IconCircleButton(Glyph.ChevR, onNext, size = 36.dp, glyphSize = 20.dp, enabled = nextEnabled, contentDescription = "Next period")
    }
}

/**
 * Calendar-aligned periods for the ‹ › navigator (DESIGN §5.3 labels):
 * 1D = a day, 1W = Mon–Sun week, 1M = calendar month, 3M = 3 calendar months ending with the current one,
 * 1Y = the last 12 months (this month included), All = everything. [offset] 0 = current, 1 = previous, …
 * The result is a normal [Window] (end exclusive, may reach into the future) for Metrics.series / History.
 */
object Periods {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun window(range: Range, offset: Int, today: LocalDate = LocalDate.now(zone)): Window = when (range) {
        Range.DAY -> { val d = today.minusDays(offset.toLong()); Window(range, offset, d, d.plusDays(1)) }
        Range.WEEK -> {
            val s = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(offset.toLong())
            Window(range, offset, s, s.plusWeeks(1))
        }
        Range.MONTH -> { val s = today.withDayOfMonth(1).minusMonths(offset.toLong()); Window(range, offset, s, s.plusMonths(1)) }
        Range.QUARTER -> {
            val e = today.withDayOfMonth(1).plusMonths(1).minusMonths(3L * offset)
            Window(range, offset, e.minusMonths(3), e)
        }
        // v3.1: 1Y = the last 12 months (this month included) everywhere, not the calendar year
        Range.YEAR -> {
            val e = today.withDayOfMonth(1).plusMonths(1).minusYears(offset.toLong())
            Window(range, offset, e.minusYears(1), e)
        }
        Range.ALL -> Window.of(Range.ALL, 0)
    }

    /** (label, sub) e.g. ("This week", "26 Oct – 1 Nov"), ("Today", "Fri 30 Oct"), ("October", "2026"). */
    fun label(w: Window, today: LocalDate = LocalDate.now(zone)): Pair<String, String?> {
        val loc = Locale.getDefault()
        val last = w.end.minusDays(1)
        return when (w.range) {
            Range.DAY -> Fmt.dayLabel(w.start, today) to (if (w.offset <= 1) DateTimeFormatter.ofPattern("EEE d MMM", loc).format(w.start) else null)
            Range.WEEK -> (when (w.offset) { 0 -> "This week"; 1 -> "Last week"; else -> Fmt.range(w.start, last) }) to Fmt.range(w.start, last)
            Range.MONTH -> DateTimeFormatter.ofPattern("MMMM", loc).format(w.start) to w.start.year.toString()
            Range.QUARTER -> "${DateTimeFormatter.ofPattern("MMM", loc).format(w.start)} – ${DateTimeFormatter.ofPattern("MMM", loc).format(last)}" to
                (if (w.start.year == last.year) last.year.toString() else "${w.start.year}–${last.year}")
            Range.YEAR -> {
                val my = DateTimeFormatter.ofPattern("MMM yyyy", loc)
                (if (w.offset == 0) "Last 12 months" else "12 months to ${my.format(last)}") to "${my.format(w.start)} – ${my.format(last)}"
            }
            Range.ALL -> "All time" to null
        }
    }

    /** Is there a newer period? (› enabled) */
    fun hasNext(w: Window): Boolean = w.range != Range.ALL && w.offset > 0
}

// =====================================================================================================================
// Delta pill (DESIGN §5.4)
// =====================================================================================================================

enum class DeltaKind { Good, Bad, Neutral }
enum class DeltaDir { Up, Down, None }

/**
 * h 26, r 13, chevron 14 (stroke 2.6) + 12.5/600 text. Text names the comparison ("2 bpm vs last week").
 */
@Composable
fun DeltaPill(text: String, kind: DeltaKind, modifier: Modifier = Modifier, dir: DeltaDir = DeltaDir.None, height: Dp = 26.dp) {
    val hb = LocalHB.current
    val (bg, fg) = when (kind) {
        DeltaKind.Good -> hb.colors.goodCont to hb.colors.good
        DeltaKind.Bad -> hb.colors.badCont to hb.colors.bad
        DeltaKind.Neutral -> hb.colors.tile to hb.colors.muted
    }
    Row(
        modifier.height(height).background(bg, RoundedCornerShape(height / 2)).padding(start = if (dir == DeltaDir.None) 10.dp else 8.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dir != DeltaDir.None) {
            GlyphIcon(if (dir == DeltaDir.Up) Glyph.ChevU else Glyph.ChevD, size = 14.dp, color = fg, stroke = 2.6f)
            Spacer(Modifier.width(4.dp))
        }
        // v5: text centred on the pill (no font padding), so pills line up with icons and neighbours
        Text(text, style = hb.type.labelS.copy(fontSize = 12.5.sp, lineHeight = 12.5.sp,
            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.Both)),
            color = fg, maxLines = 1)
    }
}

/**
 * Good / bad / neutral for a change: depends on the metric's polarity ([higherIsBetter], null = neutral always),
 * neutral when |change| < [noise].
 */
fun deltaKind(change: Double, higherIsBetter: Boolean?, noise: Double = 0.0): DeltaKind = when {
    higherIsBetter == null || kotlin.math.abs(change) <= noise -> DeltaKind.Neutral
    (change > 0) == higherIsBetter -> DeltaKind.Good
    else -> DeltaKind.Bad
}

fun deltaDir(change: Double, noise: Double = 0.0): DeltaDir = when {
    kotlin.math.abs(change) <= noise -> DeltaDir.None
    change > 0 -> DeltaDir.Up
    else -> DeltaDir.Down
}

// =====================================================================================================================
// Source chip (DESIGN §5.14)
// =====================================================================================================================

/** Generic badges, not brand logos. */
sealed class SourceBadge {
    data object Withings : SourceBadge()
    data object Google : SourceBadge()
    data object Renpho : SourceBadge()
    data object Manual : SourceBadge()
    data class Letter(val letter: String) : SourceBadge()

    companion object {
        /** From a package name or label ("com.withings.wiscale2", "Google Health", "Renpho", "manual", …). */
        fun of(source: String?): SourceBadge {
            val s = source?.lowercase() ?: return Manual
            return when {
                "withings" in s -> Withings
                "google" in s || "fitbit" in s || "healthdata" in s -> Google
                "renpho" in s || "tape" in s -> Renpho
                "manual" in s || s.isBlank() -> Manual
                else -> Letter(source.trim().take(1).uppercase())
            }
        }
    }
}

/** h 24, r 12, `tile`, badge circle 16 + label 12/600. Tappable (opens source details) when [onClick] is set. */
@Composable
fun SourceChip(label: String, badge: SourceBadge = SourceBadge.of(label), modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val hb = LocalHB.current
    val shape = RoundedCornerShape(12.dp)
    val m = if (onClick != null) modifier.hbClickable(shape = shape, scaleOnPress = false, onClick = onClick) else modifier
    Row(
        m.height(24.dp).background(hb.colors.tile, shape).padding(start = 4.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SourceBadgeCircle(badge)
        Spacer(Modifier.width(6.dp))
        Text(label, style = hb.type.labelS, color = hb.colors.text, maxLines = 1)
    }
}

@Composable
fun SourceBadgeCircle(badge: SourceBadge, size: Dp = 16.dp) {
    val hb = LocalHB.current
    val (bg, fg) = when (badge) {
        SourceBadge.Withings -> hb.colors.text to hb.colors.surface
        // v3.1: dark ink on the bright fills (white was < 4.5 : 1 on both)
        SourceBadge.Google -> Cat.googleBlue to Cat.onBright
        SourceBadge.Renpho -> Cat.waist to Cat.onBright
        SourceBadge.Manual -> hb.colors.muted to hb.colors.surface
        is SourceBadge.Letter -> hb.colors.muted to hb.colors.surface
    }
    Box(Modifier.size(size).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        when (badge) {
            SourceBadge.Withings -> Text("W", style = hb.type.micro, color = fg)
            SourceBadge.Google -> Text("G", style = hb.type.micro, color = fg)
            SourceBadge.Renpho -> GlyphIcon(Glyph.Tape, size = size * 0.7f, color = fg, stroke = 2.6f)
            SourceBadge.Manual -> GlyphIcon(Glyph.Edit, size = size * 0.7f, color = fg, stroke = 2.6f)
            is SourceBadge.Letter -> Text(badge.letter, style = hb.type.micro, color = fg)
        }
    }
}

// =====================================================================================================================
// Filter pill (catalog chips etc.): selected = accent pill; else tile. Optional leading dot.
// =====================================================================================================================

@Composable
fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, dot: Color? = null) {
    val hb = LocalHB.current
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier.height(36.dp).hbClickable(shape = shape, scaleOnPress = false, onClick = onClick)
            .background(if (selected) hb.colors.accent else hb.colors.tile, shape).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) { Dot(dot); Spacer(Modifier.width(6.dp)) }
        Text(text, style = hb.type.buttonS, color = if (selected) hb.colors.onAccent else hb.colors.text, maxLines = 1)
    }
}

/** Spacer helper: vertical gap. */
@Composable
fun VGap(h: Dp) = Spacer(Modifier.height(h))

/** Spacer helper: horizontal gap. */
@Composable
fun HGap(w: Dp) = Spacer(Modifier.width(w))

/** Horizontal arrangement helper exposed for consistency. */
val Gap8 = Arrangement.spacedBy(8.dp)
