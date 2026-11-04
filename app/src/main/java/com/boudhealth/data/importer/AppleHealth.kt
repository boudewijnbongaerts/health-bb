package com.boudhealth.data.importer

import com.boudhealth.data.DailyRow
import com.boudhealth.data.HrDay
import com.boudhealth.data.M
import com.boudhealth.data.RawReading
import com.boudhealth.data.SleepStage
import com.boudhealth.data.SyncLogic
import com.boudhealth.data.WorkoutSpan
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * Apple Health export.xml importer: PURE logic (no Android), unit-tested on the JVM with a synthetic export.
 *
 *   XmlSource (Android XmlPullParser / JVM StAX)  →  AppleParser (Record / Workout elements, streaming)
 *     →  AppleAggregator (units, plausibility, source choice, per-hour / per-day / per-minute aggregation)
 *     →  ImportResult (daily rows, per-minute heart-rate days, workouts, waist entries, per-type stats)
 *
 * Nothing holds the file: the parser sees one element at a time; the aggregator keeps per-hour / per-day sums.
 */

/** Source label stored with every imported row ("Apple Health (import)" in the UI). */
const val IMPORT_SOURCE = "com.apple.health.import"
/** Imported heart-rate day / workout whose minutes partly come from Apple interval records ("approximate (interval HR)"). */
const val IMPORT_SOURCE_APPROX = "com.apple.health.import.intervalhr"

/** Minimal pull interface: start / end tags only, attributes of the current start tag. */
interface XmlSource {
    /** Advances to the next start or end tag: [START], [END] or [EOF]. */
    fun next(): Int
    /** Name of the current tag. */
    val name: String
    /** Attribute of the current start tag, or null. */
    fun attr(name: String): String?

    companion object {
        const val EOF = 0
        const val START = 1
        const val END = 2
    }
}

/** One `<Record>`: everything as in the file, dates parsed with their own offset. */
data class AppleRecord(
    val type: String,
    val source: String,
    val device: String?,
    val unit: String?,
    val value: String?,
    val start: OffsetDateTime,
    val end: OffsetDateTime,
)

/** One `<Workout>`: totals from the old attributes (≤ iOS 15) or from `<WorkoutStatistics>` children (iOS 16+). */
data class AppleWorkout(
    val activityType: String,
    val source: String,
    val device: String?,
    val start: OffsetDateTime,
    val end: OffsetDateTime,
    val energyKcal: Double?,
    val distanceM: Double?,
)

/**
 * Removes the `<!DOCTYPE HealthData [ … ]>` block (≈ 8 KB of ELEMENT / ATTLIST declarations in a real iOS 26 export)
 * before the XML parser sees it, so no parser ever processes (or chokes on) Apple's internal DTD. Only the first
 * [maxProlog] bytes are buffered; the rest is streamed untouched.
 */
object Doctype {
    fun strip(input: java.io.InputStream, maxProlog: Int = 1 shl 20): java.io.InputStream {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var root = -1
        while (buf.size() < maxProlog) {
            val n = input.read(chunk)
            if (n < 0) break
            buf.write(chunk, 0, n)
            root = buf.toString(Charsets.ISO_8859_1.name()).indexOf("<HealthData")
            if (root >= 0) break
        }
        val bytes = buf.toByteArray()
        val text = String(bytes, Charsets.ISO_8859_1) // 1 byte = 1 char: indexes are byte offsets
        val start = text.indexOf("<!DOCTYPE")
        val end = if (start >= 0) text.indexOf("]>", start).let { if (it >= 0) it + 2 else text.indexOf('>', start) + 1 } else -1
        val head = if (start >= 0 && end > start && (root < 0 || end <= root)) bytes.copyOfRange(0, start) + bytes.copyOfRange(end, bytes.size) else bytes
        return java.io.SequenceInputStream(java.io.ByteArrayInputStream(head), input)
    }
}

object AppleDates {
    private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xx")

    /** "2024-01-02 07:12:33 +0100" → OffsetDateTime (the offset where it was recorded), null if unreadable. */
    fun parse(s: String?): OffsetDateTime? = if (s == null) null else try {
        OffsetDateTime.parse(s.trim(), FMT)
    } catch (e: Exception) {
        null
    }
}

/** Unit conversion to the cache's units. Returns null for an unknown unit (counted as skipped). */
object AppleUnits {
    fun kg(v: Double, unit: String?): Double? = when (unit?.lowercase()) {
        "kg" -> v
        "lb" -> v * 0.45359237
        "g" -> v / 1000.0
        "st" -> v * 6.35029318
        "oz" -> v * 0.028349523125
        else -> null
    }

    fun meters(v: Double, unit: String?): Double? = when (unit?.lowercase()) {
        "m" -> v
        "cm" -> v / 100.0
        "mm" -> v / 1000.0
        "km" -> v * 1000.0
        "in" -> v * 0.0254
        "ft" -> v * 0.3048
        "yd" -> v * 0.9144
        "mi" -> v * 1609.344
        else -> null
    }

    fun kcal(v: Double, unit: String?): Double? = when (unit) {
        "kcal", "Cal", "Kcal" -> v
        "kJ", "kj" -> v / 4.184
        "cal" -> v / 1000.0
        "J" -> v / 4184.0
        else -> null
    }

    /** Apple writes percentages as fractions with unit "%" (0.183 = 18.3 %); a value > 1 is taken as already in %. */
    fun percent(v: Double, unit: String?): Double? = if (unit != null && unit != "%") null else if (v <= 1.0) v * 100.0 else v

