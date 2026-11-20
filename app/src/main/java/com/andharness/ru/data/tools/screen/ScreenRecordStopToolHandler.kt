package com.andharness.ru.data.tools.screen

import android.content.Context
import com.andharness.ru.bg.ScreenRecordService
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * `screen_record_stop`: stops an ongoing recording and post-verifies that the output file exists and
 * is non-empty before reporting success.
 */
internal class ScreenRecordStopToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "screen_record_stop"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val before = ScreenRecordingState.status.value
        if (!before.active) {
            return ToolResult(false, "Запись экрана не идёт.")
        }
        val path = before.path
        ScreenRecordService.stop(context)
        val stopped = withTimeoutOrNull(ScreenSupport.STOP_TIMEOUT_MS) {
            ScreenRecordingState.status.first { !it.active }
        }
        if (stopped == null) {
            return ToolResult(
                false,
                "Не удалось остановить запись экрана за ${ScreenSupport.STOP_TIMEOUT_MS / 1000} с.",
            )
        }
        if (path.isNullOrEmpty()) {
            return ToolResult(false, "Запись остановлена, но путь к файлу неизвестен.")
        }
        // POST-VERIFY: the file must exist and be non-empty, otherwise the stop is not a success.
        val file = File(path)
        return if (file.exists() && file.length() > 0L) {
            ToolResult(
                true,
                "Запись экрана остановлена и сохранена: «${file.absolutePath}» (${file.length()} байт).",
            )
        } else {
            ToolResult(false, "Запись остановлена, но файл пуст или не создан: «$path».")
        }
    }
}
