package com.boudhealth.data

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * Structured sync log: every sync step writes one line with counts, ranges and timings (never health values).
 * - logcat tag "HB.sync" (`adb logcat -s HB.sync:V`)
 * - persisted ring of the last [MAX] lines in files/sync_log.txt (survives "Rebuild history", which only wipes the
 *   cache), included in the Data check "Copy report" / "Copy sync log".
 * File reads and writes happen on one background thread, never on the caller's (main) thread.
 */
object SyncLog {
    const val TAG = "HB.sync"
    private const val MAX = 400
    @Volatile private var file: File? = null
    private val lines = ArrayDeque<String>()
    private val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "hb-synclog").apply { isDaemon = true } }

    /** Non-blocking: loads the persisted lines on the log thread. */
    fun init(context: Context) {
        synchronized(this) {
            if (file != null) return
            file = File(context.applicationContext.filesDir, "sync_log.txt")
        }
        io.execute {
            val f = file ?: return@execute
            try {
                if (f.exists()) {
                    val old = f.readLines().takeLast(MAX)
                    synchronized(this) {
                        val newer = lines.toList()
                        lines.clear()
                        old.forEach { lines.addLast(it) }
                        newer.forEach { lines.addLast(it) }
                        while (lines.size > MAX) lines.removeFirst()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "could not read sync log", e)
            }
        }
    }

    fun i(msg: String) = add("I", msg)
    fun w(msg: String, e: Throwable? = null) = add("W", if (e == null) msg else "$msg: ${e.javaClass.simpleName}: ${e.message ?: ""}".take(400), e)

    private fun add(level: String, msg: String, e: Throwable? = null) {
        if (level == "W") Log.w(TAG, msg, e) else Log.i(TAG, msg)
        val line = "${fmt.format(Instant.now().atZone(ZoneId.systemDefault()))} $level $msg"
        synchronized(this) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
        }
        val f = file ?: return
        io.execute {
            try {
                // rewrite occasionally, append otherwise: the file stays small (≤ ~2 × MAX lines)
                if (f.exists() && f.length() > 120_000) f.writeText(tail(MAX).joinToString("\n", postfix = "\n"))
                else f.appendText(line + "\n")
            } catch (ex: Exception) {
                Log.w(TAG, "could not persist sync log", ex)
            }
        }
    }

    /** Last [n] lines, oldest first (memory only, no I/O). */
    @Synchronized
    fun tail(n: Int = 120): List<String> = lines.toList().takeLast(n)
}
