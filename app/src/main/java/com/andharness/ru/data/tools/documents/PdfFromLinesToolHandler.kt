package com.andharness.ru.data.tools.documents

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.documents.MarkdownPdfParser
import com.andharness.ru.domain.documents.PdfOutputNames
import com.andharness.ru.domain.documents.PdfTextLayout
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

    /** Shared implementation of `pdf_create` and `pdf_from_markdown`. */
internal class PdfFromLinesToolHandler(
    private val toolName: String,
    private val markdown: Boolean,
    private val io: DocumentToolIo,
    private val writer: PdfWriter,
) : ToolHandler {
    override val name = toolName

    override suspend fun execute(arguments: JsonObject): ToolResult {
        var createdFile: File? = null
        var backupOwned = false
        return try {
            val text = (arguments["text"] as? JsonPrimitive)?.content
            if (text.isNullOrBlank()) {
                return ToolResult(false, "Текст не указан или пуст (text).")
            }
            val title = io.stringArgument(arguments, "title")
            val lines = if (markdown) {
                MarkdownPdfParser.parse(text)
            } else {
                PdfTextLayout.plainLines(text)
            }
            PdfTextLayout.blankError(lines)?.let { return ToolResult(false, it) }

            val overwrite = (arguments["overwrite"] as? JsonPrimitive)
                ?.content?.toBooleanStrictOrNull() ?: false
            val rawPath = io.stringArgument(arguments, "path")
            val rawName = io.stringArgument(arguments, "file_name")
            val target = if (rawPath != null) {
                PdfOutputNames.withPdfExtension(rawPath)
            } else {
                val derived = rawName
                    ?.let { PdfOutputNames.slug(it) }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { PdfOutputNames.withPdfExtension(it) }
                    ?: PdfOutputNames.defaultFileName(
                        title = title,
                        firstLine = lines.firstOrNull { !it.isBlank }?.plainText,
                        nowMillis = System.currentTimeMillis(),
                    )
                "$DEFAULT_PDF_DIR/$derived"
            }
            val file = try {
                io.resolve(target)
            } catch (e: IllegalArgumentException) {
                return ToolResult(false, e.message ?: "Некорректный путь: «$target».")
            }
            createdFile = file
            if (file.isDirectory) {
                return ToolResult(false, "Путь указывает на каталог: ${file.absolutePath}")
            }
            // Same no-clobber rule as doc_create: an existing target is refused unless asked.
            if (file.exists() && !overwrite) {
                return ToolResult(
                    false,
                    "Файл уже существует: «${file.absolutePath}». Перезапись отключена " +
                        "(overwrite=false)."
                )
            }

            val (bytes, expectedPages) = try {
                writer.buildPdfFromLines(lines, title)
            } catch (e: Exception) {
                return ToolResult(false, "Не удалось сформировать PDF: ${e.message}")
            }
            if (bytes.isEmpty()) {
                return ToolResult(false, "Сформированный PDF пуст.")
            }

            val writeError = io.atomicWrite(file, bytes)
            if (writeError != null) {
                return ToolResult(false, writeError)
            }
            // [atomicWrite] clears any pre-existing backup before renaming, so a `.bak` seen
            // here is the one this call just made and is ours to clean up.
            backupOwned = io.backupFile(file).isFile

            // Post-verify: the file must exist, be non-empty, carry the PDF signature and have
            // exactly the page count the layout produced. On mismatch the previous version is
            // restored (or the new file removed) instead of reporting a false success.
            val verifyError = writer.verifyPdfFile(file, expectedPages)
            if (verifyError != null) {
                val restored = io.restoreBackup(file)
                if (!restored) runCatching { file.delete() }
                backupOwned = false
                val suffix = if (restored) {
                    " Исходный файл восстановлен из резервной копии."
                } else {
                    ""
                }
                return ToolResult(false, "Проверка после записи не пройдена: $verifyError$suffix")
            }

            val backupNote = io.cleanupBackup(file)
            backupOwned = false
            ToolResult(
                true,
                "Создан PDF: ${file.absolutePath}\n" +
                    "Страниц: $expectedPages\n" +
                    "Размер: ${DocumentSupport.formatBytes(file.length())}" +
                    if (backupNote != null) "\n$backupNote" else ""
            )
        } catch (e: Exception) {
            val restored = if (backupOwned && createdFile != null) {
                io.restoreBackup(createdFile!!)
            } else {
                false
            }
            val suffix = if (restored) " Исходный файл восстановлен из резервной копии." else ""
            ToolResult(false, "Ошибка при создании PDF: ${e.message}$suffix")
        }
    }

    private companion object {
        /** Default directory (relative to the tool root) for `pdf_create` / `pdf_from_markdown`. */
        const val DEFAULT_PDF_DIR = "Documents/Meha"
    }
}
