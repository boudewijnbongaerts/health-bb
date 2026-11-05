package com.boudhealth.data

import android.Manifest
import android.accounts.Account
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import com.boudhealth.App
import com.boudhealth.MainActivity
import com.boudhealth.work.Refresh
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.URL
import java.net.UnknownHostException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Read-only Google Health API connection for VO₂ max (Google Health shows it, but does not write it to Health Connect).
 *
 * - Auth: Google Identity Services [AuthorizationClient] with the app's Android OAuth client (package + SHA-1, no secret,
 *   no server). Consent once from Settings; afterwards every fetch calls authorize() again, which returns a fresh 1-hour
 *   access token without UI. Tokens stay in memory for one fetch only; nothing but [GhAccount] is stored.
 * - A 401 clears the token and retries once. Consent needed in the background → one notification that opens Settings.
 * - Fetch: `daily-vo2-max` dataPoints, 3 years back on the first run, then the last 7 days (late updates), all pages.
 *   Values go to the cache as VO₂ max of source [GhApi.SOURCE] (Cache.upsertGh), which ranks first per day.
 * - Never on the main thread, never throws: failures go to [SyncLog]; offline is skipped silently.
 */
object GoogleHealth {
    /** Owner-approval texts (vo2/TEXTS_NEW.md). */
    const val TEXT_TITLE = "Google Health"
    /** R5 #39: notification text (title [TEXT_TITLE]), the row's button when access expired, the error toast. */
    const val TEXT_RECONNECT = "Reconnect needed"
    const val TEXT_RECONNECT_BUTTON = "Reconnect"
    const val TEXT_NOT_LINKED = "Google Health account not linked"
    const val TEXT_FAILED = "Couldn't connect to Google Health"

    /** R5 #39: own notification channel "Google Health" (was filed under "Apple Health import"). */
    private const val CHANNEL = "google_health"

    private const val NOTIFICATION_ID = 4201
    private const val TIMEOUT_MS = 20_000
    private const val MAX_BODY = 8 * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    /** Filter spelling that worked last (process memory; the fallback is cheap). */
    @Volatile private var field = GhApi.FilterField.SNAKE
    @Volatile private var lastFailure: Long? = null

    private val _busy = MutableStateFlow(false)
    /** A connect is in progress (Settings disables its button). */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Error texts for a toast in Settings ([TEXT_NOT_LINKED], [TEXT_FAILED]). */
    val events: SharedFlow<String> = _events.asSharedFlow()

    private class NeedsConsent : Exception("consent needed")
    private class HttpFailure(val code: Int, status: String?) : IOException("HTTP $code${status?.let { " $it" } ?: ""}")
    private class Http(val code: Int, val body: String)
    /** Token of one fetch; [retried] = the one 401 retry is used up. */
    private class Session(val app: Context, var token: String?) { var retried = false }

    private fun store(app: Context) = (app as App).store
    private fun client(app: Context): AuthorizationClient = Identity.getAuthorizationClient(app)
    private fun request(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(GhApi.SCOPES.map { Scope(it) }).build()

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { c.resume(it) }
        addOnFailureListener { c.resumeWithException(it) }
        addOnCanceledListener { c.cancel() }
    }

    // ================================================================== Settings actions

