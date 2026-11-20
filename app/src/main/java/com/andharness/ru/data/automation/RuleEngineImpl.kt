package com.andharness.ru.data.automation

import android.util.Log
import com.andharness.ru.data.notifications.NotificationChatRouter
import com.andharness.ru.data.notifications.NotificationChatTopics
import com.andharness.ru.data.notifications.NotificationHelper
import com.andharness.ru.domain.agent.AgentOrchestrator
import com.andharness.ru.domain.agent.AgentRunOutcome
import com.andharness.ru.domain.agent.AgentRunPriority
import com.andharness.ru.domain.agent.AgentState
import com.andharness.ru.domain.automation.AgentTaskTemplate
import com.andharness.ru.domain.automation.AutomationEvent
import com.andharness.ru.domain.automation.AutomationEventRepository
import com.andharness.ru.domain.automation.AutomationRule
import com.andharness.ru.domain.automation.AutomationRuleRepository
import com.andharness.ru.domain.automation.AutomationSpec
import com.andharness.ru.domain.automation.RuleEngine
import com.andharness.ru.domain.automation.RuleRateLimiter
import com.andharness.ru.domain.chat.ChatRepository
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [RuleEngine] (update.md §11.6): loads the enabled rules that match an incoming
 * [AutomationEvent], runs each rule's action, appends one row per fired rule to the persistent
 * event log, stamps `lastRunAt` and guards against re-entrancy
 * (a rule already executing is skipped until it finishes). A per-rule [RuleRateLimiter] throttles
 * rapid re-fires and auto-disables rules that keep firing (see [autoDisableNoisy]).
 *
 * Actions are intentionally limited to [AutomationSpec.ACTION_START_CHAT] (kick off an agent run in a
 * new chat), [AutomationSpec.ACTION_AGENT_TASK] (same, but the prompt is an event-aware template
 * rendered by [AgentTaskTemplate] and failures are reported honestly) and
 * [AutomationSpec.ACTION_NOTIFY] (post a notification) — no arbitrary tool execution, so an
 * automation can never mutate the device behind the user's back.
 */
