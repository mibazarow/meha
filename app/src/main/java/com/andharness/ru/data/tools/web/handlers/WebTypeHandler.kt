package com.andharness.ru.data.tools.web.handlers

import com.andharness.ru.data.tools.str
import com.andharness.ru.data.tools.web.WebEngineHost
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class WebTypeHandler(private val host: WebEngineHost) : ToolHandler {
    override val name = "web_type"
    override suspend fun execute(arguments: JsonObject): ToolResult = host.guarded {
        val tab = host.activeTab() ?: return@guarded ToolResult(false, "Сначала откройте страницу (web_open).")
        if (tab.view == null) return@guarded ToolResult(false, "Сначала откройте страницу (web_open).")
        val selector = arguments.str("selector") ?: return@guarded ToolResult(false, "Укажите selector поля ввода.")
        val text = (arguments["text"] as? JsonPrimitive)?.content
            ?: return@guarded ToolResult(false, "Укажите text.")
        val submit = (arguments["submit"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
        val js = buildString {
            append("(function(){var el=document.querySelector(").append(host.jsonStr(selector)).append(");")
            append("if(!el)return 'NOT_FOUND';")
            append("var p=(el.tagName==='TEXTAREA')?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;")
            append("var setter=Object.getOwnPropertyDescriptor(p,'value').set;")
            append("setter.call(el,").append(host.jsonStr(text)).append(");")
            append("el.dispatchEvent(new Event('input',{bubbles:true}));")
            append("el.dispatchEvent(new Event('change',{bubbles:true}));")
            append("if(").append(if (submit) "true" else "false").append("){")
            append("if(el.form){if(el.form.requestSubmit){el.form.requestSubmit();}else{el.form.submit();}return 'SUBMITTED';}}")
            append("return 'OK';})()")
        }
        val res = host.decode(host.evalJs(tab, js))
        delay(WebEngineConsts.CLICK_SETTLE_MS)
        when (res) {
            "NOT_FOUND" -> ToolResult(false, "Поле «$selector» не найдено.")
            "SUBMITTED" -> ToolResult(true, "Текст введён и форма отправлена.\n${host.pageSummary(tab)}")
            else -> ToolResult(true, "Текст введён в «$selector».\n${host.pageSummary(tab)}")
        }
    }
}
