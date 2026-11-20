package com.andharness.ru.domain.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [SkillCodec.parseImport]/[SkillCodec.validateSkill]: an import blob (single skill or
 * array) is validated per item with the same rules as `skill_save`, and `skill_export` output
 * round-trips exactly.
 */
class SkillCodecImportTest {

    private fun skill(id: String = "s1", name: String = "Заказать такси") = Skill(
        id = id,
        name = name,
        appPackage = "com.taxi.app",
        steps = listOf(
            SkillStep.LaunchApp("com.taxi.app"),
            SkillStep.Tap(SkillSelector(textContains = "Оплатить")),
        ),
    )

    private fun okItems(raw: String): List<SkillCodec.SkillImportItem> {
        val parsed = SkillCodec.parseImport(raw)
        assertTrue("ожидался Ok, получено $parsed", parsed is SkillCodec.ImportParseResult.Ok)
        return (parsed as SkillCodec.ImportParseResult.Ok).items
    }

    @Test
    fun blankOrBrokenJsonIsRejected() {
        assertTrue(SkillCodec.parseImport(null) is SkillCodec.ImportParseResult.Err)
        assertTrue(SkillCodec.parseImport("   ") is SkillCodec.ImportParseResult.Err)
        val broken = SkillCodec.parseImport("{not json")
        assertTrue(broken is SkillCodec.ImportParseResult.Err)
        assertTrue((broken as SkillCodec.ImportParseResult.Err).message.contains("не удалось разобрать"))
    }

    @Test
    fun primitiveJsonIsRejected() {
        val result = SkillCodec.parseImport("42")
        assertTrue(result is SkillCodec.ImportParseResult.Err)
        assertTrue((result as SkillCodec.ImportParseResult.Err).message.contains("объект"))
    }

    @Test
    fun singleSkillObjectIsImported() {
        val item = okItems(SkillCodec.encodeSkill(skill())).single()
        assertEquals("Заказать такси", item.name)
        assertNull(item.error)
        assertEquals(listOf("s1"), listOf(item.skill?.id))
    }

    @Test
    fun arrayOfSkillsIsImported() {
        val raw = SkillCodec.encodeSkills(listOf(skill(), skill("s2", "Второй")))
        assertEquals(listOf("s1", "s2"), okItems(raw).map { it.skill?.id })
    }

    @Test
    fun itemWithoutNameIsReportedWithAnError() {
        val bad = SkillCodec.encodeSkill(skill(id = "s9", name = ""))
        val items = okItems(bad)
        assertEquals(1, items.size)
        assertNull(items.single().skill)
        assertTrue(items.single().error, items.single().error!!.contains("пустое имя"))
    }

    @Test
    fun itemWithAnInvalidStepIsRejectedAndOthersSurvive() {
        val good = SkillCodec.encodeSkill(skill("ok", "Хороший"))
        val bad = "{\"id\":\"bad\",\"name\":\"Плохой\",\"steps\":[{\"type\":\"tap\",\"selector\":{\"className\":\"android.widget.Button\"}}]}"
        val items = okItems("[$good,$bad]")
        assertEquals(2, items.size)
        assertNull(items[0].error)
        assertEquals("ok", items[0].skill?.id)
        assertNull(items[1].skill)
        assertTrue(items[1].error, items[1].error!!.contains("шаг #1"))
    }

    @Test
    fun exportRoundTripsThroughImport() {
        val original = listOf(skill(), skill("s2", "Второй"))
        val items = okItems(SkillCodec.encodeSkills(original))
        assertEquals(original.map { it.id }, items.map { it.skill?.id })
        assertEquals(original.map { it.steps }, items.map { it.skill?.steps })
    }
}
