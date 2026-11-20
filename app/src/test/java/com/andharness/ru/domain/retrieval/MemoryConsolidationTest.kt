package com.andharness.ru.domain.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `memory_save` consolidation. A restatement or a negation of a stored fact
 * must UPDATE that row (new text, unioned tags, max importance, touched lastUsedAt) instead of
 * inserting a duplicate; anything unrelated must still insert.
 */
class MemoryConsolidationTest {

    private val now = 1_700_000_000_000L

    private fun memory(
        id: Long = 1L,
        text: String,
        tags: String = "",
        importance: Int = 0
    ) = MemoryRecord(id = id, text = text, tags = tags, importance = importance)

    @Test
    fun exactRestatementUpdatesInsteadOfInserting() {
        val plan = MemoryConsolidation.decide(
            existing = listOf(memory(id = 7L, text = "любит кофе пользователь", tags = "кофе")),
            text = "пользователь любит кофе",
            tags = "напиток",
            importance = 0,
            now = now
        )

        assertTrue(plan is MemorySavePlan.Update)
        plan as MemorySavePlan.Update
        assertEquals(7L, plan.id)
        assertEquals("the NEW text is preferred", "пользователь любит кофе", plan.text)
        assertEquals(now, plan.lastUsedAt)
    }

    @Test
    fun negatedRestatementIsConsolidatedAsContradiction() {
        // "не" is a stop-word, so the two token bags are identical and the Jaccard overlap is 1.0.
        val plan = MemoryConsolidation.decide(
            existing = listOf(memory(text = "Пользователь любит кофе")),
            text = "Пользователь не любит кофе",
            tags = "",
            importance = 0,
            now = now
        )

        assertTrue("a contradiction must update the stored fact", plan is MemorySavePlan.Update)
        assertEquals("Пользователь не любит кофе", (plan as MemorySavePlan.Update).text)
    }

    @Test
    fun mergePrefersNewTextUnionsTagsTakesMaxImportanceAndTouches() {
        val plan = MemoryConsolidation.decide(
            existing = listOf(
                memory(
                    id = 3L,
                    text = "Пользователь любит бирюзовый цвет",
                    tags = "цвет, предпочтение",
                    importance = 1
                )
            ),
            text = "Пользователь любит бирюзовый цвет",
            tags = "любимый, цвет",
            importance = 2,
            now = now
        )

        plan as MemorySavePlan.Update
        assertEquals(3L, plan.id)
        assertEquals("Пользователь любит бирюзовый цвет", plan.text)
        assertEquals("tags are unioned, old first, de-duplicated", "цвет, предпочтение, любимый", plan.tags)
        assertEquals("importance is the maximum", 2, plan.importance)
        assertEquals(now, plan.lastUsedAt)
    }

    @Test
    fun importanceNeverDropsOnMerge() {
        val plan = MemoryConsolidation.decide(
            existing = listOf(memory(id = 4L, text = "любит кофе пользователь", importance = 2)),
            text = "пользователь любит кофе",
            tags = "",
            importance = 0,
            now = now
        )

        assertEquals(2, (plan as MemorySavePlan.Update).importance)
    }

    @Test
    fun unrelatedTextInserts() {
        val plan = MemoryConsolidation.decide(
            existing = listOf(memory(text = "Рабочая встреча с ФНС по декларации", tags = "налоговая")),
            text = "Пользователь любит бирюзовый цвет",
            tags = "цвет",
            importance = 0,
            now = now
        )

        assertTrue("unrelated memories must not be merged", plan is MemorySavePlan.Insert)
        assertEquals("Пользователь любит бирюзовый цвет", (plan as MemorySavePlan.Insert).text)
    }

    @Test
    fun emptyExistingInsertsAndClampsImportance() {
        val plan = MemoryConsolidation.decide(
            existing = emptyList(),
            text = "  факт  ",
            tags = " тег ",
            importance = 9,
            now = now
        )

        plan as MemorySavePlan.Insert
        assertEquals("факт", plan.text)
        assertEquals("тег", plan.tags)
        assertEquals(2, plan.importance)
    }

    @Test
    fun jaccardIsOneForIdenticalAndZeroForDisjoint() {
        assertEquals(1.0, MemoryConsolidation.jaccard(setOf("a", "b"), setOf("a", "b")), 1e-9)
        assertEquals(0.0, MemoryConsolidation.jaccard(setOf("a"), setOf("b")), 1e-9)
        assertEquals(0.0, MemoryConsolidation.jaccard(emptySet(), setOf("a")), 1e-9)
        assertEquals(1.0 / 3.0, MemoryConsolidation.jaccard(setOf("a", "b"), setOf("b", "c")), 1e-9)
    }

    @Test
    fun mergeTagsIsCaseInsensitiveUnionPreservingOrder() {
        assertEquals(
            "цвет, предпочтение, любимый",
            MemoryConsolidation.mergeTags("цвет, предпочтение", "любимый, ЦВЕТ")
        )
        assertEquals("кофе", MemoryConsolidation.mergeTags("", "  кофе  "))
        assertEquals("", MemoryConsolidation.mergeTags("", ""))
    }
}
