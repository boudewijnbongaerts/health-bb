package com.boudhealth.ui.settings

import com.boudhealth.data.CachedWorkout
import com.boudhealth.data.DayValue
import com.boudhealth.data.SyncLogic
import com.boudhealth.data.WorkoutDedupe
import com.boudhealth.data.WorkoutSpan

/*
 * R6 #3 (Data check): the source(s) Health BB actually USED for a data type, read from the cache's per-day choice
 * (`daily.source`), not every app that ever wrote that record type to Health Connect (those stay in the copied report).
 * Pure Kotlin (no android.*), unit-tested in r3/tools/unit/tests/UsedSourcesTest.kt. No Health Connect calls.
 */
object UsedSources {

    /**
     * Packages behind [rows] (one cached value per day; aggregates store "a,b"), most days first.
     * Ties: the package used most recently first, then by name (stable output).
     */
    fun ofDays(rows: List<DayValue>): List<String> {
        val days = HashMap<String, Int>()
        val newest = HashMap<String, Long>()
        for (r in rows) {
            for (p in pkgs(r.source)) {
                days[p] = (days[p] ?: 0) + 1
                val t = r.day.toEpochDay()
                if (t > (newest[p] ?: Long.MIN_VALUE)) newest[p] = t
            }
        }
        return days.keys.sortedWith(compareByDescending<String> { days[it] }.thenByDescending { newest[it] }.thenBy { it })
    }

    /**
     * Packages that contributed to the de-duplicated workouts in [rows] (same clustering as `Local.workouts`), each app
     * counted once per merged session; most sessions first, then by workout priority ([WorkoutDedupe.rank]).
     */
    fun ofWorkouts(rows: List<CachedWorkout>): List<String> {
        val count = HashMap<String, Int>()
        for (cl in clusters(rows)) cl.map { it.pkg }.distinct().forEach { count[it] = (count[it] ?: 0) + 1 }
        return count.keys.sortedWith(compareByDescending<String> { count[it] }.thenBy { WorkoutDedupe.rank(it) }.thenBy { it })
    }

    /** Packages of the newest merged workout session in [rows] (fallback for a quiet month), priority order. */
    fun ofNewestWorkout(rows: List<CachedWorkout>): List<String> =
        clusters(rows).maxByOrNull { cl -> cl.maxOf { it.end } }?.map { it.pkg }?.distinct()?.sortedBy { WorkoutDedupe.rank(it) } ?: emptyList()

    /**
     * Sparse types (VO₂ max, body measurements, height, waist…): with nothing in the window, show the source of the
     * newest value instead, so a row that shows a last value is never left without a source.
     */
    fun withFallback(recent: List<String>, sparse: Boolean, newest: DayValue?): List<String> =
        if (recent.isNotEmpty() || !sparse || newest == null) recent else pkgs(newest.source)

    private fun pkgs(source: String?): List<String> =
        source?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct() ?: emptyList()

    private fun clusters(rows: List<CachedWorkout>): List<List<WorkoutSpan>> =
        SyncLogic.clusterWorkouts(
            rows.map { WorkoutSpan(it.id + "\u0000" + it.pkg, it.pkg, it.start, it.end, it.type != 0 || !it.title.isNullOrBlank()) },
            WorkoutDedupe::rank,
        )
}
