package com.boudhealth.data

import android.content.Context
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import java.time.Duration

/**
 * Avoids double counting when several apps write the same thing to Health Connect.
 *
 * - Steps and total calories: read through Health Connect's aggregate API, which de-duplicates
 *   using the app priority you set in Health Connect → Data sources and priority.
 * - Everything else (raw records: weight, body fat, heart rate, HRV, sleep, …) is NOT de-duplicated by Health Connect,
 *   so we keep exactly one app per data type **per day** (the sync engine applies this day by day, so a day on which
 *   only a lower-priority app has data is still shown):
 *     body (weight, fat, lean, height)  → Withings first (the scale), then Google Health, then Renpho
 *     wearable (HR, HRV, sleep, VO2, workouts, …) → Google Health / Fitbit first (the tracker)
 * - Workouts: overlapping sessions are merged; the preferred app's session wins, otherwise the longer one.
 */
object Sources {

    enum class Kind { BODY, WEARABLE }

    private const val WITHINGS = "com.withings.wiscale2"
    /** Google Health app (formerly Fitbit): the only source of Calories burned (v5 D1). */
    const val GOOGLE_HEALTH = "com.fitbit.FitbitMobile"
    private val GOOGLE = listOf(
        "com.fitbit.FitbitMobile",          // Google Health app (formerly Fitbit)
        "com.google.android.apps.fitness",  // Google Fit
        "com.google.android.apps.healthdata",
    )
    private const val SAMSUNG = "com.sec.android.app.shealth"
    private const val RENPHO = "com.renpho.health"

    private val PRIORITY = mapOf(
        Kind.BODY to listOf(WITHINGS) + GOOGLE + listOf(RENPHO, SAMSUNG),
        Kind.WEARABLE to GOOGLE + listOf(WITHINGS, SAMSUNG, RENPHO),
    )

    /** Pseudo source for heart-rate days read as Health Connect 1-minute aggregates (all apps merged). */
    const val MERGED = "health.connect.merged"

    /**
     * Lets [SourceNames] resolve unknown packages to the installed app's name (PackageManager; packages listed in the
     * manifest's <queries> are visible). Cheap and idempotent.
     */
    fun init(context: Context) {
        if (SourceNames.resolver != null) return
        val pm = context.applicationContext.packageManager
        val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
        SourceNames.resolver = { pkg ->
            cache.getOrPut(pkg) {
                try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { "" }
            }.ifEmpty { null }
        }
    }

    /** Human app name (never an id); see [SourceNames]. */
    fun label(pkg: String): String = SourceNames.label(pkg)

    /** Lower = preferred. Unknown apps rank last (99). */
    fun rank(kind: Kind, pkg: String): Int = PRIORITY.getValue(kind).indexOf(pkg).let { if (it < 0) 99 else it }

    /** Keeps the records of one app only: the highest-priority app that has data. */
    fun <T : Record> preferred(records: List<T>, kind: Kind): List<T> {
        if (records.isEmpty()) return records
        val byPkg = records.groupBy { it.metadata.dataOrigin.packageName }
        if (byPkg.size == 1) return records
        val pick = byPkg.keys.minWith(compareBy<String> { rank(kind, it) }.thenByDescending { byPkg.getValue(it).size })
        return byPkg.getValue(pick)
    }

    /** Preferred package given how many records/samples each app has: priority first, then the app with more data. */
    fun choose(countByPkg: Map<String, Int>, kind: Kind): String? = SyncLogic.choose(countByPkg) { rank(kind, it) }

    /** Comma-separated package list (as stored in the cache) → "Google Health, Withings". */
    fun labels(pkgs: String?): String? = SourceNames.labels(pkgs)

    /** Highest-priority package among [pkgs] (e.g. the dataOrigins of an aggregate), or null if none. */
    fun pick(pkgs: Collection<String>, kind: Kind): String? = pkgs.minByOrNull { rank(kind, it) }

    /** Label of the app the (already filtered) records come from. */
    fun <T : Record> sourceOf(records: List<T>): String? =
        records.lastOrNull()?.metadata?.dataOrigin?.packageName?.let { label(it) }

    /** Workout preference: Health Connect apps by wearable priority, the Apple Health import after all of them. */
    fun workoutRank(pkg: String): Int = if (pkg.startsWith(com.boudhealth.data.importer.IMPORT_SOURCE)) 1000 else rank(Kind.WEARABLE, pkg)

    /**
     * Same workout recorded several times (several apps, or one app writing copies): ONE session per real workout
     * ([SyncLogic.clusterWorkouts]). The merged session: start / end = the union of the copies; exercise type and title
     * from the best typed / titled copy (a "Stationary bike" over "other workout"); kcal, avg HR and intensity from the
     * preferred app's copy (else the next copy that has them). Newest first.
     */
    fun dedupeWorkouts(raw: List<Pair<Workout, String>>): List<Workout> {
        // V2: one pure rule (WorkoutDedupe, same ranks), shared with the JVM engine
        return WorkoutDedupe.dedupe(raw)
    }

    /** Overlapping sleep sessions (after source preference): keep the longest. */
    fun dedupeSleep(raw: List<SleepSessionRecord>): List<SleepSessionRecord> {
        val kept = mutableListOf<SleepSessionRecord>()
        for (s in raw.sortedByDescending { Duration.between(it.startTime, it.endTime) }) {
            if (kept.none { k -> k.startTime < s.endTime && s.startTime < k.endTime }) kept += s
        }
        return kept.sortedBy { it.endTime }
    }

    /** Health Connect stage type → the stage names of [SleepStage]. */
    fun stageName(t: Int): String = when (t) {
        SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
        SleepSessionRecord.STAGE_TYPE_REM -> "rem"
        SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
        SleepSessionRecord.STAGE_TYPE_SLEEPING -> "asleep"
        SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "awake"
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "out_of_bed"
        else -> "unknown"
    }

    fun sleepInfo(s: SleepSessionRecord): SleepInfo {
        var deep = 0L; var rem = 0L; var light = 0L; var awake = 0L; var asleep = 0L
        for (st in s.stages) {
            val m = Duration.between(st.startTime, st.endTime).toMinutes()
            when (st.stage) {
                SleepSessionRecord.STAGE_TYPE_DEEP -> deep += m
                SleepSessionRecord.STAGE_TYPE_REM -> rem += m
                SleepSessionRecord.STAGE_TYPE_LIGHT -> light += m
                // v4.1 fix: "sleeping, stage unknown" is asleep without a stage, not light sleep
                SleepSessionRecord.STAGE_TYPE_SLEEPING -> asleep += m
                // awake in bed counts as awake (review m5); UNKNOWN is neither asleep nor awake and is left out
                SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> awake += m
                else -> Unit // STAGE_TYPE_UNKNOWN
            }
        }
        // no stages at all (or only UNKNOWN ones): the whole session counts as "Asleep (no stages)" — v4.1 fix, was light
        if (s.stages.isEmpty() || (deep + rem + light + awake + asleep == 0L)) asleep = Duration.between(s.startTime, s.endTime).toMinutes()
        return SleepInfo(s.startTime.toEpochMilli(), s.endTime.toEpochMilli(), deep.toInt(), rem.toInt(), light.toInt(), awake.toInt(), asleep.toInt())
    }
}
