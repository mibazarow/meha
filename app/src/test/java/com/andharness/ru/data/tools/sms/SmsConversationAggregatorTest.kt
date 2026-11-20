package com.andharness.ru.data.tools.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [aggregateSmsConversations]. Pure Kotlin only, no Android runtime.
 * Reference: https://developer.android.com/reference/android/provider/Telephony.Sms
 */
class SmsConversationAggregatorTest {

    private fun row(threadId: Long, date: Long, body: String) =
        SmsConversationRow(threadId = threadId, date = date, body = body)

    @Test
    fun groupsRowsByThreadAndUsesNewestAsSnippet() {
        val rows = listOf(
            row(7, 300, "latest"),
            row(7, 200, "older"),
            row(3, 100, "hello"),
        )
        assertEquals(
            listOf(
                "thread 7 | сообщений: 2 | latest",
                "thread 3 | сообщений: 1 | hello",
            ),
            aggregateSmsConversations(rows, limit = 50),
        )
    }

    @Test
    fun keepsFirstSeenOrderAndHonoursLimit() {
        val rows = listOf(
            row(1, 400, "a"),
            row(2, 300, "b"),
            row(3, 200, "c"),
        )
        assertEquals(
            listOf(
                "thread 1 | сообщений: 1 | a",
                "thread 2 | сообщений: 1 | b",
            ),
            aggregateSmsConversations(rows, limit = 2),
        )
    }

    @Test
    fun emptyRowsYieldNoLines() {
        assertTrue(aggregateSmsConversations(emptyList(), limit = 50).isEmpty())
    }

    @Test
    fun emptyBodyIsPreservedAsEmptySnippet() {
        assertEquals(
            listOf("thread 5 | сообщений: 1 | "),
            aggregateSmsConversations(listOf(row(5, 10, "")), limit = 50),
        )
    }
}
