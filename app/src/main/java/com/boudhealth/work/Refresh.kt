package com.boudhealth.work

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.boudhealth.App
import com.boudhealth.data.Cache
import com.boudhealth.data.Derive
import com.boudhealth.data.HcReader
import com.boudhealth.data.Snapshots
import com.boudhealth.data.Sync
import com.boudhealth.data.safe
import com.boudhealth.widget.DashboardWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object Refresh {
    /**
     * Sync Health Connect → cache (incremental, Changes API), rebuild the dashboard snapshot from the cache,
     * redraw the widgets. In the foreground it also starts / resumes the history backfill (in the app process).
     * In the background it needs "read in background"; without it only the snapshot and widgets are rebuilt
     * from the cache (and the Data check screen says so).
     * Returns whether the Health Connect sync succeeded.
     */
    suspend fun run(context: Context, foreground: Boolean): Boolean {
        val app = context.applicationContext
        val ok = if (HcReader.available(app)) {
            Sync.run(app, if (foreground) Sync.Mode.FOREGROUND else Sync.Mode.BACKGROUND).ok
        } else false
        publish(app)
        if (foreground) Sync.startBackfillIfNeeded(app)
        return ok
    }

    /** App-process scope for the work that follows a foreground sync (snapshot, widgets): never on the UI's critical path. */
    private val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /**
     * V2_SYNC_PERF: the app-open refresh. Only the Health Connect sync (Changes API, a few calls) is awaited, so the
     * sync indicator ends as soon as the data is in the cache. The snapshot + widget bitmaps (publish) and the history
     * loop (only when something is left to read) follow in the background. Screens read the cache themselves and
     * re-query on the Sync.version bump. Returns whether the Health Connect sync succeeded.
     */
    suspend fun runForeground(context: Context): Boolean {
        val app = context.applicationContext
        val ok = if (HcReader.available(app)) Sync.run(app, Sync.Mode.FOREGROUND).ok else false
        bg.launch {
            publish(app)
            Sync.startBackfillIfNeeded(app)
        }
        return ok
    }

    /**
     * Cache → snapshot → widgets. No Health Connect calls, so it is also what runs after goals, waist or height
     * changed (zone minutes are recomputed locally when the max heart rate changed).
     */
    suspend fun publish(context: Context) {
        val app = context.applicationContext
        val store = (app as App).store
        safe("snapshot", Unit) {
            val goals = store.goalsNow()
            val waist = store.waistNow()
            val height = store.heightNow()
            withContext(Dispatchers.IO) {
                val cache = Cache.get(app)
                Sync.mirrorManual(cache, waist, height)
                Derive.ensureZoneParams(cache, goals)
                // nothing cached yet (first start): keep "Loading…" instead of a dashboard full of zeros
                if (cache.hasAnyData()) Snapshots.build(cache, goals, height.lastOrNull()?.value) else null
            }?.let { store.saveSnapshot(it) }
            // manual entries / zone minutes rewritten above: screens re-query just those days (no-op when unchanged)
            Sync.announceWrites(app)
            Unit
        }
        safe("widget", Unit) { DashboardWidget().updateAll(app) }
    }

    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<RefreshWorker>(30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("healthboud-refresh", ExistingPeriodicWorkPolicy.KEEP, req)
        scheduleMidnight(context, androidx.work.ExistingWorkPolicy.KEEP)
    }

    /**
     * M5: redraw the widgets just after local midnight (cache only, no Health Connect), so "today" rings and tiles start
     * the new day even without a sync. The worker re-enqueues itself (APPEND_OR_REPLACE: runs after the current one).
     */
    fun scheduleMidnight(context: Context, policy: androidx.work.ExistingWorkPolicy) {
        val now = java.time.ZonedDateTime.now()
        val next = now.toLocalDate().plusDays(1).atTime(0, 1).atZone(now.zone)
        val delayMs = java.time.Duration.between(now, next).toMillis().coerceAtLeast(60_000L)
        val req = androidx.work.OneTimeWorkRequestBuilder<MidnightWorker>().setInitialDelay(delayMs, TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("healthboud-midnight", policy, req)
    }
}

class MidnightWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { Refresh.publish(applicationContext) }
        Refresh.scheduleMidnight(applicationContext, androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }
}

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Refresh.run(applicationContext, foreground = false)
        return Result.success()
    }
}
