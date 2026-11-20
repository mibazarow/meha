package com.andharness.ru.data.tools.ui

import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `ui_macro_run`: replays a persisted macro step by step, with honest pre-/post-conditions. */
internal class UiMacroRunToolHandler(
    private val engine: UiActionEngine,
    private val macros: UiMacroStore,
    private val replay: UiMacroReplay,
) : ToolHandler {
    override val name = "ui_macro_run"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.Main) {
        try {
            val target = arguments.str("id") ?: arguments.str("name")
                ?: return@withContext ToolResult(false, "Укажите id или name макроса.")
            val macro = macros.find(target)
                ?: return@withContext ToolResult(false, "Макрос не найден: «$target».")
            if (macro.steps.isEmpty()) {
                return@withContext ToolResult(
                    false,
                    "В макросе «${macro.name}» нет шагов — воспроизводить нечего."
                )
            }
            if (engine.serviceOrNull() == null) return@withContext engine.notEnabled()

            // Aggregated observations of the per-step post-checks (informational only).
            var selectorSteps = 0
            var targetStillPresent = 0
            var typePostConditions = 0

            for ((index, step) in macro.steps.withIndex()) {
                val stepNo = index + 1
                // Re-read for every step: the user may revoke the service mid-replay.
                val service = engine.serviceOrNull()
                    ?: return@withContext ToolResult(
                        false,
                        "Макрос «${macro.name}» прерван: шаг $stepNo («${step.tool}») не выполнен — " +
                            "служба спецвозможностей отключилась."
                    )
                val stepArgs = replay.parseStepArgs(step.argsJson)
                    ?: return@withContext ToolResult(
                        false,
                        "Макрос «${macro.name}» прерван: шаг $stepNo («${step.tool}») содержит " +
                            "некорректные аргументы."
                    )

                // Pre-condition: the target must resolve in the tree read BEFORE the action.
                // A tree read after the action cannot tell whether the target was still there
                // when it was acted on, so that unsound post-persistence check is gone.
                val precondition = replay.preconditionFailure(service, step.tool, stepArgs)
                if (precondition != null) {
                    return@withContext ToolResult(
                        false,
                        "Макрос «${macro.name}» прерван: шаг $stepNo («${step.tool}») не выполнен — " +
                            "$precondition."
                    )
                }

                val result = replay.runStep(service, step.tool, stepArgs)
                    ?: return@withContext ToolResult(
                        false,
                        "Макрос «${macro.name}» прерван: шаг $stepNo — неизвестное действие «${step.tool}»."
                    )
                if (!result.success) {
                    return@withContext ToolResult(
                        false,
                        "Макрос «${macro.name}» прерван: шаг $stepNo («${step.tool}») не выполнен. " +
                            result.output
                    )
                }

                // Bounded settle BEFORE the post-check, so the tree read below reflects the
                // post-action state instead of the stale pre-action hierarchy.
                delay(UiMacroReplay.STEP_SETTLE_MS)
                if (engine.serviceOrNull() == null) {
                    return@withContext ToolResult(
                        false,
                        "Макрос «${macro.name}» прерван: шаг $stepNo («${step.tool}») — " +
                            "служба спецвозможностей недоступна."
                    )
                }

                val post = replay.postCheck(service, step.tool, stepArgs)
                if (post.failure != null) {
                    return@withContext ToolResult(
                        false,
                        "Макрос «${macro.name}» прерван: шаг $stepNo («${step.tool}») — " +
                            "${post.failure}."
                    )
                }
                if (post.hasSelector) {
                    selectorSteps++
                    if (post.targetPresent) targetStillPresent++
                    if (post.typeConfirmed) typePostConditions++
                }
            }

            // Honest report: only claim what was actually established. Preconditions are
            // confirmed for every selector step; a `ui_type` payload is a real post-condition.
            // Target persistence after a tap/scroll is only an observation (navigation may
            // legitimately remove the target), so it is never reported as a confirmation.
            val summary = buildString {
                append(
                    "Макрос «${macro.name}» воспроизведён: ${macro.steps.size} шагов, " +
                        "все шаги выполнены; предусловия подтверждены."
                )
                if (typePostConditions > 0) {
                    append(" Постусловие ввода подтверждено для $typePostConditions шаг(ов).")
                }
                if (selectorSteps > 0) {
                    append(
                        " Наблюдение: цель осталась на экране для $targetStillPresent из " +
                            "$selectorSteps шагов с селектором (исчезновение цели не считается ошибкой: " +
                            "возможен переход)."
                    )
                }
            }
            ToolResult(true, summary)
        } catch (e: CancellationException) {
            // A cancel/withTimeout must reach the run loop instead of being reported as a
            // normal tool failure (update.md bug #7) — otherwise the timeout is defeated.
            throw e
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
