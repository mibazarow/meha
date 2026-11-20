package com.andharness.ru.data.tools.saf

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `saf_list`: lists a directory inside a persisted tree grant. */
internal class SafListToolHandler(
    private val access: SafDocumentAccess,
) : ToolHandler {

    override val name = "saf_list"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val id = access.grantIdArg(arguments) ?: return@withContext ToolResult(
                false,
                "Некорректный grant_id: «${(arguments["grant_id"] as? JsonPrimitive)?.content}». Используйте saf_grants_list."
            )
            if (access.grantUri(id) == null) {
                return@withContext ToolResult(false, "Грант #$id не найден. Используйте saf_grants_list.")
            }
            val path = (arguments["path"] as? JsonPrimitive)?.content
            if (access.pathSegments(path) == null) {
                return@withContext ToolResult(false, access.invalidPathMessage(path))
            }
            // DocumentFile.listFiles() / isDirectory / name —
            // https://developer.android.com/reference/androidx/documentfile/provider/DocumentFile
            val dir = access.resolveDocument(id, path)
                ?: return@withContext ToolResult(false, "Папка не найдена: «${path?.trim().orEmpty()}».")
            if (!dir.isDirectory) {
                return@withContext ToolResult(false, "«${dir.name ?: "?"}» — не директория.")
            }
            val children = dir.listFiles()
            if (children.isEmpty()) {
                return@withContext ToolResult(false, "Папка пуста: ${dir.name ?: "(без имени)"}.")
            }
            ToolResult(
                true,
                children.joinToString("\n") { child ->
                    "${child.name ?: "(без имени)"}${if (child.isDirectory) " [dir]" else ""}"
                }
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
