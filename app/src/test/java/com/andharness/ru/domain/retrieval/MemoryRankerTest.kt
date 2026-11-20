package com.andharness.ru.domain.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * update.md idea #3: meaning-based memory recall. The motivating case is a differently-worded query
 * («налоговая») reaching a stored «ФНС, декларация» memory.
 */
class MemoryRankerTest {

    private val now = 1_700_000_000_000L

    private val memories = listOf(
        MemoryRecord(
            id = 1L,
            text = "Пользователь любит бирюзовый цвет",
            tags = "цвет, предпочтение, color"
        ),
        MemoryRecord(
            id = 2L,
            text = "Рабочая встреча с ФНС по декларации",
            tags = "налоговая, фнс, декларация"
        )
    )

    @Test
    fun differentlyWordedQueryRanksTheTaxMemoryFirst() {
        val ranked = MemoryRanker.rank("как я решил вопрос с налоговой", memories, now = now)
        assertEquals("the ФНС note must win", 2L, ranked.first().id)
        assertTrue(ranked.first().lexicallyMatched)
    }

    @Test
    fun taxAbbreviationAlsoWorks() {
        val ranked = MemoryRanker.rank("фнс", memories, now = now)
        assertEquals(2L, ranked.first().id)
    }

    @Test
    fun colourQueryRanksTheColourMemoryFirst() {
        val ranked = MemoryRanker.rank("какой цвет мне нравится", memories, now = now)
        assertEquals(1L, ranked.first().id)
        assertFalse("the tax note must not be a lexical hit", ranked.any { it.id == 2L })
    }

    @Test
    fun unrelatedQueryFallsBackNonLexicallyInsteadOfEmpty() {
        val ranked = MemoryRanker.rank("прогноз погоды в токио", memories, now = now)
        assertTrue("a fallback must never be empty while memories exist", ranked.isNotEmpty())
        assertTrue("the fallback must be flagged honestly", ranked.none { it.lexicallyMatched })
    }

    @Test
    fun blankQueryFallsBackNonLexically() {
        val ranked = MemoryRanker.rank("   ", memories, now = now)
        assertTrue(ranked.isNotEmpty())
        assertTrue(ranked.none { it.lexicallyMatched })
    }

    @Test
    fun emptyTableYieldsNoMatches() {
        assertTrue(MemoryRanker.rank("налоговая", emptyList(), now = now).isEmpty())
    }

    @Test
    fun importanceBreaksLexicalTies() {
        val ties = listOf(
            MemoryRecord(id = 10L, text = "любит кофе", importance = 0),
            MemoryRecord(id = 11L, text = "любит кофе", importance = 2)
        )
        val ranked = MemoryRanker.rank("кофе", ties, now = now)
        assertEquals(11L, ranked.first().id)
    }

    @Test
    fun tagsAreWeightedAboveBareText() {
        val tagged = MemoryRecord(id = 20L, text = "заметка без деталей", tags = "бирюзовый, цвет")
        val untagged = MemoryRecord(id = 21L, text = "бирюзовый цвет", tags = "")
        val ranked = MemoryRanker.rank("бирюзовый цвет", listOf(untagged, tagged), now = now)
        assertEquals("the explicit tags must win", 20L, ranked.first().id)
    }

    @Test
    fun limitIsRespected() {
        val ranked = MemoryRanker.rank("цвет", memories, limit = 1, now = now)
        assertEquals(1, ranked.size)
    }

    @Test
    fun recencyBonusIsFullAtAgeZeroAndForUnknownTimestamps() {
        assertEquals(MemoryRanker.RECENCY_BONUS, MemoryRanker.recencyBonus(now, 0, now), 1e-9)
        assertEquals(MemoryRanker.RECENCY_BONUS, MemoryRanker.recencyBonus(0, 0, now), 1e-9)
    }

    @Test
    fun recencyBonusHalvesEveryHalfLife() {
        val half = MemoryRanker.RECENCY_HALF_LIFE_MS
        assertEquals(
            MemoryRanker.RECENCY_BONUS / 2,
            MemoryRanker.recencyBonus(now - half, 0, now),
            1e-9
        )
        assertEquals(
            MemoryRanker.RECENCY_BONUS / 4,
            MemoryRanker.recencyBonus(now - 2 * half, 0, now),
            1e-9
        )
    }

