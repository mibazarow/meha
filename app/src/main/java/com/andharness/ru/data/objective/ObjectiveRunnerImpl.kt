package com.andharness.ru.data.objective

import android.util.Log
import com.andharness.ru.domain.diagnostics.PowerGuardPolicy
import com.andharness.ru.domain.diagnostics.PowerStateProvider
import com.andharness.ru.domain.agent.AgentOrchestrator
import com.andharness.ru.domain.agent.AgentRunOutcome
import com.andharness.ru.domain.agent.AgentRunPriority
import com.andharness.ru.domain.chat.ChatRepository
import com.andharness.ru.domain.objective.Objective
import com.andharness.ru.domain.objective.ObjectiveRunGate
import com.andharness.ru.domain.objective.ObjectiveRunOutcome
import com.andharness.ru.domain.objective.ObjectiveRunResult
import com.andharness.ru.domain.objective.ObjectiveRunner
import com.andharness.ru.domain.objective.ObjectiveRules
import com.andharness.ru.domain.objective.ObjectiveStepStatus
import com.andharness.ru.domain.settings.LlmSettingsStore
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real [ObjectiveRunner]: drives ONE bounded step of an objective through the SAME
 * `AgentOrchestrator.run(chatId, text)` entry point the overlay assistant and scheduled tasks use, so
 * the whole turn runs off the UI, persists its messages and is bounded by `RunLifecyclePolicy`.
 *
 * The runner NEVER bypasses `RunLifecyclePolicy`: it adds only a tighter per-step wall-clock budget
 * ([ObjectiveRunner.STEP_BUDGET_MS], 10 min) on top of the orchestrator's own caps, and it cancels the
 * turn through the normal coroutine-cancellation path (which finalizes it honestly) if the budget is
 * hit. It also respects the power guard: a deferred (critical thermal/battery) device is never forced
 * to run, and no LLM provider means an honest [ObjectiveRunOutcome.NO_LLM] instead of fake progress.
 *
 * Progress is recorded by the AGENT itself via `objective_step_update`; this class only bumps the
 * background-run budget (`store.recordBackgroundRun`) before the turn so the budget cannot be gamed.
 */
