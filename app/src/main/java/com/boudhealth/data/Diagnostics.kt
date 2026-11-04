package com.boudhealth.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass

data class PermissionStatus(
    val permission: String,
    /** Short name, e.g. "READ_HEART_RATE". */
    val label: String,
    val granted: Boolean,
    /** Blocks the dashboard when missing (read types, and past data where supported). */
    val required: Boolean,
    val note: String? = null,
)

data class SourceStats(
    val pkg: String,
    val label: String,
    val count30d: Int?,
    /** null = not counted (steps / calories are read as totals, not record by record). */
    val countAll: Int?,
    val oldest: LocalDate?,
    val newest: Long?,
)

data class TypeStats(
    /** "HeartRate", "Weight", … */
    val type: String,
    val group: Group,
    val permissionGranted: Boolean,
    val count30d: Int?,
    val countAll: Int?,
    /** Oldest / newest record Health Connect returns right now (live), else from the cache. */
    val oldest: LocalDate?,
    val newest: Long?,
    val sources: List<SourceStats>,
    val note: String? = null,
)

data class MetricCoverage(val metric: String, val days: Int, val oldest: LocalDate, val newest: LocalDate)

data class CacheStats(val sizeBytes: Long, val metrics: List<MetricCoverage>, val workouts: Int, val heartRateDays: Pair<LocalDate, LocalDate>?)

/**
 * What the cache holds for one Health Connect type (from [DiagnosticsReader]): rows (days, or sessions for workouts),
 * oldest / newest cached day, and the history job of its group. Counts and dates only, never values.
 */
data class TypeCoverage(
    val type: String,
    /** Cached rows: days (sessions for workouts). */
    val rows: Int,
    /** Oldest / newest day stored in the cache. */
    val oldest: LocalDate?,
    val newest: LocalDate?,
    val job: JobProgress?,
    /** Oldest / newest record Health Connect returns to this app right now (live check; null = not checked / none). */
    val hcOldest: LocalDate? = null,
    val hcNewest: LocalDate? = null,
    /** Records in Health Connect over the last 30 days (live; null = not counted). */
    val hcCount30: Int? = null,
    val permission: Boolean = true,
    /** "pending" / "running" / "done" / "failed" / "no data" / "no permission". */
    val state: String = "pending",
    /** Realistic remaining work for a history job still reading (percent, days, calls, minutes incl. quota pauses). */
    val progress: String? = null,
)

/** Everything the "Data check" screen shows. [findings] = plain-language problems, most important first. */
data class Diagnostics(
    val generatedAt: Long,
    val healthConnectAvailable: Boolean,
    val historyFeature: Boolean,
    val backgroundFeature: Boolean,
    val permissions: List<PermissionStatus>,
    val lastSync: Long?,
    val syncState: SyncState,
    val backfill: BackfillState?,
    val steps: List<StepResult>,
    val types: List<TypeStats>,
    val cache: CacheStats,
    val errors: List<LoggedError>,
    val findings: List<String>,
    /** Per type: what is cached + history job state (also in each TypeStats.note and in the copied report). */
    val coverage: List<TypeCoverage> = emptyList(),
    /** Last lines of the sync log (counts / dates only). */
    val syncLog: List<String> = emptyList(),
)

object DiagnosticsReader {

    private val AGGREGATE_ONLY: Set<KClass<out Record>> = setOf(StepsRecord::class, TotalCaloriesBurnedRecord::class, ActiveCaloriesBurnedRecord::class)
    /** Types without their own cached rows to compare with Health Connect's oldest record. */
    private val AGGREGATE_ONLY_RAW: Set<KClass<out Record>> = setOf(ActiveCaloriesBurnedRecord::class)

    /** Cached metric (daily table) behind each record type; workouts and heart rate are counted separately. */
    private fun metricOf(type: String): String? = when (type) {
        "Steps" -> M.STEPS
        "TotalCaloriesBurned" -> M.KCAL
        "RestingHeartRate" -> M.RHR
        "HeartRateVariabilityRmssd" -> M.HRV
        "RespiratoryRate" -> M.RESP
        "OxygenSaturation" -> M.SPO2
        "Vo2Max" -> M.VO2
        "Weight" -> M.WEIGHT
        "BodyFat" -> M.FAT_PCT
        "LeanBodyMass" -> M.LEAN_KG
        "Height" -> M.HEIGHT
        "SleepSession" -> M.SLEEP_MIN
        else -> null
    }

