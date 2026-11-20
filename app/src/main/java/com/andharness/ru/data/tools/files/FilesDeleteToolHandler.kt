package com.andharness.ru.data.tools.files

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** `files_delete`: deletes one path, or a comma/semicolon-separated list of paths. */
internal class FilesDeleteToolHandler(private val io: FileToolIo) : ToolHandler {
    override val name = "files_delete"
    override suspend fun execute(arguments: JsonObject): ToolResult = try {
        // `paths` (plural) is the documented multi-path form; `path` is kept for backwards
        // compatibility, and either one may carry a comma/semicolon-separated list. A
        // single path keeps exactly the old single-path behaviour and messages.
        val raw = (arguments["paths"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: (arguments["path"] as? JsonPrimitive)?.content
        if (raw.isNullOrBlank()) {
            ToolResult(false, "Отсутствует обязательный аргумент: path (или paths).")
        } else {
            val targets = raw.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
            when {
                targets.isEmpty() -> ToolResult(false, "Не указан ни один путь для удаления.")
                targets.size == 1 -> deleteOne(targets[0])
                else -> deleteMany(targets)
            }
        }
    } catch (e: Exception) {
        ToolResult(false, "Ошибка: ${e.message}")
    }

    /**
     * Deletes ONE path and returns the exact single-path result contract of `files_delete`:
     * an existing directory is removed recursively (and success is reported only when it is gone).
     */
    private fun deleteOne(raw: String): ToolResult = try {
        val file = File(io.resolvePath(raw))
        if (!file.exists()) {
            ToolResult(false, "Нечего удалять: «${file.absolutePath}» не существует.")
        } else {
            // Deleting a directory removes its contents too. files_delete is already a
            // user-confirmed, dangerous action, and both users and the model treat "удали папку" as
            // removing the whole tree. Always deleting recursively avoids the failure mode where a
            // non-empty directory could not be removed without an explicit recursive=true (which the
            // model often forgot and then misreported). Success is reported only when the path is gone.
            val ok = file.deleteRecursively()
            val gone = !file.exists()
            if (ok && gone) ToolResult(true, "Удалено: ${file.absolutePath}")
            else ToolResult(false, "Не удалось удалить: ${file.absolutePath}")
        }
    } catch (e: Exception) {
        ToolResult(false, "Ошибка: ${e.message}")
    }

    /**
     * Deletes several paths in one call and reports each one honestly. Any path that fails (or is
     * invalid) does not stop the others; the overall result is successful only when every path was
     * actually removed, so a partial failure is never reported as success.
     */
    private fun deleteMany(targets: List<String>): ToolResult {
        val results = targets.map { it to deleteOne(it) }
        val deleted = results.count { it.second.success }
        val header = "Обработано путей: ${results.size}, удалено: $deleted, " +
            "не удалось: ${results.size - deleted}."
        val lines = results.joinToString("\n") { (_, result) -> "• ${result.output}" }
        return ToolResult(deleted == results.size, "$header\n$lines")
    }
}
