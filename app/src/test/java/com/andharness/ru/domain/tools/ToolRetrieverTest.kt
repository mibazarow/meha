package com.andharness.ru.domain.tools

import com.andharness.ru.data.agent.LlmTokenEstimator
import com.andharness.ru.domain.agent.LlmTool
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tool-RAG (update.md idea #1): BM25 relevance on representative RU/EN queries, the always-on
 * core set, explicitly named and recently used tools, and the degenerate-retrieval fallback.
 */
class ToolRetrieverTest {

    private val all: List<LlmTool> = DefaultTools.all().map { it.toLlmTool() }

    private fun tool(name: String, description: String): LlmTool =
        LlmTool(name = name, description = description, parameters = JsonObject(emptyMap()))

    private fun selectedNames(query: String, recent: List<String> = emptyList(), k: Int = 40): Set<String> =
        ToolRetriever.select(all, query, recent, k).tools.map { it.name }.toSet()

    @Test
    fun bm25RanksTheRelevantSyntheticDocFirst() {
        val tools = listOf(
            tool("alpha_read", "читать текст из файла на устройстве"),
            tool("beta_sms", "отправить смс сообщение абоненту"),
            tool("gamma_web", "поиск в интернете и открытие страниц")
        )
        assertEquals("beta_sms", ToolRetriever.search(tools, "отправь смс", 3).first())
        assertEquals("alpha_read", ToolRetriever.search(tools, "прочитай файл", 3).first())
    }

    @Test
    fun russianSmsQuerySurfacesSmsTools() {
        val names = selectedNames("отправь смс маме")
        assertTrue("sms_send must be selected", "sms_send" in names)
        assertTrue(
            "an sms reading tool must be selected too",
            "sms_thread" in names || "sms_conversations" in names || "sms_list" in names
        )
    }

    @Test
    fun currencyQueryKeepsTheWebCapability() {
        val names = selectedNames("какой сегодня курс доллара")
        assertTrue("web_search must be available", "web_search" in names)
    }

    @Test
    fun documentQuerySurfacesDocumentTools() {
        val names = selectedNames("прочитай pdf документ")
        assertTrue("doc_read must be available", "doc_read" in names)
        assertTrue("doc_info must be surfaced by BM25", "doc_info" in names)
    }

    @Test
    fun coreSetIsAlwaysIncluded() {
        val names = selectedNames("включи фонарик")
        ToolRetriever.CORE_TOOLS.forEach { core ->
            assertTrue("core tool $core missing", core in names)
        }
        assertTrue("tool_search escape hatch missing", "tool_search" in names)
    }

    @Test
    fun explicitlyNamedToolIsIncluded() {
        assertTrue("ping" in ToolRetriever.explicitlyNamed("запусти ping пожалуйста", all))
        assertTrue("files_hash" in selectedNames("сделай files_hash этого файла"))
    }

    @Test
    fun recentlyUsedToolIsKept() {
        val selection = ToolRetriever.select(all, "сделай это ещё раз", listOf("number_profile"), 40)
        assertTrue(selection.tools.any { it.name == "number_profile" })
    }

    @Test
    fun retrievalShrinksTheCatalog() {
        val selection = ToolRetriever.select(all, "отправь смс маме", emptyList(), ToolRetriever.DEFAULT_K)
        assertFalse(selection.degraded)
        assertTrue("retrieval must expose fewer tools than the full catalog", selection.tools.size < all.size)
        assertTrue("selected tools must be a subset", all.containsAll(selection.tools))
    }

    @Test
    fun blankQueryFallsBackToABoundedSafeSet() {
        val selection = ToolRetriever.select(all, "   ", emptyList(), 40)
        assertTrue(selection.degraded)
        assertEquals(0, selection.matched)
        // NEVER the whole catalog: bounded by MAX_FALLBACK_TOOLS.
        assertTrue("blank-query fallback must be bounded", selection.tools.size <= ToolRetriever.MAX_FALLBACK_TOOLS)
        assertTrue("blank-query fallback must be far smaller than the catalog", selection.tools.size < all.size)
        // The core escape hatch must survive the fallback.
        assertTrue(selection.tools.any { it.name == "tool_search" })
        assertEquals(expectedSafeSetSize(emptySet()), selection.tools.size)
    }

    @Test
    fun noLexicalMatchFallsBackToABoundedSafeSet() {
        // A query made only of tokens nothing in the catalog shares (deliberately gibberish).
        val selection = ToolRetriever.select(all, "zzzzq wwwwv", emptyList(), 40)
        assertTrue(selection.degraded)
        assertEquals(0, selection.matched)
        assertTrue("no-match fallback must be bounded", selection.tools.size <= ToolRetriever.MAX_FALLBACK_TOOLS)
        assertTrue("no-match fallback must never be the full catalog", selection.tools.size < all.size)
        assertTrue("tool_search must be in the no-match fallback", selection.tools.any { it.name == "tool_search" })
        assertEquals(expectedSafeSetSize(emptySet()), selection.tools.size)
    }

    @Test
    fun recentToolSurvivesTheBoundedFallback() {
        val selection = ToolRetriever.select(all, "zzzzq wwwwv", listOf("number_profile"), 40)
        assertTrue(selection.degraded)
        assertTrue("recent tool must survive the fallback", selection.tools.any { it.name == "number_profile" })
        assertTrue(selection.tools.size <= ToolRetriever.MAX_FALLBACK_TOOLS)
        assertEquals(expectedSafeSetSize(setOf("number_profile")), selection.tools.size)
    }

    @Test
    fun unionBelowMinimumFallsBackToABoundedSafeSet() {
        // Synthetic tiny catalog: a non-blank query that BM25 matches on exactly one tool, with a
        // two-tool core -> the union (3) is below MIN_SAFE_SET, so the bounded fallback is used.
        val catalog = listOf(
            tool("alpha_read", "прочитать файл на устройстве"),
            tool("beta_write", "записать данные"),
            tool("gamma_ping", "проверить сеть")
        )
        val core = setOf("alpha_read", "beta_write")
        val selection = ToolRetriever.select(catalog, "прочитать файл", emptyList(), 40, core = core)
        assertTrue("union below MIN_SAFE_SET must degrade", selection.degraded)
        assertTrue(selection.matched > 0)
        assertTrue("fallback must never be larger than the cap", selection.tools.size <= ToolRetriever.MAX_FALLBACK_TOOLS)
        assertTrue("core must be preserved", selection.tools.map { it.name }.containsAll(core))
    }

    @Test
    fun matchedQueryIsUnchangedByTheFallbackPolicy() {
        val selection = ToolRetriever.select(all, "отправь смс маме", emptyList(), ToolRetriever.DEFAULT_K)
        assertFalse("a lexically matched query must not degrade", selection.degraded)
        assertTrue("BM25 must have matched something", selection.matched > 0)
        assertTrue(selection.tools.any { it.name == "sms_send" })
        assertTrue("matched path must stay smaller than the catalog", selection.tools.size < all.size)
    }

    @Test
    fun toolSearchIsPublishedAsATool() {
        assertTrue(all.any { it.name == "tool_search" })
    }

    @Test
    fun freshChatToolSchemaCostStaysBounded() {
        val coreTools = all.filter { it.name in ToolRetriever.CORE_TOOLS }
        val coreOnly = LlmTokenEstimator.estimate(coreTools)
        val selection = ToolRetriever.select(
            all, "найди в переписке где писали про телефон", emptyList(), ToolRetriever.DEFAULT_K
        )
        val corePlusK = LlmTokenEstimator.estimate(selection.tools)
        println(
            "FRESH_CHAT_TOOL_COST coreTools=${coreTools.size} coreTokens=$coreOnly " +
                "corePlusKTools=${selection.tools.size} corePlusKTokens=$corePlusK"
        )
        // The explicitly required keep-set floors at ~6.0k (message_search/messages_summarize/
        // ui_find/remember_screen are heavy by themselves); restoring the high-frequency chat
        // management / contact-lookup / schedule-list tools to CORE pushed it to ~7.8k, so the
        // regression bounds sit just above the new, budget-checked ceiling.
        assertTrue("CORE-only tool schemas must stay under 8.5k tokens, was $coreOnly", coreOnly <= 8_500L)
        assertTrue("CORE+DEFAULT_K tool schemas must stay under 12k tokens, was $corePlusK", corePlusK <= 12_000L)
    }

    /** Expected size of the bounded fallback: core ∪ explicit/recent, capped, in catalog order. */
    private fun expectedSafeSetSize(extra: Set<String>): Int {
        val keep = LinkedHashSet<String>()
        keep += extra
        keep += ToolRetriever.CORE_TOOLS
        return all.count { it.name in keep }.coerceAtMost(ToolRetriever.MAX_FALLBACK_TOOLS)
    }
}
