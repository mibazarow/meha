package com.andharness.ru.ui.skills

import com.andharness.ru.data.tools.skill.SkillPlan
import com.andharness.ru.domain.skill.Skill
import com.andharness.ru.domain.skill.SkillParameterType
import com.andharness.ru.domain.skill.SkillScrollDirection
import com.andharness.ru.domain.skill.SkillSelector
import com.andharness.ru.domain.skill.SkillStep

/**
 * Android-free presentation logic for the «Навыки» library screen (M2, SPEC §24.2). It lives
 * outside the composable/VM so the whole screen's *decisions* — how to search the library, how to
 * render a step as one readable line, and whether a skill may be launched right now — are unit
 * tested on the JVM (`SkillLibraryLogicTest`) without a device, accessibility service or Compose.
 *
 * It reads the existing [Skill] model and the existing run plan [SkillPlan] (both Android-free) and
 * adds no new behaviour: it only formats them for a human and turns the engine's honest issues into
 * a single user-facing reason.
 */
object SkillLibraryLogic {

    /**
     * Case-insensitive substring search over the fields a user can see in a row: name, target app
     * package and description. A blank query returns the library unchanged (browse mode), matching
     * the «Навыки» empty/search switch on the screen.
     */
    fun filter(skills: List<Skill>, query: String): List<Skill> {
        val q = query.trim()
        if (q.isEmpty()) return skills
        return skills.filter { skill ->
            skill.name.contains(q, ignoreCase = true) ||
                skill.appPackage.contains(q, ignoreCase = true) ||
                skill.description.contains(q, ignoreCase = true)
        }
    }

    /** Compact metadata line for a list row: target app, step count and parameter count. */
    fun metaText(skill: Skill): String {
        val app = skill.appPackage.takeIf { it.isNotBlank() } ?: "приложение не указано"
        return "$app · шагов: ${skill.steps.size} · параметров: ${skill.parameters.size}"
    }

    /** One human-readable line for a step, used by the details dialog's numbered list. */
    fun formatStep(step: SkillStep): String = when (step) {
        is SkillStep.Tap -> "Нажать: ${describeSelector(step.selector)}"
        is SkillStep.Type -> "Ввести «${step.textTemplate}» в: ${describeSelector(step.selector)}"
        is SkillStep.WaitForText ->
            "Ждать появления: ${describeSelector(step.selector)} (до ${step.timeoutMs} мс)"
        is SkillStep.Scroll ->
            "Прокрутить ${directionText(step.direction)} x${step.amount}: ${describeSelector(step.selector)}"
        is SkillStep.Key -> "Системная клавиша: ${step.keyName}"
        is SkillStep.LaunchApp -> "Запустить приложение ${step.packageName}"
        is SkillStep.Delay -> "Пауза ${step.ms} мс"
        is SkillStep.AssertText ->
            "Проверить, что ${describeSelector(step.selector)} содержит «${step.expected}»"
        is SkillStep.OpenUrl -> "Открыть ссылку: ${step.urlTemplate}"
    }

    /** One line per declared parameter: `имя (тип, обязательный/необязательный) — описание`. */
    fun formatParameter(
        name: String,
        type: SkillParameterType,
        required: Boolean,
        description: String,
        default: String?,
        choices: List<String>,
    ): String = buildString {
        val modifiers = buildList {
            add(typeText(type))
            add(if (required) "обязательный" else "необязательный")
        }
        append("$name (${modifiers.joinToString(", ")})")
        if (description.isNotBlank()) append(" — $description")
        if (default != null) append(" · по умолчанию: «$default»")
        if (choices.isNotEmpty()) append(" · допустимо: ${choices.joinToString(", ")}")
    }

    /**
     * The honest pre-flight gate the screen shows before `skill_run` is attempted: `null` means the
     * plan can be executed now, otherwise a single Russian reason why it cannot.
     *
     * This mirrors the engine's own refusals (empty steps, plan issues, accessibility service off)
     * so the UI can explain the problem *before* an attempt instead of after one, while the engine
     * remains the final authority.
     */
    fun unavailableReason(plan: SkillPlan, accessibilityEnabled: Boolean): String? {
        if (plan.steps.isEmpty()) return "В навыке нет шагов — выполнять нечего."
        val problems = plan.issues + plan.stepIssues
        if (problems.isNotEmpty()) return "Навык нельзя запустить: ${problems.first()}"
        if (plan.steps.any { it.needsAccessibility } && !accessibilityEnabled) {
            return "Служба спецвозможностей Меха выключена — шаги навыка выполняются через неё. " +
                "Включите спецвозможности и повторите запуск."
        }
        return null
    }

    /** Human description of a selector: the stored note first, otherwise the matching fields. */
    fun describeSelector(selector: SkillSelector): String {
        if (selector.description.isNotBlank()) return selector.description
        val parts = listOfNotNull(
            selector.text?.takeIf { it.isNotBlank() }?.let { "текст «$it»" },
            selector.textContains?.takeIf { it.isNotBlank() }?.let { "содержит «$it»" },
            selector.resourceId?.takeIf { it.isNotBlank() }?.let { "id «$it»" },
            selector.contentDescription?.takeIf { it.isNotBlank() }?.let { "описание «$it»" },
            selector.className?.takeIf { it.isNotBlank() }?.let { "класс «$it»" },
            selector.index?.let { "№$it" },
        )
        return parts.joinToString(", ").ifBlank { "пустой селектор" }
    }

    private fun directionText(direction: SkillScrollDirection): String = when (direction) {
        SkillScrollDirection.UP -> "вверх"
        SkillScrollDirection.DOWN -> "вниз"
        SkillScrollDirection.LEFT -> "влево"
        SkillScrollDirection.RIGHT -> "вправо"
    }

    private fun typeText(type: SkillParameterType): String = when (type) {
        SkillParameterType.TEXT -> "текст"
        SkillParameterType.NUMBER -> "число"
        SkillParameterType.CHOICE -> "выбор"
    }
}
