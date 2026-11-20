package com.andharness.ru.data.tools.saf

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `saf_mkdir`: creates a directory (and any missing parents) inside a persisted tree grant. */
internal class SafMkdirToolHandler(
    private val access: SafDocumentAccess,
) : ToolHandler {

    override val name = "saf_mkdir"

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
            val segments = access.pathSegments(path)
                ?: return@withContext ToolResult(false, access.invalidPathMessage(path))
            if (segments.isEmpty()) {
                return@withContext ToolResult(false, "Нужен путь к создаваемой папке внутри гранта.")
            }
            // Every missing segment is created; the result is verified to be a directory.
            val dir = access.ensureDirectory(id, segments)
                ?: return@withContext ToolResult(
                    false,
                    "Не удалось создать папку: «${path?.trim().orEmpty()}»."
                )
            if (!dir.isDirectory) {
                return@withContext ToolResult(
                    false,
                    "Не удалось создать папку: «${path?.trim().orEmpty()}»."
                )
            }
            ToolResult(true, "Папка готова: «${path?.trim().orEmpty()}».")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
