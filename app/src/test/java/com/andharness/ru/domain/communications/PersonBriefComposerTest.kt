package com.andharness.ru.domain.communications

import com.andharness.ru.domain.person.InteractionChannel
import com.andharness.ru.domain.person.InteractionDirection
import java.time.ZoneId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage of [PersonBriefComposer]: window filtering, newest-first ordering, per-section
 * and whole-text caps, timecode rendering and the honest all-empty brief.
 */
class PersonBriefComposerTest {

    private val zone = ZoneId.of("UTC")
    private val now = 1_700_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

    private fun interaction(
        at: Long,
        summary: String? = null,
        channel: InteractionChannel = InteractionChannel.SMS,
        direction: InteractionDirection = InteractionDirection.INCOMING,
    ) = PersonBriefComposer.InteractionLine(at, channel, direction, summary)

    @Test
    fun filtersOutItemsOlderThanTheWindow() {
        val brief = PersonBriefComposer.render(
            personName = "Иван",
            personId = 7,
            days = 30,
            now = now,
            interactions = listOf(
                interaction(at = now - 2 * day, summary = "свежее"),
                interaction(at = now - 100 * day, summary = "старое"),
            ),
            moments = emptyList(),
            memories = emptyList(),
            opinions = emptyList(),
            zone = zone,
        )

        assertTrue("the in-window interaction must be shown", brief.contains("свежее"))
        assertFalse("the out-of-window interaction must be dropped", brief.contains("старое"))
        assertTrue(brief.contains("Взаимодействия (1):"))
        assertTrue(brief.contains("за 30 дн."))
    }

    @Test
    fun showsNewestFirstAndCapsTheInteractions() {
        val interactions = (1..15).map { index ->
            interaction(at = now - index * 1_000L, summary = "m$index")
        }
        val brief = PersonBriefComposer.render(
            personName = "Иван",
            personId = 7,
            days = 30,
            now = now,
            interactions = interactions,
            moments = emptyList(),
            memories = emptyList(),
            opinions = emptyList(),
            zone = zone,
        )

        assertTrue("the newest interaction must be shown", brief.contains("m1"))
        assertTrue(
            "only MAX_INTERACTIONS items may be shown",
            brief.contains("m${PersonBriefComposer.MAX_INTERACTIONS}")
        )
        assertFalse(
            "items beyond the cap must be dropped",
            brief.contains("m${PersonBriefComposer.MAX_INTERACTIONS + 1}")
        )
    }

    @Test
    fun rendersTranscriptMomentWithTimecode() {
        val brief = PersonBriefComposer.render(
            personName = "Иван",
            personId = 7,
            days = 30,
            now = now,
            interactions = emptyList(),
            moments = listOf(
                PersonBriefComposer.TranscriptMoment(
                    at = now,
                    recordingLabel = "Запись",
                    startMs = 125_000L,
                    endMs = 130_000L,
                    text = "привет",
                )
            ),
            memories = emptyList(),
            opinions = emptyList(),
            zone = zone,
        )

        assertTrue(brief.contains("«Запись»"))
        assertTrue(brief.contains("[02:05-02:10]"))
        assertTrue(brief.contains("привет"))
    }

    @Test
    fun allEmptyInputStillProducesAnHonestBrief() {
        val brief = PersonBriefComposer.render(
            personName = "Иван",
            personId = 7,
            days = 30,
            now = now,
            interactions = emptyList(),
            moments = emptyList(),
            memories = emptyList(),
            opinions = emptyList(),
            zone = zone,
        )

        assertTrue(brief.contains("«Иван» (#7)"))
        assertTrue(brief.contains("Взаимодействия (0):"))
        assertTrue(brief.contains("Расшифровки (0):"))
        assertTrue(brief.contains("Воспоминания (0):"))
        assertTrue(brief.contains("Мнения (0):"))
        assertTrue(brief.contains("Честно:"))
    }

    @Test
    fun collapsesAndCutsLongSnippets() {
        val long = "x".repeat(500)
        val brief = PersonBriefComposer.render(
            personName = "Иван",
            personId = 7,
            days = 30,
            now = now,
            interactions = listOf(interaction(at = now, summary = long)),
            moments = emptyList(),
            memories = emptyList(),
            opinions = emptyList(),
            zone = zone,
        )

        assertFalse("the raw long summary must not pass through", brief.contains("x".repeat(200)))
        assertTrue("the cut snippet must be marked", brief.contains("…"))
    }

    @Test
    fun capsTheWholeBriefAtMaxChars() {
        val chunk = "y".repeat(PersonBriefComposer.SNIPPET_CHARS)
        val brief = PersonBriefComposer.render(
            personName = "Иван",
            personId = 7,
            days = 30,
            now = now,
            interactions = emptyList(),
            moments = emptyList(),
            memories = (1..PersonBriefComposer.MAX_MEMORIES).map {
                PersonBriefComposer.DatedText(now, chunk)
            },
            opinions = (1..PersonBriefComposer.MAX_OPINIONS).map {
                PersonBriefComposer.DatedText(now, chunk)
            },
            zone = zone,
        )

        assertTrue(brief.length <= PersonBriefComposer.MAX_CHARS)
        assertTrue("a truncated brief must be marked", brief.endsWith("…"))
    }
}
