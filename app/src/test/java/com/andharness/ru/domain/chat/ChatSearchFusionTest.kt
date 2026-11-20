package com.andharness.ru.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage of [ChatSearchFusion]: empty input, title-only, semantic-only, the fused
 * ordering (title > semantic > lexical), per-chat dedup (best message wins), the
 * lexical-vs-semantic max for one message, in-chat message ranking and the bound.
 */
class ChatSearchFusionTest {

    private fun candidate(
        id: Long,
        chatId: Long = id,
        title: String = "Чат $chatId",
        text: String = "сообщение $id",
        at: Long = id,
    ) = ChatMessageCandidate(messageId = id, chatId = chatId, chatTitle = title, text = text, at = at)

    @Test
    fun emptyInputsYieldNothing() {
        assertTrue(
            ChatSearchFusion.fuseChats("", emptyList(), emptyList(), emptyList(), emptyMap(), 10)
                .isEmpty()
        )
        assertTrue(
            ChatSearchFusion.fuseChats("меха", emptyList(), emptyList(), emptyList(), emptyMap(), 10)
                .isEmpty()
        )
        assertTrue(
            ChatSearchFusion.fuseChats("меха", emptyList(), emptyList(), emptyList(), emptyMap(), 0)
                .isEmpty()
        )
    }

    @Test
    fun titleOnlyMatchReturnsChatWithEmptySnippet() {
        val results = ChatSearchFusion.fuseChats(
            query = "меха",
            titleMatches = listOf(ChatTitleMatch(10L, "Проект Меха")),
            lexicalIds = emptyList(),
            semanticHits = emptyList(),
            candidates = emptyMap(),
            limit = 10,
        )

        val result = results.single()
        assertEquals(10L, result.chatId)
        assertEquals("Проект Меха", result.title)
        assertTrue(result.matchedByTitle)
        assertEquals("", result.snippet)
        assertEquals(ChatSearchFusion.TITLE_SCORE, result.score, 1e-9)
    }

    @Test
    fun semanticOnlyMatchUsesHeadExcerptAndCosineScore() {
        val text = "Давай встретимся завтра в полдень у входа"
        val results = ChatSearchFusion.fuseChats(
            query = "когда встречаемся", // no literal occurrence -> semantic-only
            titleMatches = emptyList(),
            lexicalIds = emptyList(),
            semanticHits = listOf(5L to 0.82f),
            candidates = mapOf(5L to candidate(5L, chatId = 1L, title = "План", text = text)),
            limit = 10,
        )

        val result = results.single()
        assertEquals(1L, result.chatId)
        assertEquals("План", result.title)
        assertFalse(result.matchedByTitle)
        assertEquals(text, result.snippet) // short text: whole (head) excerpt
        assertEquals(0.82, result.score, 1e-6)
    }

    @Test
    fun fusionOrdersTitleThenSemanticThenLexical() {
        val candidates = mapOf(
            2L to candidate(2L, chatId = 2L, title = "Планы"),
            3L to candidate(3L, chatId = 3L, title = "Заметки"),
        )
        val results = ChatSearchFusion.fuseChats(
            query = "меха",
            titleMatches = listOf(ChatTitleMatch(1L, "Меха")),
            lexicalIds = listOf(3L),
            semanticHits = listOf(2L to 0.9f),
            candidates = candidates,
            limit = 10,
        )

        assertEquals(listOf(1L, 2L, 3L), results.map { it.chatId })
        assertTrue(results[0].matchedByTitle)
        assertFalse(results[1].matchedByTitle)
        assertEquals(ChatSearchFusion.LEXICAL_SCORE, results[2].score, 1e-9)
    }

    @Test
    fun messageScoreIsMaxOfLexicalFloorAndSemanticCosine() {
        // A weak semantic hit (0.3) on a lexically-matched message is floored to LEXICAL_SCORE.
        val results = ChatSearchFusion.fuseChats(
            query = "привет",
            titleMatches = emptyList(),
            lexicalIds = listOf(1L),
            semanticHits = listOf(1L to 0.3f),
            candidates = mapOf(1L to candidate(1L, chatId = 7L)),
            limit = 10,
        )

        assertEquals(ChatSearchFusion.LEXICAL_SCORE, results.single().score, 1e-9)
    }

    @Test
    fun dedupKeepsBestMessagePerChat() {
        val candidates = mapOf(
            1L to candidate(1L, chatId = 9L, title = "Один чат", at = 10L),
            2L to candidate(2L, chatId = 9L, title = "Один чат", at = 20L),
        )
        val results = ChatSearchFusion.fuseChats(
            query = "тема",
            titleMatches = emptyList(),
            lexicalIds = emptyList(),
            semanticHits = listOf(1L to 0.6f, 2L to 0.95f),
            candidates = candidates,
            limit = 10,
        )

        val result = results.single()
        assertEquals(9L, result.chatId)
        assertEquals(0.95, result.score, 1e-6)
        assertEquals("сообщение 2", result.snippet)
    }

    @Test
    fun limitBoundsTheChatsAndTiesBreakByRecencyThenId() {
        val candidates = mapOf(
            1L to candidate(1L, chatId = 1L, at = 10L),
            2L to candidate(2L, chatId = 2L, at = 20L),
            3L to candidate(3L, chatId = 3L, at = 20L),
        )
        val results = ChatSearchFusion.fuseChats(
            query = "меха",
            titleMatches = emptyList(),
            lexicalIds = listOf(1L, 2L, 3L),
            semanticHits = emptyList(),
            candidates = candidates,
            limit = 2,
        )

        // All share LEXICAL_SCORE: id 3 and 2 tie on recency (20) and 3 wins the id tie-break.
        assertEquals(listOf(3L, 2L), results.map { it.chatId })
    }

    @Test
    fun candidateWithoutAnyMatchSignalIsIgnored() {
        val results = ChatSearchFusion.fuseChats(
            query = "меха",
            titleMatches = emptyList(),
            lexicalIds = emptyList(),
            semanticHits = listOf(1L to 0.9f),
            candidates = mapOf(
                1L to candidate(1L, chatId = 1L),
                99L to candidate(99L, chatId = 99L), // in the map but matched by nothing
            ),
            limit = 10,
        )

        assertEquals(listOf(1L), results.map { it.chatId })
    }

    @Test
    fun rankMessagesDedupsOrdersAndBounds() {
        val candidates = mapOf(
            1L to candidate(1L, chatId = 5L, at = 10L, text = "привет мир"),
            2L to candidate(2L, chatId = 5L, at = 20L, text = "другое"),
            3L to candidate(3L, chatId = 5L, at = 30L, text = "третье"),
        )
        val ranked = ChatSearchFusion.rankMessages(
            query = "привет",
            lexicalIds = listOf(1L, 3L),
            semanticHits = listOf(2L to 0.9f),
            candidates = candidates,
            limit = 5,
        )

        assertEquals(listOf(2L, 3L, 1L), ranked.map { it.messageId })
        assertEquals(0.9, ranked[0].score, 1e-6)
        assertEquals(20L, ranked[0].timestamp)
        assertTrue(ranked[1].snippet.contains("третье"))

        assertEquals(
            2,
            ChatSearchFusion.rankMessages("x", listOf(1L, 2L, 3L), emptyList(), candidates, 2).size
        )
        assertTrue(
            ChatSearchFusion.rankMessages("x", listOf(1L), emptyList(), candidates, 0).isEmpty()
        )
    }
}
