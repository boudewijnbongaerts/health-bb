package com.boudhealth.data

import androidx.health.connect.client.records.ExerciseSessionRecord as E

/** Names and categories for Health Connect exercise types. Category: 0 strength, 1 cardio, 2 other. */
object ExerciseTypes {

    private val names = mapOf(
        E.EXERCISE_TYPE_STRENGTH_TRAINING to "Strength training",
        E.EXERCISE_TYPE_WEIGHTLIFTING to "Weightlifting",
        E.EXERCISE_TYPE_CALISTHENICS to "Calisthenics",
        E.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING to "HIIT",
        E.EXERCISE_TYPE_PILATES to "Pilates",
        E.EXERCISE_TYPE_YOGA to "Yoga",
        E.EXERCISE_TYPE_STRETCHING to "Stretching",
        E.EXERCISE_TYPE_RUNNING to "Running",
        E.EXERCISE_TYPE_RUNNING_TREADMILL to "Treadmill",
        E.EXERCISE_TYPE_WALKING to "Walking",
        E.EXERCISE_TYPE_HIKING to "Hiking",
        E.EXERCISE_TYPE_BIKING to "Cycling",
        E.EXERCISE_TYPE_BIKING_STATIONARY to "Indoor cycling",
        E.EXERCISE_TYPE_ELLIPTICAL to "Elliptical",
        E.EXERCISE_TYPE_ROWING_MACHINE to "Rowing machine",
        E.EXERCISE_TYPE_ROWING to "Rowing",
        E.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE to "Stair climber",
        E.EXERCISE_TYPE_STAIR_CLIMBING to "Stairs",
        E.EXERCISE_TYPE_SWIMMING_POOL to "Swimming",
        E.EXERCISE_TYPE_SWIMMING_OPEN_WATER to "Open-water swim",
        E.EXERCISE_TYPE_TENNIS to "Tennis",
        E.EXERCISE_TYPE_BADMINTON to "Badminton",
        E.EXERCISE_TYPE_SOCCER to "Football",
        E.EXERCISE_TYPE_DANCING to "Dancing",
        E.EXERCISE_TYPE_OTHER_WORKOUT to "Workout",
    )

    private val strength = setOf(
        E.EXERCISE_TYPE_STRENGTH_TRAINING, E.EXERCISE_TYPE_WEIGHTLIFTING, E.EXERCISE_TYPE_CALISTHENICS,
        E.EXERCISE_TYPE_PILATES,
    )
    private val cardio = setOf(
        E.EXERCISE_TYPE_RUNNING, E.EXERCISE_TYPE_RUNNING_TREADMILL, E.EXERCISE_TYPE_BIKING, E.EXERCISE_TYPE_BIKING_STATIONARY,
        E.EXERCISE_TYPE_ELLIPTICAL, E.EXERCISE_TYPE_ROWING_MACHINE, E.EXERCISE_TYPE_ROWING, E.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE,
        E.EXERCISE_TYPE_SWIMMING_POOL, E.EXERCISE_TYPE_SWIMMING_OPEN_WATER, E.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING,
        E.EXERCISE_TYPE_HIKING,
    )

    fun category(type: Int): Int = when (type) {
        in strength -> 0
        in cardio -> 1
        else -> 2
    }

    /** The app-given title wins (e.g. "Upper body"), else the type's name ([group] label). */
    fun name(w: Workout): String = w.title?.takeIf { it.isNotBlank() } ?: typeName(w)

    private val groups: Map<Int, WorkoutGroup> = buildMap {
        listOf(E.EXERCISE_TYPE_STRENGTH_TRAINING, E.EXERCISE_TYPE_WEIGHTLIFTING, E.EXERCISE_TYPE_CALISTHENICS).forEach { put(it, WorkoutGroup.STRENGTH) }
        listOf(E.EXERCISE_TYPE_BIKING, E.EXERCISE_TYPE_BIKING_STATIONARY).forEach { put(it, WorkoutGroup.CYCLING) }
        put(E.EXERCISE_TYPE_WALKING, WorkoutGroup.WALKING)
        put(E.EXERCISE_TYPE_RUNNING, WorkoutGroup.RUNNING)
        put(E.EXERCISE_TYPE_RUNNING_TREADMILL, WorkoutGroup.TREADMILL)
        listOf(E.EXERCISE_TYPE_ROWING, E.EXERCISE_TYPE_ROWING_MACHINE, E.EXERCISE_TYPE_PADDLING).forEach { put(it, WorkoutGroup.ROWING) }
        put(E.EXERCISE_TYPE_ELLIPTICAL, WorkoutGroup.ELLIPTICAL)
        listOf(E.EXERCISE_TYPE_SWIMMING_POOL, E.EXERCISE_TYPE_SWIMMING_OPEN_WATER).forEach { put(it, WorkoutGroup.SWIMMING) }
        listOf(E.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING, E.EXERCISE_TYPE_BOOT_CAMP, E.EXERCISE_TYPE_EXERCISE_CLASS).forEach { put(it, WorkoutGroup.HIIT) }
        listOf(E.EXERCISE_TYPE_YOGA, E.EXERCISE_TYPE_PILATES, E.EXERCISE_TYPE_STRETCHING).forEach { put(it, WorkoutGroup.YOGA) }
        put(E.EXERCISE_TYPE_HIKING, WorkoutGroup.HIKING)
        listOf(E.EXERCISE_TYPE_STAIR_CLIMBING, E.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE).forEach { put(it, WorkoutGroup.STAIRS) }
        listOf(E.EXERCISE_TYPE_TENNIS, E.EXERCISE_TYPE_BADMINTON, E.EXERCISE_TYPE_SOCCER, E.EXERCISE_TYPE_SQUASH, E.EXERCISE_TYPE_TABLE_TENNIS,
            E.EXERCISE_TYPE_BASKETBALL, E.EXERCISE_TYPE_VOLLEYBALL, E.EXERCISE_TYPE_GOLF, E.EXERCISE_TYPE_MARTIAL_ARTS, E.EXERCISE_TYPE_BOXING).forEach { put(it, WorkoutGroup.SPORTS) }
        put(E.EXERCISE_TYPE_DANCING, WorkoutGroup.DANCING)
        put(E.EXERCISE_TYPE_OTHER_WORKOUT, WorkoutGroup.OTHER)
    }

    /** v5 (S5): the workout's kind — one name and one colour per kind; "Workout" only when truly unknown. */
    fun group(w: Workout): WorkoutGroup = WorkoutGroup.of(groups[w.type], w.title)
    fun group(type: Int, title: String? = null): WorkoutGroup = WorkoutGroup.of(groups[type], title)

    /** v5 (S5): the kind's name ("Strength", "Cycling", "Treadmill", …). */
    fun typeName(w: Workout): String = group(w).label
}
