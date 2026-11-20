package com.andharness.ru.data.tools.files

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** `files_grep`: greps text files under a directory for a query, with bounded walk and output. */
internal class FilesGrepToolHandler(private val io: FileToolIo) : ToolHandler {
    override val name = "files_grep"
    override suspend fun execute(arguments: JsonObject): ToolResult {
        return try {
        val query = (arguments["query"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (query.isEmpty()) return ToolResult(false, "Укажите query.")
        val base = io.root()
        val sub = (arguments["path"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val start = if (sub.isEmpty()) base else File(io.path(arguments, "path"))
        if (!start.exists()) return ToolResult(false, "Не найдено: «${start.path}».")
        // The base must be a directory: grepping a single file is a caller error, not a
        // silently-empty result.
        if (!start.isDirectory) return ToolResult(false, "Не директория: «${start.path}».")

        // Extension filter: a bare extension ("txt" / ".txt"), case-insensitive.
        val ext = (arguments["ext"] as? JsonPrimitive)?.content?.trim().orEmpty()
            .trimStart('.').lowercase()
        val limit = ((arguments["limit"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: MAX_SEARCH_RESULTS).coerceAtLeast(1)
        val maxFileBytes =
            ((arguments["max_file_bytes"] as? JsonPrimitive)?.content?.toLongOrNull()
                ?: DEFAULT_GREP_MAX_FILE_BYTES).coerceAtLeast(1L)

        val hits = ArrayList<String>()
        var scanned = 0
        var walkTruncated = false
        var resultsTruncated = false
        // Kotlin stdlib File.walkTopDown — a lazily-evaluated depth-first sequence over the
        // file tree: https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.io/java.io.-file/
        // The walk is bounded manually (instead of `take`) so hitting the entry cap is
        // detected and reported rather than silently cutting the traversal short (A12).
        for (f in start.walkTopDown()) {
            if (scanned >= MAX_SEARCH_ENTRIES) {
                walkTruncated = true
                break
            }
            scanned++
            // Only regular files within the size budget are considered; oversized files and
            // non-matching extensions are skipped without being read.
            if (!f.isFile) continue
            if (f.length() > maxFileBytes) continue
            if (ext.isNotEmpty() && !f.name.lowercase().endsWith(".$ext")) continue
            if (isBinaryFile(f)) continue

            // Read line by line (UTF-8; the decoder replaces malformed input instead of
            // throwing), stopping as soon as the requested number of hits is collected.
            val reader = f.inputStream().bufferedReader(Charsets.UTF_8)
            try {
                var lineNo = 0
                while (hits.size < limit) {
                    val line = reader.readLine() ?: break
                    lineNo++
                    if (line.contains(query, ignoreCase = true)) {
                        hits.add("${f.relativeToOrSelf(base).path}:$lineNo: $line")
                    }
                }
            } finally {
                reader.close()
            }
            if (hits.size >= limit) {
                resultsTruncated = true
                break
            }
        }
        val summary = "Найдено: ${hits.size} (показано), просканировано: $scanned. " +
            "Обход усечён: ${if (walkTruncated) "да" else "нет"}, " +
            "результаты усечены: ${if (resultsTruncated) "да" else "нет"}."
        if (hits.isEmpty()) {
            ToolResult(true, "$summary\nНичего не найдено по «$query».")
        } else {
            ToolResult(true, "$summary\n" + hits.joinToString("\n"))
        }
    } catch (e: Exception) {
        ToolResult(false, "Ошибка: ${e.message}")
    }
    }

    /**
     * Binary detection for files_grep: a NUL byte in the first [BINARY_PROBE_BYTES] bytes marks a
     * non-text file, which is skipped instead of being garbage-decoded into match lines.
     */
    private fun isBinaryFile(file: File): Boolean {
        val probe = ByteArray(BINARY_PROBE_BYTES)
        var read = 0
        file.inputStream().use { ins ->
            while (read < probe.size) {
                val n = ins.read(probe, read, probe.size - read)
                if (n < 0) break
                read += n
            }
        }
        for (i in 0 until read) {
            if (probe[i] == 0.toByte()) return true
        }
        return false
    }

    private companion object {
        private const val MAX_SEARCH_ENTRIES = 20_000
        private const val MAX_SEARCH_RESULTS = 200
        // files_grep: files larger than this are skipped (default, and the value used when the
        // caller does not pass max_file_bytes).
        private const val DEFAULT_GREP_MAX_FILE_BYTES = 1_048_576L
        private const val BINARY_PROBE_BYTES = 8 * 1024
    }
}
