package com.boudhealth.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
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
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.boudhealth.App
import com.boudhealth.work.Refresh
import com.boudhealth.work.RefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass

/** Record types are synced in groups; one Changes-API token and one history job per group. */
enum class Group(val label: String, val types: List<KClass<out Record>>) {
    VITALS("Vitals", listOf(RestingHeartRateRecord::class, HeartRateVariabilityRmssdRecord::class, RespiratoryRateRecord::class, OxygenSaturationRecord::class, Vo2MaxRecord::class)),
    BODY("Body", listOf(WeightRecord::class, BodyFatRecord::class, LeanBodyMassRecord::class, HeightRecord::class)),
    ACTIVITY("Activity", listOf(StepsRecord::class, TotalCaloriesBurnedRecord::class, ActiveCaloriesBurnedRecord::class)),
    HEART("Heart rate", listOf(HeartRateRecord::class)),
    SLEEP("Sleep", listOf(SleepSessionRecord::class)),
    WORKOUTS("Workouts", listOf(ExerciseSessionRecord::class));

    /**
     * History is read backwards in chunks of this many days; ≤ 0 = the whole range in one paged read
     * (body, vitals, sleep and workouts are small: a few thousand records for many years).
     */
    val chunkDays: Int get() = when (this) { ACTIVITY -> 90; HEART -> 30; else -> 0 }

    companion object {
        fun of(type: KClass<out Record>): Group = entries.first { type in it.types }
    }
}

/** "Weight" for WeightRecord: the key used in the cache and on the Data check screen. */
fun typeName(k: KClass<out Record>): String = k.simpleName?.removeSuffix("Record") ?: k.toString()

@Serializable
data class BackfillState(
    val jobs: Map<String, JobProgress> = emptyMap(),
    /** Whether "access past data" was granted when the jobs started (granting it later restarts them all). */
    val history: Boolean,
    val startedAt: Long,
    /** Set when every job reached its floor and every workout is enriched. */
    val finishedAt: Long? = null,
    /** v4.1 field, unused since v5 (no BMR); kept so stored state still decodes. */
    val bmr: Boolean = false,
) {
    /** Oldest day any job reads back to. */
    val floor: LocalDate get() = jobs.values.mapNotNull { it.floor?.let(LocalDate::parse) }.minOrNull() ?: LocalDate.now()
    /** Cursor of the job that is furthest behind (what "Reading history: …" shows), or the floor when all are done. */
    val cursor: String get() = jobs.values.filter { !it.done && it.floor != null }.maxOfOrNull { it.cursor } ?: floor.toString()
    val chunks: Int get() = jobs.values.sumOf { it.chunks }
    val types: List<String> get() = jobs.values.flatMap { it.types }.sorted()

    /** Average progress of the jobs (each from its own floor to today). */
    fun fraction(today: LocalDate): Float {
        val js = jobs.values.filter { it.floor != null }
        if (js.isEmpty()) return 1f
        return js.map { j -> if (j.done) 1f else SyncLogic.fraction(LocalDate.parse(j.floor!!), LocalDate.parse(j.cursor), today) }.average().toFloat()
    }
}

@Serializable
data class TokenState(
    val token: String, val types: List<String>, val createdAt: Long,
    /** Last time this group's changes were all applied (per group, so one failing group doesn't hold back the others). */
    val syncedAt: Long = createdAt,
)

/** Outcome of one sync step, kept per step name for the Data check screen. */
@Serializable
data class StepResult(val step: String, val at: Long, val ok: Boolean, val ms: Long, val calls: Int = 0, val detail: String = "")

data class SyncResult(val ok: Boolean, val message: String, val changedDays: Int = 0)

data class BackfillProgress(
    val floor: LocalDate,
    val cursor: LocalDate,
    val done: Boolean,
    val paused: String? = null,
    /** Progress over all history jobs (set by the sync); null = estimate from [floor] / [cursor]. */
    val frac: Float? = null,
) {
    /** 0..1, how much of the history (today back to floor) is cached. */
    val fraction: Float get() = if (done) 1f else frac ?: SyncLogic.fraction(floor, cursor, LocalDate.now())
}

data class SyncState(
    val running: Boolean = false,
    val phase: String = "",
    val backfill: BackfillProgress? = null,
    val lastSuccess: Long? = null,
    val lastResult: String? = null,
)

/**
 * Health Connect → local [Cache].
 *
 * - **History** ("backfill"): one job per [Group] with its own floor (oldest record), cursor and chunk size.
 *   Body, vitals, sleep and workouts are read in one paged read over their whole range; steps/calories in
 *   90-day aggregate chunks; heart rate in 30-day chunks (newest first). Jobs fail independently: a failing heart-rate
 *   chunk no longer blocks weight or workouts (that was the v2 bug, see DATA_FIX.md). Empty chunks never stop a job,
 *   only its floor does. The mutex is taken per unit of work, so foreground refreshes run in between.
 * - **Workouts** are stored first (no extra calls) and enriched (kcal via aggregates) in budgeted batches.
 * - **Incremental**: Changes API, one token per group, taken *before* that group's history is read. Groups are
 *   independent: one failing group keeps its old token (re-read next time) and doesn't stop the others.
 * - Logs: tag "HB.sync" + [SyncLog] (Copy report).
 */
object Sync {
    enum class Mode { FOREGROUND, BACKGROUND }

    private const val PAGE = 5000
    /** Heart-rate records can carry many samples each: smaller pages keep memory bounded. */
    private const val HR_PAGE = 1000
    private const val BACKGROUND_UNITS = 6
    private const val ENRICH_BATCH = 25
    private const val MAX_FAILURES = 5
    /** During a history read, screens are told about new data at most this often (after the first units). */
    private const val BACKFILL_BUMP_MS = 3_000L
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var backfillJob: Job? = null

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val _version = MutableStateFlow(0L)
    /** Bumped whenever cached data changed: detail screens and tiles re-query when it changes. */
    val version: StateFlow<Long> = _version.asStateFlow()

    /**
     * V2_SYNC_PERF: version → earliest changed day of that bump ([DirtyDays.ALL] = everything). A bump that is not in
     * the log means "unknown" (the engine is rebuilt). Lets V2 re-read only the changed tail after a sync.
     */
    private val changeLog = java.util.concurrent.ConcurrentHashMap<Long, LocalDate>()

    /** Full bump: everything may have changed (rebuild, import, a write outside the cache tracker). */
    private fun bump() {
        Cache.peek()?.dirty?.take() // folded into this full bump
        // same lock as bumpIfChanged: a version number is never shared, and a full bump is never in the log
        synchronized(changeLog) { val v = _version.value + 1; changeLog.remove(v); _version.value = v }
    }

    /**
     * Bump only if the cache was written since the last bump, tagged with the earliest written day. Returns whether it
     * bumped (a refresh that changed nothing no longer makes every screen and widget recompute).
     */
    private fun bumpIfChanged(cache: Cache): Boolean {
        val from = cache.dirty.take() ?: return false
        synchronized(changeLog) {
            val v = _version.value + 1
            changeLog[v] = from
            changeLog.keys.filter { it < v - 256 }.forEach { changeLog.remove(it) }
            _version.value = v
        }
        return true
    }

    /** Earliest day changed by the bumps after [since] up to now; null = rebuild everything (see [DirtyDays.changedFrom]). */
    fun changedFrom(since: Long): LocalDate? = DirtyDays.changedFrom(changeLog, since, _version.value)

    /** Cached data changed outside the sync (Apple Health import added / removed): screens and tiles re-query. */
    fun dataChanged() = bump()

    /** Local writes outside a sync (manual entries mirrored, zone minutes recomputed by publish): screens re-query the changed days. */
    fun announceWrites(context: Context) { bumpIfChanged(Cache.get(context)) }

    /**
     * [SyncState.running] is a count of active operations (foreground sync, worker, history loop, rebuild), changed
     * only through [begin] / [end] in try/finally, so it can never stay stuck at true (review: buttons stayed disabled).
     */
    private val active = java.util.concurrent.atomic.AtomicInteger()
    private fun begin(phase: String) {
        active.incrementAndGet()
        _state.update { it.copy(running = true, phase = phase) }
    }
    private fun end() {
        val n = active.updateAndGet { maxOf(0, it - 1) }
        _state.update { it.copy(running = n > 0, phase = if (n > 0) it.phase else "") }
    }

    /** Foreground refreshes waiting for the lock: the worker's history units stop early for them (review m4). */
    private val foregroundWaiting = java.util.concurrent.atomic.AtomicInteger()

    // ================================================================== public actions

