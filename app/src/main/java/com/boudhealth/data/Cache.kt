package com.boudhealth.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.boudhealth.data.importer.IMPORT_SOURCE
import com.boudhealth.data.importer.ImportMerge
import java.time.LocalDate

/** Records per type, app and day, written by the sync (raw-read types only) for the Data check screen. */
data class RecCount(val type: String, val pkg: String, val day: LocalDate, val n: Int, val newest: Long)

/**
 * Local SQLite cache of everything read from Health Connect. Plain SQLiteOpenHelper (no Room / annotation processing).
 *
 *  daily(metric, day, value, time, source)  one value per metric per local day (see [M])
 *  hourly(metric, hour, value)              steps / kcal per hour, last 14 days only (for the 1D view)
 *  hr_day(day, source, bpm BLOB, start_ms)  per-minute heart rate, 1500 bytes per day (≈ 0.5 MB per year)
 *  workout(id, …)                           exercise sessions, all apps, enriched
 *  rec_index(id, type, day)                 record id → day, to map Changes-API deletions back to a day
 *  rec_count(type, pkg, day, n, newest)     diagnostics
 *  meta(key, value)                         change tokens, backfill state, step results, zone parameters
 *
 * All methods are blocking: call them from Dispatchers.IO.
 */
class Cache private constructor(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION), CacheReads {

    init {
        setWriteAheadLoggingEnabled(true) // UI reads while the sync writes
    }

