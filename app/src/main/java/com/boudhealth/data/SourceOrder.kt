package com.boudhealth.data

/**
 * Per-day source priority lists (pure Kotlin, so the order can be unit-tested on the JVM). [Sources] ranks with them.
 * Lower index = preferred.
 */
object SourceOrder {
    /** Pseudo source id of values read directly from the Google Health API (not through Health Connect). */
    const val GOOGLE_HEALTH_API = "com.google.health.api"

    const val WITHINGS = "com.withings.wiscale2"
    val GOOGLE = listOf(
        "com.fitbit.FitbitMobile",          // Google Health app (formerly Fitbit)
        "com.google.android.apps.fitness",  // Google Fit
        "com.google.android.apps.healthdata",
    )
    const val SAMSUNG = "com.sec.android.app.shealth"
    const val RENPHO = "com.renpho.health"

    /** body (weight, fat, lean, height): the scale first. */
    val BODY: List<String> = listOf(WITHINGS) + GOOGLE + listOf(RENPHO, SAMSUNG)

    /**
     * wearable (HR, HRV, sleep, VO₂ max, workouts, …): the tracker first. The Google Health API (VO₂ max only) ranks
     * before everything, so a day it has wins over the Health Connect copies (Fitbit, Withings, …).
     */
    val WEARABLE: List<String> = listOf(GOOGLE_HEALTH_API) + GOOGLE + listOf(WITHINGS, SAMSUNG, RENPHO)

    /** Index in [list]; unknown apps rank last (99). */
    fun rank(list: List<String>, pkg: String): Int = list.indexOf(pkg).let { if (it < 0) 99 else it }
}
