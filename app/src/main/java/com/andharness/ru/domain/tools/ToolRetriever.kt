package com.andharness.ru.domain.tools

import com.andharness.ru.domain.agent.LlmTool
import com.andharness.ru.domain.retrieval.TextRetriever
import kotlin.math.ln

/**
 * Two-stage tool selection (update.md idea #1), applied on every turn without a user-facing
 * toggle.
 *
 * Pure Kotlin, zero dependencies, works offline on any phone: an in-memory BM25 index is built
 * over every tool's name, description and group, tokenised for BOTH Russian and English
 * (lower-cased, split on non-letters/digits, stop-words dropped, light suffix stemming, a few
 * cross-script synonyms such as смс/sms). Per turn a small relevant subset is returned.
 *
 * Capability is never silently lost:
 * - the always-on [CORE_TOOLS] set is always included;
 * - tools named explicitly in the turn text are always included;
 * - recently used tools of the chat are always included (follow-ups keep working);
 * - [tool_search] belongs to [CORE_TOOLS], so the model can discover anything else by name;
 * - any degenerate retrieval (blank query, no lexical match at all, or a suspiciously tiny set)
 *   falls back to a BOUNDED safe set (core ∪ explicit ∪ recent, capped at [MAX_FALLBACK_TOOLS]),
 *   NEVER to the full catalog — sending all 455 schemas would blow the context window, and the
 *   [tool_search] escape hatch keeps every capability reachable on demand.
 */
object ToolRetriever {

    /** Default number of BM25-selected tools added on top of the core/recent/explicit sets. */
    const val DEFAULT_K = 20
    const val MIN_K = 10
    const val MAX_K = 200

    /** How many recently used tools (per chat) are always kept. */
    const val RECENT_TOOLS = 12

    /** A union below this size is treated as suspicious and falls back to the bounded safe set. */
    const val MIN_SAFE_SET = 8

    /**
     * Hard cap on the bounded fallback set. A degraded turn (blank query, no BM25 match, or a
     * suspiciously small union) exposes at most this many schemas — core ∪ explicit ∪ recent — so
     * the model never receives the whole 455-tool catalog (which alone is ~65-80k tokens and
     * instantly trips context compaction). [CORE_TOOLS] has 36 entries, leaving room for the
     * recent/explicit tools inside this budget.
     */
    const val MAX_FALLBACK_TOOLS = 40

    /** Name tokens are more informative than description tokens, so they count more. */
    private const val NAME_WEIGHT = 3

    /**
     * Tools that are always exposed regardless of the query: the everyday cross-domain backbone
     * (file read/write/list/search, web, memory, essential UI automation, a few device basics,
     * notifications, people/contacts, chat read AND management — create/rename/delete/pin — plus
     * schedule listing and message/transcript/recording lookups) and the [tool_search] escape
     * hatch. Everything else (objectives, MCP, RAG, SMS, notes/todos, diagnostics, …) is left to
     * BM25 retrieval or [tool_search], so the fixed per-turn schema overhead stays small while
     * every capability remains reachable on demand.
     */
    val CORE_TOOLS: Set<String> = linkedSetOf(
        "tool_search",
        "files_read", "files_write", "files_list", "files_search",
        "web_search", "web_fetch",
        "memory_save", "memory_search",
        "ui_tap", "ui_type", "ui_tree", "ui_scroll", "ui_find", "key_event",
        "app_launch", "device_info", "flashlight_set",
        "notification_list", "notification_reply",
        "person_find", "person_brief", "contact_search",
        "chat_create", "chat_rename", "chat_delete", "chat_pin", "chat_list", "chat_search",
        "schedule_list", "interaction_search",
        "message_search", "messages_summarize", "transcript_search", "recordings_list",
        "remember_screen"
    )

    /**
     * The FIXED, deterministic full [CORE_TOOLS] set in catalog order. The filter walks [all], so the
     * emitted order is stable and catalog-ordered.
     */
    fun coreOnly(all: List<LlmTool>): List<LlmTool> = all.filter { it.name in CORE_TOOLS }

    data class Selection(
        /** The tool list to expose this turn, in catalog order. */
        val tools: List<LlmTool>,
        /** How many tools BM25 proposed (before the union with core/explicit/recent). */
        val matched: Int,
        /** True when retrieval was untrustworthy and the bounded safe set was returned instead. */
        val degraded: Boolean
    )

    /**
     * Selects the tools to expose for one turn. [recentToolNames] should be the tool names used in
     * this chat, oldest first; the newest [RECENT_TOOLS] survive.
     */
    fun select(
        all: List<LlmTool>,
        query: String,
        recentToolNames: List<String>,
        k: Int = DEFAULT_K,
        core: Set<String> = CORE_TOOLS
    ): Selection {
        if (all.isEmpty()) return Selection(emptyList(), matched = 0, degraded = false)
        val effectiveK = k.coerceIn(MIN_K, MAX_K)
        val byName = all.associateBy { it.name }

        // No query text at all: there is nothing to retrieve on, so expose only the bounded safe
        // set (core + recent + anything explicitly named) — never the whole catalog.
        if (query.isBlank()) {
            val safe = safeFallback(all, core, explicit = emptySet(), recent = boundedRecent(recentToolNames, byName))
            return Selection(safe, matched = 0, degraded = true)
        }

        val explicit = explicitlyNamed(query, all)
        val recent = boundedRecent(recentToolNames, byName)
        val scored = search(all, query, effectiveK)

        // Nothing in the token vocabulary matched: the bounded safe set is still a usable subset,
        // and tool_search (in CORE_TOOLS) lets the model discover anything else by name.
        if (scored.isEmpty()) {
            return Selection(safeFallback(all, core, explicit, recent), matched = 0, degraded = true)
        }

        val chosen = LinkedHashSet<String>()
        all.forEach { if (it.name in core) chosen += it.name }
        chosen += explicit
        chosen += recent
        chosen += scored

        if (chosen.size < MIN_SAFE_SET) {
            return Selection(safeFallback(all, core, explicit, recent), matched = scored.size, degraded = true)
        }

        return Selection(
            tools = all.filter { it.name in chosen },
            matched = scored.size,
            degraded = false
        )
    }

