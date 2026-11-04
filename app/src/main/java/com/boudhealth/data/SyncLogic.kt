package com.boudhealth.data

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/*
 * Pure sync logic: no Android / Health Connect imports, so it is unit-tested on the JVM
 * (scratchpad test: SyncLogicTest.kt, run with plain kotlinc). Sync.kt calls these.
 */

/** History progress of one [Group]. Every day ≥ [cursor] has been read; reading stops at [floor] (inclusive). */
@Serializable
data class JobProgress(
    val group: String,
    /** Readable record types when this job started (a newly granted type restarts the job). */
    val types: List<String>,
    /** Oldest day with data (1-record probe per type); null = Health Connect has no record of these types. */
    val floor: String?,
    val cursor: String,
    /** Current chunk length (≤ 0 = whole range). Shrinks after failures so one bad period can't block the rest. */
    val chunkDays: Int,
    val done: Boolean = false,
    val chunks: Int = 0,
    /** Records read so far (steps / calories: days with a total). */
    val records: Long = 0,
    /** Day rows written so far. */
    val days: Long = 0,
    /** "2026-08-02…2026-10-30: 412 records → 88 days, 3 calls, 840 ms". */
    val lastChunk: String = "",
    val lastError: String? = null,
    val failures: Int = 0,
    val updatedAt: Long = 0,
    /** Unused since iteration 2 (the probe is only a hint now); kept so stored state still decodes. */
    val floorGuessed: Boolean = false,
    /** Ranges ("2024-02-29…2024-02-29") skipped after repeated failures: retried on later passes, never forgotten. */
    val skipped: List<String> = emptyList(),
    /** What the "oldest record" probe said (diagnostics only). */
    val probe: String? = null,
    /** Consecutive transient (IO / binder) errors on the current chunk: never skip, but shrink after a few. */
    val transientErrors: Int = 0,
    /** Heart rate only: "raw" (records, per-app per-day choice) or "agg" (1-minute aggregates, far fewer calls). */
    val hrMode: String = "raw",
    /** Heart rate only: records per day, smoothed over the raw chunks read so far; drives [SyncLogic.chooseHrMode]. */
    val recordsPerDay: Double = 0.0,
    /** Heart rate only: samples per day (payload weight), smoothed like [recordsPerDay]. */
    val samplesPerDay: Double = 0.0,
    /** Totals for progress / ETA: calls, ms and days covered by successful chunks. */
    val callsTotal: Long = 0,
    val msTotal: Long = 0,
    val daysCovered: Long = 0,
)

/** One raw reading (weight, resting HR, …) as the per-day selection sees it. */
data class RawReading(val day: LocalDate, val pkg: String, val time: Long, val value: Double)

/** Result of the per-day selection: one value per day from one app. */
data class DayPick(val day: LocalDate, val pkg: String, val value: Double, val time: Long, val readings: Int)

/** A workout as the overlap merge sees it. */
/** [typed] = the session has a real exercise type (not 0 = "other workout") or a title: preferred for the merged metadata. */
data class WorkoutSpan(val id: String, val pkg: String, val start: Long, val end: Long, val typed: Boolean = false)

object SyncLogic {

    // ------------------------------------------------------------------ dates

    fun within(d: LocalDate, from: LocalDate, to: LocalDate) = !d.isBefore(from) && !d.isAfter(to)
    fun maxDate(a: LocalDate, b: LocalDate): LocalDate = if (a.isAfter(b)) a else b
    fun minDate(a: LocalDate, b: LocalDate): LocalDate = if (a.isBefore(b)) a else b

