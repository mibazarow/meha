package com.andharness.ru.ui.communications

import com.andharness.ru.data.db.entity.NoteEntity
import com.andharness.ru.domain.person.Interaction
import com.andharness.ru.domain.person.InteractionChannel
import com.andharness.ru.domain.person.InteractionDirection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Presentation-only timeline model and helpers for the person profile screen. Everything here is
 * Android-free and deterministic for a fixed [ZoneId], so the grouping/ordering/format rules are
 * unit-testable on the JVM (see `PersonTimelineFormatTest`).
 *
 * The screen merges three independent sources — interactions (calls/SMS/messenger), transcript
 * segments where the person is the speaker, and linked memories/opinions — into one flat list of
 * [PersonTimelineEntry] and hands it to [PersonTimelineComposer.compose] for day grouping.
 */

/** What produced a timeline entry; drives the per-row badge and its colour. */
enum class PersonTimelineKind { CALL, SMS, MESSENGER, EMAIL, CHAT, TRANSCRIPT, MEMORY, OPINION }

/**
 * One merged event on a person's timeline. [id] is a source-prefixed stable key for the list; [at]
 * is the event time (epoch millis) used for ordering and day grouping; [title]/[body]/[meta] are
 * already-honest strings (`null` when the source has nothing to show).
 */
data class PersonTimelineEntry(
    val id: String,
    val kind: PersonTimelineKind,
    val at: Long,
    val title: String? = null,
    val body: String? = null,
    val meta: String? = null,
)

/** One day of the timeline: its Russian date label and that day's entries, newest first. */
data class PersonTimelineSection(
    val dayLabel: String,
    val entries: List<PersonTimelineEntry>,
)

/** Pure day-grouping of the merged entries: newest day first, newest event first inside a day. */
internal object PersonTimelineComposer {

    fun compose(
        entries: List<PersonTimelineEntry>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<PersonTimelineSection> {
        if (entries.isEmpty()) return emptyList()
        return entries
            .groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }
            .toSortedMap(compareByDescending<java.time.LocalDate> { it })
            .map { (date, dayEntries) ->
                PersonTimelineSection(
                    dayLabel = DATE_FORMAT.format(date),
                    entries = dayEntries.sortedWith(
                        compareByDescending<PersonTimelineEntry> { it.at }.thenBy { it.id }
                    ),
                )
            }
    }
}

/** Maps a stored [InteractionChannel] to its timeline kind (exhaustive, so a new channel fails loud). */
internal fun timelineKind(channel: InteractionChannel): PersonTimelineKind = when (channel) {
    InteractionChannel.SMS -> PersonTimelineKind.SMS
    InteractionChannel.CALL -> PersonTimelineKind.CALL
    InteractionChannel.MESSENGER -> PersonTimelineKind.MESSENGER
    InteractionChannel.EMAIL -> PersonTimelineKind.EMAIL
    InteractionChannel.CHAT -> PersonTimelineKind.CHAT
}

/** Honest Russian badge label for a timeline [kind]. */
internal fun timelineKindLabel(kind: PersonTimelineKind): String = when (kind) {
    PersonTimelineKind.CALL -> "Звонок"
    PersonTimelineKind.SMS -> "СМС"
    PersonTimelineKind.MESSENGER -> "Мессенджер"
    PersonTimelineKind.EMAIL -> "Email"
    PersonTimelineKind.CHAT -> "Чат"
    PersonTimelineKind.TRANSCRIPT -> "Речь"
    PersonTimelineKind.MEMORY -> "Память"
    PersonTimelineKind.OPINION -> "Мнение"
}

/** Direction hint for a call/message row, or `null` when the stored direction is unknown. */
internal fun directionLabel(direction: InteractionDirection): String? = when (direction) {
    InteractionDirection.INCOMING -> "входящий"
    InteractionDirection.OUTGOING -> "исходящий"
    InteractionDirection.MISSED -> "пропущенный"
    InteractionDirection.UNKNOWN -> null
}

/**
 * One interaction row: metadata only (the message body is never stored), so [body] is the stored
 * gist/summary and may be `null`.
 */
internal fun interactionEntry(interaction: Interaction): PersonTimelineEntry = PersonTimelineEntry(
    id = "i${interaction.id}",
    kind = timelineKind(interaction.channel),
    at = interaction.at,
    title = interaction.title?.takeIf { it.isNotBlank() },
    body = interaction.summary?.takeIf { it.isNotBlank() },
    meta = directionLabel(interaction.direction),
)

/** One transcript moment where the person is the speaker; [meta] is the in-recording timecode. */
internal fun transcriptEntry(
    segmentId: Long,
    at: Long,
    recordingLabel: String,
    startMs: Long,
    endMs: Long,
    text: String,
): PersonTimelineEntry = PersonTimelineEntry(
    id = "t$segmentId",
    kind = PersonTimelineKind.TRANSCRIPT,
    at = at,
    title = recordingLabel.takeIf { it.isNotBlank() },
    body = text,
    meta = "[${formatClock(startMs)}–${formatClock(endMs)}]",
)

/** One linked memory/opinion: the note text at its creation time. */
internal fun noteEntry(kind: PersonTimelineKind, note: NoteEntity): PersonTimelineEntry =
    PersonTimelineEntry(
        id = (if (kind == PersonTimelineKind.OPINION) "o" else "m") + note.id,
        kind = kind,
        at = note.createdAt,
        body = note.text,
    )

/** `HH:mm` for a row's time rail, in [zone] (a parameter so tests pin a deterministic zone). */
internal fun clockLabel(at: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    CLOCK_FORMAT.format(Instant.ofEpochMilli(at).atZone(zone))

/** `d MMMM yyyy` (Russian month name) for a section header, in [zone]. */
internal fun dayLabel(at: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DATE_FORMAT.format(Instant.ofEpochMilli(at).atZone(zone).toLocalDate())

private val DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"))

private val CLOCK_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
