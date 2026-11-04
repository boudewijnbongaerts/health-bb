package com.boudhealth.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import kotlin.reflect.KClass

/**
 * Health Connect access basics: availability, permissions, optional features.
 * Reading and caching is done by [Sync]; the dashboard snapshot is built from the cache by [Snapshots].
 */
class HcReader(context: Context) {

    private val client = HealthConnectClient.getOrCreate(context)

    suspend fun grantedPermissions(): Set<String> = client.permissionController.getGrantedPermissions()

    companion object {
        val READ_TYPES: List<KClass<out Record>> = listOf(
            StepsRecord::class, TotalCaloriesBurnedRecord::class, ActiveCaloriesBurnedRecord::class,
            ExerciseSessionRecord::class, HeartRateRecord::class, RestingHeartRateRecord::class,
            HeartRateVariabilityRmssdRecord::class, Vo2MaxRecord::class, OxygenSaturationRecord::class,
            RespiratoryRateRecord::class, WeightRecord::class, BodyFatRecord::class, LeanBodyMassRecord::class,
            HeightRecord::class, SleepSessionRecord::class,
        )
        val CORE_PERMISSIONS: Set<String> = READ_TYPES.map { HealthPermission.getReadPermission(it) }.toSet()
        val HISTORY: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
        val BACKGROUND: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
        /**
         * Optional (v4, P-KCAL): resting burn for the calories split. Requested with the others, never required: without
         * it active calories are shown only when an app writes them.
         */
        /** v5 (D1): no BMR permission any more (calories = Google Health's own records only). */
        val PERMISSIONS: Set<String> = CORE_PERMISSIONS + BACKGROUND + HISTORY

        fun available(context: Context): Boolean =
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

        fun readPermission(type: KClass<out Record>): String = HealthPermission.getReadPermission(type)

        /** Does this Health Connect version support the feature (history / background reads)? */
        fun featureAvailable(context: Context, feature: Int): Boolean =
            available(context) && try {
                HealthConnectClient.getOrCreate(context).features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
            } catch (e: Exception) {
                false
            }

        fun historyFeature(context: Context) = featureAvailable(context, HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY)
        fun backgroundFeature(context: Context) = featureAvailable(context, HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)

        /**
         * Permissions without which the app shows the "Connect" card: every read type, plus "past data" where supported.
         * Without "past data" Health Connect silently returns nothing older than 30 days before the first grant,
         * which made 3M / 1Y / All look identical.
         */
        fun requiredPermissions(context: Context): Set<String> =
            if (historyFeature(context)) CORE_PERMISSIONS + HISTORY else CORE_PERMISSIONS
    }
}
