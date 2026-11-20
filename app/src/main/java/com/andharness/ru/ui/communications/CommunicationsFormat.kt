package com.andharness.ru.ui.communications

import com.andharness.ru.domain.communications.Recording
import com.andharness.ru.domain.communications.RecordingSourceType
import com.andharness.ru.domain.communications.TranscriptStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Presentation-only helpers for the Communications screens («Связи» list and the transcript
 * viewer). Kept free of Compose/Android types so they are unit-testable on the JVM
 * (see `CommunicationsFormatTest`).
 *
 * The enum labels mirror the underlying enums exactly and cover every constant via `when`, so a new
 * [TranscriptStatus] or [RecordingSourceType] cannot silently fall back to a wrong label.
 */

/** `m:ss` for clips under an hour, `h:mm:ss` from an hour up; negative input clamps to `0:00`. */
internal fun formatClock(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000L
    val seconds = totalSeconds % 60L
    val minutes = (totalSeconds / 60L) % 60L
    val hours = totalSeconds / 3600L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

/** Segment timestamp shown in the transcript viewer, e.g. `[1:07]`. */
internal fun timestampLabel(ms: Long): String = "[${formatClock(ms)}]"

/** Clip duration shown on a recording row, e.g. `3:42`. */
internal fun durationLabel(ms: Long): String = formatClock(ms)

/** `dd.MM.yyyy HH:mm` in [zone]; `zone` is a parameter so tests can pin a deterministic zone. */
internal fun formatDateTime(
    epochMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String = DATE_TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

/** Honest Russian label for the persisted [TranscriptStatus]. */
internal fun statusLabel(status: TranscriptStatus): String = when (status) {
    TranscriptStatus.PENDING -> "Ожидает"
    TranscriptStatus.RUNNING -> "Расшифровка…"
    TranscriptStatus.DONE -> "Готово"
    TranscriptStatus.FAILED -> "Ошибка"
    TranscriptStatus.SKIPPED -> "Пропущено"
}

/** Honest Russian label for the persisted [RecordingSourceType]. */
internal fun sourceLabel(source: RecordingSourceType): String = when (source) {
    RecordingSourceType.DICTAPHONE -> "Диктофон"
    RecordingSourceType.MANUAL -> "Запись"
    RecordingSourceType.MESSENGER_VOICE -> "Голосовое"
    RecordingSourceType.IMPORTED -> "Импорт"
}

/** One-line metadata for a recording row: date, duration and where it came from. */
internal fun recordingMetaLine(
    recording: Recording,
    zone: ZoneId = ZoneId.systemDefault(),
): String = formatDateTime(recording.createdAt, zone) +
    " · " + durationLabel(recording.durationMs) +
    " · " + sourceLabel(recording.sourceType)

/**
 * Title of a recording row: the stored [Recording.displayName] when it has one, otherwise the file
 * name of a plain path. A `content://` URI carries no human name (its last segment is a media id),
 * so those fall back to `Запись #<id>` together with path-less rows.
 */
internal fun recordingTitle(recording: Recording): String {
    recording.displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val path = recording.filePath
    if (!path.startsWith("content://", ignoreCase = true)) {
        path.substringAfterLast('/').trim().takeIf { it.isNotEmpty() }?.let { return it }
    }
    return "Запись #${recording.id}"
}

/**
 * Speaker label for a transcript segment: the device owner is always «Я»; otherwise a linked
 * person's name wins over the speaker's own name, and an unnamed speaker falls back to
 * «Говорящий N» (1-based [fallbackIndex]) or the generic «Говорящий» when no index is known.
 */
internal fun speakerLabel(
    isSelf: Boolean,
    name: String?,
    personName: String?,
    fallbackIndex: Int?,
): String {
    if (isSelf) return "Я"
    personName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    name?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return fallbackIndex?.let { "Говорящий $it" } ?: "Говорящий"
}

private val DATE_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.ROOT)
