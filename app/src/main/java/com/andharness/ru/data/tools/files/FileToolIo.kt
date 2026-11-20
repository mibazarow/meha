package com.andharness.ru.data.tools.files

import android.content.Context
import android.webkit.MimeTypeMap
import com.andharness.ru.data.tools.allowedToolRoots
import com.andharness.ru.data.tools.effectiveToolRoot
import com.andharness.ru.data.tools.resolveSafePath
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Shared path/IO helpers for the files_* tools.
 *
 * The base directory resolves via [effectiveToolRoot]: the real shared-storage root
 * (/storage/emulated/0) when the MANAGE_EXTERNAL_STORAGE special permission is granted, otherwise
 * the app-private dir. Path arguments are containment-checked against every root from
 * [allowedToolRoots] (the primary root plus removable volumes on API 30+), and resolve to canonical
 * absolute paths.
 *
 * java.io.File reference:
 * - https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/io/File.html
 *   (listFiles, mkdirs, delete, deleteRecursively, renameTo; readText/writeText/copyTo
 *   are Kotlin stdlib extensions over File).
 */
internal class FileToolIo(private val context: Context) {

    /** The primary tool root. */
    fun root(): File = context.effectiveToolRoot()

    /** Every root the file tools may address: the primary root plus removable volumes (API 30+). */
    fun roots(): List<File> = context.allowedToolRoots()

    /**
     * Validates and resolves a required path argument against every allowed root (primary storage
     * plus removable volumes on API 30+). Returns the canonical ABSOLUTE path, so an absolute path
     * on a removable volume stays addressable while anything outside the allowed roots is rejected.
     * Callers must build the [File] from the returned string directly (`File(path(...))`):
     * `java.io.File(parent, child)` does NOT drop the parent for an absolute child — it concatenates.
     */
    fun path(arguments: JsonObject, key: String): String {
        val s = (arguments[key] as? JsonPrimitive)?.content
        if (s.isNullOrEmpty()) {
            throw IllegalArgumentException("Отсутствует обязательный аргумент: $key")
        }
        return resolvePath(s)
    }

    /** Validates and resolves one raw path string the same way [path] resolves an argument value. */
    fun resolvePath(raw: String): String {
        val validated = requireSafePath(raw)
        // Containment gate: the resolved path must stay inside one of the allowed roots.
        return resolveSafePath(roots(), validated).absolutePath
    }

    /**
     * MIME type from the file extension, falling back to [GENERIC_MIME] (a literal star-slash-star)
     * for unknown extensions.
     * MimeTypeMap reference:
     * https://developer.android.com/reference/android/webkit/MimeTypeMap#getMimeTypeFromExtension(java.lang.String)
     */
    fun mimeTypeOf(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: GENERIC_MIME

    /**
     * Recursive count of regular files under [dir], used to post-verify a directory copy
     * (`File.walkTopDown` — https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.io/).
     */
    fun countFiles(dir: File): Int = dir.walkTopDown().count { it.isFile }

    /**
     * Rejects paths that are clearly not intended file paths: shell metacharacters (a real, observed
     * failure mode where the model pasted shell syntax like `*.* 2>/dev/null; ls -la ...` into the
     * `path` argument), control characters, backslash separators, and absurdly long single path
     * components. Validates so a garbage or malicious argument returns a clear error instead of being
     * acted on. Space and Cyrillic are valid in filenames and are allowed.
     */
    private fun requireSafePath(raw: String): String {
        val invalid = raw.any { it in INVALID_PATH_CHARS }
        if (invalid) {
            throw IllegalArgumentException("Путь содержит недопустимые символы: «$raw». Укажите обычный относительный путь, например Documents/notes.txt")
        }
        if (raw.split('/').any { it.length > MAX_PATH_COMPONENT_LENGTH }) {
            throw IllegalArgumentException("Имя файла/папки слишком длинное (компонент пути больше $MAX_PATH_COMPONENT_LENGTH символов).")
        }
        return raw
    }

    internal companion object {
        const val GENERIC_MIME = "*/*"
        private const val MAX_PATH_COMPONENT_LENGTH = 200
        private val INVALID_PATH_CHARS = setOf(
            ';', '&', '|', '>', '<', '`', '$', '!', '(', ')', '[', ']', '{', '}', '\'', '"',
            '*', '?', '\\', '\n', '\r', '\t'
        )

        // BUG-050: max bytes of a single `content` argument accepted by files_write/files_append
        // (64 КБ). A single tool-call argument this large exceeds the LLM output-token budget: the
        // provider (OpenAiLlmClient.buildRequest sets no max_tokens) truncates the streamed JSON
        // mid-argument into malformed JSON, which fails to parse and makes the agent thrash retrying.
        // So an over-size payload is rejected with an honest "split into parts" error instead of a
        // cryptic parse failure; the system prompt tells the agent to write large files in parts.
        const val MAX_SINGLE_WRITE_BYTES = 64 * 1024

        // Honest, actionable error returned when a single write/append payload exceeds
        // [MAX_SINGLE_WRITE_BYTES]. Kept in sync with the threshold above (compile-time constant).
        const val TOO_LARGE_ONE_CALL_MESSAGE =
            "Слишком большой объём за один вызов (максимум ${MAX_SINGLE_WRITE_BYTES / 1024} КБ). " +
                "Разбейте на части: сначала files_write (первая часть), затем несколько files_append (остальное)."
    }
}
