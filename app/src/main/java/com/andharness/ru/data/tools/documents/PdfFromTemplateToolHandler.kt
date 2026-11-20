package com.andharness.ru.data.tools.documents

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.documents.MarkdownPdfParser
import com.andharness.ru.domain.documents.PdfOutputNames
import com.andharness.ru.domain.documents.PdfTextLayout
import com.andharness.ru.domain.documents.TemplateRenderer
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Shared implementation of `pdf_from_template` and `pdf_from_template_file`: renders a
 * DOCUMENT FROM A TEMPLATE by substituting `{{key}}` placeholders with the caller's values, then
 * lays the result out as an A4 PDF through the same [MarkdownPdfParser] + [PdfWriter] path
 * `pdf_from_markdown` uses (so Markdown headings/lists/bold and Cyrillic are supported).
 *
 * The template is either the inline `template` string ([fromFile] = false) or the UTF-8 text of an
 * existing local `template_path` file ([fromFile] = true). Values come from the `values` JSON object;
 * numbers/booleans/arrays/objects are stringified by [TemplateRenderer].
 *
 * Honest behaviour: a placeholder whose key has no value is **left verbatim** and listed in the
 * success message as "no value" — it is never silently blanked. The written file is re-opened and
 * checked (exists, non-empty, `%PDF-` signature, page count) before success is reported, with the
 * usual atomic write and `.bak` restore.
 *
 * References:
 * - PdfBox-Android (`PDDocument`/`PDPage`/`PDPageContentStream`/`PDType0Font`) —
 *   https://github.com/TomRoush/PdfBox-Android
 * - `java.io.File` — https://developer.android.com/reference/java/io/File
 */
internal class PdfFromTemplateToolHandler(
    private val toolName: String,
    private val fromFile: Boolean,
    private val io: DocumentToolIo,
    private val writer: PdfWriter,
) : ToolHandler {
    override val name = toolName

    override suspend fun execute(arguments: JsonObject): ToolResult {
        var createdFile: File? = null
        var backupOwned = false
        return try {
            val template = if (fromFile) {
                val rawPath = io.stringArgument(arguments, "template_path")
                    ?: return ToolResult(false, "Не указан путь к файлу шаблона (template_path).")
                val templateFile = try {
                    io.resolve(rawPath)
                } catch (e: IllegalArgumentException) {
                    return ToolResult(false, e.message ?: "Некорректный путь: «$rawPath».")
                }
                if (!templateFile.isFile) {
                    return ToolResult(false, "Файл шаблона не найден: ${templateFile.absolutePath}")
                }
                DocumentSupport.readFileText(templateFile)
                    ?: return ToolResult(false, "Не удалось прочитать шаблон как текст: ${templateFile.absolutePath}")
            } else {
                (arguments["template"] as? JsonPrimitive)?.content
                    ?: return ToolResult(false, "Не указан шаблон (template).")
            }
            if (template.isBlank()) {
                return ToolResult(false, "Шаблон пуст.")
            }

            val valuesJson = (arguments["values"] as? JsonPrimitive)?.content
                ?: return ToolResult(false, "Не указаны значения (values) — JSON-объект ключ→значение.")
            val values = try {
                TemplateRenderer.parseValues(valuesJson)
            } catch (e: IllegalArgumentException) {
                return ToolResult(false, "values: ${e.message}")
            }

            val rendered = TemplateRenderer.render(template, values)
            if (rendered.text.isBlank()) {
                return ToolResult(false, "После подстановки текст пуст: нечего записывать в PDF.")
            }

            val lines = MarkdownPdfParser.parse(rendered.text)
            PdfTextLayout.blankError(lines)?.let { return ToolResult(false, it) }

            val title = io.stringArgument(arguments, "title")
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
            backupOwned = io.backupFile(file).isFile

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
            val warnings = if (rendered.missing.isEmpty()) {
                ""
            } else {
                "\nБез значения (оставлены как есть): " +
                    rendered.missing.joinToString(", ") { "{{$it}}" }
            }
            ToolResult(
                true,
                "Создан PDF из шаблона: ${file.absolutePath}\n" +
                    "Страниц: $expectedPages\n" +
                    "Подставлено значений: ${rendered.applied.size}" +
                    warnings +
                    "\nРазмер: ${DocumentSupport.formatBytes(file.length())}" +
                    if (backupNote != null) "\n$backupNote" else ""
            )
        } catch (e: Exception) {
            val restored = if (backupOwned && createdFile != null) {
                io.restoreBackup(createdFile!!)
            } else {
                false
            }
            val suffix = if (restored) " Исходный файл восстановлен из резервной копии." else ""
            ToolResult(false, "Ошибка при создании PDF из шаблона: ${e.message}$suffix")
        }
    }

    private companion object {
        /** Default directory (relative to the tool root), matching `pdf_create`/`pdf_from_markdown`. */
        const val DEFAULT_PDF_DIR = "Documents/Meha"
    }
}
