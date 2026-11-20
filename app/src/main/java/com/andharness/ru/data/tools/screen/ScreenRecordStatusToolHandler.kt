package com.andharness.ru.data.tools.screen

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * `screen_record_status`: reports whether a recording is active, its elapsed time, output path and
 * current file size.
 */
internal class ScreenRecordStatusToolHandler : ToolHandler {

    override val name = "screen_record_status"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val status = ScreenRecordingState.status.value
        if (!status.active) {
            val suffix = status.error?.let { " Последняя ошибка: $it" } ?: ""
            return@withContext ToolResult(true, "Запись экрана не идёт.$suffix")
        }
        val file = status.path?.let { File(it) }
        val size = if (file?.exists() == true) file.length() else 0L
        val elapsedSec = if (status.startedAtEpochMs > 0) {
            (System.currentTimeMillis() - status.startedAtEpochMs) / 1000
        } else {
            0L
        }
        ToolResult(
            true,
            "Запись экрана идёт ${elapsedSec}с: «${status.path}» ($size байт)" +
                (if (status.withAudio) ", со звуком микрофона" else "") + ".",
        )
    }
}