    override fun onCreate(db: SQLiteDatabase) {
        SCHEMA.forEach { db.execSQL(it) }
        IMPORT_SCHEMA.forEach { db.execSQL(it) }
        GH_SCHEMA.forEach { db.execSQL(it) }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // a cache: drop and rebuild from Health Connect (meta goes too, so the backfill restarts)
        TABLES.forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
        onCreate(db)
        // the import tables are kept; v4 (4+) adds imp_hr_day.start_ms (old rows: null = current zone, as before)
        db.rawQuery("PRAGMA table_info(imp_hr_day)", null).use { c ->
            var has = false
            while (c.moveToNext()) if (c.getString(1) == "start_ms") has = true
            if (!has) db.execSQL("ALTER TABLE imp_hr_day ADD COLUMN start_ms INTEGER")
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = onUpgrade(db, oldVersion, newVersion)

    @Volatile private var importReady = false

    /** Import tables exist in every version (created IF NOT EXISTS on first use; never dropped by an upgrade). */
    private val db: SQLiteDatabase get() = writableDatabase.also { d ->
        if (!importReady) { IMPORT_SCHEMA.forEach { d.execSQL(it) }; GH_SCHEMA.forEach { d.execSQL(it) }; importReady = true }
    }

    private inline fun <T> tx(block: SQLiteDatabase.() -> T): T {
        val d = db
        d.beginTransaction()
        try {
            val r = d.block()
            d.setTransactionSuccessful()
            return r
        } finally {
            d.endTransaction()
        }
    }

    private inline fun <T> query(sql: String, args: Array<String>, map: (Cursor) -> T): List<T> =
        db.rawQuery(sql, args).use { c ->
            val out = ArrayList<T>(c.count.coerceAtLeast(0))
            while (c.moveToNext()) out += map(c)
            out
        }

    private fun Cursor.strOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

    // ------------------------------------------------------------------ daily

    /** Replaces [metrics] for days [from]..[to] (inclusive) with [rows]: days without a row end up empty. */
    fun replaceDaily(metrics: Collection<String>, from: LocalDate, to: LocalDate, rows: List<DailyRow>) = tx {
        // V2_SYNC_PERF: no hrSide clear here (the per-day HR side only depends on hr_day / imp_hr_day): it used to
        // drop the side of every HR day on every sync, so the next 1Y/All read re-read ~1,100 per-minute blobs
        dirty.mark(from)
        for (m in metrics) delete("daily", "metric = ? AND day BETWEEN ? AND ?", arrayOf(m, from.toString(), to.toString()))
        val cv = ContentValues()
        for (r in rows) {
            if (r.value.isNaN() || r.value.isInfinite()) continue
            cv.clear()
            cv.put("metric", r.metric); cv.put("day", r.day.toString()); cv.put("value", r.value)
            cv.put("time", r.time); cv.putOrNull("source", r.source); cv.put("est", if (r.estimated) 1 else 0)
            insertWithOnConflict("daily", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /** Columns: day, value, time, source, est (1 = calculated by Health BB). */
    private fun dayValue(c: Cursor) = DayValue(LocalDate.parse(c.getString(0)), c.getDouble(1), c.getLong(2), c.strOrNull(3), c.getInt(4) == 1)

    // ------------------------------------------------------------------ Google Health API (VO₂ max)

    /**
     * Values read directly from the Google Health API live in their own table `gh_daily` (not in [TABLES]: a rebuild or
     * a schema upgrade keeps them, like the Apple import) and are copied into `daily` under the source
     * [GhApi.SOURCE]. That source ranks first for its metric, so on its days it is what the per-day choice keeps; the
     * Health Connect sync merges the same rows into its own per-day pick (Sync.syncInstant), and [mirrorGh] puts them
     * back after "Rebuild history". Upserts by day: a day the API no longer returns keeps its last value.
     */
    fun upsertGh(metric: String, rows: List<GhRow>): Int = tx {
        val cv = ContentValues()
        for (r in rows) {
            if (r.value.isNaN() || r.value.isInfinite()) continue
            cv.clear()
            cv.put("metric", metric); cv.put("day", r.day.toString()); cv.put("value", r.value)
            cv.put("time", r.time); cv.put("api_est", if (r.estimated) 1 else 0)
            insertWithOnConflict("gh_daily", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
        mirrorGhLocked(this) // rows that changed `daily`
    }

    /** Google Health API rows of [metric] for [from]..[to], ascending. */
    fun ghDaily(metric: String, from: LocalDate, to: LocalDate): List<GhRow> =
        query("SELECT day, value, time, api_est FROM gh_daily WHERE metric = ? AND day BETWEEN ? AND ? ORDER BY day",
            arrayOf(metric, from.toString(), to.toString())) { c -> GhRow(LocalDate.parse(c.getString(0)), c.getDouble(1), c.getLong(2), c.getInt(3) == 1) }

    fun ghCount(): Int = query("SELECT COUNT(*) FROM gh_daily", emptyArray()) { it.getInt(0) }.first()

    /** Copies every `gh_daily` row into `daily` where `daily` differs (after a rebuild / upgrade). Returns rows written. */
    fun mirrorGh(): Int = tx { mirrorGhLocked(this) }

    private fun mirrorGhLocked(d: SQLiteDatabase): Int {
        val diff = "FROM gh_daily g WHERE NOT EXISTS (SELECT 1 FROM daily x WHERE x.metric = g.metric AND x.day = g.day " +
            "AND x.source = '${GhApi.SOURCE}' AND x.value = g.value AND x.time = g.time)"
        val days = d.rawQuery("SELECT MIN(day), COUNT(*) $diff", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) LocalDate.parse(c.getString(0)) to c.getInt(1) else null
        } ?: return 0
        d.execSQL("INSERT OR REPLACE INTO daily (metric, day, value, time, source, est) SELECT g.metric, g.day, g.value, g.time, '${GhApi.SOURCE}', 0 $diff")
        dirty.mark(days.first)
        return days.second
    }

    // ------------------------------------------------------------------ Apple import + Health Connect: per-day choice

    /**
     * V2_SYNC_PERF: earliest day written since the last [Sync] version bump (the engine re-reads only from there).
     * Every write that the screens can see marks it; writes of unknown days mark everything.
     */
    val dirty = DirtyDays()
    private fun markMs(ms: Long) = dirty.mark(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate())
    /** Earliest start of the given HC sessions (id-based updates), else everything. */
    private fun SQLiteDatabase.markWorkoutIds(ids: Collection<String>) {
        var min: Long? = null
        ids.chunked(500).forEach { part ->
            rawQuery("SELECT MIN(start) FROM workout WHERE id IN (${part.joinToString(",") { "?" }})", part.toTypedArray()).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0).let { if (min == null || it < min!!) min = it }
            }
        }
        min?.let(::markMs) ?: run { if (ids.isNotEmpty()) dirty.all() }
    }

    private val hasImp = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    /** Heart-rate side per day (by measured minutes), memoised: 1 = HC, 2 = import, 0 = neither. Cleared on HR writes. */
    private val hrSide = java.util.concurrent.ConcurrentHashMap<LocalDate, Int>()
    private fun invalidateCuts() = hrSide.clear()
    private fun invalidateImport() { hasImp.clear(); hrSide.clear() }

    /**
     * v4.1 fix: imported rows we calculate ourselves are marked estimated — zone/intensity minutes (zone_*, legacy hz*)
     * and the total energy (Apple delivers active + basal separately; total = their sum, calculated by us).
     */
    private val IMP_EST = "CASE WHEN metric LIKE 'zone%' OR metric LIKE 'hz%' OR metric = '${M.KCAL}' THEN 1 ELSE 0 END"

    /** Whether the Apple import has anything for this group (no import = Health Connect exactly as before). */
    fun hasImport(key: String): Boolean = hasImp.getOrPut(key) {
        when (key) {
            ImportMerge.HR -> query("SELECT EXISTS(SELECT 1 FROM imp_hr_day LIMIT 1)", emptyArray()) { it.getInt(0) == 1 }.first()
            ImportMerge.WORKOUTS -> query("SELECT EXISTS(SELECT 1 FROM imp_workout LIMIT 1)", emptyArray()) { it.getInt(0) == 1 }.first()
            else -> query("SELECT EXISTS(SELECT 1 FROM imp_daily WHERE metric = ? LIMIT 1)", arrayOf(key)) { it.getInt(0) == 1 }.first()
        }
    }

    /** Imported rows only. */
    fun dailyImp(metric: String, from: LocalDate, to: LocalDate): List<DayValue> =
        query("SELECT day, value, time, source, $IMP_EST FROM imp_daily WHERE metric = ? AND day BETWEEN ? AND ? ORDER BY day",
            arrayOf(metric, from.toString(), to.toString()), ::dayValue)

    /**
     * The side ([ImportMerge.Side]) each day of [from]..[to] uses for the group [key] ([ImportMerge.rule]); days that
     * neither side has are absent.
     */
    fun sides(key: String, from: LocalDate, to: LocalDate): Map<LocalDate, ImportMerge.Side> {
        val out = HashMap<LocalDate, ImportMerge.Side>()
        when (ImportMerge.rule(key)) {
            ImportMerge.Rule.MAX, ImportMerge.Rule.SLEEP, ImportMerge.Rule.HC_FIRST -> {
                val hc = dailyHc(key, from, to).associateBy { it.day }
                val imp = dailyImp(key, from, to).associateBy { it.day }
                for (d in hc.keys + imp.keys) {
                    val h = hc[d]; val i = imp[d]
                    when (ImportMerge.rule(key)) {
                        ImportMerge.Rule.HC_FIRST -> ImportMerge.pickHcFirst(h != null, i != null)
                        else -> ImportMerge.pickMax(h?.value, i?.value)
                    }?.let { out[d] = it }
                }
            }
            ImportMerge.Rule.HR_MINUTES -> {
                if (SyncLogic.datesBetween(from, to).any { !hrSide.containsKey(it) }) {
                    // measured minutes per day on both sides (one blob query each), memoised until the next HR write
                    val hc = HashMap<LocalDate, Int>(); val imp = HashMap<LocalDate, Int>()
                    query("SELECT day, bpm FROM hr_day WHERE day BETWEEN ? AND ?", arrayOf(from.toString(), to.toString())) { c -> LocalDate.parse(c.getString(0)) to minutesOf(c.getBlob(1)) }.forEach { hc[it.first] = it.second }
                    query("SELECT day, bpm FROM imp_hr_day WHERE day BETWEEN ? AND ?", arrayOf(from.toString(), to.toString())) { c -> LocalDate.parse(c.getString(0)) to minutesOf(c.getBlob(1)) }.forEach { imp[it.first] = it.second }
                    for (d in SyncLogic.datesBetween(from, to)) hrSide[d] = when (ImportMerge.pickMax(hc[d]?.toDouble(), imp[d]?.toDouble())) { ImportMerge.Side.HC -> 1; ImportMerge.Side.IMPORT -> 2; null -> 0 }
                }
                for (d in SyncLogic.datesBetween(from, to)) when (hrSide[d]) { 1 -> out[d] = ImportMerge.Side.HC; 2 -> out[d] = ImportMerge.Side.IMPORT }
            }
        }
        return out
    }

    private fun minutesOf(bpm: ByteArray): Int = bpm.count { it.toInt() != 0 }

    /** Per group the import has: (days from Apple, days from Health Connect) over all history (Data check). */
    fun sourceDays(): Map<String, Pair<Int, Int>> {
        val all0 = LocalDate.of(1900, 1, 1); val all1 = LocalDate.now().plusDays(1)
        return ImportMerge.KEYS.filter { hasImport(it) }.associateWith { key ->
            if (key == ImportMerge.WORKOUTS) {
                // days with a kept session from each side (after the overlap merge, Health Connect preferred)
                val zone = java.time.ZoneId.systemDefault()
                val ws = workouts(Long.MIN_VALUE / 2, Long.MAX_VALUE / 2)
                val kept = SyncLogic.mergeWorkouts(ws.map { WorkoutSpan(it.id, it.pkg, it.start, it.end) }, { Sources.workoutRank(it) })
                val byId = ws.associateBy { it.id }
                val days = kept.groupBy { java.time.Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() }
                days.values.count { l -> l.all { byId[it.id]?.pkg?.startsWith(IMPORT_SOURCE) == true } } to
                    days.values.count { l -> l.any { byId[it.id]?.pkg?.startsWith(IMPORT_SOURCE) != true } }
            } else {
                val from = if (key == ImportMerge.HR) (query("SELECT MIN(day) FROM imp_hr_day", emptyArray()) { c -> c.strOrNull(0) }.firstOrNull()?.let(LocalDate::parse)
                    ?.let { i -> query("SELECT MIN(day) FROM hr_day", emptyArray()) { c -> c.strOrNull(0) }.firstOrNull()?.let(LocalDate::parse)?.let { minOf(i, it) } ?: i } ?: all1) else all0
                ImportMerge.count(sides(key, from, all1).values)
            }
        }
    }

    /**
     * Ascending by day, [from]..[to] inclusive. With an Apple import: per day the side [ImportMerge] picks for the
     * metric's group (never both added). Without an import: Health Connect rows, unchanged.
     */
    override fun daily(metric: String, from: LocalDate, to: LocalDate): List<DayValue> {
        val hc = dailyHc(metric, from, to)
        val key = ImportMerge.groupKey(metric)
        if (!hasImport(key)) return hc
        val sides = sides(key, from, to)
        return ImportMerge.choose(hc, dailyImp(metric, from, to), { it.day }) { sides[it] }
    }

    /** Health Connect (and manual) rows only. */
    fun dailyHc(metric: String, from: LocalDate, to: LocalDate): List<DayValue> =
        query("SELECT day, value, time, source, est FROM daily WHERE metric = ? AND day BETWEEN ? AND ? ORDER BY day",
            arrayOf(metric, from.toString(), to.toString()), ::dayValue)

    /** Newest value on or before [onOrBefore] (default: any), with the same per-day choice as [daily]. */
    override fun latest(metric: String, onOrBefore: LocalDate?): DayValue? {
        val until = (onOrBefore ?: LocalDate.of(9999, 12, 31)).toString()
        val hc = query("SELECT day, value, time, source, est FROM daily WHERE metric = ? AND day <= ? ORDER BY day DESC LIMIT 1", arrayOf(metric, until), ::dayValue).firstOrNull()
        if (!hasImport(ImportMerge.groupKey(metric))) return hc
        val imp = query("SELECT day, value, time, source, $IMP_EST FROM imp_daily WHERE metric = ? AND day <= ? ORDER BY day DESC LIMIT 1", arrayOf(metric, until), ::dayValue).firstOrNull()
        val newest = ImportMerge.latest(hc, imp) { it.day } ?: return null
        // the newest day may belong to the other side under the group's rule (e.g. Apple's larger steps that day)
        return daily(metric, newest.day, newest.day).firstOrNull()
            ?: listOfNotNull(hc, imp).filter { it.day.isBefore(newest.day) }.maxByOrNull { it.day }
    }

    override fun oldestDay(metric: String): LocalDate? = daily(metric, LocalDate.of(1900, 1, 1), LocalDate.of(2999, 12, 31)).firstOrNull()?.day

    fun maxValue(metric: String, from: LocalDate, to: LocalDate): Double? = daily(metric, from, to).maxOfOrNull { it.value }

    /** metric → number of days with a value, oldest and newest day. */
    fun dailyStats(): List<Triple<String, Int, Pair<LocalDate, LocalDate>>> =
        query("SELECT metric, COUNT(*), MIN(day), MAX(day) FROM daily GROUP BY metric ORDER BY metric", emptyArray()) { c ->
            Triple(c.getString(0), c.getInt(1), LocalDate.parse(c.getString(2)) to LocalDate.parse(c.getString(3)))
        }

    override fun hasAnyData(): Boolean =
        query("SELECT EXISTS(SELECT 1 FROM daily LIMIT 1) OR EXISTS(SELECT 1 FROM workout LIMIT 1) OR EXISTS(SELECT 1 FROM imp_daily LIMIT 1)", emptyArray()) { it.getInt(0) == 1 }.firstOrNull() == true

    // ------------------------------------------------------------------ sleep stages (main sleep per wake-up day)

    fun replaceSleepStages(from: LocalDate, to: LocalDate, list: List<SleepStage>) = tx {
        dirty.mark(from)
        delete("sleep_stage", "day BETWEEN ? AND ?", arrayOf(from.toString(), to.toString()))
        insertStages("sleep_stage", list)
    }

    private fun SQLiteDatabase.insertStages(table: String, list: List<SleepStage>) {
        val cv = ContentValues()
        for (st in list) {
            cv.clear(); cv.put("day", st.day.toString()); cv.put("start", st.start); cv.put("end_ms", st.end); cv.put("stage", st.stage); cv.putOrNull("source", st.source)
            insert(table, null, cv)
        }
    }

    private fun stageOf(c: Cursor) = SleepStage(LocalDate.parse(c.getString(0)), c.getLong(1), c.getLong(2), c.getString(3), c.strOrNull(4))

    /** Stage timeline of the night that ends on [day] (wake-up day), with the same per-night choice as [daily]. */
    override fun sleepStages(day: LocalDate): List<SleepStage> {
        val hc = query("SELECT day, start, end_ms, stage, source FROM sleep_stage WHERE day = ? ORDER BY start", arrayOf(day.toString()), ::stageOf)
        if (!hasImport(M.SLEEP_MIN)) return hc
        return if (sides(M.SLEEP_MIN, day, day)[day] != ImportMerge.Side.IMPORT) hc
        else query("SELECT day, start, end_ms, stage, source FROM imp_sleep_stage WHERE day = ? ORDER BY start", arrayOf(day.toString()), ::stageOf)
    }

    // ------------------------------------------------------------------ hourly

    fun replaceHourly(metrics: Collection<String>, fromMs: Long, toMs: Long, rows: List<Triple<String, Long, Double>>) = tx {
        markMs(fromMs)
        for (m in metrics) delete("hourly", "metric = ? AND hour >= ? AND hour < ?", arrayOf(m, fromMs.toString(), toMs.toString()))
        val cv = ContentValues()
        for ((m, h, v) in rows) {
            cv.clear(); cv.put("metric", m); cv.put("hour", h); cv.put("value", v)
            insertWithOnConflict("hourly", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
        // keep the table small: only the last ~15 days are ever shown per hour
        delete("hourly", "hour < ?", arrayOf((System.currentTimeMillis() - 16L * 86_400_000L).toString()))
    }

    /** (hour start epoch ms, value), ascending. Empty if that period is not cached. */
    override fun hourly(metric: String, fromMs: Long, toMs: Long): List<Pair<Long, Double>> =
        query("SELECT hour, value FROM hourly WHERE metric = ? AND hour >= ? AND hour < ? ORDER BY hour",
            arrayOf(metric, fromMs.toString(), toMs.toString())) { c -> c.getLong(0) to c.getDouble(1) }

    // ------------------------------------------------------------------ heart rate per minute

    fun replaceHr(from: LocalDate, to: LocalDate, days: List<HrDay>) = tx {
        // only the re-read days lose their memoised HR side (V2_SYNC_PERF; was: every day)
        SyncLogic.datesBetween(from, to).forEach { hrSide.remove(it) }
        dirty.mark(from)
        delete("hr_day", "day BETWEEN ? AND ?", arrayOf(from.toString(), to.toString()))
        val cv = ContentValues()
        for (d in days) {
            cv.clear(); cv.put("day", d.day.toString()); cv.put("source", d.source); cv.put("bpm", d.bpm); cv.putOrNull("start_ms", d.startMs)
            insertWithOnConflict("hr_day", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    private fun hrDayOf(c: Cursor) = HrDay(LocalDate.parse(c.getString(0)), c.getString(1), c.getBlob(2), if (c.isNull(3)) null else c.getLong(3))

    /** Per-minute heart rate: per day the side with more measured minutes ([sides]). */
    override fun hrDays(from: LocalDate, to: LocalDate): List<HrDay> {
        val hc = hrDaysHc(from, to)
        if (!hasImport(ImportMerge.HR)) return hc
        val sides = sides(ImportMerge.HR, from, to)
        return ImportMerge.choose(hc, hrDaysImport(from, to), { it.day }) { sides[it] }
    }

    /** Health Connect days only (what the sync derives zone minutes from). */
    fun hrDaysHc(from: LocalDate, to: LocalDate): List<HrDay> =
        query("SELECT day, source, bpm, start_ms FROM hr_day WHERE day BETWEEN ? AND ? ORDER BY day", arrayOf(from.toString(), to.toString()), ::hrDayOf)

    fun hrDaysImport(from: LocalDate, to: LocalDate): List<HrDay> =
        query("SELECT day, source, bpm, start_ms FROM imp_hr_day WHERE day BETWEEN ? AND ? ORDER BY day", arrayOf(from.toString(), to.toString()), ::hrDayOf)

    override fun hrDay(day: LocalDate): HrDay? = hrDays(day, day).firstOrNull()

    override fun latestHrDay(): HrDay? {
        val hc = query("SELECT day, source, bpm, start_ms FROM hr_day ORDER BY day DESC LIMIT 1", emptyArray(), ::hrDayOf).firstOrNull()
        if (!hasImport(ImportMerge.HR)) return hc
        val imp = query("SELECT day, source, bpm, start_ms FROM imp_hr_day ORDER BY day DESC LIMIT 1", emptyArray(), ::hrDayOf).firstOrNull()
        val newest = ImportMerge.latest(hc, imp) { it.day } ?: return null
        return hrDay(newest.day) ?: newest
    }

    fun importHrDayRange(): Pair<LocalDate, LocalDate>? =
        query("SELECT MIN(day), MAX(day) FROM imp_hr_day", emptyArray()) { c ->
            if (c.isNull(0)) null else LocalDate.parse(c.getString(0)) to LocalDate.parse(c.getString(1))
        }.firstOrNull()

    /** Health Connect heart-rate days only (sync / diagnostics). */
    fun hrDayRange(): Pair<LocalDate, LocalDate>? =
        query("SELECT MIN(day), MAX(day) FROM hr_day", emptyArray()) { c ->
            if (c.isNull(0)) null else LocalDate.parse(c.getString(0)) to LocalDate.parse(c.getString(1))
        }.firstOrNull()

    // ------------------------------------------------------------------ workouts

    private fun ContentValues.putOrNull(k: String, v: Double?) { if (v == null) putNull(k) else put(k, v) }
    private fun ContentValues.putOrNull(k: String, v: Long?) { if (v == null) putNull(k) else put(k, v) }
    private fun ContentValues.putOrNull(k: String, v: Int?) { if (v == null) putNull(k) else put(k, v) }
    private fun ContentValues.putOrNull(k: String, v: String?) { if (v == null) putNull(k) else put(k, v) }

    private fun ContentValues.putWorkout(w: CachedWorkout) {
        put("id", w.id); put("start", w.start); put("end_ms", w.end); put("type", w.type)
        putOrNull("title", w.title); put("pkg", w.pkg); putOrNull("kcal", w.kcal); putOrNull("avg_hr", w.avgHr)
        putOrNull("zone_min", w.zoneMin); putOrNull("kcal_source", w.kcalSource); put("enriched", if (w.enriched) 1 else 0)
    }

    /** Replaces all sessions that start in [fromMs, toMs). */
    fun replaceWorkouts(fromMs: Long, toMs: Long, list: List<CachedWorkout>) = tx {
        markMs(fromMs)
        delete("workout", "start >= ? AND start < ?", arrayOf(fromMs.toString(), toMs.toString()))
        val cv = ContentValues()
        for (w in list) {
            cv.clear()
            cv.putWorkout(w)
            insertWithOnConflict("workout", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /** Writes the enrichment (kcal, avg HR, zone minutes) of one session and marks it enriched. */
    fun updateWorkoutEnrichment(w: CachedWorkout) = tx {
        markMs(w.start)
        val cv = ContentValues()
        cv.putOrNull("kcal", w.kcal); cv.putOrNull("kcal_source", w.kcalSource); cv.putOrNull("avg_hr", w.avgHr)
        cv.putOrNull("zone_min", w.zoneMin); cv.put("enriched", if (w.enriched) 1 else 0)
        update("workout", cv, "id = ?", arrayOf(w.id))
    }

    /** Local re-computation of heart-rate values (after heart rate of those days was (re)cached): (id, avg HR, zone min). */
    fun updateWorkoutHr(updates: List<Triple<String, Long?, Int?>>) = tx {
        markWorkoutIds(updates.map { it.first })
        val cv = ContentValues()
        for ((id, avg, zm) in updates) {
            cv.clear(); cv.putOrNull("avg_hr", avg); cv.putOrNull("zone_min", zm)
            update("workout", cv, "id = ?", arrayOf(id))
        }
    }

    /** Sessions starting in [fromMs, toMs) need their kcal looked up again (calories of those days changed). */
    fun markWorkoutsUnenriched(fromMs: Long, toMs: Long) = tx {
        markMs(fromMs)
        val cv = ContentValues(); cv.put("enriched", 0)
        update("workout", cv, "start >= ? AND start < ?", arrayOf(fromMs.toString(), toMs.toString()))
    }

    /** Sessions not enriched yet, newest first. */
    fun unenrichedWorkouts(limit: Int): List<CachedWorkout> =
        query("SELECT $W_COLS FROM workout WHERE enriched = 0 ORDER BY start DESC LIMIT $limit", emptyArray(), ::workoutOf)

    fun unenrichedCount(): Int = query("SELECT COUNT(*) FROM workout WHERE enriched = 0", emptyArray()) { it.getInt(0) }.first()

    /** (count, oldest start, newest start) of all cached sessions (all apps). */
    fun workoutRange(): Triple<Int, Long?, Long?> =
        query("SELECT COUNT(*), MIN(start), MAX(start) FROM workout", emptyArray()) { c ->
            Triple(c.getInt(0), if (c.isNull(1)) null else c.getLong(1), if (c.isNull(2)) null else c.getLong(2))
        }.first()

    fun hrDayCount(): Int = query("SELECT COUNT(*) FROM hr_day", emptyArray()) { it.getInt(0) }.first()

    private fun workoutOf(c: Cursor) = CachedWorkout(
        id = c.getString(0), start = c.getLong(1), end = c.getLong(2), type = c.getInt(3), title = c.strOrNull(4),
        pkg = c.getString(5), kcal = if (c.isNull(6)) null else c.getDouble(6), avgHr = if (c.isNull(7)) null else c.getLong(7),
        zoneMin = if (c.isNull(8)) null else c.getInt(8), kcalSource = c.strOrNull(9), enriched = c.getInt(10) == 1,
    )

    /**
     * Sessions that start in [fromMs, toMs), oldest first: Health Connect (all apps; [Sources.dedupeWorkouts] merges
     * overlapping sessions at query time) and the Apple Health import. v4.1: BOTH sides, always; the same workout on
     * both is merged at query time by the overlap rule with Health Connect preferred ([Sources.workoutRank]).
     */
    override fun workouts(fromMs: Long, toMs: Long): List<CachedWorkout> {
        val hc = workoutsHc(fromMs, toMs)
        if (!hasImport(ImportMerge.WORKOUTS)) return hc
        val imp = query("SELECT $W_COLS FROM imp_workout WHERE start >= ? AND start < ? ORDER BY start",
            arrayOf(fromMs.toString(), toMs.toString()), ::workoutOf)
        return (imp + hc).sortedBy { it.start }
    }

    /** Health Connect sessions only (what the sync writes and enriches). */
    fun workoutsHc(fromMs: Long, toMs: Long): List<CachedWorkout> =
        query("SELECT $W_COLS FROM workout WHERE start >= ? AND start < ? ORDER BY start",
            arrayOf(fromMs.toString(), toMs.toString()), ::workoutOf)

    /** Local re-computation of workout zone minutes (after the max heart rate changed). */
    fun updateWorkoutZones(updates: List<Pair<String, Int?>>) = tx {
        markWorkoutIds(updates.map { it.first })
        val cv = ContentValues()
        for ((id, zm) in updates) {
            cv.clear(); cv.putOrNull("zone_min", zm)
            update("workout", cv, "id = ?", arrayOf(id))
        }
    }

    fun workoutCount(): Int = query("SELECT COUNT(*) FROM workout", emptyArray()) { it.getInt(0) }.first()

    // ------------------------------------------------------------------ record index (Changes API deletions)

    fun replaceIndex(type: String, from: LocalDate, to: LocalDate, rows: List<Pair<String, LocalDate>>) = tx {
        delete("rec_index", "type = ? AND day BETWEEN ? AND ?", arrayOf(type, from.toString(), to.toString()))
        val cv = ContentValues()
        for ((id, day) in rows) {
            cv.clear(); cv.put("id", id); cv.put("type", type); cv.put("day", day.toString())
            insertWithOnConflict("rec_index", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /** id → (type, day) for the ids we have seen. */
    fun lookupIndex(ids: Collection<String>): Map<String, Pair<String, LocalDate>> {
        val out = HashMap<String, Pair<String, LocalDate>>()
        ids.chunked(500).forEach { part ->
            val q = part.joinToString(",") { "?" }
            query("SELECT id, type, day FROM rec_index WHERE id IN ($q)", part.toTypedArray()) { c ->
                c.getString(0) to (c.getString(1) to LocalDate.parse(c.getString(2)))
            }.forEach { out[it.first] = it.second }
        }
        return out
    }

    // ------------------------------------------------------------------ record counts (diagnostics)

    fun replaceCounts(type: String, from: LocalDate, to: LocalDate, rows: List<RecCount>) = tx {
        delete("rec_count", "type = ? AND day BETWEEN ? AND ?", arrayOf(type, from.toString(), to.toString()))
        val cv = ContentValues()
        for (r in rows) {
            cv.clear(); cv.put("type", r.type); cv.put("pkg", r.pkg); cv.put("day", r.day.toString()); cv.put("n", r.n); cv.put("newest", r.newest)
            insertWithOnConflict("rec_count", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /** Per app: (records all time, records since [since], oldest day, newest record time). */
    fun countsByPkg(type: String, since: LocalDate): List<CountSummary> =
        query("SELECT pkg, SUM(n), SUM(CASE WHEN day >= ? THEN n ELSE 0 END), MIN(day), MAX(newest) FROM rec_count WHERE type = ? GROUP BY pkg",
            arrayOf(since.toString(), type)) { c ->
            CountSummary(c.getString(0), c.getInt(1), c.getInt(2), LocalDate.parse(c.getString(3)), c.getLong(4))
        }

    data class CountSummary(val pkg: String, val all: Int, val recent: Int, val oldest: LocalDate, val newest: Long)

    // ------------------------------------------------------------------ meta

    override fun meta(key: String): String? =
        query("SELECT value FROM meta WHERE key = ?", arrayOf(key)) { it.strOrNull(0) }.firstOrNull()

    fun setMeta(key: String, value: String?) {
        // the engine's max HR comes from "zone_params": a change means every zone value was recomputed
        if (key == "zone_params") dirty.all()
        if (value == null) db.delete("meta", "key = ?", arrayOf(key))
        else db.insertWithOnConflict("meta", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun metaKeys(prefix: String): List<String> =
        query("SELECT key FROM meta WHERE key LIKE ?", arrayOf("$prefix%")) { it.getString(0) }

    /** Wipes everything (Rebuild history). */
    /** Wipes the Health Connect cache (Rebuild history / Full re-read). The Apple Health import tables are NOT touched. */
    fun clearAll() = tx { invalidateCuts(); dirty.all(); TABLES.forEach { delete(it, null, null) } }

    // ------------------------------------------------------------------ Apple Health import (separate tables, never wiped by a rebuild)

    /** Replaces the whole import in one transaction (a re-import replaces only the import). */
    fun replaceImport(rows: List<DailyRow>, hrDays: List<HrDay>, workouts: List<CachedWorkout>, meta: Map<String, String>, stages: List<SleepStage> = emptyList()) = tx {
        invalidateImport()
        dirty.all()
        IMPORT_TABLES.forEach { delete(it, null, null) }
        insertStages("imp_sleep_stage", stages)
        val cv = ContentValues()
        for (r in rows) {
            if (r.value.isNaN() || r.value.isInfinite()) continue
            cv.clear(); cv.put("metric", r.metric); cv.put("day", r.day.toString()); cv.put("value", r.value)
            cv.put("time", r.time); cv.putOrNull("source", r.source)
            insertWithOnConflict("imp_daily", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
        for (d in hrDays) {
            cv.clear(); cv.put("day", d.day.toString()); cv.put("source", d.source); cv.put("bpm", d.bpm); cv.putOrNull("start_ms", d.startMs)
            insertWithOnConflict("imp_hr_day", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
        for (w in workouts) {
            cv.clear(); cv.putWorkout(w)
            insertWithOnConflict("imp_workout", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
        for ((k, v) in meta) {
            cv.clear(); cv.put("key", k); cv.put("value", v)
            insertWithOnConflict("imp_meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /** Import rows of one metric (to recompute zone minutes locally). */
    fun replaceImportDaily(metric: String, from: LocalDate, to: LocalDate, rows: List<DailyRow>) = tx {
        dirty.mark(from)
        delete("imp_daily", "metric = ? AND day BETWEEN ? AND ?", arrayOf(metric, from.toString(), to.toString()))
        val cv = ContentValues()
        for (r in rows) {
            cv.clear(); cv.put("metric", r.metric); cv.put("day", r.day.toString()); cv.put("value", r.value)
            cv.put("time", r.time); cv.putOrNull("source", r.source)
            insertWithOnConflict("imp_daily", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun importWorkouts(): List<CachedWorkout> = query("SELECT $W_COLS FROM imp_workout ORDER BY start", emptyArray(), ::workoutOf)

    /** (id, avg HR, zone minutes) of imported sessions. */
    fun updateImportWorkoutHr(updates: List<Triple<String, Long?, Int?>>) = tx {
        if (updates.isNotEmpty()) dirty.all()
        val cv = ContentValues()
        for ((id, avg, zm) in updates) {
            cv.clear(); cv.putOrNull("avg_hr", avg); cv.putOrNull("zone_min", zm)
            update("imp_workout", cv, "id = ?", arrayOf(id))
        }
    }

    /** "Remove import": deletes only the Apple Health import. */
    fun clearImport() = tx { invalidateImport(); dirty.all(); IMPORT_TABLES.forEach { delete(it, null, null) } }

    fun importMeta(key: String): String? = query("SELECT value FROM imp_meta WHERE key = ?", arrayOf(key)) { it.strOrNull(0) }.firstOrNull()

    /** metric → (days, oldest, newest) of the import. */
    fun importStats(): List<Triple<String, Int, Pair<LocalDate, LocalDate>>> =
        query("SELECT metric, COUNT(*), MIN(day), MAX(day) FROM imp_daily GROUP BY metric ORDER BY metric", emptyArray()) { c ->
            Triple(c.getString(0), c.getInt(1), LocalDate.parse(c.getString(2)) to LocalDate.parse(c.getString(3)))
        }

    fun importWorkoutCount(): Int = query("SELECT COUNT(*) FROM imp_workout", emptyArray()) { it.getInt(0) }.first()

    fun sizeBytes(context: Context): Long = context.getDatabasePath(NAME).length()

    companion object {
        private const val NAME = "cache.db"
        /** 2: workout.enriched (v2 also forces a clean re-read of all history on the first start after the data fix). */
        /** 3 (v4): daily.est, sleep_stage; calories to now, 5 HR zones → a full re-read of Health Connect. */
        /**
         * 4 (v4.1 data fix): hr_day.start_ms (+ imp_hr_day.start_ms, added in place), sleep_asleep, calorie split on the
         * main app's own total, hourly calories incl. resting burn → a full re-read of Health Connect (import kept).
         */
        /** 5 (v4.1 real-device fix): calorie days only where an app wrote calories; exact workout copies dropped. */
        /** 6 (v5): calories = Google Health's own records; no active / resting rows. */
        private const val VERSION = 6
        private const val W_COLS = "id, start, end_ms, type, title, pkg, kcal, avg_hr, zone_min, kcal_source, enriched"
        private val TABLES = listOf("daily", "hourly", "hr_day", "workout", "rec_index", "rec_count", "meta", "sleep_stage")
        /** Apple Health import: own tables, not in [TABLES], so neither a rebuild nor a schema upgrade drops them. */
        private val IMPORT_TABLES = listOf("imp_daily", "imp_hr_day", "imp_workout", "imp_meta", "imp_sleep_stage")
        private val IMPORT_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS imp_daily (metric TEXT NOT NULL, day TEXT NOT NULL, value REAL NOT NULL, time INTEGER NOT NULL, source TEXT, PRIMARY KEY (metric, day)) WITHOUT ROWID",
            "CREATE TABLE IF NOT EXISTS imp_hr_day (day TEXT PRIMARY KEY NOT NULL, source TEXT NOT NULL, bpm BLOB NOT NULL, start_ms INTEGER)",
            "CREATE TABLE IF NOT EXISTS imp_workout (id TEXT PRIMARY KEY NOT NULL, start INTEGER NOT NULL, end_ms INTEGER NOT NULL, type INTEGER NOT NULL, title TEXT, pkg TEXT NOT NULL, kcal REAL, avg_hr INTEGER, zone_min INTEGER, kcal_source TEXT, enriched INTEGER NOT NULL DEFAULT 1)",
            "CREATE INDEX IF NOT EXISTS imp_workout_start ON imp_workout (start)",
            "CREATE TABLE IF NOT EXISTS imp_meta (key TEXT PRIMARY KEY NOT NULL, value TEXT)",
            "CREATE TABLE IF NOT EXISTS imp_sleep_stage (day TEXT NOT NULL, start INTEGER NOT NULL, end_ms INTEGER NOT NULL, stage TEXT NOT NULL, source TEXT)",
            "CREATE INDEX IF NOT EXISTS imp_sleep_stage_day ON imp_sleep_stage (day)",
        )
        private val SCHEMA = listOf(
            "CREATE TABLE daily (metric TEXT NOT NULL, day TEXT NOT NULL, value REAL NOT NULL, time INTEGER NOT NULL, source TEXT, est INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (metric, day)) WITHOUT ROWID",
            "CREATE TABLE hourly (metric TEXT NOT NULL, hour INTEGER NOT NULL, value REAL NOT NULL, PRIMARY KEY (metric, hour)) WITHOUT ROWID",
            "CREATE TABLE hr_day (day TEXT PRIMARY KEY NOT NULL, source TEXT NOT NULL, bpm BLOB NOT NULL, start_ms INTEGER)",
            "CREATE TABLE workout (id TEXT PRIMARY KEY NOT NULL, start INTEGER NOT NULL, end_ms INTEGER NOT NULL, type INTEGER NOT NULL, title TEXT, pkg TEXT NOT NULL, kcal REAL, avg_hr INTEGER, zone_min INTEGER, kcal_source TEXT, enriched INTEGER NOT NULL DEFAULT 0)",
            "CREATE INDEX workout_start ON workout (start)",
            "CREATE TABLE rec_index (id TEXT PRIMARY KEY NOT NULL, type TEXT NOT NULL, day TEXT NOT NULL)",
            "CREATE INDEX rec_index_day ON rec_index (type, day)",
            "CREATE TABLE rec_count (type TEXT NOT NULL, pkg TEXT NOT NULL, day TEXT NOT NULL, n INTEGER NOT NULL, newest INTEGER NOT NULL, PRIMARY KEY (type, pkg, day)) WITHOUT ROWID",
            "CREATE TABLE meta (key TEXT PRIMARY KEY NOT NULL, value TEXT)",
            "CREATE TABLE sleep_stage (day TEXT NOT NULL, start INTEGER NOT NULL, end_ms INTEGER NOT NULL, stage TEXT NOT NULL, source TEXT)",
            "CREATE INDEX sleep_stage_day ON sleep_stage (day)",
        )

        /** Google Health API values (see [upsertGh]): kept through rebuilds and upgrades. api_est = Google's "estimated". */
        private val GH_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS gh_daily (metric TEXT NOT NULL, day TEXT NOT NULL, value REAL NOT NULL, time INTEGER NOT NULL, api_est INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (metric, day)) WITHOUT ROWID",
        )

        @Volatile private var instance: Cache? = null

        /** The open cache, if any (Sync's version bump reads its [dirty] days without needing a Context). */
        fun peek(): Cache? = instance

        fun get(context: Context): Cache =
            instance ?: synchronized(this) { instance ?: Cache(context.applicationContext).also { instance = it } }
    }
}
