package com.andharness.ru.ui.chats

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andharness.ru.data.attachments.AttachmentStore
import com.andharness.ru.domain.agent.AgentOrchestrator
import com.andharness.ru.domain.agent.AgentRunPriority
import com.andharness.ru.domain.agent.AgentState
import com.andharness.ru.domain.chat.ChatRepository
import com.andharness.ru.domain.chat.ChatSearch
import com.andharness.ru.domain.chat.ChatTitleGenerator
import com.andharness.ru.domain.permission.PermissionManager
import com.andharness.ru.domain.ui.ActiveChatTracker
import com.andharness.ru.domain.ui.UiInteractionBridge
import com.andharness.ru.domain.ui.UiResult
import com.andharness.ru.inbound.InboundSeedStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * ViewModel of one chat screen. It owns the observable [ChatUiState] through a [ChatStateHolder]
 * and delegates each responsibility to a focused collaborator: [ChatVoiceController] (per-message
 * TTS), [ChatAttachmentController] (attachment staging), [InboundSeedConsumer]
 * (inbound share/process-text seeds), [ChatSendCoordinator] (the agent turn) and
 * [ChatToolApprovalController] (run approval + UI-bridge results). The public members below are the
 * thin, source-compatible delegation surface the screen observes.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    repository: ChatRepository,
    titleGenerator: ChatTitleGenerator,
    orchestrator: AgentOrchestrator,
    permissionManager: PermissionManager,
    uiBridge: UiInteractionBridge,
    attachmentStore: AttachmentStore,
    // Seed left by an inbound entry point for this chat (update.md idea #10), if any.
    inboundSeedStore: InboundSeedStore,
    // In-chat search over THIS chat's own messages (redesign §4 «Экран чата», phase 9).
    private val chatSearch: ChatSearch,
    @ApplicationContext appContext: Context,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val chatId: Long = savedStateHandle["chatId"] ?: 0L

    private val stateHolder = ChatStateHolder()
    val uiState: StateFlow<ChatUiState> = stateHolder.state

    /**
     * The in-flight debounced in-chat search. Replaced (the previous one cancelled) on every
     * keystroke, so only the most recent query ever reaches [ChatSearch.searchInChat]; a blank
     * query or closing the search bar cancels it outright.
     */
    private var searchJob: Job? = null

    // --- Responsibility-specific collaborators ----------------------------------------------
    private val voice: ChatVoiceController = ChatVoiceController(
        appContext = appContext,
        state = stateHolder
    )

    private val attachments = ChatAttachmentController(
        state = stateHolder,
        scope = viewModelScope,
        attachmentStore = attachmentStore
    )

    private val inboundSeedConsumer = InboundSeedConsumer(
        inboundSeedStore = inboundSeedStore,
        attachmentStore = attachmentStore
    )

    private val sendCoordinator: ChatSendCoordinator = ChatSendCoordinator(
        chatId = chatId,
        state = stateHolder,
        scope = viewModelScope,
        orchestrator = orchestrator,
        attachmentStore = attachmentStore,
        titleGenerator = titleGenerator
    )

    private val toolApproval = ChatToolApprovalController(
        chatId = chatId,
        scope = viewModelScope,
        orchestrator = orchestrator,
        permissionManager = permissionManager,
        uiBridge = uiBridge
    )

    init {
        // update.md bug #5: this ViewModel lives exactly as long as the chat screen is open, so it is
        // the reliable "which chat is the user looking at" publisher for the notification gate.
        if (chatId > 0L) ActiveChatTracker.onChatOpened(chatId)
        viewModelScope.launch {
            repository.observeChat(chatId).collect { chat ->
                stateHolder.update { it.copy(title = chat?.title.orEmpty()) }
            }
        }
        viewModelScope.launch {
            repository.observeMessages(chatId).collect { messages ->
                stateHolder.update { state ->
                    state.copy(
                        messages = messages,
                        error = null
                    )
                }
            }
        }
        viewModelScope.launch {
            orchestrator.statusFor(chatId).collect { status ->
                // Context isolation (per chat): with concurrent per-chat runs `statusFor(chatId)`
                // returns THIS chat's own status flow, but the run's fields still describe exactly
                // one chat. Every per-run field is gated on the run's chat id, so a chat never shows
                // another chat's streaming text, tool activity, compaction notice or numbers. Only
                // the compiled request's measurement and any error/result belong to the chat where
                // the run actually happened.
                val forThisChat = status.chatId == chatId
                val streaming = forThisChat && when (status.state) {
                    AgentState.RUNNING, AgentState.TOOL_EXEC,
                    AgentState.WAITING_PERMISSION, AgentState.QUEUED -> true
                    else -> false
                }
                val error = if (forThisChat && status.state == AgentState.ERROR) status.toolLog else null
                val streamingMessageId = status.streamingMessageId.takeIf { forThisChat }
                val streamingText = status.streamingText.takeIf { forThisChat }
                stateHolder.update { state ->
                    state.copy(
                        isStreaming = streaming,
                        error = error,
                        pendingPermission = status.pendingPermission.takeIf { forThisChat },
                        toolActivity = status.toolActivity.takeIf { forThisChat },
                        contextCompaction = status.contextCompaction && forThisChat,
                        providerSlow = status.providerSlow && forThisChat,
                        providerThinking = status.providerThinking && forThisChat,
                        // The usage measurement belongs to the chat it was taken in; never show
                        // another chat's numbers after switching chats.
                        contextUsedTokens = status.contextUsedTokens.takeIf { forThisChat },
                        contextBudgetTokens = status.contextBudgetTokens.takeIf { forThisChat },
                        streamingMessageId = streamingMessageId,
                        streamingText = streamingText
                    )
                }
            }
        }
        viewModelScope.launch {
            // Concurrent-run visibility (SPEC §20): show what the agent is doing in OTHER chats
            // (background objectives) and whether THIS chat's request is waiting for a slot, so the
            // user never sees a silent BUSY while a background task holds the agent.
            orchestrator.activeRuns.collect { runs ->
                val background = runs
                    .filter { it.kind == AgentRunPriority.BACKGROUND }
                    .map { it.title }
                    .filter { it.isNotBlank() }
                val queuedForThisChat = runs.any { it.chatId == chatId && it.queued }
                stateHolder.update {
                    it.copy(backgroundRuns = background, queuedInLine = queuedForThisChat)
                }
            }
        }
        viewModelScope.launch {
            uiBridge.pending.collect { request ->
                stateHolder.update { it.copy(uiRequest = request) }
            }
        }
        viewModelScope.launch {
            // Inbound entry point prefill (update.md idea #10): the seed is consumed exactly once;
            // a plain text share is sent right away, everything else is only staged.
            val seed = inboundSeedConsumer.consume(chatId) ?: return@launch
            stateHolder.update {
                it.copy(
                    input = seed.text,
                    attachments = seed.attachments,
                    attachmentNotice = seed.notice
                )
            }
            if (seed.autoRun) send()
        }
    }

    fun onInputChange(value: String) {
        stateHolder.update { it.copy(input = value, error = null) }
    }

    // --- In-chat search (redesign §4 «Экран чата», phase 9) ----------------------------------

    /**
     * Toggles the in-chat search bar. Opening only flips the flag (an empty query searches
     * nothing); closing clears the query and any matches so the next open starts fresh.
     */
    fun toggleSearch() {
        if (stateHolder.current.isSearchActive) closeSearch() else openSearch()
    }

    private fun openSearch() {
        stateHolder.update { it.copy(isSearchActive = true) }
    }

    /** Exits search mode: cancels any pending search and drops the query and matches. */
    fun closeSearch() {
        searchJob?.cancel()
        searchJob = null
        stateHolder.update {
            it.copy(
                isSearchActive = false,
                searchQuery = "",
                searchMatches = emptyList(),
                isSearching = false
            )
        }
    }

    /**
     * Updates the in-chat query and (re)starts the debounced search scoped to THIS chat. Blank input
     * clears the matches and cancels any pending search; a non-blank query is debounced by
     * [SEARCH_DEBOUNCE_MS] and the previous job is cancelled first, so a burst of keystrokes issues
     * exactly one [ChatSearch.searchInChat] call — for the final query only.
     */
    fun onSearchQueryChange(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            searchJob = null
            stateHolder.update {
                it.copy(searchQuery = query, searchMatches = emptyList(), isSearching = false)
            }
            return
        }
        stateHolder.update { it.copy(searchQuery = query, isSearching = true) }
        val chatId = this.chatId
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val matches = chatSearch.searchInChat(chatId, query, SEARCH_RESULT_LIMIT)
            stateHolder.update { it.copy(searchMatches = matches, isSearching = false) }
        }
    }

    // --- Per-message TTS (redesign §2 phase 5: dictation / hands-free removed) ---------------

    /**
     * Speaks one assistant message on demand (the per-message «Озвучить» action). The engine is
     * created lazily. A missing/unavailable engine is reported honestly through
     * [ChatUiState.error] — never a crash.
     */
    fun speakMessage(text: String) = voice.speakMessage(text)

    /**
     * Called by the screen when it is no longer visible (§21): stop any ongoing per-message TTS so
     * the assistant never keeps talking in the background.
     */
    fun onLeaveForeground() = voice.onLeaveForeground()

    override fun onCleared() {
        if (chatId > 0L) ActiveChatTracker.onChatClosed(chatId)
        voice.destroy()
        super.onCleared()
    }

    // --- §19: attachments ------------------------------------------------------------------

    /** §19: reads the picked file's metadata (+ a small thumbnail for images) and stages it. */
    fun addAttachment(uri: Uri) = attachments.add(uri)

    fun removeAttachment(uri: Uri) = attachments.remove(uri)

    fun clearAttachmentNotice() = attachments.clearNotice()

    /**
     * §19.2: the composer's «Сделать фото» flow finished. A captured photo behaves like a picked
     * image; a cancel or a failed import stages nothing.
     */
    fun onPhotoCaptured(success: Boolean, captureFile: File) =
        attachments.onPhotoCaptured(success, captureFile)

    /** §19.2: honest notice for the camera flow (permission denied, no camera app, no camera). */
    fun onPhotoCaptureNotice(message: String) = attachments.notice(message)

    /**
     * §2 phase 5: honest notice for the composer dictation flow (recognizer unavailable, no speech,
     * missing microphone permission), shown on the same notice line as the attachment notices.
     */
    fun onComposerNotice(message: String) = attachments.notice(message)

    fun send() = sendCoordinator.send()

    // --- Agent run / tool approval ---------------------------------------------------------

    fun stopGeneration() = toolApproval.stopGeneration()

    fun grantPermission() = toolApproval.grantPermission()

    fun denyPermission() = toolApproval.denyPermission()

    fun isPermissionGranted(permission: String): Boolean =
        toolApproval.isPermissionGranted(permission)

    /**
     * §8: after the user returns from a system settings screen, services like accessibility /
     * notification-listener connect slightly after the Activity resumes, so re-check the grant a
     * few times before answering the agent.
     *
     * Runs in [viewModelScope] on purpose: the previous implementation re-checked on a
     * `rememberCoroutineScope`, which is cancelled as soon as the user leaves this screen — the
     * grant/deny was then never submitted and the agent blocked until its 60 s gate timeout. The
     * ViewModel outlives the composition, so the result is always delivered.
     */
    fun awaitPermissionResult(permission: String) = toolApproval.awaitPermissionResult(permission)

    fun isSpecialPermission(permission: String): Boolean =
        toolApproval.isSpecialPermission(permission)

    fun submitUiResult(result: UiResult) = toolApproval.submitUiResult(result)

    fun cancelUiRequest() = toolApproval.cancelUiRequest()

    companion object {
        /**
         * Debounce window for the in-chat search. Matches the chat-list search so both feel equally
         * immediate (Telegram-like); cancelling the prior job is what guarantees only the final
         * query is searched.
         */
        internal const val SEARCH_DEBOUNCE_MS = 275L

        /** Max in-chat search results requested from [ChatSearch] (bounded UI list). */
        internal const val SEARCH_RESULT_LIMIT = 20
    }
}
