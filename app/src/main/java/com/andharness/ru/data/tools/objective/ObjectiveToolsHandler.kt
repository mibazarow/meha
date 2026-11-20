package com.andharness.ru.data.tools.objective

import com.andharness.ru.data.objective.ObjectiveRunCoordinator
import com.andharness.ru.data.objective.ObjectiveStore
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.chat.CurrentChatProvider
import com.andharness.ru.domain.objective.Objective
import com.andharness.ru.domain.objective.ObjectiveConcurrencyPolicy
import com.andharness.ru.domain.objective.ObjectiveOutcome
import com.andharness.ru.domain.objective.ObjectiveRunOutcome
import com.andharness.ru.domain.objective.ObjectiveRunReport
import com.andharness.ru.domain.objective.ObjectiveRules
import com.andharness.ru.domain.objective.ObjectiveStatus
import com.andharness.ru.domain.objective.ObjectiveStepStatus
import com.andharness.ru.domain.objective.ObjectiveStopReport
import com.andharness.ru.domain.objective.ObjectiveWorkItem
import com.andharness.ru.domain.objective.ObjectiveWorkersStatus
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.andharness.ru.domain.serialization.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Tools for long-running OBJECTIVES (checklist §1): the agent creates a goal with a plan, works it
 * step by step across turns (writing checkpoints with `objective_step_update`) and continues it in a
 * later session because the objective is persisted on disk and re-injected into the prompt.
 *
 * Everything is honest: a terminal objective is never silently reopened, marking a step `done` is
 * the model's own explicit action (never inferred), and a missing objective/step is reported as
 * NOT_FOUND instead of being fabricated. Only create/step_update/finish/cancel/resume change state.
 */
