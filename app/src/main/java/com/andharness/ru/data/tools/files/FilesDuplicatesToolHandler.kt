package com.andharness.ru.data.tools.files

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.common.Hashing
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** `files_duplicates`: finds duplicate files by size bucket + streaming SHA-256. */
internal class FilesDuplicatesToolHandler(private val io: FileToolIo) : ToolHandler {
    override val name = "files_duplicates"
    override suspend fun execute(arguments: JsonObject): ToolResult {
        return try {
            val base = io.root()
            val sub = (arguments["path"] as? JsonPrimitive)?.content?.trim().orEmpty()
            // Empty path means the storage root itself. A non-empty one goes through
            // path() (shell/control-char guard plus per-root containment), which rejects
            // `..` and any canonical escape from every allowed root (A1). The resolved
            // directory must exist and be a directory.
            val start =
                if (sub.isEmpty()) base else File(io.path(arguments, "path"))
            if (!start.exists()) return ToolResult(false, "Не найдено: «${start.path}».")
            if (!start.isDirectory) return ToolResult(false, "Не директория: «${start.path}».")

            val minSize = ((arguments["min_size"] as? JsonPrimitive)?.content?.toLongOrNull()
                ?: DEFAULT_DUPLICATES_MIN_SIZE)
                .coerceAtLeast(0L)
            val limit = ((arguments["limit"] as? JsonPrimitive)?.content?.toIntOrNull()
                ?: DEFAULT_DUPLICATES_LIMIT)
                .coerceAtLeast(1)
                .coerceAtMost(MAX_DUPLICATES_LIMIT)

            // Cheap first pass: bucket regular files by exact byte size. A size bucket with a
            // single file cannot contain duplicates, so its content is never hashed — the
            // expensive SHA-256 pass below runs only for files that share a size.
            val bySize = HashMap<Long, MutableList<File>>()
            var scanned = 0
            var walkTruncated = false
            // Kotlin stdlib File.walkTopDown — a lazily-evaluated depth-first sequence over
            // the file tree: https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.io/java.io.-file/
            // The walk is bounded manually (instead of `take`) so hitting the entry cap is
            // detected and reported rather than silently cutting the traversal short (A12).
            for (f in start.walkTopDown()) {
                if (scanned >= MAX_SEARCH_ENTRIES) {
                    walkTruncated = true
                    break
                }
                scanned++
                if (!f.isFile) continue
                val len = f.length()
                if (len < minSize) continue
                bySize.getOrPut(len) { mutableListOf() }.add(f)
            }

            val groups = ArrayList<DuplicateGroup>()
            var groupsFound = 0
            var wastedBytes = 0L
            var hashed = 0
            val unreadable = ArrayList<String>()
            // Biggest files first, so the most valuable duplicate groups are listed first.
            for ((size, sameSize) in bySize.entries.sortedByDescending { it.key }) {
                if (sameSize.size < 2) continue
                val byDigest = HashMap<String, MutableList<File>>()
                for (file in sameSize) {
                    hashed++
                    val digest = try {
                        sha256Hex(file)
                    } catch (e: Exception) {
                        // Honest accounting: an unreadable file is reported, never silently
                        // dropped and never misreported as a non-duplicate.
                        unreadable.add(file.absolutePath)
                        continue
                    }
                    byDigest.getOrPut(digest) { mutableListOf() }.add(file)
                }
                for ((digest, duplicates) in byDigest) {
                    if (duplicates.size < 2) continue
                    groupsFound++
                    // Every copy after the first is the reclaimable redundancy.
                    wastedBytes += size * (duplicates.size - 1)
                    if (groups.size < limit) {
                        groups.add(
                            DuplicateGroup(
                                size = size,
                                digest = digest,
                                paths = duplicates.map {
                                    it.relativeToOrSelf(base).path.ifEmpty { it.absolutePath }
                                },
                            )
                        )
                    }
                }
            }

            val outputTruncated = groupsFound > groups.size
            val unreadableNote =
                if (unreadable.isEmpty()) "." else ", не удалось прочитать: ${unreadable.size}."
            val summary = "Групп дубликатов: $groupsFound, показано: ${groups.size}, " +
                "просканировано: $scanned, проверено по хешу: $hashed, " +
                "лишних байт: $wastedBytes. Обход усечён: ${if (walkTruncated) "да" else "нет"}, " +
                "вывод усечён: ${if (outputTruncated) "да" else "нет"}$unreadableNote"
            if (groups.isEmpty()) {
                return ToolResult(true, "$summary\nДубликаты не найдены.")
            }
            val body = groups.joinToString("\n") { group ->
                "SHA-256 ${group.digest}, ${group.size} байт, копий ${group.paths.size}:\n" +
                    group.paths.joinToString("\n") { "  $it" }
            }
            ToolResult(true, "$summary\n$body")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }

    /**
     * Streaming SHA-256 of [file]: the shared [Hashing.sha256Hex] helper is fed the file input
     * stream, so an arbitrarily large file is never loaded into memory. Hex output is lowercase,
     * matching files_hash.
     */
    private fun sha256Hex(file: File): String =
        file.inputStream().use { ins -> Hashing.sha256Hex(ins) }

    /** One duplicate group: the shared SHA-256, the common byte size, and the member paths. */
    private data class DuplicateGroup(val size: Long, val digest: String, val paths: List<String>)

    private companion object {
        private const val MAX_SEARCH_ENTRIES = 20_000
        // files_duplicates: minimum file size considered (default) and the output bound for the
        // number of duplicate groups listed.
        private const val DEFAULT_DUPLICATES_MIN_SIZE = 1L
        private const val DEFAULT_DUPLICATES_LIMIT = 50
        private const val MAX_DUPLICATES_LIMIT = 1000
    }
}
