package com.andharness.ru.domain.communications

import com.andharness.ru.domain.formatting.collapseWhitespace
import com.andharness.ru.domain.person.InteractionChannel
import com.andharness.ru.domain.person.InteractionDirection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure composer for the `person_brief` tool: turns already-fetched personal-base data into one
 * bounded, honest Russian brief about a person.
 *
 * The tool must summarise a person's recent communication footprint without ever flooding the
 * context window, so the composer applies hard caps to every section and to the whole text, and
 * filters every section to the requested time window:
 * - at most [MAX_INTERACTIONS] interactions (newest first);
 * - at most [MAX_MOMENTS] transcript moments (newest recording first);
 * - at most [MAX_MEMORIES] memories and [MAX_OPINIONS] opinions;
 * - snippets are cut to [SNIPPET_CHARS];
 * - the whole brief is cut to [MAX_CHARS] characters.
 *
 * It is Android-free and deterministic for a fixed [now]/[ZoneId], so it is unit-testable on the
 * JVM. The caller fetches the data from the repositories and passes it in.
 *
 * Reference for the date/time formatters:
 * https://developer.android.com/reference/java/time/format/DateTimeFormatter
 */
object PersonBriefComposer {

    /** Maximum number of interactions shown. */
    const val MAX_INTERACTIONS = 10

    /** Maximum number of transcript moments shown. */
    const val MAX_MOMENTS = 8

    /** Maximum number of memories shown. */
    const val MAX_MEMORIES = 8

    /** Maximum number of opinions shown. */
    const val MAX_OPINIONS = 5

    /** Hard cap on the whole rendered brief, in characters. */
    const val MAX_CHARS = 1800

    /** Maximum length of a single snippet, in characters. */
    const val SNIPPET_CHARS = 140

    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val ELLIPSIS = '…'
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    /** One interaction line (metadata + gist only, never a message body). */
    data class InteractionLine(
        val at: Long,
        val channel: InteractionChannel,
        val direction: InteractionDirection,
        val summary: String?,
    )

    /** One transcript moment where the person speaks or is mentioned. */
    data class TranscriptMoment(
        val at: Long,
        val recordingLabel: String,
        val startMs: Long,
        val endMs: Long,
        val text: String,
    )

    /** One dated piece of text (a memory or an opinion). */
    data class DatedText(val at: Long, val text: String)

    /**
     * Renders the brief. Every list is filtered to the last [days] days (relative to [now]) and
     * capped; an all-empty input still produces an honest "nothing found in the window" brief rather
     * than an empty string.
     */
    fun render(
        personName: String,
        personId: Long,
        days: Int,
        now: Long,
        interactions: List<InteractionLine>,
        moments: List<TranscriptMoment>,
        memories: List<DatedText>,
        opinions: List<DatedText>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val windowDays = days.coerceIn(1, 3650)
        val cutoff = now - windowDays * DAY_MS

        val recentInteractions = interactions
            .filter { inWindow(it.at, cutoff) }
            .sortedByDescending { it.at }
            .take(MAX_INTERACTIONS)
        val recentMoments = moments
            .filter { inWindow(it.at, cutoff) }
            .sortedByDescending { it.at }
            .take(MAX_MOMENTS)
        val recentMemories = memories
            .filter { inWindow(it.at, cutoff) }
            .sortedByDescending { it.at }
            .take(MAX_MEMORIES)
        val recentOpinions = opinions
            .filter { inWindow(it.at, cutoff) }
            .sortedByDescending { it.at }
            .take(MAX_OPINIONS)

        val text = buildString {
            append("Сводка по «")
            append(personName.trim().ifEmpty { "Без имени" })
            append("» (#")
            append(personId)
            append(") за ")
            append(windowDays)
            append(" дн.")

            append("\n\nВзаимодействия (").append(recentInteractions.size).append("):")
            if (recentInteractions.isEmpty()) {
                append("\n- нет")
            } else {
                recentInteractions.forEach { append("\n- ").append(interactionLine(it, zone)) }
            }

            append("\n\nРасшифровки (").append(recentMoments.size).append("):")
            if (recentMoments.isEmpty()) {
                append("\n- нет")
            } else {
                recentMoments.forEach { append("\n- ").append(momentLine(it, zone)) }
            }

            append("\n\nВоспоминания (").append(recentMemories.size).append("):")
            if (recentMemories.isEmpty()) {
                append("\n- нет")
            } else {
                recentMemories.forEach { append("\n- ").append(cut(it.text, SNIPPET_CHARS)) }
            }

            append("\n\nМнения (").append(recentOpinions.size).append("):")
            if (recentOpinions.isEmpty()) {
                append("\n- нет")
            } else {
                recentOpinions.forEach { append("\n- ").append(cut(it.text, SNIPPET_CHARS)) }
            }

            append(
                "\n\nЧестно: показаны только метаданные, краткие фрагменты расшифровок и память; " +
                    "тела сообщений не хранятся. Период ограничен $windowDays дн."
            )
        }
        return if (text.length <= MAX_CHARS) text else text.take(MAX_CHARS - 1).trimEnd() + ELLIPSIS
    }

    private fun interactionLine(line: InteractionLine, zone: ZoneId): String = buildString {
        append(STAMP.format(Instant.ofEpochMilli(line.at).atZone(zone)))
        append(' ')
        append(line.channel.name)
        append(directionArrow(line.direction))
        val gist = line.summary?.let { cut(it, SNIPPET_CHARS) }.orEmpty()
        if (gist.isNotEmpty()) {
            append(' ')
            append(gist)
        }
    }

    private fun momentLine(moment: TranscriptMoment, zone: ZoneId): String = buildString {
        append(STAMP.format(Instant.ofEpochMilli(moment.at).atZone(zone)))
        if (moment.recordingLabel.isNotBlank()) {
            append(" «")
            append(moment.recordingLabel.trim())
            append('»')
        }
        append(" [")
        append(timecode(moment.startMs))
        append('-')
        append(timecode(moment.endMs))
        append("]: ")
        append(cut(moment.text, SNIPPET_CHARS))
    }

    private fun directionArrow(direction: InteractionDirection): String = when (direction) {
        InteractionDirection.INCOMING -> " ←"
        InteractionDirection.OUTGOING -> " →"
        InteractionDirection.MISSED -> " ✕"
        InteractionDirection.UNKNOWN -> ""
    }

    /** Compact `h:mm:ss` / `mm:ss` timecode; negative values clamp to `0`. */
    private fun timecode(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0L)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        }
    }

    private fun inWindow(at: Long, cutoff: Long): Boolean = at >= cutoff

    /** Collapses whitespace, trims and cuts [raw] to [max] characters with an in-budget ellipsis. */
    private fun cut(raw: String, max: Int): String {
        val collapsed = collapseWhitespace(raw)
        if (max <= 0) return ""
        if (collapsed.length <= max) return collapsed
        if (max == 1) return ELLIPSIS.toString()
        return collapsed.take(max - 1).trimEnd() + ELLIPSIS
    }
}
