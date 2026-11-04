package com.boudhealth.data

import java.time.LocalDate

/*
 * Cache value types and metric keys: pure Kotlin (no Android), moved out of Cache.kt so the Apple Health importer's
 * logic can be unit-tested on the JVM with the same keys and row types the cache uses.
 */

/** Column `metric` values of the `daily` table. */
object M {
    // activity (Health Connect aggregates, de-duplicated by HC priority)
    const val STEPS = "steps"
    const val KCAL = "kcal"
    // heart (from raw heart-rate samples of one app per day)
    /** v4.1: INTENSITY minutes per day (Fitbit AZM on heart-rate reserve: fat burn × 1, cardio / peak × 2). */
    const val ZONE_MIN = "zone_min"
    /** v4.1: minutes per day below zones / fat burn / cardio / peak (index = HrZones.BELOW..PEAK). */
    val AZM = listOf("zone_below", "zone_fatburn", "zone_cardio", "zone_peak")
    const val HR_AVG = "hr_avg"
    const val HR_MIN = "hr_min"
    const val HR_MAX = "hr_max"
    // vitals (one app per day, average of that day's readings)
    const val RHR = "rhr"
    const val HRV = "hrv"
    const val RESP = "resp"
    const val SPO2 = "spo2"
    const val VO2 = "vo2"
    // sleep (main sleep of the night, by wake-up day)
    const val SLEEP_MIN = "sleep_min"
    const val SLEEP_DEEP = "sleep_deep"
    const val SLEEP_REM = "sleep_rem"
    const val SLEEP_LIGHT = "sleep_light"
    const val SLEEP_AWAKE = "sleep_awake"
    /** v4.1 fix: sleep without stage information ("Asleep (no stages)"), no longer counted as light. */
    const val SLEEP_ASLEEP = "sleep_asleep"
    const val SLEEP_START = "sleep_start"
    const val SLEEP_END = "sleep_end"
    // body (one app per day, last reading of the day)
    const val WEIGHT = "weight"
    const val FAT_PCT = "fat_pct"
    const val LEAN_KG = "lean_kg"
    const val HEIGHT = "height_m"
    // manual entries mirrored from Store
    const val WAIST = "waist_cm"
    const val HEIGHT_MANUAL = "height_manual_m"
    // Apple Health import only (Health Connect has no equivalent series)
    /** HRV as SDNN (Apple). NOT the same quantity as [HRV] (RMSSD, Health Connect): kept as its own series. */
    const val HRV_SDNN = "hrv_sdnn"
    /** Active calories: delivered by the same app as the total, else total − resting burn (estimated flag). */
    const val ACTIVE_KCAL = "active_kcal"
    /** Resting calories: total − active (calculated) or Health Connect's resting burn; always flagged estimated. */
    const val REST_KCAL = "rest_kcal"
    const val DISTANCE_M = "distance_m"
    const val FLIGHTS = "flights"

    val SLEEP = listOf(SLEEP_MIN, SLEEP_DEEP, SLEEP_REM, SLEEP_LIGHT, SLEEP_ASLEEP, SLEEP_AWAKE, SLEEP_START, SLEEP_END)
}

/** One cached daily value. [time] = time of the newest reading behind it; [source] = package(s), comma separated. */
data class DayValue(
    val day: LocalDate, val value: Double, val time: Long, val source: String?,
    /** Calculated by Health BB (not delivered by an app): label it in the UI. */
    val estimated: Boolean = false,
) {
    /** Human app name(s) of [source] ("Google Health", "Calculated", "Apple Health (import)"), never an id. */
    val sourceLabel: String? get() = SourceNames.labels(source)
}

/** Row to write into `daily`. */
data class DailyRow(val metric: String, val day: LocalDate, val value: Double, val time: Long, val source: String?, val estimated: Boolean = false)

/**
 * Heart rate of one local day, per minute since local midnight (index 0 = 00:00), average bpm of that minute,
 * 0 = no sample. 1500 slots so 25-hour DST days fit. Only the preferred wearable app of that day is kept.
 */
/**
 * Per-minute heart rate of one local day: [bpm] index m = the minute starting at [startMs] + m × 60 s.
 * [startMs] (v4.1 fix): the instant minute 0 was keyed to when the day was written (start of [day] in the phone's time
 * zone AT THAT TIME). Stored, so a later time-zone change can't shift workouts against their heart rate. null =
 * written before v4.1 (old import rows): the current zone is assumed, as before.
 */
class HrDay(val day: LocalDate, val source: String, val bpm: ByteArray, val startMs: Long? = null) {
    fun at(minute: Int): Int = if (minute in bpm.indices) bpm[minute].toInt() and 0xFF else 0

    /** Instant of minute 0: the stored one, else the start of [day] in [zone]. */
    fun start(zone: java.time.ZoneId): Long = startMs ?: day.atStartOfDay(zone).toInstant().toEpochMilli()

    companion object {
        const val MINUTES = 1500
    }
}


/**
 * One stage segment of the main sleep of a night ([day] = wake-up day). [stage]: "deep", "rem", "light", "awake",
 * "asleep" (sleeping, stage unknown), "inbed", "out_of_bed", "unknown" — exactly as delivered.
 */
data class SleepStage(val day: LocalDate, val start: Long, val end: Long, val stage: String, val source: String?)
