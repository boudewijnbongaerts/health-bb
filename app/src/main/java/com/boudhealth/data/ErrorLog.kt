package com.boudhealth.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** What kind of failure it was, so the Data check screen can say what to do about it. */
enum class ErrKind(val hint: String) {
    PERMISSION("A Health Connect permission is missing. Open Health BB → Allow."),
    NOT_FOREGROUND("Health Connect refused a background read. Allow \"Access data in the background\", or open the app."),
    RATE_LIMIT("Health Connect's read quota was hit. Sync resumes automatically in about 20 minutes."),
    UNAVAILABLE("Health Connect is not available or needs an update."),
    IO("Health Connect did not answer (busy or restarting). Try again."),
    OTHER("Unexpected error."),
}

@Serializable
data class LoggedError(
    val time: Long,
    /** Which step failed, e.g. "sync/HEART 2026-09-01…2026-09-30" or "snapshot". */
    val step: String,
    /** [ErrKind] name. */
    val kind: String,
    /** Exception class, e.g. SecurityException. */
    val type: String,
    val message: String,
)

/**
 * Small persisted ring buffer (last 50) of everything that went wrong, newest first. Replaces the old silent `safe {}`:
 * each failure is still contained (one tile blank instead of a crash) but now leaves a trace for the Data check screen.
 */
object ErrorLog {
    private const val TAG = "HealthBB"
    private const val MAX = 50
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(LoggedError.serializer())
    private var file: File? = null
    private val state = MutableStateFlow<List<LoggedError>>(emptyList())

    /** Newest first. */
    val errors: StateFlow<List<LoggedError>> = state.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "errors.json")
        file = f
        state.value = try {
            if (f.exists()) json.decodeFromString(ser, f.readText()) else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun record(step: String, e: Throwable): ErrKind {
        val kind = classify(e)
        Log.w(TAG, "$step failed ($kind)", e)
        add(LoggedError(System.currentTimeMillis(), step, kind.name, e.javaClass.simpleName, (e.message ?: "").take(400)))
        return kind
    }

    /** A problem that is not an exception (e.g. "background permission missing, sync skipped"). */
    fun note(step: String, kind: ErrKind, message: String) {
        Log.i(TAG, "$step: $message")
        add(LoggedError(System.currentTimeMillis(), step, kind.name, "-", message.take(400)))
    }

    @Synchronized
    fun clear() {
        state.value = emptyList()
        file?.delete()
    }

    @Synchronized
    private fun add(err: LoggedError) {
        val list = (listOf(err) + state.value).take(MAX)
        state.value = list
        file?.let { f ->
            try {
                f.writeText(json.encodeToString(ser, list))
            } catch (e: Exception) {
                Log.w(TAG, "could not persist error log", e)
            }
        }
    }

    /**
     * Rate limit by type (review m6), not only by message: walks the cause chain for a class named like a rate-limit
     * exception, or a platform android.health.connect.HealthConnectException whose error code equals its
     * ERROR_RATE_LIMIT_EXCEEDED constant (both read by reflection, so no hard-coded numbers).
     */
    private fun isRateLimit(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 8) {
            val n = t.javaClass.name.lowercase()
            if ("ratelimit" in n || "quota" in n) return true
            if (t.javaClass.name == "android.health.connect.HealthConnectException") {
                val code = runCatching { t.javaClass.getMethod("getErrorCode").invoke(t) as Int }.getOrNull()
                val limit = runCatching { t.javaClass.getField("ERROR_RATE_LIMIT_EXCEEDED").getInt(null) }.getOrNull()
                if (code != null && code == limit) return true
            }
            val m = (t.message ?: "").lowercase()
            if ("quota" in m || "rate limit" in m || "ratelimit" in m || "rate_limit" in m) return true
            t = t.cause
            depth++
        }
        return false
    }

    fun classify(e: Throwable): ErrKind {
        val m = (e.message ?: "").lowercase()
        return when {
            isRateLimit(e) -> ErrKind.RATE_LIMIT
            e is SecurityException && ("background" in m || "foreground" in m) -> ErrKind.NOT_FOREGROUND
            e is SecurityException -> ErrKind.PERMISSION
            "not available" in m || "unavailable" in m || "not installed" in m -> ErrKind.UNAVAILABLE
            e is java.io.IOException || e is android.os.RemoteException -> ErrKind.IO
            else -> ErrKind.OTHER
        }
    }
}

/**
 * Runs [block]; on failure records it in [ErrorLog] under [step] and returns [default]. Never swallows cancellation.
 * Inline, so suspend calls are allowed inside when used from a suspend function.
 */
internal inline fun <T> safe(step: String, default: T, block: () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ErrorLog.record(step, e)
        default
    }