    fun datesBetween(from: LocalDate, to: LocalDate): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        var d = from
        while (!d.isAfter(to)) { out += d; d = d.plusDays(1) }
        return out
    }

    /** Days → contiguous (from, to) ranges, merging gaps of ≤ 3 days, each at most 31 days long. */
    fun ranges(days: Set<LocalDate>, maxLen: Long = 31): List<Pair<LocalDate, LocalDate>> {
        if (days.isEmpty()) return emptyList()
        val sorted = days.sorted()
        val out = ArrayList<Pair<LocalDate, LocalDate>>()
        var a = sorted.first()
        var b = a
        for (d in sorted.drop(1)) {
            if (ChronoUnit.DAYS.between(b, d) <= 4 && ChronoUnit.DAYS.between(a, d) < maxLen) b = d
            else { out += a to b; a = d; b = d }
        }
        out += a to b
        return out
    }

    /**
     * v4.1 fix (calorie days read before midnight): past days ([lookback] days back, today excluded) whose stored read
     * time is before the end of that day (end − 1 s, the time a complete day is stored with) were read while the day
     * was still running — e.g. at 23:10, then no change came in. They are re-read until complete.
     */
    fun incompleteDays(times: List<Pair<LocalDate, Long>>, today: LocalDate, dayEndMs: (LocalDate) -> Long, lookback: Long = 30): Set<LocalDate> {
        val oldest = today.minusDays(lookback)
        return times.filter { (d, t) -> d.isBefore(today) && !d.isBefore(oldest) && t < dayEndMs(d) - 1_000L }.map { it.first }.toSet()
    }

    // ------------------------------------------------------------------ paging

    /**
     * Reads every page: [fetch] gets the page token (null for the first page) and returns (records, next token).
     * Ends on a null or empty next token; a token that repeats (a server bug) or more than [maxPages] pages throw
     * instead of looping forever. Returns the number of records. Inline, so [fetch] may suspend.
     */
    inline fun <T> pageLoop(maxPages: Int = 10_000, fetch: (String?) -> Pair<List<T>, String?>, onPage: (List<T>) -> Unit): Int {
        var token: String? = null
        var n = 0
        var pages = 0
        val seen = HashSet<String>()
        do {
            val (records, next) = fetch(token)
            n += records.size
            pages++
            onPage(records)
            token = next?.takeIf { it.isNotEmpty() }
            if (token != null && !seen.add(token)) error("page token repeated after $pages pages")
            if (pages >= maxPages && token != null) error("paging did not end after $pages pages")
        } while (token != null)
        return n
    }

    // ------------------------------------------------------------------ floors and gaps

    /**
     * Where a history job stops: always [ALL_START]. The "oldest record" probe is only logged; it is never trusted as
     * a bound (in v2 a too-recent probe — 25 Oct for weight — or an empty one meant older data was never asked for).
     * Empty periods are crossed cheaply with [afterEmpty] instead.
     */
    fun initialFloor(@Suppress("UNUSED_PARAMETER") probe: LocalDate?, @Suppress("UNUSED_PARAMETER") chunked: Boolean): LocalDate = ALL_START

    /**
     * After a chunk [from]..(cursor-1) that returned NO records: ask Health Connect for the newest record before
     * [from] ([newestOlder], one call) and jump the cursor just past it, skipping the empty gap; when there is none,
     * the job is done. So a job never stops because a chunk was empty, only when Health Connect confirms nothing older.
     */
    fun afterEmpty(j: JobProgress, from: LocalDate, floor: LocalDate, newestOlder: LocalDate?): JobProgress = when {
        j.done -> j
        newestOlder == null || newestOlder.isBefore(floor) -> j.copy(cursor = floor.toString(), done = true)
        !newestOlder.isBefore(from) -> j
        else -> j.copy(cursor = newestOlder.plusDays(1).toString())
    }

    /**
     * Local day of a record: its own zone offset when it has one (where it was recorded), else [fallback] (the phone's
     * zone). Used the same way by the history read and by the Changes API, so a record always lands on one day.
     */
    fun localDay(epochMs: Long, offset: ZoneOffset?, fallback: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(offset ?: fallback).toLocalDate()

    // Heart-rate samples are keyed by the PHONE zone (minutes since the phone's local midnight) both when stored
    // and when looked up for workouts / zone minutes: one consistent key (review m2).

    // ------------------------------------------------------------------ backfill chunk planning

    /**
     * Next chunk of a backwards backfill. Everything on/after [cursor] is done; [floor] is the oldest day to read
     * (inclusive). Returns (from, to) inclusive, or null when the floor has been reached. Empty chunks are NOT a stop
     * condition: only the floor is (the old engine never stopped on empty chunks either, but a failing group did
     * block every other group; see DATA_FIX.md).
     * [chunkDays] ≤ 0 means "everything in one go" (body, vitals, sleep, workouts).
     */
    fun nextChunk(floor: LocalDate, cursor: LocalDate, chunkDays: Int): Pair<LocalDate, LocalDate>? {
        if (!cursor.isAfter(floor)) return null
        val to = cursor.minusDays(1)
        val from = if (chunkDays <= 0) floor else maxDate(floor, to.minusDays(chunkDays.toLong() - 1))
        return from to to
    }

    /** All chunks from [cursor] back to [floor] (for tests / progress estimates). */
    fun plan(floor: LocalDate, cursor: LocalDate, chunkDays: Int): List<Pair<LocalDate, LocalDate>> {
        val out = ArrayList<Pair<LocalDate, LocalDate>>()
        var c = cursor
        while (true) {
            val ch = nextChunk(floor, c, chunkDays) ?: break
            out += ch
            c = ch.first
            if (out.size > 100_000) error("runaway plan")
        }
        return out
    }

    /**
     * Smaller chunk after a non-quota failure: a whole-range read that fails becomes yearly chunks, a 30-day
     * heart-rate chunk that keeps failing becomes 7, then 2, then 1 day.
     */
    fun shrink(chunkDays: Int): Int = when {
        chunkDays <= 0 -> 365
        chunkDays > 30 -> 30
        chunkDays > 7 -> 7
        chunkDays > 2 -> 2
        else -> 1
    }

    /** Fraction of [floor]..[today] that is done when everything ≥ [cursor] is done. */
    fun fraction(floor: LocalDate, cursor: LocalDate, today: LocalDate): Float {
        val total = ChronoUnit.DAYS.between(floor, today.plusDays(1)).coerceAtLeast(1)
        val done = ChronoUnit.DAYS.between(cursor, today.plusDays(1)).coerceIn(0, total)
        return done.toFloat() / total
    }

    // ------------------------------------------------------------------ history job scheduling

    enum class PickKind { JOB, ENRICH, RETRY, NONE }
    data class Pick(val kind: PickKind, val job: String? = null)

    /**
     * What the history loop does next. Jobs that are done, have no data, or failed in this pass are skipped, so a
     * failing group can never block the others, and the loop always ends.
     *  1. the whole-range groups in [wholeOrder] (one read each)
     *  2. the first chunk (today) of every chunked group, for the dashboard
     *  3. enrichment (workout kcal), alternating with 4. ([enrichTurn])
     *  4. the remaining chunks, the job with the newest cursor first (so groups move back in time together)
     */
    fun pickUnit(
        jobs: List<JobProgress>, defaultChunk: (String) -> Int, wholeOrder: List<String>, failed: Set<String>,
        enrichPending: Boolean, enrichTurn: () -> Boolean,
    ): Pick {
        val open = jobs.filter { !it.done && it.floor != null && it.group !in failed }
        // whole-range groups first (vitals, body, workouts, sleep: one read each, what the owner looks at most), so a
        // slow or failing heart-rate / steps read can never hold them back (review M1)
        wholeOrder.firstNotNullOfOrNull { g -> open.firstOrNull { it.group == g && defaultChunk(it.group) <= 0 && it.chunkDays <= 0 } }?.let { return Pick(PickKind.JOB, it.group) }
        open.firstOrNull { defaultChunk(it.group) > 0 && it.chunks == 0 }?.let { return Pick(PickKind.JOB, it.group) }
        val rest = open.maxByOrNull { it.cursor }
        if (enrichPending && (rest == null || enrichTurn())) return Pick(PickKind.ENRICH)
        if (rest != null) return Pick(PickKind.JOB, rest.group)
        // finally: skipped ranges of finished jobs, once per pass per job
        jobs.firstOrNull { it.done && it.skipped.isNotEmpty() && it.group !in failed }?.let { return Pick(PickKind.RETRY, it.group) }
        return Pick(PickKind.NONE)
    }

    /** Job after a chunk [from]..(cursor-1) was read: cursor moves to [from]; done at the floor; chunk size recovers. */
    fun afterSuccess(j: JobProgress, from: LocalDate, floor: LocalDate, records: Int, days: Int, line: String, defaultChunk: Int, now: Long,
                     calls: Int = 0, ms: Long = 0): JobProgress =
        j.copy(
            callsTotal = j.callsTotal + calls, msTotal = j.msTotal + ms,
            daysCovered = j.daysCovered + ChronoUnit.DAYS.between(from, LocalDate.parse(j.cursor)).coerceAtLeast(0),
            cursor = from.toString(), done = !from.isAfter(floor), chunks = j.chunks + 1, records = j.records + records, days = j.days + days,
            lastChunk = line, lastError = null, failures = 0, transientErrors = 0, updatedAt = now,
            chunkDays = grow(j.chunkDays, defaultChunk),
        )

    /**
     * Chunk size after a success: a shrunk chunk doubles again — up to the group's default, and for whole-range
     * groups (default ≤ 0) back to "whole range" once it reaches ~10 years — so a few failures never leave a job
     * crawling in tiny chunks.
     */
    fun grow(chunkDays: Int, defaultChunk: Int): Int = when {
        chunkDays <= 0 -> chunkDays
        defaultChunk <= 0 -> (chunkDays * 2).let { if (it >= 3650) 0 else it }
        else -> maxOf(chunkDays, minOf(defaultChunk, chunkDays * 2))
    }

    /**
     * Job after a real failure of chunk [from]..[to] (NOT quota / IO / background / permission: those pause without
     * counting): smaller chunks next time; a 1-day chunk that failed [maxFailures] times is moved to [JobProgress.skipped]
     * (the cursor moves past it so it can't block older history) and retried on later passes ([afterRetry]).
     */
    fun afterFailure(j: JobProgress, from: LocalDate, to: LocalDate, floor: LocalDate, msg: String, now: Long, maxFailures: Int): JobProgress {
        val failures = j.failures + 1
        val skip = failures >= maxFailures && j.chunkDays == 1
        return j.copy(
            failures = if (skip) 0 else failures,
            chunkDays = shrink(j.chunkDays),
            cursor = if (skip) from.toString() else j.cursor,
            done = skip && !from.isAfter(floor),
            skipped = if (skip) j.skipped + rangeKey(from, to) else j.skipped,
            lastError = "${if (skip) "SKIPPED (retried later) " else ""}$from…$to $msg",
            updatedAt = now,
        )
    }

    /**
     * Job after a transient error (IO, binder/RemoteException, Health Connect busy): no failure is counted and nothing
     * is ever skipped; the pass pauses. Only after [maxBeforeShrink] in a row on the same job the chunk gets smaller
     * (a response too large for the binder also surfaces as an IO error).
     */
    fun afterTransient(j: JobProgress, msg: String, now: Long, maxBeforeShrink: Int = 3): JobProgress {
        val n = j.transientErrors + 1
        return if (n >= maxBeforeShrink) j.copy(transientErrors = 0, chunkDays = shrink(j.chunkDays), lastError = msg, updatedAt = now)
        else j.copy(transientErrors = n, lastError = msg, updatedAt = now)
    }

    fun rangeKey(from: LocalDate, to: LocalDate) = "$from…$to"
    fun parseRange(k: String): Pair<LocalDate, LocalDate> = k.split("…").let { LocalDate.parse(it[0]) to LocalDate.parse(it[1]) }

    /** After retrying the first skipped range: removed on success, moved to the back on failure. */
    fun afterRetry(j: JobProgress, ok: Boolean, now: Long): JobProgress {
        if (j.skipped.isEmpty()) return j
        val first = j.skipped.first()
        return j.copy(skipped = if (ok) j.skipped.drop(1) else j.skipped.drop(1) + first, updatedAt = now,
            lastError = if (ok && j.skipped.size == 1) null else j.lastError)
    }

    // ------------------------------------------------------------------ heart-rate mode, ETA, rate-limit fallback

    /** Days per 1-minute aggregate call (2 days = ≤ 2,940 buckets incl. a DST day: well under any bucket limit). */
    const val HR_AGG_DAYS = 2

    /**
     * Raw heart-rate reads are bounded both by page size (records per call) and by payload: a page of records that each
     * carry many samples is heavy (binder transfer, memory). One raw call is treated as carrying at most this many samples.
     */
    const val RAW_SAMPLES_PER_CALL = 20_000

    /** Estimated cost of raw reads per day of history, in "calls": records / page size or samples / payload budget. */
    fun rawCostPerDay(recordsPerDay: Double, samplesPerDay: Double, pageSize: Int): Double =
        maxOf(recordsPerDay / pageSize, samplesPerDay / RAW_SAMPLES_PER_CALL)

    /**
     * Heart-rate history mode: raw is kept unless 1-minute aggregates (1 call per [HR_AGG_DAYS] days, bounded payload)
     * are clearly cheaper (raw keeps the per-app per-day choice; aggregates merge apps).
     */
    fun chooseHrMode(recordsPerDay: Double, samplesPerDay: Double, pageSize: Int): String {
        val raw = rawCostPerDay(recordsPerDay, samplesPerDay, pageSize)
        val agg = 1.0 / HR_AGG_DAYS
        return if (raw > agg * 1.5) "agg" else "raw"
    }

    /** Re-evaluate the heart-rate mode every this many raw chunks (density changes over the years: new tracker, …). */
    const val HR_REEVALUATE_EVERY = 3

    /**
     * After a RAW heart-rate chunk of [days] days with [records] records and [samples] samples: update the smoothed
     * density (half old, half new; days without any record are left out so gaps don't dilute it) and, on the first
     * chunk and then every [HR_REEVALUATE_EVERY] chunks, switch raw → aggregate if aggregates became cheaper.
     * Only raw → aggregate switches (aggregates never become more expensive per day).
     */
    fun reevaluateHr(j: JobProgress, records: Int, samples: Long, daysWithData: Int, pageSize: Int): JobProgress {
        if (j.hrMode != "raw" || daysWithData <= 0) return j
        val rpd = records.toDouble() / daysWithData
        val spd = samples.toDouble() / daysWithData
        val r = if (j.recordsPerDay > 0) (j.recordsPerDay + rpd) / 2 else rpd
        val sp = if (j.samplesPerDay > 0) (j.samplesPerDay + spd) / 2 else spd
        val decide = j.chunks <= 1 || j.chunks % HR_REEVALUATE_EVERY == 0
        return j.copy(recordsPerDay = r, samplesPerDay = sp, hrMode = if (decide) chooseHrMode(r, sp, pageSize) else j.hrMode)
    }

    /** Plausible heart rate (bpm) for zones, min / max: sensor glitches outside are dropped. */
    fun plausibleBpm(bpm: Long): Boolean = bpm in 30..230

    /**
     * Re-read plan after a group's Changes token expired, from THAT group's last successful sync ([syncedAt], never a
     * global "last sync" that other groups advanced, review iteration 5): re-read [since]..today, or reset the group's
     * history job when the gap is longer than [maxDays].
     */
    data class ExpiredPlan(val since: LocalDate, val resetJob: Boolean)

    fun expiredPlan(syncedAtDay: LocalDate, today: LocalDate, maxDays: Long = 60): ExpiredPlan {
        val since = syncedAtDay.minusDays(2)
        return ExpiredPlan(since, since.isBefore(today.minusDays(maxDays)))
    }

    /** Estimated calls per day of history for a heart-rate job. */
    fun hrCallsPerDay(j: JobProgress, pageSize: Int): Double =
        if (j.hrMode == "agg") 1.0 / HR_AGG_DAYS else maxOf(rawCostPerDay(j.recordsPerDay, j.samplesPerDay, pageSize), 1.0 / 30)

    data class Eta(val daysLeft: Long, val callsLeft: Long, val minutes: Long, val quotaWindows: Long)

    /**
     * Remaining work of a job down to [oldest] (the probed oldest record; the floor is 2000 but empty years are jumped):
     * days, calls (from [callsPerDay] or the measured rate), minutes (measured ms per day; + 15 min per extra quota
     * window of [quotaPer15] calls).
     */
    fun eta(j: JobProgress, oldest: LocalDate?, callsPerDay: Double?, quotaPer15: Int = 2000): Eta? {
        if (j.done) return Eta(0, 0, 0, 0)
        val cursor = LocalDate.parse(j.cursor)
        val stop = oldest ?: return null
        val daysLeft = ChronoUnit.DAYS.between(stop, cursor).coerceAtLeast(0)
        val cpd = callsPerDay ?: if (j.daysCovered > 0) j.callsTotal.toDouble() / j.daysCovered else return null
        val calls = Math.ceil(daysLeft * cpd).toLong()
        val msPerDay = if (j.daysCovered > 0) j.msTotal.toDouble() / j.daysCovered else 200.0
        val windows = if (calls <= quotaPer15) 0 else (calls - 1) / quotaPer15
        val minutes = Math.ceil(daysLeft * msPerDay / 60_000.0).toLong() + windows * 15
        return Eta(daysLeft, calls, minutes, windows)
    }

    /**
     * Fallback rate-limit detection (review m6): [threshold] or more failures within [windowMs] are treated as a quota
     * hit even when the exception doesn't say so. Feed it the failure times; true = pause.
     */
    class RapidFailures(private val threshold: Int = 5, private val windowMs: Long = 60_000) {
        private val times = ArrayDeque<Long>()
        fun failed(now: Long): Boolean {
            times.addLast(now)
            while (times.isNotEmpty() && now - times.first() > windowMs) times.removeFirst()
            return times.size >= threshold
        }
        fun reset() = times.clear()
    }

    // ------------------------------------------------------------------ per-day source selection

    /**
     * One value per day: the app with the best [rank] (lower = preferred) that has data **that day**; ties go to the
     * app with more readings that day. [lastOfDay] = take its newest reading (body), else the average (vitals).
     * Every day that has any reading gets a value: a day where only a lower-priority app measured is kept.
     */
    fun pickPerDay(readings: List<RawReading>, rank: (String) -> Int, lastOfDay: Boolean): List<DayPick> =
        readings.groupBy { it.day }.toSortedMap().map { (d, rs) ->
            val counts = rs.groupingBy { it.pkg }.eachCount()
            val pkg = counts.keys.minWith(compareBy<String> { rank(it) }.thenByDescending { counts.getValue(it) }.thenBy { it })
            val mine = rs.filter { it.pkg == pkg }
            val v = if (lastOfDay) mine.maxBy { it.time }.value else mine.map { it.value }.average()
            DayPick(d, pkg, v, mine.maxOf { it.time }, mine.size)
        }

    /** Preferred package given counts per package: rank first, then the one with more data. */
    fun choose(countByPkg: Map<String, Int>, rank: (String) -> Int): String? =
        countByPkg.keys.minWithOrNull(compareBy<String> { rank(it) }.thenByDescending { countByPkg.getValue(it) }.thenBy { it })

    // ------------------------------------------------------------------ workouts

    /**
     * Same workout recorded by several apps (or several times by one app): one cluster per real workout. Order of
     * preference: app rank, then a typed / titled session, then the longer one. A session joins the cluster of an
     * already kept (more preferred) session when they overlap by more than half of the shorter one. Sessions shorter
     * than [minMinutes] are dropped. Each cluster's first element is its preferred session; clusters newest first.
     */
    fun clusterWorkouts(raw: List<WorkoutSpan>, rank: (String) -> Int, minMinutes: Int = 3): List<List<WorkoutSpan>> {
        val clusters = ArrayList<MutableList<WorkoutSpan>>()
        val ordered = raw.filter { (it.end - it.start) / 60_000L >= minMinutes }
            .sortedWith(compareBy<WorkoutSpan> { rank(it.pkg) }.thenByDescending { it.typed }.thenByDescending { it.end - it.start }.thenBy { it.id })
        for (w in ordered) {
            val c = clusters.firstOrNull { cl ->
                val k = cl[0]
                val overlap = minOf(k.end, w.end) - maxOf(k.start, w.start)
                overlap > 0.5 * minOf(w.end - w.start, k.end - k.start)
            }
            if (c != null) c += w else clusters += mutableListOf(w)
        }
        return clusters.sortedByDescending { cl -> cl[0].start }
    }

    /** The preferred session of each cluster ([clusterWorkouts]), newest first. */
    fun mergeWorkouts(raw: List<WorkoutSpan>, rank: (String) -> Int, minMinutes: Int = 3): List<WorkoutSpan> =
        clusterWorkouts(raw, rank, minMinutes).map { it[0] }

    /**
     * Exact copies written by one app (Withings writes a session up to 6×): same app, same exercise type, start and end
     * within [toleranceMs] (the real copies differ by < 1 s). Returns the ids to drop at sync time; per group the
     * titled copy is kept, else the smallest id. Copies with another type or from another app are merged at query time.
     */
    fun exactDuplicates(raw: List<Triple<WorkoutSpan, Int, Boolean>>, toleranceMs: Long = 2_000L): Set<String> {
        val drop = HashSet<String>()
        raw.groupBy { (w, type, _) -> w.pkg to type }.values.forEach { same ->
            val groups = ArrayList<MutableList<Triple<WorkoutSpan, Int, Boolean>>>()
            for (x in same.sortedBy { it.first.start }) {
                val g = groups.firstOrNull { g -> Math.abs(g[0].first.start - x.first.start) <= toleranceMs && Math.abs(g[0].first.end - x.first.end) <= toleranceMs }
                if (g != null) g += x else groups += mutableListOf(x)
            }
            for (g in groups) g.sortedWith(compareByDescending<Triple<WorkoutSpan, Int, Boolean>> { it.third }.thenBy { it.first.id }).drop(1).forEach { drop += it.first.id }
        }
        return drop
    }

    // ------------------------------------------------------------------ per-minute heart-rate bucketing

    /** Index of the day (in [starts], ascending day starts in epoch ms, last = end of the range) that contains [ms], or -1. */
    fun dayIndex(starts: LongArray, ms: Long): Int {
        val n = starts.size - 1
        if (n <= 0 || ms < starts[0] || ms >= starts[n]) return -1
        val i = java.util.Arrays.binarySearch(starts, ms)
        return if (i >= 0) i else -i - 2
    }

    /** Minute slot since that day's local midnight (0..[slots]-1). */
    fun minuteSlot(dayStart: Long, ms: Long, slots: Int): Int = ((ms - dayStart) / 60_000L).toInt().coerceIn(0, slots - 1)
}

