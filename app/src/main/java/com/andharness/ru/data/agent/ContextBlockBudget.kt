package com.andharness.ru.data.agent

/**
 * Per-block token budgets for the always-injected prompt blocks (CONTEXT_TASKS_V2 §2).
 *
 * Without a per-block budget, a long history with an active objective, a big profile, an MCP list
 * and twenty people can push the reference blocks past the point where the dialogue itself no longer
 * fits. `ContextManagerImpl` then compacts the conversation — i.e. the user pays for dialogue
 * compression because a reference block grew. Each always-on block therefore gets a share of the same
 * budget compaction uses, allocated by priority rather than by build order.
 *
 * Trimming rule: cut the NUMBER of items, never the length of one item. A memory truncated in the
 * middle of a phrase reads like a fact that was never stated — worse than a missing entry.
 *
 * Fixed blocks are never touched here: `trustedSourcesBlock`, `nowBlock`, `currentChatBlock` and
 * `recallHintBlock` are small and self-capped, and `objectiveBlock` is a single fixed shape with its
 * own 900-character cap (it shows the next step, not a list of steps), so there is no count to cut.
 */
internal object ContextBlockBudget {

    /** Share of the budget the always-on profile block may take. */
    const val PROFILE_PERCENT = 8L

    /** Share of the budget the automatic recall block may take. */
    const val MEMORY_PERCENT = 12L

    /**
     * Absolute ceiling for the recall block, independent of the window (MEMORY_TASKS §2). On a large
     * window 12 % would allow thousands of tokens of memories to crowd out the live dialogue, so the
     * block is additionally capped at this many tokens.
     */
    const val MAX_MEMORY_BLOCK_TOKENS = 3_000L

    /** Share of the budget the personal-base block may take. */
    const val PEOPLE_PERCENT = 6L

    /** Share of the budget the device summary and the device-fact cache may take (each). */
    const val DEVICE_PERCENT = 4L

    /** Share of the budget the MCP-server line may take. */
    const val MCP_PERCENT = 2L

    /**
     * Returns [blocks] with the budgeted blocks trimmed to their share of [budgetTokens]. A
     * non-positive budget (e.g. an unconfigured test) leaves every block untouched.
     */
    fun apply(blocks: ContextBlockBuilder.Blocks, budgetTokens: Long): ContextBlockBuilder.Blocks {
        if (budgetTokens <= 0L) return blocks
        return blocks.copy(
            memoryBlock = trimItems(
                blocks.memoryBlock,
                minOf(share(budgetTokens, MEMORY_PERCENT), MAX_MEMORY_BLOCK_TOKENS),
                isMemoryItem
            ),
            profileBlock = trimItems(
                blocks.profileBlock,
                share(budgetTokens, PROFILE_PERCENT),
                isProfileItem
            ),
            peopleBlock = trimItems(
                blocks.peopleBlock,
                share(budgetTokens, PEOPLE_PERCENT),
                isPersonItem
            ),
            deviceSummary = dropIfOver(blocks.deviceSummary, share(budgetTokens, DEVICE_PERCENT)),
            deviceFactsBlock = dropIfOver(
                blocks.deviceFactsBlock,
                share(budgetTokens, DEVICE_PERCENT)
            ),
            mcpBlock = dropIfOver(blocks.mcpBlock, share(budgetTokens, MCP_PERCENT))
        )
    }

    /**
     * Keeps the block's header and the first items that fit [capTokens], dropping whole trailing
     * items. When anything is dropped an honest one-line note states how many entries are hidden;
     * when even the header does not fit, the block is dropped entirely (a header without items is
     * noise, not information).
     */
    private fun trimItems(
        block: String,
        capTokens: Long,
        isItem: (String) -> Boolean
    ): String {
        if (block.isBlank()) return ""
        val lines = block.split('\n')
        val firstItem = lines.indexOfFirst(isItem)
        if (firstItem < 0) return dropIfOver(block, capTokens)

        val header = lines.subList(0, firstItem)
        val rest = lines.drop(firstItem)
        val items = rest.filter(isItem)
        val trailer = rest.filterNot(isItem)
        // Reserve the room the honest "N hidden" note will take, so the finished block — note
        // included — never exceeds the cap it was given.
        val noteReserve = LlmTokenEstimator.estimate("\n… ещё ${items.size} записей не показано.")
        val cap = (capTokens - noteReserve).coerceAtLeast(0L)
        if (header.isNotEmpty() && LlmTokenEstimator.estimate(header.joinToString("\n")) >= cap) {
            return ""
        }

        val kept = ArrayList<String>(items.size)
        for (item in items) {
            val candidate = (header + kept + item).joinToString("\n")
            if (kept.isNotEmpty() && LlmTokenEstimator.estimate(candidate) > cap) break
            kept += item
        }
        if (kept.isEmpty()) return ""

        val dropped = items.size - kept.size
        return buildString {
            append((header + kept).joinToString("\n"))
            if (trailer.isNotEmpty()) append('\n').append(trailer.joinToString("\n"))
            if (dropped > 0) {
                append("\n… ещё ").append(dropped).append(" записей не показано.")
            }
        }
    }

    /** Keeps [block] only when it fits [capTokens]; these blocks are small and have no item list. */
    private fun dropIfOver(block: String, capTokens: Long): String =
        if (block.isBlank() || LlmTokenEstimator.estimate(block) > capTokens) "" else block

    private fun share(budgetTokens: Long, percent: Long): Long = budgetTokens * percent / 100

    /** A recalled memory renders as `- [id] text (важность N…)`. */
    private val isMemoryItem: (String) -> Boolean = { it.startsWith("- [") }

    /** A profile fact renders as `- [id] text`. */
    private val isProfileItem: (String) -> Boolean = { it.startsWith("- [") }

    /** A person renders as `- #id Имя …`. */
    private val isPersonItem: (String) -> Boolean = { it.startsWith("- #") }
}
