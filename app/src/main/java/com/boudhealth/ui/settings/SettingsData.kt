package com.boudhealth.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import com.boudhealth.data.Cache
import com.boudhealth.data.Diagnostics
import com.boudhealth.data.HcReader
import com.boudhealth.data.Local
import com.boudhealth.data.M

import com.boudhealth.data.TypeStats
import com.boudhealth.ui.components.Fmt
import com.boudhealth.ui.components.Glyph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/*
 * Small read-only helpers for Settings / Data check. They only call existing backend APIs
 * (HcReader, Cache.daily, Local.workouts, Sources) and Android intents.
 */

/** Opening Health Connect screens. Every intent falls back to the next one if the device doesn't handle it. */
object HcIntents {
    private const val MANAGE_APP_PERMISSIONS = "android.health.connect.action.MANAGE_HEALTH_PERMISSIONS" // API 34+
    private const val HC_SETTINGS = "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"
    private const val HC_HOME = "android.health.connect.action.HEALTH_HOME_SETTINGS"

    /** The permission screen of this app inside Health Connect ("Fix" buttons). */
    fun openAppPermissions(context: Context) = start(
        context,
        Intent(MANAGE_APP_PERMISSIONS).putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName),
        Intent(HC_SETTINGS), Intent(HC_HOME),
    )

    /** Health Connect's main settings. */
    fun openHealthConnect(context: Context) = start(context, Intent(HC_SETTINGS), Intent(HC_HOME),
        Intent(MANAGE_APP_PERMISSIONS).putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName))

    private fun start(context: Context, vararg intents: Intent): Boolean {
        for (i in intents) {
            try {
                context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: ActivityNotFoundException) {
                continue
            } catch (e: SecurityException) {
                continue
            }
        }
        return false
    }
}

/** Granted Health Connect permissions, or null if Health Connect can't be asked. */
suspend fun grantedPermissions(context: Context): Set<String>? = withContext(Dispatchers.IO) {
    if (!HcReader.available(context)) return@withContext null
    runCatching { HcReader(context).grantedPermissions() }.getOrNull()
}

fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

