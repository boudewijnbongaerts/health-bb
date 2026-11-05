package com.boudhealth.ui.components

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Number / time formatting rules (DESIGN §7). Pure functions, usable from widgets too. */
object Fmt {
    // R5 #42: English only, whatever the phone's language ("4 Sep", "1,234"), the same as R3Texts
    private val locale: Locale get() = Locale.ENGLISH
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** English thousands separator, [decimals] fixed decimals. */
    fun num(v: Double, decimals: Int = 0): String =
        if (decimals <= 0) NumberFormat.getIntegerInstance(locale).format(v.roundToLong())
        else NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = decimals; maximumFractionDigits = decimals }.format(v)

    fun int(v: Number): String = NumberFormat.getIntegerInstance(locale).format(v.toLong())

    /** Compact axis value: 10000 → "10k", 2500 → "2.5k", 950 → "950". */
    fun compact(v: Double, decimals: Int = 0): String {
        val a = abs(v)
        return when {
            a >= 1_000_000 -> trim(v / 1_000_000) + "M"
            a >= 10_000 -> "${(v / 1000).roundToLong()}k"
            a >= 1_000 -> trim(v / 1000) + "k"
            else -> num(v, decimals)
        }
    }

    private fun trim(v: Double): String {
        val r = Math.round(v * 10) / 10.0
        return if (r == Math.floor(r)) r.toLong().toString() else String.format(locale, "%.1f", r)
    }

    /** Signed delta with fixed decimals: "+1.2", "−0.8" (true minus sign), "0". */
    fun delta(v: Double, decimals: Int = 0): String {
        val s = num(abs(v), decimals)
        return when {
            Math.round(v * Math.pow(10.0, decimals.toDouble())) == 0L -> s
            v > 0 -> "+$s"
            else -> "−$s"
        }
    }

    /** Minutes → "7h 20m" / "7h 05m" / "45m" (R5 #41: minutes padded after hours, as R3 "7h 05m"). Use [durationParts] to style numbers and letters differently. */
    fun duration(minutes: Int): String = durationParts(minutes).joinToString(" ") { it.first + it.second }

    /** Minutes → [("7","h"), ("05","m")]: numbers in number style, letters in unit style. */
    fun durationParts(minutes: Int): List<Pair<String, String>> {
        val h = minutes / 60; val m = minutes % 60
        return when {
            h == 0 -> listOf(m.toString() to "m")
            else -> listOf(h.toString() to "h", m.toString().padStart(2, '0') to "m")
        }
    }

    fun date(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    fun dateTime(ms: Long): LocalDateTime = Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime()
    fun epoch(t: LocalDateTime): Long = t.atZone(zone).toInstant().toEpochMilli()

    fun hm(ms: Long): String = DateTimeFormatter.ofPattern("HH:mm", locale).format(dateTime(ms))

    /** DESIGN §7: "11:34" today, "Yesterday 12:03", "Thu 10:15" this week, "23 Oct" otherwise. */
    fun timestamp(ms: Long, now: LocalDate = LocalDate.now(zone)): String {
        val t = dateTime(ms); val d = t.toLocalDate()
        val days = ChronoUnit.DAYS.between(d, now)
        return when {
            days == 0L -> hm(ms)
            days == 1L -> "Yesterday ${hm(ms)}"
            days in 2L..6L -> DateTimeFormatter.ofPattern("EEE HH:mm", locale).format(t)
            d.year == now.year -> DateTimeFormatter.ofPattern("d MMM", locale).format(d)
            else -> DateTimeFormatter.ofPattern("d MMM yyyy", locale).format(d)
        }
    }

    /** Day label: "Today" / "Yesterday" / "Tue 27 Oct" (other years: "Tue 27 Oct 2025"). */
    fun dayLabel(d: LocalDate, now: LocalDate = LocalDate.now(zone)): String = when (ChronoUnit.DAYS.between(d, now)) {
        0L -> "Today"
        1L -> "Yesterday"
        else -> DateTimeFormatter.ofPattern(if (d.year == now.year) "EEE d MMM" else "EEE d MMM yyyy", locale).format(d)
    }

    /** "Yesterday · 12:03" / "Thu · 10:15" / "23 Oct · 07:40" (list-row subtitles). */
    fun rowWhen(ms: Long, now: LocalDate = LocalDate.now(zone)): String {
        val d = date(ms)
        val day = when (ChronoUnit.DAYS.between(d, now)) {
            0L -> "Today"; 1L -> "Yesterday"
            in 2L..6L -> DateTimeFormatter.ofPattern("EEE", locale).format(d)
            else -> DateTimeFormatter.ofPattern("d MMM", locale).format(d)
        }
        return "$day · ${hm(ms)}"
    }

    fun shortDate(d: LocalDate): String = DateTimeFormatter.ofPattern("d MMM", locale).format(d)

    /** "26 Oct – 1 Nov" (adds the year when the range crosses years). */
    fun range(start: LocalDate, endInclusive: LocalDate): String {
        val f = DateTimeFormatter.ofPattern("d MMM", locale)
        val fy = DateTimeFormatter.ofPattern("d MMM yyyy", locale)
        return if (start.year != endInclusive.year) "${fy.format(start)} – ${fy.format(endInclusive)}"
        else if (start == endInclusive) f.format(start) else "${f.format(start)} – ${f.format(endInclusive)}"
    }

    /** Narrow day letter (S M T W T F S). */
    fun dayLetter(d: LocalDate): String = d.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, locale)
}
