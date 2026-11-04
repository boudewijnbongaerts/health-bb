package com.boudhealth.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/*
 * R3 data facade (IMPL_PLAN §2.6), same pattern as [V2]: blocking, call it inside the section's single
 * `SectionSpec.loadExtras` IO hop. The UI tokens object of the same name lives in com.boudhealth.ui.section; import
 * one of them with an alias when a file needs both (e.g. `import com.boudhealth.data.R3 as R3Data`).
 */
object R3 {
    private fun e(env: MetricEnv): V2Engine = V2.engine(env)
    fun now(env: MetricEnv): LocalDateTime = LocalDateTime.now(env.zone)

    /** Day rings vs the DAILY goals (same as V2 DAY rings); absent = no data (before the first data day / after today). */
    fun dayRings(env: MetricEnv, from: LocalDate, to: LocalDate): Map<LocalDate, FloatArray> = e(env).dayRings(from, to, env.today)

    /** Month rings of [year] vs month-scaled goals; future months absent. */
    fun monthRings(env: MetricEnv, year: Int): Map<YearMonth, FloatArray> = e(env).monthRings(year, env.today)

    /** One ring per year; the first year prorated from the first data day. */
    fun yearRings(env: MetricEnv): List<YearRingCell> = e(env).yearRings(env.today)

    /** (workout hours, steps, heart points) = sum of [yearRings]. */
    fun lifetime(env: MetricEnv): Triple<Double, Double, Double> = e(env).lifetime(env.today)

    /** FULL previous period aligned by index to the current buckets (1D yesterday's hours … 1Y previous months); All: null. */
    fun ghost(env: MetricEnv, metricId: String, p: SectionPeriod, now: LocalDateTime = now(env)): Ghost? = e(env).ghost(metricId, p, now)

    /** Body line ghost: previous period per day, aligned by day index; Day / All: null. */
    fun prevSeries(env: MetricEnv, metricId: String, p: SectionPeriod): List<Double?>? = e(env).prevSeries(metricId, p, env.today)

    /** Total vs the previous comparable window (1D ABSOLUTE, else PERCENT; until hh:mm while running). */
    fun totalChange(env: MetricEnv, metricId: String, p: SectionPeriod, now: LocalDateTime = now(env)): TotalChange? = e(env).totalChange(metricId, p, now)

    /** The Trends header window (null for All); wholeDays when the cut day's hourly data is missing. */
    fun window(env: MetricEnv, p: SectionPeriod, now: LocalDateTime = now(env)): CompareWindow? = e(env).window(p, now)

    /** First data day..today per-day values (plausible only), memoised by the engine per sync version. */
    fun history(env: MetricEnv, metricId: String): Map<LocalDate, Double> = e(env).history(metricId, env.today)

    /** The "/ week" column cells for these Mondays (future weeks left out). */
    fun weekColumn(env: MetricEnv, mondays: List<LocalDate>, now: LocalDateTime = now(env)): List<WeekColumnCell> = e(env).weekColumn(mondays, now)

    /** Hourly buckets of one day (Peak hour). */
    fun hours(env: MetricEnv, metricId: String, day: LocalDate, now: LocalDateTime = now(env)): List<SeriesBucket> = e(env).hours(metricId, day, now)

    /** The engine itself, for the pure helpers (R3Math) and V2Engine extensions inside loadExtras. */
    fun engine(env: MetricEnv): V2Engine = e(env)
}