    /**
     * Incremental sync (Changes API) + local recomputation. In [Mode.BACKGROUND] (worker) it also continues
     * unfinished history for a few units, if background reads are allowed. Never throws.
     * Waits for a running history unit to finish (units are short) instead of skipping.
     */
    suspend fun run(context: Context, mode: Mode): SyncResult = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        ErrorLog.init(app); SyncLog.init(app)
        if (!HcReader.available(app)) {
            ErrorLog.note("sync", ErrKind.UNAVAILABLE, "Health Connect not available")
            SyncResult(false, "Health Connect not available")
        } else {
            // a foreground refresh announces itself while it waits, so the worker's history units stop early (m4)
            var waiting = mode == Mode.FOREGROUND
            if (waiting) foregroundWaiting.incrementAndGet()
            try {
                mutex.withLock {
                    if (waiting) { foregroundWaiting.decrementAndGet(); waiting = false }
                    runLocked(app, mode)
                }
            } finally {
                if (waiting) foregroundWaiting.decrementAndGet()
            }
        }
    }

    /** Starts (or resumes) the history backfill in the app process, if one is needed. Returns immediately. */
    @Synchronized
    fun startBackfillIfNeeded(context: Context) {
        val app = context.applicationContext
        if (rebuilding) return // the rebuild starts it itself once the cache is wiped
        if (backfillJob?.isActive == true) return
        // "running" goes up synchronously, before the coroutine starts: no gap between a rebuild and the history loop
        begin("Reading history…")
        backfillJob = scope.launch {
            try { backfillLoop(app) } finally { end() }
        }
    }

    /**
     * "Rebuild history": wipes the cache, change tokens and progress (not manual entries) and reads everything again.
     * A second tap while one is in progress is ignored (guard set synchronously, before anything is launched).
     */
    fun rebuild(context: Context) = startRebuild(context.applicationContext, "rebuild requested")

    /**
     * "Full re-read" (Data check): same as [rebuild] — wipe cache, change tokens and every history marker, then read all
     * history again in the app process with progress in [state]. Safe to call from a click handler: no I/O on the
     * caller's thread, double taps ignored, [SyncState.running] stays true from the tap through the whole history read.
     */
    fun fullReread(context: Context) = startRebuild(context.applicationContext, "full re-read requested from Data check")

    /** Returns false when a rebuild is already in progress (the call is ignored). */
    private fun startRebuild(app: Context, why: String): Boolean {
        synchronized(this) {
            if (rebuilding) return false
            rebuilding = true // no new history loop can start until the wipe is done
            begin("Rebuilding…")
        }
        _state.update { it.copy(backfill = null) }
        scope.launch {
            try {
                SyncLog.init(app)
                SyncLog.i(why)
                backfillJob?.cancelAndJoin()
                mutex.withLock {
                    Cache.get(app).clearAll() // daily, hourly, hr_day, workout, rec_index, rec_count AND meta (tokens, progress, steps)
                    SyncLog.i("rebuild: cache, change tokens and history progress wiped")
                    // Google Health API values live in gh_daily (kept): back into daily right away, no network
                    Cache.get(app).mirrorGh().let { if (it > 0) SyncLog.i("rebuild: $it Google Health API days restored") }
                }
                bump()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.record("rebuild", e)
                SyncLog.w("rebuild failed", e)
            } finally {
                synchronized(this@Sync) { rebuilding = false }
                // start the history loop (its begin() runs now) BEFORE ending the rebuild's own count: no flicker
                startBackfillIfNeeded(app)
                end()
            }
        }
        return true
    }

    @Volatile private var rebuilding = false

    /** Re-reads [from]..[to] for the given groups (e.g. a "re-sync this period" action). */
    suspend fun resyncRange(context: Context, from: LocalDate, to: LocalDate, groups: Collection<Group> = Group.entries): SyncResult =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            mutex.withLock {
                val ctx = newCtx(app) ?: return@withLock SyncResult(false, "Health Connect not available")
                var ok = true
                for (g in groups) for ((a, b) in ranges(datesBetween(from, to).toSet())) {
                    ctx.step("resync/${g.name}") { ctx.syncGroup(g, a, b) } ?: run { ok = false }
                }
                ctx.step("resync/enrich") { ctx.enrichBatch(ENRICH_BATCH) }
                bumpIfChanged(ctx.cache)
                SyncResult(ok, if (ok) "re-read $from…$to" else "some steps failed, see errors")
            }
        }

    /** Backfill progress as stored (for diagnostics), null if never started. */
    fun backfillState(context: Context): BackfillState? = loadBf(Cache.get(context))

    /** Step name → last result, for diagnostics. */
    fun stepResults(context: Context): Map<String, StepResult> = loadSteps(Cache.get(context))

    fun lastSync(context: Context): Long? = Cache.get(context).meta(K_LAST_SYNC)?.toLongOrNull()

    /** Mirrors manual entries (waist, height) from [Store] into the cache. Local only. */
    fun mirrorManual(cache: Cache, waist: List<Sample>, height: List<Sample>, zone: ZoneId = ZoneId.systemDefault()) {
        fun rows(metric: String, list: List<Sample>) = list.groupBy { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }
            .map { (d, v) -> v.maxBy { it.time }.let { DailyRow(metric, d, it.value, it.time, "manual") } }
        val far = LocalDate.of(1900, 1, 1) to LocalDate.of(2999, 12, 31)
        // V2_SYNC_PERF: runs on every publish: only write when the manual entries changed (an unconditional rewrite of
        // 1900…2999 marked all history as changed after every sync)
        fun same(metric: String, rs: List<DailyRow>) =
            cache.dailyHc(metric, far.first, far.second).map { Triple(it.day, it.value, it.time) } == rs.sortedBy { it.day }.map { Triple(it.day, it.value, it.time) }
        rows(M.WAIST, waist).let { if (!same(M.WAIST, it)) cache.replaceDaily(listOf(M.WAIST), far.first, far.second, it) }
        rows(M.HEIGHT_MANUAL, height).let { if (!same(M.HEIGHT_MANUAL, it)) cache.replaceDaily(listOf(M.HEIGHT_MANUAL), far.first, far.second, it) }
    }

    // ================================================================== internals

    private const val K_BACKFILL = "backfill2"
    private const val K_STEPS = "steps"
    private const val K_LAST_SYNC = "last_sync"
    private fun tokenKey(g: Group) = "token/${g.name}"

    private class Ctx(
        val app: Context,
        val client: HealthConnectClient,
        val cache: Cache,
        val granted: Set<String>,
        val goals: Goals,
    ) {
        val zone: ZoneId = ZoneId.systemDefault()
        var calls = 0
        /** Activity days the Changes step already re-read (the "today" re-read is skipped when it covers them). */
        val activityReread = HashSet<LocalDate>()
        var lastFailure: ErrKind? = null
        /** Fallback quota detection: 5 failures within a minute pause the pass (review m6). */
        val rapid = SyncLogic.RapidFailures()
        val hasHistory: Boolean get() = HcReader.HISTORY in granted
        val hasBackground: Boolean get() = HcReader.BACKGROUND in granted
        fun has(type: KClass<out Record>) = HcReader.readPermission(type) in granted
        fun grantedTypes(): List<KClass<out Record>> = HcReader.READ_TYPES.filter { has(it) }
        fun day(i: Instant): LocalDate = i.atZone(zone).toLocalDate()
        fun day(ms: Long): LocalDate = day(Instant.ofEpochMilli(ms))
        fun startOf(d: LocalDate): Instant = d.atStartOfDay(zone).toInstant()
        fun startMs(d: LocalDate): Long = startOf(d).toEpochMilli()
        /** [from] 00:00 … [to]+1 00:00 local (end exclusive, so [to] is fully included, today included). */
        fun filter(from: LocalDate, to: LocalDate): TimeRangeFilter = TimeRangeFilter.between(startOf(from), startOf(to.plusDays(1)))
        fun today(): LocalDate = LocalDate.now(zone)
        /** End of [d] or now, whichever is earlier: the "time" of a daily total. */
        fun dayTime(d: LocalDate): Long = minOf(System.currentTimeMillis(), startMs(d.plusDays(1)) - 1)
    }

    /** What one read wrote: records read from Health Connect, day rows (or sessions) written. */
    private data class ReadStats(val records: Int, val days: Int, val samples: Long = 0) {
        operator fun plus(o: ReadStats) = ReadStats(records + o.records, days + o.days, samples + o.samples)
    }
    private val NONE = ReadStats(0, 0)

    @Volatile private var lastPermLine: String? = null

    private suspend fun newCtx(app: Context): Ctx? {
        ErrorLog.init(app); SyncLog.init(app)
        Sources.init(app)
        if (!HcReader.available(app)) return null
        val client = HealthConnectClient.getOrCreate(app)
        val granted = safe("permissions", null) { client.permissionController.getGrantedPermissions() } ?: return null
        val goals = (app as App).store.goalsNow()
        // explicit runtime check of "past data": without it Health Connect silently returns only recent data
        val missing = HcReader.READ_TYPES.filter { HcReader.readPermission(it) !in granted }.map(::typeName)
        val line = "permissions: ${HcReader.READ_TYPES.size - missing.size}/${HcReader.READ_TYPES.size} read types, " +
            "PAST DATA (READ_HEALTH_DATA_HISTORY)=${if (HcReader.HISTORY in granted) "granted" else "NOT GRANTED"}, " +
            "background=${if (HcReader.BACKGROUND in granted) "granted" else "not granted"}" + (if (missing.isEmpty()) "" else ", missing: ${missing.joinToString()}")
        if (line != lastPermLine) { SyncLog.i(line); lastPermLine = line }
        return Ctx(app, client, Cache.get(app), granted, goals)
    }

    private suspend fun runLocked(app: Context, mode: Mode): SyncResult {
        val ctx = newCtx(app) ?: return SyncResult(false, "could not read permissions")
        if (ctx.grantedTypes().isEmpty()) {
            ctx.saveStep(StepResult("sync", System.currentTimeMillis(), false, 0, 0, "no read permission granted"))
            SyncLog.w("sync: no read permission granted")
            return SyncResult(false, "no Health Connect permission")
        }
        if (mode == Mode.BACKGROUND && !ctx.hasBackground) {
            val prev = loadSteps(ctx.cache)["sync"]
            if (prev?.detail != NO_BG) ErrorLog.note("sync", ErrKind.NOT_FOREGROUND, NO_BG)
            ctx.saveStep(StepResult("sync", System.currentTimeMillis(), false, 0, 0, NO_BG))
            return SyncResult(false, NO_BG)
        }
        begin("Checking for changes…")
        try {
            val t0 = System.currentTimeMillis()
            val changed = ctx.step("changes") { ctx.incremental() }
            // totals can change without a record change (priority list edited in Health Connect): re-read
            // yesterday + today's steps/kcal on every foreground refresh (2–4 cheap calls)
            if (mode == Mode.FOREGROUND && loadBf(ctx.cache) != null &&
                !(ctx.activityReread.contains(ctx.today()) && ctx.activityReread.contains(ctx.today().minusDays(1)))) {
                ctx.step("today") { ctx.syncGroup(Group.ACTIVITY, ctx.today().minusDays(1), ctx.today()) }
            }
            // v4.1 fix: days whose steps/calories were read before their end (e.g. at 23:10) are re-read on every
            // sync, foreground and background, until complete (2 calls per range, usually one range).
            if (loadBf(ctx.cache) != null) ctx.step("complete days") { ctx.completeDays() }
            // V2_SYNC_PERF: in the foreground the publish right after the sync does this (in the background): after an
            // install it recomputes the zone minutes of all imported heart rate (~0.6 s measured), which used to keep
            // the app-open sync indicator up
            if (mode == Mode.BACKGROUND) ctx.step("zones") { Derive.ensureZoneParams(ctx.cache, ctx.goals) }
            if (mode == Mode.BACKGROUND && needsBackfill(ctx)) {
                // the worker continues history a few units at a time (background quota is lower)
                var n = 0
                val failed = HashSet<Group>()
                while (n < BACKGROUND_UNITS) {
                    if (foregroundWaiting.get() > 0) { SyncLog.i("worker: app opened, history units yield to the foreground refresh"); break }
                    val r = ctx.safeUnit(failed)
                    if (r != Unit_.WORK) break
                    n++
                }
            }
            val ok = changed != null
            val msg = if (ok) "${changed} day(s) updated" else "failed: ${ctx.lastFailure?.name ?: "error"}"
            ctx.saveStep(StepResult("sync", t0, ok, System.currentTimeMillis() - t0, ctx.calls, msg))
            SyncLog.i("sync ${mode.name.lowercase()}: $msg, ${ctx.calls} calls, ${System.currentTimeMillis() - t0} ms")
            if (ok) ctx.cache.setMeta(K_LAST_SYNC, System.currentTimeMillis().toString())
            if (ctx.lastFailure == ErrKind.RATE_LIMIT) scheduleResume(app)
            _state.update { it.copy(lastSuccess = if (ok) System.currentTimeMillis() else it.lastSuccess, lastResult = msg) }
            // V2_SYNC_PERF: was "bump on every foreground refresh", even when nothing changed (a full recompute of
            // every screen and widget per app open). Now only when the cache was written, with the changed days.
            bumpIfChanged(ctx.cache)
            return SyncResult(ok, msg, changed ?: 0)
        } finally {
            end()
        }
    }

    /** Re-reads the activity group for past days stored before they ended ([SyncLogic.incompleteDays]). */
    private suspend fun Ctx.completeDays(): Int {
        val today = today()
        val from = today.minusDays(30)
        val times = (cache.dailyHc(M.KCAL, from, today) + cache.dailyHc(M.STEPS, from, today)).map { it.day to it.time }
        val days = SyncLogic.incompleteDays(times, today, { startMs(it.plusDays(1)) })
        if (days.isEmpty()) return 0
        for ((a, b) in SyncLogic.ranges(days)) syncGroup(Group.ACTIVITY, a, b)
        SyncLog.i("complete days: re-read ${days.size} day(s) stored before their end (${days.min()}…${days.max()})")
        return days.size
    }

    private const val NO_BG = "background read permission missing: widget shows cached data until the app is opened"

    // ------------------------------------------------------------------ history (backfill)

    private suspend fun needsBackfill(ctx: Ctx): Boolean {
        val bf = loadBf(ctx.cache) ?: return ctx.grantedTypes().isNotEmpty()
        if (ctx.hasHistory && !bf.history) return true
        for (g in Group.entries) {
            val names = g.types.filter { ctx.has(it) }.map(::typeName)
            if (names.isEmpty()) continue
            val j = bf.jobs[g.name] ?: return true
            if (!j.types.containsAll(names)) return true
        }
        return bf.finishedAt == null || bf.jobs.values.any { !it.done || it.skipped.isNotEmpty() } || ctx.cache.unenrichedCount() > 0
    }

    private enum class Unit_ { WORK, IDLE, PAUSE }

    /**
     * The app-process history loop: one unit of work per mutex hold (one chunk of one job, or one enrichment batch),
     * so foreground refreshes and the Changes API run in between. Stops when all jobs are done, or pauses on a
     * quota / permission / background error (resumed by the next app open or the worker).
     */
    private suspend fun backfillLoop(app: Context) {
        run {
            val ctx = mutex.withLock {
                val c = newCtx(app) ?: return
                if (!needsBackfill(c)) return
                c
            }
            val t0 = System.currentTimeMillis()
            lastPermLine = null // always log the permission line with a history pass
            SyncLog.i("history: pass start (PAST DATA permission=${if (ctx.hasHistory) "granted" else "NOT GRANTED: Health Connect only returns recent data"}, background=${ctx.hasBackground})")
            val failedThisPass = HashSet<Group>()
            var units = 0
            var result = Unit_.IDLE
            try {
                var lastBump = 0L
                while (true) {
                    result = mutex.withLock { ctx.safeUnit(failedThisPass) }
                    if (result != Unit_.WORK) break
                    units++
                    // V2_SYNC_PERF: the screens re-query on every bump; during a long history read (e.g. heart rate
                    // after an install) bump the first units at once, then at most every 3 s (the last one at the end)
                    val now = System.currentTimeMillis()
                    if (units <= 4 || now - lastBump >= BACKFILL_BUMP_MS) { if (bumpIfChanged(ctx.cache)) lastBump = now }
                    // the first units hold today and the small groups: show them now, then refresh every few units
                    if (units <= 4 || units % 6 == 0) Refresh.publish(app)
                }
            } finally {
                bumpIfChanged(ctx.cache)
                val bf = loadBf(ctx.cache)
                val paused = if (result != Unit_.PAUSE) null else when (ctx.lastFailure) {
                    ErrKind.RATE_LIMIT -> { scheduleResume(ctx.app); "Health Connect quota reached, continuing in ~20 min" }
                    ErrKind.NOT_FOREGROUND -> "paused while the app is in the background"
                    else -> "paused after an error, continues on next refresh"
                }
                SyncLog.i("history: pass end after $units unit(s), ${ctx.calls} calls, ${System.currentTimeMillis() - t0} ms, result=$result${paused?.let { " ($it)" } ?: ""}")
                _state.update { it.copy(backfill = bf?.let { b -> progressOf(b, paused) }) }
            }
            Refresh.publish(app)
        }
    }

    private fun progressOf(bf: BackfillState, paused: String?): BackfillProgress {
        val today = LocalDate.now()
        return BackfillProgress(bf.floor, LocalDate.parse(bf.cursor), bf.finishedAt != null, paused, bf.fraction(today))
    }

    /**
     * Creates or restarts the per-group jobs: for every group whose readable types changed (or when "past data" was
     * granted since), a change token is taken FIRST (so whatever apps write while history is read is picked up by
     * the Changes API), then the floor is probed. Returns the current state.
     */
    private suspend fun Ctx.prepare(): BackfillState {
        var bf = loadBf(cache)
        val restartAll = bf == null || (hasHistory && !bf.history)
        if (bf == null || restartAll) {
            if (bf != null) SyncLog.i("history: \"past data\" was granted since the last read: restarting every job")
            bf = BackfillState(emptyMap(), hasHistory, System.currentTimeMillis())
        }
        val jobs = bf.jobs.toMutableMap()
        val today = today()
        for (g in Group.entries) {
            val types = g.types.filter { has(it) }
            val names = types.map(::typeName).sorted()
            val cur = jobs[g.name]
            if (names.isEmpty()) {
                if (cur != null) jobs.remove(g.name)
                cache.setMeta(tokenKey(g), null)
                continue
            }
            if (cur != null && cur.types.containsAll(names)) continue
            // token first
            saveToken(g, newToken(types))
            val (probe, guessed) = probeFloor(types)
            // the probe is only a hint: whole-range groups always read from ALL_START, an empty probe never means "no data"
            val floor = SyncLogic.initialFloor(probe, g.chunkDays > 0) // always ALL_START: empty periods are jumped
            jobs[g.name] = JobProgress(
                group = g.name, types = names, floor = floor.toString(), cursor = today.plusDays(1).toString(),
                chunkDays = g.chunkDays, done = false, updatedAt = System.currentTimeMillis(),
                lastChunk = "", floorGuessed = false, probe = if (guessed) "probe failed" else probe?.toString() ?: "empty",
            )
            SyncLog.i("history: job ${g.name} (re)started, probe=${probe ?: "EMPTY"}${if (guessed) " (probe failed)" else ""}, reading from $floor, chunk=${if (g.chunkDays <= 0) "whole range" else "${g.chunkDays} d"}")
        }
        val open = jobs.values.any { !it.done }
        bf = bf.copy(jobs = jobs, history = hasHistory, finishedAt = if (open) null else bf.finishedAt)
        saveBf(cache, bf)
        return bf
    }

    /**
     * Floor of a group: the oldest record of any of its [types] (one call per type), and whether a probe failed.
     * A failed probe counts as ALL_START (read everything) instead of "no data": in v2 an error here made the whole
     * group look empty, so it was never read (see DATA_FIX.md).
     */
    private suspend fun Ctx.probeFloor(types: List<KClass<out Record>>): Pair<LocalDate?, Boolean> {
        var floor: LocalDate? = null
        var failed = false
        val probes = ArrayList<String>()
        for (t in types) {
            val p = try {
                oldestDay(t)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.record("floor/${typeName(t)}", e)
                SyncLog.w("history: probe ${typeName(t)} failed", e)
                failed = true
                ALL_START
            }
            probes += "${typeName(t)}=${p ?: "none"}"
            if (p != null && (floor == null || p.isBefore(floor))) floor = p
        }
        SyncLog.i("history: oldest records: ${probes.joinToString()}")
        return floor?.let { SyncLogic.maxDate(it, ALL_START) } to failed
    }

    /**
     * Newest record day before [before] over the group's readable types (1 call per type, newest first, 1 record), or
     * null when Health Connect has nothing older. Used to jump over empty periods ([SyncLogic.afterEmpty]).
     */
    private suspend fun Ctx.newestBefore(g: Group, before: LocalDate): LocalDate? {
        var best: LocalDate? = null
        for (t in g.types.filter { has(it) }) {
            @Suppress("UNCHECKED_CAST")
            val k = t as KClass<Record>
            calls++
            val r = client.readRecords(ReadRecordsRequest(recordType = k, timeRangeFilter = TimeRangeFilter.between(startOf(ALL_START), startOf(before)), ascendingOrder = false, pageSize = 1))
            val d = r.records.firstOrNull()?.let { rec -> timeOf(rec)?.let { dayOf(rec, it) } } ?: continue
            if (best == null || d.isAfter(best)) best = d
        }
        return best?.let { SyncLogic.minDate(it, before.minusDays(1)) }
    }

    /** Oldest record day of a type (1 call). null = Health Connect has no record of it. Throws on errors. */
    private suspend fun Ctx.oldestDay(type: KClass<out Record>): LocalDate? {
        @Suppress("UNCHECKED_CAST")
        val k = type as KClass<Record>
        calls++
        val r = client.readRecords(
            ReadRecordsRequest(recordType = k, timeRangeFilter = TimeRangeFilter.between(startOf(ALL_START), Instant.now().plusSeconds(3600)), ascendingOrder = true, pageSize = 1)
        )
        return r.records.firstOrNull()?.let { rec -> timeOf(rec)?.let { day(it) } }
    }

    /** [backfillUnit] that never throws (except cancellation): an unexpected error ends the pass, it is retried later. */
    private suspend fun Ctx.safeUnit(failedThisPass: MutableSet<Group>): Unit_ = try {
        backfillUnit(failedThisPass)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val kind = ErrorLog.record("history", e)
        lastFailure = kind
        SyncLog.w("history: unit failed ($kind)", e)
        if (kind.pauses()) Unit_.PAUSE else Unit_.IDLE
    }

    private var enrichTick = 0

    /** Runs one unit of history work. The caller holds the mutex. */
    private suspend fun Ctx.backfillUnit(failedThisPass: MutableSet<Group>): Unit_ {
        val bf = prepare()
        // order (pure, unit-tested): today's chunk of chunked groups → whole-range groups → enrichment alternating
        // with the remaining chunks (newest cursor first) → nothing left
        val pick = SyncLogic.pickUnit(
            jobs = Group.entries.mapNotNull { bf.jobs[it.name] },
            defaultChunk = { Group.valueOf(it).chunkDays },
            // vitals first: workout zone minutes need the day's resting heart rate (review m1)
            wholeOrder = listOf(Group.VITALS, Group.BODY, Group.WORKOUTS, Group.SLEEP).map { it.name },
            failed = failedThisPass.map { it.name }.toSet(),
            enrichPending = Group.WORKOUTS !in failedThisPass && cache.unenrichedCount() > 0,
            enrichTurn = { enrichTick++ % 2 == 0 },
        )
        if (pick.kind == SyncLogic.PickKind.JOB) {
            val g = Group.valueOf(pick.job!!)
            return jobUnit(bf, g, bf.jobs.getValue(g.name), failedThisPass)
        }
        if (pick.kind == SyncLogic.PickKind.RETRY) {
            val g = Group.valueOf(pick.job!!)
            val j = bf.jobs.getValue(g.name)
            val (a, b) = SyncLogic.parseRange(j.skipped.first())
            return try {
                val st = syncGroup(g, a, b)
                saveJob(SyncLogic.afterRetry(j, true, System.currentTimeMillis()))
                SyncLog.i("history: ${g.name} skipped range $a…$b re-read: ${st.records} records")
                Unit_.WORK
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val kind = ErrorLog.record("history/retry/${g.name} $a…$b", e)
                lastFailure = kind
                SyncLog.w("history: ${g.name} skipped range $a…$b failed again ($kind), retried next pass", e)
                if (kind.pauses()) return Unit_.PAUSE
                saveJob(SyncLogic.afterRetry(j, false, System.currentTimeMillis()))
                failedThisPass += g
                Unit_.WORK
            }
        }
        if (pick.kind == SyncLogic.PickKind.ENRICH) {
            _state.update { it.copy(phase = "Workout calories…") }
            return try {
                enrichBatch(ENRICH_BATCH)
                Unit_.WORK
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val kind = ErrorLog.record("history/enrich", e)
                SyncLog.w("history: enrichment failed ($kind)", e)
                lastFailure = kind
                if (kind.pauses()) Unit_.PAUSE else { failedThisPass += Group.WORKOUTS; Unit_.WORK }
            }
        }
        // nothing left (or only jobs that failed in this pass)
        if (failedThisPass.isEmpty()) {
            val all = loadBf(cache) ?: return Unit_.IDLE
            if (all.finishedAt == null && all.jobs.values.all { it.done || it.floor == null }) {
                saveBf(cache, all.copy(finishedAt = System.currentTimeMillis()))
                Derive.ensureZoneParams(cache, goals)
                SyncLog.i("history: complete. " + all.jobs.values.joinToString { "${it.group}: ${it.days} days/${it.records} rec, floor ${it.floor ?: "-"}" })
            }
        } else {
            SyncLog.i("history: pass stops; failed this pass: ${failedThisPass.joinToString()} (retried on the next refresh)")
        }
        _state.update { it.copy(backfill = progressOf(loadBf(cache) ?: bf, null)) }
        return Unit_.IDLE
    }

    /**
     * Errors that pause the WHOLE pass (every group would fail the same way): quota, background reads not allowed,
     * Health Connect gone. IO and permission errors only stop their own group for this pass (review M1).
     */
    private fun ErrKind.pauses() = this == ErrKind.RATE_LIMIT || this == ErrKind.NOT_FOREGROUND || this == ErrKind.UNAVAILABLE

    /** Reads one chunk of one job and saves the job's progress. */
    private suspend fun Ctx.jobUnit(bf: BackfillState, g: Group, j: JobProgress, failedThisPass: MutableSet<Group>): Unit_ {
        val floor = LocalDate.parse(j.floor!!)
        val cursor = LocalDate.parse(j.cursor)
        val ch = SyncLogic.nextChunk(floor, cursor, j.chunkDays)
        if (ch == null) {
            saveJob(j.copy(done = true, updatedAt = System.currentTimeMillis()))
            return Unit_.WORK
        }
        val (from, to) = ch
        _state.update { it.copy(phase = "Reading history: ${g.label} $from", backfill = BackfillProgress(bf.floor, LocalDate.parse(bf.cursor), false, null, bf.fraction(today()))) }
        val t0 = System.currentTimeMillis()
        val c0 = calls
        try {
            val agg = g == Group.HEART && j.hrMode == "agg"
            val s = if (agg) syncHeartAgg(from, to) else syncGroup(g, from, to)
            val ms = System.currentTimeMillis() - t0
            val line = "$from…$to: ${s.records} ${if (agg) "minute buckets" else "records"} → ${s.days} days, ${calls - c0} calls, $ms ms"
            var nj = SyncLogic.afterSuccess(j, from, floor, s.records, s.days, line, g.chunkDays, System.currentTimeMillis(), calls - c0, ms)
            if (g == Group.HEART && !agg && s.days > 0) {
                // density (records AND samples per day) is re-measured on every raw chunk and the mode re-decided on the
                // first chunk and every few chunks after: raw → 1-minute aggregates as soon as those are cheaper
                val before = nj.hrMode
                nj = SyncLogic.reevaluateHr(nj, s.records, s.samples, s.days, HR_PAGE)
                SyncLog.i("history: HEART density ${"%.0f".format(nj.recordsPerDay)} records/day, ${"%.0f".format(nj.samplesPerDay)} samples/day → ${nj.hrMode}" +
                    if (before != nj.hrMode) " (switched to 1-minute aggregates, ${SyncLogic.HR_AGG_DAYS} days per call)" else "")
            }
            if (s.records == 0 && !nj.done) {
                // empty period: ask for the newest older record and jump straight to it (or finish when there is none)
                val older = newestBefore(g, from)
                nj = SyncLogic.afterEmpty(nj, from, floor, older)
                SyncLog.i("history: ${g.name} nothing in $from…$to; newest older record: ${older ?: "none"} → ${if (nj.done) "done" else "continue from ${older}"}")
            }
            val done = nj.done
            saveJob(nj)
            saveStep(StepResult("history/${g.name}", t0, true, ms, calls - c0, line))
            SyncLog.i("history: ${g.name} $line${if (done) " (done)" else ""}")
            return Unit_.WORK
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (e is Error && e !is OutOfMemoryError) throw e
            val kind = if (e is Exception) ErrorLog.record("history/${g.name} $from…$to", e) else ErrKind.OTHER.also { ErrorLog.note("history/${g.name} $from…$to", it, "out of memory") }
            lastFailure = kind
            val ms = System.currentTimeMillis() - t0
            val msg = "${kind.name}: ${e.javaClass.simpleName}: ${e.message ?: ""}".take(300)
            saveStep(StepResult("history/${g.name}", t0, false, ms, calls - c0, msg))
            if (rapid.failed(System.currentTimeMillis()) && !kind.pauses()) {
                // many failures in a short time: treat as an (unannounced) quota hit and pause the whole pass
                lastFailure = ErrKind.RATE_LIMIT
                saveJob(j.copy(lastError = "$from…$to $msg (repeated failures: paused as quota)", updatedAt = System.currentTimeMillis()))
                SyncLog.w("history: ${g.name} $from…$to: repeated rapid failures, pausing as a quota hit", e)
                return Unit_.PAUSE
            }
            if (kind == ErrKind.IO || kind == ErrKind.PERMISSION) {
                // transient / this group only: not counted toward skipping (IO shrinks after a few in a row); this group
                // waits for the next pass, the OTHER groups continue now (review M1)
                saveJob(SyncLogic.afterTransient(j, "$from…$to $msg", System.currentTimeMillis()))
                SyncLog.w("history: ${g.name} $from…$to ${kind.name.lowercase()} error: ${g.name} retried next pass, other groups continue", e)
                failedThisPass += g
                return Unit_.WORK
            }
            if (kind.pauses()) {
                saveJob(j.copy(lastError = "$from…$to $msg", updatedAt = System.currentTimeMillis()))
                SyncLog.w("history: ${g.name} $from…$to paused ($kind)", e)
                return Unit_.PAUSE
            }
            // any other error: smaller chunks next time; a single day that keeps failing is skipped (logged) so it
            // can never block the rest of this group's history
            val nj = SyncLogic.afterFailure(j, from, to, floor, msg, System.currentTimeMillis(), MAX_FAILURES)
            saveJob(nj)
            val skipped = nj.cursor != j.cursor
            SyncLog.w("history: ${g.name} $from…$to failed #${j.failures + 1}${if (skipped) ", period skipped" else ", next chunk ${nj.chunkDays} d"}", e)
            failedThisPass += g
            return Unit_.WORK
        }
    }

    private fun Ctx.saveJob(j: JobProgress) {
        val bf = loadBf(cache) ?: return
        saveBf(cache, bf.copy(jobs = bf.jobs + (j.group to j)))
    }

    private fun timeOf(r: Record): Instant? = when (r) {
        is StepsRecord -> r.startTime
        is TotalCaloriesBurnedRecord -> r.startTime
        is ActiveCaloriesBurnedRecord -> r.startTime
        is HeartRateRecord -> r.startTime
        is ExerciseSessionRecord -> r.startTime
        is SleepSessionRecord -> r.endTime
        is RestingHeartRateRecord -> r.time
        is HeartRateVariabilityRmssdRecord -> r.time
        is RespiratoryRateRecord -> r.time
        is OxygenSaturationRecord -> r.time
        is Vo2MaxRecord -> r.time
        is WeightRecord -> r.time
        is BodyFatRecord -> r.time
        is LeanBodyMassRecord -> r.time
        is HeightRecord -> r.time
        else -> null
    }

    /** The record's own zone offset (where it was recorded), if the writing app set one. */
    private fun offsetOf(r: Record): ZoneOffset? = when (r) {
        is StepsRecord -> r.startZoneOffset
        is TotalCaloriesBurnedRecord -> r.startZoneOffset
        is ActiveCaloriesBurnedRecord -> r.startZoneOffset
        is HeartRateRecord -> r.startZoneOffset
        is ExerciseSessionRecord -> r.startZoneOffset
        is SleepSessionRecord -> r.endZoneOffset
        is RestingHeartRateRecord -> r.zoneOffset
        is HeartRateVariabilityRmssdRecord -> r.zoneOffset
        is RespiratoryRateRecord -> r.zoneOffset
        is OxygenSaturationRecord -> r.zoneOffset
        is Vo2MaxRecord -> r.zoneOffset
        is WeightRecord -> r.zoneOffset
        is BodyFatRecord -> r.zoneOffset
        is LeanBodyMassRecord -> r.zoneOffset
        is HeightRecord -> r.zoneOffset
        else -> null
    }

    /** Local day of [t] for record [r]: the record's own offset, else the phone's zone (same rule for history and changes). */
    private fun Ctx.dayOf(r: Record, t: Instant): LocalDate = SyncLogic.localDay(t.toEpochMilli(), offsetOf(r), zone)

    /** Local days a changed record touches (sleep belongs to the wake-up day). */
    private fun Ctx.daysOf(r: Record): List<LocalDate> {
        fun span(a: Instant, b: Instant): List<LocalDate> {
            val d0 = day(a)
            val d1 = day(b).let { if (it.isAfter(d0.plusDays(3))) d0.plusDays(3) else it }
            return datesBetween(d0, d1)
        }
        return when (r) {
            is SleepSessionRecord -> listOf(dayOf(r, r.endTime))
            is StepsRecord -> span(r.startTime, r.endTime)
            is TotalCaloriesBurnedRecord -> span(r.startTime, r.endTime)
            is ActiveCaloriesBurnedRecord -> span(r.startTime, r.endTime)
            is HeartRateRecord -> span(r.startTime, r.endTime)
            is ExerciseSessionRecord -> span(r.startTime, r.endTime)
            else -> listOfNotNull(timeOf(r)?.let { dayOf(r, it) })
        }
    }

    // ------------------------------------------------------------------ incremental (Changes API)

    private suspend fun Ctx.newToken(types: List<KClass<out Record>>): TokenState {
        calls++
        val t = client.getChangesToken(ChangesTokenRequest(types.toSet()))
        return TokenState(t, types.map(::typeName).sorted(), System.currentTimeMillis())
    }

    /**
     * Applies all pending changes. Returns the number of (group, day) pairs re-read. Groups are independent: a group
     * whose changes can't be read or re-read keeps its old token (so it is retried) and the others still update.
     * Throws only when every group failed, or on a quota / permission error (so the step shows as failed).
     */
    private suspend fun Ctx.incremental(): Int {
        val bf = loadBf(cache) ?: return 0 // nothing cached yet: the history jobs create the tokens
        val today = today()
        val dirty = Group.entries.associateWith { HashSet<LocalDate>() }
        val newTokens = HashMap<Group, TokenState>()
        val failed = HashSet<Group>()
        var firstError: Exception? = null
        fun recent(days: Long) = datesBetween(today.minusDays(days - 1), today)

        for (g in Group.entries) {
            val types = g.types.filter { has(it) }
            if (types.isEmpty()) continue
            val names = types.map(::typeName).sorted()
            val saved = loadToken(g)
            if (saved == null || bf.jobs[g.name] == null) continue // new permission: the history job creates the token
            try {
                if (saved.types != names) {
                    // a type of this group was revoked: new token for what is left, re-read recent weeks
                    newTokens[g] = newToken(types)
                    dirty.getValue(g) += recent(35)
                    continue
                }
                var token = saved.token
                var expired = false
                val deletions = ArrayList<String>()
                var upserts = 0
                try {
                    while (true) {
                        calls++
                        val r = client.getChanges(token)
                        if (r.changesTokenExpired) { expired = true; break }
                        for (c in r.changes) when (c) {
                            is UpsertionChange -> { upserts++; dirty.getValue(g) += daysOf(c.record) }
                            is DeletionChange -> deletions += c.recordId
                        }
                        token = r.nextChangesToken
                        if (!r.hasMore) break
                    }
                } catch (e: IllegalArgumentException) {
                    // invalid / unknown token: treat like expired
                    ErrorLog.record("changes/${g.name}", e)
                    expired = true
                }
                if (expired) {
                    newTokens[g] = newToken(types)
                    // tokens live ~30 days: re-read everything since THIS group's last good sync (review m3)
                    // only THIS group's last good sync: a global "last sync" that other groups advanced must not hide
                    // this group's gap (review iteration 5)
                    val plan = SyncLogic.expiredPlan(day(saved.syncedAt), today)
                    val since = plan.since
                    if (plan.resetJob) {
                        // a long gap: let the history job read the whole group again (chunked, resumable, unlocked
                        // between units) instead of one huge locked re-read
                        bf.jobs[g.name]?.let { j -> saveJob(j.copy(cursor = today.plusDays(1).toString(), done = false, chunks = 0, chunkDays = g.chunkDays, updatedAt = System.currentTimeMillis())) }
                        dirty.getValue(g) += recent(3)
                        SyncLog.i("changes: ${g.name} token expired, last sync $since: full re-read of this group by the history job")
                    } else {
                        dirty.getValue(g) += datesBetween(since, today)
                        SyncLog.i("changes: ${g.name} token expired, re-reading since $since")
                    }
                } else {
                    newTokens[g] = saved.copy(token = token)
                }
                if (deletions.isNotEmpty()) {
                    val known = cache.lookupIndex(deletions)
                    known.values.forEach { dirty.getValue(g) += it.second }
                    // steps, calories and heart rate are not indexed (too many records), so the day is unknown (review m3):
                    //  - activity totals are cheap (2 aggregate calls per 90 days): the history job re-reads them all
                    //  - heart rate: the last 60 days now; older deleted heart rate stays until "Full re-read" (documented)
                    if (known.size < deletions.size && g == Group.ACTIVITY) {
                        bf.jobs[g.name]?.let { j -> saveJob(j.copy(cursor = today.plusDays(1).toString(), done = false, chunks = 0, chunkDays = g.chunkDays, updatedAt = System.currentTimeMillis())) }
                        dirty.getValue(g) += recent(3)
                        SyncLog.i("changes: ACTIVITY ${deletions.size - known.size} deletion(s) of unknown day: all step/calorie totals are re-read")
                    }
                    if (known.size < deletions.size && g == Group.HEART) {
                        // V2_SYNC_PERF: was recent(60) here, i.e. ~60 days of raw heart rate read INSIDE the foreground
                        // refresh (the app-open spinner) whenever a wearable app rewrote heart rate. Now: the last 3 days
                        // now, the older 57 days by the history loop in the background (HEART retry list, ≤ 30 days/unit).
                        dirty.getValue(g) += recent(3)
                        bf.jobs[g.name]?.let { j ->
                            val add = DirtyDays.hrRecheckRanges(today).filter { it !in j.skipped }
                            if (add.isNotEmpty()) saveJob(j.copy(skipped = j.skipped + add, updatedAt = System.currentTimeMillis()))
                        }
                        SyncLog.i("changes: HEART ${deletions.size - known.size} deletion(s) of unknown day: last 3 days re-read now, ${today.minusDays(59)}…${today.minusDays(3)} in the background")
                    }
                }
                if (upserts > 0 || deletions.isNotEmpty()) SyncLog.i("changes: ${g.name} $upserts upserts, ${deletions.size} deletions → ${dirty.getValue(g).size} day(s)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val kind = ErrorLog.record("changes/${g.name}", e)
                lastFailure = kind
                SyncLog.w("changes: ${g.name} failed ($kind)", e)
                failed += g; firstError = firstError ?: e
                newTokens.remove(g)
                dirty.getValue(g).clear()
                if (kind.pauses()) throw e
            }
        }

        var n = 0
        for (g in Group.entries) {
            if (g in failed) continue
            // only real days: no lower "floor" filter any more (an app may add old records, e.g. a Withings import)
            val days = dirty.getValue(g).filter { !it.isBefore(ALL_START) && !it.isAfter(today) }.toSet()
            try {
                for ((a, b) in ranges(days)) {
                    _state.update { it.copy(phase = "Updating ${g.label}: $a…$b") }
                    val s = syncGroup(g, a, b)
                    SyncLog.i("changes: re-read ${g.name} $a…$b: ${s.records} records → ${s.days} days")
                    // workout kcal depend on that day's calories: look them up again
                    if (g == Group.ACTIVITY) { cache.markWorkoutsUnenriched(startMs(a), startMs(b.plusDays(1))); activityReread += datesBetween(a, b) }
                }
                n += days.size
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val kind = ErrorLog.record("changes/reread/${g.name}", e)
                lastFailure = kind
                SyncLog.w("changes: re-read ${g.name} failed ($kind), token kept for a retry", e)
                failed += g; firstError = firstError ?: e
                newTokens.remove(g)
                if (kind.pauses()) break
            }
        }
        // only groups whose changed days were all re-read get their new token (the others retry next time)
        val now = System.currentTimeMillis()
        newTokens.forEach { (g, t) -> if (g !in failed) saveToken(g, t.copy(syncedAt = now)) }
        // kcal of new / changed sessions (a few calls)
        try {
            enrichBatch(ENRICH_BATCH)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastFailure = ErrorLog.record("changes/enrich", e)
            SyncLog.w("changes: enrichment failed", e)
        }
        val e = firstError
        if (e != null && (failed.size >= newTokens.size + failed.size || lastFailure?.pauses() == true)) throw e
        return n
    }

    // ------------------------------------------------------------------ per-group range sync (history and incremental)

    private suspend fun Ctx.syncGroup(g: Group, from: LocalDate, to: LocalDate): ReadStats = when (g) {
        Group.VITALS -> {
            var s = NONE
            s += syncInstant(RestingHeartRateRecord::class, M.RHR, Sources.Kind.WEARABLE, from, to, false, { it.time }, { it.beatsPerMinute.toDouble() })
            s += syncInstant(HeartRateVariabilityRmssdRecord::class, M.HRV, Sources.Kind.WEARABLE, from, to, false, { it.time }, { it.heartRateVariabilityMillis })
            s += syncInstant(RespiratoryRateRecord::class, M.RESP, Sources.Kind.WEARABLE, from, to, false, { it.time }, { it.rate })
            s += syncInstant(OxygenSaturationRecord::class, M.SPO2, Sources.Kind.WEARABLE, from, to, false, { it.time }, { it.percentage.value })
            s += syncInstant(Vo2MaxRecord::class, M.VO2, Sources.Kind.WEARABLE, from, to, false, { it.time }, { it.vo2MillilitersPerMinuteKilogram })
            // resting HR feeds zone minutes of the following days too (local, only days with cached heart rate)
            Derive.recomputeZones(cache, goals, from, SyncLogic.minDate(today(), to.plusDays(30)))
            // workout zone minutes depend on that day's resting HR too (review m1)
            refreshWorkoutHr(from, SyncLogic.minDate(today(), to.plusDays(30)))
            s
        }
        Group.BODY -> {
            var s = NONE
            s += syncInstant(WeightRecord::class, M.WEIGHT, Sources.Kind.BODY, from, to, true, { it.time }, { it.weight.inKilograms })
            s += syncInstant(BodyFatRecord::class, M.FAT_PCT, Sources.Kind.BODY, from, to, true, { it.time }, { it.percentage.value })
            s += syncInstant(LeanBodyMassRecord::class, M.LEAN_KG, Sources.Kind.BODY, from, to, true, { it.time }, { it.mass.inKilograms })
            s += syncInstant(HeightRecord::class, M.HEIGHT, Sources.Kind.BODY, from, to, true, { it.time }, { it.height.inMeters })
            s
        }
        Group.ACTIVITY -> syncActivity(from, to)
        Group.HEART -> syncHeart(from, to)
        Group.SLEEP -> syncSleep(from, to)
        Group.WORKOUTS -> syncWorkouts(from, to)
    }

    /** All pages of [type] in [filter] (newest-first order doesn't matter: everything is read). */
    private suspend fun <T : Record> Ctx.readPaged(type: KClass<T>, filter: TimeRangeFilter, pageSize: Int = PAGE, onPage: (List<T>) -> Unit): Int =
        SyncLogic.pageLoop(fetch = { token ->
            calls++
            val resp = client.readRecords(ReadRecordsRequest(recordType = type, timeRangeFilter = filter, pageSize = pageSize, pageToken = token))
            resp.records to resp.pageToken
        }, onPage = onPage)

    /** One value per day, from the preferred app of that day: average of its readings, or its last reading. */
    private suspend fun <T : Record> Ctx.syncInstant(
        type: KClass<T>, metric: String, kind: Sources.Kind, from: LocalDate, to: LocalDate, lastOfDay: Boolean,
        time: (T) -> Instant, value: (T) -> Double,
    ): ReadStats {
        if (!has(type)) return NONE
        val ids = ArrayList<Rec>()
        val readings = ArrayList<RawReading>()
        // one day extra on both sides: a record's own time zone can put it on a neighbouring local day
        val n = readPaged(type, filter(from.minusDays(1), to.plusDays(1))) { page ->
            page.forEach { r ->
                val t = time(r).toEpochMilli()
                val d = dayOf(r, time(r))
                if (!SyncLogic.within(d, from, to)) return@forEach
                val pkg = r.metadata.dataOrigin.packageName
                ids += Rec(r.metadata.id, pkg, t, d)
                val v = value(r)
                if (!v.isNaN() && !v.isInfinite()) readings += RawReading(d, pkg, t, v)
            }
        }
        // VO2 max read directly from the Google Health API (own table, ranks first): part of the same per-day choice,
        // so this replace never drops those days
        if (metric == M.VO2) cache.ghDaily(metric, from, to).forEach { readings += RawReading(it.day, GhApi.SOURCE, it.time, it.value) }
        val picks = SyncLogic.pickPerDay(readings.filter { SyncLogic.within(it.day, from, to) }, { Sources.rank(kind, it) }, lastOfDay)
        val rows = picks.map { DailyRow(metric, it.day, it.value, it.time, it.pkg) }
        cache.replaceDaily(listOf(metric), from, to, rows)
        bookkeep(type, from, to, ids, index = true)
        if (n > 0 && from != to) SyncLog.i("  ${typeName(type)} $from…$to: $n records (${readings.groupingBy { it.pkg }.eachCount().entries.joinToString { "${Sources.label(it.key)} ${it.value}" }}) → ${rows.size} days")
        return ReadStats(n, rows.size)
    }

    /** rec_index (for deletions) and rec_count (diagnostics) for one type and range. */
    /** A record for bookkeeping: id, app, time (ms) and the local day it was assigned to. */
    private class Rec(val id: String, val pkg: String, val time: Long, val day: LocalDate)

    private fun Ctx.bookkeep(type: KClass<out Record>, from: LocalDate, to: LocalDate, recs: List<Rec>, index: Boolean) {
        val name = typeName(type)
        val inRange = recs.filter { SyncLogic.within(it.day, from, to) }
        if (index) cache.replaceIndex(name, from, to, inRange.map { it.id to it.day })
        val counts = inRange.groupBy { it.day to it.pkg }.map { (k, v) -> RecCount(name, k.second, k.first, v.size, v.maxOf { it.time }) }
        cache.replaceCounts(name, from, to, counts)
    }

    // ------------------------------------------------------------------ activity

    private suspend fun Ctx.syncActivity(from: LocalDate, to: LocalDate): ReadStats {
        // local-time filter, one bucket per local day. The window ends NOW when it reaches today (Q3: Health Connect
        // fills total calories with resting burn up to the end of the window — a window to midnight showed the rest of
        // today's resting burn at 09:00), else at the start of the day after [to]. Same for steps and hourly values.
        val end = Kcal.windowEnd(to, today(), java.time.LocalDateTime.now(zone))
        val local = TimeRangeFilter.between(from.atStartOfDay(), end)
        var s = NONE
        if (has(StepsRecord::class)) {
            calls++
            val res = client.aggregateGroupByPeriod(AggregateGroupByPeriodRequest(setOf(StepsRecord.COUNT_TOTAL), local, Period.ofDays(1)))
            val rows = res.mapNotNull { b ->
                // no contributing app = no data (don't store zeros for days before any tracker)
                if (b.result.dataOrigins.isEmpty()) return@mapNotNull null
                val v = b.result[StepsRecord.COUNT_TOTAL] ?: return@mapNotNull null
                val d = b.startTime.toLocalDate()
                DailyRow(M.STEPS, d, v.toDouble(), dayTime(d), originsOf(b.result.dataOrigins))
            }
            cache.replaceDaily(listOf(M.STEPS), from, to, rows)
            s += ReadStats(rows.size, rows.size)
        }
        if (has(TotalCaloriesBurnedRecord::class)) s += syncCalories(from, to, end.atZone(zone).toInstant())
        // per hour for the 1D view, last 15 days only (to now, like the daily totals)
        val hFrom = SyncLogic.maxDate(from, today().minusDays(14))
        if (!hFrom.isAfter(to)) {
            val f = TimeRangeFilter.between(startOf(hFrom), end.atZone(zone).toInstant())
            val rows = ArrayList<Triple<String, Long, Double>>()
            val metrics = ArrayList<String>()
            if (has(StepsRecord::class)) {
                calls++
                metrics += M.STEPS
                client.aggregateGroupByDuration(AggregateGroupByDurationRequest(setOf(StepsRecord.COUNT_TOTAL), f, Duration.ofHours(1))).forEach { b ->
                    if (b.result.dataOrigins.isNotEmpty()) b.result[StepsRecord.COUNT_TOTAL]?.let { rows += Triple(M.STEPS, b.startTime.toEpochMilli(), it.toDouble()) }
                }
            }
            cache.replaceHourly(metrics, startMs(hFrom), startMs(to.plusDays(1)), rows)
        }
        return s
    }

    /** Packages of an aggregate's contributing apps, as stored; unknown / id-like origins are logged once (Q16). */
    private fun originsOf(origins: Set<DataOrigin>): String {
        for (o in origins) {
            val p = o.packageName
            if (SourceNames.label(p) == "Unknown app" && seenOrigins.add(p)) SyncLog.w("source: data origin '$p' has no app name (shown as \"Unknown app\")")
        }
        return origins.joinToString(",") { it.packageName }
    }
    private val seenOrigins = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * v5 (D1) Calories burned = Google Health's OWN TotalCaloriesBurned records (com.fitbit.FitbitMobile) only, summed
     * per day and per hour up to now (a record spanning a boundary is split pro rata). No Health Connect aggregate
     * (it fills days and hours nobody wrote with its resting-burn model), no other app, no active / resting split, no
     * estimate. Days without a Google Health record have no value. Hourly values: the last 15 days.
     */
    private suspend fun Ctx.syncCalories(from: LocalDate, to: LocalDate, end: Instant): ReadStats {
        val recs = ArrayList<Triple<Long, Long, Double>>()
        val idx = ArrayList<Rec>()
        val n = readPaged(TotalCaloriesBurnedRecord::class, TimeRangeFilter.between(startOf(from), end)) { page ->
            for (r in page) {
                val p = r.metadata.dataOrigin.packageName
                idx += Rec(r.metadata.id, p, r.startTime.toEpochMilli(), day(r.startTime))
                if (p == Sources.GOOGLE_HEALTH) recs += Triple(r.startTime.toEpochMilli(), r.endTime.toEpochMilli(), r.energy.inKilocalories)
            }
        }
        bookkeep(TotalCaloriesBurnedRecord::class, from, to, idx, index = true)
        val nowMs = end.toEpochMilli()
        val days = datesBetween(from, to)
        val dayBounds = LongArray(days.size + 1) { startMs(from.plusDays(it.toLong())) }
        val perDay = Kcal.bucketSums(recs, dayBounds, nowMs)
        val rows = days.indices.mapNotNull { i -> perDay[i]?.let { DailyRow(M.KCAL, days[i], it, dayTime(days[i]), Sources.GOOGLE_HEALTH) } }
        // also clears active / resting rows of older versions
        cache.replaceDaily(listOf(M.KCAL, M.ACTIVE_KCAL, M.REST_KCAL), from, to, rows)
        val hFrom = SyncLogic.maxDate(from, today().minusDays(14))
        if (!hFrom.isAfter(to)) {
            val h0 = startOf(hFrom).atZone(zone)
            val hours = ArrayList<Long>()
            var h = h0
            while (h.toInstant().toEpochMilli() < startMs(to.plusDays(1))) { hours += h.toInstant().toEpochMilli(); h = h.plusHours(1) }
            hours += startMs(to.plusDays(1))
            val perHour = Kcal.bucketSums(recs, hours.toLongArray(), nowMs)
            val hrows = perHour.indices.mapNotNull { i -> perHour[i]?.let { Triple(M.KCAL, hours[i], it) } }
            cache.replaceHourly(listOf(M.KCAL), startMs(hFrom), startMs(to.plusDays(1)), hrows)
        }
        if (from != to) SyncLog.i("  calories $from…$to: ${rows.size} day(s) from Google Health's own records ($n records read)")
        return ReadStats(n, rows.size)
    }

    // ------------------------------------------------------------------ heart rate → per-minute days, daily avg/min/max, zone minutes

    private class DayAcc {
        val sum = IntArray(HrDay.MINUTES)
        val cnt = IntArray(HrDay.MINUTES)
        var samples = 0
        var total = 0L
        var min = Long.MAX_VALUE
        var max = Long.MIN_VALUE
        var newestSample = 0L
        var records = 0
        var newestRecord = 0L
    }

    /**
     * Streams heart-rate pages into per-minute sums (per app, per day) — never holds the raw samples, so memory is
     * bounded by days × apps × 12 KB, whatever the sample rate.
     */
    private suspend fun Ctx.syncHeart(from: LocalDate, to: LocalDate): ReadStats {
        if (!has(HeartRateRecord::class)) return NONE
        val days = datesBetween(from, to)
        val n = days.size
        val starts = LongArray(n + 1) { startMs(from.plusDays(it.toLong())) }
        val acc = HashMap<String, Array<DayAcc?>>()
        var samplesRead = 0L
        // read one day extra on both sides: a heart-rate record (an interval with many samples) that starts before the
        // chunk or ends after it still has samples inside it; samples are clipped to the chunk's days with dayIndex
        val records = readPaged(HeartRateRecord::class, filter(from.minusDays(1), to.plusDays(1)), HR_PAGE) { page ->
            for (r in page) {
                val pkg = r.metadata.dataOrigin.packageName
                val perDay = acc.getOrPut(pkg) { arrayOfNulls(n) }
                // one consistent key: minutes since the PHONE's local midnight, for storage and for workout lookups (m2)
                val ri = SyncLogic.dayIndex(starts, r.startTime.toEpochMilli())
                if (ri >= 0) {
                    val a = perDay[ri] ?: DayAcc().also { perDay[ri] = it }
                    a.records++
                    a.newestRecord = maxOf(a.newestRecord, r.endTime.toEpochMilli())
                }
                samplesRead += r.samples.size
                for (s in r.samples) {
                    val bpm = s.beatsPerMinute
                    if (!SyncLogic.plausibleBpm(bpm)) continue
                    val ms = s.time.toEpochMilli()
                    val i = SyncLogic.dayIndex(starts, ms)
                    if (i < 0) continue
                    val a = perDay[i] ?: DayAcc().also { perDay[i] = it }
                    val m = SyncLogic.minuteSlot(starts[i], ms, HrDay.MINUTES)
                    a.sum[m] += bpm.toInt(); a.cnt[m]++
                    a.samples++; a.total += bpm
                    if (bpm < a.min) a.min = bpm
                    if (bpm > a.max) a.max = bpm
                    if (ms > a.newestSample) a.newestSample = ms
                }
            }
        }
        val hrDays = ArrayList<HrDay>()
        val rows = ArrayList<DailyRow>()
        val counts = ArrayList<RecCount>()
        for (i in 0 until n) {
            val d = days[i]
            acc.forEach { (pkg, perDay) -> perDay[i]?.takeIf { it.records > 0 }?.let { counts += RecCount(typeName(HeartRateRecord::class), pkg, d, it.records, it.newestRecord) } }
            val candidates = acc.mapNotNull { (pkg, perDay) -> perDay[i]?.takeIf { it.samples > 0 }?.let { pkg to it.samples } }.toMap()
            val pkg = Sources.choose(candidates, Sources.Kind.WEARABLE) ?: continue
            val a = acc.getValue(pkg)[i]!!
            val blob = ByteArray(HrDay.MINUTES) { m -> if (a.cnt[m] == 0) 0 else ((a.sum[m] + a.cnt[m] / 2) / a.cnt[m]).coerceIn(1, 255).toByte() }
            hrDays += HrDay(d, pkg, blob, starts[i]) // v4.1: minute 0 = the instant the minutes were keyed to
            rows += DailyRow(M.HR_AVG, d, a.total.toDouble() / a.samples, a.newestSample, pkg)
            rows += DailyRow(M.HR_MIN, d, a.min.toDouble(), a.newestSample, pkg)
            rows += DailyRow(M.HR_MAX, d, a.max.toDouble(), a.newestSample, pkg)
        }
        cache.replaceHr(from, to, hrDays)
        cache.replaceDaily(listOf(M.HR_AVG, M.HR_MIN, M.HR_MAX), from, to, rows)
        cache.replaceCounts(typeName(HeartRateRecord::class), from, to, counts)
        Derive.recomputeZones(cache, goals, from, to)
        // workouts of these days: heart rate and zone minutes from the (re)cached per-minute values (local)
        refreshWorkoutHr(from, to)
        return ReadStats(records, hrDays.size, samplesRead)
    }

    /**
     * Heart-rate history from 1-minute aggregates (review M2): one aggregateGroupByDuration call per
     * [SyncLogic.HR_AGG_DAYS] days instead of paging raw records. Gives the same per-minute average the raw path stores
     * (zone minutes and workout HR keep 1-minute resolution), and daily avg / min / max. Trade-off: Health Connect
     * merges all apps' heart rate in a minute instead of our one-app-per-day choice; used only for older history when
     * raw records are dense (chosen by [SyncLogic.chooseHrMode]). Returns (minute buckets with data, days).
     */
    private suspend fun Ctx.syncHeartAgg(from: LocalDate, to: LocalDate): ReadStats {
        if (!has(HeartRateRecord::class)) return NONE
        val days = datesBetween(from, to)
        val hrDays = ArrayList<HrDay>()
        val rows = ArrayList<DailyRow>()
        val counts = ArrayList<RecCount>()
        var buckets = 0
        var i = 0
        while (i < days.size) {
            val a = days[i]
            val b = days[minOf(days.size - 1, i + SyncLogic.HR_AGG_DAYS - 1)]
            calls++
            val res = client.aggregateGroupByDuration(AggregateGroupByDurationRequest(
                setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_MAX),
                TimeRangeFilter.between(startOf(a), startOf(b.plusDays(1))), Duration.ofMinutes(1)))
            val perDay = HashMap<LocalDate, ByteArray>()
            val stats = HashMap<LocalDate, LongArray>() // sum, n, min, max, newest
            val origins = HashMap<LocalDate, MutableSet<String>>()
            for (r in res) {
                if (r.result.dataOrigins.isEmpty()) continue
                val avg = r.result[HeartRateRecord.BPM_AVG] ?: continue
                // obviously bad minutes are left out of min / max / zone minutes (review iteration 5)
                if (!SyncLogic.plausibleBpm(avg)) continue
                val ms = r.startTime.toEpochMilli()
                val d = day(ms)
                if (!SyncLogic.within(d, a, b)) continue
                val m = SyncLogic.minuteSlot(startMs(d), ms, HrDay.MINUTES)
                perDay.getOrPut(d) { ByteArray(HrDay.MINUTES) }[m] = avg.coerceIn(1, 255).toInt().toByte()
                val st = stats.getOrPut(d) { longArrayOf(0, 0, Long.MAX_VALUE, Long.MIN_VALUE, 0) }
                st[0] += avg; st[1]++
                st[2] = minOf(st[2], r.result[HeartRateRecord.BPM_MIN]?.takeIf { SyncLogic.plausibleBpm(it) } ?: avg)
                st[3] = maxOf(st[3], r.result[HeartRateRecord.BPM_MAX]?.takeIf { SyncLogic.plausibleBpm(it) } ?: avg)
                st[4] = maxOf(st[4], r.endTime.toEpochMilli())
                origins.getOrPut(d) { HashSet() } += r.result.dataOrigins.map { it.packageName }
                buckets++
            }
            for ((d, blob) in perDay) {
                val st = stats.getValue(d)
                // coverage row for Data check: merged by Health Connect, n = minutes with heart rate that day
                counts += RecCount(typeName(HeartRateRecord::class), Sources.MERGED, d, st[1].toInt(), st[4])
                val pkgs = origins.getValue(d)
                val src = Sources.pick(pkgs, Sources.Kind.WEARABLE) ?: pkgs.first()
                hrDays += HrDay(d, src, blob, startMs(d)) // v4.1: minute 0 instant stored
                rows += DailyRow(M.HR_AVG, d, st[0].toDouble() / st[1], st[4], pkgs.joinToString(","))
                rows += DailyRow(M.HR_MIN, d, st[2].toDouble(), st[4], pkgs.joinToString(","))
                rows += DailyRow(M.HR_MAX, d, st[3].toDouble(), st[4], pkgs.joinToString(","))
            }
            i += SyncLogic.HR_AGG_DAYS
        }
        cache.replaceHr(from, to, hrDays)
        cache.replaceDaily(listOf(M.HR_AVG, M.HR_MIN, M.HR_MAX), from, to, rows)
        cache.replaceCounts(typeName(HeartRateRecord::class), from, to, counts)
        Derive.recomputeZones(cache, goals, from, to)
        refreshWorkoutHr(from, to)
        return ReadStats(buckets, hrDays.size)
    }

    private fun Ctx.refreshWorkoutHr(from: LocalDate, to: LocalDate) {
        val ws = cache.workoutsHc(startMs(from), startMs(to.plusDays(1)))
        if (ws.isEmpty()) return
        val hrMax = Derive.hrMax(cache, goals)
        cache.updateWorkoutHr(ws.map { w ->
            Triple(w.id, Derive.avgHr(cache, w.start, w.end, zone) ?: w.avgHr, Derive.zoneMinutes(cache, goals, w.start, w.end, hrMax, zone) ?: w.zoneMin)
        })
    }

    // ------------------------------------------------------------------ sleep

    private suspend fun Ctx.syncSleep(from: LocalDate, to: LocalDate): ReadStats {
        if (!has(SleepSessionRecord::class)) return NONE
        val all = ArrayList<SleepSessionRecord>()
        // a night belongs to the day you wake up: start reading at noon the day before
        // (and one day extra at the end: the wake-up day follows the record's own time zone)
        val n = readPaged(SleepSessionRecord::class, TimeRangeFilter.between(startOf(from).minus(Duration.ofHours(12)), startOf(to.plusDays(2)))) { all += it }
        val inRange = all.filter { SyncLogic.within(dayOf(it, it.endTime), from, to) }.distinctBy { it.metadata.id }
        val rows = ArrayList<DailyRow>()
        val stages = ArrayList<SleepStage>()
        var nights = 0
        inRange.groupBy { dayOf(it, it.endTime) }.forEach { (d, ss) ->
            val pkg = Sources.choose(ss.groupingBy { it.metadata.dataOrigin.packageName }.eachCount(), Sources.Kind.WEARABLE) ?: return@forEach
            val rec = Sources.dedupeSleep(ss.filter { it.metadata.dataOrigin.packageName == pkg })
                .maxByOrNull { Sources.sleepInfo(it).asleepMin } ?: return@forEach
            val main = Sources.sleepInfo(rec)
            // the stage timeline of the main sleep, as delivered (no stages: one "asleep" segment for the session)
            if (rec.stages.isEmpty()) stages += SleepStage(d, rec.startTime.toEpochMilli(), rec.endTime.toEpochMilli(), "asleep", pkg)
            else rec.stages.sortedBy { it.startTime }.forEach { st -> stages += SleepStage(d, st.startTime.toEpochMilli(), st.endTime.toEpochMilli(), Sources.stageName(st.stage), pkg) }
            val t = main.end
            nights++
            rows += DailyRow(M.SLEEP_MIN, d, main.asleepMin.toDouble(), t, pkg)
            rows += DailyRow(M.SLEEP_DEEP, d, main.deepMin.toDouble(), t, pkg)
            rows += DailyRow(M.SLEEP_REM, d, main.remMin.toDouble(), t, pkg)
            rows += DailyRow(M.SLEEP_LIGHT, d, main.lightMin.toDouble(), t, pkg)
            rows += DailyRow(M.SLEEP_ASLEEP, d, main.asleepUnspecifiedMin.toDouble(), t, pkg)
            rows += DailyRow(M.SLEEP_AWAKE, d, main.awakeMin.toDouble(), t, pkg)
            rows += DailyRow(M.SLEEP_START, d, main.start.toDouble(), t, pkg)
            rows += DailyRow(M.SLEEP_END, d, main.end.toDouble(), t, pkg)
        }
        cache.replaceDaily(M.SLEEP, from, to, rows)
        cache.replaceSleepStages(from, to, stages)
        bookkeep(SleepSessionRecord::class, from, to, inRange.map { Rec(it.metadata.id, it.metadata.dataOrigin.packageName, it.endTime.toEpochMilli(), dayOf(it, it.endTime)) }, index = true)
        return ReadStats(n, nights)
    }

    // ------------------------------------------------------------------ workouts

    /**
     * Stores every session that starts in [from]..[to] (all apps; duplicates are merged at query time). No extra
     * Health Connect calls: kcal are looked up later by [enrichBatch]; heart rate and zone minutes come from the
     * cached per-minute heart rate (and are refreshed when those days' heart rate is cached).
     * A session that was enriched before and did not change keeps its kcal.
     */
    private suspend fun Ctx.syncWorkouts(from: LocalDate, to: LocalDate): ReadStats {
        if (!has(ExerciseSessionRecord::class)) return NONE
        val all = ArrayList<ExerciseSessionRecord>()
        val n = readPaged(ExerciseSessionRecord::class, filter(from, to)) { all += it }
        val fromMs = startMs(from)
        val toMs = startMs(to.plusDays(1))
        val inRange = all.filter { it.startTime.toEpochMilli() in fromMs until toMs }.distinctBy { it.metadata.id }
        // v4.1 real-device fix: exact copies by one app (Withings writes the same session up to 6×: same start, end
        // and type) are not stored at all; copies with other types / apps are merged at query time (dedupeWorkouts)
        val drop = SyncLogic.exactDuplicates(inRange.map { s ->
            Triple(WorkoutSpan(s.metadata.id, s.metadata.dataOrigin.packageName, s.startTime.toEpochMilli(), s.endTime.toEpochMilli()), s.exerciseType, !s.title.isNullOrBlank())
        })
        val sessions = inRange.filter { it.metadata.id !in drop }
        if (drop.isNotEmpty()) SyncLog.i("  ExerciseSession $from…$to: ${drop.size} exact duplicate(s) dropped (same app, start, end and type)")
        val existing = cache.workoutsHc(fromMs, toMs).associateBy { it.id }
        val hrMax = Derive.hrMax(cache, goals)
        val out = sessions.map { s ->
            val pkg = s.metadata.dataOrigin.packageName
            val start = s.startTime.toEpochMilli()
            val end = s.endTime.toEpochMilli()
            val prev = existing[s.metadata.id]
            val keep = prev != null && prev.enriched && prev.start == start && prev.end == end && prev.pkg == pkg
            CachedWorkout(
                id = s.metadata.id, start = start, end = end, type = s.exerciseType, title = s.title?.takeIf { it.isNotBlank() }, pkg = pkg,
                kcal = if (keep) prev.kcal else null, kcalSource = if (keep) prev.kcalSource else null,
                avgHr = Derive.avgHr(cache, start, end, zone) ?: (if (keep) prev.avgHr else null),
                zoneMin = Derive.zoneMinutes(cache, goals, start, end, hrMax, zone),
                enriched = keep,
            )
        }
        cache.replaceWorkouts(fromMs, toMs, out)
        bookkeep(ExerciseSessionRecord::class, from, to, inRange.map { Rec(it.metadata.id, it.metadata.dataOrigin.packageName, it.startTime.toEpochMilli(), day(it.startTime)) }, index = true)
        if (sessions.isNotEmpty()) {
            val byApp = sessions.groupingBy { Sources.label(it.metadata.dataOrigin.packageName) }.eachCount()
            SyncLog.i("  ExerciseSession $from…$to: ${sessions.size} sessions (${byApp.entries.joinToString { "${it.key} ${it.value}" }}), newest ${sessions.maxOf { it.startTime }}")
        }
        return ReadStats(n, sessions.size)
    }

    /**
     * Looks up kcal (and, if the cached heart rate has none, avg HR) for up to [limit] sessions that are not enriched
     * yet, newest first. kcal: the session's own app — active calories, else its total; else Health Connect's
     * de-duplicated total over the session (never another app's tiny "active" number: the 3-kcal strength session).
     * Throws on a Health Connect error (the sessions stay stored, just without kcal).
     */
    private suspend fun Ctx.enrichBatch(limit: Int): Int {
        val list = cache.unenrichedWorkouts(limit)
        if (list.isEmpty()) return 0
        val c0 = calls
        for (w in list) {
            val e = try {
                enrich(w)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                val kind = ErrorLog.classify(ex)
                // quota / background / IO are not this session's fault: stop the batch, retry later (kcal not lost)
                if (kind.pauses() || kind == ErrKind.IO) throw ex
                // one odd session must not block the others: keep it without kcal
                ErrorLog.record("workouts/enrich ${day(w.start)}", ex)
                SyncLog.w("workouts: enrich ${day(w.start)} ${Sources.label(w.pkg)} failed, stored without kcal", ex)
                w.copy(enriched = true)
            }
            cache.updateWorkoutEnrichment(e)
        }
        SyncLog.i("workouts: enriched ${list.size} session(s) (${list.last().start.let { day(it) }}…${list.first().start.let { day(it) }}), ${calls - c0} calls, ${cache.unenrichedCount()} left")
        return list.size
    }

    private suspend fun Ctx.enrich(w: CachedWorkout): CachedWorkout {
        if (w.end <= w.start) return w.copy(enriched = true)
        val range = TimeRangeFilter.between(Instant.ofEpochMilli(w.start), Instant.ofEpochMilli(w.end))
        val own = setOf(DataOrigin(w.pkg))
        var kcal: Double? = null
        var kcalSrc: String? = null
        if (has(ActiveCaloriesBurnedRecord::class)) kcal = energy(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, range, own)?.also { kcalSrc = w.pkg }
        if (kcal == null && has(TotalCaloriesBurnedRecord::class)) kcal = energy(TotalCaloriesBurnedRecord.ENERGY_TOTAL, range, own)?.also { kcalSrc = w.pkg }
        if (kcal == null && has(TotalCaloriesBurnedRecord::class)) {
            calls++
            val r = client.aggregate(AggregateRequest(setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL), range))
            if (r.dataOrigins.isNotEmpty()) {
                kcal = r[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories?.takeIf { it >= 1.0 }
                if (kcal != null) kcalSrc = r.dataOrigins.joinToString(",") { it.packageName }
            }
        }
        var avg = Derive.avgHr(cache, w.start, w.end, zone)
        if (avg == null && has(HeartRateRecord::class)) {
            calls++
            val r = client.aggregate(AggregateRequest(setOf(HeartRateRecord.BPM_AVG), range, own))
            if (r.dataOrigins.isNotEmpty()) avg = r[HeartRateRecord.BPM_AVG]
        }
        return w.copy(kcal = kcal, kcalSource = kcalSrc, avgHr = avg ?: w.avgHr, enriched = true)
    }

    private suspend fun Ctx.energy(metric: AggregateMetric<Energy>, range: TimeRangeFilter, origins: Set<DataOrigin>): Double? {
        calls++
        val r = client.aggregate(AggregateRequest(setOf(metric), range, origins))
        if (r.dataOrigins.isEmpty()) return null
        return r[metric]?.inKilocalories?.takeIf { it >= 1.0 }
    }

    // ------------------------------------------------------------------ bookkeeping

    /** Runs a step: records duration, call count and outcome; on failure logs it and returns null. */
    private inline fun <T> Ctx.step(name: String, block: () -> T): T? {
        val t0 = System.currentTimeMillis()
        val c0 = calls
        return try {
            block().also { saveStep(StepResult(name, t0, true, System.currentTimeMillis() - t0, calls - c0)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val kind = ErrorLog.record("sync/$name", e)
            lastFailure = kind
            SyncLog.w("step $name failed ($kind)", e)
            saveStep(StepResult(name, t0, false, System.currentTimeMillis() - t0, calls - c0, "${kind.name}: ${e.javaClass.simpleName}: ${e.message ?: ""}".take(300)))
            null
        }
    }

    private val stepsSer = MapSerializer(String.serializer(), StepResult.serializer())

    private fun loadSteps(cache: Cache): Map<String, StepResult> =
        cache.meta(K_STEPS)?.let { runCatching { json.decodeFromString(stepsSer, it) }.getOrNull() } ?: emptyMap()

    private fun Ctx.saveStep(r: StepResult) {
        cache.setMeta(K_STEPS, json.encodeToString(stepsSer, loadSteps(cache) + (r.step to r)))
    }

    private fun loadBf(cache: Cache): BackfillState? =
        cache.meta(K_BACKFILL)?.let { runCatching { json.decodeFromString(BackfillState.serializer(), it) }.getOrNull() }

    private fun saveBf(cache: Cache, bf: BackfillState) = cache.setMeta(K_BACKFILL, json.encodeToString(BackfillState.serializer(), bf))

    private fun Ctx.loadToken(g: Group): TokenState? =
        cache.meta(tokenKey(g))?.let { runCatching { json.decodeFromString(TokenState.serializer(), it) }.getOrNull() }

    private fun Ctx.saveToken(g: Group, t: TokenState) = cache.setMeta(tokenKey(g), json.encodeToString(TokenState.serializer(), t))

    /** After a quota error: try again in 20 minutes (needs the background permission to read anything). */
    private fun scheduleResume(app: Context) {
        safe("schedule resume", Unit) {
            val req = OneTimeWorkRequestBuilder<RefreshWorker>().setInitialDelay(20, TimeUnit.MINUTES).build()
            WorkManager.getInstance(app).enqueueUniqueWork("healthboud-resume", ExistingWorkPolicy.REPLACE, req)
            Unit
        }
    }

    // ------------------------------------------------------------------ date helpers (pure versions in SyncLogic)

    internal fun within(d: LocalDate, from: LocalDate, to: LocalDate) = SyncLogic.within(d, from, to)
    internal fun maxDate(a: LocalDate, b: LocalDate): LocalDate = SyncLogic.maxDate(a, b)
    internal fun minDate(a: LocalDate, b: LocalDate): LocalDate = SyncLogic.minDate(a, b)
    internal fun datesBetween(from: LocalDate, to: LocalDate): List<LocalDate> = SyncLogic.datesBetween(from, to)
    internal fun ranges(days: Set<LocalDate>): List<Pair<LocalDate, LocalDate>> = SyncLogic.ranges(days)
}
