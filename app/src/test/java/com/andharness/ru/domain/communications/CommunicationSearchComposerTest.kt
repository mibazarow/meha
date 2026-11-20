package com.andharness.ru.domain.communications

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage of [CommunicationSearchComposer]: whitespace normalization, snippet cutting,
 * newest-first merge, bounding and the honest empty render.
 */
class CommunicationSearchComposerTest {

    private val zone = ZoneId.of("UTC")

    private fun item(
        at: Long,
        text: String,
        channel: String = "SMS",
        title: String = "",
        ref: String? = null,
        rank: Double = 0.0,
    ) = CommunicationSearchComposer.Item(
        at = at,
        channel = channel,
        title = title,
        text = text,
        ref = ref,
        rank = rank,
    )

    @Test
    fun normalizeCollapsesWhitespace() {
        assertEquals("привет мир", CommunicationSearchComposer.normalize("  привет\n\n  мир  "))
    }

    @Test
    fun normalizeCutsLongTextWithAnEllipsis() {
        val cut = CommunicationSearchComposer.normalize("a".repeat(500))

        assertEquals(CommunicationSearchComposer.SNIPPET_CHARS, cut.length)
        assertTrue(cut.endsWith("…"))
    }

    @Test
    fun mergeSortsNewestFirstAndCaps() {
        val merged = CommunicationSearchComposer.merge(
            listOf(
                item(at = 100L, text = "старое"),
                item(at = 300L, text = "новое"),
                item(at = 200L, text = "среднее"),
            ),
            limit = 2,
        )

        assertEquals(listOf("новое", "среднее"), merged.map { it.text })
    }

    @Test
    fun mergeRanksByRelevanceThenRecency() {
        val merged = CommunicationSearchComposer.merge(
            listOf(
                item(at = 100L, text = "lexical", ref = "r1", rank = 1.0),
                item(at = 300L, text = "unranked", ref = "r2", rank = 0.0),
                item(at = 200L, text = "semantic", ref = "r3", rank = 0.7),
            ),
            limit = 10,
        )

        assertEquals(listOf("lexical", "semantic", "unranked"), merged.map { it.text })
    }

    @Test
    fun mergeDropsBlankTextAndNormalizesKeptItems() {
        val merged = CommunicationSearchComposer.merge(
            listOf(
                item(at = 1L, text = "   "),
                item(at = 2L, text = "  x   y  "),
            )
        )

        assertEquals(1, merged.size)
        assertEquals("x y", merged.single().text)
    }

    @Test
    fun mergeEmptyLimitYieldsNothing() {
        assertTrue(CommunicationSearchComposer.merge(listOf(item(at = 1L, text = "x")), limit = 0).isEmpty())
    }

    @Test
    fun renderEmptyIsHonest() {
        assertEquals("Ничего не найдено.", CommunicationSearchComposer.render(emptyList(), zone))
    }

    @Test
    fun renderCarriesChannelTitleAndRef() {
        val rendered = CommunicationSearchComposer.render(
            listOf(item(at = 1_700_000_000_000L, text = "привет", channel = "SMS", title = "Иван", ref = "interaction #5")),
            zone,
        )

        assertTrue("channel must label the hit", rendered.contains("SMS: Иван — привет (interaction #5)"))
    }
}
