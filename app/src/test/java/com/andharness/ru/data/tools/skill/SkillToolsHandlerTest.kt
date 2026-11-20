package com.andharness.ru.data.tools.skill

import com.andharness.ru.data.skill.SkillStore
import com.andharness.ru.domain.skill.SkillStep
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for the `skill_*` CRUD tools. The handler depends only on the Android-free [SkillStore],
 * so the honest validation of `skill_save` (broken steps JSON, unknown step type, bad parameters) is
 * verified without an Android runtime — the point of the slice is that a bad definition is refused
 * with a message naming the offending step, never stored.
 */
class SkillToolsHandlerTest {

    private lateinit var dir: File
    private lateinit var store: SkillStore
    private lateinit var handler: SkillToolsHandler

    private val validSteps = "[{\"type\":\"delay\",\"ms\":100}]"

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (key, value) -> put(key, value) }
    }

    private fun tool(name: String) = handler.handlers().first { it.name == name }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("skill-handler-test").toFile()
        store = SkillStore(dir)
        handler = SkillToolsHandler(store)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun saveCreatesSkillAndPersistsIt() = runTest {
        val result = tool("skill_save").execute(
            args("name" to "Заказать такси", "appPackage" to "com.taxi.app", "steps" to validSteps)
        )
        assertTrue(result.output, result.success)
        assertTrue(result.output.contains("создан"))
        assertEquals(1, store.count())
        val saved = store.list().single()
        assertEquals("Заказать такси", saved.name)
        assertEquals("com.taxi.app", saved.appPackage)
        assertEquals(1, saved.steps.size)
        assertTrue("createdAt must be set", saved.createdAt > 0L)
    }

    @Test
    fun saveRejectsBrokenStepsJsonHonestly() = runTest {
        val result = tool("skill_save").execute(args("name" to "Плохой", "steps" to "[{not json"))
        assertFalse(result.success)
        assertTrue(result.output, result.output.contains("steps"))
        assertTrue("a broken skill must not be stored", store.list().isEmpty())
    }

    @Test
    fun saveRejectsUnknownStepTypeAndNamesTheStep() = runTest {
        val result = tool("skill_save").execute(
            args("name" to "Телепорт", "steps" to "[{\"type\":\"teleport\"}]")
        )
        assertFalse(result.success)
        assertTrue(result.output, result.output.contains("шаг #1"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun saveAcceptsTelegramMessageAndFileRecordingAsAJsonArrayWithFlatSelectors() = runTest {
        val steps = buildJsonArray {
            add(buildJsonObject { put("type", "launch_app"); put("packageName", "org.telegram.messenger") })
            add(buildJsonObject { put("type", "tap"); put("text", "Тестовая группа") })
            add(
                buildJsonObject {
                    put("type", "type")
                    put("resourceId", "chat_message_input")
                    put("textTemplate", "{сообщение}")
                }
            )
            add(buildJsonObject { put("type", "tap"); put("contentDescription", "Прикрепить") })
            add(buildJsonObject { put("type", "wait_for_text"); put("text", "Файл"); put("timeoutMs", 5_000) })
            add(buildJsonObject { put("type", "delay"); put("ms", 1_000) })
            add(buildJsonObject { put("type", "tap"); put("contentDescription", "Отправить") })
        }
        val parameters = buildJsonArray {
            add(buildJsonObject { put("name", "сообщение"); put("type", "TEXT"); put("required", true) })
        }
        val arguments = buildJsonObject {
            put("name", "Отправить сообщение и файл в группу")
            put("appPackage", "org.telegram.messenger")
            put("steps", steps)
            put("parameters", parameters)
        }

        val result = tool("skill_save").execute(arguments)

        assertTrue(result.output, result.success)
        assertEquals(1, store.count())
        val saved = store.list().single()
        assertEquals(7, saved.steps.size)
        assertEquals(1, saved.parameters.size)
        assertTrue("createdAt must be set", saved.createdAt > 0L)
        val typeStep = saved.steps[2] as SkillStep.Type
        assertEquals("chat_message_input", typeStep.selector.resourceId)
        assertEquals("{сообщение}", typeStep.textTemplate)
    }

    @Test
    fun saveRejectsChoiceParameterWithoutChoices() = runTest {
        val result = tool("skill_save").execute(
            args(
                "name" to "Тариф",
                "steps" to validSteps,
                "parameters" to "[{\"name\":\"тариф\",\"type\":\"CHOICE\"}]",
            )
        )
        assertFalse(result.success)
        assertTrue(result.output, result.output.contains("choices"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun saveWarnsAboutUndeclaredTemplateParameterButStillSaves() = runTest {
        val steps = "[{\"type\":\"type\",\"selector\":{\"text\":\"Поиск\"},\"textTemplate\":\"до {адрес}\"}]"
        val result = tool("skill_save").execute(args("name" to "С предупреждением", "steps" to steps))
        assertTrue(result.output, result.success)
        assertTrue(result.output, result.output.contains("не объявлены"))
        assertEquals(1, store.count())
    }

    @Test
    fun saveUpdatesExistingSkillByNameAndKeepsCreatedAt() = runTest {
        tool("skill_save").execute(args("name" to "Обновляемый", "steps" to validSteps))
        val before = store.list().single()

        val newSteps = "[{\"type\":\"key\",\"keyName\":\"BACK\"}]"
        val result = tool("skill_save").execute(
            args("name" to "Обновляемый", "steps" to newSteps, "description" to "новая версия")
        )
        assertTrue(result.output, result.success)
        assertTrue(result.output.contains("обновлён"))
        assertEquals(1, store.count())
        val after = store.list().single()
        assertEquals(before.id, after.id)
        assertEquals(before.createdAt, after.createdAt)
        assertEquals("новая версия", after.description)
        assertEquals(1, after.steps.size)
    }

    @Test
    fun getReturnsFullDefinitionByIdOrName() = runTest {
        tool("skill_save").execute(args("name" to "Такси", "steps" to validSteps))
        val id = store.list().single().id

        val byId = tool("skill_get").execute(args("id" to id))
        assertTrue(byId.output, byId.success)
        assertTrue(byId.output.contains("\"name\""))
        assertTrue(byId.output, byId.output.contains("Такси"))

        val byName = tool("skill_get").execute(args("name" to "Такси"))
        assertTrue(byName.output, byName.success)
        assertTrue(byName.output, byName.output.contains(id))
    }

    @Test
    fun getMissingSkillIsAnHonestError() = runTest {
        val result = tool("skill_get").execute(args("name" to "Нет такого"))
        assertFalse(result.success)
        assertTrue(result.output, result.output.contains("не найден"))
    }

    @Test
    fun getWithoutIdOrNameExplainsWhatIsMissing() = runTest {
        val result = tool("skill_get").execute(args())
        assertFalse(result.success)
        assertTrue(result.output, result.output.contains("id или name"))
    }

    @Test
    fun listReportsEmptyAndNonEmptyStates() = runTest {
        val empty = tool("skill_list").execute(args())
        assertTrue(empty.success)
        assertTrue(empty.output, empty.output.contains("нет"))

        tool("skill_save").execute(args("name" to "Первый", "steps" to validSteps))
        val listed = tool("skill_list").execute(args())
        assertTrue(listed.success)
        assertTrue(listed.output, listed.output.contains("Первый"))
    }

    @Test
    fun deleteRemovesByNameAndIsHonestWhenMissing() = runTest {
        tool("skill_save").execute(args("name" to "Удаляемый", "steps" to validSteps))
        val removed = tool("skill_delete").execute(args("name" to "Удаляемый"))
        assertTrue(removed.output, removed.success)
        assertTrue(store.list().isEmpty())

        val missing = tool("skill_delete").execute(args("name" to "Удаляемый"))
        assertFalse(missing.success)
        assertNotNull(missing.output)
    }
}
