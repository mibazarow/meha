package com.andharness.ru.domain.agent

import com.andharness.ru.domain.tools.DefaultTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolLabelsTest {

    @Test
    fun knownToolsHaveHumanReadableLabels() {
        assertEquals("Смотрю файлы", ToolLabels.label("files_list"))
        assertEquals("Ищу файлы", ToolLabels.label("files_search"))
        assertEquals("Записываю файл", ToolLabels.label("files_write"))
        assertEquals("Объединяю PDF", ToolLabels.label("pdf_merge"))
        assertEquals("Создаю PDF", ToolLabels.label("pdf_create"))
        assertEquals("Создаю PDF из Markdown", ToolLabels.label("pdf_from_markdown"))
        assertEquals("Открываю сайт", ToolLabels.label("web_fetch"))
        assertEquals("Открываю сайт", ToolLabels.label("web_open"))
        assertEquals("Отправляю SMS", ToolLabels.label("sms_send"))
        assertEquals("Ставлю задачу по расписанию", ToolLabels.label("schedule_create"))
        assertEquals("Печатаю на экране", ToolLabels.label("ui_type"))
        assertEquals("Ищу в памяти", ToolLabels.label("memory_search"))
        assertEquals("Смотрю память", ToolLabels.label("memory_list"))
        assertEquals("Удаляю из памяти", ToolLabels.label("memory_delete"))
    }

    @Test
    fun unknownToolFallsBackToHumanizedActionName() {
        assertEquals("Действие: foo bar baz", ToolLabels.label("foo_bar_baz"))
        assertEquals("Действие: mysterytool", ToolLabels.label("mysterytool"))
    }

    @Test
    fun dynamicMcpToolGetsAReadableMcpLabel() {
        assertEquals("MCP: mcpabc get weather", ToolLabels.label("mcp_mcpabc_get_weather"))
        assertFalse(ToolLabels.label("mcp_mcpabc_get_weather").contains('_'))
    }

    @Test
    fun fallbackNeverLeavesSnakeCaseInTheLabel() {
        val label = ToolLabels.label("some_unknown_tool")
        assertFalse("подпись не должна содержать подчёркиваний: $label", label.contains('_'))
    }

    @Test
    fun blankToolDegradesGracefullyAndQuietly() {
        assertEquals("Действие: неизвестно", ToolLabels.label(""))
        assertEquals("Действие: неизвестно", ToolLabels.label("   "))
    }

    @Test
    fun everyRegisteredToolHasAHumanLabel() {
        val missing = DefaultTools.all()
            .map { it.name }
            .filter { ToolLabels.label(it).startsWith("Действие: ") }
        assertTrue("инструменты без человеческой подписи: $missing", missing.isEmpty())
    }
}
