package com.andharness.ru.domain.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BUGS.md M1: junk never becomes a memory. Speech input and two-word replies produce far more
 * fragments than typing, and a junk fact in `notes` is injected into every later prompt.
 */
class MemoryJunkFilterTest {

    @Test
    fun acknowledgementsAndFillersAreRejected() {
        listOf(
            "ок", "Ок!", "ок…", "ага", "угу", "спасибо", "спс", "лол", "ахаха",
            "привет", "да", "нет", "понял", "хм", "мм", "ok", "thanks", "hello", "lol",
            "аааа", "ну-ну"
        ).forEach {
            assertTrue("«$it» is a filler, not a fact", MemoryJunkFilter.isLowValue(it))
            assertFalse("«$it» must not be stored", MemoryJunkFilter.shouldStore(it))
        }
    }

    @Test
    fun aRealFactThatStartsWithAnAcknowledgementSurvives() {
        val text = "ок, тогда встречаемся в четверг в офисе"
        assertFalse(MemoryJunkFilter.isLowValue(text))
        assertTrue(MemoryJunkFilter.shouldStore(text))
    }

    @Test
    fun narrationAboutTheConversationIsRejected() {
        listOf(
            "Говорящий сказал, что любит кофе",
            "Упомянуто, что встреча в четверг",
            "Автор сообщения живёт в Москве",
            "Участник чата написал про отпуск",
            "Написал сообщение о поездке в Сочи",
            "Встреча в четверг, упомянутый говорящим"
        ).forEach {
            assertTrue("«$it» narrates the conversation", MemoryJunkFilter.isJunkFact(it))
            assertFalse("«$it» must not be stored", MemoryJunkFilter.shouldStore(it))
        }
    }

    @Test
    fun aFactContainingTheWordGovorilLaterIsNotNarration() {
        val text = "Договорились с Аней про отпуск, она говорила про Сочи"
        assertFalse(MemoryJunkFilter.isJunkFact(text))
        assertTrue(MemoryJunkFilter.shouldStore(text))
    }

    @Test
    fun tooShortTextIsRejected() {
        assertTrue(MemoryJunkFilter.isJunkFact("код 47"))
        assertFalse(MemoryJunkFilter.isJunkFact("код домофона 4721"))
    }

    @Test
    fun blankAndPunctuationOnlyAreRejected() {
        listOf("", "   ", "…", "?!", "---").forEach {
            assertFalse("«$it» must not be stored", MemoryJunkFilter.shouldStore(it))
        }
    }

    @Test
    fun ordinaryFactsAreStored() {
        listOf(
            "Любит крепкий чёрный чай без сахара",
            "Договорились с Мариной про аренду офиса на сентябрь",
            "Аллергия на пенициллин",
            "Код домофона 4721"
        ).forEach {
            assertTrue("«$it» is a real fact", MemoryJunkFilter.shouldStore(it))
        }
    }
}
