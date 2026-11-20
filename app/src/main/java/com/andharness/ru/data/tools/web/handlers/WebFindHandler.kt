package com.andharness.ru.data.tools.web.handlers

import android.util.Log
import com.andharness.ru.data.tools.str
import com.andharness.ru.data.tools.web.WebEngineHost
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal class WebFindHandler(private val host: WebEngineHost) : ToolHandler {
    override val name = "web_find"
    override suspend fun execute(arguments: JsonObject): ToolResult = host.guarded {
        val tab = host.activeTab() ?: return@guarded ToolResult(false, "Сначала откройте страницу (web_open).")
        if (tab.view == null) return@guarded ToolResult(false, "Сначала откройте страницу (web_open).")
        val query = arguments.str("query") ?: return@guarded ToolResult(false, "Укажите query.")
        val js = buildString {
            append("(function(){var q=").append(host.jsonStr(query)).append(";")
            append("var b=document.body;if(!b)return JSON.stringify({count:0,snippets:[]});")
            append("var t=b.innerText||b.textContent||'';var lo=t.toLowerCase(),ql=q.toLowerCase();")
            append("var c=0,idx=0,sn=[];")
            append("while(true){var i=lo.indexOf(ql,idx);if(i<0)break;c++;")
            append("if(sn.length<5){sn.push(t.substring(Math.max(0,i-60),Math.min(t.length,i+q.length+60)).replace(/\\s+/g,' ').trim());}")
            append("idx=i+ql.length;if(c>2000)break;}")
            append("return JSON.stringify({count:c,snippets:sn});})()")
        }
        val obj = host.decode(host.evalJs(tab, js))?.let {
            try {
                Json.parseToJsonElement(it).jsonObject
            } catch (e: Exception) {
                Log.w(WebEngineConsts.TAG, "web search JSON parse failed", e)
                null
            }
        } ?: return@guarded ToolResult(false, "Не удалось выполнить поиск по странице.")
        val count = (obj["count"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        val snippets = (obj["snippets"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        if (count <= 0) {
            ToolResult(true, "Совпадений «$query» на странице не найдено.")
        } else {
            ToolResult(
                true,
                buildString {
                    appendLine("Найдено совпадений «$query»: $count.")
                    snippets.forEachIndexed { i, s -> appendLine("${i + 1}) …$s…") }
                }.trim(),
            )
        }
    }
}
