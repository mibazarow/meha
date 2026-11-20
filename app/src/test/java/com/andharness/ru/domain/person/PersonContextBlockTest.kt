package com.andharness.ru.domain.person

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the always-present personal-base context block (update.md §4.6): the caps
 * and the ranking are the whole point, so they are asserted exactly.
 */
class PersonContextBlockTest {

    private val zone = ZoneId.of("UTC")
    private val base = Instant.parse("2026-01-15T10:00:00Z").toEpochMilli()

    private fun person(id: Long, name: String) = Person(
        id = id,
        displayName = name,
        notes = "",
        createdAt = base,
        updatedAt = base
    )

    private fun interaction(
        personId: Long,
        at: Long,
        channel: InteractionChannel = InteractionChannel.SMS,
        direction: InteractionDirection = InteractionDirection.INCOMING,
        summary: String? = "привет"
    ) = Interaction(
        id = 0,
        personId = personId,
        channel = channel,
        direction = direction,
        at = at,
        title = null,
        summary = summary,
        externalRef = null,
        sourceApp = null
    )

    @Test
    fun emptyBaseRendersNothing() {
        assertEquals("", PersonContextBlock.render(emptyList(), zone))
    }

    @Test
    fun rendersHeaderInstructionAndGist() {
        val block = PersonContextBlock.render(
            listOf(
                PersonContextBlock.Entry(
                    person(1, "Серёга"),
                    listOf(interaction(1, base, summary = "как дела"))
                )
            ),
            zone
        )
        assertTrue(block.startsWith(PersonContextBlock.HEADER))
        assertTrue(block.contains(PersonContextBlock.INSTRUCTION))
        assertTrue(block.contains("#1 Серёга"))
        assertTrue(block.contains("SMS←"))
        assertTrue(block.contains("как дела"))
    }

    @Test
    fun newestPersonComesFirst() {
        val old = PersonContextBlock.Entry(person(1, "Старый"), listOf(interaction(1, base - 100_000)))
        val fresh = PersonContextBlock.Entry(person(2, "Свежий"), listOf(interaction(2, base)))
        val block = PersonContextBlock.render(listOf(old, fresh), zone)
        assertTrue(
            "the person with the newest interaction must be listed first",
            block.indexOf("#2 Свежий") < block.indexOf("#1 Старый")
        )
    }

    @Test
    fun atMostThreeInteractionsPerPerson() {
        val interactions = (0 until 6).map { interaction(1, base - it * 60_000L, summary = "m$it") }
        val block = PersonContextBlock.render(
            listOf(PersonContextBlock.Entry(person(1, "Много"), interactions)),
            zone
        )
        val fragments = block.lineSequence().filter { it.startsWith("- #1 ") }.toList().first()
        // m0..m2 (newest three) only; the fourth newest must be absent.
        assertTrue(fragments.contains("m0"))
        assertTrue(fragments.contains("m2"))
        assertFalse("only 3 interactions may be shown", fragments.contains("m3"))
    }

    @Test
    fun atMostTenPersons() {
        val entries = (1L..15L).map { id ->
            PersonContextBlock.Entry(person(id, "P$id"), listOf(interaction(id, base - id)))
        }
        val block = PersonContextBlock.render(entries, zone)
        val lines = block.lineSequence().count { it.startsWith("- #") }
        assertEquals(PersonContextBlock.MAX_PERSONS, lines)
    }

    @Test
    fun neverExceedsTheCharacterCap() {
        val huge = "x".repeat(500)
        val entries = (1L..30L).map { id ->
            PersonContextBlock.Entry(
                person(id, "Очень длинное имя номер $id"),
                (0 until 3).map { interaction(id, base - it, summary = huge) }
            )
        }
        val block = PersonContextBlock.render(entries, zone)
        assertTrue(
            "block must stay within ${PersonContextBlock.MAX_CHARS} chars, was ${block.length}",
            block.length <= PersonContextBlock.MAX_CHARS
        )
        assertTrue("a cut block should end with an ellipsis", block.endsWith("…"))
    }

    @Test
    fun gistIsBoundedToEightyCharacters() {
        val block = PersonContextBlock.render(
            listOf(
                PersonContextBlock.Entry(
                    person(1, "Имя"),
                    listOf(interaction(1, base, summary = "y".repeat(200)))
                )
            ),
            zone
        )
        val gist = block.substringAfter("SMS← ").trim()
        assertTrue("gist must be cut to <= 80 chars", gist.length <= PersonContextBlock.GIST_CHARS)
        assertTrue(gist.endsWith("…"))
    }
}
