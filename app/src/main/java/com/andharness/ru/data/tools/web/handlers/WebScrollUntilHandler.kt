package com.andharness.ru.data.tools.web.handlers

import com.andharness.ru.data.tools.str
import com.andharness.ru.data.tools.web.WebEngineHost
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

internal class WebScrollUntilHandler(private val host: WebEngineHost) : ToolHandler {
    override val name = "web_scroll_until"
    override suspend fun execute(arguments: JsonObject): ToolResult = host.guarded {
        val tab = host.activeTab() ?: return@guarded ToolResult(false, "Сначала откройте страницу (web_open).")
        if (tab.view == null) return@guarded ToolResult(false, "Сначала откройте страницу (web_open).")
        val maxSteps = arguments.str("max_steps")?.toIntOrNull()?.coerceIn(1, 50) ?: 20
        val heightJs =
            "(function(){var b=document.body;var d=document.documentElement;var h=Math.max(b?b.scrollHeight:0,d?d.scrollHeight:0);return String(h);})()"
        val scrollJs =
            "(function(){var b=document.body;var d=document.documentElement;var h=Math.max(b?b.scrollHeight:0,d?d.scrollHeight:0);window.scrollTo(0,h);return 'OK';})()"
        var steps = 0
        var lastHeight = -1
        var stable = 0
        var height = 0
        while (steps < maxSteps) {
            height = host.decode(host.evalJs(tab, heightJs))?.toDoubleOrNull()?.toInt() ?: height
            if (height <= lastHeight) {
                stable += 1
                if (stable >= 2) break
            } else {
                stable = 0
            }
            lastHeight = height
            host.evalJs(tab, scrollJs)
            delay(WebEngineConsts.SCROLL_UNTIL_SETTLE_MS)
            steps += 1
        }
        ToolResult(
            true,
            "Прокрутил до конца: шагов $steps, итоговая высота документа ${lastHeight.coerceAtLeast(0)} px" +
                if (steps >= maxSteps) " (достигнут лимит max_steps=$maxSteps)." else ".",
        )
    }
}
