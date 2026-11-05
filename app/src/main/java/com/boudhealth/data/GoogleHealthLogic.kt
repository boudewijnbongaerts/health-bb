package com.boudhealth.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId

/*
 * Google Health API (VO₂ max only): the pure part, no Android. URLs, the date filter and its casing fallback, response
 * parsing, the fetch window and the 6-hour gate. The Android side (auth, HTTP, cache, notification) is GoogleHealth.kt.
 */

/** What Settings shows and the sync needs. Stored in DataStore. Never a token. */
@Serializable
data class GhAccount(
    val connected: Boolean = false,
    val email: String? = null,
    /** Last successful VO₂ max fetch (ms). */
    val lastFetch: Long? = null,
    /** The first full read (3 years back) is done; later fetches read the last 7 days only. */
    val historyDone: Boolean = false,
    /** Silent authorization needs consent again (grant expired or removed): Settings shows Connect, one notification. */
    val needsReconnect: Boolean = false,
)

/** One day of VO₂ max as delivered. [estimated] = Google's own "estimated" flag (low confidence), not Health BB's. */
data class Vo2Point(val date: LocalDate, val vo2Max: Double, val estimated: Boolean)

/** One page of `dataPoints:list`. [next] = null when this was the last page. */
data class GhPage(val points: List<Vo2Point>, val next: String?)

/** A cached Google Health API day (own table, see Cache.upsertGh). */
data class GhRow(val day: LocalDate, val value: Double, val time: Long, val estimated: Boolean)

/** Parsed error body (`{"error": {"code", "status", "message", "details": [{"reason"}]}}`). */
data class GhError(val code: Int?, val status: String?, val reasons: List<String>)

object GhApi {
    const val SOURCE = SourceOrder.GOOGLE_HEALTH_API
    const val BASE = "https://health.googleapis.com/v4/users/me"
    const val IDENTITY_URL = "$BASE/identity"
    const val VO2_LIST_URL = "$BASE/dataTypes/daily-vo2-max/dataPoints"

    const val SCOPE_METRICS = "https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly"
    const val SCOPE_ACTIVITY = "https://www.googleapis.com/auth/googlehealth.activity_and_fitness.readonly"
    /** Non-sensitive; only so the connected account's e-mail can be shown in Settings. */
    const val SCOPE_EMAIL = "https://www.googleapis.com/auth/userinfo.email"
    val SCOPES = listOf(SCOPE_METRICS, SCOPE_ACTIVITY, SCOPE_EMAIL)

    const val PAGE_SIZE = 1000
    /** Safety stop for a broken nextPageToken loop (1000 days a page: 3 years fit in 2). */
    const val MAX_PAGES = 50
    const val HISTORY_YEARS = 3L
    const val RECENT_DAYS = 7L
    const val MIN_INTERVAL_MS = 6 * 60 * 60_000L
    /** After a failed fetch (not offline), wait this long before the next background try. */
    const val RETRY_AFTER_FAILURE_MS = 60 * 60_000L
    const val ACCOUNT_NOT_LINKED = "ACCOUNT_NOT_LINKED"

    /** The two spellings of the filter's data-type name (the docs disagree, see OWNAPI.md §1). */
    enum class FilterField(val key: String) { SNAKE("daily_vo2_max"), CAMEL("dailyVo2Max") }

    /** AIP-160 filter: every day on or after [from]. */
    fun filter(field: FilterField, from: LocalDate): String = "${field.key}.date >= \"$from\""

