package com.andharness.ru.data.agent

import android.content.Context
import android.util.Log
import com.andharness.ru.bg.AgentForegroundService
import com.andharness.ru.bg.OfflineActionWorker
import com.andharness.ru.data.offline.OfflineActionQueue
import com.andharness.ru.domain.agent.ActiveRun
import com.andharness.ru.domain.agent.AgentOrchestrator
import com.andharness.ru.domain.agent.AgentRunOutcome
import com.andharness.ru.domain.agent.AgentRunPriority
import com.andharness.ru.domain.agent.AgentState
import com.andharness.ru.domain.agent.AgentStatus
import com.andharness.ru.domain.agent.AssistantTextSanitizer
import com.andharness.ru.domain.agent.ToolActivity
import com.andharness.ru.domain.agent.ToolActivityState
import com.andharness.ru.domain.agent.ContextManager
import com.andharness.ru.domain.agent.DeviceActionClaimDetector
import com.andharness.ru.domain.agent.FalseSuccessGuard
import com.andharness.ru.domain.agent.LlmClient
import com.andharness.ru.domain.agent.LlmEvent
import com.andharness.ru.domain.agent.LlmContentPart
import com.andharness.ru.domain.agent.LlmMessage
import com.andharness.ru.domain.agent.LlmRequest
import com.andharness.ru.domain.agent.LoopGuard
import com.andharness.ru.domain.agent.PromptTokenCalibration
import com.andharness.ru.domain.agent.RepeatCallDetector
import com.andharness.ru.domain.agent.TextToolCallParser
import com.andharness.ru.domain.agent.NoAttemptGuard
import com.andharness.ru.domain.agent.LlmTool
import com.andharness.ru.domain.agent.LlmToolCall
import com.andharness.ru.domain.agent.RunConcurrencyLimiter
import com.andharness.ru.domain.agent.RunLifecyclePolicy
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.agent.ToolResultEnvelope
import com.andharness.ru.domain.chat.AttachmentPolicy
import com.andharness.ru.domain.chat.ChatAttachment
import com.andharness.ru.domain.chat.ChatRepository
import com.andharness.ru.domain.chat.CurrentChatProvider
import com.andharness.ru.domain.tools.ToolExecutionContext
import com.andharness.ru.domain.tools.ToolRegistry
import com.andharness.ru.data.llm.LlmTimeouts
import com.andharness.ru.data.style.StyleProfileStore
import com.andharness.ru.domain.settings.LlmSettingsStore
import com.andharness.ru.data.vision.VisionCapability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Overall wall-clock backstop for ONE LLM completion (61 min = 3_660_000 ms).
 *
 * Derived from the single source of truth for completion budgets, [LlmTimeouts.MAX_COMPLETION_SEC]
 * (3600 s), plus a 60 s margin, so it can never sit below the LLM layer's own overall cap and
 * pre-empt a legally-long response: the streaming data-wait / no-progress budgets are 1024 s
 * (~17 min) and the client's own overall cap is 3600 s (60 min). It is deliberately NOT a tighter
 * value (the former 11 min would have cut a legal completion at ~660 s < 1024 s): the client's
 * per-delta watchdog normally reports its precise reason first, and this only backstops a stream
 * that keeps trickling tiny progress forever. It stays equal to `RunLifecyclePolicy.RUN_HARD_CAP_MS`.
 *
 * This relationship is asserted (Android-free) by `LlmCompletionCapTest` against `LlmTimeouts`, so
 * the cap can never drift below the LLM completion budget again.
 */
internal const val LLM_COMPLETION_CAP_MS: Long = LlmTimeouts.MAX_COMPLETION_SEC * 1_000L + 60_000L

/**
 * How long ONE in-flight LLM call may stay completely silent before the chat status line switches
 * from «Меха печатает…» to an actionable hint (check balance/limits/key, or just wait). It only
 * changes a transient UI line — it never aborts the call: a slow model is still allowed its full
 * budget ([LlmTimeouts]). Any streamed event clears the hint immediately.
 */
internal const val PROVIDER_SLOW_NOTICE_MS: Long = 45_000L

/** How often the silent-call ticker re-checks the threshold above. */
private const val PROVIDER_SLOW_TICK_MS: Long = 5_000L

/**
 * Multi-run orchestrator. It used to own ONE `_status`/`runJob` and rejected any second turn with
 * `AgentRunOutcome.BUSY`; that made chatting impossible while a background objective was running
 * (the reported bug). It now owns a **per-chat registry** ([runs] + [statusFlows]) and admits up to
 * [RunConcurrencyLimiter] simultaneous runs with interactive priority, a bounded queue and per-chat
 * status/streaming/context/cancel/permission state.
 *
 * Guarantees:
 * - **Interactive is never rejected while a background run is active.** `INTERACTIVE` runs are
 *   admitted whenever `running < limit`; a `BACKGROUND` run reserves capacity (`limit - 1`) so a
 *   chat always has a free slot, and at a single-slot limit a background run yields at its next
 *   checkpoint. If every slot is genuinely busy the chat is QUEUED (never dropped) and started when
 *   a slot frees; `BUSY` is returned only when THIS chat already runs, or the bounded queue is full.
 * - **Per-run isolation.** Status, streaming overlay, tool activity, compaction flags, permission
 *   gate, cancel flag and watchdog are keyed by `chatId`; [statusFor] isolates them per chat.
 * - **Unchanged single-chat behaviour.** For one chat the turn flow, persistence, compaction, tool
 *   loop and watchdog semantics are byte-for-byte the previous implementation.
 *
 * Concurrency model references (official Kotlin coroutines docs):
 * - structured concurrency: https://kotlinlang.org/docs/coroutines-basics.html#structured-concurrency
 * - cancellation is cooperative: https://kotlinlang.org/docs/cancellation-and-timeouts.html
 * - shared mutable state / confinement: https://kotlinlang.org/docs/shared-mutable-state-and-concurrency.html
 * - `Semaphore` (the primitive replaced here by an explicit priority + FIFO policy):
 *   https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.sync/-semaphore/
 */
