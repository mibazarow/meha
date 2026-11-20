package com.andharness.ru.data.agent

import com.andharness.ru.data.style.StyleProfileStore
import com.andharness.ru.domain.style.ProfanityLexicon
import com.andharness.ru.domain.style.StyleAnalyzer
import java.time.Instant
import java.time.ZoneId

/**
 * Feeds one user-authored message into the style store.
 *
 * Only the user's own message is observed (never assistant text, tool results or external text), and
 * the profanity count is style-eligible only when the message is not hostile: an insult suppresses
 * the mild count, so an argument never trains the assistant to swear.
 *
 * Kept out of the orchestrator so the day/timezone math and the lexicon wiring are JVM-testable.
 * References:
 * - `Instant.atZone` / `LocalDate.toEpochDay`:
 *   https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/Instant.html#atZone(java.time.ZoneId)
 * - `LocalDate.ofEpochDay`:
 *   https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/LocalDate.html#ofEpochDay(long)
 */
internal object StyleObserver {

    /**
     * Observes [text] at [now] in [zone]. A null store or blank text is a no-op. Never throws:
     * style learning must never break a chat turn.
     */
    fun observe(
        store: StyleProfileStore?,
        text: String,
        now: Long,
        zone: ZoneId,
    ) {
        if (store == null || text.isBlank()) return
        runCatching {
            val day = epochDay(now, zone)
            val signal = StyleAnalyzer.analyze(
                text = text,
                profanityHits = ProfanityLexicon.count(text),
            )
            store.observe(signal, now, day)
        }
    }

    /** Epoch day of [now] in [zone], used by the reducer's cross-day recurrence gates. */
    internal fun epochDay(now: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()
}