    @Test
    fun recencyBonusDecaysSmoothlyInsteadOfDroppingToZero() {
        val eightDays = 8L * 24 * 60 * 60 * 1000
        val bonus = MemoryRanker.recencyBonus(now - eightDays, 0, now)
        assertTrue("past the old 7-day window the bonus must still be positive", bonus > 0.0)
        assertTrue("but clearly decayed", bonus < MemoryRanker.RECENCY_BONUS)
    }

    @Test
    fun createdAtIsUsedBeforeTheFirstRecallAndFutureTimestampsAreClamped() {
        val half = MemoryRanker.RECENCY_HALF_LIFE_MS
        assertEquals(
            MemoryRanker.RECENCY_BONUS / 2,
            MemoryRanker.recencyBonus(0, now - half, now),
            1e-9
        )
        assertEquals(
            MemoryRanker.RECENCY_BONUS,
            MemoryRanker.recencyBonus(now + 100_000, 0, now),
            1e-9
        )
    }

    @Test
    fun fresherMemoryOutranksACloserOlderTwin() {
        val stale = MemoryRecord(
            id = 30L,
            text = "любит кофе",
            lastUsedAt = now - 3 * MemoryRanker.RECENCY_HALF_LIFE_MS
        )
        val fresh = MemoryRecord(id = 31L, text = "любит кофе", lastUsedAt = now)

        val ranked = MemoryRanker.rank("кофе", listOf(stale, fresh), now = now)

        assertEquals(31L, ranked.first().id)
    }

    @Test
    fun graphReachedMemoryIsReturnedAsMatchedWithGraphBonus() {
        val lexical = MemoryRanker.rank("бирюзовый", memories, now = now)
        assertTrue("without the graph the ФНС memory is not recalled", lexical.none { it.id == 2L })

        val withGraph = MemoryRanker.rank("бирюзовый", memories, now = now, graphIds = setOf(2L))
        val graphHit = withGraph.first { it.id == 2L }
        assertTrue("a graph-reached memory is a genuine connected match", graphHit.lexicallyMatched)

        val priorOnly = MemoryRanker.rank("прогноз погоды в токио", memories, now = now)
            .first { it.id == 2L }.score
        assertEquals(
            "the graph hit adds exactly GRAPH_BONUS on top of the prior",
            priorOnly + MemoryRanker.GRAPH_BONUS,
            graphHit.score,
            1e-9
        )
    }

    @Test
    fun frequentlyRecalledMemoryOutranksItsColdTwin() {
        val cold = MemoryRecord(id = 40L, text = "любит кофе")
        val hot = MemoryRecord(id = 41L, text = "любит кофе", accessCount = 3)

        val ranked = MemoryRanker.rank("кофе", listOf(cold, hot), now = now)

        assertEquals("the more-recalled memory must win", 41L, ranked.first().id)
    }

    @Test
    fun accessCountZeroAddsNoBonusOverTheDefaultRecord() {
        val explicitZero = MemoryRecord(id = 50L, text = "любит кофе", accessCount = 0)
        val default = MemoryRecord(id = 51L, text = "любит кофе")

        fun score(record: MemoryRecord) =
            MemoryRanker.rank("кофе", listOf(record), now = now).first().score

        assertEquals(
            "a zero recall count must yield exactly the pre-frequency score",
            score(default),
            score(explicitZero),
            0.0
        )
    }

    @Test
    fun accessBonusGrowsPerRecallAndSaturatesAtTheCap() {
        val base = MemoryRecord(id = 60L, text = "любит кофе", accessCount = 0)
        val one = MemoryRecord(id = 61L, text = "любит кофе", accessCount = 1)
        val five = MemoryRecord(id = 62L, text = "любит кофе", accessCount = 5)
        val many = MemoryRecord(id = 63L, text = "любит кофе", accessCount = 50)

        fun score(record: MemoryRecord) =
            MemoryRanker.rank("кофе", listOf(record), now = now).first().score

        assertEquals(MemoryRanker.ACCESS_BONUS_PER_HIT, score(one) - score(base), 1e-9)
        assertEquals(MemoryRanker.MAX_ACCESS_BONUS, score(five) - score(base), 1e-9)
        assertEquals("the frequency prior must saturate", score(five), score(many), 1e-9)
    }

