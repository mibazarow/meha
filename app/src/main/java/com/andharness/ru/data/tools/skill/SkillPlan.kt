package com.andharness.ru.data.tools.skill

/**
 * An ordered run plan for one skill invocation. [isRunnable] is the honest gate: when it is false the
 * executor MUST refuse to touch the screen and report [issues]/step issues instead.
 */
data class SkillPlan(
    val skillId: String,
    val skillName: String,
    val values: Map<String, String>,
    val steps: List<PlannedStep>,
    val issues: List<String>,
) {
    /** Per-step problems, rendered with their step number. */
    val stepIssues: List<String>
        get() = steps.mapNotNull { step -> step.issue?.let { "Шаг ${step.index} (${step.action}): $it" } }

    val isRunnable: Boolean
        get() = steps.isNotEmpty() && issues.isEmpty() && steps.none { it.issue != null }

    /** Dry-run report: the resolved plan (selectors/params/templates), without touching the UI. */
    fun render(): String = buildString {
        appendLine("Навык «$skillName»: шагов ${steps.size}.")
        if (values.isNotEmpty()) {
            appendLine("Параметры: " + values.entries.joinToString(", ") { "${it.key}=«${it.value}»" })
        }
        appendLine("План выполнения:")
        steps.forEach { step ->
            append("  ${step.index}. ${step.summary}")
            if (step.issue != null) append(" — ПРОБЛЕМА: ${step.issue}")
            appendLine()
        }
        val problems = issues + stepIssues
        if (problems.isEmpty()) {
            append("План корректен: все шаги разрешены.")
        } else {
            appendLine("Навык нельзя запустить:")
            problems.forEach { appendLine("  - $it") }
        }
    }.trim()
}