    fun minutes(v: Double, unit: String?): Double? = when (unit) {
        "min", null -> v
        "s" -> v / 60.0
        "hr", "h" -> v * 60.0
        else -> null
    }
}

/** Where a sample came from, for choosing one source (Apple's raw export contains every source). */
enum class SourceClass { WATCH, PHONE, OTHER }

object AppleSources {
    /** Apple Watch / iPhone from the device string ("…name:Apple Watch, … model:Watch…") or the source name. */
    /**
     * By the SOURCE (the app / device that wrote the record), never by the device string: in real exports third-party
     * apps write with device "Apple Watch" (Komoot, WorkOutDoors, Technogym) or even "Apple Watch via Apple Health"
     * (Withings re-writing the Watch's own energy), and classifying those as the Watch would add them to the Watch's
     * numbers (double counting). Apple's own sources are named after the device ("Alex’s Apple Watch", "iPhone alex";
     * names can contain a no-break space).
     */
    @Suppress("UNUSED_PARAMETER")
    fun classify(source: String, device: String?): SourceClass {
        val s = source.replace('\u00a0', ' ')
        return when {
            " via " in s -> SourceClass.OTHER
            "Watch" in s -> SourceClass.WATCH
            "iPhone" in s -> SourceClass.PHONE
            else -> SourceClass.OTHER
        }
    }

    /** Stable key per source: "watch", "phone", or "other:<source name>" (each third-party app separately). */
    fun key(source: String, device: String?): String = when (classify(source, device)) {
        SourceClass.WATCH -> "watch"
        SourceClass.PHONE -> "phone"
        SourceClass.OTHER -> "other:$source"
    }

    fun isWithings(key: String) = key.startsWith("other:") && (key.contains("Withings", true) || key.contains("Health Mate", true))

    /** Steps, distance, energy, vitals, heart rate, sleep: the Watch (worn), then the iPhone, then other apps. */
    fun wearableRank(key: String): Int = when {
        key == "watch" -> 0
        key == "phone" -> 1
        isWithings(key) -> 2
        else -> 3
    }

    /** Weight, body fat, lean mass, height, waist: Withings (the scale) first, then everything else (consistent with Sources). */
    fun bodyRank(key: String): Int = when {
        isWithings(key) -> 0
        key == "phone" -> 1 // typed into the Health app
        key == "watch" -> 2
        else -> 3
    }
}

/** Per Apple type: imported / skipped counts and the date range seen. */
data class TypeStat(var read: Int = 0, var used: Int = 0, val skipped: MutableMap<String, Int> = HashMap(), var oldest: LocalDate? = null, var newest: LocalDate? = null) {
    fun seen(d: LocalDate) {
        if (oldest == null || d.isBefore(oldest)) oldest = d
        if (newest == null || d.isAfter(newest)) newest = d
    }
    fun skip(reason: String) { skipped[reason] = (skipped[reason] ?: 0) + 1 }
}

data class ImpWorkout(
    val id: String,
    val start: Long,
    val end: Long,
    /** Apple activity type, e.g. "HKWorkoutActivityTypeRunning" (mapped to a Health Connect type on Android). */
    val appleType: String,
    val kcal: Double?,
    val distanceM: Double?,
    val source: String,
    /** Its heart rate partly comes from Apple interval records: "approximate (interval HR)". */
    val approxHr: Boolean = false,
)

data class WaistEntry(val time: Long, val cm: Double)

data class ImportResult(
    val rows: List<DailyRow>,
    val hrDays: List<HrDay>,
    val workouts: List<ImpWorkout>,
    val waist: List<WaistEntry>,
    val stats: Map<String, TypeStat>,
    val elements: Long,
    /** Stage timeline of each imported main sleep. */
    val sleepStages: List<SleepStage> = emptyList(),
) {
    val oldest: LocalDate? get() = rows.minOfOrNull { it.day } ?: hrDays.minOfOrNull { it.day }
    val newest: LocalDate? get() = rows.maxOfOrNull { it.day } ?: hrDays.maxOfOrNull { it.day }
}

