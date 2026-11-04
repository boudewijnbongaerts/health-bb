package com.boudhealth.data

import kotlinx.serialization.Serializable

/** Everything the dashboard shows, read from Health Connect in one go and cached as JSON. */
@Serializable
data class Snapshot(
    val updatedAt: Long,
    /** Last 7 days, oldest first; the last entry is today. */
    val week: List<DayActivity>,
    /** Last ~26 weeks, newest first, duplicates from different apps removed. */
    val workouts: List<Workout> = emptyList(),
    /** One point per day that has any body measurement, oldest first (~400 days). */
    val body: List<BodyPoint> = emptyList(),
    val heightM: Double? = null,
    val hrLatest: Sample? = null,
    /** Resting heart rate per day, last 60 days, oldest first (baseline + sparkline). */
    val restingHr: List<Sample> = emptyList(),
    /** HRV (RMSSD, ms) per day, last 60 days, oldest first. */
    val hrv: List<Sample> = emptyList(),
    val vo2: Sample? = null,
    val spo2: Sample? = null,
    val resp: Sample? = null,
    val sleep: SleepInfo? = null,
    /** Which app each data group came from, e.g. "body" -> "Withings", "vitals" -> "Google Health". */
    val sources: Map<String, String> = emptyMap(),
    /** Max heart rate used for the zones (from birth year, else highest recorded). */
    val hrMax: Int? = null,
) {
    val today: DayActivity get() = week.last()
}

/**
 * date = ISO local date (yyyy-MM-dd).
 * kcal = total calories burned (incl. resting), the same number Google Health shows as "Cal burned".
 * zoneMin = zone minutes: 1 per minute in fat-burn zone, 2 per minute in cardio/peak (Google AZM method).
 * exerciseMin = length of recorded workouts that day.
 */
@Serializable
data class DayActivity(
    val date: String,
    val steps: Long = 0,
    val kcal: Double = 0.0,
    val zoneMin: Int = 0,
    val exerciseMin: Int = 0,
)

@Serializable
data class Workout(
    val start: Long,
    val end: Long,
    val type: Int,
    val title: String? = null,
    val kcal: Double? = null,
    val avgHr: Long? = null,
    val zoneMin: Int? = null,
    val source: String? = null,
) {
    val minutes: Int get() = ((end - start) / 60_000L).toInt()
}

@Serializable
data class BodyPoint(val date: String, val weightKg: Double? = null, val fatPct: Double? = null, val leanKg: Double? = null)

@Serializable
data class Sample(val time: Long, val value: Double)

@Serializable
data class SleepInfo(
    val start: Long, val end: Long, val deepMin: Int, val remMin: Int, val lightMin: Int, val awakeMin: Int,
    /** v4.1 fix: minutes asleep WITHOUT stage information ("Asleep (no stages)"); part of [asleepMin], not of light. */
    val asleepUnspecifiedMin: Int = 0,
) {
    val asleepMin: Int get() = deepMin + remMin + lightMin + asleepUnspecifiedMin
    companion object { const val UNSPECIFIED_LABEL = "Asleep (no stages)" }
}

@Serializable
data class Goals(
    /** v6: no calorie goal any more; field kept only so stored goals and old callers still work. */
    @Deprecated("v6: no calorie goal") val kcal: Int = 2_500,
    /** Daily zone minutes; 22 a day ≈ the 150-a-week health guideline. */
    val zoneMin: Int = 22,
    /** Weekly heart points target (ring 3), default [DEFAULT_WEEKLY_HEART_POINTS]; reset with Store.resetWeeklyHeartPoints. */
    val zoneMinWeek: Int = DEFAULT_WEEKLY_HEART_POINTS,
    val steps: Int = 10_000,
    val workoutsPerWeek: Int = 3,
    /** For the heart-rate zones (max HR = 220 − age unless [maxHr] is set). */
    val birthYear: Int? = null,
    /** v6: IGNORED (max HR = measured only, no setting). Kept so stored goals still decode. */
    @Deprecated("v6: max HR is measured only") val maxHr: Int? = null,
    /** v4 (P-RINGS): daily workout minutes goal (ring 2). */
    val workoutMinDay: Int = 30,
    /** v4 (Q19): days with ≥ 1 workout per week. */
    val workoutDaysWeek: Int = 5,
    /** v4 (Q22): the user's name for greetings (Profile). */
    val name: String? = null,
    /** Optional targets, drawn as a dashed line on the body charts. */
    val targetWeightKg: Double? = null,
    val targetFatPct: Double? = null,
    val targetWaistCm: Double? = null,
    /**
     * LEGACY (old section dashboard). Ignored by the new UI; tile layouts live in [UiPrefs.layouts].
     * Kept only so the old widget renderer (render/Renderer.kt) still compiles until the widget rebuild drops it.
     */
    val order: List<String> = listOf("RINGS", "WORKOUTS", "BODY", "VITALS"),
    val hiddenSections: Set<String> = emptySet(),
    val hiddenTiles: Set<String> = emptySet(),
    // ---- V2.0 (T1, IC10)
    /** Max HR for the zones; null = [MaxHr.FIT_DEFAULT] (190, fitted to Google Health AZM, V2-011). */
    val maxHrFit: Int? = null,
    /** D18: workout-day rule. "workout" (default) or "points". */
    val activeDayMode: String = "workout",
    /** D18 / G0: a workout of at least this many minutes … */
    val activeDayMinWorkoutMin: Int = 20,
    /** … that earned at least this many heart points itself. */
    val activeDayMinWorkoutPoints: Int = 10,
    /** Points rule: a day with at least this many heart points. */
    val activeDayMinPoints: Int = 10,
    /** D9: start values of the body targets (stored when a target is set; null = backfill rule, see V2.bodyTargetStart). */
    val targetWeightStart: Double? = null,
    val targetFatPctStart: Double? = null,
    val targetWaistStart: Double? = null,
    /** Epoch day the targets were set (for the D9 backfill: first value on/after it). */
    val targetWeightSetDay: Long? = null,
    val targetFatPctSetDay: Long? = null,
    val targetWaistSetDay: Long? = null,
) {
    /** IC10: the workout-day rule in force. */
    val activeDayRule: ActiveDayRule get() = ActiveDayRule(
        if (activeDayMode == "points") ActiveDayRule.Mode.POINTS else ActiveDayRule.Mode.WORKOUT,
        activeDayMinWorkoutMin.coerceAtLeast(1), activeDayMinWorkoutPoints.coerceAtLeast(0), activeDayMinPoints.coerceAtLeast(1),
    )

    companion object {
        const val DEFAULT_WEEKLY_HEART_POINTS = 150
        /** D17: WHO minimum (150 min moderate activity a week ≈ 150 heart points). */
        const val WHO_WEEKLY_HEART_POINTS = 150
    }
}
