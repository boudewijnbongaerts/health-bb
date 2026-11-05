package com.boudhealth.data.importer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import androidx.core.app.NotificationCompat
import androidx.health.connect.client.records.ExerciseSessionRecord as E
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.boudhealth.App
import com.boudhealth.data.Cache
import com.boudhealth.data.CachedWorkout
import com.boudhealth.data.Derive
import com.boudhealth.data.ErrorLog
import com.boudhealth.data.Sample
import com.boudhealth.data.Sync
import com.boudhealth.data.SyncLog
import com.boudhealth.work.Refresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Apple Health import on Android: SAF uri → (zip →) export.xml → XmlPullParser → [AppleAggregator] → import tables.
 * Runs as an expedited WorkManager job in the foreground (notification with progress and Cancel), so it survives the
 * screen turning off. The file is streamed, never loaded into memory or unpacked to disk.
 */
object AppleImport {
    const val WORK = "healthboud-apple-import"
    /** The app's one notification channel (also used by the Google Health "Reconnect" notification). */
    internal const val CHANNEL = "import"
    const val NOTIFICATION_ID = 4711

    data class Progress(
        val running: Boolean = false,
        val phase: String = "",
        /** Bytes of the picked file read so far (compressed bytes for a .zip) and its size (-1 = unknown). */
        val bytesRead: Long = 0,
        val bytesTotal: Long = -1,
        /** Apple type (short name) → records used so far. */
        val perType: Map<String, Int> = emptyMap(),
        val error: String? = null,
        val finishedAt: Long? = null,
        val cancelled: Boolean = false,
    ) {
        val fraction: Float? get() = if (bytesTotal > 0) (bytesRead.toFloat() / bytesTotal).coerceIn(0f, 1f) else null
        val records: Int get() = perType.values.sum()
    }

    private val _state = MutableStateFlow(Progress())
    val state: StateFlow<Progress> = _state.asStateFlow()

    /** Last import, from the import tables (survives app restarts). */
    data class Summary(
        val importedAt: Long?, val fileName: String?, val oldest: LocalDate?, val newest: LocalDate?,
        /** Short Apple type → records used. */
        val perType: Map<String, Int>,
        /** Cache metric → days stored. */
        val days: Map<String, Int>,
        val workouts: Int,
        val skipped: Int,
        /** "multi-source hours; third-party hours dropped; body copies; workouts merged; in-bed-only nights". */
        val duplicates: String? = null,
        /** v4.1: type label → (days shown from Apple, days shown from Health Connect), per-day choice ([ImportMerge]). */
        val sources: List<Triple<String, Int, Int>> = emptyList(),
    )

