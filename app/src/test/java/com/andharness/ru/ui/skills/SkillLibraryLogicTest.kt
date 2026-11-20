package com.andharness.ru.ui.skills

import com.andharness.ru.data.tools.skill.SkillPlanner
import com.andharness.ru.domain.skill.Skill
import com.andharness.ru.domain.skill.SkillParameter
import com.andharness.ru.domain.skill.SkillParameterType
import com.andharness.ru.domain.skill.SkillScrollDirection
import com.andharness.ru.domain.skill.SkillSelector
import com.andharness.ru.domain.skill.SkillStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Android-free presentation logic of the «Навыки» screen
 * ([SkillLibraryLogic]): list search, row metadata, step/parameter formatting and the pre-flight
 * availability decision. No Compose, no `AccessibilityService` and no device are involved.
 *
 * (Plans are built through the already-JVM-tested [SkillPlanner] rather than hand-constructed, so
 * these tests use the same honest planning the screen relies on.)
 */
class SkillLibraryLogicTest {

    private fun skill(
        id: String = "id-1",
        name: String = "Заказать такси",
        description: String = "",
        appPackage: String = "",
        parameters: List<SkillParameter> = emptyList(),
        steps: List<SkillStep> = listOf(SkillStep.Tap(SkillSelector(text = "Оплатить"))),
    ): Skill = Skill(
        id = id,
        name = name,
        description = description,
        appPackage = appPackage,
        parameters = parameters,
        steps = steps,
    )

    // --- filter ---------------------------------------------------------------------------------

    @Test
    fun blankQueryReturnsTheWholeLibraryUnchanged() {
        val skills = listOf(skill(id = "a", name = "A"), skill(id = "b", name = "B"))
        assertEquals(skills, SkillLibraryLogic.filter(skills, "   "))
    }

    @Test
    fun filterMatchesNameCaseInsensitively() {
        val skills = listOf(skill(id = "a", name = "Заказать такси"), skill(id = "b", name = "Калькулятор"))
        val hits = SkillLibraryLogic.filter(skills, "ТАКСИ")
        assertEquals(listOf("a"), hits.map { it.id })
    }

    @Test
    fun filterMatchesAppPackageAndDescriptionToo() {
        val skills = listOf(
            skill(id = "a", name = "Такси", appPackage = "ru.taxi.app"),
            skill(id = "b", name = "Погода", description = "смотрит прогноз"),
            skill(id = "c", name = "Прочее"),
        )
        assertEquals(listOf("a"), SkillLibraryLogic.filter(skills, "ru.taxi").map { it.id })
        assertEquals(listOf("b"), SkillLibraryLogic.filter(skills, "ПРОГНОЗ").map { it.id })
    }

    @Test
    fun filterReturnsNothingWhenNoFieldMatches() {
        val skills = listOf(skill(id = "a", name = "Такси"))
        assertTrue(SkillLibraryLogic.filter(skills, "телепорт").isEmpty())
    }

    // --- metaText / formatting ------------------------------------------------------------------

    @Test
    fun metaTextNamesTheAppAndCountsStepsAndParameters() {
        val s = skill(
            appPackage = "ru.taxi.app",
            parameters = listOf(SkillParameter(name = "адрес")),
            steps = listOf(SkillStep.Delay(100), SkillStep.Delay(200)),
        )
        assertEquals("ru.taxi.app · шагов: 2 · параметров: 1", SkillLibraryLogic.metaText(s))
    }

    @Test
    fun metaTextSaysWhenNoAppIsDeclared() {
        val text = SkillLibraryLogic.metaText(skill(appPackage = ""))
        assertTrue(text, text.startsWith("приложение не указано ·"))
    }