@Singleton
class ObjectiveRunnerImpl @Inject constructor(
    private val store: ObjectiveStore,
    private val orchestrator: Lazy<AgentOrchestrator>,
    private val settings: LlmSettingsStore,
    private val powerGuard: PowerStateProvider,
    private val chatRepository: ChatRepository
) : ObjectiveRunner {

    override suspend fun runStep(objectiveId: String): ObjectiveRunResult {
        val objective = store.get(objectiveId)
            ?: return ObjectiveRunResult(ObjectiveRunOutcome.NOT_FOUND, "цель «$objectiveId» не найдена")

        val llmConfigured = runCatching { settings.isConfigured().first() }.getOrDefault(false)
        val deferred = runCatching { PowerGuardPolicy.evaluate(powerGuard.snapshot(), false).defer }
            .getOrDefault(false)
        val gate = ObjectiveRunGate.evaluate(
            status = objective.status,
            blocked = ObjectiveRules.isBlocked(objective),
            llmConfigured = llmConfigured,
            deferred = deferred,
            backgroundRuns = objective.backgroundRuns
        )
        if (gate != ObjectiveRunOutcome.RUNNABLE) {
            return ObjectiveRunResult(gate, explain(gate))
        }

        // A background objective may have been created without a chat; bind one so the agent turn has
        // a place to persist its messages (the same createChat path the debug seeder uses). A bound
        // chat may however have been DELETED since: `messages.chatId` is a FOREIGN KEY into `chats`,
        // so running against a dangling id would throw SQLiteConstraintException (code 787). Validate
        // it first and, when it is gone, refuse honestly and block the objective instead of throwing.
        val chatId = when (val bound = objective.chatId) {
            null -> runCatching { chatRepository.createChat(objective.title) }.getOrNull()
                ?: return ObjectiveRunResult(ObjectiveRunOutcome.FAILED, "не удалось создать чат для цели")
            else -> {
                val boundChatAlive = runCatching { chatRepository.chatExists(bound) }.getOrDefault(false)
                if (!boundChatAlive) return blockMissingChat(objective)
                bound
            }
        }
        if (objective.chatId == null) {
            runCatching { store.put(objective.copy(chatId = chatId)) }
        }

        // Budget accounting: a real attempt counts, but a BUSY result means the turn did NOT run, so
        // it must not consume the objective's background run budget.
        val instruction = buildInstruction(objective)
        val outcome = try {
            withTimeout(ObjectiveRunner.STEP_BUDGET_MS) {
                orchestrator.get().run(chatId, instruction, priority = AgentRunPriority.BACKGROUND)
            }
        } catch (timeout: TimeoutCancellationException) {
            // The step budget is enforced by cancelling the caller's `run()` wait; the turn itself
            // now lives on the orchestrator's application scope, so it must be stopped explicitly
            // (otherwise it would keep running past the budget).
            runCatching { orchestrator.get().cancel(chatId) }
                .onFailure { Log.w(TAG, "cancelling the timed-out objective step failed: ${it.message}") }
            store.recordBackgroundRun(objective.id)
            return ObjectiveRunResult(
                ObjectiveRunOutcome.BUDGET_EXHAUSTED,
                "шаг не завершился за ${ObjectiveRunner.STEP_BUDGET_MS / 60_000} мин и был прерван"
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            store.recordBackgroundRun(objective.id)
            Log.e(TAG, "objective step $objectiveId failed", e)
            return ObjectiveRunResult(
                ObjectiveRunOutcome.FAILED,
                e.message ?: e.javaClass.simpleName
            )
        }
        if (outcome != AgentRunOutcome.BUSY) store.recordBackgroundRun(objective.id)
        return when (outcome) {
            AgentRunOutcome.STARTED -> ObjectiveRunResult(
                ObjectiveRunOutcome.ADVANCED,
                "агент отработал шаг; прогресс — через objective_step_update"
            )
            AgentRunOutcome.BUSY -> ObjectiveRunResult(
                ObjectiveRunOutcome.BUSY,
                "агент занят другим прогоном — шаг не выполнялся"
            )
        }
    }

    /**
     * Refuses to run an objective whose bound chat was deleted: it marks the first unfinished step
     * BLOCKED with an honest note (so the objective is no longer auto-run and the status shows why)
     * and reports [ObjectiveRunOutcome.NO_CHAT]. Never throws; nothing is written to the missing chat.
     */
    private fun blockMissingChat(objective: Objective): ObjectiveRunResult {
        ObjectiveRules.firstUnfinished(objective)?.let { step ->
            runCatching {
                store.updateStep(objective.id, step.id, ObjectiveStepStatus.BLOCKED, MISSING_CHAT_NOTE)
            }
        }
        Log.w(TAG, "objective ${objective.id} bound to a missing chat — not run")
        return ObjectiveRunResult(ObjectiveRunOutcome.NO_CHAT, MISSING_CHAT_NOTE)
    }

    private fun buildInstruction(objective: Objective): String {
        val progress = ObjectiveRules.progress(objective)
        val next = ObjectiveRules.nextStep(objective)
        return buildString {
            append("Продолжай долгую цель [").append(objective.id).append("] «")
                .append(objective.title).append("». ")
            append("Прогресс ").append(progress.done).append('/').append(progress.total).append(" шагов. ")
            if (next != null) {
                append("Следующий шаг [").append(next.id).append("]: ").append(next.text).append(". ")
            } else if (progress.total > 0 && progress.done == progress.total) {
                append("Все шаги отмечены выполненными — заверши цель через objective_finish. ")
            }
            append("Сделай следующий шаг и обязательно отметь его через objective_step_update ")
            append("(done или blocked, с короткой заметкой). Работай только по этой цели и не ")
            append("выдумывай выполненное.")
        }
    }

    private fun explain(outcome: ObjectiveRunOutcome): String = when (outcome) {
        ObjectiveRunOutcome.TERMINAL -> "цель уже завершена/отменена — не перезапускается"
        ObjectiveRunOutcome.PAUSED -> "цель на паузе — сначала objective_resume"
        ObjectiveRunOutcome.BLOCKED -> "первый незавершённый шаг заблокирован — сначала снимите блокировку"
        ObjectiveRunOutcome.BUDGET_EXHAUSTED ->
            "исчерпан бюджет фоновых шагов (${ObjectiveRunGate.MAX_BACKGROUND_RUNS}) — продолжите вручную"
        ObjectiveRunOutcome.NO_LLM -> "LLM не настроена — фон не выполнялся"
        ObjectiveRunOutcome.DEFERRED -> "охрана питания отложила фоновую работу"
        ObjectiveRunOutcome.NO_CHAT -> MISSING_CHAT_NOTE
        ObjectiveRunOutcome.NOT_FOUND -> "цель не найдена"
        ObjectiveRunOutcome.RUNNABLE -> ""
        ObjectiveRunOutcome.STARTED -> ""
        ObjectiveRunOutcome.ADVANCED -> ""
        ObjectiveRunOutcome.BUSY -> ""
        ObjectiveRunOutcome.FAILED -> ""
    }

    private companion object {
        const val TAG = "Andharness"

        /** Honest note written to the blocked step when the objective's chat no longer exists. */
        const val MISSING_CHAT_NOTE =
            "чат цели удалён — фоновое выполнение невозможно; создайте новый чат и уберите " +
                "блокировку шага (objective_step_update)"
    }
}
