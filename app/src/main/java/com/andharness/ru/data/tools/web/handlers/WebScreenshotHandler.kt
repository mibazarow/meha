package com.andharness.ru.data.tools.web.handlers

import com.andharness.ru.data.tools.effectiveToolRoot
import com.andharness.ru.data.tools.resolveSafePath
import com.andharness.ru.data.tools.str
import com.andharness.ru.data.tools.web.WebEngineHost
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

internal class WebScreenshotHandler(private val host: WebEngineHost) : ToolHandler {
    override val name = "web_screenshot"
    override suspend fun execute(arguments: JsonObject): ToolResult = host.guarded {
        val tab = host.activeTab() ?: return@guarded ToolResult(false, "Сначала откройте страницу (web_open).")
        val view = tab.view ?: return@guarded ToolResult(false, "Сначала откройте страницу (web_open).")
        val rawPath = arguments.str("path")
            ?: return@guarded ToolResult(false, "Укажите path — путь для сохранения PNG.")
        val file = try {
            resolveSafePath(host.appContext.effectiveToolRoot(), rawPath)
        } catch (e: IllegalArgumentException) {
            return@guarded ToolResult(false, e.message ?: "Некорректный путь.")
        }
        val bitmap = withContext(Dispatchers.Main.immediate) { host.captureBitmap(view) }
            ?: return@guarded ToolResult(false, "Не удалось снять скриншот страницы (WebView не отрисован).")
        val bytes = try {
            host.writePngAtomically(bitmap, file)
        } finally {
            bitmap.recycle()
        }
        ToolResult(
            true,
            "Скриншот страницы сохранён: «${file.absolutePath}» ($bytes байт).",
        )
    }
}
