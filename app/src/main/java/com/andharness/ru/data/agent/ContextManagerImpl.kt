package com.andharness.ru.data.agent

import android.util.Log
import com.andharness.ru.data.db.dao.MemoryPersonDao
import com.andharness.ru.data.db.entity.MessageEntity
import com.andharness.ru.data.mcp.McpServerStore
import com.andharness.ru.data.objective.ObjectiveStore
import com.andharness.ru.data.trust.TrustedSourceStore
import com.andharness.ru.data.profile.GlobalProfileStore
import com.andharness.ru.data.style.StyleProfileStore
import com.andharness.ru.domain.assist.AssistRepository
import com.andharness.ru.domain.device.DeviceInfoProvider
import com.andharness.ru.domain.agent.ContextManager
import com.andharness.ru.domain.chat.ChatRepository
import com.andharness.ru.domain.agent.CompiledContext
import com.andharness.ru.domain.agent.FactExtractor
import com.andharness.ru.domain.agent.LlmMessage
import com.andharness.ru.domain.agent.LlmTool
import com.andharness.ru.domain.common.Hashing
import com.andharness.ru.domain.common.TimeProvider
import com.andharness.ru.domain.memory.DeviceFactRepository
import com.andharness.ru.domain.person.InteractionRepository
import com.andharness.ru.domain.person.PersonRepository
import com.andharness.ru.domain.settings.LlmSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Andharness"


