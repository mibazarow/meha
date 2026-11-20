package com.andharness.ru.data.tools.saf

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.saf.SafGrantRepository
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `saf_grant_remove`: releases a grant's persisted permission and removes the stored row. */
internal class SafGrantRemoveToolHandler(
    private val context: Context,
    private val safGrantRepository: SafGrantRepository,
) : ToolHandler {

    override val name = "saf_grant_remove"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val raw = (arguments["grant_id"] as? JsonPrimitive)?.content?.trim()
            val id = raw?.toLongOrNull() ?: return@withContext ToolResult(
                false,
                "Некорректный grant_id: «$raw». Используйте saf_grants_list."
            )
            val grant = safGrantRepository.get(id) ?: return@withContext ToolResult(
                false,
                "Грант #$id не найден. Используйте saf_grants_list."
            )

            // releasePersistableUriPermission —
            // https://developer.android.com/reference/android/content/ContentResolver#releasePersistableUriPermission(android.net.Uri,%20int)
            try {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(grant.uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Best effort only: the row is still removed below.
            }

            safGrantRepository.remove(id)

            if (safGrantRepository.get(id) == null) {
                ToolResult(
                    true,
                    "Доступ к папке отозван: #$id ${grant.displayName}"
                )
            } else {
                ToolResult(false, "Не удалось удалить грант #$id.")
            }
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
