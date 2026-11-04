package com.boudhealth.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.IOException

// A corrupt preferences file is replaced by empty preferences (defaults) instead of failing every read.
private val Context.dataStore by preferencesDataStore(
    name = "healthboud",
    corruptionHandler = ReplaceFileCorruptionHandler { e ->
        Log.w("HealthBB", "preferences file corrupt, reset to defaults", e)
        emptyPreferences()
    },
)

/**
 * Small preferences store: last dashboard snapshot (built from [Cache]), goals, and manual entries
 * (waist in cm, height in m). Health Connect data itself lives in [Cache] (SQLite).
 * Manual entries are mirrored into the cache by the sync so the metric registry sees them as well.
 */
class Store(context: Context) {
    private val ds = context.applicationContext.dataStore

    /**
     * All reads go through this: an IOException while reading emits empty preferences (= defaults), so the UI, which
     * waits for the first prefs emission, never blanks or crashes on a read failure. Other errors still throw.
     */
    private val data: Flow<Preferences> = ds.data.catch { e ->
        if (e is IOException) {
            Log.w("HealthBB", "preferences read failed, using defaults", e)
            emit(emptyPreferences())
        } else throw e
    }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val kSnapshot = stringPreferencesKey("snapshot")
    private val kGoals = stringPreferencesKey("goals")
    private val kWaist = stringPreferencesKey("waist")
    private val kHeight = stringPreferencesKey("height")
    private val samples = kotlinx.serialization.builtins.ListSerializer(Sample.serializer())