/** Streams `<Record>` and `<Workout>` elements out of export.xml. */
class AppleParser(
    private val src: XmlSource,
    private val onRecord: (AppleRecord) -> Unit,
    private val onWorkout: (AppleWorkout) -> Unit,
    private val onBadDate: (String) -> Unit = {},
    /** Checked every 1,000 elements; true = stop (cancel). */
    private val cancelled: () -> Boolean = { false },
) {
    var elements = 0L
        private set

    /** Returns false when cancelled. */
    fun run(): Boolean {
        while (true) {
            val ev = src.next()
            if (ev == XmlSource.EOF) return true
            if (ev != XmlSource.START) continue
            elements++
            if (elements % 1000 == 0L && cancelled()) return false
            when (src.name) {
                "Record" -> record()
                "Workout" -> workout()
            }
        }
    }

    private fun record() {
        val type = src.attr("type") ?: return
        val start = AppleDates.parse(src.attr("startDate"))
        val end = AppleDates.parse(src.attr("endDate")) ?: start
        if (start == null || end == null) { onBadDate(type); return }
        onRecord(AppleRecord(type, src.attr("sourceName") ?: "?", src.attr("device"), src.attr("unit"), src.attr("value"), start, end))
    }

    private fun workout() {
        val type = src.attr("workoutActivityType") ?: "HKWorkoutActivityTypeOther"
        val source = src.attr("sourceName") ?: "?"
        val device = src.attr("device")
        val start = AppleDates.parse(src.attr("startDate"))
        val end = AppleDates.parse(src.attr("endDate"))
        // old format: totals as attributes
        var kcal = src.attr("totalEnergyBurned")?.toDoubleOrNull()?.let { AppleUnits.kcal(it, src.attr("totalEnergyBurnedUnit")) }
        var dist = src.attr("totalDistance")?.toDoubleOrNull()?.let { AppleUnits.meters(it, src.attr("totalDistanceUnit")) }
        // new format: <WorkoutStatistics type=… sum=… unit=…/> children (and nested WorkoutActivity, events, routes: skipped)
        // statistics directly under <Workout> are the workout's totals; those inside <WorkoutActivity> (multi-activity
        // workouts) are per activity and only used when there are no direct ones (never both: no double counting)
        val kc = DoubleArray(2); val ds = DoubleArray(2); val hasK = BooleanArray(2); val hasD = BooleanArray(2)
        var depth = 1
        while (depth > 0) {
            val ev = src.next()
            if (ev == XmlSource.EOF) break
            if (ev == XmlSource.END) { depth--; continue }
            val level = if (depth == 1) 0 else 1
            depth++
            elements++
            if (src.name == "WorkoutStatistics") {
                val sum = src.attr("sum")?.toDoubleOrNull() ?: continue
                val unit = src.attr("unit")
                when (src.attr("type")) {
                    "HKQuantityTypeIdentifierActiveEnergyBurned" -> AppleUnits.kcal(sum, unit)?.let { kc[level] += it; hasK[level] = true }
                    "HKQuantityTypeIdentifierDistanceWalkingRunning", "HKQuantityTypeIdentifierDistanceCycling",
                    "HKQuantityTypeIdentifierDistanceSwimming", "HKQuantityTypeIdentifierDistanceDownhillSnowSports",
                    "HKQuantityTypeIdentifierDistanceWheelchair" -> AppleUnits.meters(sum, unit)?.let { ds[level] += it; hasD[level] = true }
                }
            }
        }
        if (start == null || end == null) { onBadDate("Workout"); return }
        if (kcal == null || kcal <= 0.0) kcal = if (hasK[0]) kc[0] else if (hasK[1]) kc[1] else null
        if (dist == null || dist <= 0.0) dist = if (hasD[0]) ds[0] else if (hasD[1]) ds[1] else null
        onWorkout(AppleWorkout(type, source, device, start, end, kcal, dist))
    }
}

/**
 * Turns records into cache rows. Decisions (see DATA_FIX.md "Apple Health import"):
 *  - Steps / distance / active + basal energy / flights: per HOUR, the best source present in that hour
 *    (Watch > iPhone > other apps) — close to how the Health app itself de-duplicates overlapping iPhone + Watch
 *    samples, without losing the hours the Watch wasn't worn. Records spanning hours are split proportionally.
 *  - Body (weight, fat, lean, height, waist): one source per day, Withings first, last reading of the day.
 *  - Vitals (resting HR, HRV-SDNN, VO2 max, SpO2, breathing): one source per day, Watch first, average of the day.
 *  - Heart rate: per-minute averages per day from one source (Watch first): same storage as the Health Connect path.
 *  - Sleep: per source, segments are joined into nights (gaps < 2 h); the night belongs to the wake-up day; one source
 *    per night (Watch first); main sleep = most time asleep.
 *  - Days use the record's own offset ("+0100"); heart rate is keyed by the phone's zone like the HC path.
 */
