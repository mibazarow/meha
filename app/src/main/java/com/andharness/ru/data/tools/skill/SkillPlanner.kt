package com.andharness.ru.data.tools.skill

import com.andharness.ru.domain.skill.Skill
import com.andharness.ru.domain.skill.SkillCodec
import com.andharness.ru.domain.skill.SkillParameterType
import com.andharness.ru.domain.skill.SkillParseResult
import com.andharness.ru.domain.skill.SkillSelector
import com.andharness.ru.domain.skill.SkillStep
import com.andharness.ru.domain.skill.SkillTemplate
import com.andharness.ru.domain.ui.UiSelector

/**
 * Pure planning half of the skill run engine (M2 slice 2, SPEC §24.2): turns a stored [Skill] plus
 * the caller's parameter values into an ordered, fully-resolved [SkillPlan] (selector mapping,
 * `{parameter}` rendering, honest per-step issues). It has NO Android imports, so it is exercised on
 * the JVM (`SkillRunEngineTest`); the Android-bound executor [SkillRunEngine] only performs the plan.
 *
 * Selector mapping mirrors `SkillSelector` -> `UiSelector` one-to-one (including the 1-based `index`),
 * and template rendering delegates to [SkillTemplate] so a missing parameter is reported, never
 * silently replaced by an empty string.
 */
object SkillPlanner {

    /** Maps a stored [SkillSelector] to the semantic [UiSelector] the resolver understands. */
    fun toUiSelector(selector: SkillSelector): UiSelector = UiSelector(
        text = selector.text?.takeIf { it.isNotBlank() },
        textContains = selector.textContains?.takeIf { it.isNotBlank() },
        resourceId = selector.resourceId?.takeIf { it.isNotBlank() },
        contentDescription = selector.contentDescription?.takeIf { it.isNotBlank() },
        className = selector.className?.takeIf { it.isNotBlank() },
        index = selector.index?.takeIf { it >= 1 },
    )

    /**
     * Effective values for one run: declared defaults fill in for parameters the caller omitted, and
     * undeclared supplied values are kept (a step may still reference them; `skill_save` warns about
     * that case at save time). Blank supplied values fall back to the default.
     */
    fun valuesFor(skill: Skill, supplied: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (parameter in skill.parameters) {
            val value = supplied[parameter.name]?.takeIf { it.isNotBlank() }
                ?: parameter.default?.takeIf { it.isNotBlank() }
            if (value != null) out[parameter.name] = value
        }
        for ((key, value) in supplied) {
            if (key !in out && value.isNotBlank()) out[key] = value
        }
        return out
    }

    /** Declared parameters marked `required` that have no value (caller or default). */
    fun missingRequired(skill: Skill, values: Map<String, String>): List<String> =
        skill.parameters.filter { it.required && values[it.name].isNullOrBlank() }.map { it.name }

    /**
     * Builds the run plan. Never touches the UI; a plan that is not [SkillPlan.isRunnable] must not be
     * executed (the executor refuses and reports the issues).
     */
    fun plan(skill: Skill, supplied: Map<String, String>): SkillPlan {
        val values = valuesFor(skill, supplied)
        val issues = ArrayList<String>()

        for (name in missingRequired(skill, values)) {
            issues += "Не задан обязательный параметр «{$name}»."
        }
        for (parameter in skill.parameters) {
            val value = values[parameter.name] ?: continue
            when (parameter.type) {
                SkillParameterType.NUMBER -> if (value.toDoubleOrNull() == null) {
                    issues += "Параметр «${parameter.name}» должен быть числом, получено «$value»."
                }
                SkillParameterType.CHOICE -> if (parameter.choices.isNotEmpty() && value !in parameter.choices) {
                    issues += "Значение «$value» параметра «${parameter.name}» не из списка допустимых: " +
                        parameter.choices.joinToString(", ")
                }
                SkillParameterType.TEXT -> Unit
            }
        }
        if (skill.steps.isEmpty()) issues += "В навыке нет шагов — выполнять нечего."
        if (skill.steps.size > SkillCodec.MAX_STEPS) {
            issues += "В навыке больше ${SkillCodec.MAX_STEPS} шагов — запуск отклонён."
        }

        // BUG-032: a skill recorded "live" has no launch step, so replaying it from the «Навыки»
        // library (foreground app = Меха) fails at step 1. When the target package is known and the
        // skill does not already start by launching an app, prepend an implicit launch_app step.
        // Launching an app that is already foreground is harmless (it is simply brought forward).
        val implicitPackage = skill.appPackage
            .takeIf { it.isNotBlank() && skill.steps.isNotEmpty() && skill.steps.first() !is SkillStep.LaunchApp }
        val shift = if (implicitPackage != null) 1 else 0
        val steps = ArrayList<PlannedStep>(skill.steps.size + shift)
        if (implicitPackage != null) {
            steps += PlannedStep(
                index = 1,
                action = SkillActions.LAUNCH_APP,
                summary = "запустить приложение $implicitPackage (неявный старт, добавлен движком)",
                packageName = implicitPackage,
                needsAccessibility = false,
                implicit = true,
            )
        }
        skill.steps.forEachIndexed { index, step -> steps += planStep(index + 1 + shift, step, values) }

        return SkillPlan(
            skillId = skill.id,
            skillName = skill.name,
            values = values,
            steps = steps,
            issues = issues,
        )
    }