    @Test
    fun emptyGraphIdsKeepTheRankingUnchanged() {
        val baseline = MemoryRanker.rank("бирюзовый", memories, now = now)
        val explicitEmpty = MemoryRanker.rank("бирюзовый", memories, now = now, graphIds = emptySet())
        assertEquals(baseline, explicitEmpty)
    }

    // ----------------------------------------------------- semantic candidate expansion

    /** Query unit vector; near is a unit vector whose cosine with it is ≈ 0.9. */
    private val queryVector = floatArrayOf(1f, 0f)
    private val nearVector = floatArrayOf(0.9f, 0.43589f)
    private val orthogonalVector = floatArrayOf(0f, 1f)

    /** Zero token overlap with "погода", so only the semantic layer can recall them. */
    private val nearMemory = MemoryRecord(id = 100L, text = "кофеварка сломалась")
    private val farMemory = MemoryRecord(id = 101L, text = "орхидея завяла", importance = 2)

    @Test
    fun semanticOnlyMatchAboveThresholdIsRecalled() {
        val ranked = MemoryRanker.rank(
            query = "погода",
            memories = listOf(nearMemory, farMemory),
            now = now,
            queryVector = queryVector,
            docVectors = mapOf(
                "100" to nearVector,
                "101" to orthogonalVector
            )
        )

        assertEquals("only the paraphrase close in embedding space is recalled", listOf(100L), ranked.map { it.id })
        assertTrue("a semantic recall is a genuine recall", ranked.first().lexicallyMatched)

        val cos = TextRetriever.cosine(queryVector, nearVector)
        val expectedPrior = MemoryRanker.recencyBonus(0L, 0L, now)
        assertEquals(
            "the score is the prior plus the weighted cosine, with no double counting",
            expectedPrior + cos * MemoryRanker.SEMANTIC_ONLY_WEIGHT,
            ranked.first().score,
            1e-9
        )
        assertTrue("the near vector must clear the recall threshold", cos >= MemoryRanker.SEMANTIC_RECALL_MIN_COSINE)
    }

    @Test
    fun semanticOnlyMatchBelowThresholdIsNotRecalled() {
        val plainFar = MemoryRecord(id = 102L, text = "орхидея завяла")
        val ranked = MemoryRanker.rank(
            query = "погода",
            memories = listOf(plainFar),
            now = now,
            queryVector = queryVector,
            docVectors = mapOf("102" to orthogonalVector)
        )

        assertTrue("the honest fallback must still return the memory", ranked.isNotEmpty())
        assertTrue("an orthogonal vector is not a match", ranked.none { it.lexicallyMatched })
        assertEquals(
            "the fallback is the bare prior, with no semantic bonus",
            MemoryRanker.recencyBonus(0L, 0L, now),
            ranked.first().score,
            1e-9
        )
    }

    @Test
    fun semanticExpansionDoesNotOutrankStrongLexicalMatch() {
        val strongLexical = MemoryRecord(id = 200L, text = "чай чай чай")
        val semanticOnly = MemoryRecord(id = 201L, text = "кофеварка сломалась")
        // Unrelated third candidate with an orthogonal vector: it is never recalled, but it lowers
        // the MEAN cosine so the top (1.0) forms a clear peak that clears the lift gate.
        val far = MemoryRecord(id = 202L, text = "орхидея завяла")
        // cos 0.95: comfortably above the floor and within SEMANTIC_RECALL_MARGIN (0.08) of the
        // lexical memory's top cosine (1.0), so the semantic-only memory is still recalled under
        // the relative rule.
        val semanticVector = floatArrayOf(0.95f, 0.31225f)

        val ranked = MemoryRanker.rank(
            query = "чай",
            memories = listOf(strongLexical, semanticOnly, far),
            now = now,
            queryVector = queryVector,
            docVectors = mapOf(
                "200" to queryVector,
                "201" to semanticVector,
                "202" to orthogonalVector
            )
        )

        val semanticHit = ranked.first { it.id == 201L }
        assertTrue("the semantic-only memory is still recalled", semanticHit.lexicallyMatched)
        assertEquals("the strong fused lexical match must still win", 200L, ranked.first().id)
        assertTrue(
            "the lexical hit must outscore the semantic-only one",
            ranked.first { it.id == 200L }.score > semanticHit.score
        )
    }

