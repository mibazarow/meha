package com.andharness.ru.data.tools.saf

import android.net.Uri
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.saf.SafGrantRepository
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `saf_grants_list`: lists persisted folder grants and checks each one's live availability. */
internal class SafGrantsListToolHandler(
    private val safGrantRepository: SafGrantRepository,
    private val access: SafDocumentAccess,
) : ToolHandler {

    override val name = "saf_grants_list"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val grants = safGrantRepository.list()
            if (grants.isEmpty()) {
                return@withContext ToolResult(
                    true,
                    "Нет сохранённых папок. Используйте saf_pick_tree."
                )
            }
            val lines = grants.map { grant ->
                val ok = access.isGrantAlive(Uri.parse(grant.uri))
                safGrantRepository.markChecked(grant.id, ok)
                val alive = if (ok) "да" else "нет"
                "#${grant.id} ${grant.displayName} — ${grant.uri} — доступен: $alive"
            }
            ToolResult(true, lines.joinToString("\n"))
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
