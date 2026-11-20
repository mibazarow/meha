package com.andharness.ru.domain.person

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Deterministic, always-present context block «ЛЮДИ / ПОСЛЕДНИЕ ВЗАИМОДЕЙСТВИЯ» (update.md §4.6).
 *
 * The agent must consult the personal base (`person_find` / `person_get`) instead of re-scanning the
 * phone on every request. To make that possible without blowing up the context window, the block is
 * built here with **hard caps** and is intentionally lossy:
 *
 * - at most [MAX_PERSONS] people, ranked by the recency then the frequency of their interactions;
 * - at most [MAX_INTERACTIONS_PER_PERSON] interactions per person, newest first;
 * - at most [MAX_LINES] rendered person lines;
 * - at most [MAX_CHARS] characters of the whole block (header included);
 * - each gist is shown only as a bounded [GIST_CHARS]-character snippet.
 *
 * Only metadata + gist are read (update.md §4.9) — the message body is never stored, so it can never
 * appear here. The object is pure and JVM-testable (no Android runtime); the caller fetches the data
 * and passes it in, and an empty input renders an empty string so the caller can omit the block.
 *
 * Reference for the date formatter: https://developer.android.com/reference/java/time/format/DateTimeFormatter
 */
object PersonContextBlock {

    /** Maximum number of people in the block. */
    const val MAX_PERSONS = 10

    /** Maximum number of interactions shown per person. */
    const val MAX_INTERACTIONS_PER_PERSON = 3

    /** Maximum number of rendered person lines. */
    const val MAX_LINES = 30

    /** Maximum total length of the rendered block, in characters (header included). */
    const val MAX_CHARS = 1200

    /** Maximum length of a single gist inside the block, in characters. */
    const val GIST_CHARS = 80

    const val HEADER = "ЛЮДИ / ПОСЛЕДНИЕ ВЗАИМОДЕЙСТВИЯ"

    const val INSTRUCTION =
        "Обращайся к персональной базе (person_find/person_get), а не пересканируй телефон."

    private const val ELLIPSIS = '…'
    private const val WHITESPACE_CUT_MIN = 24

    private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")

    /** One person together with the interactions to show (order does not matter; sorted here). */
    data class Entry(
        val person: Person,
        val interactions: List<Interaction> = emptyList()
    ) {
        /** Newest interaction time, or `null` when the person has none. */
        val lastInteractionAt: Long? get() = interactions.maxOfOrNull { it.at }
    }

    /**
     * Renders the block, or an empty string when there is nothing to show (the caller then omits it).
     *
     * @param zone time zone used to format timestamps; injected so tests are deterministic.
     */
    fun render(entries: List<Entry>, zone: ZoneId = ZoneId.systemDefault()): String {
        if (entries.isEmpty()) return ""

        val ranked = entries
            .filter { it.person.displayName.isNotBlank() || it.interactions.isNotEmpty() }
            .sortedWith(
                compareByDescending<Entry> { it.lastInteractionAt ?: Long.MIN_VALUE }
                    .thenByDescending { it.interactions.size }
                    .thenBy { it.person.id }
            )
            .take(MAX_PERSONS)
        if (ranked.isEmpty()) return ""

        val header = "$HEADER\n$INSTRUCTION"
        val builder = StringBuilder(header)
        var lines = 0
        for (entry in ranked) {
            if (lines >= MAX_LINES) break
            val line = "\n" + personLine(entry, zone)
            val candidate = builder.toString() + line
            if (candidate.length <= MAX_CHARS) {
                builder.append(line)
                lines++
                continue
            }
            // Not enough budget for the whole line: append an honestly cut tail, then stop.
            val room = MAX_CHARS - builder.length - 1
            if (room < WHITESPACE_CUT_MIN) break
            builder.append('\n').append(cut(line.trimStart(), room))
            lines++
            break
        }
        return builder.toString()
    }

    /** Renders one person: `- #id Имя (взаимодействий: N): 01.01 12:00 SMS→ gist; ...`. */
    private fun personLine(entry: Entry, zone: ZoneId): String = buildString {
        append("- #")
        append(entry.person.id)
        append(' ')
        append(entry.person.displayName.trim().ifEmpty { "Без имени" })
        append(" (взаимодействий: ")
        append(entry.interactions.size)
        append("): ")
        val recent = entry.interactions.sortedByDescending { it.at }.take(MAX_INTERACTIONS_PER_PERSON)
        if (recent.isEmpty()) {
            append("нет взаимодействий")
        } else {
            recent.forEachIndexed { index, interaction ->
                if (index > 0) append("; ")
                append(interactionFragment(interaction, zone))
            }
        }
    }

    private fun interactionFragment(interaction: Interaction, zone: ZoneId): String = buildString {
        append(DATE_TIME.format(Instant.ofEpochMilli(interaction.at).atZone(zone)))
        append(' ')
        append(interaction.channel.name)
        append(directionArrow(interaction.direction))
        val gist = interaction.summary?.trim()
        if (!gist.isNullOrEmpty()) {
            append(' ')
            append(cut(gist, GIST_CHARS))
        }
    }

    private fun directionArrow(direction: InteractionDirection): String = when (direction) {
        InteractionDirection.INCOMING -> "←"
        InteractionDirection.OUTGOING -> "→"
        InteractionDirection.MISSED -> "✕"
        InteractionDirection.UNKNOWN -> "·"
    }

    /** Cuts [raw] to at most [max] characters, ending with an in-budget ellipsis when it had to cut. */
    private fun cut(raw: String, max: Int): String {
        if (max <= 0) return ""
        if (raw.length <= max) return raw
        if (max == 1) return ELLIPSIS.toString()
        return raw.take(max - 1).trimEnd() + ELLIPSIS
    }
}
