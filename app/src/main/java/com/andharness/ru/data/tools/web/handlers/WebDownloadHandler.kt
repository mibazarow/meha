package com.andharness.ru.data.tools.web.handlers

import com.andharness.ru.data.tools.str
import com.andharness.ru.data.tools.web.WebEngineHost
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject

internal class WebDownloadHandler(private val host: WebEngineHost) : ToolHandler {
    override val name = "web_download"
    override suspend fun execute(arguments: JsonObject): ToolResult = host.guarded {
        val url = arguments.str("url") ?: return@guarded ToolResult(false, "Укажите url.")
        if (!host.isHttpUrl(url)) {
            return@guarded ToolResult(false, "Поддерживаются только http/https ссылки.")
        }
        val id = host.enqueueDownload(
            url = url,
            userAgent = host.customUserAgent,
            contentDisposition = null,
            mimeType = null,
        ) ?: return@guarded ToolResult(
            false,
            "Системный менеджер загрузок недоступен — скачать «$url» не удалось.",
        )
        host.reportDownload(id, url)
    }
}
