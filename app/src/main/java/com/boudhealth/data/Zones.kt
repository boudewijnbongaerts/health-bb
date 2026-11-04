package com.boudhealth.data

import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.ZoneId
import kotlin.math.sqrt

/**
 * Zone minutes, the Google "Active Zone Minutes" method:
 * heart-rate reserve (Karvonen) = (HR − resting HR) / (max HR − resting HR)
 *   ≥ 40 % fat burn → 1 point per minute
 *   ≥ 60 % cardio, ≥ 85 % peak → 2 points per minute
 * Heart-rate samples are averaged per clock minute first.
 */
object Zones {

    data class Bpm(val epochMs: Long, val bpm: Long)

    fun hrMax(goals: Goals, observedMax: Long?): Int =
        goals.birthYear?.let { (220 - (Year.now().value - it)).coerceIn(150, 210) }
            // no birth year: never go below 180, so a quiet week can't shrink the reserve and inflate zone minutes
            ?: maxOf(observedMax?.toInt() ?: 0, 180).coerceAtMost(210)

    /** Points per clock minute (key = epoch minute). */
    fun perMinute(samples: List<Bpm>, restingHr: Double, hrMax: Int): Map<Long, Int> {
        val reserve = (hrMax - restingHr).coerceAtLeast(20.0)
        val byMin = samples.groupBy { it.epochMs / 60_000L }.mapValues { (_, v) ->
            val hrr = (v.map { it.bpm }.average() - restingHr) / reserve
            when {
                hrr >= 0.60 -> 2
                hrr >= 0.40 -> 1
                else -> 0
            }
        }.toSortedMap()
        // trackers don't always write a sample every minute: carry a minute's zone over gaps of up to 2 minutes
        val out = HashMap<Long, Int>(byMin)
        var prev: Pair<Long, Int>? = null
        for ((m, pts) in byMin) {
            prev?.let { (pm, pp) -> if (pp > 0 && m - pm in 2..3) for (g in pm + 1 until m) out.putIfAbsent(g, pp) }
            prev = m to pts
        }
        return out
    }

    /**
     * Same rules as [perMinute], for one cached day ([HrDay.bpm]: per-minute average, 0 = no sample).
     * Returns points per minute slot (same indexing as the input).
     */
    fun minutePoints(bpm: ByteArray, restingHr: Double, hrMax: Int): IntArray {
        val reserve = (hrMax - restingHr).coerceAtLeast(20.0)
        val out = IntArray(bpm.size)
        var prevMin = -1
        var prevPts = 0
        for (m in bpm.indices) {
            val v = bpm[m].toInt() and 0xFF
            if (v == 0) continue
            val hrr = (v - restingHr) / reserve
            val pts = when {
                hrr >= 0.60 -> 2
                hrr >= 0.40 -> 1
                else -> 0
            }
            out[m] = pts
            // carry a minute's zone over gaps of up to 2 minutes
            if (prevMin >= 0 && prevPts > 0 && m - prevMin in 2..3) for (g in prevMin + 1 until m) out[g] = prevPts
            prevMin = m
            prevPts = pts
        }
        return out
    }

    fun byDay(points: Map<Long, Int>, zone: ZoneId): Map<LocalDate, Int> =
        points.entries.groupBy { Instant.ofEpochMilli(it.key * 60_000L).atZone(zone).toLocalDate() }
            .mapValues { (_, v) -> v.sumOf { it.value } }

    fun between(points: Map<Long, Int>, startMs: Long, endMs: Long): Int =
        points.entries.filter { it.key * 60_000L in startMs until endMs }.sumOf { it.value }

    /** Personal "normal" band: mean ± 1 SD of the values before the latest one (needs ≥ 7 days). */
    fun normalBand(values: List<Double>): Pair<Double, Double>? {
        val base = values.dropLast(1)
        if (base.size < 7) return null
        val m = base.average()
        val sd = sqrt(base.sumOf { (it - m) * (it - m) } / base.size).coerceAtLeast(m * 0.02)
        return (m - sd) to (m + sd)
    }
}
