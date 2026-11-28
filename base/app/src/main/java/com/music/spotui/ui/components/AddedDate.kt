package com.music.spotui.ui.components

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * When a song was added to a playlist or to Liked Songs, as the desktop app's "Date added" column
 * shows it: `dd.MM.yyyy`, in the phone's own time zone. Spotify sends an ISO instant
 * (`2026-01-01T00:00:00Z`); rows without one (an album's songs) show nothing.
 */
object AddedDate {
    private val pattern: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    /** Spotify sends Unix epoch 0 (`1970-01-01T00:00:00Z`) as a placeholder on algorithmic mixes. */
    private val MIN_VALID_DATE: LocalDate = LocalDate.of(2000, 1, 1)

    /** Formatted once per value: this runs for every visible row on every recomposition. */
    private val cache = ConcurrentHashMap<String, String>()

    /** [iso] as `dd.MM.yyyy`, or null when it is blank, a sentinel epoch date, or not a date. */
    fun format(iso: String?): String? {
        val raw = iso?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return cache.getOrPut(raw) { parse(raw)?.format(pattern).orEmpty() }.ifEmpty { null }
    }

    internal fun parse(raw: String): LocalDate? {
        val date = runCatching { Instant.parse(raw).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
            ?: runCatching { LocalDate.parse(raw.take(10)) }.getOrNull()
            ?: return null
        return date.takeUnless { it.isBefore(MIN_VALID_DATE) }
    }
}
