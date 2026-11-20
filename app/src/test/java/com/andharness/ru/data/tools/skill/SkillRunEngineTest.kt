package com.andharness.ru.data.tools.skill

import com.andharness.ru.domain.skill.Skill
import com.andharness.ru.domain.skill.SkillParameter
import com.andharness.ru.domain.skill.SkillSelector
import com.andharness.ru.domain.skill.SkillStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure half of the run engine ([SkillPlanner] / [SkillPlan]). They exercise the
 * planning logic only — no Android `AccessibilityService`, no UI — which is exactly the point of the
 * split: selector mapping, `{parameter}` rendering, the dry-run report, and the honest refusal of an
 * unknown step type or a missing required parameter are all verifiable off-device.
 *
 * (The Android-bound [SkillRunEngine] executor is intentionally NOT referenced here: loading it would
 * pull in `android.*`, which unit tests must avoid.)
 */
class SkillRunEngineTest {

    private fun skill(
        parameters: List<SkillParameter> = emptyList(),
        steps: List<SkillStep> = listOf(SkillStep.Tap(SkillSelector(text = "Оплатить"))),
    ): Skill = Skill(id = "id-1", name = "Заказать такси", parameters = parameters, steps = steps)

    @Test
    fun selectorMapsToUiSelectorOneToOneIncludingIndex() {
        val mapped = SkillPlanner.toUiSelector(
            SkillSelector(
                text = "Оплатить",
                textContains = "плат",
                resourceId = "com.app:id/pay",
                contentDescription = "кнопка Оплатить",
                className = "android.widget.Button",
                index = 2,
            )
        )
        assertEquals("Оплатить", mapped.text)
        assertEquals("плат", mapped.textContains)
        assertEquals("com.app:id/pay", mapped.resourceId)
        assertEquals("кнопка Оплатить", mapped.contentDescription)
        assertEquals("android.widget.Button", mapped.className)
        assertEquals(2, mapped.index)
    }

    @Test
    fun nonPositiveIndexIsDroppedAndBlankFieldsAreIgnored() {
        val mapped = SkillPlanner.toUiSelector(SkillSelector(text = "   ", textContains = "оплата", index = 0))
        assertNull("blank text must not become a criterion", mapped.text)
        assertEquals("оплата", mapped.textContains)
        assertNull("index 0 is not a valid 1-based ordinal", mapped.index)
    }

    @Test
    fun missingTemplateParameterIsReportedHonestlyAndPlanIsNotRunnable() {
        val s = skill(
            parameters = listOf(SkillParameter(name = "адрес", required = true)),
            steps = listOf(
                SkillStep.Type(
                    selector = SkillSelector(text = "Поиск"),
                    textTemplate = "до {адрес}",
                )
            ),
        )
        val plan = SkillPlanner.plan(s, emptyMap())

        assertFalse(plan.isRunnable)
        assertTrue(
            "the missing parameter must be named honestly: ${plan.issues}",
            plan.issues.any { it.contains("адрес") }
        )
        assertTrue(
            "the step must carry its own issue: ${plan.stepIssues}",
            plan.stepIssues.any { it.contains("Шаг 1") && it.contains("адрес") }
        )
    }

    @Test
    fun declaredDefaultFillsInForAnOmittedValue() {
        val s = skill(
            parameters = listOf(SkillParameter(name = "адрес", default = "Ленина 1")),
            steps = listOf(SkillStep.Type(SkillSelector(text = "Поиск"), "до {адрес}")),
        )
        val plan = SkillPlanner.plan(s, emptyMap())

        assertTrue(plan.render(), plan.isRunnable)
        assertEquals("до Ленина 1", plan.steps.single().payload)
    }

    @Test
    fun suppliedValueOverridesDefaultAndRendersIntoTheTemplate() {
        val s = skill(
            parameters = listOf(SkillParameter(name = "адрес", default = "по умолчанию")),
            steps = listOf(SkillStep.Type(SkillSelector(text = "Поиск"), "до {адрес}")),
        )
        val plan = SkillPlanner.plan(s, mapOf("адрес" to "Тверская 7"))

        assertTrue(plan.render(), plan.isRunnable)
        assertEquals("до Тверская 7", plan.steps.single().payload)
    }

    @Test
    fun dryRunReportListsResolvedSelectorsParametersAndTemplates() {
        val s = skill(
            parameters = listOf(SkillParameter(name = "адрес")),
            steps = listOf(
                SkillStep.LaunchApp("com.taxi.app"),
                SkillStep.Type(SkillSelector(text = "Куда"), "улица {адрес}"),
                SkillStep.Delay(500),
            ),
        )
        val plan = SkillPlanner.plan(s, mapOf("адрес" to "Ленина 1"))
        val report = plan.render()

        assertTrue(report, plan.isRunnable)
        assertTrue(report, report.contains("План выполнения"))
        assertTrue("rendered template must appear: $report", report.contains("улица Ленина 1"))
        assertTrue("selector must be resolved in the plan: $report", report.contains("text=«Куда»"))
        assertTrue("parameters must be listed: $report", report.contains("адрес=«Ленина 1»"))
        assertEquals(3, plan.steps.size)
    }