class AppleAggregator(
    private val phoneZone: ZoneId = ZoneId.systemDefault(),
    /**
     * Longest interval heart-rate record whose value is spread over its minutes (see [result]); spreading only ever
     * happens INSIDE a workout. Owner decision (v4.1): every length is used (no limit). A long interval averages
     * warm-up and main effort into one value, so those minutes are approximate (labelled "approximate (interval HR)"),
     * but intensity tracks Apple's exercise minutes (real export: median ratio 0.59, r 0.76) instead of losing most
     * older workout heart rate (≤ 5 min: 0.13, r 0.23). 0 = off.
     */
    val maxIntervalMin: Int = Int.MAX_VALUE,
) {

    val stats = HashMap<String, TypeStat>()
    private fun stat(t: String) = stats.getOrPut(t) { TypeStat() }

    // cumulative: metric → (day, utc hour) → source key → sum
    private class Hour(val day: LocalDate) { val bySource = HashMap<String, Double>() }
    private val cumulative = HashMap<String, HashMap<Long, Hour>>()

    // discrete: metric → readings
    private val discrete = HashMap<String, ArrayList<RawReading>>()
    private val waist = ArrayList<RawReading>()

    // heart rate: (day, source key) → per-minute sums
    private class HrAcc { val sum = IntArray(HrDay.MINUTES); val cnt = ShortArray(HrDay.MINUTES); var samples = 0; var newest = 0L }
    private val hr = HashMap<LocalDate, HashMap<String, HrAcc>>()
    private class HrInterval(val key: String, val start: Long, val end: Long, val bpm: Int)
    private val hrIntervals = ArrayList<HrInterval>()
    /** Minutes filled from interval heart-rate records (Apple's older workout series). */
    var hrIntervalMinutes = 0
        private set
    /** Interval records longer than [maxIntervalMin] (not spread) and records outside any workout (not spread). */
    var hrIntervalsLong = 0
        private set
    var hrIntervalsOutside = 0
        private set
    /** Days whose heart rate uses interval-record minutes: labelled "approximate (interval HR)". */
    private val approxDays = HashSet<LocalDate>()
    /** Spans (ms) of the interval records actually spread: a kept workout overlapping one is "approximate". */
    private val approxSpans = ArrayList<LongArray>()

    // sleep segments per source
    private class Seg(val start: Long, val end: Long, val kind: String, val wakeOffset: java.time.ZoneOffset)
    private val sleep = HashMap<String, ArrayList<Seg>>()

    private val workouts = ArrayList<ImpWorkout>()

    private val CUMULATIVE = mapOf(
        "HKQuantityTypeIdentifierStepCount" to M.STEPS,
        "HKQuantityTypeIdentifierActiveEnergyBurned" to M.ACTIVE_KCAL,
        "HKQuantityTypeIdentifierBasalEnergyBurned" to BASAL,
        "HKQuantityTypeIdentifierDistanceWalkingRunning" to M.DISTANCE_M,
        "HKQuantityTypeIdentifierFlightsClimbed" to M.FLIGHTS,
    )

    fun onBadDate(type: String) { stat(type).read++; stat(type).skip("bad date") }

    /** Records of types we don't use (Apple has 100+), counted only. */
    var ignored = 0L
        private set

    fun add(r: AppleRecord) {
        if (r.type !in HANDLED && r.type !in CUMULATIVE) { ignored++; return }
        val st = stat(r.type)
        st.read++
        val key = AppleSources.key(r.source, r.device)
        val day = r.start.toLocalDate()
        if (r.type == "HKCategoryTypeIdentifierSleepAnalysis") {
            val v = r.value ?: return st.skip("no value")
            if (!r.end.isAfter(r.start)) return st.skip("empty interval")
            sleep.getOrPut(key) { ArrayList() } += Seg(r.start.toInstant().toEpochMilli(), r.end.toInstant().toEpochMilli(), v.removePrefix("HKCategoryValueSleepAnalysis"), r.end.offset)
            st.used++; st.seen(r.end.toLocalDate())
            return
        }
        val raw = r.value?.toDoubleOrNull() ?: return st.skip("not a number")
        if (raw.isNaN() || raw.isInfinite()) return st.skip("not a number")
        val t = r.start.toInstant().toEpochMilli()
        fun reading(metric: String, v: Double?, range: ClosedFloatingPointRange<Double>, list: ArrayList<RawReading> = discrete.getOrPut(metric) { ArrayList() }) {
            if (v == null) return st.skip("unit ${r.unit}")
            if (v !in range) return st.skip("implausible")
            list += RawReading(day, key, t, v)
            st.used++; st.seen(day)
        }
        when (r.type) {
            in CUMULATIVE -> {
                val metric = CUMULATIVE.getValue(r.type)
                val v = when (metric) {
                    M.STEPS, M.FLIGHTS -> raw
                    M.DISTANCE_M -> AppleUnits.meters(raw, r.unit)
                    else -> AppleUnits.kcal(raw, r.unit)
                } ?: return st.skip("unit ${r.unit}")
                val max = when (metric) { M.STEPS -> 100_000.0; M.FLIGHTS -> 1_000.0; M.DISTANCE_M -> 200_000.0; else -> 20_000.0 }
                if (v < 0 || v > max) return st.skip("implausible")
                addCumulative(metric, key, r.start, r.end, v)
                st.used++; st.seen(day)
            }
            "HKQuantityTypeIdentifierBodyMass" -> reading(M.WEIGHT, AppleUnits.kg(raw, r.unit), 25.0..350.0)
            "HKQuantityTypeIdentifierBodyFatPercentage" -> reading(M.FAT_PCT, AppleUnits.percent(raw, r.unit), 2.0..75.0)
            "HKQuantityTypeIdentifierLeanBodyMass" -> reading(M.LEAN_KG, AppleUnits.kg(raw, r.unit), 15.0..250.0)
            "HKQuantityTypeIdentifierHeight" -> reading(M.HEIGHT, AppleUnits.meters(raw, r.unit), 0.5..2.6)
            "HKQuantityTypeIdentifierWaistCircumference" -> reading(M.WAIST, AppleUnits.meters(raw, r.unit)?.let { it * 100.0 }, 40.0..200.0, waist)
            "HKQuantityTypeIdentifierRestingHeartRate" -> reading(M.RHR, raw, 25.0..140.0)
            "HKQuantityTypeIdentifierHeartRateVariabilitySDNN" -> reading(M.HRV_SDNN, raw, 1.0..500.0)
            "HKQuantityTypeIdentifierVO2Max" -> reading(M.VO2, raw, 10.0..95.0)
            "HKQuantityTypeIdentifierOxygenSaturation" -> reading(M.SPO2, AppleUnits.percent(raw, r.unit), 70.0..100.0)
            "HKQuantityTypeIdentifierRespiratoryRate" -> reading(M.RESP, raw, 4.0..40.0)
            "HKQuantityTypeIdentifierHeartRate" -> {
                val bpm = Math.round(raw)
                if (!SyncLogic.plausibleBpm(bpm)) return st.skip("implausible")
                val endMs = r.end.toInstant().toEpochMilli()
                if (endMs - t >= 60_000L) {
                    // an INTERVAL record (Apple exports older workout heart-rate series as one record per interval whose
                    // value is the series average): its value is the delivered reading for every minute it covers;
                    // applied after all point samples, only to minutes that have no sample of their own
                    if (endMs - t <= maxIntervalMin * 60_000L) hrIntervals += HrInterval(key, t, endMs, bpm.toInt()) else hrIntervalsLong++
                    st.used++; st.seen(r.start.toLocalDate())
                    return
                }
                val i = Instant.ofEpochMilli(t)
                val d = i.atZone(phoneZone).toLocalDate()
                val dayStart = d.atStartOfDay(phoneZone).toInstant().toEpochMilli()
                val a = hr.getOrPut(d) { HashMap() }.getOrPut(key) { HrAcc() }
                val m = SyncLogic.minuteSlot(dayStart, t, HrDay.MINUTES)
                a.sum[m] += bpm.toInt(); a.cnt[m] = (a.cnt[m] + 1).toShort(); a.samples++
                if (t > a.newest) a.newest = t
                st.used++; st.seen(d)
            }
        }
    }

    fun add(w: AppleWorkout) {
        val st = stat("Workout")
        st.read++
        val s = w.start.toInstant().toEpochMilli()
        val e = w.end.toInstant().toEpochMilli()
        if (e <= s || e - s > 24L * 3_600_000L) return st.skip("bad duration")
        val kcal = w.energyKcal?.takeIf { it in 0.0..20_000.0 }
        workouts += ImpWorkout("apple:$s:$e:${w.activityType}:${w.source.hashCode()}:${workouts.size}", s, e, w.activityType, kcal, w.distanceM?.takeIf { it in 0.0..1_000_000.0 }, AppleSources.key(w.source, w.device))
        st.used++; st.seen(w.start.toLocalDate())
    }

    /** Splits [start, end) over UTC hours proportionally; each piece belongs to the local day (record offset) of its start. */
    private fun addCumulative(metric: String, key: String, start: OffsetDateTime, end: OffsetDateTime, v: Double) {
        val map = cumulative.getOrPut(metric) { HashMap() }
        val s = start.toInstant().toEpochMilli()
        val e = maxOf(end.toInstant().toEpochMilli(), s)
        val off = start.offset
        if (e == s) {
            val h = s / 3_600_000L
            map.getOrPut(h) { Hour(Instant.ofEpochMilli(s).atOffset(off).toLocalDate()) }.bySource.merge(key, v, Double::plus)
            return
        }
        var a = s
        while (a < e) {
            val h = a / 3_600_000L
            val b = minOf(e, (h + 1) * 3_600_000L)
            val part = v * (b - a).toDouble() / (e - s).toDouble()
            map.getOrPut(h) { Hour(Instant.ofEpochMilli(a).atOffset(off).toLocalDate()) }.bySource.merge(key, part, Double::plus)
            a = b
        }
    }

    fun result(elements: Long): ImportResult {
        val rows = ArrayList<DailyRow>()
        // cumulative: per hour the best source present, summed per day. On a day where Apple's own devices (Watch /
        // iPhone) have data for the metric, third-party apps are ignored: in the real export Withings and Yazio
        // re-write the Watch's energy in their own time buckets (976 hours with only their copies) — counting those
        // hours would add the same calories twice. Third-party values count only on days without any Apple data.
        val perDay = HashMap<String, HashMap<LocalDate, Double>>()
        for ((metric, hours) in cumulative) {
            val days = perDay.getOrPut(metric) { HashMap() }
            val appleDays = HashSet<LocalDate>()
            for ((_, hour) in hours) if (hour.bySource.keys.any { it == "watch" || it == "phone" }) appleDays += hour.day
            for ((_, hour) in hours) {
                val keys = if (hour.day in appleDays) hour.bySource.keys.filter { it == "watch" || it == "phone" } else hour.bySource.keys.toList()
                if (keys.isEmpty()) { thirdPartyHoursDropped++; continue }
                val best = keys.minWith(compareBy<String> { AppleSources.wearableRank(it) }.thenByDescending { hour.bySource.getValue(it) }.thenBy { it })
                if (hour.bySource.size > 1) multiSourceHours++
                days.merge(hour.day, hour.bySource.getValue(best), Double::plus)
            }
        }
        fun dayEnd(d: LocalDate) = d.plusDays(1).atStartOfDay(phoneZone).toInstant().toEpochMilli() - 1
        for (metric in listOf(M.STEPS, M.ACTIVE_KCAL, M.DISTANCE_M, M.FLIGHTS)) {
            perDay[metric]?.forEach { (d, v) -> rows += DailyRow(metric, d, if (metric == M.STEPS || metric == M.FLIGHTS) Math.round(v).toDouble() else v, dayEnd(d), IMPORT_SOURCE) }
        }
        // total calories = active + basal, only on days that have basal (else it would look like a very low total)
        val basal = perDay[BASAL] ?: emptyMap()
        val active = perDay[M.ACTIVE_KCAL] ?: emptyMap()
        for ((d, b) in basal) {
            rows += DailyRow(M.KCAL, d, b + (active[d] ?: 0.0), dayEnd(d), IMPORT_SOURCE)
            // resting = Apple's basal energy as delivered (P-KCAL: same app as the total and active; past days only)
            rows += DailyRow(M.REST_KCAL, d, b, dayEnd(d), IMPORT_SOURCE)
        }

        // discrete: one source per day; for body metrics, copies of the scale's readings by other apps are dropped first
        for ((metric, list) in discrete) {
            val body = metric in setOf(M.WEIGHT, M.FAT_PCT, M.LEAN_KG, M.HEIGHT)
            val rank: (String) -> Int = if (body) AppleSources::bodyRank else AppleSources::wearableRank
            val clean = if (body) dropCopies(metric, list) else list
            SyncLogic.pickPerDay(clean, rank, lastOfDay = body).forEach { rows += DailyRow(metric, it.day, it.value, it.time, IMPORT_SOURCE) }
        }
        val waistOut = SyncLogic.pickPerDay(dropCopies(M.WAIST, waist), AppleSources::bodyRank, lastOfDay = true).map { WaistEntry(it.time, it.value) }

        // interval heart-rate records (≤ maxIntervalMin, inside a recorded workout only): their value for each covered minute
        // without a point sample of the same source. Outside workouts nothing is spread (no gap filling).
        val wsAll = workouts.sortedBy { it.start }
        for (iv in hrIntervals) {
            val w = wsAll.firstOrNull { iv.start >= it.start - 60_000L && iv.end <= it.end + 60_000L }
            if (w == null) { hrIntervalsOutside++; continue }
            var spread = false
            var t = iv.start - Math.floorMod(iv.start, 60_000L)
            while (t < iv.end) {
                val d = Instant.ofEpochMilli(t).atZone(phoneZone).toLocalDate()
                val a = hr.getOrPut(d) { HashMap() }.getOrPut(iv.key) { HrAcc() }
                val m = SyncLogic.minuteSlot(d.atStartOfDay(phoneZone).toInstant().toEpochMilli(), t, HrDay.MINUTES)
                if (a.cnt[m].toInt() == 0) { a.sum[m] = iv.bpm; a.cnt[m] = (-1).toShort(); a.samples++; hrIntervalMinutes++; approxDays += d; spread = true }
                if (iv.end > a.newest) a.newest = iv.end
                t += 60_000L
            }
            if (spread) approxSpans += longArrayOf(iv.start, iv.end)
        }
        // heart rate: one source per day (Watch first), per-minute average
        val hrDays = ArrayList<HrDay>()
        for ((d, bySource) in hr) {
            val key = bySource.keys.minWith(compareBy<String> { AppleSources.wearableRank(it) }.thenByDescending { bySource.getValue(it).samples }.thenBy { it })
            val a = bySource.getValue(key)
            var sum = 0L; var n = 0; var mn = Int.MAX_VALUE; var mx = 0
            val blob = ByteArray(HrDay.MINUTES) { m ->
                val c = a.cnt[m].toInt()
                if (c == 0) 0 else {
                    // c == -1: minute filled from an interval record (sum = its value)
                    val v = (if (c < 0) a.sum[m] else (a.sum[m] + c / 2) / c).coerceIn(1, 255)
                    sum += v; n++; if (v < mn) mn = v; if (v > mx) mx = v
                    v.toByte()
                }
            }
            if (n == 0) continue
            val src = if (d in approxDays) IMPORT_SOURCE_APPROX else IMPORT_SOURCE
            hrDays += HrDay(d, src, blob, d.atStartOfDay(phoneZone).toInstant().toEpochMilli()) // v4.1: minute 0 instant
            rows += DailyRow(M.HR_AVG, d, sum.toDouble() / n, a.newest, src)
            rows += DailyRow(M.HR_MIN, d, mn.toDouble(), a.newest, src)
            rows += DailyRow(M.HR_MAX, d, mx.toDouble(), a.newest, src)
        }

        stagesOut.clear()
        rows += sleepRows()
        // after merging duplicates: the kept session is approximate when a spread interval falls inside it
        val merged = mergedWorkouts().map { w -> if (approxSpans.any { it[0] < w.end + 60_000L && it[1] > w.start - 60_000L }) w.copy(approxHr = true) else w }
        return ImportResult(rows, hrDays.sortedBy { it.day }, merged, waistOut, stats, elements, stagesOut.toList())
    }

    /** Hours whose only values came from third-party apps on days the Watch / iPhone had that metric (dropped copies). */
    var thirdPartyHoursDropped = 0
        private set
    /** Hours with values from several sources (one kept). */
    var multiSourceHours = 0
        private set

    /**
     * Body readings re-written by other apps (Yazio copied every Withings weigh-in ~17× a day in the real export):
     * a reading from a lower-ranked source is a copy when the preferred source (rank 0, the scale) has the same value
     * (±0.05 kg / ±0.1 %pt) from up to 7 days before to 1 hour after it. Copies are dropped and counted.
     */
    private fun dropCopies(metric: String, list: List<RawReading>): List<RawReading> {
        val tol = if (metric == M.FAT_PCT) 0.1 else 0.05
        val scale = list.filter { AppleSources.bodyRank(it.pkg) == 0 }.sortedBy { it.time }
        if (scale.isEmpty()) return list
        val times = LongArray(scale.size) { scale[it].time }
        val out = ArrayList<RawReading>(list.size)
        var copies = 0
        for (r in list) {
            if (AppleSources.bodyRank(r.pkg) == 0) { out += r; continue }
            var i = java.util.Arrays.binarySearch(times, r.time + 3_600_000L).let { if (it >= 0) it else -it - 2 }
            var copy = false
            while (i >= 0 && scale[i].time >= r.time - 7 * 86_400_000L) {
                if (Math.abs(scale[i].value - r.value) <= tol) { copy = true; break }
                i--
            }
            if (copy) copies++ else out += r
        }
        if (copies > 0) copiesDropped[metric] = (copiesDropped[metric] ?: 0) + copies
        return out
    }

    /** Metric → body readings dropped as copies of the scale's readings. */
    val copiesDropped = HashMap<String, Int>()
    /** Workouts recorded twice (e.g. the Watch's Workout app and Withings / Komoot) merged into one. */
    var workoutsMerged = 0
        private set
    /** Nights with only "in bed" time (iPhone bedtime, no sleep measured): not shown as sleep. */
    var inBedOnlyNights = 0
        private set

    /** Same session from two apps: one kept (Apple's Workout app on the Watch first, then the longer one). */
    private fun mergedWorkouts(): List<ImpWorkout> {
        val spans = workouts.map { WorkoutSpan(it.id, it.source, it.start, it.end) }
        val kept = SyncLogic.mergeWorkouts(spans, AppleSources::wearableRank, minMinutes = 0).mapTo(HashSet()) { it.id }
        workoutsMerged = workouts.size - kept.size
        return workouts.filter { it.id in kept }.sortedBy { it.start }
    }

    private val stagesOut = ArrayList<SleepStage>()

    /** Nights from sleep segments; see the class doc. */
    private fun sleepRows(): List<DailyRow> {
        class Night(val key: String, val start: Long, val end: Long, val day: LocalDate, val deep: Int, val rem: Int, val light: Int, val awake: Int, val segs: List<Seg>,
                    val unspecified: Int = 0) {
            val asleep get() = deep + rem + light + unspecified
        }
        val nights = ArrayList<Night>()
        for ((key, segs) in sleep) {
            val sorted = segs.sortedBy { it.start }
            var group = ArrayList<Seg>()
            fun flush() {
                if (group.isEmpty()) return
                val start = group.minOf { it.start }
                val end = group.maxOf { it.end }
                fun min(kinds: Set<String>) = minutesUnion(group.filter { it.kind in kinds })
                val deep = min(setOf("AsleepDeep"))
                val rem = min(setOf("AsleepREM"))
                val light = min(setOf("AsleepCore"))
                // v4.1 fix: "Asleep" / "AsleepUnspecified" (older watchOS, third-party apps) = asleep without stages,
                // not light; counted once where it overlaps a staged segment
                val unspecified = (min(setOf("AsleepDeep", "AsleepREM", "AsleepCore", "Asleep", "AsleepUnspecified")) - deep - rem - light).coerceAtLeast(0)
                val awake = min(setOf("Awake"))
                // only "InBed" (the iPhone's bedtime schedule / phone-use estimate): not measured sleep, not shown.
                // In the real export 320 of 379 nights were iPhone in-bed only, with implausible lengths.
                if (deep + rem + light + unspecified == 0) { inBedOnlyNights++; group = ArrayList(); return }
                val last = group.maxBy { it.end }
                nights += Night(key, start, end, Instant.ofEpochMilli(end).atOffset(last.wakeOffset).toLocalDate(), deep, rem, light, awake, group.toList(), unspecified)
                group = ArrayList()
            }
            var groupEnd = Long.MIN_VALUE
            for (s in sorted) {
                if (group.isNotEmpty() && s.start - groupEnd > 2 * 3_600_000L) flush()
                group += s
                groupEnd = maxOf(groupEnd, s.end)
            }
            flush()
        }
        val rows = ArrayList<DailyRow>()
        for ((d, list) in nights.groupBy { it.day }) {
            val key = list.map { it.key }.distinct().minWith(compareBy<String> { AppleSources.wearableRank(it) }.thenBy { it })
            val main = list.filter { it.key == key }.maxBy { it.asleep }
            if (main.asleep <= 0) continue
            // stage timeline as delivered (same names as Health Connect's: deep, rem, light, asleep, awake, inbed)
            for (sg in main.segs.sortedBy { it.start }) stagesOut += SleepStage(d, sg.start, sg.end, when (sg.kind) {
                "AsleepDeep" -> "deep"; "AsleepREM" -> "rem"; "AsleepCore" -> "light"; "Asleep", "AsleepUnspecified" -> "asleep"
                "Awake" -> "awake"; "InBed" -> "inbed"; else -> "unknown"
            }, IMPORT_SOURCE)
            val t = main.end
            rows += DailyRow(M.SLEEP_MIN, d, main.asleep.toDouble(), t, IMPORT_SOURCE)
            rows += DailyRow(M.SLEEP_DEEP, d, main.deep.toDouble(), t, IMPORT_SOURCE)
            rows += DailyRow(M.SLEEP_REM, d, main.rem.toDouble(), t, IMPORT_SOURCE)
            rows += DailyRow(M.SLEEP_LIGHT, d, main.light.toDouble(), t, IMPORT_SOURCE)
            rows += DailyRow(M.SLEEP_ASLEEP, d, main.unspecified.toDouble(), t, IMPORT_SOURCE)
            rows += DailyRow(M.SLEEP_AWAKE, d, main.awake.toDouble(), t, IMPORT_SOURCE)
            rows += DailyRow(M.SLEEP_START, d, main.start.toDouble(), t, IMPORT_SOURCE)
            rows += DailyRow(M.SLEEP_END, d, main.end.toDouble(), t, IMPORT_SOURCE)
        }
        return rows
    }

    /** Minutes covered by the union of the segments (overlaps counted once). */
    private fun minutesUnion(segs: List<Seg>): Int {
        if (segs.isEmpty()) return 0
        var total = 0L
        var curS = Long.MIN_VALUE; var curE = Long.MIN_VALUE
        for (s in segs.sortedBy { it.start }) {
            if (s.start > curE) { if (curE > curS) total += curE - curS; curS = s.start; curE = s.end } else if (s.end > curE) curE = s.end
        }
        if (curE > curS) total += curE - curS
        return (total / 60_000L).toInt()
    }

    private companion object {
        const val BASAL = "basal_kcal"
        val HANDLED = setOf(
            "HKCategoryTypeIdentifierSleepAnalysis", "HKQuantityTypeIdentifierBodyMass", "HKQuantityTypeIdentifierBodyFatPercentage",
            "HKQuantityTypeIdentifierLeanBodyMass", "HKQuantityTypeIdentifierHeight", "HKQuantityTypeIdentifierWaistCircumference",
            "HKQuantityTypeIdentifierRestingHeartRate", "HKQuantityTypeIdentifierHeartRateVariabilitySDNN", "HKQuantityTypeIdentifierVO2Max",
            "HKQuantityTypeIdentifierOxygenSaturation", "HKQuantityTypeIdentifierRespiratoryRate", "HKQuantityTypeIdentifierHeartRate",
        )
    }
}

