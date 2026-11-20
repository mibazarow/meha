package com.andharness.ru.data.tools.files

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import java.io.File

/** `files_list`: lists the entries of a directory. */
internal class FilesListToolHandler(private val io: FileToolIo) : ToolHandler {
    override val name = "files_list"
    override suspend fun execute(arguments: JsonObject): ToolResult = try {
        val pathArg = io.path(arguments, "path")
        val dir = File(pathArg)
        if (!dir.exists() || !dir.isDirectory) {
            ToolResult(false, "Ошибка: «$pathArg» не является директорией.")
        } else {
            val children = dir.listFiles()
            val out = if (children.isNullOrEmpty()) "" else
                children.joinToString("\n") { child ->
                    "${child.name}${if (child.isDirectory) " [dir]" else ""}"
                }
            ToolResult(true, out)
        }
    } catch (e: Exception) {
        ToolResult(false, "Ошибка: ${e.message}")
    }
}
