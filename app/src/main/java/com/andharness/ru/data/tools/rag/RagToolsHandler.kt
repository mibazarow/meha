package com.andharness.ru.data.tools.rag

import android.content.Context
import com.andharness.ru.data.tools.effectiveToolRoot
import com.andharness.ru.data.tools.formatBytes
import com.andharness.ru.data.tools.formatTimestamp
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.rag.RagIndexReport
import com.andharness.ru.domain.rag.RagQueryResult
import com.andharness.ru.domain.rag.RagRepository
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Local RAG tools (update.md idea #4): `rag_index` builds/refreshes a chunk index over local files,
 * `rag_query` answers questions from that index with citations (`sourcePath` + `chunkIndex`), so the
 * agent does not have to re-read whole documents on every turn.
 *
 * Paths are resolved against [Context.effectiveToolRoot] exactly like the file/document tools.
 * Everything is honest: an unchanged source is skipped, a failed file is listed with its reason, and
 * an empty/zero-match query says so instead of inventing an answer.
 */
class RagToolsHandler(
    private val context: Context,
    private val ragRepository: RagRepository
) {

    fun handlers(): List<ToolHandler> = listOf(ragIndex(), ragQuery())

    private fun ragIndex(): ToolHandler = object : ToolHandler {
        override val name: String = "rag_index"

        override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val rawPath = arguments.str("path")
                ?: return@withContext ToolResult(false, "Не указан path.")
            val recursive = arguments.bool("recursive")
            val force = arguments.bool("force")
            val report = try {
                ragRepository.index(context.effectiveToolRoot(), rawPath, recursive, force)
            } catch (t: Throwable) {
                return@withContext ToolResult(false, "Не удалось проиндексировать: ${t.message}")
            }
            ToolResult(report.filesIndexed + report.filesSkipped > 0 || report.errors.isEmpty(), format(report))
        }
    }

    private fun ragQuery(): ToolHandler = object : ToolHandler {
        override val name: String = "rag_query"

        override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val query = arguments.str("query")
                ?: return@withContext ToolResult(false, "Не указан query.")
            val limit = arguments.str("limit")?.toIntOrNull()?.coerceIn(1, 50) ?: DEFAULT_LIMIT
            val pathFilter = arguments.str("path")
            val result = try {
                ragRepository.query(query, limit, pathFilter)
            } catch (t: Throwable) {
                return@withContext ToolResult(false, "Не удалось выполнить поиск: ${t.message}")
            }
            ToolResult(result.hits.isNotEmpty(), format(result))
        }
    }

    private fun format(report: RagIndexReport): String = buildString {
        append("Файлов проиндексировано: ${report.filesIndexed}")
        append("\nПропущено без изменений: ${report.filesSkipped}")
        append("\nОшибок: ${report.filesFailed}")
        append("\nФрагментов сохранено: ${report.chunksStored}")
        if (report.truncatedFiles.isNotEmpty()) {
            append("\nОбрезаны (текст больше лимита): ${report.truncatedFiles.joinToString(", ")}")
        }
        if (report.errors.isNotEmpty()) {
            append("\nОшибки:")
            report.errors.forEach { append("\n— $it") }
        }
        if (report.notes.isNotEmpty()) {
            report.notes.forEach { append("\n$it") }
        }
    }

    private fun format(result: RagQueryResult): String {
        if (result.hits.isEmpty()) {
            return buildString {
                append(result.note ?: "Совпадений не найдено.")
                append("\nПроверено фрагментов: ${result.searchedChunks}")
            }
        }
        return buildString {
            append("Найдено фрагментов: ${result.hits.size} (проверено ${result.searchedChunks})")
            if (result.note != null) append("\n${result.note}")
            result.hits.forEachIndexed { position, hit ->
                append("\n\n#${position + 1} · релевантность ${"%.2f".format(hit.score)}")
                append(
                    "\nИсточник: ${hit.sourcePath} (${hit.sourceType}, фрагмент ${hit.chunkIndex}, " +
                        "${formatBytes(hit.sizeBytes)}, файл изменён ${formatTimestamp(hit.updatedAt)})"
                )
                append("\n${hit.text}")
            }
        }
    }

    private fun JsonObject.bool(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.content?.trim()?.toBooleanStrictOrNull() ?: false

    private companion object {
        const val DEFAULT_LIMIT = 8
    }
}
