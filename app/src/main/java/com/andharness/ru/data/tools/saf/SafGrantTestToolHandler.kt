package com.andharness.ru.data.tools.saf

import android.net.Uri
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.saf.SafGrantRepository
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `saf_grant_test`: checks whether a single grant is still usable right now. */
internal class SafGrantTestToolHandler(
    private val safGrantRepository: SafGrantRepository,
    private val access: SafDocumentAccess,
) : ToolHandler {

    override val name = "saf_grant_test"

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

            val ok = access.isGrantAlive(Uri.parse(grant.uri))
            safGrantRepository.markChecked(id, ok)

            if (ok) {
                ToolResult(true, "Грант #$id ${grant.displayName}: доступен.")
            } else {
                ToolResult(
                    false,
                    "Грант #$id ${grant.displayName}: недоступен (перевыберите папку через saf_pick_tree)."
                )
            }
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
