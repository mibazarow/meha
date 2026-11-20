package com.andharness.ru.data.agent

import com.andharness.ru.domain.locale.AppLanguage

/**
 * Renders the SYSTEM message and the volatile "runtime tail" of one turn.
 *
 * The SYSTEM message is now **static and byte-exact**: it is exactly [SystemPrompts.BASE_SYSTEM_PROMPT],
 * which is byte-identical to the training prompt, so the on-device model's prompt prefix is stable
 * across turns and its KV cache can be reused. Everything that used to be concatenated after the
 * base by the former `buildCloud(...)` — the current time, the chat id, the device context, the
 * device-fact cache, people, trusted sources, the active objective, the profile, MCP and the recalled
 * memory — plus the rolling summary is rendered by [buildRuntimeTail] and attached to the LAST user
 * message by [ContextManagerImpl] (compile-time only; never persisted).
 *
 * Pure (no Android deps): the block order, headers, separators and wording are the wire-level
 * contract and are pinned by the prompt tests.
 */
internal object SystemPromptBuilder {

    /**
     * The STATIC system message: the base prompt for [language], byte-identical to the training
     * prompt of that language. Russian ([AppLanguage.DEFAULT]) is the default and returns exactly
     * [SystemPrompts.BASE_SYSTEM_PROMPT] (sha256
     * `44389455dc6d8672548ea3416dcb53196c16256213eb14030a50b0ace0b76edb`), so the legacy/RU path is
     * unchanged; English ([AppLanguage.ENGLISH]) returns [SystemPrompts.BASE_SYSTEM_PROMPT_EN]. Any
     * other/blank tag normalizes to Russian ([AppLanguage.normalize]).
     *
     * It carries NO volatile data (no time, no free space, no chat id) so it never changes between
     * turns. It carries NO hardcoded reply-language directive either: the reply language is injected
     * via the runtime tail, so the static prefix is identical for the cloud and the local model and
     * stays byte-stable across a language switch. Pinned by `systemPromptSnapshotIsByteStable`.
     */
    fun buildStatic(language: String = AppLanguage.DEFAULT): String =
        if (AppLanguage.normalize(language) == AppLanguage.ENGLISH) {
            SystemPrompts.BASE_SYSTEM_PROMPT_EN
        } else {
            SystemPrompts.BASE_SYSTEM_PROMPT
        }

    /**
     * The volatile tail of one turn: every dynamic block formerly injected into the cloud system
     * prompt, in the exact original order, plus the rolling summary. Reuses [ContextBlockBuilder]'s
     * pre-rendered block strings; blank blocks are omitted, blank summaries render nothing.
     *
     * The REPLY-LANGUAGE directive is prepended here — NOT in the static head — so the language can
     * change per settings/UI without touching the byte-stable static prefix (local KV cache) and so
     * both the cloud and the on-device model receive it. It is derived from [language], the app's
     * UI/`LlmSettings.language` tag, and defaults to Russian.
     */
    fun buildRuntimeTail(
        blocks: ContextBlockBuilder.Blocks,
        summaryText: String? = null,
        language: String = AppLanguage.DEFAULT
    ): String {
        val parts = ArrayList<String>(13)
        parts += replyLanguageDirective(language)
        if (blocks.nowBlock.isNotBlank()) parts += blocks.nowBlock
        // The id of THIS chat, so «этот чат» is unambiguous without asking the user.
        if (blocks.currentChatBlock.isNotBlank()) parts += blocks.currentChatBlock
        // Dropped whole when it does not fit its own budget (ContextBlockBudget): an empty
        // "DEVICE CONTEXT:" header with nothing under it is noise, so skip the part entirely.
        if (blocks.deviceSummary.isNotBlank()) {
            parts += "DEVICE CONTEXT:\n${blocks.deviceSummary}"
        }
        if (blocks.deviceFactsBlock.isNotBlank()) parts += blocks.deviceFactsBlock
        if (blocks.peopleBlock.isNotBlank()) parts += blocks.peopleBlock
        if (blocks.trustedSourcesBlock.isNotBlank()) parts += blocks.trustedSourcesBlock
        // The ACTIVE objective of THIS chat (checklist §1): injected only when one exists, so
        // unrelated chats are completely unaffected and the agent can RESUME the objective.
        if (blocks.objectiveBlock.isNotBlank()) parts += blocks.objectiveBlock
        if (blocks.profileBlock.isNotBlank()) parts += blocks.profileBlock
        // Learned style: form only, always subordinate to the rules above. Placed after the profile
        // so identity facts and style read together, before recall/memory evidence.
        if (blocks.styleBlock.isNotBlank()) parts += blocks.styleBlock
        if (blocks.mcpBlock.isNotBlank()) parts += blocks.mcpBlock
        // A recall question gets an explicit "call the meaning-based tools first" nudge. It goes
        // right before the injected memories so both the instruction and the evidence sit together.
        if (blocks.recallHintBlock.isNotBlank()) parts += blocks.recallHintBlock
        if (blocks.memoryBlock.isNotBlank()) {
            parts += "KNOWN MEMORY ABOUT THE USER (from earlier sessions; use only if relevant):\n" +
                blocks.memoryBlock
        }
        if (!summaryText.isNullOrBlank()) parts += summaryText
        return parts.joinToString("\n\n")
    }

    /**
     * The reply-language directive for [language] (a BCP-47 tag such as "ru", "en" or "en-US"),
     * telling the model which language to answer the user in regardless of its internal reasoning
     * language. Unknown/blank values normalize to Russian (the app default, [AppLanguage.DEFAULT]).
     */
    fun replyLanguageDirective(language: String?): String =
        "REPLY LANGUAGE: Always respond to the user in " +
            "${replyLanguageName(language)}, regardless of internal reasoning language."

    /** Maps a language tag to the display name used in the directive ("English" | "Russian"). */
    fun replyLanguageName(language: String?): String =
        if (AppLanguage.normalize(language) == AppLanguage.ENGLISH) "English" else "Russian"
}
