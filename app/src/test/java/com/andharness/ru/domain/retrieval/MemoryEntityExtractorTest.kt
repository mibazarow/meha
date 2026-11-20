package com.andharness.ru.domain.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MemoryEntityExtractor]: tags become explicit topics, mid-sentence capitalized runs become
 * proper-noun entities, sentence-initial noise is dropped, duplicates collapse and output is capped.
 */
class MemoryEntityExtractorTest {

    @Test
    fun tagsBecomeTopicsWithFullWeight() {
        val entities = MemoryEntityExtractor.extract(
            text = "Обсудили смету",
            tags = "налоговая, фнс"
        )

        val topics = entities.filter { it.kind == MemoryEntityExtractor.KIND_TOPIC }
        assertEquals(setOf("налоговая", "фнс"), topics.map { it.normalizedName }.toSet())
        assertTrue(topics.all { it.weight == MemoryEntityExtractor.WEIGHT_TAG })
    }

    @Test
    fun multiWordProperNounInMidSentenceIsExtracted() {
        val entities = MemoryEntityExtractor.extract(
            text = "Вчера мы запустили Годот Энджин в продакшн",
            tags = ""
        )

        val proper = entities.single()
        assertEquals("Годот Энджин", proper.name)
        assertEquals(MemoryEntityExtractor.KIND_OTHER, proper.kind)
        assertEquals(MemoryEntityExtractor.WEIGHT_PROPER_NOUN, proper.weight, 0.0001f)
    }

    @Test
    fun sentenceInitialCapitalizedRunIsNotExtracted() {
        val entities = MemoryEntityExtractor.extract(
            text = "Годот Энджин был запущен вчера. Потом мы отдохнули.",
            tags = ""
        )

        assertTrue("sentence-initial casing must not become an entity", entities.isEmpty())
    }

    @Test
    fun singleCapitalizedWordIsNotExtracted() {
        val entities = MemoryEntityExtractor.extract(
            text = "Мы видели Париж прошлым летом",
            tags = ""
        )

        assertTrue("a lone capitalized word is not a multi-word proper noun", entities.isEmpty())
    }

    @Test
    fun duplicateProperNounRunIsCollapsed() {
        val entities = MemoryEntityExtractor.extract(
            text = "Снова Годот Энджин и опять Годот Энджин",
            tags = ""
        )

        assertEquals("repeated runs must collapse onto one entity", 1, entities.size)
        assertEquals("Годот Энджин", entities.single().name)
    }

    @Test
    fun duplicateTagIsCollapsed() {
        val entities = MemoryEntityExtractor.extract(text = "", tags = "фнс, фнс")

        assertEquals(1, entities.size)
        assertEquals("фнс", entities.single().normalizedName)
    }

    @Test
    fun outputIsCapped() {
        val tags = (1..30).joinToString(", ") { "тема$it" }
        val entities = MemoryEntityExtractor.extract(text = "", tags = tags)

        assertEquals(MemoryEntityExtractor.MAX_ENTITIES, entities.size)
    }

    @Test
    fun blankInputsYieldNothing() {
        assertTrue(MemoryEntityExtractor.extract("", "").isEmpty())
        assertTrue(MemoryEntityExtractor.extract("   ", " , ").isEmpty())
    }
}
