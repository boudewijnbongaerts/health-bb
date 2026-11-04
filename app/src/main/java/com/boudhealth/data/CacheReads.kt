package com.boudhealth.data

import java.time.LocalDate

/*
 * V2 (IC2 seam, V2-020): the READ side of the local cache as a pure interface, so everything above SQLite (V2, V2Engine,
 * Metrics, TileSource, …) can run on the JVM against a fixture (scratchpad: FixtureCache). [Cache] implements it on the
 * device. Pure Kotlin: no android.* here.
 */
interface CacheReads {
    /** Ascending by day, [from]..[to] inclusive; per day one side (Health Connect or Apple import), never both added. */
    fun daily(metric: String, from: LocalDate, to: LocalDate): List<DayValue>
    /** Newest value on or before [onOrBefore] (null = any day). */
    fun latest(metric: String, onOrBefore: LocalDate? = null): DayValue?
    fun oldestDay(metric: String): LocalDate?
    /** (hour start epoch ms, value), ascending; empty when that period is not cached (≈ last 15 days only). */
    fun hourly(metric: String, fromMs: Long, toMs: Long): List<Pair<Long, Double>>
    /** Per-minute heart rate, one side per day (the one with more measured minutes). */
    fun hrDays(from: LocalDate, to: LocalDate): List<HrDay>
    fun hrDay(day: LocalDate): HrDay?
    fun latestHrDay(): HrDay?
    /** Sessions that start in [fromMs, toMs), oldest first, BOTH sides, NOT de-duplicated ([WorkoutDedupe]). */
    fun workouts(fromMs: Long, toMs: Long): List<CachedWorkout>
    /** Stage timeline of the night ending on [day] (wake-up day). */
    fun sleepStages(day: LocalDate): List<SleepStage>
    fun hasAnyData(): Boolean
    fun meta(key: String): String?
}

/** A workout session as read from Health Connect (all apps; duplicates are removed at query time), plus enrichment. */
data class CachedWorkout(
    val id: String,
    val start: Long,
    val end: Long,
    val type: Int,
    val title: String?,
    val pkg: String,
    val kcal: Double?,
    val avgHr: Long?,
    val zoneMin: Int?,
    /** Where kcal came from: the session's own app, or the wearable's total as fallback. */
    val kcalSource: String?,
    /** kcal / avg HR were looked up in Health Connect (sessions are stored first, enriched later in a budgeted pass). */
    val enriched: Boolean = false,
) {
    fun toWorkout() = Workout(start, end, type, title, kcal, avgHr, zoneMin, SourceNames.label(pkg))
}

/** Main sleep of one night, by wake-up day. */
data class SleepNight(val date: LocalDate, val info: SleepInfo)

/**
 * Workout de-duplication, pure (moved from Sources so the JVM engine uses the very same rule): Health Connect apps by
 * wearable priority, the Apple Health import after all of them; one session per real workout.
 */
object WorkoutDedupe {
    private val WEARABLE = listOf(
        "com.fitbit.FitbitMobile", "com.google.android.apps.fitness", "com.google.android.apps.healthdata",
        "com.withings.wiscale2", "com.sec.android.app.shealth", "com.renpho.health",
    )
    private const val IMPORT = "com.apple.health.import"

    fun rank(pkg: String): Int = if (pkg.startsWith(IMPORT)) 1000 else WEARABLE.indexOf(pkg).let { if (it < 0) 99 else it }

    /** Newest first, like [Sources.dedupeWorkouts]. */
    fun dedupe(raw: List<Pair<Workout, String>>): List<Workout> {
        val spans = raw.mapIndexed { i, (w, pkg) -> WorkoutSpan(i.toString(), pkg, w.start, w.end, w.type != 0 || !w.title.isNullOrBlank()) }
        return SyncLogic.clusterWorkouts(spans, ::rank).map { cl -> WorkoutMath.merged(cl.map { raw[it.id.toInt()].first }) }
    }

    fun of(rows: List<CachedWorkout>): List<Workout> = dedupe(rows.map { it.toWorkout() to it.pkg })
}