    @Test
    fun formatStepRendersEachStepTypeAsReadableRussian() {
        assertEquals(
            "Нажать: кнопка Оплатить",
            SkillLibraryLogic.formatStep(SkillStep.Tap(SkillSelector(description = "кнопка Оплатить")))
        )
        assertEquals(
            "Ввести «до {адрес}» в: поле Поиск",
            SkillLibraryLogic.formatStep(
                SkillStep.Type(SkillSelector(description = "поле Поиск"), "до {адрес}")
            )
        )
        assertEquals(
            "Ждать появления: текст «Готово» (до 5000 мс)",
            SkillLibraryLogic.formatStep(SkillStep.WaitForText(SkillSelector(text = "Готово")))
        )
        assertEquals(
            "Прокрутить вниз x2: список",
            SkillLibraryLogic.formatStep(
                SkillStep.Scroll(SkillSelector(description = "список"), SkillScrollDirection.DOWN, 2)
            )
        )
        assertEquals("Системная клавиша: back", SkillLibraryLogic.formatStep(SkillStep.Key("back")))
        assertEquals(
            "Запустить приложение com.taxi.app",
            SkillLibraryLogic.formatStep(SkillStep.LaunchApp("com.taxi.app"))
        )
        assertEquals("Пауза 500 мс", SkillLibraryLogic.formatStep(SkillStep.Delay(500)))
        assertEquals(
            "Проверить, что итог содержит «3»",
            SkillLibraryLogic.formatStep(SkillStep.AssertText(SkillSelector(description = "итог"), "3"))
        )
        assertEquals(
            "Открыть ссылку: https://example.com",
            SkillLibraryLogic.formatStep(SkillStep.OpenUrl("https://example.com"))
        )
    }

    @Test
    fun describeSelectorFallsBackToFieldsWhenThereIsNoHumanNote() {
        val described = SkillLibraryLogic.describeSelector(
            SkillSelector(text = "Оплатить", resourceId = "com.app:id/pay", index = 2)
        )
        assertTrue(described, described.contains("текст «Оплатить»"))
        assertTrue(described, described.contains("id «com.app:id/pay»"))
        assertTrue(described, described.contains("№2"))
    }

    @Test
    fun describeSelectorAdmitsAnEmptySelector() {
        assertEquals("пустой селектор", SkillLibraryLogic.describeSelector(SkillSelector()))
    }

    @Test
    fun formatParameterListsTypeRequiredDefaultAndChoices() {
        val text = SkillLibraryLogic.formatParameter(
            name = "размер",
            type = SkillParameterType.CHOICE,
            required = true,
            description = "объём",
            default = "средний",
            choices = listOf("малый", "средний", "большой"),
        )
        assertTrue(text, text.contains("размер (выбор, обязательный)"))
        assertTrue(text, text.contains("по умолчанию: «средний»"))
        assertTrue(text, text.contains("допустимо: малый, средний, большой"))
    }

    // --- availability ---------------------------------------------------------------------------

    @Test
    fun emptyPlanIsUnavailableWithHonestReason() {
        val plan = SkillPlanner.plan(skill(steps = emptyList()), emptyMap())
        val reason = SkillLibraryLogic.unavailableReason(plan, accessibilityEnabled = true)
        assertTrue(reason.orEmpty(), reason != null && reason.contains("нет шагов"))
    }

    @Test
    fun missingRequiredParameterIsReportedBeforeAnyAttempt() {
        val plan = SkillPlanner.plan(
            skill(parameters = listOf(SkillParameter(name = "адрес", required = true))),
            emptyMap(),
        )
        val reason = SkillLibraryLogic.unavailableReason(plan, accessibilityEnabled = true)
        assertTrue(reason.orEmpty(), reason != null && reason.contains("адрес"))
    }

    @Test
    fun accessibilityStepIsBlockedWhenTheServiceIsOff() {
        val plan = SkillPlanner.plan(skill(), emptyMap())
        val reason = SkillLibraryLogic.unavailableReason(plan, accessibilityEnabled = false)
        assertTrue(reason.orEmpty(), reason != null && reason.contains("спецвозможностей"))
    }

    @Test
    fun accessibilityStepIsAllowedWhenTheServiceIsOn() {
        val plan = SkillPlanner.plan(skill(), emptyMap())
        assertNull(SkillLibraryLogic.unavailableReason(plan, accessibilityEnabled = true))
    }

    @Test
    fun nonAccessibilitySkillRunsEvenWithTheServiceOff() {
        val plan = SkillPlanner.plan(
            skill(steps = listOf(SkillStep.LaunchApp("com.taxi.app"), SkillStep.Delay(100))),
            emptyMap(),
        )
        assertFalse(plan.steps.any { it.needsAccessibility })
        assertNull(SkillLibraryLogic.unavailableReason(plan, accessibilityEnabled = false))
    }
}
