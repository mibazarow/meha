package com.andharness.ru.data.tools.files

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import java.io.File

/** `files_stat`: reports the size, type, mtime and read/write flags of a path. */
internal class FilesStatToolHandler(private val io: FileToolIo) : ToolHandler {
    override val name = "files_stat"
    override suspend fun execute(arguments: JsonObject): ToolResult = try {
        val file = File(io.path(arguments, "path"))
        if (!file.exists()) {
            ToolResult(false, "Не найдено: ${file.absolutePath}")
        } else {
            ToolResult(
                true,
                "size=${file.length()} isDir=${file.isDirectory} modified=${file.lastModified()} " +
                    "canRead=${file.canRead()} canWrite=${file.canWrite()}"
            )
        }
    } catch (e: Exception) {
        ToolResult(false, "Ошибка: ${e.message}")
    }
}
