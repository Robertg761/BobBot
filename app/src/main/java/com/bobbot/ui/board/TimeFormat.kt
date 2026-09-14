package com.bobbot.ui.board

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Tiny, defensive time helpers shared by the board, relay and automations screens.
 * Server timestamps arrive in several shapes (ISO with/without zone, epoch seconds,
 * "yyyy-MM-dd HH:mm:ss"), so every parse is best-effort and falls back to the raw string.
 */

private val DayMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")
private val DayMonthYear: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
private val DayMonthTime: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm")

/** Parse a loose timestamp into epoch millis, or null if nothing sensible comes out. */
fun parseEpochMillis(raw: String?): Long? {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return null

    // Pure number: epoch seconds (10 digits) or millis (13 digits).
    s.toDoubleOrNull()?.let { n ->
        if (n <= 0.0) return null
        return if (n > 1_000_000_000_000.0) n.toLong() else (n * 1000.0).toLong()
    }

    val normalized = if (s.length > 10 && s[10] == ' ') s.replaceFirst(' ', 'T') else s

    runCatching { return Instant.parse(normalized).toEpochMilli() }
    runCatching { return OffsetDateTime.parse(normalized).toInstant().toEpochMilli() }
    runCatching { return ZonedDateTime.parse(normalized).toInstant().toEpochMilli() }
    runCatching { return LocalDateTime.parse(normalized).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
    runCatching { return LocalDate.parse(normalized).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
    // Trailing microseconds or a stray "Z" the parsers above choked on.
    runCatching {
        val cut = normalized.substringBefore('+').removeSuffix("Z").take(19)
        return LocalDateTime.parse(cut).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    return null
}

/** "3m ago", "in 2h", "Mar 4" — or the raw string when it cannot be parsed. */
fun relativeTime(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val millis = parseEpochMillis(iso) ?: return iso.trim()
    return relativeFromMillis(millis)
}

/** Same, for the epoch-seconds doubles the session APIs return. */
fun relativeTime(epochSeconds: Double): String {
    if (epochSeconds <= 0.0) return ""
    val millis = if (epochSeconds > 1_000_000_000_000.0) epochSeconds.toLong() else (epochSeconds * 1000.0).toLong()
    return relativeFromMillis(millis)
}

/** An absolute, readable stamp ("Mar 4, 09:15"); falls back to the raw string. */
fun absoluteTime(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val millis = parseEpochMillis(iso) ?: return iso.trim()
    val local = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return runCatching { local.format(DayMonthTime) }.getOrDefault(iso.trim())
}

fun relativeFromMillis(millis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - millis
    val future = diff < 0
    val d = abs(diff)
    val seconds = d / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24

    val span = when {
        seconds < 45 -> return if (future) "in a moment" else "just now"
        minutes < 60 -> "${minutes.coerceAtLeast(1)}m"
        hours < 24 -> "${hours}h"
        days < 7 -> "${days}d"
        else -> {
            val local = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
            val nowLocal = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
            val fmt = if (local.year == nowLocal.year) DayMonth else DayMonthYear
            return runCatching { local.format(fmt) }.getOrDefault("")
        }
    }
    return if (future) "in $span" else "$span ago"
}
