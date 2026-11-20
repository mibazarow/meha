package com.andharness.ru.data.assist

import android.util.Log
import com.andharness.ru.data.notifications.NotificationHelper
import com.andharness.ru.domain.agent.AgentOrchestrator
import com.andharness.ru.domain.agent.AgentRunPriority
import com.andharness.ru.domain.assist.overlay.OverlayHandoff
import com.andharness.ru.domain.chat.ChatRepository
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BUG-041: runs one agent turn asked for from the floating bubble, with NO dependency on an
 * Activity being in the foreground.
 *
 * The overlay is a foreground Service, so the app *process* is alive while the bubble is on screen —
 * but the user is looking at another app, so the app has no visible Activity and a background
 * activity launch is not guaranteed
 * (https://developer.android.com/guide/components/activities/secure-bal). The previous hand-off
 * therefore parked the question in the in-memory `InboundSeedStore`, consumed only by the
 * `ChatViewModel` the chat screen constructs: with no Activity, `MainActivity` never handled the
 * inbound Intent, the seed was never consumed, and no agent turn ever ran.
 *
 * This runner removes that dependency by calling the same
 * [AgentOrchestrator.run] entry point scheduled tasks and automation rules use, which persists the
 * user message and the assistant reply into the chat database and starts its own foreground service
 * for the run, so the answer is produced and stored even while Меха is closed.
 *
 * The run guarantees, unit-tested in [OverlayHandoff]:
 *  - every distinct submit is admitted (the shared chat is never a reason to drop a question);
 *  - honest reporting — a `AgentRunOutcome.BUSY` run means the question was NOT sent, so it is
 *    persisted and the user is notified instead of losing it silently.
 *
 * The turn runs on this singleton's own application-lifetime scope, so closing the bubble (or the
 * overlay Service being destroyed) cannot cancel an answer that is already being produced. The same
 * pattern backs [com.andharness.ru.data.automation.RuleEngineImpl].
 */
@Singleton
class OverlayAgentRunner @Inject constructor(
    private val chatRepository: ChatRepository,
    // Lazy so constructing the overlay Service does not build the whole agent/tool graph; the
    // orchestrator is resolved only when a question is actually submitted.
    private val orchestrator: Lazy<AgentOrchestrator>,
    private val notifications: NotificationHelper,
) {

    /**
     * Never cancelled: it outlives the overlay Service on purpose, so an in-flight turn survives the
     * bubble being closed or the Service being stopped by the system.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Starts an automatic turn for [chatId] with [seedText] (the question plus the screen context
     * block).
     *
     * Every distinct submit is admitted: all overlay requests share one persistent chat, and a
     * second question asked while the first turn is still running must reach the orchestrator —
     * which either answers it or honestly reports BUSY (the question is then persisted, never
     * silently dropped). A per-chat exactly-once guard no longer applies because the chat id is
     * shared, so it would mistake a genuine second question for a duplicate of the first.
     *
     * Non-suspending on purpose: the Service only needs to know that the chat was ensured and the
     * turn was launched, so the panel can close immediately; the run itself continues in the
     * background and the agent posts its own progress/completion notifications.
     */
    fun runOnce(chatId: Long, seedText: String) {
        scope.launch {
            val outcome = try {
                val run = orchestrator.get().run(chatId, seedText, priority = AgentRunPriority.INTERACTIVE)
                OverlayHandoff.outcome(run, orchestrator.get().statusFor(chatId).value.state)
            } catch (e: Exception) {
                Log.e(TAG, "overlay agent run failed for chat $chatId", e)
                runCatching {
                    chatRepository.addMessage(
                        chatId,
                        ROLE_ASSISTANT,
                        "⚠ Не удалось выполнить запрос: ${e.message ?: e.javaClass.simpleName}"
                    )
                }
                OverlayHandoff.Outcome.FAILED
            }
            // STARTED needs no extra work: the orchestrator already persisted the turn (and its
            // own honest error/cancel note) and AgentForegroundService posts the notification.
            when (outcome) {
                OverlayHandoff.Outcome.STARTED -> Unit
                OverlayHandoff.Outcome.BUSY -> {
                    // Nothing was sent, so persist the question rather than dropping it.
                    runCatching { chatRepository.addMessage(chatId, ROLE_USER, seedText) }
                        .onFailure { Log.w(TAG, "could not persist the busy question", it) }
                    notify("Меха занята другой задачей — вопрос сохранён в чате, повторите позже", chatId)
                }

                OverlayHandoff.Outcome.FAILED ->
                    notify("Не удалось выполнить запрос из плавающего помощника", chatId)
            }
        }
    }

    private fun notify(text: String, chatId: Long) {
        runCatching { notifications.postDoneNotification(text = text, chatId = chatId) }
            .onFailure { Log.w(TAG, "overlay notification failed: ${it.message}") }
    }

    private companion object {
        const val TAG = "OverlayAgentRunner"

        /** `MessageEntity.role` values, matching the rest of the app. */
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}
