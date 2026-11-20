package com.andharness.ru.data.tools.ui

import android.os.Build
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `screen_capture`: takes an accessibility screenshot and saves it to the gallery (API 30+). */
internal class ScreenCaptureToolHandler(
    private val engine: UiActionEngine,
    private val capture: UiScreenCapture,
) : ToolHandler {
    override val name = "screen_capture"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.Main) {
        val service = engine.serviceOrNull() ?: return@withContext engine.notEnabled()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return@withContext ToolResult(false, "Скриншот через спецвозможности требует Android 11+.")
        }
        try {
            val bitmap = capture.captureScreen(service)
                ?: return@withContext ToolResult(false, "Скриншот не удался (система отклонила захват).")
            val location = withContext(Dispatchers.IO) { capture.saveToGallery(bitmap) }
            bitmap.recycle()
            if (location == null) ToolResult(false, "Не удалось сохранить скриншот.")
            else ToolResult(true, "Скриншот сохранён: $location")
        } catch (e: CancellationException) {
            // A cancel/withTimeout must reach the run loop instead of being reported as a
            // normal tool failure (update.md bug #7) — otherwise the timeout is defeated.
            throw e
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