/**
 * V2_SYNC_PERF: the earliest local day the cache wrote since the last [take] (pure, thread-safe). The sync hands it to
 * the version bump, so the section engine re-reads only the changed tail instead of all history.
 * [ALL] = everything changed (or unknown); null from [take] = nothing was written.
 */
class DirtyDays {
    private var min: LocalDate? = null
    @Synchronized fun mark(day: LocalDate) { val m = min; if (m == null || day.isBefore(m)) min = day }
    fun all() = mark(ALL)
    @Synchronized fun take(): LocalDate? = min.also { min = null }

    companion object {
        val ALL: LocalDate = LocalDate.MIN

        /**
         * Earliest changed day over the bumps (since, current] of a version → day log; null when one of them changed
         * everything or is not in the log (pruned, or a bump without a day).
         */
        fun changedFrom(log: Map<Long, LocalDate>, since: Long, current: Long): LocalDate? {
            if (current <= since || current - since > 512) return null
            var out: LocalDate? = null
            for (v in since + 1..current) {
                val d = log[v] ?: return null
                if (d == ALL) return null
                if (out == null || d.isBefore(out)) out = d
            }
            return out
        }

        /**
         * A heart-rate deletion of an unknown day used to re-read the last 60 days of raw heart rate INSIDE the
         * foreground refresh (≈ 40 pages for a dense wearable). Now only the last 3 days are re-read at once; these
         * ranges (≤ 30 days each) go to the HEART job's retry list and are read by the history loop in the background.
         */
        fun hrRecheckRanges(today: LocalDate): List<String> =
            listOf(SyncLogic.rangeKey(today.minusDays(59), today.minusDays(30)), SyncLogic.rangeKey(today.minusDays(29), today.minusDays(3)))
    }
}
