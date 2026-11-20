package com.andharness.ru.data.tools.skill

import com.andharness.ru.data.skill.SkillStore
import com.andharness.ru.domain.skill.Skill
import com.andharness.ru.domain.skill.SkillCodec
import com.andharness.ru.domain.skill.SkillSelector
import com.andharness.ru.domain.skill.SkillStep
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for `skill_export`/`skill_import` (M2 slice 3): exact JSON round-trip through [SkillCodec],
 * honest per-skill reporting and the duplicate/overwrite policy. Only the Android-free [SkillStore]
 * is used, so this runs without an Android runtime.
 */
class SkillTransferToolsHandlerTest {

    private lateinit var dir: File
    private lateinit var store: SkillStore
    private lateinit var handler: SkillTransferToolsHandler

    private fun args(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
        pairs.forEach { (key, value) ->
            when (value) {
                is Boolean -> put(key, value)
                else -> put(key, value.toString())
            }
        }
    }

    private fun tool(name: String) = handler.handlers().first { it.name == name }

    private fun skill(id: String = "s1", name: String = "Такси") = Skill(
        id = id,
        name = name,
        appPackage = "com.taxi.app",
        steps = listOf(
            SkillStep.LaunchApp("com.taxi.app"),
            SkillStep.Tap(SkillSelector(textContains = "Оплатить")),
        ),
        createdAt = 1_000L,
        updatedAt = 2_000L,
    )

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("skill-transfer-test").toFile()
        store = SkillStore(dir)
        handler = SkillTransferToolsHandler(store)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun exportSingleSkillRoundTripsThroughImport() = runTest {
        store.save(skill())
        val exported = tool("skill_export").execute(args("name" to "Такси"))
        assertTrue(exported.output, exported.success)

        val parsed = SkillCodec.parseImport(exported.output)
        assertTrue(parsed is SkillCodec.ImportParseResult.Ok)
        val item = (parsed as SkillCodec.ImportParseResult.Ok).items.single()
        assertEquals("s1", item.skill?.id)
        assertEquals(skill().steps, item.skill?.steps)
    }

    @Test
    fun exportAllReturnsAnArrayOfEverySkill() = runTest {
        store.save(skill())
        store.save(skill("s2", "Второй"))
        val exported = tool("skill_export").execute(args("all" to true))
        assertTrue(exported.output, exported.success)
        val parsed = SkillCodec.parseImport(exported.output) as SkillCodec.ImportParseResult.Ok
        assertEquals(listOf("s1", "s2"), parsed.items.map { it.skill?.id })
    }

    @Test
    fun exportNeedsAConcreteTargetOrAll() = runTest {
        store.save(skill())
        val noTarget = tool("skill_export").execute(args())
        assertFalse(noTarget.success)
        assertTrue(noTarget.output, noTarget.output.contains("all=true"))

        val missing = tool("skill_export").execute(args("name" to "Нет такого"))
        assertFalse(missing.success)
        assertTrue(missing.output, missing.output.contains("не найден"))
    }

    @Test
    fun exportAllIsHonestWhenLibraryIsEmpty() = runTest {
        val result = tool("skill_export").execute(args("all" to true))
        assertFalse(result.success)
        assertTrue(result.output, result.output.contains("нечего"))
    }

    @Test
    fun importIntoAFreshStoreAddsTheSkill() = runTest {
        store.save(skill())
        val exported = tool("skill_export").execute(args("all" to true)).output

        val freshDir = Files.createTempDirectory("skill-transfer-fresh").toFile()
        try {
            val freshStore = SkillStore(freshDir)
            val fresh = SkillTransferToolsHandler(freshStore)
            val imported = fresh.handlers().first { it.name == "skill_import" }
                .execute(args("json" to exported))
            assertTrue(imported.output, imported.success)
            assertEquals(1, freshStore.count())
            assertEquals("s1", freshStore.list().single().id)
        } finally {
            freshDir.deleteRecursively()
        }
    }

    @Test
    fun duplicateIsSkippedUnlessOverwriteIsSet() = runTest {
        store.save(skill())
        val exported = tool("skill_export").execute(args("all" to true)).output

        val skipped = tool("skill_import").execute(args("json" to exported))
        assertFalse(skipped.success)
        assertTrue(skipped.output, skipped.output.contains("пропущен"))
        assertEquals(1, store.count())

        val replacedSkill = skill().copy(steps = listOf(SkillStep.Key("BACK")))
        val overwrite = tool("skill_import").execute(
            args("json" to SkillCodec.encodeSkill(replacedSkill), "overwrite" to true)
        )
        assertTrue(overwrite.output, overwrite.success)
        assertEquals(1, store.count())
        assertEquals(listOf(SkillStep.Key("BACK")), store.list().single().steps)
        assertEquals("s1", store.list().single().id)
    }

    @Test
    fun importOverwritesByNameKeepingTheExistingSlot() = runTest {
        store.save(skill("s1", "Такси"))
        val sameNameDifferentId = skill("s-new", "Такси").copy(steps = listOf(SkillStep.Key("HOME")))
        val result = tool("skill_import").execute(
            args("json" to SkillCodec.encodeSkill(sameNameDifferentId), "overwrite" to true)
        )
        assertTrue(result.output, result.success)
        assertEquals(1, store.count())
        assertEquals("s1", store.list().single().id)
        assertEquals(listOf(SkillStep.Key("HOME")), store.list().single().steps)
    }

    @Test
    fun importReportsGoodAndBadItemsHonestly() = runTest {
        val good = SkillCodec.encodeSkill(skill("ok", "Хороший"))
        val bad = "{\"id\":\"bad\",\"name\":\"Плохой\",\"steps\":[{\"type\":\"teleport\"}]}"
        val result = tool("skill_import").execute(args("json" to "[$good,$bad]"))
        assertTrue(result.output, result.success)
        assertTrue(result.output, result.output.contains("импортировано 1"))
        assertTrue(result.output, result.output.contains("пропущено 1"))
        assertTrue(result.output, result.output.contains("Плохой"))
        assertEquals(1, store.count())
    }

    @Test
    fun importRejectsBrokenJsonAndMissingArgument() = runTest {
        val missing = tool("skill_import").execute(args())
        assertFalse(missing.success)
        assertTrue(missing.output, missing.output.contains("json"))

        val broken = tool("skill_import").execute(args("json" to "{not json"))
        assertFalse(broken.success)
        assertTrue(broken.output, broken.output.contains("не удалось разобрать"))
        assertEquals(0, store.count())
    }
}
