package com.boudhealth.data

import android.content.Context
import java.time.LocalDate
import java.time.LocalDateTime

/*
 * V2 data API for the section screens, All metrics, Settings, tiles and widgets (IC2, IC10). Android facade over the
 * pure [V2Engine]: blocking functions take a [MetricEnv] and run on Dispatchers.IO ([load] wraps one IO hop).
 * One engine per (cache, goals, day): reads are shared between calls; bundles are memoised (LRU 32) per Sync.version.
 * A sync that changed only recent days keeps the engine and invalidates from the first changed day (V2_SYNC_PERF).
 */
object V2 {
    /** One IO hop with a [MetricEnv] (= V4.load). */
    suspend fun <T> load(context: Context, block: (MetricEnv) -> T): T = V4.load(context, block)

    private const val MEMO = 32
    private data class EngineKey(val cache: CacheReads, val goals: Goals, val version: Long, val day: LocalDate)
    @Volatile private var engineKey: EngineKey? = null
    @Volatile private var engineMemo: V2Engine? = null
    private val bundles = object : LinkedHashMap<String, SectionBundle>(MEMO, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SectionBundle>?) = size > MEMO
    }

    private fun version(): Long = runCatching { Sync.version.value }.getOrDefault(0L)

    /** The engine for [env] (shared while the data, goals and day are unchanged). */
    @Synchronized fun engine(env: MetricEnv): V2Engine {
        val k = EngineKey(env.cache, env.goals, version(), env.today)
        val old = engineKey
        val e = engineMemo
        if (k == old && e != null) return e
        // V2_SYNC_PERF: a sync bump with only recent days changed keeps the engine and re-reads just those days
        // (was: a new engine on every Sync.version, i.e. all history of every metric re-read after each app open)
        val from = if (e != null && old != null && old.copy(version = k.version) == k) Sync.changedFrom(old.version) else null
        if (e != null && from != null) {
            e.invalidateFrom(from)
            engineKey = k
            bundles.clear() // bundles are keyed by version anyway; the warm engine rebuilds them in a few ms
            return e
        }
        return V2Engine(env.cache, env.goals, env.zone, group = { ExerciseTypes.group(it) }).also {
            engineMemo = it
            engineKey = k
            bundles.clear()
        }
    }

    private fun now(env: MetricEnv): LocalDateTime = LocalDateTime.now(env.zone)

    /** First data day (All, swipe clamp). */
    fun dataStart(env: MetricEnv): LocalDate = engine(env).dataStart()

    fun value(env: MetricEnv, metricId: String, p: SectionPeriod): PeriodValue? = engine(env).value(metricId, p, env.today)
    fun series(env: MetricEnv, metricId: String, p: SectionPeriod, now: LocalDateTime = now(env)): PeriodSeries? = engine(env).series(metricId, p, now)
    fun trend(env: MetricEnv, metricId: String, p: SectionPeriod): PeriodTrend? = engine(env).trend(metricId, p, env.today)
    fun pace(env: MetricEnv, metricId: String, p: SectionPeriod, now: LocalDateTime = now(env)): PeriodPace? = engine(env).pace(metricId, p, now)
    /** Card data of one metric (detail page uses the same function as the card, §2.2 rule). */
    fun card(env: MetricEnv, metricId: String, p: SectionPeriod, now: LocalDateTime = now(env)): CardData = engine(env).card(metricId, p, now)
    /** Rings for a period; [SectionPeriod.Day] today = Today tile = widget rings. */
    fun rings(env: MetricEnv, p: SectionPeriod): RingSet = engine(env).rings(p, env.today)
    fun workouts(env: MetricEnv, p: SectionPeriod): PeriodWorkouts = engine(env).periodWorkouts(p, env.today)
    fun zones(env: MetricEnv, p: SectionPeriod): PeriodZones = engine(env).zones(p, env.today)
    fun sleep(env: MetricEnv, p: SectionPeriod): List<SleepNight> = engine(env).sleep(p, env.today)
    /** (current, best) workout streak as of [day]. */
    fun streakAsOf(env: MetricEnv, day: LocalDate): Pair<Int, Int> = engine(env).streakAsOf(day)
    fun bodyTargets(env: MetricEnv, p: SectionPeriod): List<TargetRing> = engine(env).bodyTargets(p, env.today)
    fun insights(env: MetricEnv, section: SectionId, scope: SectionScope, now: LocalDateTime = now(env)): List<Insight> =
        bundle(env, section, scope, MetricSections.of(section), now).insights

    /** ONE call per section screen load; memoised by (Sync.version, section, scope, cards). */
    fun bundle(env: MetricEnv, section: SectionId, scope: SectionScope, cardMetricIds: List<String>, now: LocalDateTime = now(env)): SectionBundle {
        val e = engine(env)
        val v = version()
        val key = "$section|$scope|$cardMetricIds|${now.toLocalDate()}|${now.hour}|$v"
        synchronized(this) { bundles[key] }?.let { return it }
        val b = e.bundle(section, scope, cardMetricIds, now, v)
        // M2: a sync may have replaced the engine while this bundle was built: don't memoise pre-sync data
        synchronized(this) { if (engineMemo === e && version() == v) bundles[key] = b }
        return b
    }

    /** Prefetch the previous period (call after a load, in the background) so a swipe back is instant. */
    fun prefetchPrevious(env: MetricEnv, section: SectionId, scope: SectionScope, cardMetricIds: List<String>, now: LocalDateTime = now(env)) {
        val prev = scope.shift(-1, dataStart(env), env.today)
        if (prev != scope) runCatching { bundle(env, section, prev, cardMetricIds, now) }
    }

    /** All metrics list. */
    fun metricIndex(env: MetricEnv): List<MetricIndexRow> = engine(env).metricIndex(env.today)

    /** D17 "Suggested for you": dropped in R3 (it was a median), always null. */
    fun suggestedHeartPointsTarget(env: MetricEnv): Int? = engine(env).suggestedHeartPointsTarget(env.today)

    /** D9 start value of a body target (stored, else backfill rule). */
    fun bodyTargetStart(env: MetricEnv, metricId: String): Double? = engine(env).bodyTargetStart(metricId, env.today)

    /**
     * D9 (V2-050): Goals with a new body target and its start value (the latest reading, else null = backfill rule).
     * T7: `store.updateGoals { V2.withBodyTarget(it, "weight", 78.0, V2.latestValue(env, "weight"), today) }`.
     */
    fun withBodyTarget(g: Goals, metricId: String, target: Double?, latest: Double?, today: LocalDate): Goals = g.withBodyTarget(metricId, target, latest, today)

    /** Latest plausible value of a V2 metric (null = none in 3 years). */
    fun latestValue(env: MetricEnv, metricId: String): Double? = engine(env).daily(metricId, env.today.minusYears(3), env.today).maxByOrNull { it.key }?.value

    /**
     * "Load hours" (V2-046) for a 1D card whose series gap is [SeriesGap.HOURLY_NOT_CACHED]: live hourly steps or
     * calories of [day] from Health Connect (foreground). null for metrics without a live hourly source.
     */
    suspend fun loadHours(context: Context, metricId: String, day: LocalDate): PeriodSeries? {
        val key = when (metricId) { "steps" -> M.STEPS; "calories" -> M.KCAL; else -> return null }
        val pts = History(context).hoursOf(key, day).associate { it.at to it.v }
        val p = SectionPeriod.Day(day.toEpochDay())
        val zone = java.time.ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val out = ArrayList<SeriesBucket>()
        var t = day.atStartOfDay(zone)
        while (t.isBefore(day.plusDays(1).atStartOfDay(zone))) {
            val at = t.toLocalDateTime()
            out += SeriesBucket(at, at.plusHours(1), pts[at], future = at.isAfter(now))
            t = t.plusHours(1)
        }
        return PeriodSeries(metricId, p, Grain.HOUR, out, null, if (pts.isEmpty()) SeriesGap.HOURLY_NOT_CACHED else SeriesGap.NONE)
    }

    // ------------------------------------------------------------------ restore round (V2_REMOVALS_AUDIT §4.1; V2Restore.kt)

    /** H3: last [weeks] (13 or 52) weeks of heart points vs the weekly target, oldest first, this week last. */
    fun weeklyHeartPoints(env: MetricEnv, weeks: Int): List<WeekPoints> = engine(env).weeklyHeartPoints(weeks, env.today)
    /** D2 / H4 / K1: per-day list (newest first) for steps, exercise_min, zone_min, calories; [to] default today. */
    fun dayList(env: MetricEnv, metricId: String, days: Int = 30, to: LocalDate = env.today): List<DayRow> = engine(env).dayList(metricId, to, days)
    /** D1: Total · Best day · Peak hour (1D) · change vs the previous day (1D) · Goal. */
    fun stats(env: MetricEnv, metricId: String, p: SectionPeriod, now: LocalDateTime = now(env)): PeriodStats = engine(env).stats(metricId, p, now)
    /** H1: zone bpm limits + max / resting HR used on [day]. */
    fun zoneLimits(env: MetricEnv, day: LocalDate = env.today): ZoneLimits = engine(env).zoneLimits(day)
    /** H2 / W2: sessions with zone split, points and average HR. */
    fun sessionDetails(env: MetricEnv, p: SectionPeriod): List<SessionDetail> = engine(env).sessionDetails(p, env.today)
    /** A2: goal met on n of m days per goal / all three / per month. */
    fun goalDays(env: MetricEnv, p: SectionPeriod): GoalDaysSummary = engine(env).goalDays(p, env.today)
    /** W1: workout minutes by type (total and per bucket). */
    fun minutesByType(env: MetricEnv, p: SectionPeriod): MinutesByType = engine(env).minutesByType(p, env.today)
    /** 1.4: latest reading vs the previous one ("lean_mass", "hrv", …). */
    fun latestChange(env: MetricEnv, metricId: String, asOf: LocalDate = env.today): LatestChange? = engine(env).latestChange(metricId, asOf)
    /** 1.4 Weekly goals tile: pace per goal this week. */
    fun weeklyGoals(env: MetricEnv, now: LocalDateTime = now(env)): List<WeeklyGoalPace> = engine(env).weeklyGoals(now)

    fun clearMemo() = synchronized(this) { bundles.clear(); engineKey = null; engineMemo = null }
}