    private fun coverageOf(cache: Cache, stats: Map<String, Triple<String, Int, Pair<LocalDate, LocalDate>>>, t: KClass<out Record>, bf: BackfillState?, zone: ZoneId): TypeCoverage {
        val name = typeName(t)
        val job = bf?.jobs?.get(Group.of(t).name)
        return when (name) {
            "ExerciseSession" -> {
                val (n, a, b) = cache.workoutRange()
                TypeCoverage(name, n, a?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }, b?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }, job)
            }
            "HeartRate" -> cache.hrDayRange().let { r -> TypeCoverage(name, cache.hrDayCount(), r?.first, r?.second, job) }
            else -> {
                val m = metricOf(name) ?: return TypeCoverage(name, 0, null, null, job)
                val st = stats[m]
                TypeCoverage(name, st?.second ?: 0, st?.third?.first, st?.third?.second, job)
            }
        }
    }

    /**
     * "62 % · 2,140 days left · ~1,070 calls · ~25 min (incl. 1 quota pause)" for a job still reading. The stop is the
     * probed oldest record (the job's floor is 2000 but empty years are jumped). Heart rate uses its mode's calls per day.
     */
    fun progressText(j: JobProgress, type: String, oldestKnown: LocalDate?, today: LocalDate): String? {
        if (j.done) return null
        val oldest = j.probe?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: oldestKnown ?: return "reading back to ${j.cursor}"
        val cpd = when (j.group) {
            Group.HEART.name -> if (j.chunks > 0) SyncLogic.hrCallsPerDay(j, 1000) else null
            Group.ACTIVITY.name -> 2.0 / 90
            else -> null
        }
        val e = SyncLogic.eta(j, oldest, cpd) ?: return "reading back to ${j.cursor}"
        val pct = (SyncLogic.fraction(oldest, LocalDate.parse(j.cursor), today) * 100).toInt().coerceIn(0, 100)
        val mode = if (type == "HeartRate" && j.chunks > 0) (if (j.hrMode == "agg") " · 1-min aggregates" else " · raw records") else ""
        return "$pct % · ${e.daysLeft} days left · ~${e.callsLeft} calls · ~${e.minutes} min" +
            (if (e.quotaWindows > 0) " (incl. ${e.quotaWindows} quota pause${if (e.quotaWindows > 1) "s" else ""} of 15 min)" else "") + mode
    }

    /** "Cached: 1,234 days, 2014-03-02 → 2026-10-30. History: done (…)." */
    fun coverageText(c: TypeCoverage): String {
        val unit = if (c.type == "ExerciseSession") "sessions" else "days"
        val cached = if (c.rows == 0) "Cached: nothing." else "Cached: ${c.rows} $unit, ${c.oldest} → ${c.newest}."
        val j = c.job ?: return "$cached History: not started."
        val state = when {
            j.floor == null -> "no records in Health Connect"
            j.done -> "done back to ${j.floor}"
            else -> "read back to ${j.cursor}, going to ${j.floor}"
        }
        val last = j.lastChunk.takeIf { it.isNotBlank() }?.let { " Last read: $it." } ?: ""
        val err = j.lastError?.let { " Last error: $it." } ?: ""
        return "$cached History: $state.$last$err"
    }
    private val EARLIEST: Instant = ALL_START.atStartOfDay(ZoneId.of("UTC")).toInstant()

    /**
     * Collects the Data check model. [live] = also ask Health Connect (≈ 2 calls per type + a 30-day read of steps
     * and calories; foreground only). Without it only cached information is used (instant, no quota).
     */
    suspend fun collect(context: Context, live: Boolean = true): Diagnostics = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        ErrorLog.init(app)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val since30 = today.minusDays(29)
        val cache = Cache.get(app)
        val available = HcReader.available(app)
        val historyFeature = HcReader.historyFeature(app)
        val backgroundFeature = HcReader.backgroundFeature(app)
        val client = if (available) HealthConnectClient.getOrCreate(app) else null
        val granted: Set<String> = client?.let { c -> safe("diagnostics/permissions", emptySet()) { c.permissionController.getGrantedPermissions() } } ?: emptySet()

        val required = HcReader.CORE_PERMISSIONS + (if (historyFeature) setOf(HcReader.HISTORY) else emptySet())
        val permissions = HcReader.PERMISSIONS.sorted().map { p ->
            PermissionStatus(
                p, p.substringAfterLast('.'), p in granted, p in required,
                when (p) {
                    HcReader.HISTORY -> if (!historyFeature) "Not supported by this Health Connect version" else "Without it: nothing older than 30 days before the first grant"
                    HcReader.BACKGROUND -> if (!backgroundFeature) "Not supported by this Health Connect version" else "Without it: the widget only updates when the app is opened"
                    else -> null
                },
            )
        }

        val bf = Sync.backfillState(app)
        val stats = cache.dailyStats().associateBy { it.first }
        val coverage = HcReader.READ_TYPES.map { t -> coverageOf(cache, stats, t, bf, zone) }.associateBy { it.type }

        val types = HcReader.READ_TYPES.map { t ->
            val name = typeName(t)
            val has = HcReader.readPermission(t) in granted
            if (!has) return@map TypeStats(name, Group.of(t), false, null, null, null, null, emptyList(), "permission not granted")
            val cached = cache.countsByPkg(name, since30)
            var sources = cached.map { SourceStats(it.pkg, Sources.label(it.pkg), it.recent, it.all, it.oldest, it.newest) }
            var oldest = cached.minOfOrNull { it.oldest }
            var newest = cached.maxOfOrNull { it.newest }
            var note: String? = null
            if (live && client != null) {
                val first = edge(client, t, true, zone)
                val last = edge(client, t, false, zone)
                if (first != null) oldest = first.first
                if (last != null) newest = last.second
                if (t in AGGREGATE_ONLY) {
                    // totals are read through aggregates: count the raw records of the last 30 days live
                    val per = safe("diagnostics/count/$name", null) { count30(client, t, since30, zone) }
                    if (per != null) sources = per.map { (pkg, v) -> SourceStats(pkg, Sources.label(pkg), v.first, null, null, v.second) }
                    note = "Read as daily totals (Health Connect merges apps by its priority list)."
                }
                if (t == ExerciseSessionRecord::class || t == WeightRecord::class) {
                    // DATA FIX: what Health Connect itself has per app right now (the owner's two problem types)
                    val per = safe("diagnostics/count/$name", null) { count30(client, t, since30, zone) }
                    if (per != null) note = listOfNotNull(note, "Health Connect, last 30 days: " +
                        (if (per.isEmpty()) "none" else per.entries.joinToString { (pkg, v) -> "${Sources.label(pkg)} ${v.first} (newest ${Instant.ofEpochMilli(v.second).atZone(zone).toLocalDate()})" }) + ".").joinToString(" ")
                }
                if (first != null && HcReader.HISTORY !in granted && historyFeature) {
                    note = listOfNotNull(note, "Past data not allowed: Health Connect may hide older records.").joinToString(" ")
                }
            } else if (t in AGGREGATE_ONLY) {
                note = "Read as daily totals; per-app counts need a live check."
            }
            // what is cached, how far the history job got, and whether Health Connect has more than the cache
            val cov = coverage[name]
            if (cov != null) {
                note = listOfNotNull(note, coverageText(cov)).joinToString(" ")
                val hcOld = oldest
                if (live && hcOld != null && cov.job?.done == true && (cov.oldest == null || hcOld.isBefore(cov.oldest.minusDays(1))) && t !in AGGREGATE_ONLY_RAW)
                    note = "$note Health Connect has older records ($hcOld) than the cache: tap Rebuild history."
            }
            TypeStats(name, Group.of(t), true, sources.sumOfOrNull { it.count30d }, sources.sumOfOrNull { it.countAll }, oldest, newest,
                sources.sortedByDescending { it.count30d ?: 0 }, note)
        }

        val cacheStats = CacheStats(
            sizeBytes = cache.sizeBytes(app),
            metrics = cache.dailyStats().map { (m, n, r) -> MetricCoverage(m, n, r.first, r.second) },
            workouts = cache.workoutCount(),
            heartRateDays = cache.hrDayRange(),
        )
        val errors = ErrorLog.errors.value
        SyncLog.init(app)
        Diagnostics(
            generatedAt = System.currentTimeMillis(),
            healthConnectAvailable = available,
            historyFeature = historyFeature,
            backgroundFeature = backgroundFeature,
            permissions = permissions,
            lastSync = Sync.lastSync(app),
            syncState = Sync.state.value,
            backfill = bf,
            steps = Sync.stepResults(app).values.sortedBy { it.step },
            types = types,
            cache = cacheStats,
            errors = errors,
            findings = findings(available, historyFeature, backgroundFeature, granted, types, bf, errors, today, coverage, live),
            coverage = HcReader.READ_TYPES.mapNotNull { t ->
                val c = coverage[typeName(t)] ?: return@mapNotNull null
                val ts = types.firstOrNull { it.type == c.type }
                val running = Sync.state.value.running
                val state = when {
                    ts?.permissionGranted == false -> "no permission"
                    c.job == null -> "pending"
                    c.job.floor == null -> "no data"
                    c.job.done -> "done"
                    c.job.lastError != null -> "failed"
                    running -> "running"
                    else -> "pending"
                }
                c.copy(
                    progress = c.job?.let { progressText(it, c.type, ts?.oldest ?: c.oldest, today) },
                    hcOldest = if (live) ts?.oldest else null,
                    hcNewest = if (live) ts?.newest?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } else null,
                    hcCount30 = if (live) ts?.count30d else null,
                    permission = ts?.permissionGranted != false,
                    state = state,
                )
            },
            syncLog = SyncLog.tail(150),
        )
    }

    private fun Iterable<SourceStats>.sumOfOrNull(f: (SourceStats) -> Int?): Int? {
        var any = false
        var s = 0
        for (x in this) f(x)?.let { any = true; s += it }
        return if (any) s else null
    }

    /** Oldest (asc) or newest (desc) record: (local day, epoch ms). One call. */
    private suspend fun edge(client: HealthConnectClient, type: KClass<out Record>, oldest: Boolean, zone: ZoneId): Pair<LocalDate, Long>? =
        safe("diagnostics/${if (oldest) "oldest" else "newest"}/${typeName(type)}", null) {
            @Suppress("UNCHECKED_CAST")
            val k = type as KClass<Record>
            val r = client.readRecords(ReadRecordsRequest(recordType = k, timeRangeFilter = TimeRangeFilter.between(EARLIEST, Instant.now().plusSeconds(3600)), ascendingOrder = oldest, pageSize = 1))
            r.records.firstOrNull()?.let { rec -> recordTime(rec)?.let { t -> t.atZone(zone).toLocalDate() to t.toEpochMilli() } }
        }

    /** Raw records of the last 30 days per app: pkg → (count, newest end time). */
    private suspend fun count30(client: HealthConnectClient, type: KClass<out Record>, since: LocalDate, zone: ZoneId): Map<String, Pair<Int, Long>> {
        @Suppress("UNCHECKED_CAST")
        val k = type as KClass<Record>
        val out = HashMap<String, Pair<Int, Long>>()
        var token: String? = null
        val f = TimeRangeFilter.between(since.atStartOfDay(zone).toInstant(), Instant.now())
        do {
            val resp = client.readRecords(ReadRecordsRequest(recordType = k, timeRangeFilter = f, pageSize = 5000, pageToken = token))
            for (r in resp.records) {
                val pkg = r.metadata.dataOrigin.packageName
                val t = recordTime(r)?.toEpochMilli() ?: 0L
                val (n, newest) = out[pkg] ?: (0 to 0L)
                out[pkg] = (n + 1) to maxOf(newest, t)
            }
            token = resp.pageToken?.takeIf { it.isNotEmpty() }
        } while (token != null)
        return out
    }

    private fun recordTime(r: Record): Instant? = when (r) {
        is StepsRecord -> r.endTime
        is TotalCaloriesBurnedRecord -> r.endTime
        is ActiveCaloriesBurnedRecord -> r.endTime
        is androidx.health.connect.client.records.HeartRateRecord -> r.endTime
        is androidx.health.connect.client.records.ExerciseSessionRecord -> r.endTime
        is androidx.health.connect.client.records.SleepSessionRecord -> r.endTime
        is androidx.health.connect.client.records.RestingHeartRateRecord -> r.time
        is androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord -> r.time
        is androidx.health.connect.client.records.RespiratoryRateRecord -> r.time
        is androidx.health.connect.client.records.OxygenSaturationRecord -> r.time
        is androidx.health.connect.client.records.Vo2MaxRecord -> r.time
        is androidx.health.connect.client.records.WeightRecord -> r.time
        is androidx.health.connect.client.records.BodyFatRecord -> r.time
        is androidx.health.connect.client.records.LeanBodyMassRecord -> r.time
        is androidx.health.connect.client.records.HeightRecord -> r.time
        else -> null
    }

    /** Plain-language problems, most important first: what is wrong and what to do. */
    private fun findings(
        available: Boolean, historyFeature: Boolean, backgroundFeature: Boolean, granted: Set<String>,
        types: List<TypeStats>, bf: BackfillState?, errors: List<LoggedError>, today: LocalDate,
        coverage: Map<String, TypeCoverage>, live: Boolean,
    ): List<String> {
        if (!available) return listOf("Health Connect is not available. Install or update it from the Play Store.")
        val out = ArrayList<String>()
        val missing = types.filter { !it.permissionGranted }
        if (missing.isNotEmpty()) out += "No permission for: ${missing.joinToString { it.type }}. Open Health BB → Allow, and switch these on."
        // explicit runtime check (getGrantedPermissions), always stated first when missing
        if (HcReader.HISTORY !in granted)
            out += "Past data permission (READ_HEALTH_DATA_HISTORY) is NOT granted right now: Health Connect silently returns only recent data (about the last 30 days). Health Connect → App permissions → Health BB → \"Access past data\"."
        if (backgroundFeature && HcReader.BACKGROUND !in granted)
            out += "\"Access data in the background\" is off: the widget only updates when you open the app."
        if (!historyFeature) out += "This Health Connect version has no \"past data\" permission; update Health Connect for full history."
        when {
            bf == null -> out += "History has not been read yet. Open the app and keep it open for a minute."
            bf.finishedAt == null -> {
                val open = bf.jobs.values.filter { !it.done && it.floor != null }
                if (open.isNotEmpty()) out += "Reading history: " + open.joinToString("; ") { "${Group.valueOf(it.group).label} back to ${it.cursor} (of ${it.floor})" } + ". It continues automatically while the app is open."
            }
        }
        bf?.jobs?.values?.filter { it.lastError != null }?.forEach { j ->
            out += "History of ${Group.valueOf(j.group).label} had a problem: ${j.lastError}. It retries on its own (smaller periods)."
        }
        // the cache must reach as far as Health Connect does (that is what was broken in v2)
        if (live) for (t in types.filter { it.permissionGranted }) {
            val c = coverage[t.type] ?: continue
            val hcNewest = t.newest?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
            if (t.type != "ActiveCaloriesBurned" && hcNewest != null && (c.newest == null || hcNewest.isAfter(c.newest.plusDays(1))) && c.job?.done == true)
                out += "${t.type}: Health Connect has data up to $hcNewest but the cache only to ${c.newest ?: "nothing"}. Tap Sync now; if it stays, Rebuild history and send the report."
        }
        val now = System.currentTimeMillis()
        // heart rate is written around the clock by a worn tracker, so an old newest value means the writer app is not syncing
        val wearable = setOf("HeartRate")
        for (t in types.filter { it.type in wearable && it.permissionGranted }) {
            val n = t.newest ?: continue
            val hours = (now - n) / 3_600_000L
            if (hours >= 6) out += "Newest ${t.type} in Health Connect is $hours h old. The app that writes it (${t.sources.firstOrNull()?.label ?: "?"}) has not synced: open it to push data to Health Connect."
        }
        types.firstOrNull { it.type == "ActiveCaloriesBurned" }?.sources?.takeIf { it.isNotEmpty() }?.let { s ->
            out += "Active calories come from: ${s.joinToString { it.label }}. Workout kcal only use the session's own app (or the wearable's total)."
        }
        val dayAgo = now - 86_400_000L
        val recent = errors.filter { it.time >= dayAgo }
        recent.groupBy { it.kind }.forEach { (kind, list) ->
            val k = runCatching { ErrKind.valueOf(kind) }.getOrDefault(ErrKind.OTHER)
            out += "${list.size}× ${k.name.lowercase().replace('_', ' ')} in the last 24 h (last: ${list.first().step}). ${k.hint}"
        }
        if (out.isEmpty()) out += "All good: permissions complete, history cached, data is recent (checked ${today})."
        return out
    }
}
