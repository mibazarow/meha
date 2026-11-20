package com.andharness.ru.domain.automation

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure, Android-free rendering of the firing-event log. Kept out of the tool handler
 * so the exact output format — `18.09 22:14 · battery_low · Батарея разряжена` — is unit-testable
 * without a device; the handler only adds the header and empty-state wording.
 */
object AutomationEventLog {

    private val TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd.MM HH:mm", Locale.ROOT)

    /** Local-time stamp of an occurrence, e.g. `18.09 22:14`. */
    fun formatTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).format(TIME_FORMAT)

    /**
     * One line: `dd.MM HH:mm · <eventType> · <ruleName> · <observed>`.
     * Blank parts are omitted; the observed value falls back to the payload when the value is empty,
     * so a line never invents data it does not have.
     */
    fun line(record: AutomationEventRecord, zone: ZoneId = ZoneId.systemDefault()): String {
        val observed = record.value?.takeIf { it.isNotBlank() }
            ?: record.payload?.takeIf { it.isNotBlank() }
        val tail = buildList {
            record.ruleName?.takeIf { it.isNotBlank() }?.let { add(it) }
            observed?.let { add(it) }
        }.joinToString(separator = " · ")
        val suffix = if (tail.isEmpty()) "" else " · $tail"
        return "${formatTime(record.createdAt, zone)} · ${record.eventType}$suffix"
    }

    /** Newest-first rows as one line each; empty input yields an empty string. */
    fun format(records: List<AutomationEventRecord>, zone: ZoneId = ZoneId.systemDefault()): String =
        records.joinToString(separator = "\n") { line(it, zone) }
}