    @Test
    fun semanticExpansionUsesRelativeMargin() {
        // Unit doc vectors whose cosine with queryVector = [1, 0] is ≈ 0.90 / ≈ 0.84 / ≈ 0.60.
        val best = floatArrayOf(0.90f, 0.43589f)
        val withinMargin = floatArrayOf(0.84f, 0.54259f)
        val belowMargin = floatArrayOf(0.60f, 0.8f)
        val zeroOverlap = listOf(
            MemoryRecord(id = 300L, text = "кофеварка сломалась"),
            MemoryRecord(id = 301L, text = "орхидея завяла"),
            MemoryRecord(id = 302L, text = "велосипед ржавеет")
        )

        val ranked = MemoryRanker.rank(
            query = "погода",
            memories = zeroOverlap,
            now = now,
            queryVector = queryVector,
            docVectors = mapOf(
                "300" to best,
                "301" to withinMargin,
                "302" to belowMargin
            )
        )

        assertEquals(
            "the top memory and the one within the margin are recalled, the far one is not",
            listOf(300L, 301L),
            ranked.map { it.id }
        )
        assertTrue(ranked.all { it.lexicallyMatched })
    }

    @Test
    fun weakSemanticTopDoesNotExpand() {
        // Best cosine across all candidates is ≈ 0.45, below SEMANTIC_RECALL_MIN_COSINE.
        val weak = floatArrayOf(0.45f, 0.89303f)
        val memory = MemoryRecord(id = 303L, text = "кофеварка сломалась")

        val ranked = MemoryRanker.rank(
            query = "погода",
            memories = listOf(memory),
            now = now,
            queryVector = queryVector,
            docVectors = mapOf("303" to weak)
        )

        assertTrue("the honest fallback must still return the memory", ranked.isNotEmpty())
        assertTrue("a weak top cosine must not invent a semantic match", ranked.none { it.lexicallyMatched })
        assertEquals(
            "the fallback is the bare prior, with no semantic bonus",
            MemoryRanker.recencyBonus(0L, 0L, now),
            ranked.first().score,
            1e-9
        )
    }

    @Test
    fun uniformSemanticSetDoesNotExpand() {
        // Three candidates all ABOVE the floor but nearly equally close to the query: cosines
        // ≈ 0.750 / 0.740 / 0.730, so top − mean ≈ 0.010 < SEMANTIC_RECALL_MIN_LIFT. A flat
        // distribution has no peak, so it is rejected by the LIFT gate even though every cosine
        // clears the absolute floor — the test proves the flatness guard, not the floor.
        val near = floatArrayOf(0.75f, 0.66144f)
        val mid = floatArrayOf(0.74f, 0.67261f)
        val far = floatArrayOf(0.73f, 0.68345f)
        val uniformlyClose = listOf(
            MemoryRecord(id = 400L, text = "кофеварка сломалась"),
            MemoryRecord(id = 401L, text = "орхидея завяла"),
            MemoryRecord(id = 402L, text = "велосипед ржавеет")
        )

        val ranked = MemoryRanker.rank(
            query = "погода",
            memories = uniformlyClose,
            now = now,
            queryVector = queryVector,
            docVectors = mapOf(
                "400" to near,
                "401" to mid,
                "402" to far
            )
        )

        val cosines = listOf(near, mid, far).map { TextRetriever.cosine(queryVector, it) }
        val top = cosines.max()
        val mean = cosines.sum() / cosines.size
        assertTrue("every candidate must clear the floor", top >= MemoryRanker.SEMANTIC_RECALL_MIN_COSINE)
        assertTrue("the distribution must be flat", top - mean < MemoryRanker.SEMANTIC_RECALL_MIN_LIFT)

        assertTrue("the honest fallback must still return memories", ranked.isNotEmpty())
        assertTrue("a flat cosine distribution must not invent a semantic match", ranked.none { it.lexicallyMatched })
    }

