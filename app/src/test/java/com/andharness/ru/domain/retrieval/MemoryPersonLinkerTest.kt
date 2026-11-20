package com.andharness.ru.domain.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MemoryPersonLinker]: memories must be linked to the persons they mention, with token-boundary
 * matching (never substring), deterministic ordering and bounded output.
 */
class MemoryPersonLinkerTest {

    private fun candidate(id: Long, vararg names: String) =
        PersonNameCandidate(personId = id, names = names.toList())

    @Test
    fun singleFirstNameMatch() {
        val links = MemoryPersonLinker.link(
            memoryText = "Созвониться с Серёгой завтра",
            candidates = listOf(candidate(1L, "Серёга")),
        )

        assertEquals(1, links.size)
        assertEquals(1L, links[0].personId)
        assertTrue("a matched single name must carry a positive weight", links[0].weight > 0f)
    }

    @Test
    fun fullNameIsStrongerThanSingleToken() {
        val links = MemoryPersonLinker.link(
            memoryText = "Сергей Петрович звонил",
            candidates = listOf(candidate(1L, "Сергей Петрович")),
        )

        assertEquals(1, links.size)
        assertEquals(1.0f, links[0].weight, 0.0001f)
    }

    @Test
    fun transliterationMatches() {
        val links = MemoryPersonLinker.link(
            memoryText = "Serega звонил",
            candidates = listOf(candidate(1L, "Серёга")),
        )

        assertEquals(1, links.size)
        assertEquals(1L, links[0].personId)
    }

    @Test
    fun tokenBoundaryIsExactNotSubstring() {
        val links = MemoryPersonLinker.link(
            memoryText = "поехал в Иваново",
            candidates = listOf(candidate(1L, "Иван")),
        )

        assertTrue("«иван» must not match inside «иваново»", links.isEmpty())
    }

    @Test
    fun tooShortNameIsIgnored() {
        val links = MemoryPersonLinker.link(
            memoryText = "Ян пришёл",
            candidates = listOf(candidate(1L, "Ян")),
        )

        assertTrue("a name below MIN_NAME_CHARS must never link", links.isEmpty())
    }

    @Test
    fun singleShortTokenHasBaseWeight() {
        val links = MemoryPersonLinker.link(
            memoryText = "Анна пришла",
            candidates = listOf(candidate(1L, "Анна")),
        )

        assertEquals(1, links.size)
        assertEquals(0.6f, links[0].weight, 0.0001f)
    }

    @Test
    fun deduplicatesPersonAndKeepsMaxWeight() {
        val links = MemoryPersonLinker.link(
            memoryText = "Serega и Сергей Петрович",
            candidates = listOf(candidate(42L, "Серёга", "Сергей Петрович")),
        )

        assertEquals("one link per person id", 1, links.size)
        assertEquals(42L, links[0].personId)
        assertEquals("the strongest name wins", 1.0f, links[0].weight, 0.0001f)
    }

    @Test
    fun blankTextOrEmptyCandidatesYieldNothing() {
        assertTrue(MemoryPersonLinker.link("", listOf(candidate(1L, "Серёга"))).isEmpty())
        assertTrue(MemoryPersonLinker.link("   ", listOf(candidate(1L, "Серёга"))).isEmpty())
        assertTrue(MemoryPersonLinker.link("Серёга звонил", emptyList()).isEmpty())
    }

    @Test
    fun ordersByWeightDescending() {
        val links = MemoryPersonLinker.link(
            memoryText = "Сергей Петрович и Сергей",
            candidates = listOf(
                candidate(7L, "Сергей"),
                candidate(3L, "Сергей Петрович"),
            ),
        )

        assertEquals(2, links.size)
        assertEquals(3L, links[0].personId)
        assertEquals(1.0f, links[0].weight, 0.0001f)
        assertEquals(7L, links[1].personId)
        assertEquals(0.8f, links[1].weight, 0.0001f)
    }

    @Test
    fun tiesAreBrokenByPersonIdAscending() {
        val links = MemoryPersonLinker.link(
            memoryText = "Сергей позвонил",
            candidates = listOf(
                candidate(5L, "Сергей"),
                candidate(2L, "Сергей"),
            ),
        )

        assertEquals(2, links.size)
        assertEquals(2L, links[0].personId)
        assertEquals(5L, links[1].personId)
    }

    @Test
    fun capsAtMaxLinks() {
        val candidates = (1L..(MemoryPersonLinker.MAX_LINKS + 5).toLong()).map { candidate(it, "Сергей") }
        val links = MemoryPersonLinker.link("Сергей звонил", candidates)

        assertEquals(MemoryPersonLinker.MAX_LINKS, links.size)
    }
}
