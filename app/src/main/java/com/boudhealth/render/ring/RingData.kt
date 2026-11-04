package com.boudhealth.render.ring

import com.boudhealth.data.PeriodRing
import com.boudhealth.data.RingDay
import com.boudhealth.data.RingSet

/**
 * Ring data → painter input, BY RING ID (never by list index: the v6 Today-pill bug, V2-111). Slot / position order is
 * [PeriodRing.ORDER] = Workout minutes · Steps · Heart points (outer → inner), the same slots as the palette colours.
 */
object RingData {
    val ORDER: List<String> = PeriodRing.ORDER
    val NAMES: Map<String, String> = mapOf(PeriodRing.WORKOUT to "Workout minutes", PeriodRing.STEPS to "Steps", PeriodRing.HEART to "Heart points")
    val UNITS: Map<String, String> = mapOf(PeriodRing.WORKOUT to "min", PeriodRing.STEPS to "", PeriodRing.HEART to "pts")

    /** Progress per slot (0 when the ring is missing). */
    fun progress(set: RingSet): FloatArray = FloatArray(ORDER.size) { set.ring(ORDER[it])?.progress ?: 0f }

    /** Label per slot from the ring's own value / goal (null when the ring is missing). */
    fun labels(set: RingSet): List<RingLabel?> = ORDER.map { id -> set.ring(id)?.let { label(it) } }

    fun label(r: PeriodRing): RingLabel = RingLabel.of(r.value, r.goal, UNITS[r.id] ?: r.unit, NAMES[r.id] ?: r.id)

    /** v6 RingDay (Today tile / widgets until they read V2.rings): same ids, same numbers. */
    fun progress(d: RingDay): FloatArray = FloatArray(ORDER.size) { i -> d.ordered.firstOrNull { idOf(it.id) == ORDER[i] }?.progress ?: 0f }

    fun labels(d: RingDay): List<RingLabel?> = ORDER.map { id ->
        d.ordered.firstOrNull { idOf(it.id) == id }?.let { RingLabel.of(it.value, it.goal.toDouble().takeIf { g -> g > 0 }, UNITS[id] ?: "", NAMES[id] ?: it.label) }
    }

    /** RingValue ids (TRAINING / STEPS / HEART_WEEK) → PeriodRing ids. */
    private fun idOf(ringValueId: String): String = when (ringValueId) {
        com.boudhealth.data.RingValue.TRAINING -> PeriodRing.WORKOUT
        com.boudhealth.data.RingValue.STEPS -> PeriodRing.STEPS
        com.boudhealth.data.RingValue.HEART_WEEK -> PeriodRing.HEART
        else -> ringValueId
    }
}