    @Test
    fun singleCandidateUsesFloorAlone() {
        // With a single candidate there is no meaningful mean/lift, so the ABSOLUTE floor decides:
        // cos ≈ 0.80 (>= 0.72) is recalled, cos ≈ 0.60 (< 0.72) is not.
        val aboveFloor = floatArrayOf(0.80f, 0.6f)
        val recalled = MemoryRanker.rank(
            query = "погода",
            memories = listOf(MemoryRecord(id = 500L, text = "кофеварка сломалась")),
            now = now,
            queryVector = queryVector,
            docVectors = mapOf("500" to aboveFloor)
        )
        assertEquals("a lone candidate above the floor is recalled", listOf(500L), recalled.map { it.id })
        assertTrue("a semantic recall is a genuine recall", recalled.first().lexicallyMatched)
        assertEquals(
            "the score is the prior plus the weighted cosine",
            MemoryRanker.recencyBonus(0L, 0L, now) +
                TextRetriever.cosine(queryVector, aboveFloor) * MemoryRanker.SEMANTIC_ONLY_WEIGHT,
            recalled.first().score,
            1e-9
        )

        val belowFloor = floatArrayOf(0.60f, 0.8f)
        val notRecalled = MemoryRanker.rank(
            query = "погода",
            memories = listOf(MemoryRecord(id = 501L, text = "кофеварка сломалась")),
            now = now,
            queryVector = queryVector,
            docVectors = mapOf("501" to belowFloor)
        )
        assertTrue("the honest fallback must still return the memory", notRecalled.isNotEmpty())
        assertTrue("a lone candidate below the floor is not a match", notRecalled.none { it.lexicallyMatched })
    }

    @Test
    fun withoutQueryVectorRankingIsUnchanged() {
        val baseline = MemoryRanker.rank("погода", listOf(nearMemory, farMemory), now = now)
        val withVectorsButNoQuery = MemoryRanker.rank(
            query = "погода",
            memories = listOf(nearMemory, farMemory),
            now = now,
            queryVector = null,
            docVectors = mapOf(
                "100" to nearVector,
                "101" to orthogonalVector
            )
        )

        assertEquals("doc vectors alone must not change anything", baseline, withVectorsButNoQuery)
        assertEquals("the pre-change fallback order is unchanged", listOf(101L, 100L), baseline.map { it.id })
        assertTrue(baseline.none { it.lexicallyMatched })
    }

    /**
     * BUGS.md M2: a question about the past must reach back. Without [MemoryRanker.rank]'s
     * `ignoreRecency`, a year-old agreement loses the age prior to a fresh, equally relevant note;
     * with it, relevance decides.
     */
    @Test
    fun aPastRecallQuestionDoesNotPenaliseAnOldMemory() {
        val day = 24L * 60 * 60 * 1000
        val old = MemoryRecord(
            id = 70L,
            text = "Договорились с Мариной про аренду офиса",
            tags = "марина, аренда",
            createdAt = now - 365 * day
        )
        val fresh = MemoryRecord(
            id = 71L,
            text = "Договорились с Мариной про аренду офиса",
            tags = "марина, аренда",
            createdAt = now
        )
        val memories = listOf(old, fresh)
        val query = "что мы решили по аренде офиса с Мариной"

        val normal = MemoryRanker.rank(query, memories, now = now)
        val past = MemoryRanker.rank(query, memories, now = now, ignoreRecency = true)

        assertEquals("normally the fresh twin wins on the age prior", 71L, normal.first().id)
        val oldScoreNormal = normal.first { it.id == 70L }.score
        val oldScorePast = past.first { it.id == 70L }.score
        assertEquals(
            "the age penalty is neutralised: the old note gets the full recency prior",
            MemoryRanker.RECENCY_BONUS - MemoryRanker.recencyBonus(old.createdAt, old.createdAt, now),
            oldScorePast - oldScoreNormal,
            1e-9
        )
        assertEquals("with equal relevance the two are tied", 0.0, past.first().score - past[1].score, 1e-9)
    }

    @Test
    fun ignoreRecencyIsOffByDefault() {
        val memories = listOf(
            MemoryRecord(id = 80L, text = "любит кофе", createdAt = now - 90L * 24 * 60 * 60 * 1000),
            MemoryRecord(id = 81L, text = "любит кофе", createdAt = now)
        )
        assertEquals(
            MemoryRanker.rank("кофе", memories, now = now),
            MemoryRanker.rank("кофе", memories, now = now, ignoreRecency = false)
        )
    }
}
