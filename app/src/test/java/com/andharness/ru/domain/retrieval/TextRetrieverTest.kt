package com.andharness.ru.domain.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * update.md idea #3: the shared lexical/synonym retriever. Covers RU/EN tokenisation, synonym and
 * transliteration expansion, Russian inflection tolerance, BM25 ordering and the optional-embedding
 * fusion fallback.
 */
class TextRetrieverTest {

    private fun doc(id: String, text: String) = TextRetriever.Doc(id, TextRetriever.tokenize(text))

    // ---------------------------------------------------------------- tokenisation / synonyms

    @Test
    fun transliterationSynonymsExpandBothWays() {
        assertTrue("смс must expand to sms", "sms" in TextRetriever.tokenize("смс"))
        assertTrue("sms must expand to смс", "смс" in TextRetriever.tokenize("sms"))
        assertTrue("фото must expand to photo", "photo" in TextRetriever.tokenize("фото"))
        assertTrue("photo must expand to фото", "фото" in TextRetriever.tokenize("photo"))
    }

    @Test
    fun taxSynonymsBridgeToFns() {
        assertTrue("налоговая must reach фнс", "фнс" in TextRetriever.tokenize("налоговая"))
        assertTrue("налоговой must reach фнс", "фнс" in TextRetriever.tokenize("налоговой"))
        assertTrue("фнс must reach налогов", "налогов" in TextRetriever.tokenize("фнс"))
    }

    @Test
    fun moneySymbolAndWordBridge() {
        assertTrue("₽ must tokenise to рубл", "рубл" in TextRetriever.tokenize("₽"))
        assertTrue("деньги must reach рубл", "рубл" in TextRetriever.tokenize("деньги"))
        assertTrue("balance must reach баланс", "баланс" in TextRetriever.tokenize("balance"))
    }

    @Test
    fun carSynonymsBridge() {
        assertTrue("машина must expand to авто", "авто" in TextRetriever.tokenize("машина"))
        assertTrue("car must expand to машин", "машин" in TextRetriever.tokenize("car"))
    }

    @Test
    fun russianInflectionCollapsesToTheSameStemAndPrefix() {
        val a = TextRetriever.tokenize("налоговая")
        val b = TextRetriever.tokenize("налоговой")
        assertTrue("both forms must share the stem", "налогов" in a && "налогов" in b)
        val prefixesA = a.filter { it.startsWith("\u0001p") }
        val prefixesB = b.filter { it.startsWith("\u0001p") }
        assertTrue("both forms must share a prefix token", prefixesA.isNotEmpty() && prefixesA == prefixesB)
    }

    // ---------------------------------------------------------------- BM25 ranking

    @Test
    fun bm25RanksTheSynonymMatchFirst() {
        val docs = listOf(
            doc("tax", "Рабочая встреча с ФНС по декларации"),
            doc("color", "Пользователь любит бирюзовый цвет")
        )
        val hits = TextRetriever.bm25("налоговая", docs)
        assertEquals("tax", hits.first().id)
        assertEquals("only the tax note matches", 1, hits.size)
    }

    @Test
    fun unrelatedQueryScoresLowOrNothing() {
        val docs = listOf(
            doc("tax", "Рабочая встреча с ФНС по декларации"),
            doc("color", "Пользователь любит бирюзовый цвет")
        )
        assertTrue(
            "an unrelated query must not match either note",
            TextRetriever.bm25("прогноз погоды в токио", docs).isEmpty()
        )
    }

    @Test
    fun wordOrderDoesNotMatterForMatching() {
        val docs = listOf(
            doc("meeting", "встреча с врачом в пятницу"),
            doc("grocery", "купить молоко и хлеб")
        )
        assertEquals("meeting", TextRetriever.bm25("врач встреча", docs).first().id)
    }

    // ---------------------------------------------------------------- optional embeddings hook

    @Test
    fun fuseWithoutEmbeddingsKeepsLexicalOrder() {
        val lexical = listOf(TextRetriever.Hit("a", 2.0), TextRetriever.Hit("b", 1.0))
        val query = TextRetriever.NoOpEmbedder.embed("налог")
        assertEquals(null, query)
        assertEquals(lexical, TextRetriever.fuse(lexical, query, emptyMap()))
    }

    @Test
    fun fuseWithEmbeddingsCanRerank() {
        val lexical = listOf(TextRetriever.Hit("a", 0.10), TextRetriever.Hit("b", 0.09))
        val vectors = mapOf(
            "a" to floatArrayOf(0f, 1f),
            "b" to floatArrayOf(1f, 0f)
        )
        val fused = TextRetriever.fuse(lexical, floatArrayOf(1f, 0f), vectors)
        assertEquals("the semantically closer doc must win", "b", fused.first().id)
    }

    @Test
    fun cosineHandlesMissingAndDegenerateVectors() {
        assertEquals(0.0, TextRetriever.cosine(null, floatArrayOf(1f)), 0.0001)
        assertEquals(0.0, TextRetriever.cosine(floatArrayOf(1f, 2f), floatArrayOf(1f)), 0.0001)
        assertEquals(0.0, TextRetriever.cosine(floatArrayOf(0f), floatArrayOf(0f)), 0.0001)
        assertEquals(1.0, TextRetriever.cosine(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f)), 0.0001)
    }

    @Test
    fun stopWordsAndShortTokensAreDropped() {
        assertTrue(TextRetriever.tokenize("а и в на по").isEmpty())
        assertFalse(TextRetriever.tokenize("файл").isEmpty())
    }
}
