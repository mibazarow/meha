package com.andharness.ru.data.tools.documents

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.documents.DocumentFileNames
import com.andharness.ru.domain.documents.TemplateRenderer
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * `docx_replace`: replaces text occurrences inside the `word/document.xml` part of an existing DOCX.
 *
 * Two JSON shapes are accepted and may be combined:
 * - `replacements`: literal `old -> new` pairs (`{"Иван": "Пётр"}`),
 * - `template_values`: `key -> value` pairs substituted as `{{key}}` placeholders, matching the
 *   `pdf_from_template` `values` shape.
 *
 * The edit is a text-node transform ([DocumentWriter.replaceInTextNodes]) so all other package parts
 * survive byte-for-byte. Honest limit: a placeholder split across several `<w:r>`/`<w:t>` runs is not
 * matched (the transform works within one text node), and a `find` that is absent is reported, never
 * silently ignored. By default the file is edited in place with a `.bak` backup; an optional
 * `output_path` (or `file_name`, placed in `Documents/Meha`) writes a verified copy instead.
 *
 * Post-write verification re-reads the result through the same extraction path `doc_read` uses and
 * compares it with the text computed from the original XML — a mismatch restores the backup (or
 * removes a freshly created copy) and fails honestly.
 *
 * References:
 * - OOXML WordprocessingML (`w:t` runs) —
 *   https://learn.microsoft.com/en-us/office/open-xml/word/wordprocessingml
 * - `java.util.zip.ZipFile` — https://developer.android.com/reference/java/util/zip/ZipFile
 */