    /**
     * "Connect" tapped. Returns the consent screen to launch (first time, or the grant expired), or null when access was
     * granted silently (the connect then finishes in the background) or failed (a toast event is emitted).
     */
    suspend fun beginConnect(context: Context): PendingIntent? {
        val app = context.applicationContext
        SyncLog.init(app)
        return try {
            val r = withContext(Dispatchers.IO) { client(app).authorize(request()).await() }
            if (r.hasResolution()) {
                r.pendingIntent ?: run { SyncLog.w("google health: consent needed but no intent"); _events.tryEmit(TEXT_FAILED); null }
            } else {
                scope.launch { connectWith(app, r) }
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncLog.w("google health: authorize failed", e)
            _events.tryEmit(TEXT_FAILED)
            null
        }
    }

    /** Result of the consent screen (ActivityResult launcher in Settings). */
    fun onConsentResult(context: Context, resultCode: Int, data: Intent?) {
        val app = context.applicationContext
        if (resultCode != Activity.RESULT_OK) { SyncLog.i("google health: consent not given"); return }
        scope.launch {
            val r = try {
                client(app).getAuthorizationResultFromIntent(data)
            } catch (e: Exception) {
                SyncLog.w("google health: consent result unreadable", e)
                _events.tryEmit(TEXT_FAILED)
                return@launch
            }
            connectWith(app, r)
        }
    }

    /** "Disconnect": forget the account (and revoke the grant, best effort). Cached VO₂ max values stay. */
    fun disconnect(context: Context) {
        val app = context.applicationContext
        scope.launch {
            try {
                // after any running fetch, so it cannot write "fetched" onto the cleared state
                mutex.withLock { disconnectLocked(app) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SyncLog.w("google health: disconnect failed", e)
            }
        }
    }

    private suspend fun disconnectLocked(app: Context) {
        store(app).googleHealthNow().email?.let { email ->
            try {
                val req = RevokeAccessRequest.builder().setAccount(Account(email, "com.google"))
                    .setScopes(GhApi.SCOPES.map { Scope(it) }).build()
                client(app).revokeAccess(req).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SyncLog.w("google health: revoke failed (flag cleared anyway)", e)
            }
        }
        store(app).updateGoogleHealth { GhAccount() }
        cancelNotification(app)
        SyncLog.i("google health: disconnected")
    }

    private suspend fun connectWith(app: Context, r: AuthorizationResult) {
        _busy.value = true
        try {
            var changed = false
            mutex.withLock {
                val email = try { r.toGoogleSignInAccount()?.email } catch (e: Exception) { null }
                val s = Session(app, r.accessToken)
                val id = get(s, GhApi.IDENTITY_URL)
                if (id.code == 400 && GhApi.isAccountNotLinked(id.body)) {
                    SyncLog.w("google health: identity 400 ${GhApi.ACCOUNT_NOT_LINKED}")
                    _events.tryEmit(TEXT_NOT_LINKED)
                    return@withLock
                }
                if (id.code !in 200..299) {
                    SyncLog.w("google health: identity HTTP ${id.code} ${GhApi.parseError(id.body)?.status.orEmpty()}")
                    _events.tryEmit(TEXT_FAILED)
                    return@withLock
                }
                val prev = store(app).googleHealthNow()
                val same = prev.email == null || email == null || prev.email == email
                store(app).updateGoogleHealth {
                    it.copy(connected = true, email = email ?: it.email, needsReconnect = false, historyDone = it.historyDone && same)
                }
                cancelNotification(app)
                SyncLog.i("google health: connected")
                changed = runFetch(app, store(app).googleHealthNow(), s)
            }
            if (changed) Refresh.publish(app)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncLog.w("google health: connect failed", e)
            _events.tryEmit(TEXT_FAILED)
        } finally {
            _busy.value = false
        }
    }

    // ================================================================== background fetch

    /**
     * Fetches VO₂ max when connected and due (every 6 hours; [force] = Sync now). Returns whether the cache changed.
     * Never throws (cancellation aside), never runs on the caller's thread.
     */
    suspend fun syncIfDue(context: Context, force: Boolean): Boolean = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        try {
            SyncLog.init(app)
            val st = store(app).googleHealthNow()
            if (!st.connected) return@withContext false
            if (st.needsReconnect && !force) return@withContext false
            if (!GhApi.due(force, st.lastFetch, lastFailure, System.currentTimeMillis())) return@withContext false
            if (!mutex.tryLock()) return@withContext false
            try {
                runFetch(app, st, Session(app, null))
            } finally {
                mutex.unlock()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncLog.w("google health: sync failed", e)
            false
        }
    }

    /** One fetch + cache write (mutex held). Returns whether `daily` changed. Handles its own failures. */
    private suspend fun runFetch(app: Context, st: GhAccount, s: Session): Boolean {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val today = LocalDate.now(zone)
        val from = GhApi.fromDate(today, st.historyDone, st.lastFetch?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() })
        return try {
            val t0 = System.currentTimeMillis()
            val points = fetchVo2(s, from)
            val rows = GhApi.rows(points, zone, now)
            val changed = Cache.get(app).upsertGh(M.VO2, rows)
            store(app).updateGoogleHealth { if (it.connected) it.copy(lastFetch = now, historyDone = true, needsReconnect = false) else it }
            lastFailure = null
            SyncLog.i("google health: VO2 max $from…$today: ${points.size} points → ${rows.size} days, $changed changed " +
                "(filter ${field.key}, ${System.currentTimeMillis() - t0} ms)")
            changed > 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: NeedsConsent) {
            onNeedsConsent(app)
            false
        } catch (e: Exception) {
            if (!offline(e)) {
                lastFailure = now
                SyncLog.w("google health: fetch failed", e)
            }
            false
        }
    }

    /** All pages of daily VO₂ max from [from] on, with the filter-casing fallback (OWNAPI.md §1). */
    private suspend fun fetchVo2(s: Session, from: LocalDate): List<Vo2Point> {
        var f = field
        var pageToken: String? = null
        var pages = 0
        val out = ArrayList<Vo2Point>()
        while (true) {
            val r = get(s, GhApi.listUrl(GhApi.filter(f, from), GhApi.PAGE_SIZE, pageToken))
            if (r.code !in 200..299) {
                val alt = GhApi.fallbackField(f, r.code, pages == 0, r.body)
                if (alt != null) {
                    SyncLog.i("google health: filter ${f.key} rejected (400), retrying with ${alt.key}")
                    f = alt
                    continue
                }
                throw HttpFailure(r.code, GhApi.parseError(r.body)?.status)
            }
            val page = GhApi.parsePage(r.body)
            out += page.points
            pages++
            field = f
            pageToken = page.next ?: break
            if (pages >= GhApi.MAX_PAGES) { SyncLog.w("google health: stopped after $pages pages"); break }
        }
        return out
    }

    // ================================================================== auth + HTTP

    /** Fresh access token without UI; [NeedsConsent] when the grant is gone or expired. */
    private suspend fun silentToken(app: Context): String {
        val r = client(app).authorize(request()).await()
        if (r.hasResolution()) throw NeedsConsent()
        return r.accessToken ?: throw IOException("no access token")
    }

    private suspend fun clearToken(app: Context, token: String) {
        try {
            client(app).clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncLog.w("google health: clearToken failed", e)
        }
    }

    /** GET with the session's token; on 401: clear the token, get a new one, retry once per session. */
    private suspend fun get(s: Session, url: String): Http {
        val t = s.token ?: silentToken(s.app).also { s.token = it }
        val r = http(url, t)
        if (r.code != 401 || s.retried) return r
        s.retried = true
        SyncLog.i("google health: 401, token cleared, retrying once")
        clearToken(s.app, t)
        val t2 = silentToken(s.app)
        s.token = t2
        return http(url, t2)
    }

    private suspend fun http(url: String, token: String): Http = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "GET"
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.useCaches = false
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.use { st ->
                val bytes = st.readNBytes(MAX_BODY)
                String(bytes, Charsets.UTF_8)
            }.orEmpty()
            Http(code, body)
        } finally {
            c.disconnect()
        }
    }

    private fun offline(e: Throwable): Boolean =
        e is UnknownHostException || e is ConnectException || e is NoRouteToHostException ||
            (e is ApiException && e.statusCode == 7) // CommonStatusCodes.NETWORK_ERROR

    // ================================================================== reconnect notification

    private suspend fun onNeedsConsent(app: Context) {
        val before = store(app).googleHealthNow()
        if (!before.needsReconnect) {
            store(app).updateGoogleHealth { it.copy(needsReconnect = true) }
            SyncLog.w("google health: consent needed again, notification posted")
            notifyReconnect(app)
        }
    }

    /** One notification: "Google Health" / "Reconnect needed" on its own channel "Google Health"; tap opens Settings. */
    private fun notifyReconnect(app: Context) {
        try {
            if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, TEXT_TITLE, NotificationManager.IMPORTANCE_LOW))   // idempotent
            val open = Intent(app, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ROUTE, "settings")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(app, NOTIFICATION_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(TEXT_TITLE)
                .setContentText(TEXT_RECONNECT)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFICATION_ID, n)
        } catch (e: Exception) {
            SyncLog.w("google health: notification failed", e)
        }
    }

    private fun cancelNotification(app: Context) {
        try { app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) } catch (e: Exception) { /* ignore */ }
    }
}