    @Test
    fun missingRequiredParameterAbortsHonestlyBeforeAnyExecution() {
        val s = skill(
            parameters = listOf(SkillParameter(name = "сумма", required = true)),
            steps = listOf(SkillStep.Tap(SkillSelector(text = "Оплатить"))),
        )
        val plan = SkillPlanner.plan(s, emptyMap())

        assertFalse(plan.isRunnable)
        assertTrue(plan.issues.any { it.contains("обязательный") && it.contains("сумма") })
        // Dry-run is still informative (it shows the plan) but reports "cannot run".
        assertTrue(plan.render(), plan.render().contains("нельзя запустить"))
    }

    @Test
    fun unknownStepTypeIsRejectedHonestlyByThePlannerEntryPoint() {
        val result = SkillPlanner.planFromJson(
            name = "Телепорт",
            stepsJson = "[{\"type\":\"teleport\"}]",
            parametersJson = null,
            supplied = emptyMap(),
        )
        assertTrue("an unknown step type must be an honest error", result is PlanResult.Err)
        val message = (result as PlanResult.Err).message
        assertTrue("the error must name the offending step: $message", message.contains("шаг #1"))
    }

    @Test
    fun emptySelectorIsRejectedInThePlan() {
        // A selector with only a class/index has no identity and must not resolve to a random node.
        val s = skill(steps = listOf(SkillStep.Tap(SkillSelector(className = "android.widget.Button", index = 1))))
        val plan = SkillPlanner.plan(s, emptyMap())
        assertFalse(plan.isRunnable)
        assertTrue(plan.stepIssues.single(), plan.stepIssues.single().contains("опознающ"))
    }

    @Test
    fun skillWithoutStepsIsNotRunnable() {
        val plan = SkillPlanner.plan(skill(steps = emptyList()), emptyMap())
        assertFalse(plan.isRunnable)
        assertTrue(plan.issues.any { it.contains("нет шагов") })
    }

    @Test
    fun launchDelayAndUrlStepsDoNotRequireAccessibility() {
        val s = skill(
            steps = listOf(
                SkillStep.LaunchApp("com.taxi.app"),
                SkillStep.Delay(100),
                SkillStep.OpenUrl("https://example.com/{запрос}"),
            ),
        )
        // The URL template references an undeclared parameter, so plan it with a value supplied.
        val plan = SkillPlanner.plan(s, mapOf("запрос" to "такси"))
        assertTrue(plan.render(), plan.isRunnable)
        assertTrue(plan.steps.all { !it.needsAccessibility })
        assertEquals("https://example.com/такси", plan.steps.last().payload)
    }

    @Test
    fun selectorStepsRequireAccessibility() {
        val s = skill(steps = listOf(SkillStep.Tap(SkillSelector(text = "Оплатить"))))
        val plan = SkillPlanner.plan(s, emptyMap())
        assertTrue(plan.steps.single().needsAccessibility)
        assertNotNull(plan.steps.single().selector)
    }

    // --- BUG-032: implicit startup launch for skills recorded without a launch step -----------------

    private fun skillWithPackage(pkg: String, steps: List<SkillStep>): Skill =
        Skill(id = "id-pkg", name = "Записанный калькулятор", appPackage = pkg, steps = steps)

    @Test
    fun plannerPrependsAnImplicitLaunchWhenAppPackageIsKnownAndStepsDoNotLaunch() {
        val s = skillWithPackage(
            "com.sec.android.app.popupcalculator",
            listOf(SkillStep.Tap(SkillSelector(resourceId = "calc_keypad_btn_clear"))),
        )
        val plan = SkillPlanner.plan(s, emptyMap())

        assertTrue(plan.render(), plan.isRunnable)
        assertEquals(2, plan.steps.size)
        val launch = plan.steps.first()
        assertEquals("launch_app", launch.action)
        assertTrue("the prepended launch must be marked implicit", launch.implicit)
        assertFalse("a launch needs no accessibility service", launch.needsAccessibility)
        assertEquals("com.sec.android.app.popupcalculator", launch.packageName)
        // The original step is preserved and renumbered after the implicit launch.
        assertEquals(2, plan.steps[1].index)
        assertEquals("tap", plan.steps[1].action)
        assertFalse(plan.steps[1].implicit)
        assertTrue("the dry-run must show the implicit launch honestly: ${plan.render()}", plan.render().contains("неявн"))
    }

    @Test
    fun plannerDoesNotAddALaunchWhenTheSkillAlreadyStartsWithOne() {
        val s = skillWithPackage(
            "com.taxi.app",
            listOf(SkillStep.LaunchApp("com.taxi.app"), SkillStep.Delay(100)),
        )
        val plan = SkillPlanner.plan(s, emptyMap())
        assertEquals(2, plan.steps.size)
        assertEquals("launch_app", plan.steps.first().action)
        assertFalse("an explicit launch is not implicit", plan.steps.first().implicit)
        assertTrue(plan.steps.none { it.implicit })
    }

    @Test
    fun plannerDoesNotInventALaunchWithoutAnAppPackage() {
        val s = skill(steps = listOf(SkillStep.Tap(SkillSelector(text = "Оплатить"))))
        val plan = SkillPlanner.plan(s, emptyMap())
        assertEquals(1, plan.steps.size)
        assertTrue(plan.steps.none { it.implicit })
    }
}
