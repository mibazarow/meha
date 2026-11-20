package com.andharness.ru.domain.agent

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecallIntentTest {

    @Test
    fun recognisesQuestionsAboutTheUsersOwnPast() {
        listOf(
            "Помнишь, какой у меня день рождения?",
            "Что мы обсуждали про отпуск?",
            "Напомни, что мы решили по машине",
            "Где я живу?",
            "Какие у меня предпочтения по еде?",
        ).forEach { text ->
            val hint = RecallIntent.hintFor(text)
            assertNotNull("should be a recall question: $text", hint)
            assertTrue("hint must name memory_search: $text", hint!!.contains("memory_search"))
        }
    }

    @Test
    fun routesMessageQuestionsToTheSemanticMessageSearch() {
        listOf(
            "Кто писал про счёт?",
            "Что было в переписке с Сергеем?",
            "Найди сообщение про встречу",
            "When did she text me about the invoice?",
        ).forEach { text ->
            val hint = RecallIntent.hintFor(text)
            assertNotNull("should be a message question: $text", hint)
            assertTrue("hint must name message_search: $text", hint!!.contains("message_search"))
            assertTrue("hint must keep semantic search on: $text", hint!!.contains("semantic=true"))
        }
    }

    @Test
    fun reminderActionsAreNotRecallQuestions() {
        listOf(
            "Напомни поставить будильник на 7 утра",
            "Поставь таймер на 10 минут",
            "Напомни завтра позвонить маме",
            "Remind me to call mom tomorrow",
            "Set an alarm for 6",
        ).forEach { text ->
            assertNull("not a recall question: $text", RecallIntent.hintFor(text))
        }
    }

    @Test
    fun ordinaryQuestionsDoNotTriggerARecallHint() {
        listOf(
            "Сколько будет 2+2?",
            "Включи фонарик",
            "Какая сегодня погода?",
            "Напиши функцию на Kotlin",
            "",
        ).forEach { text ->
            assertNull("must not trigger: $text", RecallIntent.hintFor(text))
        }
    }
}
