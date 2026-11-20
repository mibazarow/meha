package com.andharness.ru.data.tools.documents

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.documents.DocumentFileNames
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * `docx_append`: appends [DocumentWriter.appendDocxText] paragraphs to the end of an existing DOCX,
 * keeping every other package part intact. The new content goes just before the document-level
 * `<w:sectPr>` (which OOXML requires to stay last in `<w:body>`), so page setup is preserved.
 *
 * Honest limit: only plain paragraphs are added — Markdown/`**bold**`/heading syntax is kept as
 * literal text, no styling runs are created. By default the file is edited in place with a `.bak`
 * backup; an optional `output_path` (or `file_name`, placed in `Documents/Meha`) writes a verified
 * copy instead. Post-write verification re-reads the file through the `doc_read` path and compares it
 * with the text computed from the original XML, restoring the backup (or deleting a fresh copy) on a
 * mismatch.
 *
 * References:
 * - OOXML `CT_Body` (`<w:sectPr>` last) —
 *   https://learn.microsoft.com/en-us/office/open-xml/word/wordprocessingml
 * - `java.util.zip.ZipFile` — https://developer.android.com/reference/java/util/zip/ZipFile
 */
internal class DocxAppendToolHandler(
    private val io: DocumentToolIo,
    private val inspector: DocumentInspector,
) : ToolHandler {
    override val name = "docx_append"

    override suspend fun execute(arguments: JsonObject): ToolResult {
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
            if (!file.isFile) {
                return ToolResult(false, "Файл не найден: ${file.absolutePath}")
            }
            if (DocumentFormatDetector.detect(file) != DocumentFormat.DOCX) {
                return ToolResult(false, "Файл не является DOCX: ${file.absolutePath}")
            }
            val content = (arguments["content"] as? JsonPrimitive)?.content
                ?: return ToolResult(false, "Не указан текст для добавления (content).")
            if (content.isBlank()) {
                return ToolResult(false, "Текст для добавления пуст (content).")
            }

            val xml = DocumentSupport.readZipEntryText(file, "word/document.xml")
                ?: return ToolResult(false, "Внутренний документ word/document.xml не найден.")
            val updated = try {
                DocumentWriter.appendDocxText(xml, content)
            } catch (e: IllegalArgumentException) {
                return ToolResult(false, e.message ?: "Не удалось добавить абзацы.")
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
            ToolResult(
                true,
                (if (inPlace) "Изменён документ: ${out.absolutePath}" else "Создан документ: ${out.absolutePath}") +
                    "\nФормат: DOCX" +
                    "\nДобавлено абзацев: ${content.count { it == '\n' } + 1}" +
                    "\nПримечание: добавлен обычный текст без форматирования (Markdown-разметка не применяется)." +
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
            ToolResult(false, "Ошибка при добавлении в DOCX: ${e.message}$suffix")
        }
    }

    /** Re-reads [file] and returns an honest error unless its text matches [expectedXml]'s text. */
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
        return if (actual == expected) null else "текст после добавления не совпадает с ожидаемым."
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
