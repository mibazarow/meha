package com.andharness.ru.domain.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * JVM tests for the Android-free skill model and codec: JSON round-trip of every step type (sealed
 * polymorphism with the `type` discriminator), corrupt-store degradation, and the honest validation
 * errors the `skill_save` tool surfaces to the agent.
 */
class SkillCodecTest {

    private fun selector(text: String = "Оплатить") = SkillSelector(
        text = text,
        resourceId = "com.app:id/pay",
        description = "кнопка Оплатить",
    )

    private fun allSteps(): List<SkillStep> = listOf(
        SkillStep.Tap(selector()),
        SkillStep.Type(selector("Поиск"), "такси до {адрес}"),
        SkillStep.WaitForText(selector("Готово"), timeoutMs = 7_000),
        SkillStep.Scroll(selector("Лента"), SkillScrollDirection.DOWN, amount = 3),
        SkillStep.Key("BACK"),
        SkillStep.LaunchApp("com.taxi.app"),
        SkillStep.Delay(500),
        SkillStep.AssertText(selector("Статус"), "Заказ принят"),
        SkillStep.OpenUrl("https://taxi.example/order?to={адрес}"),
    )

    private fun skill(id: String = "s1", name: String = "Заказать такси"): Skill = Skill(
        id = id,
        name = name,
        description = "Демонстрационный навык",
        appPackage = "com.taxi.app",
        parameters = listOf(
            SkillParameter(
                name = "адрес",
                type = SkillParameterType.TEXT,
                description = "Куда ехать",
                required = true,
            ),
            SkillParameter(
                name = "тариф",
                type = SkillParameterType.CHOICE,
                choices = listOf("эконом", "комфорт"),
                default = "эконом",
            ),
        ),
        steps = allSteps(),
        createdAt = 1_000L,
        updatedAt = 2_000L,
    )

    @Test
    fun roundTripPreservesTheWholeModel() {
        val skills = listOf(skill(), skill("s2", "Второй"))
        assertEquals(skills, SkillCodec.decodeSkills(SkillCodec.encodeSkills(skills)))
    }

    @Test
    fun everyStepTypeSurvivesRoundTrip() {
        val decoded = SkillCodec.decodeSkills(SkillCodec.encodeSkills(listOf(skill()))).single()
        assertEquals(allSteps(), decoded.steps)
    }

    @Test
    fun selectorFieldsSurviveRoundTrip() {
        val decoded = SkillCodec.decodeSkills(SkillCodec.encodeSkills(listOf(skill()))).single()
        val tap = decoded.steps.first() as SkillStep.Tap
        assertEquals("Оплатить", tap.selector.text)
        assertEquals("com.app:id/pay", tap.selector.resourceId)
        assertEquals("кнопка Оплатить", tap.selector.description)
    }

    @Test
    fun corruptOrForeignStoreDegradesToEmpty() {
        assertTrue(SkillCodec.decodeSkills(null).isEmpty())
        assertTrue(SkillCodec.decodeSkills("").isEmpty())
        assertTrue(SkillCodec.decodeSkills("not json").isEmpty())
        assertTrue(SkillCodec.decodeSkills("{\"unexpected\":true}").isEmpty())
    }

    @Test
    fun skillsWithoutIdOrNameAreDropped() {
        val raw = SkillCodec.encodeSkills(listOf(skill(id = "", name = "Без id"), skill("ok", "Нормальный")))
        assertEquals(listOf("ok"), SkillCodec.decodeSkills(raw).map { it.id })
    }

    @Test
    fun referencedParametersAreCollectedFromTemplates() {
        assertEquals(listOf("адрес"), skill().referencedParameters())
    }

    @Test
    fun parseStepsBlankMeansNoSteps() {
        assertEquals(SkillParseResult.Ok(emptyList<SkillStep>()), SkillCodec.parseSteps(null))
        assertEquals(SkillParseResult.Ok(emptyList<SkillStep>()), SkillCodec.parseSteps("   "))
    }

    @Test
    fun parseStepsRejectsNonArray() {
        val result = SkillCodec.parseSteps("{\"type\":\"tap\"}")
        assertTrue(result is SkillParseResult.Err)
        assertTrue((result as SkillParseResult.Err).message.contains("массив"))
    }

    @Test
    fun parseStepsRejectsBrokenJson() {
        val result = SkillCodec.parseSteps("[{not json")
        assertTrue(result is SkillParseResult.Err)
        assertTrue((result as SkillParseResult.Err).message.contains("не удалось разобрать JSON"))
    }

    @Test
    fun parseStepsRejectsUnknownStepTypeWithItsNumber() {
        val result = SkillCodec.parseSteps("[{\"type\":\"teleport\"}]")
        assertTrue(result is SkillParseResult.Err)
        val message = (result as SkillParseResult.Err).message
        assertTrue("должен называть номер шага: $message", message.contains("шаг #1"))
        assertTrue("должен перечислять доступные type: $message", message.contains("open_url"))
    }

