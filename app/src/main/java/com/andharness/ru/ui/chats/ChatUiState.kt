package com.andharness.ru.ui.chats

import com.andharness.ru.data.db.entity.MessageEntity
import com.andharness.ru.domain.agent.ToolActivity
import com.andharness.ru.domain.chat.ChatMessageMatch
import com.andharness.ru.domain.ui.UiRequest

data class ChatUiState(
    val title: String = "",
    val messages: List<MessageEntity> = emptyList(),
    val input: String = "",
    val isStreaming: Boolean = false,
    val error: String? = null,
    val pendingPermission: String? = null,
    val uiRequest: UiRequest? = null,
    /** The tool the agent is currently driving, for the single updating status line (§17). */
    val toolActivity: ToolActivity? = null,
    /** True while the request is being prepared with a compacted context (transient status line). */
    val contextCompaction: Boolean = false,
    /**
     * True when the provider has not produced any event for a while. Drives the transient
     * «Провайдер долго не отвечает — проверьте баланс/ключ» hint; cleared on the next event.
     */
    val providerSlow: Boolean = false,
    /** True while the provider is producing internal reasoning; the UI shows «Меха думает…». */
    val providerThinking: Boolean = false,
    /** Estimated tokens of the last compiled request and the budget, for «использовано X / N». */
    val contextUsedTokens: Long? = null,
    val contextBudgetTokens: Long? = null,
    /** Attachments staged for the next message (§19). */
    val attachments: List<PendingAttachment> = emptyList(),
    /** Honest per-attachment status message (limit exceeded, load failure) shown under the input. */
    val attachmentNotice: String? = null,
    /**
     * Id of the assistant message the current run is writing. While it is set, that message is
     * in-flight. The persisted row is hidden from the transcript and its live text is rendered by
     * the streaming bubble instead, driven by [streamingText] (smoothed to a typewriter reveal).
     * Once the turn completes this id clears, the overlay disappears and the finished message is
     * shown from the database exactly as the model produced it.
     */
    val streamingMessageId: Long? = null,
    /**
     * In-flight assistant text of the current run, published on a throttle by the orchestrator. The
     * UI buffers this target and reveals it at a steady cadence (see `rememberStreamingReveal`), so
     * the answer prints smoothly instead of jumping one throttled chunk at a time.
     */
    val streamingText: String? = null,
    /**
     * Titles of the BACKGROUND runs (long objectives) the agent is working on in other chats right
     * now, for the honest «В фоне: …» line. Empty when nothing runs in the background.
     */
    val backgroundRuns: List<String> = emptyList(),
    /** True while THIS chat's request is accepted but waiting for a free run slot («в очереди»). */
    val queuedInLine: Boolean = false,
    /**
     * True while the in-chat search bar is open (redesign §4 «Экран чата», phase 9). While it is
     * set the screen shows the in-chat search bar under the header and the message matches instead
     * of the normal transcript + composer.
     */
    val isSearchActive: Boolean = false,
    /** Raw text of the in-chat search field. Blank means «no search» and yields no matches. */
    val searchQuery: String = "",
    /** Message matches for [searchQuery] within THIS chat only, best first. */
    val searchMatches: List<ChatMessageMatch> = emptyList(),
    /** True while the debounced in-chat search is in flight (suppresses «Ничего не найдено»). */
    val isSearching: Boolean = false
)