/**
 * How imported data and Health Connect data combine (v4.1 real-device fix, replaces the per-type cutover): PER DAY,
 * one side is chosen for each type group, and its values are shown as they are — sources are never added together.
 *
 * | Group | Rule per day |
 * |---|---|
 * | steps, distance, floors | the larger daily total |
 * | calories burned | the larger total |
 * | heart rate, intensity, the 4 zones | the side with more measured minutes |
 * | sleep (all parts, stage timeline) | the night with more time asleep |
 * | body and vitals (weight, fat, lean, height, VO₂ max, resting HR, HRV, SpO₂, breathing) | Health Connect when it has a value, else the import |
 * | workouts | both, merged by the usual overlap rule (same workout = > 50 % of the shorter), Health Connect preferred |
 *
 * Why the larger value: in the transition month Health Connect had partial days (821 vs Apple's 24,226 steps) and
 * days without data; a single cutover hid Apple's real days behind them. "Larger" can lean high when both sides are
 * full-day estimates of the same thing; the evidence showed partial coverage is the far bigger error.
 */
object ImportMerge {
    enum class Side { HC, IMPORT }
    enum class Rule { MAX, HR_MINUTES, SLEEP, HC_FIRST }

    /** The group a cache metric belongs to: its parts are always taken from the same side on a day. */
    fun groupKey(metric: String): String = when (metric) {
        M.HR_AVG, M.HR_MIN, M.HR_MAX, M.ZONE_MIN -> HR
        in M.AZM -> HR
        M.ACTIVE_KCAL, M.REST_KCAL -> M.KCAL
        in M.SLEEP -> M.SLEEP_MIN
        else -> metric
    }
    @Deprecated("v4.1: per-day selection", ReplaceWith("groupKey(metric)")) fun cutoverKey(metric: String): String = groupKey(metric)

