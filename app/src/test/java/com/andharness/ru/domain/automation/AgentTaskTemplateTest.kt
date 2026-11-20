package com.andharness.ru.domain.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentTaskTemplateTest {

    @Test
    fun rendersEveryKnownPlaceholder() {
        val event = AutomationEvent(
            triggerType = AutomationSpec.TRIGGER_NOTIFICATION,
            value = "значение",
            payload = "полезная нагрузка"
        )
        val rendered = AgentTaskTemplate.render(
            "trigger={trigger} value={value} payload={payload} name={name}",
            event,
            "Моё правило"
        )
        assertEquals(
            "trigger=notification value=значение payload=полезная нагрузка name=Моё правило",
            rendered
        )
    }

    @Test
    fun leavesUnknownPlaceholdersUntouched() {
        val rendered = AgentTaskTemplate.render(
            "a {unknown} b {value}",
            AutomationEvent(AutomationSpec.TRIGGER_BOOT, value = "v"),
            "r"
        )
        assertEquals("a {unknown} b v", rendered)
    }

    @Test
    fun missingOrBlankPayloadRendersEmpty() {
        val event = AutomationEvent(AutomationSpec.TRIGGER_BOOT)
        assertEquals("payload=''", AgentTaskTemplate.render("payload='{payload}'", event, "r"))

        val blank = AutomationEvent(AutomationSpec.TRIGGER_BOOT, payload = "   ")
        assertEquals("payload=''", AgentTaskTemplate.render("payload='{payload}'", blank, "r"))

        val missingValue = AutomationEvent(AutomationSpec.TRIGGER_BOOT, payload = "p")
        assertEquals("value=''", AgentTaskTemplate.render("value='{value}'", missingValue, "r"))
    }

    @Test
    fun templateWithoutPlaceholdersPassesThroughUnchanged() {
        val template = "Просто запрос без подстановок"
        val rendered = AgentTaskTemplate.render(
            template,
            AutomationEvent(AutomationSpec.TRIGGER_BOOT),
            "r"
        )
        assertEquals(template, rendered)
    }

    @Test
    fun emptyTemplateStaysEmpty() {
        assertEquals("", AgentTaskTemplate.render("", AutomationEvent(AutomationSpec.TRIGGER_BOOT), "r"))
    }
}