@Singleton
class AgentOrchestratorImpl @Inject constructor(
    private val llmClient: LlmClient,
    private val contextManager: ContextManager,
    private val toolRegistry: ToolRegistry,
    private val repository: ChatRepository,
    private val settingsStore: LlmSettingsStore,
    private val currentChatProvider: CurrentChatProvider,
    private val visionCapability: VisionCapability,
    private val offlineActionQueue: OfflineActionQueue,
    private val toolSelector: RunToolSelector,
    private val messageAssembler: TurnMessageAssembler,
    private val finalizer: TurnFinalizer,
    private val runScope: AgentRunScope,
    @ApplicationContext private val context: Context,
    /**
     * Learned communication style (form only). Observation is best-effort and must never break a
     * turn; `null` (e.g. in tests) disables learning.
     */
    private val styleProfileStore: StyleProfileStore? = null
) : AgentOrchestrator {

    private val TAG = "Andharness"

    /**
     * Watchdog scope: runs on [Dispatchers.Default], i.e. independently of the main thread, so a
     * frozen screen (blocking main-thread call) cannot stop the stall/hard-cap check from firing.
     */
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Bounded, priority-aware admission control shared by every run. */
    private val limiter = RunConcurrencyLimiter()

    /** chatId -> its STABLE status flow (created on first access, kept for the process lifetime). */
    private val statusFlows = ConcurrentHashMap<Long, MutableStateFlow<AgentStatus>>()

    /** chatId -> live run bookkeeping. A finished run is removed; its status flow stays. */
    private val runs = ConcurrentHashMap<Long, RunState>()

    /**
     * Rolling estimate-vs-actual prompt-size calibration, shared by every run so the average covers
     * the last requests of the whole process. Debug telemetry only: it never gates a request.
     */
    private val tokenCalibration = PromptTokenCalibration()

    private val _activeRuns = MutableStateFlow<List<ActiveRun>>(emptyList())
    override val activeRuns: StateFlow<List<ActiveRun>> = _activeRuns.asStateFlow()

    /** Last chat an INTERACTIVE run was started in; drives the backward-compatible [status]. */
    private val viewedChatId = MutableStateFlow<Long?>(null)

    override val status: StateFlow<AgentStatus> = viewedChatId
        .flatMapLatest { id -> if (id == null) flowOf(AgentStatus()) else statusFor(id) }
        .stateIn(watchdogScope, SharingStarted.Eagerly, AgentStatus())

    override fun statusFor(chatId: Long): StateFlow<AgentStatus> = statusFlowFor(chatId)

    private fun statusFlowFor(chatId: Long): MutableStateFlow<AgentStatus> =
        statusFlows.computeIfAbsent(chatId) { MutableStateFlow(AgentStatus(chatId = chatId)) }

    /**
     * Everything that belongs to ONE run. Keeping it per chat (rather than in `@Volatile` fields on
     * the singleton) is what makes several simultaneous runs correct: two runs can never see each
     * other's cancel flag, permission gate, watchdog or streaming text.
     */
    private inner class RunState(val chatId: Long) {
        /**
         * Whether this turn's user text is human-authored chat input (eligible for style learning)
         * or a synthetic prompt from a scheduler/objective (never eligible). Set per admission.
         */
        var learnStyle: Boolean = false

        val statusFlow: MutableStateFlow<AgentStatus> = statusFlowFor(chatId)

        /**
         * The coroutine that owns THIS turn (and, while queued, the coroutine awaiting a slot).
         * `cancel(chatId)` must CANCEL it, not merely set a flag: a flag is only observed between
         * steps, so a turn blocked inside a tool would keep running forever.
         */
        @Volatile
        var job: Job? = null

        @Volatile
        var cancelRequested = false

        /** Honest reason of a watchdog force-stop, kept so `finalize` can report it as ERROR. */
        @Volatile
        var forcedStopReason: String? = null

        /**
         * BUG-055 one-shot latch for [NoAttemptGuard]: set when the corrective message has already
         * been injected in THIS run, so a model that refuses again is accepted as final (no loop).
         * Reset for every new run in `begin()`.
         */
        @Volatile
        var noAttemptCorrected = false

        @Volatile
        var assistantId: Long? = null

        @Volatile
        var priority: AgentRunPriority = AgentRunPriority.INTERACTIVE

        @Volatile
        var startedAtMs: Long = 0L

        /** Visibility label (chat title, or the request's first line when untitled). */
        @Volatile
        var label: String = ""

        /**
         * Set on a BACKGROUND run when a waiting INTERACTIVE run needs its slot: the loop checks it
         * at each iteration boundary so the background step yields without waiting for its budget.
         */
        @Volatile
        var yieldRequested = false

        /** Set while this chat's run is waiting for a slot; cancelled/completed to wake it. */
        @Volatile
        var queuedGate: CompletableDeferred<Unit>? = null

        // A fresh gate per permission request: a resolved CompletableDeferred can never block again.
        val permissionGate = OneShotGate()

        val watchdog = RunWatchdog(
            scope = watchdogScope,
            isRunActive = { RunLifecyclePolicy.isActive(statusFlow.value.state) },
            onOverdue = { forceStopRun(this) }
        )

        fun noteProgress() = watchdog.noteProgress()
    }

    private fun runStateFor(chatId: Long): RunState = runs.computeIfAbsent(chatId) { RunState(chatId) }

    private fun publishActiveRuns() {
        val now = System.currentTimeMillis()
        val list = ArrayList<ActiveRun>(runs.size)
        for ((chatId, state) in runs) {
            val running = limiter.isRunning(chatId)
            val queued = limiter.isQueued(chatId)
            if (!running && !queued) continue
            list += ActiveRun(
                chatId = chatId,
                title = state.label.ifBlank { "Чат $chatId" },
                kind = state.priority,
                startedAtMs = if (state.startedAtMs > 0L) state.startedAtMs else now,
                queued = queued
            )
        }
        _activeRuns.value = list.sortedBy { it.startedAtMs }
    }

    /** Wakes every chat the limiter just admitted (its queued `run()` coroutine resumes). */
    private fun wakeAdmitted(ids: List<Long>) {
        ids.forEach { id -> runs[id]?.queuedGate?.complete(Unit) }
        publishActiveRuns()
        requestYields()
    }

    /** Releases [chatId]'s slot (no-op if not running) and admits whatever the queue allows. */
    private fun releaseSlot(chatId: Long) = wakeAdmitted(limiter.release(chatId))

    /** Asks BACKGROUND runs to pause at their next checkpoint when an interactive run is waiting. */
    private fun requestYields() {
        limiter.backgroundRunsToYield().forEach { id -> runs[id]?.yieldRequested = true }
    }

    /** Chat title, or the request's first line for still-untitled chats; never throws. */
    private suspend fun resolveLabel(chatId: Long, userMessage: String): String {
        val title = runCatching { repository.observeChat(chatId).first()?.title }.getOrNull()
        if (!title.isNullOrBlank()) return title
        return userMessage.lineSequence().firstOrNull()?.trim()?.take(60).orEmpty()
    }

    /** Result of [begin]: either the turn was not accepted, or it was launched and owns a [Job]. */
    private sealed interface BeginResult {
        data object Busy : BeginResult
        data class Started(val job: Job) : BeginResult
    }

    /**
     * Starts one agent turn WITHOUT waiting for it, launching it into the application-owned
     * [AgentRunScope]. Returns [AgentRunOutcome.BUSY] only when this chat already has an active turn
     * or the bounded queue is full; otherwise the turn is running/queued and [AgentRunOutcome.STARTED]
     * is returned promptly.
     *
     * The caller is NOT the owner of the turn: cancelling the caller's scope (e.g. `viewModelScope`
     * when the chat screen goes away) cannot cancel it. Only an explicit [cancel] / [cancel] stops it.
     *
     * @return the same [AgentRunOutcome] contract as [run]: STARTED / (queued -> STARTED) / BUSY.
     */
    override fun start(
        chatId: Long,
        userMessage: String,
        attachments: List<ChatAttachment>,
        priority: AgentRunPriority
    ): AgentRunOutcome = when (
        val begun = begin(chatId, userMessage, attachments, priority, learnStyle = true)
    ) {
        BeginResult.Busy -> AgentRunOutcome.BUSY
        is BeginResult.Started -> AgentRunOutcome.STARTED
    }

    /**
     * Suspending variant of [start] kept for the background callers (scheduled tasks, objectives,
     * the overlay) that need to know when the turn actually finished. The turn still runs on the
     * application scope, so cancelling THIS caller does not cancel the turn — it only stops waiting.
     *
     * See [start] and [AgentRunScope] for the ownership model.
     */
    override suspend fun run(
        chatId: Long,
        userMessage: String,
        attachments: List<ChatAttachment>,
        priority: AgentRunPriority
    ): AgentRunOutcome {
        val begun = begin(chatId, userMessage, attachments, priority, learnStyle = false)
        if (begun is BeginResult.Started) {
            // Wait for the owned turn. `join()` here is on the CALLER's coroutine, but the job lives
            // in the application scope, so a cancelled caller (navigation, a step budget) leaves the
            // turn running instead of killing it.
            begun.job.join()
        }
        return when (begun) {
            BeginResult.Busy -> AgentRunOutcome.BUSY
            is BeginResult.Started -> AgentRunOutcome.STARTED
        }
    }

    /**
     * Shared admission + launch: checks for an active turn, asks the [RunConcurrencyLimiter] for a
     * slot, and, when accepted, launches the turn into the application-owned [AgentRunScope]. The
     * per-chat `RunState.job` is set to the launched job (via `onLaunched`, before it starts) so
     * `cancel(chatId)` keeps working. Never suspends.
     */
    private fun begin(
        chatId: Long,
        userMessage: String,
        attachments: List<ChatAttachment>,
        priority: AgentRunPriority,
        learnStyle: Boolean
    ): BeginResult {
        val existing = runs[chatId]
        if (existing != null) {
            val ownerActive = existing.job?.isActive == true
            val publishedActive = RunLifecyclePolicy.isActive(existing.statusFlow.value.state)
            if (ownerActive || publishedActive) {
                // update.md bug #7: never trust an "in progress" state whose coroutine is gone.
                if (RunLifecyclePolicy.isStale(existing.statusFlow.value.state, ownerActive)) {
                    Log.w(TAG, "Stale ${existing.statusFlow.value.state} state with no active run " +
                        "coroutine for chat $chatId — resetting")
                    forceStopRun(existing)
                } else {
                    return BeginResult.Busy
                }
            }
        }

        val state = runStateFor(chatId)
        state.learnStyle = learnStyle
        state.priority = priority
        state.cancelRequested = false
        state.forcedStopReason = null
        state.assistantId = null
        state.yieldRequested = false
        state.noAttemptCorrected = false

        val admission = limiter.request(chatId, priority)
        if (admission == RunConcurrencyLimiter.Admission.REJECTED) {
            // The ONLY honest BUSY: the bounded queue itself is full, so the turn was not accepted.
            state.job = null
            runs.remove(chatId, state)
            publishActiveRuns()
            Log.w(TAG, "Run queue full — rejecting chat $chatId ($priority)")
            return BeginResult.Busy
        }

        // §20: best-effort crash-recovery snapshot only (see CurrentChatProvider). Tool dispatch does
        // NOT read this: it receives the chat/model through the per-run coroutine element inside the
        // turn body. Written here, in the caller context, so it holds even while the turn is queued.
        currentChatProvider.noteLastActiveChatForCrash(chatId)

        // The turn runs in the AGENT's application scope, never in the caller's: leaving a chat
        // (cancelling viewModelScope) must not cancel the turn.
        val job = runScope.launch(chatId = chatId, onLaunched = { state.job = it }) {
            executeTurn(state, chatId, userMessage, attachments, priority, admission)
        }
        return BeginResult.Started(job)
    }

    /**
     * The body of ONE turn. Runs on the application-owned [AgentRunScope] (see [begin]), so it is
     * never a child of the screen/feature that requested it. [admission] tells it whether it already
     * holds a slot ([RunConcurrencyLimiter.Admission.ADMITTED]) or must first wait in the queue.
     */
    private suspend fun executeTurn(
        state: RunState,
        chatId: Long,
        userMessage: String,
        attachments: List<ChatAttachment>,
        priority: AgentRunPriority,
        admission: RunConcurrencyLimiter.Admission
    ) {
        val thisRun = currentCoroutineContext()[Job]
        // Resolved here (suspending DB read) rather than in the caller, so every run — including a
        // cancelled caller's — still gets an honest label for the active-runs surface.
        state.label = resolveLabel(chatId, userMessage)

        if (admission == RunConcurrencyLimiter.Admission.QUEUED) {
            state.startedAtMs = System.currentTimeMillis()
            state.statusFlow.value = AgentStatus(state = AgentState.QUEUED, chatId = chatId)
            publishActiveRuns()
            // A waiting interactive run must not sit behind a background run: ask it to yield.
            requestYields()
            val gate = CompletableDeferred<Unit>()
            state.queuedGate = gate
            try {
                gate.await()
                state.queuedGate = null
            } catch (cancellation: CancellationException) {
                state.queuedGate = null
                state.job = null
                state.statusFlow.value = state.statusFlow.value.copy(state = AgentState.CANCELLED)
                runs.remove(chatId, state)
                wakeAdmitted(limiter.cancel(chatId))
                throw cancellation
            }
        } else {
            state.queuedGate = null
        }

        state.startedAtMs = System.currentTimeMillis()
        if (priority == AgentRunPriority.INTERACTIVE) viewedChatId.value = chatId
        state.watchdog.start()

        // The persisted user message carries the saved absolute path of every attachment, so the
        // agent can open it with the document/file tools (§19.3). Images are ALSO sent inline below.
        val userContent = AttachmentPolicy.buildUserText(userMessage, attachments)
        var assistantId: Long? = null
        var error: String? = null
        try {
            assistantId = initialize(state, chatId, userContent)
            state.assistantId = assistantId
            // Learn only from HUMAN chat input (not scheduled/objective prompts) and only from the
            // raw text: attachment paths added to userContent must not skew the length signal.
            if (state.learnStyle) {
                runCatching {
                    StyleObserver.observe(
                        store = styleProfileStore,
                        text = userMessage,
                        now = System.currentTimeMillis(),
                        zone = java.time.ZoneId.systemDefault(),
                    )
                }
            }
            // Expose the tool catalog to the model: core + BM25-relevant + explicitly named +
            // recently used tools (see RunToolSelector).
            val settings = settingsStore.config.first()
            val runTools = toolSelector.resolve(
                chatId,
                userMessage,
                language = settings.language
            )
            // Tool dispatch (update.md idea #6 audit hook + handlers that deep-link notifications)
            // reads chat/model from the per-run coroutine element, so two concurrent runs never
            // cross-talk. Nothing global is written here.
            error = withContext(ToolExecutionContext(chatId, settings.model)) {
                executeLoop(state, chatId, userMessage, userContent, attachments, assistantId, runTools)
            }
        } catch (cancellation: CancellationException) {
            state.cancelRequested = true
            throw cancellation
        } catch (e: Exception) {
            error = e.message ?: "Непредвиденная ошибка агента."
            Log.e(TAG, "Agent run failed before/around the loop", e)
        } finally {
            state.watchdog.stop()
            try {
                // The streaming flag is PERSISTED state, so it must be cleared on EVERY exit path
                // (success, error, cancel, timeout, unexpected crash) — not only the happy path. The
                // clear runs under NonCancellable because a plain suspend DB call would abort at once
                // in a cancelled coroutine and leave isStreaming=1 forever.
                val id = assistantId
                if (id != null) {
                    withContext(NonCancellable) {
                        finalizer.finalize(
                            chatId = chatId,
                            assistantId = id,
                            error = error,
                            owner = thisRun,
                            currentRunJob = { state.job },
                            forcedReason = { state.forcedStopReason },
                            cancelRequested = { state.cancelRequested },
                            // Only a chat/floating-assistant turn should bring the originating chat
                            // back to the front when it finishes while Меха is in another app; a
                            // background/objective/automation run must never do that.
                            userInitiated = priority == AgentRunPriority.INTERACTIVE,
                            status = state.statusFlow
                        )
                    }
                } else {
                    // initialize() failed before an assistant row existed: nothing was persisted to
                    // clear, but the in-memory streaming status must still be reset.
                    withContext(NonCancellable) {
                        // Publish only while this turn still owns the status: a watchdog takeover may
                        // already have started a NEWER turn, whose state must not be clobbered here.
                        if (state.job === thisRun) {
                            val forced = state.forcedStopReason
                            state.statusFlow.value = state.statusFlow.value.copy(
                                state = if (state.cancelRequested && forced == null) {
                                    AgentState.CANCELLED
                                } else {
                                    AgentState.ERROR
                                },
                                isStreaming = false,
                                pendingPermission = null,
                                toolActivity = null,
                                streamingMessageId = null,
                                streamingText = null,
                                contextCompaction = false,
                                toolLog = forced?.let { "⚠ $it" } ?: state.statusFlow.value.toolLog
                            )
                        }
                        runCatching { AgentForegroundService.stop(context, chatId = chatId) }
                    }
                }
            } finally {
                // Cleared only after finalize, so a concurrent run()/cancel() can never mistake the
                // still-finalizing turn for a stale one.
                if (state.job === thisRun) {
                    state.job = null
                    state.queuedGate = null
                }
                releaseSlot(chatId)
                runs.remove(chatId, state)
            }
        }
    }

    private suspend fun initialize(state: RunState, chatId: Long, userMessage: String): Long {
        // A background run (e.g. a scheduled task in a WorkManager worker) may be forbidden from
        // starting a foreground service on Android 12+; that must not abort the agent run.
        try {
            AgentForegroundService.start(context, chatId)
        } catch (e: Exception) {
            Log.w(TAG, "FGS start not allowed (running headless): ${e.message}")
        }

        repository.addMessage(chatId, "user", userMessage)
        val assistantMessageId = repository.addMessage(chatId, "assistant", "")
        repository.setStreaming(assistantMessageId, true)
        state.statusFlow.value = AgentStatus(
            state = AgentState.RUNNING,
            chatId = chatId,
            isStreaming = true,
            streamingMessageId = assistantMessageId
        )
        return assistantMessageId
    }

    override fun cancel() {
        viewedChatId.value?.let { cancel(it) }
    }

    override fun cancel(chatId: Long) {
        val state = runs[chatId] ?: return
        state.cancelRequested = true
        state.permissionGate.resolve(false)
        state.watchdog.stop()
        // Reset the VISIBLE run state right away. A hung tool that ignores cancellation must never
        // be able to keep the composer disabled (nor Back gated) until the app is restarted — the
        // run's own `finally` still persists the partial answer and stops the foreground service.
        state.statusFlow.value = state.statusFlow.value.copy(
            state = AgentState.CANCELLED,
            isStreaming = false,
            pendingPermission = null,
            toolActivity = null,
            contextCompaction = false,
            streamingMessageId = null,
            streamingText = null
        )
        // Wake a queued run out of its await (it then unregisters itself from the limiter).
        state.queuedGate?.cancel(CancellationException("Остановлено пользователем"))
        // Reach the suspension point the turn is parked on (a hung tool call suspends forever and
        // would otherwise ignore the flag). The run's own `finally` then clears `isStreaming`.
        // Cancelled through the [AgentRunScope] registry, i.e. exactly THIS chat's turn.
        runScope.cancelTurn(chatId, "Остановлено пользователем")
    }

    /**
     * Force-resets ONE hung run: publishes an honest error state FIRST (so the composer is enabled
     * and Back works immediately), then cancels the run coroutine. Never claims success.
     */
    private fun forceStopRun(state: RunState) {
        if (!RunLifecyclePolicy.isActive(state.statusFlow.value.state)) return
        Log.w(TAG, "Run watchdog fired for chat ${state.chatId} after " +
            "${RunLifecyclePolicy.formatDuration(state.watchdog.elapsedMs())} " +
            "without progress — forcing a stop")
        state.cancelRequested = true
        state.forcedStopReason = RunLifecyclePolicy.FORCED_STOP_MESSAGE
        state.permissionGate.resolve(false)
        state.statusFlow.value = state.statusFlow.value.copy(
            state = AgentState.ERROR,
            isStreaming = false,
            pendingPermission = null,
            toolActivity = null,
            contextCompaction = false,
            streamingMessageId = null,
            streamingText = null,
            toolLog = "⚠ ${RunLifecyclePolicy.FORCED_STOP_MESSAGE}"
        )
        state.job?.cancel(CancellationException(RunLifecyclePolicy.FORCED_STOP_MESSAGE))
        runScope.cancelTurn(state.chatId, RunLifecyclePolicy.FORCED_STOP_MESSAGE)
        // Leave an honest, persisted note in the chat: the transient status line is cleared as soon
        // as the message list re-emits, so it alone would not survive. Best-effort — never throws.
        state.assistantId?.let { finalizer.persistForcedStopNote(watchdogScope, state.chatId, it) }
    }

    override fun grantPermission() {
        viewedChatId.value?.let { grantPermission(it) }
    }

    override fun grantPermission(chatId: Long) {
        runs[chatId]?.permissionGate?.resolve(true)
    }

    override fun denyPermission() {
        viewedChatId.value?.let { denyPermission(it) }
    }

    override fun denyPermission(chatId: Long) {
        runs[chatId]?.permissionGate?.resolve(false)
    }

    private suspend fun executeLoop(
        state: RunState,
        chatId: Long,
        userMessage: String,
        userContent: String,
        attachments: List<ChatAttachment>,
        assistantId: Long,
        runTools: List<LlmTool>
    ): String? {
        val settings = settingsStore.config.first()
        val compiled = contextManager.compile(chatId, runTools)
        // If the history had to be compacted, surface the transient status line («Подождите, идёт
        // сжатие контекста.»). It is cleared as soon as the model starts producing output and is
        // never written to the chat as a message. The same update publishes the measured request
        // size against the budget for the optional «использовано X / N» indicator.
        state.statusFlow.update {
            it.copy(
                contextCompaction = compiled.compressed,
                contextUsedTokens = compiled.estimatedTokens,
                // The displayed denominator is the configured context-compression budget.
                contextBudgetTokens = ContextBudget.budgetFor(settings.contextCompressionTokens)
            )
        }
        var working: List<LlmMessage> = compiled.messages

        // Attach the current turn's image parts to the last user message. This happens AFTER context
        // compilation on purpose: image payloads are never compressed/dropped and never persisted.
        working = messageAssembler.attachImages(working, userContent, attachments)

        var lastError: String? = null
        var anyToolExecuted = false
        var coerced = false
        // Anti false-success bookkeeping (FalseSuccessGuard): how many tools ran in the whole turn
        // and whether the most recent tool result was an error.
        var toolCallsInTurn = 0
        var lastToolErrored = false
        // Anti "device-action claim with no tool call" bookkeeping (DeviceActionClaimDetector): the
        // full list of executed tool calls of this turn (name + success) fed to the detector.
        val toolCallRecords = mutableListOf<DeviceActionClaimDetector.ToolCall>()
        // BUG-059: a destructive/clearing tool that returned success counts as confirming evidence
        // (its handler re-checked the effect). Tracked per turn, plus a one-shot correction flag.
        var destructiveToolConfirmed = false
        var destructiveCorrected = false
        var reachedCompletion = false
        var lastStreamedText = ""
        var yielded = false
        // Progress-aware anti-loop guard (BUG-045): aborts only when the SAME tool batch produces
        // the SAME observable result again; a changing result (on↔off, counter, sensor data) is
        // legitimate repetition and resets the counter. The per-run iteration/wall-clock caps remain
        // the ultimate bound.
        val loopGuard = LoopGuard(MAX_IDENTICAL_ITERATIONS)
        // Exact-repeat detector (TOOLS_TASKS §3): a call that returns the SAME output as before gets a
        // short notice instead of a second copy of the error hint, which reads like fresh success.
        val repeatCalls = RepeatCallDetector()
        // Token estimate of the tool schemas is constant for the whole run: compute it once.
        val toolsTokenEstimate = LlmTokenEstimator.estimate(emptyList(), runTools)
        // Persisting the assistant text on every streamed token would fire one Room UPDATE (and a
        // whole-list re-emission) per token. Persist on a throttle instead; the in-flight text is
        // published through the status flow so the UI still updates smoothly.
        var lastPersistedAt = 0L
        var lastPersistedText = ""
        // Index in [working] of the synthetic user message carrying the MOST RECENT tool-produced
        // screenshot, or null. Only that one image is kept on the wire: without this bound a long
        // run would push one full screenshot per iteration into every subsequent request (§19.3).
        // The images injected for user attachments live in an earlier message and are never touched.
        var lastToolImageIndex: Int? = null

        for (ignored in 0 until MAX_ITERATIONS) {
            if (state.cancelRequested) break
            // A BACKGROUND run yields its slot at a checkpoint when an interactive chat is waiting,
            // so a chat is never blocked behind a long objective (see RunConcurrencyLimiter).
            if (state.yieldRequested && state.priority == AgentRunPriority.BACKGROUND) {
                yielded = true
                break
            }
            // A new iteration is real work: keep the stall watchdog quiet.
            state.noteProgress()

            var done = false
            var errorMsg: String? = null
            var sawDone = false
            var pendingToolCalls: List<LlmToolCall> = emptyList()
            val sb = StringBuilder()

            // Hard pre-send budget check on the FULL request (messages + tool schemas), so an
            // oversized payload is at least visible in the log instead of failing silently.
            val requestTokens = LlmTokenEstimator.estimate(working) + toolsTokenEstimate
            if (requestTokens > settings.contextCompressionTokens) {
                Log.w(
                    TAG,
                    "Запрос (~$requestTokens токенов) превышает бюджет " +
                        "(${settings.contextCompressionTokens}) — отправляем как есть."
                )
            }

            suspend fun persistStreamingText(force: Boolean) {
                // Per-token writes stop as soon as the run is cancelled; the explicit final flush
                // (force = true) still runs so the visible partial answer is not lost.
                if (state.cancelRequested && !force) return
                // Never persist or show the model's internal reasoning in the chat (bug #3).
                // Realtime variant: an open reasoning block is hidden even before its closing tag,
                // so chain-of-thought never appears and then vanishes mid-stream.
                val text = AssistantTextSanitizer.sanitizeRealtime(sb.toString())
                val now = System.currentTimeMillis()
                if (!force) {
                    if (text == lastPersistedText) return
                    if (now - lastPersistedAt < PERSIST_INTERVAL_MS) return
                }
                lastPersistedAt = now
                lastPersistedText = text
                repository.updateMessageContent(assistantId, text)
                state.statusFlow.update { it.copy(streamingMessageId = assistantId, streamingText = text) }
            }

            /** Writes the authoritative final text and mirrors it into the streaming overlay. */
            suspend fun publishContent(text: String) {
                // The final message is sanitized as a whole, so a leak split across streamed deltas
                // is still removed before it becomes the stored/visible answer (bug #3).
                val clean = AssistantTextSanitizer.sanitizeRealtime(text)
                lastPersistedAt = System.currentTimeMillis()
                lastPersistedText = clean
                repository.updateMessageContent(assistantId, clean)
                state.statusFlow.update { it.copy(streamingMessageId = assistantId, streamingText = clean) }
            }

            try {
                withTimeout(LLM_COMPLETION_CAP_MS) {
                coroutineScope {
                // Wall-clock of the last event from THIS call. A dedicated ticker flips the
                // transient `providerSlow` hint when nothing at all arrives; the collector below
                // flips it back on the very next event, so text/tool deltas always win.
                val lastEventAt = AtomicLong(System.currentTimeMillis())
                val slowWatch = launch {
                    while (isActive) {
                        delay(PROVIDER_SLOW_TICK_MS)
                        val slow = !state.statusFlow.value.providerThinking &&
                            System.currentTimeMillis() - lastEventAt.get() >=
                            PROVIDER_SLOW_NOTICE_MS
                        if (state.statusFlow.value.providerSlow != slow) {
                            state.statusFlow.update { it.copy(providerSlow = slow) }
                        }
                    }
                }
                try {
                llmClient.complete(
                    LlmRequest(
                        messages = working,
                        tools = runTools
                    )
                ).collect { event ->
                    lastEventAt.set(System.currentTimeMillis())
                    if (state.statusFlow.value.providerSlow) {
                        state.statusFlow.update { it.copy(providerSlow = false) }
                    }
                    when (event) {
                        is LlmEvent.Reasoning -> {
                            // Internal reasoning is never shown; only the status line changes.
                            // It also clears the tool/compaction notices so the user sees one
                            // honest state («Меха думает…») instead of stacked hints.
                            state.statusFlow.update {
                                it.copy(
                                    providerThinking = true,
                                    providerSlow = false,
                                    contextCompaction = false,
                                    toolActivity = null,
                                )
                            }
                        }
                        is LlmEvent.Text -> {
                            if (state.cancelRequested) return@collect
                            state.noteProgress()
                            if (state.statusFlow.value.providerThinking) {
                                state.statusFlow.update { it.copy(providerThinking = false) }
                            }
                            sb.append(event.delta)
                            lastStreamedText = sb.toString()
                            persistStreamingText(force = false)
                            // Text is flowing again, so the tool indicator and the compaction notice
                            // yield to "Меха печатает…".
                            if (state.statusFlow.value.toolActivity != null || state.statusFlow.value.contextCompaction) {
                                state.statusFlow.update { it.copy(toolActivity = null, contextCompaction = false) }
                            }
                        }
                        is LlmEvent.ToolCalls -> {
                            pendingToolCalls = event.calls
                            state.statusFlow.update {
                                it.copy(
                                    toolLog = "⚙ вызов ${event.calls.size} инструмента(ов)",
                                    contextCompaction = false,
                                    providerThinking = false,
                                )
                            }
                        }
                        is LlmEvent.Done -> {
                            sawDone = true
                            if (state.statusFlow.value.providerThinking) {
                                state.statusFlow.update { it.copy(providerThinking = false) }
                            }
                        }
                        is LlmEvent.Usage -> {
                            // Provider-billed prompt size vs our estimate (CONTEXT_TASKS_V2). Logged
                            // only, never enforced: a consistent divergence means the estimator's
                            // constants need correcting from evidence, not from intuition.
                            val ratio = tokenCalibration.record(
                                estimated = requestTokens,
                                actual = event.promptTokens
                            )
                            if (ratio != null) {
                                val average = tokenCalibration.averageRatio() ?: ratio
                                Log.d(
                                    TAG,
                                    "tokens estimate=$requestTokens actual=${event.promptTokens} " +
                                        "ratio=${"%.2f".format(ratio)} " +
                                        "avg=${"%.2f".format(average)} " +
                                        "diverges=${tokenCalibration.diverges()}"
                                )
                            }
                        }
                        is LlmEvent.Error -> {
                            errorMsg = event.message
                            state.statusFlow.update { it.copy(providerThinking = false) }
                            throw RuntimeException(event.message)
                        }
                    }
                }
                } finally {
                    // The hint is transient: it must never outlive its call (done, errored,
                    // cancelled or timed out).
                    slowWatch.cancel()
                    if (state.statusFlow.value.providerSlow ||
                        state.statusFlow.value.providerThinking
                    ) {
                        state.statusFlow.update {
                            it.copy(providerSlow = false, providerThinking = false)
                        }
                    }
                }
                }
                }
            } catch (timeout: TimeoutCancellationException) {
                // Hard wall-clock cap for ONE completion. The client's per-delta watchdog already
                // aborts a silent stall; this backstops a stream that keeps trickling tiny progress
                // forever, and cancelling the collection cancels the in-flight OkHttp Call.
                errorMsg = "Модель не завершила ответ за ${LLM_COMPLETION_CAP_MS / 60_000} мин " +
                    "(жёсткий лимит на один ответ) — генерация прервана."
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                errorMsg = error.message
            }

            if (state.cancelRequested) {
                // Never leave the last streamed text unpersisted just because the run was stopped.
                persistStreamingText(force = true)
                break
            }

            if (errorMsg == null && !sawDone) {
                // The stream ended without a Done/stop signal: treat it as an abnormal finish instead
                // of silently marking the turn done (the answer may be truncated).
                errorMsg = "Поток ответа завершился неожиданно (без сигнала завершения) — возможно, ответ неполный. Повторите запрос."
            }
            persistStreamingText(force = true)

            // Some OpenAI-compatible models write the tool call as TEXT markup instead of a native
            // `tool_calls` delta (`<tool_call><function=device_facts_list></function></tool_call>`).
            // Recover it as a real call so the action actually runs, and drop the markup from the
            // answer — otherwise the user sees the raw tag in the chat and nothing happens.
            val rawAnswer = sb.toString()
            if (pendingToolCalls.isEmpty()) {
                pendingToolCalls = TextToolCallParser.parse(rawAnswer)
                if (pendingToolCalls.isNotEmpty()) {
                    Log.w(TAG, "Recovered ${pendingToolCalls.size} tool call(s) written as text markup")
                }
            }
            val withoutMarkup = TextToolCallParser.remove(rawAnswer)
            if (withoutMarkup != rawAnswer) {
                sb.setLength(0)
                sb.append(withoutMarkup)
                // The guards below read the streamed text, so they must see the answer the user sees.
                lastStreamedText = withoutMarkup
            }

            if (errorMsg != null) {
                Log.e(TAG, "Agent run error: $errorMsg")
                // Always surface an honest failure explanation. The model may have streamed a
                // preamble ("Понял, сейчас сделаю") and then stalled/errored before actually
                // calling any tool — in that case leaving only the preamble would make it look like
                // the action was performed. Prepend it with the real reason instead.
                val body = if (sb.isBlank()) "" else "$sb\n\n"
                publishContent("${body}Не получилось. $errorMsg")
                lastError = errorMsg
                break
            }

            if (pendingToolCalls.isNotEmpty()) {
                // Call signature of the batch the model asked for (fed to the progress-aware guard).
                val callSignature = pendingToolCalls.joinToString("|") { "${it.name}:${it.arguments}" }
                working = working + LlmMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = pendingToolCalls
                )

                // Execute the batch FIRST, so the anti-loop guard below compares the OBSERVABLE
                // RESULT (progress), not just the call: a changing result (on↔off, counter, sensor
                // data) is legitimate repetition, not a stuck loop (BUG-045).
                val results = arrayOfNulls<ToolResult>(pendingToolCalls.size)
                for ((idx, call) in pendingToolCalls.withIndex()) {
                    if (state.cancelRequested) {
                        working = working + deniedToolResult(call, "Выполнение отменено пользователем.")
                        repository.addMessage(chatId, "tool", "Ошибка: выполнение отменено пользователем.", call.name)
                        continue
                    }

                    // Dangerous tools (and every other tool) execute directly: the app no longer
                    // shows any confirmation prompt before a tool call. Only technically-impossible
                    // operations (a missing runtime permission, a platform restriction) are blocked,
                    // and that block happens inside the tool itself.
                    if (!state.cancelRequested) {
                        state.statusFlow.update {
                            it.copy(state = AgentState.TOOL_EXEC, pendingPermission = null)
                        }
                    }
                    anyToolExecuted = true
                    toolCallsInTurn++
                    state.noteProgress()
                    state.statusFlow.update {
                        it.copy(toolActivity = ToolActivity(call.name, ToolActivityState.RUNNING))
                    }
                    // Hard cap on ONE tool call. A tool can park forever on a suspension whose
                    // completion never arrives (e.g. a WebView `evaluateJavascript` callback that is
                    // never delivered); without this bound the whole turn hangs with `isStreaming=1`
                    // and NO watchdog ever fires, because both watchdogs only cover the LLM stream.
                    // Timing out here yields an honest failure result and lets the loop finish.
                    var result = try {
                        withTimeout(TOOL_EXEC_TIMEOUT_MS) {
                            toolRegistry.execute(call.name, call.arguments)
                        }
                    } catch (e: TimeoutCancellationException) {
                        Log.w(TAG, "Tool ${call.name} did not finish in ${TOOL_EXEC_TIMEOUT_MS / 1000}s — aborted")
                        ToolResult(
                            false,
                            "Инструмент «${call.name}» не завершился за ${TOOL_EXEC_TIMEOUT_MS / 1000} секунд " +
                                "и был прерван. Действие НЕ выполнено — повторите попытку позже."
                        )
                    } finally {
                        state.noteProgress()
                    }
                    val permission = result.output.removePrefix(PERMISSION_REQUIRED_PREFIX)
                    if (permission != result.output && permission.isNotBlank()) {
                        state.statusFlow.update {
                            it.copy(
                                state = AgentState.WAITING_PERMISSION,
                                pendingPermission = permission
                            )
                        }
                        val gate = state.permissionGate.open()
                        val granted = try {
                            withTimeout(GATE_TIMEOUT_MS) { gate.await() }
                        } catch (e: TimeoutCancellationException) {
                            state.cancelRequested = true
                            false
                        } finally {
                            state.permissionGate.clear(gate)
                            state.noteProgress()
                        }
                        if (!state.cancelRequested) {
                            state.statusFlow.update {
                                it.copy(state = AgentState.TOOL_EXEC, pendingPermission = null)
                            }
                        }
                        result = if (granted) {
                            // The retry after a granted permission needs the SAME hard cap as the
                            // first attempt: without it a tool that hangs (unbounded `.first{...}`,
                            // lost callback) keeps the turn — and the composer — stuck forever,
                            // because the watchdog cannot interrupt a coroutine parked in a tool.
                            try {
                                withTimeout(TOOL_EXEC_TIMEOUT_MS) {
                                    toolRegistry.execute(call.name, call.arguments)
                                }
                            } catch (e: TimeoutCancellationException) {
                                Log.w(TAG, "Tool ${call.name} (after permission) did not finish in " +
                                    "${TOOL_EXEC_TIMEOUT_MS / 1000}s — aborted")
                                ToolResult(
                                    false,
                                    "Инструмент «${call.name}» не завершился за " +
                                        "${TOOL_EXEC_TIMEOUT_MS / 1000} секунд после выдачи разрешения " +
                                        "и был прерван. Действие НЕ выполнено."
                                )
                            } finally {
                                state.noteProgress()
                            }
                        } else {
                            ToolResult(false, "Отказано в разрешении: $permission")
                        }
                    }
                    // Offline queue (§2): a network-bound tool that failed for lack of network is
                    // not dropped — the ORIGINAL call is persisted and retried by a WorkManager
                    // worker once connectivity is back. The conservative policy
                    // (OfflineQueuePolicy) is re-applied inside enqueue(), so only safe,
                    // genuinely network-bound calls are ever queued; irreversible local actions
                    // (SMS, calls, deletes, files, contacts, calendar) are never queued.
                    if (!result.success &&
                        offlineActionQueue.enqueue(call.name, call.arguments, result.output) != null
                    ) {
                        OfflineActionWorker.enqueue(context)
                        Log.i(TAG, "Нет сети: ${call.name} поставлен в очередь на повтор " +
                            "(в очереди: ${offlineActionQueue.count()})")
                        result = result.copy(output = result.output + "\n" + OFFLINE_QUEUED_NOTE)
                    }
                    // Keep the final outcome of THIS tool call for the progress-aware anti-loop
                    // guard (BUG-045), which compares the observable result across iterations.
                    results[idx] = result
                    // Remember the final outcome of THIS tool call for the anti false-success guard:
                    // a success claim after an errored tool must not be accepted as final.
                    lastToolErrored = !result.success
                    // Full per-turn call list for DeviceActionClaimDetector (name + success).
                    toolCallRecords += DeviceActionClaimDetector.ToolCall(call.name, result.success)
                    // BUG-059: only a destructive tool that returned success (i.e. verified its own
                    // effect) counts as confirming evidence for a later delete/clear claim.
                    if (result.success && FalseSuccessGuard.isDestructiveTool(call.name)) {
                        destructiveToolConfirmed = true
                    }
                    // The full output is persisted for the user log, but only a bounded copy is fed
                    // back into the working history — a huge file/page dump would otherwise consume
                    // the whole context window (with an honest "вывод усечён" marker).
                    working = working + LlmMessage(
                        role = "tool",
                        // `exit 0` / `exit 1` first, then the bounded body: the model must be able to
                        // tell success from failure without guessing from prose (TOOLS_TASKS §1).
                        content = ToolResultEnvelope.wrap(
                            result.success,
                            if (repeatCalls.isExactRepeat(call.name, call.arguments, result.output)) {
                                // Same tool + same args + same output: the hint was already given and
                                // ignored, so repeating it only fills the window (TOOLS_TASKS §3).
                                REPEAT_CALL_NOTICE
                            } else {
                                ToolOutputLimiter.limit(result.output)
                            }
                        ),
                        toolCallId = call.id
                    )
                    // A tool may return a transient image (e.g. `screen_understand`). Feed it to the
                    // model as an inline user message, but ONLY when the configured model is known to
                    // accept images; otherwise the tool's own honest text note is the fallback. The
                    // image deliberately does NOT go on the `tool`-role message: OpenAI-compatible
                    // servers are inconsistent about content arrays on a tool role.
                    if (result.imageParts.isNotEmpty() && visionCapability.supportsVision()) {
                        // Bounding: keep only the most recent screenshot. Before appending the new
                        // one, replace the previously injected message with a text-only marker so a
                        // long run cannot accumulate images. Index-stable because `working` only ever
                        // grows here (compaction happened before the loop and never removes tail items).
                        val staleIndex = lastToolImageIndex
                        if (staleIndex != null && staleIndex in working.indices) {
                            val stale = working[staleIndex]
                            if (stale.contentParts?.any { it is LlmContentPart.ImageUrl } == true) {
                                working = working.toMutableList().also {
                                    it[staleIndex] = stale.copy(
                                        contentParts = listOf(LlmContentPart.Text(TOOL_IMAGE_DROPPED_NOTE))
                                    )
                                }
                            }
                        }
                        working = working + LlmMessage(
                            role = "user",
                            contentParts = buildList {
                                add(LlmContentPart.Text(TOOL_IMAGE_CAPTION))
                                addAll(result.imageParts)
                            }
                        )
                        lastToolImageIndex = working.lastIndex
                    }
                    // The structured `payload` (if any, e.g. `device_search` result cards) rides on
                    // the persisted `tool` row for the chat's rich preview; only `output` feeds the
                    // model's working history.
                    repository.addMessageWithPayload(
                        chatId = chatId,
                        role = "tool",
                        content = result.output,
                        toolName = call.name,
                        payload = result.payload
                    )
                    state.noteProgress()
                    val outcome = if (result.success) ToolActivityState.SUCCEEDED else ToolActivityState.FAILED
                    state.statusFlow.update {
                        it.copy(
                            toolLog = "⚙ ${call.name} → ${result.output.take(120)}",
                            toolActivity = ToolActivity(call.name, outcome)
                        )
                    }
                }

                // Progress-aware anti-loop guard (BUG-045): abort only when the SAME batch was
                // called AND produced the SAME observable result again — identical call AND
                // identical progress. A changing result (on↔off, counter, fresh sensor data) is
                // progress and resets the counter, so legitimate repetition (SOS, retries,
                // «повтори N раз») is not killed. When the user explicitly asked to repeat, trust
                // that and let the per-run iteration/wall-clock caps be the only bound.
                val progressSignature = pendingToolCalls.mapIndexed { i, call ->
                    "${call.name}:${results[i]?.success}:${resultDigest(results[i]?.output)}"
                }.joinToString("|")
                val stuck = if (looksLikeRepeatRequest(userMessage)) {
                    false
                } else {
                    loopGuard.record(callSignature, progressSignature)
                }
                if (stuck) {
                    val toolName = pendingToolCalls.first().name
                    // A short cycle (A→B→A→B, A→B→C→…) never matches the "identical iteration" rule,
                    // so it is reported for what it is instead of as N identical calls.
                    val msg = if (loopGuard.detectedCycle > 0) {
                        "Агент ходит по кругу: последние вызовы повторяются (цикл из " +
                            "${loopGuard.detectedCycle} шагов, последний — «$toolName») с тем же " +
                            "результатом — останавливаю. Действие не подтверждено; уточните, что именно " +
                            "нужно сделать, или разбейте задачу на части."
                    } else {
                        "Агент вызвал «$toolName» $loopGuard.consecutiveRepeats раз(а) с тем же " +
                            "результатом и не продвинулся — останавливаю. Действие не подтверждено; " +
                            "уточните, что именно нужно сделать, или разбейте задачу на части."
                    }
                    publishContent(if (sb.isBlank()) msg else "$sb\n\n$msg")
                    lastError = msg
                    break
                }
            } else {
                // The model produced a plain-text answer with no tool call. Three deterministic guards
                // run here, all bounded to a single retry per run:
                // 1) NoAttemptGuard (BUG-055) — the answer is a refusal/incapability and NOT ONE tool
                //    was called in the whole run: reject it once and ask the model to actually try
                //    the available tools before declaring it impossible. One-shot via
                //    `state.noAttemptCorrected`.
                // 2) FalseSuccessGuard — the reply CLAIMS success although no tool ran (or the last
                //    tool errored): reject it as final and force one corrective model turn.
                // 3) The generic nudge — the user asked for an action, no tool ran, and the model
                //    did not clearly refuse: ask it once to emit the matching tool call.
                val refusal = looksLikeExplicitRefusal(sb.toString())
                val noAttempt = NoAttemptGuard.shouldCorrectOnce(
                    alreadyCorrected = state.noAttemptCorrected,
                    toolCallsMadeInRun = toolCallsInTurn,
                    finalText = sb.toString()
                )
                val falseSuccess = !coerced && !refusal &&
                    FalseSuccessGuard.shouldCorrect(
                        userMessage,
                        sb.toString(),
                        toolCallsInTurn,
                        lastToolErrored
                    )
                // DeviceActionClaimDetector: the reply claims a device action although the whole
                // turn called NO tool. Shares the single `coerced` retry latch with the other
                // corrective nudges, so at most ONE corrective turn is ever run.
                val deviceClaim = !coerced && !destructiveCorrected && !refusal &&
                    DeviceActionClaimDetector.shouldCorrect(sb.toString(), toolCallRecords)
                if (noAttempt) {
                    state.noAttemptCorrected = true
                    working = working + LlmMessage(
                        role = "system",
                        content = NoAttemptGuard.CORRECTIVE_MESSAGE
                    )
                    done = false
                } else if (falseSuccess) {
                    coerced = true
                    working = working + LlmMessage(
                        role = "system",
                        content = FalseSuccessGuard.CORRECTION_PROMPT
                    )
                    done = false
                } else if (!destructiveCorrected && !refusal &&
                    FalseSuccessGuard.shouldCorrectDestructive(sb.toString(), destructiveToolConfirmed)
                ) {
                    // BUG-059: the reply claims a delete/clear/trash, but no destructive tool in this
                    // turn returned confirming success. Reject once and force a factual re-check.
                    destructiveCorrected = true
                    working = working + LlmMessage(
                        role = "system",
                        content = FalseSuccessGuard.DESTRUCTIVE_CORRECTION_PROMPT
                    )
                    done = false
                } else if (deviceClaim) {
                    // The reply claims a device action but no tool ran: reject it once and force the
                    // model to either call a tool or say honestly that it did not act (no loop).
                    coerced = true
                    working = working + LlmMessage(
                        role = "system",
                        content = DeviceActionClaimDetector.CORRECTION_PROMPT
                    )
                    done = false
                } else if (!coerced && !anyToolExecuted && looksLikeActionRequest(userMessage) && !refusal) {
                    coerced = true
                    working = working + LlmMessage(role = "system", content = ACTION_COERCION_PROMPT)
                    done = false
                } else {
                    done = true
                }
            }

            if (done) {
                reachedCompletion = true
                break
            }
        }

        if (yielded) {
            // Honest: the BACKGROUND run paused so a waiting interactive chat could take the slot.
            // The step is retried by the objective coordinator on its next tick; say so in the chat.
            state.cancelRequested = true
            repository.updateMessageContent(assistantId, AssistantTextSanitizer.sanitize(YIELD_NOTE))
            return null
        }

        if (lastError == null && !reachedCompletion && !state.cancelRequested) {
            // The loop ran out of iterations without the model ever delivering a final answer
            // (e.g. it kept producing tool calls or text without finishing). Stop and say so
            // honestly instead of silently ending as "done".
            val msg = "Не получилось: задача не была завершена за $MAX_ITERATIONS шагов. Вероятно, модель зациклилась или действие не удалось. Останавливаюсь."
            lastError = msg
            repository.updateMessageContent(assistantId, AssistantTextSanitizer.sanitize(msg))
        }

        // Honest guard against the model "promising but not doing", applied to the FINAL accepted
        // message (i.e. also after the single correction turn):
        //  - DeviceActionClaimDetector still holds → the final text claims a device action although
        //    the turn called NO tool. The model already had its single corrective turn, so REPLACE
        //    the misleading text with an honest fallback (it must never stand). Checked first so the
        //    replacement wins over the append-a-note path below.
        //  - FalseSuccessGuard still holds → the success claim is not backed by a tool result (no
        //    tool ran at all, or the last tool errored). Append the honest unverified note.
        //  - Otherwise, the original nudge: an action was requested, nothing ran, and the model did
        //    not explicitly refuse.
        if (lastError == null && reachedCompletion && !state.cancelRequested &&
            !looksLikeExplicitRefusal(lastStreamedText) &&
            DeviceActionClaimDetector.shouldCorrect(lastStreamedText, toolCallRecords)
        ) {
            repository.updateMessageContent(
                assistantId,
                AssistantTextSanitizer.sanitize(DeviceActionClaimDetector.HONEST_FALLBACK)
            )
            lastError = "Действие не выполнено: модель сообщила о выполнении без вызова инструмента."
        } else if (lastError == null && reachedCompletion && !state.cancelRequested &&
            !looksLikeExplicitRefusal(lastStreamedText) &&
            (
                FalseSuccessGuard.shouldCorrect(userMessage, lastStreamedText, toolCallsInTurn, lastToolErrored) ||
                    FalseSuccessGuard.shouldCorrectDestructive(lastStreamedText, destructiveToolConfirmed)
                )
        ) {
            val note = FalseSuccessGuard.UNVERIFIED_SUCCESS_NOTE
            val body = if (lastStreamedText.isBlank()) note else "$lastStreamedText\n\n$note"
            repository.updateMessageContent(assistantId, AssistantTextSanitizer.sanitize(body))
            lastError = "Действие не выполнено: модель сообщила об успехе без подтверждения инструмента."
        } else if (lastError == null && reachedCompletion && !state.cancelRequested &&
            !anyToolExecuted && looksLikeActionRequest(userMessage) &&
            !looksLikeExplicitRefusal(lastStreamedText)
        ) {
            val note = "Внимание: действие фактически не выполнено — я не вызвал ни одного инструмента и только ответил текстом. Если для этого есть инструмент, переформулируй запрос точнее."
            val body = if (lastStreamedText.isBlank()) note else "$lastStreamedText\n\n$note"
            repository.updateMessageContent(assistantId, AssistantTextSanitizer.sanitize(body))
            lastError = "Действие не выполнено: модель не вызвала инструмент."
        }

        return lastError
    }

    /** A tool result for a call that was NOT executed (cancelled): the model still needs one. */
    private fun deniedToolResult(call: LlmToolCall, reason: String) = LlmMessage(
        role = "tool",
        content = ToolResultEnvelope.wrap(success = false, body = "Ошибка: $reason."),
        toolCallId = call.id
    )

    private companion object {
        // Per-turn cap on model iterations (one LLM completion each). Raised 40 -> 500 (bug-list
        // item 8): simple tasks were exhausting the 40-step budget and aborting honestly with
        // «задача не завершена за 40 шагов». The progress-aware LoopGuard (BUG-045), the wall-clock
        // RunLifecyclePolicy cap, and the per-completion LLM timeout remain the real anti-runaway
        // bounds, so a larger iteration budget does not allow an unbounded turn.
        private const val MAX_ITERATIONS = 500
        private const val GATE_TIMEOUT_MS = 60_000L

        /**
         * Short body for an exact repeated call (same tool, same arguments, same result). It states
         * the fact and stops: no hint, no suggestion — the model already received the hint once.
         */
        private const val REPEAT_CALL_NOTICE =
            "Повтор: этот вызов с теми же аргументами уже вернул ровно этот результат в этом ходу. " +
                "Результат не изменится — не повторяй его, измени аргументы или выбери другой инструмент."
        // Hard wall-clock cap for ONE tool execution (4 min), so no tool can hang a turn forever.
        private const val TOOL_EXEC_TIMEOUT_MS = 240_000L
        // Overall wall-clock backstop for ONE LLM completion lives at the top of this file as the
        // test-visible `LLM_COMPLETION_CAP_MS` (3_660_000 ms, derived from LlmTimeouts).
        // Assistant text is persisted at most this often while streaming (final content is always
        // flushed exactly at stream end), so one Room write per token cannot flood the UI.
        private const val PERSIST_INTERVAL_MS = 120L
        // Consecutive iterations whose tool-call batch AND observable result are both identical,
        // before the run is treated as a stuck loop (BUG-045). The guard is PROGRESS-AWARE: a batch
        // that changes its result (on↔off, counter, sensor data) resets the counter, so legitimate
        // repetition is not killed. Relayed verbatim to `LoopGuard`; the per-run iteration cap
        // (MAX_ITERATIONS) and RunLifecyclePolicy wall-clock remain the ultimate bound.
        private const val MAX_IDENTICAL_ITERATIONS = 3
        private const val PERMISSION_REQUIRED_PREFIX = "PERMISSION_REQUIRED:"
        // Caption of the synthetic user message that carries a tool-produced screenshot (§19.3).
        private const val TOOL_IMAGE_CAPTION = "Изображение с экрана для анализа:"
        // Stands in for a superseded screenshot, so the history stays honest about what was dropped.
        private const val TOOL_IMAGE_DROPPED_NOTE =
            "Предыдущее изображение с экрана пропущено: приложено только последнее."
        // Appended to a queued tool result so the user/model see the action was not lost (§2).
        private const val OFFLINE_QUEUED_NOTE =
            "(Нет сети: действие поставлено в очередь и будет повторено автоматически при появлении подключения.)"
        // Honest note written when a BACKGROUND step yields its slot to a waiting interactive chat.
        private const val YIELD_NOTE =
            "⏸ Уступил очередь интерактивному диалогу, чтобы не блокировать чат. Шаг цели " +
                "будет повторён автоматически на следующем проходе."
    }
}