    fun rule(key: String): Rule = when (key) {
        M.STEPS, M.DISTANCE_M, M.FLIGHTS, M.KCAL -> Rule.MAX
        HR -> Rule.HR_MINUTES
        M.SLEEP_MIN -> Rule.SLEEP
        else -> Rule.HC_FIRST
    }

    /** Larger value wins; equal → Health Connect; null when neither side has the day. */
    fun pickMax(hc: Double?, imp: Double?): Side? = when {
        hc == null && imp == null -> null
        imp == null -> Side.HC
        hc == null -> Side.IMPORT
        imp > hc -> Side.IMPORT
        else -> Side.HC
    }

    fun pickHcFirst(hc: Boolean, imp: Boolean): Side? = when { hc -> Side.HC; imp -> Side.IMPORT; else -> null }

    /** Rows of the chosen side per day (ascending by day). Days without a choice are left out. */
    fun <T> choose(hc: List<T>, imp: List<T>, day: (T) -> LocalDate, side: (LocalDate) -> Side?): List<T> =
        (hc.filter { side(day(it)) == Side.HC } + imp.filter { side(day(it)) == Side.IMPORT }).sortedBy { day(it) }

    /** (days from Apple, days from Health Connect) for the Data check. */
    fun count(sides: Collection<Side?>): Pair<Int, Int> = sides.count { it == Side.IMPORT } to sides.count { it == Side.HC }