@Singleton
class RuleEngineImpl @Inject constructor(
    private val repository: AutomationRuleRepository,
    private val eventRepository: AutomationEventRepository,
    private val chatRepository: ChatRepository,
    private val orchestrator: Lazy<AgentOrchestrator>,
    private val notifications: NotificationHelper,
    private val notificationChatRouter: NotificationChatRouter
) : RuleEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap.newKeySet<Long>()

    /**
     * Runtime safety net against degenerate always-true triggers (a `-100 dBFS` sound rule used to
     * fire ~once a minute). Plain field, not a Hilt binding: this class is already a `@Singleton`,
     * so one limiter instance is shared by every caller of the engine.
     */
    private val rateLimiter = RuleRateLimiter()

    override fun dispatch(event: AutomationEvent) {
        scope.launch {
            runCatching { handle(event) }
                .onFailure { Log.e(TAG, "Automation dispatch failed for ${event.triggerType}", it) }
        }
    }

    override suspend fun handle(event: AutomationEvent): List<Long> {
        val candidates = if (event.ruleId != null) {
            listOfNotNull(repository.getById(event.ruleId))
        } else {
            repository.getAll().filter { it.enabled && it.triggerType == event.triggerType }
        }
        return candidates
            .filter { AutomationSpec.matches(it, event) }
            .mapNotNull { rule -> runRule(rule, event) }
    }

    /**
     * Runs one rule, or returns null when it was skipped (already running, rate-limited, or
     * auto-disabled as noisy). [event] is the concrete occurrence that fired the rule; an
     * `agent_task` action needs it to render its prompt template.
     */
    private suspend fun runRule(rule: AutomationRule, event: AutomationEvent): Long? {
        if (!running.add(rule.id)) {
            Log.w(TAG, "Automation rule ${rule.id} already running — skipped")
            return null
        }
        return try {
            val now = System.currentTimeMillis()
            when (rateLimiter.evaluate(rule.id, now)) {
                RuleRateLimiter.Decision.SkipThrottled -> {
                    Log.d(
                        TAG,
                        "Automation rule ${rule.id} throttled (min interval " +
                            "${RuleRateLimiter.MIN_INTERVAL_MS} ms) — skipped"
                    )
                    return null
                }

                RuleRateLimiter.Decision.DisableNoisy -> {
                    autoDisableNoisy(rule)
                    return null
                }

                RuleRateLimiter.Decision.Fire -> Unit
            }
            executeAction(rule, event)
            repository.markRun(rule.id, now)
            recordFiredEvent(rule, event)
            rule.id
        } catch (e: Exception) {
            Log.e(TAG, "Automation rule ${rule.id} failed", e)
            null
        } finally {
            running.remove(rule.id)
        }
    }

    /**
     * Appends one row to the persistent firing-event log. Deliberately
     * fire-and-forget on the engine's IO scope: a logging failure must never break the rule run, and
     * only events that actually fired a rule are recorded (not every raw sensor observation). The
     * row carries the trigger type, the observed value/payload and the rule id+name as of now.
     */
    private fun recordFiredEvent(rule: AutomationRule, event: AutomationEvent) {
        scope.launch {
            runCatching { eventRepository.record(event, rule) }
                .onFailure { Log.e(TAG, "Recording automation event for rule ${rule.id} failed", it) }
        }
    }

    /**
     * Persists the noisy verdict as "disabled" and tells the user. The rate limiter only reports
     * [RuleRateLimiter.Decision.DisableNoisy] once per rule, so this notification cannot repeat for
     * the same rule until its state is reset.
     */
    private suspend fun autoDisableNoisy(rule: AutomationRule) {
        Log.w(
            TAG,
            "Automation rule ${rule.id} (${rule.name}) disabled: fired " +
                "${RuleRateLimiter.NOISY_MAX_FIRES} times within " +
                "${RuleRateLimiter.NOISY_WINDOW_MS} ms"
        )
        try {
            repository.setEnabled(rule.id, false)
        } catch (e: Exception) {
            Log.e(TAG, "Disabling noisy automation rule ${rule.id} failed", e)
        }
        val text = "Правило «${rule.name}» отключено: слишком часто срабатывает"
        val chatId = notificationChatRouter.routeIfNeeded(
            topic = NotificationChatTopics.AUTOMATION,
            title = NotificationChatTopics.AUTOMATION_TITLE,
            text = text,
            chatId = null,
        )
        notifications.postDoneNotification(
            text = text,
            chatId = chatId,
            notificationId = notificationId(rule.id)
        )
    }

    private suspend fun executeAction(rule: AutomationRule, event: AutomationEvent) {
        when (rule.actionType) {
            // start_chat runs the raw config; agent_task renders the event-aware template first.
            AutomationSpec.ACTION_START_CHAT ->
                runAgent(rule, rule.actionConfig.trim(), honest = false)
            AutomationSpec.ACTION_AGENT_TASK ->
                runAgent(
                    rule,
                    AgentTaskTemplate.render(rule.actionConfig.trim(), event, rule.name),
                    honest = true
                )
            AutomationSpec.ACTION_NOTIFY -> notify(rule)
            else -> Log.w(TAG, "Automation rule ${rule.id} has unknown action ${rule.actionType}")
        }
    }

    /**
     * Shared agent-run path for `start_chat` and `agent_task`: [prompt] is already final (the raw
     * config for `start_chat`, the rendered [AgentTaskTemplate] for `agent_task`), a fresh chat is
     * created and one turn is run.
     *
     * Delivery mirrors [com.andharness.ru.bg.ScheduleWorker]:
     * - [AgentRunOutcome.BUSY] and a run that ends [AgentState.ERROR] / [AgentState.CANCELLED] are
     *   reported honestly — the reason is written into the chat and the notification says the rule
     *   was NOT completed, instead of claiming success.
     * - On success the notification keeps the original wording («Правило «…» выполнено»), so
     *   `start_chat`'s success output is unchanged.
     *
     * [honest] keeps the legacy `start_chat` failure path conservative: it still only logs, while
     * the new `agent_task` surfaces failures.
     */
    private suspend fun runAgent(rule: AutomationRule, prompt: String, honest: Boolean) {
        if (prompt.isEmpty()) return
        val chatId = chatRepository.createChat(titleFor(rule))
        var failure: String? = null
        var threw = false
        try {
            // Lazy so that merely constructing the trigger manager at app start does not build the
            // whole agent/tool graph; the orchestrator is resolved only when a rule actually runs.
            val outcome = orchestrator.get().run(chatId, prompt, priority = AgentRunPriority.BACKGROUND)
            // Classify THIS run by its OWN chat's status, never by the deprecated viewed-chat
            // shortcut (which follows the last INTERACTIVE chat and would misreport a background
            // automation run's success/failure).
            val state = orchestrator.get().statusFor(chatId).value.state
            failure = when {
                outcome == AgentRunOutcome.BUSY -> REASON_BUSY
                state == AgentState.ERROR -> REASON_ERROR
                state == AgentState.CANCELLED -> REASON_CANCELLED
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Automation rule ${rule.id} agent run failed", e)
            threw = true
            failure = e.message?.takeIf { it.isNotBlank() } ?: REASON_UNKNOWN
        }

        if (honest && failure != null) {
            // Never leave the chat empty and never stay silent about a failed run.
            runCatching {
                chatRepository.addMessage(chatId, ROLE_ASSISTANT, failureMessage(failure))
            }
            notifications.postDoneNotification(
                text = "Правило «${rule.name}» не выполнено: $failure",
                chatId = chatId,
                notificationId = notificationId(rule.id)
            )
            return
        }

        // Legacy start_chat path: a thrown run stays silent (as before), anything else reports the
        // original success wording.
        if (!honest && threw) return
        notifications.postDoneNotification(
            text = "Правило «${rule.name}» выполнено",
            chatId = chatId,
            notificationId = notificationId(rule.id)
        )
    }

    /** Honest chat body for a failed `agent_task` run, in the spirit of `ScheduleRunPlan.failureMessage`. */
    private fun failureMessage(reason: String): String =
        "Не получилось выполнить действие автоматизации: $reason"

    private fun notify(rule: AutomationRule) {
        val spec = AutomationSpec.parseNotify(rule.actionConfig) ?: return
        notifications.postDoneNotification(
            text = spec.text,
            chatId = spec.chatId,
            notificationId = notificationId(rule.id),
            title = spec.title
        )
    }

    private fun titleFor(rule: AutomationRule): String =
        "Автоматизация: " + rule.name.replace('\n', ' ').take(50)

    /** Stable per-rule notification id so two rules never overwrite each other's notifications. */
    private fun notificationId(ruleId: Long): Int =
        NotificationHelper.NOTIFICATION_ID_AUTOMATION_BASE +
            (ruleId % NotificationHelper.NOTIFICATION_ID_ITEM_MODULUS).toInt()

    companion object {
        private const val TAG = "Andharness"
        private const val ROLE_ASSISTANT = "assistant"

        /** Failure reasons mirror `ScheduleWorker` so both background paths word failures the same. */
        private const val REASON_BUSY = "агент уже выполняет другой запрос — запуск пропущен"
        private const val REASON_ERROR = "агент завершил с ошибкой (подробности в чате)"
        private const val REASON_CANCELLED = "выполнение отменено"
        private const val REASON_UNKNOWN = "неизвестная ошибка"
    }
}