@Singleton
class ContextManagerImpl @Inject constructor(
    private val repository: ChatRepository,
    private val settingsStore: LlmSettingsStore,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val assistRepository: AssistRepository,
    private val factExtractor: FactExtractor,
    private val deviceFactRepository: DeviceFactRepository,
    private val personRepository: PersonRepository,
    private val interactionRepository: InteractionRepository,
    private val timeProvider: TimeProvider,
    /**
     * Optional `memory ↔ person` link DAO used to surface the people behind the recalled memories.
     * `null` (the default, e.g. in tests) disables the enrichment and keeps the behaviour exactly
     * as before. Best-effort: a read failure never breaks the prompt.
     */
    private val memoryPersonDao: MemoryPersonDao? = null,
    /**
     * Allowlist of external sources whose text may command the agent without confirmation. `null`
     * (the default, e.g. in tests) renders the empty block «ДОВЕРЕННЫЕ ИСТОЧНИКИ: нет». Best-effort:
     * a store read failure never breaks the prompt.
     */
    private val trustedSourceStore: TrustedSourceStore? = null,
    /**
     * Always-on PROFILE of facts the user explicitly saved for all chats (shared cross-chat context).
     * Unlike the lexical recall-gated memory block, a non-empty profile is injected into EVERY chat's
     * prompt, so a saved name/preference is always available. `null` (the default, e.g. in tests)
     * injects nothing. Best-effort: a store read failure never breaks the prompt.
     */
    private val globalProfileStore: GlobalProfileStore? = null,
    /**
     * Registry of MCP servers the user configured from chat. `null` (the default, e.g. in tests)
     * injects nothing; when wired, a compact line tells the agent it can add/list/test/remove MCP
     * servers from chat and that their tools appear after a successful connection. Best-effort: a
     * store read failure never breaks the prompt.
     */
    private val mcpServerStore: McpServerStore? = null,
    /**
     * Persistent long-running OBJECTIVES (checklist §1). `null` (the default, e.g. in tests) injects
     * nothing. When wired, the ACTIVE objective of the current chat is rendered as a compact
     * `АКТИВНАЯ ЦЕЛЬ` resume block; a chat without an ACTIVE objective is unaffected. Best-effort: a
     * store read failure never breaks the prompt.
     */
    private val objectiveStore: ObjectiveStore? = null,
    /**
     * Learned communication style (form only). `null` (e.g. in tests) injects nothing; a store read
     * failure never breaks the prompt. Wired as a singleton so the observer and the prompt share
     * one source of truth.
     */
    private val styleProfileStore: StyleProfileStore? = null,
    /**
     * Best-effort LLM style-card updater, run on compaction next to [factExtractor]. `null` (e.g. in
     * tests) disables it; any failure is logged and ignored.
     */
    private val styleCardExtractor: StyleCardExtractor? = null
) : ContextManager {

    /** Builds all non-history prompt blocks (recall/device/facts/people/trusted/profile/mcp/time). */
    private val contextBlocks = ContextBlockBuilder(
        assistRepository = assistRepository,
        deviceInfoProvider = deviceInfoProvider,
        deviceFactRepository = deviceFactRepository,
        personRepository = personRepository,
        interactionRepository = interactionRepository,
        timeProvider = timeProvider,
        memoryPersonDao = memoryPersonDao,
        trustedSourceStore = trustedSourceStore,
        globalProfileStore = globalProfileStore,
        mcpServerStore = mcpServerStore,
        objectiveStore = objectiveStore,
        styleProfileStore = styleProfileStore
    )

    override suspend fun compile(chatId: Long, tools: List<LlmTool>): CompiledContext {
        val settings = settingsStore.config.first()

        val chat = repository.observeChat(chatId).first()
        val existingSummary = chat?.summary?.takeIf { it.isNotBlank() }
        // Messages already folded into the persisted summary are NOT replayed in full: the summary
        // stands in for them. This is what makes compaction incremental — the whole history is never
        // re-summarised on every request (the rolling summary is persisted and reused).
        val summarizedUpTo = parseSummarizedUpTo(chat?.contextState)

        val allMessages = repository.observeMessages(chatId).first()
            // Tool-role messages are excluded from the LLM context: their `tool_call_id` and the
            // assistant's `tool_calls` array are not persisted, so replaying a lone tool message
            // across turns yields an OpenAI-protocol-invalid and model-confusing history (a stray
            // tool message sitting after the assistant's final answer). The assistant's final text
            // already reports the outcome of the previous turn, so the raw tool log is not needed
            // for the model to continue correctly.
            .filter { it.content.isNotBlank() && it.role != "tool" }
        // No boundary yet → nothing has been folded into the summary, so every message is replayed.
        val history =
            if (summarizedUpTo <= 0L) allMessages else allMessages.filter { it.id > summarizedUpTo }

        // One read of every reference block per turn, then the per-block budget is applied to that
        // single snapshot (CONTEXT_TASKS_V2 §2): a runaway profile/people/memory block must never
        // force the DIALOGUE to be compacted.
        val rawBlocks = contextBlocks.build(allMessages, chatId)
        val blocks = ContextBlockBudget.apply(
            rawBlocks,
            ContextBudget.budgetFor(settings.contextCompressionTokens)
        )
        if (blocks != rawBlocks) {
            Log.d(TAG, "reference blocks trimmed to the per-block budget")
        }

        // The SYSTEM message is STATIC and byte-exact per language (the configured UI language,
        // `LlmSettings.language`), so the prefix never changes between turns for a given language.
        // Every volatile block moves into the
        // runtime tail attached to the LAST user message below — compile-time only, never persisted.
        val systemMessage = LlmMessage(
            role = "system",
            content = SystemPromptBuilder.buildStatic(settings.language)
        )

        val cleanHistory = history.map { it.toLlmMessage() }
        // The tail carries the CURRENT (persisted) summary; it is rebuilt with the merged summary
        // after compaction.
        val initialTail = SystemPromptBuilder.buildRuntimeTail(
            blocks,
            existingSummary?.let { summaryText(it) },
            language = settings.language
        )

        val messages = buildList {
            add(systemMessage)
            addAll(attachRuntimeTail(cleanHistory, initialTail))
        }
        // Debug breadcrumb for the prefix-cache contract (CONTEXT_TASKS_V2 §1/§3): the hash of
        // everything the provider can reuse (all messages before the last one) plus the hash of the
        // volatile tail. Two turns over an unchanged history at the same minute must log the same
        // prefix hash; if they differ, a volatile block leaked into the prefix and every turn pays a
        // full re-prefill.
        Log.d(
            TAG,
            "prompt prefix=${promptHash(messages.dropLast(1))} tail=${promptHash(initialTail)}"
        )

        var estimatedTokens = LlmTokenEstimator.estimate(messages, tools)

        // Budget source is the user's configured context-compression token setting; compaction
        // triggers at 80% of it and trims back to 60%.
        val budget = ContextBudget.budgetFor(settings.contextCompressionTokens)
        val trigger = ContextBudget.triggerFor(settings.contextCompressionTokens)
        if (ContextBudget.shouldCompactAt(estimatedTokens, trigger)) {
            // Tokens that compaction can never remove: the static system prompt, the runtime tail
            // (attached to the always-kept current user turn) and the tool schemas. The rest
            // (history) is trimmed until the request fits the target.
            val fixedTokens = LlmTokenEstimator.estimate(systemMessage.content.orEmpty()) +
                LlmTokenEstimator.estimate(initialTail) +
                LlmTokenEstimator.estimate(tools)
            val result = compress(
                history = cleanHistory,
                existingSummary = existingSummary,
                fixedTokens = fixedTokens,
                target = ContextBudget.targetFor(settings.contextCompressionTokens)
            )
            if (result.droppedCount > 0) {
                // BEFORE the dropped turns are folded away, run one best-effort,
                // hard-bounded extraction pass that persists durable facts into long-term memory.
                // Any failure (no LLM, network, parse) is logged and ignored so compaction can
                // never be blocked or fail; a genuine cancellation of this turn still propagates.
                try {
                    factExtractor.extractAndSave(
                        history.take(result.droppedCount).map { it.toLlmMessage() }
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Exception) {
                    Log.w(TAG, "Авто-извлечение фактов перед сжатием не удалось: ${e.message}")
                }
                // Nuances counters cannot infer (humor, irony, anglicisms) come from one bounded
                // style-card pass. Like facts, it is cheap, cached and never blocks compaction.
                try {
                    styleCardExtractor?.updateIfDue(
                        history.take(result.droppedCount).map { it.toLlmMessage() }
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Exception) {
                    Log.w(TAG, "Обновление стиль-карточки не удалось: ${e.message}")
                }
                // The rolling summary is persisted PER CHAT and reused: only the newly dropped
                // messages are appended to it, never the whole history recomputed.
                repository.updateSummary(chatId, result.summary)
                history.getOrNull(result.droppedCount - 1)?.id?.let { lastDroppedId ->
                    if (lastDroppedId > summarizedUpTo) {
                        repository.updateContextState(chatId, summarizedUpToString(lastDroppedId))
                    }
                }
            }
            // The reused summary still stands in for the already-folded turns, even when this pass
            // dropped nothing new (the fixed part alone already exceeds the target). It rides in the
            // runtime tail of the always-kept last user turn.
            val finalTail = SystemPromptBuilder.buildRuntimeTail(
                blocks,
                summaryText(result.summary),
                language = settings.language
            )
            val compressedMessages = buildList {
                add(systemMessage)
                addAll(attachRuntimeTail(result.kept, finalTail))
            }
            estimatedTokens = LlmTokenEstimator.estimate(compressedMessages, tools)
            if (estimatedTokens > budget) {
                // Hard pre-send budget check on the FULL request (messages + tool schemas). We do
                // not drop tools here (that would silently reduce capabilities) — warn honestly.
                Log.w(
                    TAG,
                    "Контекст запроса (~$estimatedTokens токенов) превышает бюджет " +
                        "($budget) даже после сжатия — отправляем как есть."
                )
            }
            return CompiledContext(
                messages = compressedMessages,
                estimatedTokens = estimatedTokens,
                compressed = true
            )
        }

        return CompiledContext(
            messages = messages,
            estimatedTokens = estimatedTokens,
            compressed = false
        )
    }

    /** The persisted summary is model context only — it is never rendered or persisted as a chat message. */
    private fun summaryText(summary: String) = "$SUMMARY_PREFIX$summary"

    /**
     * Returns [history] with the volatile [tail] prepended to its LAST user message, wrapped in a
     * clearly delimited runtime-context preamble. This is a compile-time, in-memory change only: the
     * returned messages are never persisted, so the next turn re-reads the clean, stored user text.
     *
     * The tail must never be lost (it carries the rolling summary and the live device/time context),
     * so when a compile has no user message at all (a compacted assistant-only history, or an empty
     * history) it is attached to the LAST message of any role, or emitted as a synthetic user message
     * when the history is empty. In the real turn flow the current user request is always present, so
     * this is only a safety net.
     */
    private fun attachRuntimeTail(history: List<LlmMessage>, tail: String): List<LlmMessage> {
        if (tail.isBlank()) return history
        val wrapped = "$RUNTIME_OPEN\n$tail\n$RUNTIME_CLOSE"
        val index = history.indexOfLast { it.role == "user" }
            .let { if (it >= 0) it else history.lastIndex }
        if (index < 0) return listOf(LlmMessage(role = "user", content = wrapped))
        val message = history[index]
        val merged = "$wrapped\n\n$USER_TEXT_SEPARATOR\n${message.content.orEmpty()}"
        return history.toMutableList().also { it[index] = message.copy(content = merged) }
    }

    /**
     * Short stable fingerprint of a message list or a block, used only by the debug breadcrumb. The
     * separator bytes cannot occur in rendered prompt text, so two different shapes never collide
     * by concatenation ambiguity.
     */
    private fun promptHash(messages: List<LlmMessage>): String =
        Hashing.sha256Hex(
            messages.joinToString("\u0000") { "${it.role}\u0001${it.content.orEmpty()}" }
        ).take(Hashing.FINGERPRINT_HEX_LENGTH)

    /** [promptHash] for a single rendered block. */
    private fun promptHash(block: String): String =
        Hashing.sha256Hex(block).take(Hashing.FINGERPRINT_HEX_LENGTH)

    private fun parseSummarizedUpTo(state: String?): Long {
        if (state.isNullOrBlank()) return 0L
        return state.substringAfterLast('=', "").trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L
    }

    private fun summarizedUpToString(id: Long) = "summarizedUpTo=$id"

    private fun MessageEntity.toLlmMessage() = LlmMessage(
        role = role,
        content = content
    )

    private data class CompressionResult(
        val summary: String,
        val kept: List<LlmMessage>,
        val droppedCount: Int
    )

    /**
     * Drops the oldest history messages until the request ([fixedTokens] + remaining history) fits
     * [target], and folds them into the rolling summary. [fixedTokens] covers everything compaction
     * cannot remove (system prompt + reused summary + tool schemas).
     */
    private fun compress(
        history: List<LlmMessage>,
        existingSummary: String?,
        fixedTokens: Long,
        target: Long
    ): CompressionResult {
        if (history.isEmpty()) {
            return CompressionResult(existingSummary.orEmpty(), emptyList(), droppedCount = 0)
        }

        var remainingTokens = LlmTokenEstimator.estimate(history)
        var dropIndex = 0
        while (dropIndex < history.size && fixedTokens + remainingTokens > target) {
            remainingTokens -= LlmTokenEstimator.estimate(listOf(history[dropIndex]))
            dropIndex++
        }

        // The newest message is the current turn (the user's just-sent request). Even when the fixed
        // part alone exceeds the target (a budget smaller than the system prompt / tool schemas),
        // folding the current request into the summary would ask the model to answer a question it
        // never saw — so the tail user turn is always kept verbatim ("last N messages entirely").
        if (history.last().role == "user") {
            dropIndex = minOf(dropIndex, history.size - 1)
        }

        val dropped = history.subList(0, dropIndex)
        val kept = history.subList(dropIndex, history.size)
        return CompressionResult(
            summary = mergeSummary(existingSummary, dropped),
            kept = kept,
            droppedCount = dropped.size
        )
    }

    /**
     * Rolling, incremental summary: the previously persisted text is kept verbatim and only the
     * newly dropped turns are appended as a compact digest. The result is length-capped so the
     * summary itself can never grow without bound.
     */
    private fun mergeSummary(existing: String?, dropped: List<LlmMessage>): String {
        if (dropped.isEmpty()) return existing.orEmpty()
        val digest = dropped.joinToString("\n") { message ->
            val speaker = if (message.role == "user") "Пользователь" else "Агент"
            val text = message.content.orEmpty().replace('\n', ' ').trim()
            val preview = if (text.length > SUMMARY_PREVIEW_CHARS) {
                text.take(SUMMARY_PREVIEW_CHARS) + "…"
            } else {
                text
            }
            "- $speaker: $preview"
        }
        val merged = buildString {
            if (!existing.isNullOrBlank()) {
                append(existing.trim())
                append('\n')
            }
            append(digest)
        }.trim()
        return if (merged.length <= MAX_SUMMARY_CHARS) merged else "…\n" + merged.takeLast(MAX_SUMMARY_CHARS)
    }

    private companion object {
        /** Prefix of the synthetic summary message; kept stable for the model, never user-visible. */
        const val SUMMARY_PREFIX = "Краткое изложение предыдущего диалога:\n"

        /** Runtime-context wrapper placed around the volatile tail of the last user message. */
        const val RUNTIME_OPEN = "<runtime>"
        const val RUNTIME_CLOSE = "</runtime>"
        const val USER_TEXT_SEPARATOR = "---"

        /** Max characters kept per dropped message in the running summary digest. */
        const val SUMMARY_PREVIEW_CHARS = 200

        /** Hard cap of the persisted rolling summary. */
        const val MAX_SUMMARY_CHARS = 4_000
    }
}
