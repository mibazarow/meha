package com.andharness.ru.data.tools.saf

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `saf_delete`: deletes a document inside a persisted tree grant, post-verified. */
internal class SafDeleteToolHandler(
    private val access: SafDocumentAccess,
) : ToolHandler {

    override val name = "saf_delete"

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
                return@withContext ToolResult(false, "Нельзя удалить корень гранта.")
            }
            val doc = access.resolveDocument(id, path)
                ?: return@withContext ToolResult(false, "Документ не найден: «${path?.trim().orEmpty()}».")
            // DocumentFile.delete() —
            // https://developer.android.com/reference/androidx/documentfile/provider/DocumentFile#delete()
            val deleted = doc.delete()
            // Post-delete verification: the document must no longer resolve.
            val stillThere = access.resolveDocument(id, path) != null
            if (!deleted || stillThere) {
                return@withContext ToolResult(false, "Не удалось удалить: «${path?.trim().orEmpty()}».")
            }
            ToolResult(true, "Удалено: «${path?.trim().orEmpty()}».")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