    /** The newest [RECENT_TOOLS] tool names that actually exist in the catalog, newest first. */
    private fun boundedRecent(recentToolNames: List<String>, byName: Map<String, LlmTool>): List<String> =
        recentToolNames.asReversed().distinct().filter { it in byName }.take(RECENT_TOOLS)

    /**
     * Bounded safe set used whenever BM25 retrieval is untrustworthy (blank query, no lexical match,
     * or a suspiciously small union): exactly core ∪ explicitly named ∪ recent, in catalog order,
     * capped at [MAX_FALLBACK_TOOLS]. It NEVER returns the full catalog. Explicit/recent names are
     * prioritised so they survive the cap; [tool_search] is part of [CORE_TOOLS], which keeps the
     * whole catalog reachable on demand.
     */
    private fun safeFallback(
        all: List<LlmTool>,
        core: Set<String>,
        explicit: Set<String>,
        recent: List<String>
    ): List<LlmTool> {
        // Explicitly named and recently used tools are mandatory, so they are admitted first and
        // only then does the remaining budget go to the core set. The result is re-emitted in
        // catalog order (as Selection documents), which keeps selection deterministic.
        val priority = LinkedHashSet<String>()
        priority += explicit
        priority += recent
        val coreRest = all.asSequence().map { it.name }.filter { it in core && it !in priority }
        val keep = (priority.asSequence() + coreRest).take(MAX_FALLBACK_TOOLS).toSet()
        return all.filter { it.name in keep }
    }

    /**
     * Tool names mentioned verbatim in [query] (case-insensitive), e.g. "ping", "files_hash".
     * Only names of at least 4 characters are matched to avoid short-token false positives.
     */
    fun explicitlyNamed(query: String, all: List<LlmTool>): Set<String> {
        if (query.isBlank()) return emptySet()
        val lower = query.lowercase()
        val tokens = tokenize(query).toSet()
        return all.asSequence()
            .map { it.name }
            .filter { name ->
                name.length >= 4 && (name in lower || name.replace('_', ' ') in lower || name in tokens)
            }
            .toSet()
    }

    /**
     * BM25 relevance search over [all]; returns up to [limit] tool names with a positive score,
     * best first.
     */
    fun search(all: List<LlmTool>, query: String, limit: Int): List<String> {
        val queryTokens = tokenize(query).distinct()
        if (queryTokens.isEmpty() || all.isEmpty()) return emptyList()

        val docs = all.map { docTokens(it) }
        val df = HashMap<String, Int>()
        for (doc in docs) {
            for (term in doc.toHashSet()) df.merge(term, 1) { a, b -> a + b }
        }
        val total = docs.size.toDouble()
        val lengths = docs.map { it.size }
        val avgLength = lengths.average().coerceAtLeast(1.0)

        val scored = ArrayList<Pair<String, Double>>(all.size)
        for (i in all.indices) {
            val tf = HashMap<String, Int>()
            for (term in docs[i]) tf.merge(term, 1) { a, b -> a + b }
            val docLength = lengths[i].coerceAtLeast(1)
            var score = 0.0
            for (term in queryTokens) {
                val freq = tf[term] ?: continue
                val docFreq = df[term] ?: continue
                val idf = ln(1.0 + (total - docFreq + 0.5) / (docFreq + 0.5))
                val norm = freq * (TextRetriever.BM25_K1 + 1) /
                    (freq + TextRetriever.BM25_K1 *
                        (1 - TextRetriever.BM25_B + TextRetriever.BM25_B * docLength / avgLength))
                score += idf * norm
            }
            if (score > 0.0) scored += all[i].name to score
        }
        return scored.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    /** Weighted token bag of one tool: name (heavily weighted), group and description. */
    private fun docTokens(tool: LlmTool): List<String> {
        val out = ArrayList<String>()
        repeat(NAME_WEIGHT) {
            out += normalize(tool.name)
            tool.name.split('_', '-', ' ').forEach { part -> out += normalize(part) }
        }
        out += normalize(groupOf(tool.name))
        out += tokenize(tool.description)
        return out.filter { it.length >= TextRetriever.MIN_TOKEN_LENGTH }
    }

    private fun groupOf(name: String): String = name.substringBefore('_')

    /**
     * Delegates to the shared [TextRetriever] tokenizer (RU/EN normalisation, stop-words, light
     * stemming, 4-char prefix tokens for Russian inflection, synonym/transliteration expansion) so
     * Tool retrieval and memory recall use exactly one vocabulary.
     */
    fun tokenize(text: String): List<String> = TextRetriever.tokenize(text)

    private fun normalize(token: String): String = TextRetriever.normalize(token)
}