    @Test
    fun parseStepsRejectsSelectorWithoutIdentity() {
        val result = SkillCodec.parseSteps("[{\"type\":\"tap\",\"selector\":{\"className\":\"android.widget.Button\"}}]")
        assertTrue(result is SkillParseResult.Err)
        assertTrue((result as SkillParseResult.Err).message.contains("опознающего поля"))
    }

    @Test
    fun parseStepsRejectsTooManySteps() {
        val tooMany = (1..SkillCodec.MAX_STEPS + 1).joinToString(",") { "{\"type\":\"delay\",\"ms\":10}" }
        val result = SkillCodec.parseSteps("[$tooMany]")
        assertTrue(result is SkillParseResult.Err)
        assertTrue((result as SkillParseResult.Err).message.contains("слишком много шагов"))
    }

    @Test
    fun parseStepsAcceptsAValidArray() {
        val expected = listOf<SkillStep>(
            SkillStep.LaunchApp("com.app"),
            SkillStep.Delay(100),
        )
        assertEquals(
            SkillParseResult.Ok(expected),
            SkillCodec.parseSteps(
                "[{\"type\":\"launch_app\",\"packageName\":\"com.app\"},{\"type\":\"delay\",\"ms\":100}]"
            ),
        )
    }

    @Test
    fun parseParametersBlankMeansNone() {
        assertEquals(SkillParseResult.Ok(emptyList<SkillParameter>()), SkillCodec.parseParameters(""))
    }

    @Test
    fun parseParametersRejectsDuplicateNames() {
        val result = SkillCodec.parseParameters(
            "[{\"name\":\"a\"},{\"name\":\"a\"}]"
        )
        assertTrue(result is SkillParseResult.Err)
        assertTrue((result as SkillParseResult.Err).message.contains("повторяется"))
    }

    @Test
    fun parseParametersRejectsChoiceWithoutChoices() {
        val result = SkillCodec.parseParameters("[{\"name\":\"тариф\",\"type\":\"CHOICE\"}]")
        assertTrue(result is SkillParseResult.Err)
        assertTrue((result as SkillParseResult.Err).message.contains("choices"))
    }

    @Test
    fun parseParametersRejectsUnknownType() {
        val result = SkillCodec.parseParameters("[{\"name\":\"x\",\"type\":\"MAGIC\"}]")
        assertTrue(result is SkillParseResult.Err)
        assertTrue((result as SkillParseResult.Err).message.contains("TEXT, NUMBER или CHOICE"))
    }

    // ---- tolerant argument shapes (a model sends a real array / flat selectors / a code fence) ----

    @Test
    fun parseStepsArgAcceptsARealJsonArray() {
        val element = buildJsonArray {
            add(buildJsonObject { put("type", "launch_app"); put("packageName", "com.telegram.messenger") })
            add(buildJsonObject { put("type", "delay"); put("ms", 500) })
        }
        assertEquals(
            SkillParseResult.Ok(
                listOf<SkillStep>(SkillStep.LaunchApp("com.telegram.messenger"), SkillStep.Delay(500))
            ),
            SkillCodec.parseStepsArg(element),
        )
    }

    @Test
    fun parseStepsFoldsFlatSelectorFieldsIntoSelector() {
        val result = SkillCodec.parseSteps(
            "[{\"type\":\"tap\",\"text\":\"Тестовая группа\"}," +
                "{\"type\":\"tap\",\"id\":\"send_button\"}]"
        )
        val steps = (result as SkillParseResult.Ok).value
        assertEquals(2, steps.size)
        assertEquals("Тестовая группа", (steps[0] as SkillStep.Tap).selector.text)
        assertEquals("send_button", (steps[1] as SkillStep.Tap).selector.resourceId)
    }

    @Test
    fun parseStepsStripsASurroundingCodeFence() {
        val result = SkillCodec.parseSteps("```json\n[{\"type\":\"delay\",\"ms\":100}]\n```")
        assertEquals(SkillParseResult.Ok(listOf<SkillStep>(SkillStep.Delay(100))), result)
    }

    @Test
    fun parseParametersArgAcceptsARealJsonArray() {
        val element = buildJsonArray {
            add(buildJsonObject { put("name", "сообщение"); put("type", "TEXT") })
        }
        val parameters = (SkillCodec.parseParametersArg(element) as SkillParseResult.Ok).value
        assertEquals(1, parameters.size)
        assertEquals("сообщение", parameters.single().name)
    }

    @Test
    fun parseStepsStillRejectsATypeStepWithoutAnySelector() {
        val result = SkillCodec.parseSteps("[{\"type\":\"type\",\"textTemplate\":\"{сообщение}\"}]")
        assertTrue(result is SkillParseResult.Err)
        assertTrue((result as SkillParseResult.Err).message.contains("шаг #1"))
    }
}