    /** Newest of the two; on the same day Health Connect wins. */
    fun <T> latest(hc: T?, imp: T?, day: (T) -> LocalDate): T? = when {
        hc == null -> imp
        imp == null -> hc
        day(imp).isAfter(day(hc)) -> imp
        else -> hc
    }

    const val HR = "hr_day"
    const val WORKOUTS = "workout"

    /** Groups the import can have (Data check order). */
    val KEYS = listOf(M.STEPS, M.KCAL, M.DISTANCE_M, M.FLIGHTS, HR, WORKOUTS, M.SLEEP_MIN, M.WEIGHT, M.FAT_PCT, M.LEAN_KG,
        M.HEIGHT, M.RHR, M.VO2, M.SPO2, M.RESP) // v5 (D7): no Apple HRV (SDNN)

    fun keyLabel(key: String): String = when (key) {
        M.STEPS -> "Steps"; M.KCAL -> "Calories burned"; M.DISTANCE_M -> "Distance"; M.FLIGHTS -> "Floors"
        HR -> "Heart rate & zone minutes"; WORKOUTS -> "Workouts"; M.SLEEP_MIN -> "Sleep"; M.WEIGHT -> "Weight"; M.FAT_PCT -> "Body fat"
        M.LEAN_KG -> "Lean mass"; M.HEIGHT -> "Height"; M.RHR -> "Resting HR"; M.HRV_SDNN -> "HRV (SDNN)"; M.VO2 -> "VO₂ max"
        M.SPO2 -> "Blood oxygen"; M.RESP -> "Breathing"; else -> key
    }
}
