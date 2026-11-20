package com.andharness.ru.data.agent

import android.content.Context
import android.util.Log
import com.andharness.ru.bg.AgentForegroundService
import com.andharness.ru.domain.agent.AgentState
import com.andharness.ru.domain.agent.AgentStatus
import com.andharness.ru.domain.agent.RunLifecyclePolicy
import com.andharness.ru.domain.chat.ChatRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Owns the end-of-turn result formatting and persistence: publishing the final [AgentStatus],
 * stopping the foreground service, and leaving an honest persisted note when a turn was
 * force-stopped by the watchdog.
 *
 * With the concurrent per-chat runs there is no shared per-turn state to clear here any more: the
 * chat/model of a tool call lives in the run's own coroutine scope
 * ([com.andharness.ru.domain.tools.ToolExecutionContext]), and each chat owns its own status flow,
 * so finishing one run can never tear down another's.
 *
 * The host passes lambdas for its own volatile flags so the reads happen at exactly the same moment
 * as before the extraction.
 */
class TurnFinalizer @Inject constructor(
    private val repository: ChatRepository,
    private val chatReturn: ChatReturnLauncher,
    @ApplicationContext private val context: Context
) {

    /**
     * Clears the persisted streaming flag and, when this turn still owns its status, publishes the
     * final state, stops the foreground service, and — for a user-initiated run that finished while
     * Меха was not visible — brings the ORIGINATING chat back to the front with the report.
     *
     * [userInitiated] is true only for an INTERACTIVE run (a chat message or the floating
     * assistant); background/objective/automation runs must not yank the user into the app.
     */
    suspend fun finalize(
        chatId: Long,
        assistantId: Long,
        error: String?,
        owner: Job?,
        currentRunJob: () -> Job?,
        forcedReason: () -> String?,
        cancelRequested: () -> Boolean,
        userInitiated: Boolean = false,
        status: MutableStateFlow<AgentStatus>
    ) {
        repository.setStreaming(assistantId, false)
        // A late finalize of a superseded turn must not clobber the status of the NEWER turn.
        val ownsTurn = currentRunJob() === owner
        if (!ownsTurn) {
            Log.i(TAG, "Superseded turn finished late; status left to the live turn (chatId=$chatId)")
            return
        }
        // A watchdog force-stop wins over the generic "cancelled" label: the user must see WHY the
        // turn died, and it must not look like a deliberate stop.
        val forced = forcedReason()
        val finalState = when {
            forced != null -> AgentState.ERROR
            cancelRequested() -> AgentState.CANCELLED
            error != null -> AgentState.ERROR
            else -> AgentState.DONE
        }
        Log.i(TAG, "Agent ${finalState} chatId=$chatId error=${forced ?: error ?: "none"}")
        status.value = status.value.copy(
            state = finalState,
            isStreaming = false,
            pendingPermission = null,
            toolActivity = null,
            // The persisted message is now authoritative; drop the streaming overlay.
            streamingMessageId = null,
            streamingText = null,
            contextCompaction = false,
            toolLog = when {
                forced != null -> "⚠ $forced"
                error != null -> "⚠ $error"
                else -> status.value.toolLog
            }
        )
        // A user-initiated run that finished while Меха was in the background (a UI-automation step
        // or `app_launch` moved the user into another app) brings the originating chat back to the
        // front, so the report is actually seen. Cancelled runs are left alone — the user stopped
        // them deliberately. Best-effort: on refusal it falls back to the result notification and
        // never throws (high-caution area).
        //
        // When the return succeeded the report is on screen, so the chat-targeted completion
        // notification is skipped as redundant; every other case (not attempted, refused,
        // cancelled) keeps it as the fallback.
        val returnedToChat = finalState != AgentState.CANCELLED &&
            chatReturn.returnToOriginatingChatIfAway(chatId, userInitiated)
        try {
            val notify = !returnedToChat
            when (finalState) {
                AgentState.DONE -> AgentForegroundService.stop(context, "Задача завершена", chatId, notify)
                AgentState.ERROR -> AgentForegroundService.stop(context, "Задача завершена с ошибкой", chatId, notify)
                AgentState.CANCELLED -> AgentForegroundService.stop(context, "Задача отменена", chatId, notify)
                else -> AgentForegroundService.stop(context, chatId = chatId, postNotification = notify)
            }
        } catch (e: Exception) {
            Log.w(TAG, "FGS stop failed: ${e.message}")
        }
    }

    /**
     * Appends the honest "the run was force-stopped" note to the live assistant message (or creates
     * one when the turn had not persisted anything yet). Runs off [scope]; failures are swallowed
     * because the reset of the in-memory state is what actually unblocks the UI.
     */
    fun persistForcedStopNote(scope: CoroutineScope, chatId: Long, assistantId: Long?) {
        scope.launch {
            runCatching {
                val note = RunLifecyclePolicy.FORCED_STOP_MESSAGE
                val current = assistantId?.let { id ->
                    repository.observeMessages(chatId).first().firstOrNull { it.id == id }?.content
                }
                if (assistantId != null && current != null) {
                    // Keep the partial answer and state honestly that it was cut off.
                    val body = if (current.isBlank()) note else "$current\n\n$note"
                    repository.updateMessageContent(assistantId, body)
                    repository.setStreaming(assistantId, false)
                } else {
                    repository.addMessage(chatId, "assistant", note)
                }
            }.onFailure { Log.w(TAG, "Could not persist the watchdog note: ${it.message}") }
        }
    }

    private companion object {
        private const val TAG = "Andharness"
    }
}