    /** Starts the import of a picked file (.zip or .xml). Call from the UI right after ACTION_OPEN_DOCUMENT. */
    fun start(context: Context, uri: Uri) {
        val app = context.applicationContext
        try {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // not persistable (some providers): the grant of this session is enough while the app process lives
        }
        _state.value = Progress(running = true, phase = "Starting…")
        val req = OneTimeWorkRequestBuilder<AppleImportWorker>()
            .setInputData(workDataOf("uri" to uri.toString()))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK)
        _state.update { if (it.running) it.copy(running = false, cancelled = true, phase = "Cancelled") else it }
    }

    /** "Remove import": deletes the imported data (and the imported waist entries), keeps Health Connect data. */
    suspend fun remove(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        SyncLog.init(app)
        val cache = Cache.get(app)
        val range = cache.importHrDayRange()
        // waist first (the list of imported entries lives in the Store and is updated in the same edit), then the tables
        val removed = (app as App).store.replaceImportedWaist(emptyList())
        cache.clearImport()
        // Health Connect workouts that took their heart rate from imported days: from Health Connect's own minutes again
        range?.let { (a, b) -> Derive.refreshHcWorkoutsHr(cache, app.store.goalsNow(), a, b) }
        _state.value = Progress()
        SyncLog.i("import: removed (cache import tables and $removed imported waist entries)")
        Sync.dataChanged()
        Refresh.publish(app)
    }

    fun summary(context: Context): Summary? {
        val cache = Cache.get(context.applicationContext)
        val at = cache.importMeta(K_AT)?.toLongOrNull() ?: return null
        val per = cache.importMeta(K_TYPES)?.split(';')?.filter { it.contains('=') }?.associate { it.substringBefore('=') to (it.substringAfter('=').toIntOrNull() ?: 0) } ?: emptyMap()
        return Summary(
            at, cache.importMeta(K_FILE), cache.importMeta(K_OLDEST)?.let(LocalDate::parse), cache.importMeta(K_NEWEST)?.let(LocalDate::parse),
            per, cache.importStats().associate { it.first to it.second }, cache.importWorkoutCount(), cache.importMeta(K_SKIPPED)?.toIntOrNull() ?: 0,
            cache.importMeta(K_DUPES)?.split(';')?.takeIf { it.size == 5 }?.let {
                "Duplicates removed: ${it[0]} hours counted by both iPhone and Watch (one kept), ${it[1]} hours of calories re-written by other apps, " +
                    "${it[2]} copied weigh-ins, ${it[3]} workouts recorded twice; ${it[4]} nights with only in-bed time not shown as sleep."
            },
            cache.sourceDays().map { (k, v) -> Triple(ImportMerge.keyLabel(k), v.first, v.second) },
        )
    }

    // ------------------------------------------------------------------ the import itself (worker thread)

    private const val K_AT = "imported_at"
    private const val K_FILE = "file"
    private const val K_OLDEST = "oldest"
    private const val K_NEWEST = "newest"
    private const val K_TYPES = "types"
    private const val K_SKIPPED = "skipped"
    private const val K_DUPES = "dupes"

    fun shortType(t: String) = t.removePrefix("HKQuantityTypeIdentifier").removePrefix("HKCategoryTypeIdentifier")

    /** Counts bytes read from the picked file (progress = bytes / file size). */
    private class Counting(input: InputStream, val onRead: (Long) -> Unit) : FilterInputStream(input) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) { count++; onRead(count) } }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) { count += it; onRead(count) } }
    }

    /** Android XmlPullParser → [XmlSource]. The DOCTYPE internal subset of export.xml is skipped, not processed. */
    private class PullSource(input: InputStream) : XmlSource {
        private val p: XmlPullParser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, "UTF-8")
        }
        override var name: String = ""
        override fun next(): Int {
            while (true) {
                when (p.next()) {
                    XmlPullParser.START_TAG -> { name = p.getName() ?: ""; return XmlSource.START }
                    XmlPullParser.END_TAG -> { name = p.getName() ?: ""; return XmlSource.END }
                    XmlPullParser.END_DOCUMENT -> return XmlSource.EOF
                }
            }
        }
        override fun attr(name: String): String? = p.getAttributeValue(null, name)
    }

    /** Opens the picked file: a zip is streamed entry by entry up to apple_health_export/export.xml (not export_cda.xml). */
    private fun openXml(raw: InputStream): InputStream {
        val buf = BufferedInputStream(raw, 1 shl 16)
        buf.mark(4)
        val magic = ByteArray(4)
        val n = buf.read(magic)
        buf.reset()
        val isZip = n == 4 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()
        if (!isZip) return Doctype.strip(buf)
        val zin = ZipInputStream(buf)
        while (true) {
            val e = zin.nextEntry ?: error("No export.xml in this zip. Pick the export.zip from the Health app (Profile → Export All Health Data).")
            val name = e.name.substringAfterLast('/')
            if (!e.isDirectory && name == "export.xml") return Doctype.strip(BufferedInputStream(zin, 1 shl 16))
        }
    }

    internal suspend fun run(context: Context, uri: Uri, stopped: () -> Boolean, notify: suspend (Progress) -> Unit): Boolean {
        val app = context.applicationContext
        SyncLog.init(app); ErrorLog.init(app)
        val cr = app.contentResolver
        var fileName: String? = null
        var size = -1L
        try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { fileName = c.getString(0); if (!c.isNull(1)) size = c.getLong(1) }
            }
        } catch (e: Exception) { /* name / size are only for display */ }
        SyncLog.i("import: start ${fileName ?: uri} (${if (size > 0) "${size / 1_048_576} MB" else "size unknown"})")
        val t0 = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val agg = AppleAggregator(zone)
        var bytes = 0L
        var lastUi = 0L
        fun publish(phase: String) {
            _state.value = Progress(true, phase, bytes, size, agg.stats.mapKeys { shortType(it.key) }.mapValues { it.value.used })
        }
        _state.value = Progress(true, "Reading ${fileName ?: "file"}…", 0, size)
        val ok = withContext(Dispatchers.IO) {
            val raw = cr.openInputStream(uri) ?: error("Can't open the file")
            Counting(raw) { bytes = it }.use { counting ->
                val parser = AppleParser(PullSource(openXml(counting)), agg::add, agg::add, agg::onBadDate) {
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 400) { lastUi = now; publish("Reading… ${if (size > 0) "${bytes * 100 / size} %" else "${bytes / 1_048_576} MB"}") }
                    stopped()
                }
                val done = parser.run()
                SyncLog.i("import: parsed ${parser.elements} elements, ${agg.ignored} records of other types ignored, ${System.currentTimeMillis() - t0} ms, ${if (done) "complete" else "CANCELLED"}")
                done
            }
        }
        if (!ok) {
            _state.value = Progress(false, "Cancelled", bytes, size, cancelled = true)
            SyncLog.i("import: cancelled, nothing written (the previous import, if any, is kept)")
            return false
        }
        publish("Saving…")
        notify(_state.value)
        val res = agg.result(0)
        withContext(Dispatchers.IO) {
            val cache = Cache.get(app)
            val workouts = res.workouts.map { w ->
                val (type, title) = exerciseType(w.appleType)
                CachedWorkout(
                    id = w.id, start = w.start, end = w.end, type = type, title = title, pkg = if (w.approxHr) IMPORT_SOURCE_APPROX else IMPORT_SOURCE,
                    kcal = w.kcal, avgHr = null, zoneMin = null, kcalSource = if (w.kcal != null) IMPORT_SOURCE else null, enriched = true,
                )
            }
            val skipped = res.stats.values.sumOf { it.skipped.values.sum() }
            val oldRange = cache.importHrDayRange()
            val waist = res.waist.map { Sample(it.time, it.cm) }
            val meta = mapOf(
                K_AT to System.currentTimeMillis().toString(),
                K_FILE to (fileName ?: uri.lastPathSegment ?: "export"),
                K_TYPES to res.stats.entries.joinToString(";") { "${shortType(it.key)}=${it.value.used}" },
                K_SKIPPED to skipped.toString(),
                K_DUPES to "${agg.multiSourceHours};${agg.thirdPartyHoursDropped};${agg.copiesDropped.values.sum()};${agg.workoutsMerged};${agg.inBedOnlyNights}",
            ) + listOfNotNull(res.oldest?.let { K_OLDEST to it.toString() }, res.newest?.let { K_NEWEST to it.toString() })
            cache.replaceImport(res.rows, res.hrDays, workouts, meta, res.sleepStages)
            (app as App).store.replaceImportedWaist(waist)
            // zone minutes per day and workout HR / zone minutes from the imported per-minute heart rate (local), and the
            // Health Connect workouts whose days now take their heart rate from the import (or no longer do)
            val goals = app.store.goalsNow()
            Derive.recomputeImportZones(cache, goals, zone)
            val a = listOfNotNull(oldRange?.first, res.hrDays.firstOrNull()?.day).minOrNull()
            val b = listOfNotNull(oldRange?.second, res.hrDays.lastOrNull()?.day).maxOrNull()
            if (a != null && b != null) Derive.refreshHcWorkoutsHr(cache, goals, a, b, zone)
            for ((t, st) in res.stats.entries.sortedBy { it.key }) {
                SyncLog.i("import: ${shortType(t)} read ${st.read}, used ${st.used}, ${st.oldest ?: "-"} → ${st.newest ?: "-"}" +
                    if (st.skipped.isEmpty()) "" else ", skipped ${st.skipped.entries.joinToString { "${it.value} ${it.key}" }}")
            }
            SyncLog.i("import: duplicates handled: ${agg.multiSourceHours} hours with several sources (one kept), " +
                "${agg.thirdPartyHoursDropped} third-party-only hours on Apple days dropped, body copies dropped ${agg.copiesDropped}, " +
                "${agg.workoutsMerged} duplicate workouts merged, ${agg.inBedOnlyNights} in-bed-only nights not shown as sleep")
            SyncLog.i("import: stored ${res.rows.size} day rows, ${res.hrDays.size} heart-rate days, ${workouts.size} workouts, ${waist.size} waist entries, " +
                "${res.oldest} → ${res.newest}, ${System.currentTimeMillis() - t0} ms")
        }
        _state.value = Progress(false, "Done", size.coerceAtLeast(bytes), size, agg.stats.mapKeys { shortType(it.key) }.mapValues { it.value.used }, finishedAt = System.currentTimeMillis())
        Sync.dataChanged()
        Refresh.publish(app)
        return true
    }

    internal fun fail(e: Throwable) {
        val kind = if (e is Exception) ErrorLog.record("import", e) else null
        SyncLog.w("import failed${kind?.let { " ($it)" } ?: ""}", e)
        _state.update { it.copy(running = false, error = "${e.javaClass.simpleName}: ${e.message ?: ""}".take(200), phase = "Failed") }
    }

    // ------------------------------------------------------------------ notification

    /** Creates [CHANNEL] if needed (idempotent). */
    internal fun ensureChannel(nm: NotificationManager?) {
        nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Apple Health import", NotificationManager.IMPORTANCE_LOW))
    }

    internal fun foregroundInfo(context: Context, p: Progress, cancel: android.app.PendingIntent?): ForegroundInfo {
        val nm = context.getSystemService(NotificationManager::class.java)
        ensureChannel(nm)
        val pct = ((p.fraction ?: 0f) * 100).toInt()
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Importing Apple Health")
            .setContentText(p.phase.ifBlank { "Reading…" } + if (p.records > 0) " · ${p.records} records" else "")
            .setProgress(100, pct, p.fraction == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
        if (cancel != null) b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel)
        return ForegroundInfo(NOTIFICATION_ID, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    // ------------------------------------------------------------------ Apple workout type → Health Connect type

    /** (Health Connect exercise type, title for types without their own name). */
    fun exerciseType(apple: String): Pair<Int, String?> {
        val short = apple.removePrefix("HKWorkoutActivityType")
        val t = when (short) {
            "Running" -> E.EXERCISE_TYPE_RUNNING
            "Walking" -> E.EXERCISE_TYPE_WALKING
            "Hiking" -> E.EXERCISE_TYPE_HIKING
            "Cycling" -> E.EXERCISE_TYPE_BIKING
            "HandCycling" -> E.EXERCISE_TYPE_BIKING
            "Swimming" -> E.EXERCISE_TYPE_SWIMMING_POOL
            "TraditionalStrengthTraining" -> E.EXERCISE_TYPE_STRENGTH_TRAINING
            "FunctionalStrengthTraining" -> E.EXERCISE_TYPE_STRENGTH_TRAINING
            "HighIntensityIntervalTraining" -> E.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING
            "Yoga" -> E.EXERCISE_TYPE_YOGA
            "Pilates" -> E.EXERCISE_TYPE_PILATES
            "Elliptical" -> E.EXERCISE_TYPE_ELLIPTICAL
            "Rowing" -> E.EXERCISE_TYPE_ROWING_MACHINE
            "StairClimbing" -> E.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE
            "Stairs" -> E.EXERCISE_TYPE_STAIR_CLIMBING
            "Tennis" -> E.EXERCISE_TYPE_TENNIS
            "Badminton" -> E.EXERCISE_TYPE_BADMINTON
            "Soccer" -> E.EXERCISE_TYPE_SOCCER
            "Dance", "SocialDance", "CardioDance", "DanceInspiredTraining" -> E.EXERCISE_TYPE_DANCING
            "Flexibility", "Cooldown", "PreparationAndRecovery" -> E.EXERCISE_TYPE_STRETCHING
            else -> E.EXERCISE_TYPE_OTHER_WORKOUT
        }
        val title = when {
            short == "FunctionalStrengthTraining" -> "Functional strength"
            t == E.EXERCISE_TYPE_OTHER_WORKOUT && short != "Other" -> short.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").lowercase().replaceFirstChar { it.uppercase() }
            else -> null
        }
        return t to title
    }
}

/** Expedited, foreground (dataSync) job: keeps running with the screen off; Cancel in the notification stops it. */
class AppleImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo =
        AppleImport.foregroundInfo(applicationContext, AppleImport.state.value, WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))

    override suspend fun doWork(): Result {
        val uri = inputData.getString("uri")?.let(Uri::parse) ?: return Result.failure()
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        try {
            setForeground(AppleImport.foregroundInfo(applicationContext, AppleImport.state.value, cancel))
        } catch (e: Exception) {
            // foreground not allowed right now (e.g. started from the background): run anyway while the app is open
            SyncLog.w("import: could not start in the foreground", e)
        }
        return try {
            val ok = coroutineScope {
                // notification progress, at most every 3 s, while the parse runs on the IO dispatcher
                val ui = launch {
                    AppleImport.state.collect { p ->
                        runCatching { setForeground(AppleImport.foregroundInfo(applicationContext, p, cancel)) }
                        delay(3_000)
                    }
                }
                try {
                    AppleImport.run(applicationContext, uri, { isStopped }) {}
                } finally {
                    ui.cancel()
                }
            }
            if (ok) Result.success() else Result.failure()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (e is Error && e !is OutOfMemoryError) throw e
            AppleImport.fail(e)
            Result.failure()
        }
    }
}