    fun listUrl(filter: String, pageSize: Int = PAGE_SIZE, pageToken: String? = null): String {
        val q = StringBuilder("$VO2_LIST_URL?filter=").append(enc(filter)).append("&pageSize=").append(pageSize)
        if (!pageToken.isNullOrEmpty()) q.append("&pageToken=").append(enc(pageToken))
        return q.toString()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /**
     * Filter casing fallback: a 400 on the FIRST page with the snake_case name → retry with camelCase. Not for a 400
     * that says the account is not linked, not on later pages, never twice. null = no retry.
     */
    fun fallbackField(current: FilterField, httpCode: Int, firstPage: Boolean, body: String?): FilterField? =
        if (httpCode == 400 && firstPage && current == FilterField.SNAKE && !isAccountNotLinked(body)) FilterField.CAMEL else null

    /**
     * First day to read: 3 years back until the first full read is done; then the last 7 days (late updates), reaching
     * further back to 7 days before the last successful fetch when that is older (phone off, reconnect after weeks).
     * Never more than 3 years.
     */
    fun fromDate(today: LocalDate, historyDone: Boolean, lastFetchDay: LocalDate? = null): LocalDate {
        val floor = today.minusYears(HISTORY_YEARS)
        if (!historyDone) return floor
        val recent = today.minusDays(RECENT_DAYS)
        val gap = lastFetchDay?.minusDays(RECENT_DAYS)
        val from = if (gap != null && gap.isBefore(recent)) gap else recent
        return if (from.isBefore(floor)) floor else from
    }

    /**
     * Whether a background fetch is due: [force] (Sync now / connect), never fetched, or the last success is 6 hours
     * old (or in the future: clock changed); and no failure within the last hour.
     */
    fun due(force: Boolean, lastFetch: Long?, lastFailure: Long?, now: Long): Boolean {
        if (force) return true
        val stale = lastFetch == null || now - lastFetch !in 0 until MIN_INTERVAL_MS
        val resting = lastFailure != null && now - lastFailure in 0 until RETRY_AFTER_FAILURE_MS
        return stale && !resting
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Parses one list page. Points without a valid date or value are skipped. Throws on a body that is not JSON. */
    fun parsePage(body: String): GhPage {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return GhPage(emptyList(), null)
        val arr = root["dataPoints"] as? JsonArray
        val points = arr?.mapNotNull { dp ->
            val o = dp as? JsonObject ?: return@mapNotNull null
            val d = (o["dailyVo2Max"] ?: o["daily_vo2_max"]) as? JsonObject ?: return@mapNotNull null
            val date = parseDate(d["date"]) ?: return@mapNotNull null
            val v = num(d["vo2Max"] ?: d["vo2_max"]) ?: return@mapNotNull null
            if (v.isNaN() || v.isInfinite() || v <= 0.0 || v > 100.0) return@mapNotNull null
            val est = (d["estimated"] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.content.equals("true", true) } ?: false
            Vo2Point(date, v, est)
        } ?: emptyList()
        val next = (root["nextPageToken"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
        return GhPage(points, next)
    }

    /** google.type.Date `{year, month, day}`, or a "YYYY-MM-DD" string. */
    fun parseDate(e: JsonElement?): LocalDate? = when (e) {
        is JsonObject -> {
            val y = int(e["year"]); val m = int(e["month"]); val d = int(e["day"])
            if (y == null || m == null || d == null || y <= 0 || m <= 0 || d <= 0) null
            else runCatching { LocalDate.of(y, m, d) }.getOrNull()
        }
        is JsonPrimitive -> if (e.isString) runCatching { LocalDate.parse(e.content.take(10)) }.getOrNull() else null
        else -> null
    }

    private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
    private fun int(e: JsonElement?): Int? = (e as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() }

    /** Parses an error body; null when it is not one. */
    fun parseError(body: String?): GhError? {
        if (body.isNullOrBlank()) return null
        val err = runCatching { (json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonObject }.getOrNull() ?: return null
        val reasons = (err["details"] as? JsonArray)?.mapNotNull { ((it as? JsonObject)?.get("reason") as? JsonPrimitive)?.content }.orEmpty()
        return GhError(int(err["code"]), (err["status"] as? JsonPrimitive)?.content, reasons)
    }

    /** 400 ACCOUNT_NOT_LINKED (Google Health app not set up for this account), as status, reason or in the text. */
    fun isAccountNotLinked(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        val e = parseError(body)
        return e?.status == ACCOUNT_NOT_LINKED || e?.reasons?.contains(ACCOUNT_NOT_LINKED) == true || body.contains(ACCOUNT_NOT_LINKED)
    }

    /** "Time" of a daily value: the end of [day], or [now] for today. */
    fun dayTime(day: LocalDate, zone: ZoneId, now: Long): Long =
        minOf(now, day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1)

    /** One row per date: a non-estimated point wins over an estimated one; otherwise the first delivered (newest). */
    fun rows(points: List<Vo2Point>, zone: ZoneId, now: Long): List<GhRow> =
        points.groupBy { it.date }.toSortedMap().map { (d, ps) ->
            val p = ps.firstOrNull { !it.estimated } ?: ps.first()
            GhRow(d, p.vo2Max, dayTime(d, zone, now), p.estimated)
        }
}