class ObjectiveToolsHandler(
    private val store: ObjectiveStore,
    private val currentChatProvider: CurrentChatProvider,
    private val coordinator: ObjectiveRunCoordinator
) {

    fun handlers(): List<ToolHandler> = listOf(
        create(),
        listObjectives(),
        get(),
        stepUpdate(),
        next(),
        finish(),
        cancel(),
        resume(),
        pause(),
        run(),
        stop(),
        workersStatus(),
        concurrencySet()
    )

    private fun create(): ToolHandler = object : ToolHandler {
        override val name: String = "objective_create"

        override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val title = arguments.str("title")
            val goal = arguments.str("goal")
            if (title == null || goal == null) {
                return@withContext ToolResult(false, "Укажите title и goal — название и что нужно получить в итоге.")
            }
            val explicitChat = longOrNull(arguments, "chat_id")
            val chatId = explicitChat ?: currentChatProvider.chatId()
            val steps = parseSteps(arguments["steps"])
            val objective = store.create(title, goal, steps, chatId)
                ?: return@withContext ToolResult(false, "Не удалось создать цель: пустые title/goal.")
            ToolResult(true, "Создана цель ${objective.id}.\n" + formatDetail(objective))
        }
    }

    private fun listObjectives(): ToolHandler = object : ToolHandler {
        override val name: String = "objective_list"

        override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val rawStatus = arguments.str("status")
            val statusFilter: ObjectiveStatus?
            if (rawStatus == null) {
                statusFilter = null
            } else {
                statusFilter = parseObjectiveStatus(rawStatus)
                    ?: return@withContext ToolResult(
                        false,
                        "Неизвестный статус «$rawStatus»: используй active, paused, done или cancelled."
                    )
            }
            val all = store.list()
            val filtered = if (statusFilter == null) all else all.filter { it.status == statusFilter }
            if (filtered.isEmpty()) {
                val note = if (statusFilter != null) {
                    "Целей со статусом «${objectiveStatusRu(statusFilter)}» нет."
                } else {
                    "Долгих целей пока нет."
                }
                return@withContext ToolResult(true, note)
            }
            val text = buildString {
                append("Долгие цели (${filtered.size}):")
                filtered.forEach { objective ->
                    append('\n').append(formatSummaryLine(objective))
                }
            }
            ToolResult(true, text)
        }
    }

    private fun get(): ToolHandler = object : ToolHandler {
        override val name: String = "objective_get"

        override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val ref = arguments.str("id")
                ?: return@withContext ToolResult(false, "Укажите id цели (id или название).")
            val objective = store.get(ref)
                ?: return@withContext ToolResult(false, "Цель «$ref» не найдена.")
            ToolResult(true, formatDetail(objective))
        }
    }

    private fun stepUpdate(): ToolHandler = object : ToolHandler {
        override val name: String = "objective_step_update"

        override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val stepRef = arguments.str("step")
                ?: return@withContext ToolResult(false, "Укажите step — id шага или его номер (1, 2, …).")
            val rawStatus = arguments.str("status")
                ?: return@withContext ToolResult(false, "Укажите status шага: pending, in_progress, done или blocked.")
            val status = parseStepStatus(rawStatus)
                ?: return@withContext ToolResult(
                    false,
                    "Неизвестный статус «$rawStatus»: используй pending, in_progress, done или blocked."
                )
            val target = resolveTarget(arguments) ?: return@withContext noActive("objective_step_update")
            val note = arguments.str("note")
            val result = store.updateStep(target.id, stepRef, status, note)
            when (result.outcome) {
                ObjectiveOutcome.CHANGED -> {
                    val step = result.step!!
                    ToolResult(
                        true,
                        "Шаг [${step.id}] «${step.text}» → ${stepStatusRu(step.status)}" +
                            (if (step.note.isNotBlank()) " (заметка: ${step.note})" else "") + "\n" +
                            formatSummaryLine(result.objective!!)
                    )
                }
                ObjectiveOutcome.NOOP_SAME ->
                    ToolResult(true, "Шаг уже в этом статусе — ничего не изменилось.\n" + formatSummaryLine(result.objective!!))
                ObjectiveOutcome.NOOP_TERMINAL ->
                    ToolResult(false, "Цель уже завершена (${objectiveStatusRu(result.objective!!.status)}) — шаги не меняются.")
                ObjectiveOutcome.NOT_FOUND ->
                    ToolResult(false, "Цель «${target.id}» найдена, но шаг «$stepRef» не найден.")
                ObjectiveOutcome.INVALID ->
                    ToolResult(false, "Некорректные аргументы шага.")
            }
        }
    }

    private fun next(): ToolHandler = object : ToolHandler {
        override val name: String = "objective_next"

        override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val target = resolveTarget(arguments) ?: return@withContext noActive("objective_next")
            val step = ObjectiveRules.nextStep(target)
            val progress = ObjectiveRules.progress(target)
            val text = buildString {
                append("Цель ${target.id} «${target.title}» — ${objectiveStatusRu(target.status)}, ")
                append("прогресс ${progress.done}/${progress.total}.")
                if (step == null) {
                    if (progress.total > 0 && progress.done == progress.total) {
                        append("\nВсе шаги выполнены — заверши цель через objective_finish.")
                    } else {
                        append("\nСледующего шага нет (нет незавершённых шагов).")
                    }
                } else {
                    append("\nСледующий шаг [${step.id}] (${stepStatusRu(step.status)}): ${step.text}")
                    append("\nДелай этот шаг, затем отметь его через objective_step_update.")
                }
            }
            // A read-only call is still forward progress on the objective: bump lastWorkedAt so the
            // resume view stays truthful. `next` never claims the step was completed.
            store.touch(target.id)
            ToolResult(true, text)
        }
    }

    private fun finish(): ToolHandler = object : ToolHandler {
        override val name: String = "objective_finish"

        override suspend fun execute(arguments: JsonObject): ToolResult =
            setTerminal(arguments, ObjectiveStatus.DONE)
    }

    private fun cancel(): ToolHandler = object : ToolHandler {
        override val name: String = "objective_cancel"

        override suspend fun execute(arguments: JsonObject): ToolResult =
            setTerminal(arguments, ObjectiveStatus.CANCELLED)
    }

    private fun resume(): ToolHandler = object : ToolHandler {
        override val name: String = "objective_resume"

        override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val explicit = arguments.str("objective")
            val target = if (explicit != null) {
                store.get(explicit) ?: return@withContext ToolResult(false, "Цель «$explicit» не найдена.")
            } else {
                val chatId = currentChatProvider.chatId()
                store.list().firstOrNull {
                    it.status == ObjectiveStatus.PAUSED && (chatId == null || it.chatId == chatId)
                } ?: return@withContext ToolResult(false, "Нет приостановленных целей для возобновления.")
            }
            val result = store.resume(target.id)
            when (result.outcome) {
                ObjectiveOutcome.CHANGED ->
                    ToolResult(true, "Цель возобновлена (активна).\n" + formatDetail(result.objective!!))
                ObjectiveOutcome.NOOP_SAME ->
                    ToolResult(true, "Цель уже активна — ничего не изменилось.\n" + formatSummaryLine(result.objective!!))
                ObjectiveOutcome.NOOP_TERMINAL ->
                    ToolResult(false, "Цель уже завершена (${objectiveStatusRu(result.objective!!.status)}) — возобновить нельзя, создай новую.")
                ObjectiveOutcome.NOT_FOUND ->
                    ToolResult(false, "Цель не найдена.")
                ObjectiveOutcome.INVALID ->
                    ToolResult(false, "Некорректные аргументы.")
            }
        }
    }

    private fun pause(): ToolHandler = object : ToolHandler {
        override val name: String = "objective_pause"

        override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val target = resolveTarget(arguments) ?: return@withContext noActive("objective_pause")
            val result = store.setStatus(target.id, ObjectiveStatus.PAUSED)
            when (result.outcome) {
                ObjectiveOutcome.CHANGED ->
                    ToolResult(true, "Цель ${result.objective!!.id} поставлена на паузу. Продолжить — objective_resume.")
                ObjectiveOutcome.NOOP_SAME ->
                    ToolResult(true, "Цель уже на паузе — ничего не изменилось.")
                ObjectiveOutcome.NOOP_TERMINAL ->
                    ToolResult(false, "Цель уже завершена (${objectiveStatusRu(result.objective!!.status)}) — пауза не применяется.")
                ObjectiveOutcome.NOT_FOUND ->
                    ToolResult(false, "Цель не найдена.")
                ObjectiveOutcome.INVALID ->
                    ToolResult(false, "Некорректные аргументы.")
            }
        }
    }

    private fun run(): ToolHandler = object : ToolHandler {
        override val name: String = "objective_run"

        override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val ref = arguments.str("objective")
            val report = coordinator.runRequested(ref)
            ToolResult(report.outcome != ObjectiveRunOutcome.NO_LLM, runReportText(report))
        }
    }

    private fun stop(): ToolHandler = object : ToolHandler {
        override val name: String = "objective_stop"

        override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val ref = arguments.str("objective")
            val report = coordinator.stop(ref)
            ToolResult(true, stopReportText(report))
        }
    }

    private fun workersStatus(): ToolHandler = object : ToolHandler {
        override val name: String = "objective_workers_status"

        override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            ToolResult(true, workersStatusText(coordinator.refreshStatus()))
        }
    }

    private fun concurrencySet(): ToolHandler = object : ToolHandler {
        override val name: String = "objective_concurrency_set"

        override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val raw = intOrNull(arguments, "max")
                ?: return@withContext ToolResult(
                    false,
                    "Укажите max — целое число от ${ObjectiveConcurrencyPolicy.MIN} до " +
                        "${ObjectiveConcurrencyPolicy.MAX}."
                )
            val clamped = coordinator.setConcurrency(raw)
            val note = if (clamped != raw) {
                " (запрошено $raw, допустимо ${ObjectiveConcurrencyPolicy.MIN}.." +
                    "${ObjectiveConcurrencyPolicy.MAX} — сохранено $clamped)"
            } else {
                ""
            }
            ToolResult(true, "Лимит параллельности целей: $clamped$note.")
        }
    }

    private fun runReportText(report: ObjectiveRunReport): String = buildString {
        when (report.outcome) {
            ObjectiveRunOutcome.STARTED -> {
                append("Фоновое выполнение запущено")
                report.objectiveId?.let { append(" для цели $it") }
                append(".\n")
                append(workersSummary(report.status))
            }
            ObjectiveRunOutcome.NO_LLM ->
                append("LLM не настроена — фоновое выполнение НЕ запускалось. " +
                    "Настройте провайдера и повторите.")
            ObjectiveRunOutcome.NOT_FOUND ->
                append("Цель не найдена — запускать нечего.")
            ObjectiveRunOutcome.TERMINAL ->
                append("Цель уже завершена (done/cancelled) — фон не перезапускает её.")
            ObjectiveRunOutcome.PAUSED ->
                append("Цель на паузе — сначала возобновите её через objective_resume.")
            ObjectiveRunOutcome.BLOCKED ->
                append("Первый незавершённый шаг заблокирован — снимите блокировку, потом запускайте.")
            ObjectiveRunOutcome.BUDGET_EXHAUSTED ->
                append("У цели исчерпан бюджет фоновых шагов — продолжите её вручную в чате.")
            ObjectiveRunOutcome.DEFERRED ->
                append("Охрана питания отложила фоновую работу (критический заряд или нагрев).")
            ObjectiveRunOutcome.FAILED ->
                append("Не удалось запустить фоновое выполнение — попробуйте позже.")
            ObjectiveRunOutcome.NO_CHAT ->
                append("Чат цели удалён — фон не запускается, цель помечена как заблокированная. " +
                    "Создайте новый чат и снимите блокировку шага.")
            ObjectiveRunOutcome.BUSY ->
                append("Агент занят другим прогоном — фон не запущен, попробуйте позже.")
            ObjectiveRunOutcome.ADVANCED ->
                append("Шаг цели выполнен.")
            ObjectiveRunOutcome.RUNNABLE -> append("Цель готова к запуску.")
        }
    }

    private fun stopReportText(report: ObjectiveStopReport): String {
        if (!report.stoppedSomething) {
            return "Нечего останавливать: выполняющихся или ожидающих целей нет."
        }
        return buildString {
            append("Остановлено целей: ${report.stoppedIds.size} (${report.stoppedIds.joinToString(", ")}).\n")
            append(workersSummary(report.status))
        }
    }

    private fun workersStatusText(status: ObjectiveWorkersStatus): String = buildString {
        appendLine("Фоновые цели:")
        appendLine("Лимит параллельности: ${status.concurrencyLimit} (эффективный: ${status.effectiveLimit})")
        appendLine("LLM настроена: ${if (status.llmConfigured) "да" else "нет"}")
        if (status.running.isEmpty()) {
            appendLine("Выполняются: нет")
        } else {
            appendLine("Выполняются (${status.running.size}):")
            status.running.forEach { appendLine(workItemLine(it)) }
        }
        if (status.queued.isEmpty()) {
            appendLine("В очереди: нет")
        } else {
            appendLine("В очереди (${status.queued.size}):")
            status.queued.forEach { appendLine(workItemLine(it)) }
        }
        if (status.note.isNotBlank()) appendLine("Примечание: ${status.note}")
        status.lastError?.let { appendLine("Последняя ошибка: $it") }
        if (status.lastTickAt > 0) append("Последний тик: ${status.lastTickAt}")
    }

    private fun workersSummary(status: ObjectiveWorkersStatus): String =
        "Выполняются: ${status.running.size}, в очереди: ${status.queued.size}, " +
            "эффективный лимит ${status.effectiveLimit}." +
            (if (status.note.isNotBlank()) " ${status.note}." else "")

    private fun workItemLine(item: ObjectiveWorkItem): String =
        "- [${item.objectiveId}] ${item.title} — " +
            (if (item.state == com.andharness.ru.domain.objective.ObjectiveWorkState.RUNNING) {
                "выполняется"
            } else {
                "в очереди"
            })

    private suspend fun setTerminal(arguments: JsonObject, status: ObjectiveStatus): ToolResult =
        withContext(Dispatchers.IO) {
            val tool = if (status == ObjectiveStatus.DONE) "objective_finish" else "objective_cancel"
            val target = resolveTarget(arguments) ?: return@withContext noActive(tool)
            val result = store.setStatus(target.id, status)
            when (result.outcome) {
                ObjectiveOutcome.CHANGED ->
                    ToolResult(true, "Цель ${result.objective!!.id} → ${objectiveStatusRu(status)}.")
                ObjectiveOutcome.NOOP_SAME ->
                    ToolResult(true, "Цель уже в статусе «${objectiveStatusRu(result.objective!!.status)}» — ничего не изменилось.")
                ObjectiveOutcome.NOOP_TERMINAL ->
                    ToolResult(
                        true,
                        "Цель уже завершена (${objectiveStatusRu(result.objective!!.status)}) — статус не меняется."
                    )
                ObjectiveOutcome.NOT_FOUND ->
                    ToolResult(false, "Цель не найдена.")
                ObjectiveOutcome.INVALID ->
                    ToolResult(false, "Некорректные аргументы.")
            }
        }

    /** Explicit `objective` argument, else the ACTIVE objective of the current chat. */
    private suspend fun resolveTarget(arguments: JsonObject): Objective? {
        val explicit = arguments.str("objective")
        if (explicit != null) return store.get(explicit)
        return store.activeForChat(currentChatProvider.chatId())
    }

    private fun noActive(tool: String): ToolResult = ToolResult(
        false,
        "Нет активной цели для этого чата. Создай её через objective_create или укажи " +
            "objective (id/название). Вызов: $tool."
    )

    private fun formatSummaryLine(objective: Objective): String {
        val progress = ObjectiveRules.progress(objective)
        val next = ObjectiveRules.nextStep(objective)
        val nextPart = if (next != null) "следующий [${next.id}] ${next.text}" else "нет следующего шага"
        return "- [${objective.id}] ${objective.title} — ${objectiveStatusRu(objective.status)}, " +
            "${progress.done}/${progress.total} шагов; $nextPart"
    }

    private fun formatDetail(objective: Objective): String {
        val progress = ObjectiveRules.progress(objective)
        return buildString {
            append("id: ${objective.id}")
            append("\nНазвание: ${objective.title}")
            append("\nЦель: ${objective.goal}")
            append("\nСтатус: ${objectiveStatusRu(objective.status)}")
            append("\nПрогресс: ${progress.done}/${progress.total} шагов")
            append("\nЧат: ${objective.chatId ?: "не привязан"}")
            append("\nИтераций: ${objective.iterations}")
            if (objective.steps.isEmpty()) {
                append("\nШагов нет (план не задан).")
            } else {
                append("\nШаги:")
                objective.steps.forEach { step ->
                    append("\n- [${step.id}] ${stepStatusRu(step.status)}: ${step.text}")
                    if (step.note.isNotBlank()) append(" (заметка: ${step.note})")
                }
            }
        }
    }

    private fun parseSteps(element: JsonElement?): List<String> {
        if (element == null) return emptyList()
        // The schema asks for a newline-separated string (the local llama.cpp server rejects an
        // `array` type), but a cloud model may still send a real JSON array or a JSON-array string,
        // so all three shapes are accepted.
        return when (element) {
            is JsonArray -> element.mapNotNull { stepText(it) }
            is JsonPrimitive -> {
                val content = element.content.trim()
                if (content.isEmpty()) {
                    emptyList()
                } else {
                    val asJsonArray = runCatching { JSON.parseToJsonElement(content) }.getOrNull()
                    if (asJsonArray is JsonArray) {
                        asJsonArray.mapNotNull { stepText(it) }
                    } else {
                        content.lines().mapNotNull { it.trim().takeIf { line -> line.isNotEmpty() } }
                    }
                }
            }
            else -> emptyList()
        }
    }

    private fun stepText(element: JsonElement): String? =
        (element as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }

    private fun longOrNull(arguments: JsonObject, key: String): Long? =
        (arguments[key] as? JsonPrimitive)?.content?.trim()?.toLongOrNull()

    private fun intOrNull(arguments: JsonObject, key: String): Int? =
        (arguments[key] as? JsonPrimitive)?.content?.trim()?.toIntOrNull()

    private fun parseObjectiveStatus(raw: String): ObjectiveStatus? = when (raw.trim().lowercase()) {
        "active", "активна", "активный" -> ObjectiveStatus.ACTIVE
        "paused", "пауза", "на паузе" -> ObjectiveStatus.PAUSED
        "done", "выполнена", "готово", "завершена" -> ObjectiveStatus.DONE
        "cancelled", "canceled", "отменена" -> ObjectiveStatus.CANCELLED
        else -> null
    }

    private fun parseStepStatus(raw: String): ObjectiveStepStatus? = when (raw.trim().lowercase()) {
        "pending", "ожидает", "запланирован" -> ObjectiveStepStatus.PENDING
        "in_progress", "inprogress", "в работе", "выполняется" -> ObjectiveStepStatus.IN_PROGRESS
        "done", "готово", "выполнен" -> ObjectiveStepStatus.DONE
        "blocked", "заблокирован", "блок" -> ObjectiveStepStatus.BLOCKED
        else -> null
    }

    private fun objectiveStatusRu(status: ObjectiveStatus): String = when (status) {
        ObjectiveStatus.ACTIVE -> "активна"
        ObjectiveStatus.PAUSED -> "пауза"
        ObjectiveStatus.DONE -> "выполнена"
        ObjectiveStatus.CANCELLED -> "отменена"
    }

    private fun stepStatusRu(status: ObjectiveStepStatus): String = when (status) {
        ObjectiveStepStatus.PENDING -> "ожидает"
        ObjectiveStepStatus.IN_PROGRESS -> "в работе"
        ObjectiveStepStatus.DONE -> "готово"
        ObjectiveStepStatus.BLOCKED -> "заблокирован"
    }

    private companion object {
        val JSON = AppJson.Lenient
    }
}
