package com.andharness.ru.data.tools.skill

import android.content.Context
import com.andharness.ru.data.skill.SkillStore
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.service.AccessibilityAutomationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.andharness.ru.domain.serialization.AppJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Android-bound executor for a [SkillPlan]: the `skill_run` tool coordinator.
 *
 * It owns the honest gating (refuse a non-runnable plan, refuse when the accessibility service is
 * off), the per-step timeout and the abort-on-failure loop; the actual UI actions live in
 * [SkillStepExecutor] and the planning lives in the pure [SkillPlanner]. Every step runs under a
 * bounded timeout; a failed step aborts the run and is reported by number.
 */
class SkillRunEngine(
    private val context: Context,
    private val store: SkillStore,
) {

    private val executor = SkillStepExecutor(context)

    /** The `skill_run` tool: replays a stored skill, or validates its plan with `dry_run=true`. */
    fun handler(): ToolHandler = object : ToolHandler {
        override val name = "skill_run"

        override suspend fun execute(arguments: JsonObject): ToolResult {
            return try {
            val query = arguments.str("id") ?: arguments.str("name")
                ?: return@execute ToolResult(
                    false,
                    "Укажи id или name навыка, который нужно запустить."
                )
            val skill = store.get(query)
                ?: return@execute ToolResult(
                    false,
                    "Навык «$query» не найден. Посмотри список через skill_list."
                )
            val values = parseValues(arguments)
                ?: return@execute ToolResult(
                    false,
                    "values должен быть JSON-объектом вида {\"параметр\":\"значение\"}."
                )
            val dryRun = arguments.str("dry_run")?.toBooleanStrictOrNull() ?: false

            val plan = SkillPlanner.plan(skill, values)
            if (dryRun) {
                val header = if (plan.isRunnable) {
                    "Сухой прогон (dry_run): экран не затрагивался.\n\n"
                } else {
                    "Сухой прогон (dry_run): запуск невозможен, экран не затрагивался.\n\n"
                }
                return@execute ToolResult(plan.isRunnable, header + plan.render())
            }
            if (!plan.isRunnable) {
                return@execute ToolResult(
                    false,
                    "Навык «${skill.name}» нельзя запустить:\n" +
                        (plan.issues + plan.stepIssues).joinToString("\n") { "  - $it" }
                )
            }
            if (plan.steps.any { it.needsAccessibility } && AccessibilityAutomationService.instance == null) {
                return@execute ToolResult(
                    false,
                    "Служба спецвозможностей Меха выключена — шаги навыка выполняются через неё. " +
                        "Включи спецвозможности и повтори запуск."
                )
            }
            val reports = runPlan(plan)
            val abortedAt = reports.indexOfFirst { !it.success }
            // Idle GC bookkeeping: a fully successful REAL run (never a dry-run or a failed one)
            // counts as usage, so the skill is kept. Best-effort — a failed stamp must not fail the run.
            if (abortedAt < 0) runCatching { store.touch(skill.id) }
            ToolResult(
                success = abortedAt < 0,
                output = renderRun(plan, reports, abortedAt),
            )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolResult(false, "Ошибка запуска навыка: ${e.message}")
            }
        }
    }

    /**
     * Executes every step in order, on the main thread so `AccessibilityNodeInfo.performAction` /
     * `dispatchGesture` / `performGlobalAction` are called where the framework expects. `delay`
     * suspends (never blocks the UI). A failure aborts immediately with the honest partial report.
     */
    private suspend fun runPlan(plan: SkillPlan): List<StepReport> = withContext(Dispatchers.Main) {
        val reports = ArrayList<StepReport>(plan.steps.size)
        for (step in plan.steps) {
            if (step.needsAccessibility && AccessibilityAutomationService.instance == null) {
                reports += StepReport(
                    index = step.index,
                    action = step.action,
                    success = false,
                    detail = "служба спецвозможностей выключена — включи её и повтори запуск",
                )
                return@withContext reports
            }
            val budget = maxOf(DEFAULT_STEP_TIMEOUT_MS, step.timeoutMs + RETRY_MARGIN_MS)
            val outcome = withTimeoutOrNull(budget) { executor.execute(step) }
                ?: StepOutcome(false, "таймаут шага ($budget мс) — шаг не завершился")
            reports += StepReport(step.index, step.action, outcome.success, outcome.detail)
            if (!outcome.success) return@withContext reports
            delay(STEP_SETTLE_MS)
        }
        reports
    }

    private fun renderRun(plan: SkillPlan, reports: List<StepReport>, abortedAt: Int): String = buildString {
        appendLine("Навык «${plan.skillName}»: выполнено ${reports.count { it.success }} из ${plan.steps.size} шагов.")
        reports.forEach { report ->
            appendLine(
                "  ${report.index}. ${report.action} — " +
                    (if (report.success) "OK" else "ОШИБКА") + ": ${report.detail}"
            )
        }
        if (abortedAt >= 0) {
            val failed = reports[abortedAt]
            append("Запуск прерван на шаге ${failed.index} (${failed.action}): ${failed.detail}.")
        } else {
            append("Все шаги выполнены.")
        }
    }.trim()

    private fun parseValues(arguments: JsonObject): Map<String, String>? {
        val raw = arguments.str("values") ?: return emptyMap()
        return try {
            val element = VALUES_JSON.parseToJsonElement(raw)
            val obj = element.jsonObject
            obj.entries.mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { key to it }
            }.toMap()
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        /** Whole-step budget; a single skill step can never hang the agent turn. */
        const val DEFAULT_STEP_TIMEOUT_MS = 20_000L
        const val RETRY_MARGIN_MS = 2_000L

        const val STEP_SETTLE_MS = 400L

        val VALUES_JSON = AppJson.Lenient
    }
}