    val snapshot: Flow<Snapshot?> = data.map { p ->
        p[kSnapshot]?.let { runCatching { json.decodeFromString(Snapshot.serializer(), it) }.getOrNull() }
    }
    val goals: Flow<Goals> = data.map { p ->
        p[kGoals]?.let { runCatching { json.decodeFromString(Goals.serializer(), it) }.getOrNull() } ?: Goals()
    }
    /** Oldest first. */
    val waist: Flow<List<Sample>> = data.map { p ->
        p[kWaist]?.let { runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Sample.serializer()), it) }.getOrNull() } ?: emptyList()
    }

    /** Manually entered height in metres (time, m), oldest first; used when Health Connect has no height. */
    val height: Flow<List<Sample>> = data.map { p ->
        p[kHeight]?.let { runCatching { json.decodeFromString(samples, it) }.getOrNull() } ?: emptyList()
    }

    suspend fun snapshotNow(): Snapshot? = snapshot.first()
    suspend fun heightNow(): List<Sample> = height.first()
    /** Latest manually entered height in metres, or null. */
    suspend fun manualHeightM(): Double? = heightNow().lastOrNull()?.value
    suspend fun goalsNow(): Goals = goals.first()
    suspend fun waistNow(): List<Sample> = waist.first()

    suspend fun saveSnapshot(s: Snapshot) {
        ds.edit { it[kSnapshot] = json.encodeToString(Snapshot.serializer(), s) }
    }

    suspend fun saveGoals(g: Goals) {
        ds.edit { it[kGoals] = json.encodeToString(Goals.serializer(), g) }
    }

    /** A manual / tape entry on a day that has an imported entry replaces the imported one (one entry per day for it). */
    suspend fun addWaist(cm: Double, time: Long = System.currentTimeMillis()) {
        ds.edit { p ->
            val cur = p[kWaist]?.let { runCatching { json.decodeFromString(samples, it) }.getOrNull() } ?: emptyList()
            val imported = p[kWaistImported]?.let { runCatching { json.decodeFromString(longs, it) }.getOrNull() }?.toSet() ?: emptySet()
            val day = dayOf(time)
            val drop = cur.filter { it.time in imported && dayOf(it.time) == day }.mapTo(HashSet()) { it.time }
            p[kWaist] = json.encodeToString(samples, (cur.filterNot { it.time in drop } + Sample(time, cm)).sortedBy { it.time })
            if (drop.isNotEmpty()) p[kWaistImported] = json.encodeToString(longs, (imported - drop).toList())
        }
    }

    suspend fun removeWaist(time: Long) {
        val list = waistNow().filterNot { it.time == time }
        ds.edit { it[kWaist] = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Sample.serializer()), list) }
    }

    /** Times of the waist entries that came from the Apple Health import (kept next to the entries, same edits). */
    private val kWaistImported = stringPreferencesKey("waist_imported")
    private val longs = kotlinx.serialization.builtins.ListSerializer(Long.serializer())

    private fun dayOf(t: Long) = java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault()).toLocalDate()

    /**
     * Apple Health import: replaces the previously imported waist entries with [entries] in ONE edit (entries and the
     * list of imported times change together, so a crash can't leave orphans). Days that already have a manual / tape
     * entry keep only that one. Returns how many imported entries were removed.
     */
    suspend fun replaceImportedWaist(entries: List<Sample>): Int {
        var removed = 0
        ds.edit { p ->
            val cur = p[kWaist]?.let { runCatching { json.decodeFromString(samples, it) }.getOrNull() } ?: emptyList()
            val old = p[kWaistImported]?.let { runCatching { json.decodeFromString(longs, it) }.getOrNull() }?.toSet() ?: emptySet()
            val kept = cur.filterNot { it.time in old }
            removed = cur.size - kept.size
            val keptDays = kept.mapTo(HashSet()) { dayOf(it.time) }
            val add = entries.filter { dayOf(it.time) !in keptDays }.distinctBy { dayOf(it.time) }
            p[kWaist] = json.encodeToString(samples, (kept + add).sortedBy { it.time })
            p[kWaistImported] = json.encodeToString(longs, add.map { it.time })
        }
        return removed
    }

    /** Height typed in by the user (cm, 100–250). */
    suspend fun setHeightCm(cm: Double, time: Long = System.currentTimeMillis()) {
        require(cm in 100.0..250.0) { "height out of range" }
        val list = (heightNow() + Sample(time, cm / 100.0)).sortedBy { it.time }
        ds.edit { it[kHeight] = json.encodeToString(samples, list) }
    }

    suspend fun removeHeight(time: Long) {
        val list = heightNow().filterNot { it.time == time }
        ds.edit { it[kHeight] = json.encodeToString(samples, list) }
    }

    // ------------------------------------------------------------------ UI preferences (see Prefs.kt)

    private val kUi = stringPreferencesKey("ui_prefs")

    /** Theme, palette, Today pill, tile layouts. Validated + defaulted (see [UiPrefs.normalized]). */
    val ui: Flow<UiPrefs> = data.map { p ->
        (p[kUi]?.let { runCatching { json.decodeFromString(UiPrefs.serializer(), it) }.getOrNull() } ?: UiPrefs()).normalized()
    }

    suspend fun uiNow(): UiPrefs = ui.first()

    /** Atomic read-modify-write of the UI preferences. */
    suspend fun updateUi(transform: (UiPrefs) -> UiPrefs) {
        ds.edit { p ->
            val cur = (p[kUi]?.let { runCatching { json.decodeFromString(UiPrefs.serializer(), it) }.getOrNull() } ?: UiPrefs()).normalized()
            p[kUi] = json.encodeToString(UiPrefs.serializer(), transform(cur).normalized())
        }
    }

    /** v6: weekly heart points target back to the default (150). */
    suspend fun resetWeeklyHeartPoints() = updateGoals { it.copy(zoneMinWeek = Goals.DEFAULT_WEEKLY_HEART_POINTS) }

    /** Atomic read-modify-write of the goals (birth year lives in [Goals] because the zone maths reads it). */
    suspend fun updateGoals(transform: (Goals) -> Goals) {
        ds.edit { p ->
            val cur = p[kGoals]?.let { runCatching { json.decodeFromString(Goals.serializer(), it) }.getOrNull() } ?: Goals()
            p[kGoals] = json.encodeToString(Goals.serializer(), transform(cur))
        }
    }
}
