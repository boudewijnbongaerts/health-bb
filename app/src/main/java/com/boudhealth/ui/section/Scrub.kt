package com.boudhealth.ui.section

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.boudhealth.data.SeriesBucket
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs

// =====================================================================================================================
// R3 scrub + selection (IMPL_PLAN §2.4). Tap a bucket = select / drill (ctx.drill(bucket.day)); press and hold, then
// slide = scrub: [ScrubState.day] (or [ScrubState.hour] on hourly buckets) follows the finger and every chart / value
// block that shares the state mirrors that point (Body hero: weight + composition; Vitals hero: 4 lanes + labels).
// The value block's pill fades to 0 while scrubbing ([ScrubState.pillAlpha]). Releasing clears the state.
// =====================================================================================================================

@Stable
class ScrubState {
    var day: LocalDate? by mutableStateOf(null)
    var hour: LocalDateTime? by mutableStateOf(null)
    /** Index of the bucket under the finger in the chart that owns the gesture (the one that shows the read-out). */
    var ownerKey: Any? by mutableStateOf(null)

    val active: Boolean get() = day != null || hour != null
    /** Opacity for the delta pill: 0 while scrubbing. */
    val pillAlpha: Float get() = if (active) 0f else 1f

    fun clear() { day = null; hour = null; ownerKey = null }

    /** Sets the state from a bucket (hourly buckets set [hour] too). */
    fun set(b: SeriesBucket, owner: Any? = null) {
        val hourly = java.time.Duration.between(b.start, b.end).toMinutes() <= 60
        day = b.day; hour = if (hourly) b.start else null; ownerKey = owner
    }

    /** True when [b] is the scrubbed bucket. */
    fun matches(b: SeriesBucket): Boolean = hour?.let { it == b.start } ?: (day != null && b.day == day && java.time.Duration.between(b.start, b.end).toDays() <= 1)
}

/** One scrub state per [key] (e.g. the scope), shared by the charts of a hero. */
@Composable
fun rememberScrub(key: Any): ScrubState = remember(key) { ScrubState() }

/**
 * Tap → [onTap] (future buckets ignored); press and hold, then slide → [state] follows the bucket under the finger
 * (snapped to the nearest bucket with a value when [snap]), cleared on release. Buckets are spread evenly over the
 * width minus [gutter] (the charts' right y-axis gutter: 28–30 dp).
 */
fun Modifier.scrubAndTap(buckets: List<SeriesBucket>, state: ScrubState, onTap: (SeriesBucket) -> Unit): Modifier =
    scrubAndTap(buckets, state, onTap, 0.dp, true)

fun Modifier.scrubAndTap(buckets: List<SeriesBucket>, state: ScrubState, onTap: (SeriesBucket) -> Unit, gutter: Dp, snap: Boolean): Modifier = composed {
    val haptics = com.boudhealth.ui.components.rememberHaptics()
    val n = buckets.size
    if (n == 0) return@composed Modifier
    fun indexAt(x: Float, w: Float, g: Float): Int = (x / ((w - g).coerceAtLeast(1f) / n)).toInt().coerceIn(0, n - 1)
    fun snapped(i: Int): Int = if (!snap) i else (0 until n).filter { buckets[it].value != null || buckets[it].min != null }.minByOrNull { abs(it - i) } ?: i
    Modifier
        .pointerInput(buckets) {
            detectTapGestures(onLongPress = {}) { o ->
                val b = buckets[indexAt(o.x, size.width.toFloat(), gutter.toPx())]
                if (!b.future) onTap(b)
            }
        }
        .pointerInput(buckets, state) {
            var last = -1
            detectDragGesturesAfterLongPress(
                onDragStart = { o -> last = snapped(indexAt(o.x, size.width.toFloat(), gutter.toPx())); state.set(buckets[last], buckets); haptics.tick() },
                onDragEnd = { last = -1; state.clear() },
                onDragCancel = { last = -1; state.clear() },
                onDrag = { ch, _ ->
                    val i = snapped(indexAt(ch.position.x, size.width.toFloat(), gutter.toPx()))
                    if (i != last) { last = i; state.set(buckets[i], buckets); haptics.tick() }
                    ch.consume()
                },
            )
        }
}
