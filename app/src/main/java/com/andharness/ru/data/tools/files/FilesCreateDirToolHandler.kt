package com.andharness.ru.data.tools.files

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import java.io.File

/** `files_create_dir`: creates a directory (and any missing parents). */
internal class FilesCreateDirToolHandler(private val io: FileToolIo) : ToolHandler {
    override val name = "files_create_dir"
    override suspend fun execute(arguments: JsonObject): ToolResult = try {
        val dir = File(io.path(arguments, "path"))
        val existed = dir.exists()
        dir.mkdirs()
        if (dir.isDirectory) {
            ToolResult(
                true,
                if (existed) "Директория уже существует: ${dir.absolutePath}"
                else "Создана директория: ${dir.absolutePath}"
            )
        } else {
            ToolResult(false, "Не удалось создать каталог: ${dir.absolutePath}")
        }
    } catch (e: Exception) {
        ToolResult(false, "Ошибка: ${e.message}")
    }
}