    /**
     * Parses raw `steps`/`parameters` JSON and plans it. Exists so the caller (and the tests) get the
     * same honest rejection of an unknown step type (`SkillCodec.parseSteps`) that `skill_save` uses.
     */
    fun planFromJson(
        name: String,
        stepsJson: String?,
        parametersJson: String?,
        supplied: Map<String, String>,
    ): PlanResult {
        val steps = when (val parsed = SkillCodec.parseSteps(stepsJson)) {
            is SkillParseResult.Err -> return PlanResult.Err(parsed.message)
            is SkillParseResult.Ok -> parsed.value
        }
        val parameters = when (val parsed = SkillCodec.parseParameters(parametersJson)) {
            is SkillParseResult.Err -> return PlanResult.Err(parsed.message)
            is SkillParseResult.Ok -> parsed.value
        }
        val skill = Skill(
            id = "dry-run",
            name = name,
            parameters = parameters,
            steps = steps,
        )
        return PlanResult.Ok(plan(skill, supplied))
    }

    /** Human description of a raw selector, used when it is invalid and no [UiSelector] was built. */
    private fun describeRaw(selector: SkillSelector): String = listOfNotNull(
        selector.text?.takeIf { it.isNotBlank() }?.let { "text=«$it»" },
        selector.textContains?.takeIf { it.isNotBlank() }?.let { "textContains=«$it»" },
        selector.resourceId?.takeIf { it.isNotBlank() }?.let { "resourceId=«$it»" },
        selector.contentDescription?.takeIf { it.isNotBlank() }?.let { "contentDescription=«$it»" },
        selector.className?.takeIf { it.isNotBlank() }?.let { "className=«$it»" },
        selector.index?.let { "index=$it" },
    ).joinToString(", ").ifBlank { "пусто" }

    private fun planStep(index: Int, step: SkillStep, values: Map<String, String>): PlannedStep {
        fun selectorOrIssue(selector: SkillSelector): Pair<UiSelector?, String?> = when {
            selector.isEmpty() -> null to "селектор пуст (нужен text/textContains/resourceId/contentDescription)"
            !selector.hasIdentity() ->
                null to "в селекторе нет опознающего поля (только className/index)"
            else -> toUiSelector(selector) to null
        }

        fun renderOrIssue(template: String): Pair<String?, String?> {
            val rendered = SkillTemplate.renderTemplate(template, values)
            if (rendered != null) return rendered to null
            val missing = SkillTemplate.missingParameters(template, values)
            return null to "не задан параметр " + missing.joinToString(", ") { "{$it}" }
        }

        return when (step) {
            is SkillStep.LaunchApp -> PlannedStep(
                index = index,
                action = SkillActions.LAUNCH_APP,
                summary = "запустить приложение ${step.packageName}",
                packageName = step.packageName,
                needsAccessibility = false,
            )

            is SkillStep.Tap -> {
                val (selector, issue) = selectorOrIssue(step.selector)
                PlannedStep(
                    index = index,
                    action = SkillActions.TAP,
                    summary = "нажать элемент (" + (selector?.description ?: describeRaw(step.selector)) + ")",
                    selector = selector,
                    issue = issue,
                )
            }

            is SkillStep.Type -> {
                val (selector, selectorIssue) = selectorOrIssue(step.selector)
                val (text, renderIssue) = renderOrIssue(step.textTemplate)
                PlannedStep(
                    index = index,
                    action = SkillActions.TYPE,
                    summary = "ввести «${text ?: step.textTemplate}» в (" +
                        (selector?.description ?: describeRaw(step.selector)) + ")",
                    selector = selector,
                    payload = text,
                    issue = selectorIssue ?: renderIssue,
                )
            }

            is SkillStep.WaitForText -> {
                val (selector, issue) = selectorOrIssue(step.selector)
                PlannedStep(
                    index = index,
                    action = SkillActions.WAIT_FOR_TEXT,
                    summary = "ждать появления (" + (selector?.description ?: describeRaw(step.selector)) +
                        ") до ${step.timeoutMs} мс",
                    selector = selector,
                    timeoutMs = step.timeoutMs,
                    issue = issue,
                )
            }

            is SkillStep.Scroll -> {
                val (selector, issue) = selectorOrIssue(step.selector)
                PlannedStep(
                    index = index,
                    action = SkillActions.SCROLL,
                    summary = "прокрутить ${step.direction} x${step.amount} (" +
                        (selector?.description ?: describeRaw(step.selector)) + ")",
                    selector = selector,
                    direction = step.direction.name,
                    amount = step.amount,
                    issue = issue,
                )
            }

            is SkillStep.Key -> PlannedStep(
                index = index,
                action = SkillActions.KEY,
                summary = "нажать системную клавишу ${step.keyName}",
                keyName = step.keyName,
            )

            is SkillStep.Delay -> PlannedStep(
                index = index,
                action = SkillActions.DELAY,
                summary = "пауза ${step.ms} мс",
                ms = step.ms,
                needsAccessibility = false,
            )

            is SkillStep.AssertText -> {
                val (selector, selectorIssue) = selectorOrIssue(step.selector)
                val (expected, renderIssue) = renderOrIssue(step.expected)
                PlannedStep(
                    index = index,
                    action = SkillActions.ASSERT_TEXT,
                    summary = "проверить, что (" + (selector?.description ?: describeRaw(step.selector)) +
                        ") содержит «${expected ?: step.expected}»",
                    selector = selector,
                    payload = expected,
                    issue = selectorIssue ?: renderIssue,
                )
            }

            is SkillStep.OpenUrl -> {
                val (url, renderIssue) = renderOrIssue(step.urlTemplate)
                PlannedStep(
                    index = index,
                    action = SkillActions.OPEN_URL,
                    summary = "открыть ссылку ${url ?: step.urlTemplate}",
                    payload = url,
                    needsAccessibility = false,
                    issue = renderIssue,
                )
            }
        }
    }
}