fun appVersion(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "–"

/** V2-130: "Version 2.0 (10)" from the installed package (versionName + versionCode). */
fun appVersionLine(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    "Version ${info.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)})"
}.getOrNull() ?: "Version –"

// =====================================================================================================================
// Data types: names, glyphs, 30-day coverage
// =====================================================================================================================

/** UI name, glyph and colour key for a Health Connect record type name (TypeStats.type). */
data class TypeInfo(val name: String, val glyph: Glyph, val colorKey: String, val body: Boolean, val totals: Boolean, val sparse: Boolean)

fun typeInfo(type: String): TypeInfo = when (type) {
    "Steps" -> TypeInfo("Steps", Glyph.Steps, "steps", false, true, false)
    "TotalCaloriesBurned" -> TypeInfo("Calories burned", Glyph.Flame, "calories", false, true, false)
    "ActiveCaloriesBurned" -> TypeInfo("Calories burned", Glyph.Flame, "calories", false, true, false)
    "ExerciseSession" -> TypeInfo("Workouts", Glyph.Dumbbell, "exercise_min", false, false, true)
    "HeartRate" -> TypeInfo("Heart rate", Glyph.Heart, "hr", false, false, false)
    "RestingHeartRate" -> TypeInfo("Resting heart rate", Glyph.Heart, "rhr", false, false, false)
    "HeartRateVariabilityRmssd" -> TypeInfo("HRV", Glyph.Hrv, "hrv", false, false, false)
    "Vo2Max" -> TypeInfo("VO₂ max", Glyph.Run, "vo2", false, false, true)
    "OxygenSaturation" -> TypeInfo("Blood oxygen", Glyph.Drop, "spo2", false, false, true)
    "RespiratoryRate" -> TypeInfo("Breathing rate", Glyph.Lungs, "breathing", false, false, false)
    "Weight" -> TypeInfo("Weight", Glyph.Scale, "weight", true, false, false)
    "BodyFat" -> TypeInfo("Body fat percentage", Glyph.Percent, "body_fat", true, false, false)
    "LeanBodyMass" -> TypeInfo("Lean mass", Glyph.Layers, "lean_mass", true, false, true)
    "Height" -> TypeInfo("Height", Glyph.User, "height", true, false, true)
    "SleepSession" -> TypeInfo("Sleep", Glyph.Moon, "sleep", false, false, false)
    "Waist" -> TypeInfo("Waist circumference", Glyph.Tape, "waist", true, false, true)
    else -> TypeInfo(readableType(type), Glyph.Info, "", false, false, true)
}

/** "BasalMetabolicRate" → "Basal metabolic rate": never show a raw record id. */
fun readableType(type: String): String {
    val words = type.removeSuffix("Record").replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return "Other data"
    return words.mapIndexed { i, w -> if (i == 0) w.replaceFirstChar { it.uppercase() } else if (w.length > 1 && w.all { it.isUpperCase() }) w else w.lowercase() }.joinToString(" ")
}

/** Readable, short source name for the Data check rows ("Phone" for the phone's own counter, never an id). */
fun shortSource(label: String): String? = when {
    label.isBlank() || label == "Unknown app" -> null
    label == com.boudhealth.data.SourceNames.PHONE -> "Phone"
    else -> label
}

/** Metric id of the latest value shown on each Data check row. */
fun lastValueMetric(type: String): String? = when (type) {
    "Steps" -> "steps"
    "HeartRate" -> "hr"
    "TotalCaloriesBurned", "ActiveCaloriesBurned" -> "calories"
    "ExerciseSession" -> "last_workout"
    "SleepSession" -> "sleep"
    "Weight" -> "weight"
    "BodyFat" -> "body_fat"
    "LeanBodyMass" -> "lean_mass"
    "RestingHeartRate" -> "rhr"
    "HeartRateVariabilityRmssd" -> "hrv"
    "RespiratoryRate" -> "breathing"
    "Vo2Max" -> "vo2"
    "OxygenSaturation" -> "spo2"
    "Height" -> "height"
    "Waist" -> "waist"
    else -> null
}

/** Last value + its time for one Data check row ("7,240" · ms), formatted with the metric's own unit. */
data class LastValue(val text: String, val time: Long)

fun lastValue(env: com.boudhealth.data.MetricEnv, type: String): LastValue? {
    val id = lastValueMetric(type) ?: return null
    val m = com.boudhealth.data.Metrics.find(id) ?: return null
    val r = com.boudhealth.data.Metrics.latest(env, id) ?: return null
    val text = when (id) {
        "steps" -> Fmt.int(r.value)
        "sleep" -> Fmt.duration(Math.round(r.value * 60).toInt())
        "last_workout" -> listOfNotNull(r.text, "${Math.round(r.value)} min").joinToString(" ")
        "height" -> "${Math.round(r.value * 100)} cm"
        else -> Fmt.num(r.value, m.decimals) + if (m.unit.isNotBlank()) " ${m.unit}" else ""
    }
    return LastValue(text, r.time)
}

/**
 * Workouts per app in [from]..[to], each real session counted once: an app that writes the same session several
 * times (Withings writes copies) counts 1. Same overlap rule as the workout de-duplication, applied within one app.
 */
fun workoutCountsPerApp(cache: com.boudhealth.data.CacheReads, from: LocalDate, to: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Map<String, Int> {
    val rows = cache.workouts(from.atStartOfDay(zone).toInstant().toEpochMilli(), to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
    return rows.groupBy { it.pkg }.mapValues { (pkg, ws) ->
        val spans = ws.map { com.boudhealth.data.WorkoutSpan(it.id, pkg, it.start, it.end, it.type != 0 || !it.title.isNullOrBlank()) }
        com.boudhealth.data.SyncLogic.clusterWorkouts(spans, { 0 }).size
    }
}

/** Order on the screen: what people look at most first. */
val TYPE_ORDER = listOf(
    "Steps", "HeartRate", "ActiveCaloriesBurned", "TotalCaloriesBurned", "ExerciseSession", "SleepSession", "Weight", "BodyFat", "Waist",
    "RestingHeartRate", "HeartRateVariabilityRmssd", "RespiratoryRate", "Vo2Max", "OxygenSaturation", "LeanBodyMass", "Height",
)

/** Days with data in the last 30 days per type (from the cache; no Health Connect calls). null = not tracked per day. */
suspend fun loadCoverage(context: Context): Map<String, Set<LocalDate>> = withContext(Dispatchers.IO) {
    runCatching {
        val cache = Cache.get(context.applicationContext)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val from = today.minusDays(29)
        fun days(metric: String) = cache.daily(metric, from, today).map { it.day }.toSet()
        mapOf(
            "Steps" to days(M.STEPS),
            "TotalCaloriesBurned" to days(M.KCAL),
            "ActiveCaloriesBurned" to days(M.KCAL),
            "HeartRate" to days(M.HR_AVG),
            "RestingHeartRate" to days(M.RHR),
            "HeartRateVariabilityRmssd" to days(M.HRV),
            "Vo2Max" to days(M.VO2),
            "OxygenSaturation" to days(M.SPO2),
            "RespiratoryRate" to days(M.RESP),
            "Weight" to days(M.WEIGHT),
            "BodyFat" to days(M.FAT_PCT),
            "LeanBodyMass" to days(M.LEAN_KG),
            "Height" to days(M.HEIGHT),
            "SleepSession" to days(M.SLEEP_MIN),
            "Waist" to days(M.WAIST),
            "ExerciseSession" to Local.workouts(cache, from, today).map { Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() }.toSet(),
        )
    }.getOrDefault(emptyMap())
}

/** A stale type: data used to arrive, but nothing new for a week. Returns the sentence or null. */
fun staleSentence(t: TypeStats, now: Long = System.currentTimeMillis()): String? {
    if (!t.permissionGranted) return null
    val info = typeInfo(t.type)
    if (info.sparse) return null
    val newest = t.newest ?: return null
    val days = ChronoUnit.DAYS.between(Fmt.date(newest), LocalDate.now())
    if (days < 7) return null
    if ((t.countAll ?: t.count30d ?: 0) < 3) return null
    val from = t.sources.maxByOrNull { it.newest ?: 0L }?.label
    return "Nothing new for $days days · last${if (from != null) " from $from" else ""} on ${Fmt.shortDate(Fmt.date(newest))}"
}

/** Issues the hero counts: permission problems + stale types. */
fun issueCount(d: Diagnostics): Int {
    val perm = d.permissions
    var n = 0
    if (d.types.any { !it.permissionGranted }) n++
    if (d.historyFeature && perm.any { it.permission == HcReader.HISTORY && !it.granted }) n++
    if (d.backgroundFeature && perm.any { it.permission == HcReader.BACKGROUND && !it.granted }) n++
    n += d.types.count { staleSentence(it) != null }
    return n
}

/** Plain-text report for support: counts, dates, sources, permissions, last error; no health values. */
fun buildReport(d: Diagnostics, version: String): String = buildString {
    appendLine("Health BB data check · version $version")
    appendLine("Generated: ${Fmt.dateTime(d.generatedAt)}")
    appendLine("PAST DATA permission (runtime): ${if (d.permissions.any { it.permission == HcReader.HISTORY && it.granted }) "granted" else "NOT GRANTED"}")
    appendLine("Health Connect available: ${d.healthConnectAvailable} · past-data feature: ${d.historyFeature} · background feature: ${d.backgroundFeature}")
    appendLine("Last sync: ${d.lastSync?.let { Fmt.dateTime(it).toString() } ?: "never"} · running: ${d.syncState.running} ${d.syncState.phase}")
    d.backfill?.let { appendLine("History: cursor ${it.cursor}, floor ${it.floor}, finished ${it.finishedAt?.let { f -> Fmt.dateTime(f).toString() } ?: "no"}, chunks ${it.chunks}, past-data at start ${it.history}") }
    // DATA FIX (data team): per-group history jobs + per-type cache coverage, so a report shows where history stops
    d.backfill?.jobs?.values?.forEach { j ->
        appendLine("  job ${j.group}: floor ${j.floor ?: "none"}${if (j.floorGuessed) " (guessed)" else ""}, cursor ${j.cursor}, done ${j.done}, chunk ${j.chunkDays} d, ${j.chunks} chunks, ${j.records} records, ${j.days} days")
        if (j.lastChunk.isNotBlank()) appendLine("      last: ${j.lastChunk}")
        j.lastError?.let { appendLine("      error: $it") }
    }
    appendLine()
    appendLine("Permissions:")
    d.permissions.forEach { appendLine("  ${it.label}: ${if (it.granted) "granted" else "OFF"}${if (it.required) " (required)" else ""}") }
    appendLine()
    appendLine("Data types (records last 30 d / all · oldest · newest):")
    d.types.forEach { t ->
        appendLine("  ${t.type}: ${t.count30d ?: "-"} / ${t.countAll ?: "-"} · ${t.oldest ?: "-"} · ${t.newest?.let { Fmt.dateTime(it).toString() } ?: "-"}${if (!t.permissionGranted) " · NO PERMISSION" else ""}")
        t.sources.forEach { s -> appendLine("      ${s.label} (${s.pkg}): ${s.count30d ?: "-"} / ${s.countAll ?: "-"}") }
    }
    appendLine()
    appendLine("Sync steps:")
    d.steps.forEach { appendLine("  ${it.step}: ${if (it.ok) "ok" else "FAILED"} ${it.ms} ms, ${it.calls} calls ${it.detail}") }
    appendLine()
    appendLine("Cache: ${d.cache.sizeBytes / 1024} KB, ${d.cache.workouts} workouts, HR days ${d.cache.heartRateDays?.let { "${it.first}…${it.second}" } ?: "-"}")
    // DATA FIX (data team): cached rows per type vs. what Health Connect reports above
    d.coverage.forEach { c ->
        c.progress?.let { appendLine("  ${c.type} progress: $it") }
        appendLine("  ${c.type}: ${c.state} · HC oldest ${c.hcOldest ?: "-"} / newest ${c.hcNewest ?: "-"} / ${c.hcCount30 ?: "-"} in 30 d · cached ${c.rows} ${if (c.type == "ExerciseSession") "sessions" else "days"} ${c.oldest ?: "-"} → ${c.newest ?: "-"}")
    }
    appendLine()
    appendLine("Findings:")
    d.findings.forEach { appendLine("  - $it") }
    d.errors.firstOrNull()?.let { e ->
        appendLine()
        appendLine("Last error: ${Fmt.dateTime(e.time)} ${e.step} ${e.kind} ${e.type}: ${e.message}")
    }
    // DATA FIX (data team): recent errors + sync log (counts, ranges, timings; no health values)
    if (d.errors.size > 1) {
        appendLine("Other recent errors:")
        d.errors.drop(1).take(10).forEach { e -> appendLine("  ${Fmt.dateTime(e.time)} ${e.step} ${e.kind} ${e.type}: ${e.message.take(160)}") }
    }
    if (d.syncLog.isNotEmpty()) {
        appendLine()
        appendLine("Sync log (HB.sync, last ${d.syncLog.size} lines):")
        d.syncLog.forEach { appendLine("  $it") }
    }
}