internal class DocxReplaceToolHandler(
    private val io: DocumentToolIo,
    private val inspector: DocumentInspector,
) : ToolHandler {
    override val name = "docx_replace"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        var source: File? = null
        var target: File? = null
        var targetExisted = false
        var backupOwned = false
        return try {
            val raw = io.stringArgument(arguments, "path")
                ?: return ToolResult(false, "Путь не указан.")
            val file = try {
                io.resolve(raw)
            } catch (e: IllegalArgumentException) {
                return ToolResult(false, e.message ?: "Некорректный путь: «$raw».")
            }
            source = file
            if (!file.isFile) {
                return ToolResult(false, "Файл не найден: ${file.absolutePath}")
            }
            if (DocumentFormatDetector.detect(file) != DocumentFormat.DOCX) {
                return ToolResult(false, "Файл не является DOCX: ${file.absolutePath}")
            }

            val replacements = LinkedHashMap<String, String>()
            io.stringArgument(arguments, "replacements")?.let { json ->
                try {
                    replacements.putAll(TemplateRenderer.parseReplacements(json, templateStyle = false))
                } catch (e: IllegalArgumentException) {
                    return ToolResult(false, "replacements: ${e.message}")
                }
            }
            io.stringArgument(arguments, "template_values")?.let { json ->
                try {
                    replacements.putAll(TemplateRenderer.parseReplacements(json, templateStyle = true))
                } catch (e: IllegalArgumentException) {
                    return ToolResult(false, "template_values: ${e.message}")
                }
            }
            if (replacements.isEmpty()) {
                return ToolResult(
                    false,
                    "Не указаны замены: нужен replacements (JSON old→new) или template_values (JSON ключ→значение)."
                )
            }

            val xml = DocumentSupport.readZipEntryText(file, "word/document.xml")
                ?: return ToolResult(false, "Внутренний документ word/document.xml не найден.")
            var updated = xml
            val applied = LinkedHashSet<String>()
            val notFound = LinkedHashSet<String>()
            for ((find, replace) in replacements) {
                if (find.isEmpty()) continue
                val next = DocumentWriter.replaceInTextNodes(updated, find, replace, listOf("t"))
                if (next == updated) notFound += find else applied += find
                updated = next
            }
            if (applied.isEmpty()) {
                return ToolResult(
                    false,
                    "Ни одна замена не найдена в документе: ${notFound.joinToString(", ") { "«$it»" }}."
                )
            }

            val rawTarget = resolveTargetRaw(arguments)
            val out: File = if (rawTarget == null) {
                file
            } else {
                when (val destination = io.resolveDestination(rawTarget, overwrite = overwrite(arguments))) {
                    is Destination.Failure -> return ToolResult(false, destination.message)
                    is Destination.Ok -> destination.file
                }
            }
            target = out
            targetExisted = out.exists()

            val bytes = DocumentSupport.rewriteZip(
                file,
                mapOf("word/document.xml" to updated.toByteArray(Charsets.UTF_8))
            ) ?: return ToolResult(false, "Не удалось перепаковать контейнер (${file.name}).")
            if (bytes.isEmpty()) {
                return ToolResult(false, "Изменённый документ получился пустым.")
            }

            val writeError = io.atomicWrite(out, bytes)
            if (writeError != null) {
                return ToolResult(false, writeError)
            }
            backupOwned = io.backupFile(out).isFile

            val verifyError = verifyDocx(out, updated)
            if (verifyError != null) {
                val restored = io.restoreBackup(out)
                if (!restored && !targetExisted) runCatching { out.delete() }
                backupOwned = false
                val suffix = if (restored) " Файл восстановлен из резервной копии." else ""
                return ToolResult(false, "Проверка после записи не пройдена: $verifyError$suffix")
            }

            val backupNote = io.cleanupBackup(out)
            backupOwned = false
            val inPlace = out == file
            val warning = if (notFound.isEmpty()) {
                ""
            } else {
                "\nНе найдено (без изменений): ${notFound.joinToString(", ") { "«$it»" }}"
            }
            ToolResult(
                true,
                (if (inPlace) "Изменён документ: ${out.absolutePath}" else "Создан документ: ${out.absolutePath}") +
                    "\nФормат: DOCX" +
                    "\nЗамен выполнено: ${applied.size} (${applied.joinToString(", ") { "«$it»" }})" +
                    warning +
                    "\nРазмер: ${DocumentSupport.formatBytes(out.length())}" +
                    if (backupNote != null) "\n$backupNote" else ""
            )
        } catch (e: Exception) {
            val restored = if (backupOwned && target != null) {
                io.restoreBackup(target!!)
            } else {
                false
            }
            if (!restored && !targetExisted) target?.let { runCatching { it.delete() } }
            val suffix = if (restored) " Файл восстановлен из резервной копии." else ""
            ToolResult(false, "Ошибка при замене в DOCX: ${e.message}$suffix")
        }
    }

    /**
     * Re-reads [file] and compares its extracted text with the text of [expectedXml]; returns an
     * honest error, or `null` when they match. A missing/short/damaged file also fails.
     */
    private fun verifyDocx(file: File, expectedXml: String): String? {
        if (!file.isFile) return "файл недоступен после записи."
        if (file.length() == 0L) return "файл пуст после записи."
        if (DocumentFormatDetector.detect(file) != DocumentFormat.DOCX) {
            return "формат файла после записи не DOCX."
        }
        val xml = DocumentSupport.readZipEntryText(file, "word/document.xml")
            ?: return "не удалось повторно прочитать word/document.xml."
        val expected = inspector.normalizedText(DocumentSupport.docxText(expectedXml))
        val actual = inspector.normalizedText(DocumentSupport.docxText(xml))
        return if (actual == expected) null else "текст после замены не совпадает с ожидаемым."
    }

    private fun overwrite(arguments: JsonObject): Boolean =
        (arguments["overwrite"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false

    /** Raw (unresolved) copy destination, or `null` for an in-place edit. */
    private fun resolveTargetRaw(arguments: JsonObject): String? {
        io.stringArgument(arguments, "output_path")?.let {
            return DocumentFileNames.withExtension(it, DOCX_SUFFIX)
        }
        io.stringArgument(arguments, "file_name")?.let { name ->
            val derived = DocumentFileNames.docxFromName(name)
            if (derived.isNotEmpty()) return "$DEFAULT_DOC_DIR/$derived"
        }
        return null
    }

    private companion object {
        const val DOCX_SUFFIX = ".docx"
        const val DEFAULT_DOC_DIR = "Documents/Meha"
    }
}
