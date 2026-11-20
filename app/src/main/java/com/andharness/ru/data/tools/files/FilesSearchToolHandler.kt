package com.andharness.ru.data.tools.files

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** `files_search`: finds entries whose name contains a query, with bounded walk and output. */
internal class FilesSearchToolHandler(private val io: FileToolIo) : ToolHandler {
    override val name = "files_search"
    override suspend fun execute(arguments: JsonObject): ToolResult {
        return try {
        val query = (arguments["query"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (query.isEmpty()) return ToolResult(false, "Укажите query.")
        val base = io.root()
        val sub = (arguments["path"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val start = if (sub.isEmpty()) base else File(io.path(arguments, "path"))
        if (!start.exists()) return ToolResult(false, "Не найдено: «${start.path}».")
        val matches = ArrayList<String>()
        var scanned = 0
        var foundTotal = 0
        var walkTruncated = false
        // Kotlin stdlib File.walkTopDown — a lazily-evaluated depth-first sequence over the
        // file tree: https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.io/java.io.-file/
        // The walk is bounded manually (instead of `take`) so hitting the entry cap is
        // detected and reported rather than silently cutting the result set short (A12).
        for (f in start.walkTopDown()) {
            if (scanned >= MAX_SEARCH_ENTRIES) {
                walkTruncated = true
                break
            }
            scanned++
            if (f != start && f.name.contains(query, ignoreCase = true)) {
                foundTotal++
                if (matches.size < MAX_SEARCH_RESULTS) {
                    matches.add(f.relativeToOrSelf(base).path + if (f.isDirectory) "/" else "")
                }
            }
        }
        // foundTotal counts every match; matches holds only the first MAX_SEARCH_RESULTS.
        val resultsTruncated = foundTotal > matches.size
        val summary = "Найдено: ${matches.size} (показано), совпало: $foundTotal, " +
            "просканировано: $scanned. Обход усечён: ${if (walkTruncated) "да" else "нет"}, " +
            "результаты усечены: ${if (resultsTruncated) "да" else "нет"}."
        if (matches.isEmpty()) {
            ToolResult(true, "$summary\nНичего не найдено по «$query».")
        } else {
            ToolResult(true, "$summary\n" + matches.joinToString("\n"))
        }
    } catch (e: Exception) {
        ToolResult(false, "Ошибка: ${e.message}")
    }
    }

    private companion object {
        private const val MAX_SEARCH_ENTRIES = 20_000
        private const val MAX_SEARCH_RESULTS = 200
    }
}
