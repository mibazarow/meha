package com.andharness.ru.data.tools.files

import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * `files_open`: opens a file in a resolved app (no system chooser) or a file manager for a
 * directory. The optional `app` argument forces a specific package. When nothing can open the MIME
 * type the launcher returns an honest failure with Google Play suggestions.
 */
internal class FilesOpenToolHandler(
    private val io: FileToolIo,
    private val launcher: FileOpenLauncher,
) : ToolHandler {
    override val name = "files_open"
    override suspend fun execute(arguments: JsonObject): ToolResult = try {
        val pathArg = io.path(arguments, "path")
        val app = arguments.str("app")
        val file = File(pathArg)
        if (!file.exists()) {
            ToolResult(false, "Не найдено: «$pathArg».")
        } else if (file.isDirectory) {
            launcher.openDirectory(file)
        } else {
            launcher.openFile(file, app)
        }
    } catch (e: Exception) {
        ToolResult(false, "Ошибка: ${e.message}")
    }
}
