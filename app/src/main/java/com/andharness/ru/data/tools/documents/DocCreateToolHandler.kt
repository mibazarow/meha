package com.andharness.ru.data.tools.documents

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.Locale

internal class DocCreateToolHandler(
    private val io: DocumentToolIo,
    private val writer: PdfWriter,
    private val inspector: DocumentInspector,
) : ToolHandler {
    override val name = "doc_create"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        var createdFile: File? = null
        var backupOwned = false
        return try {
            val raw = (arguments["path"] as? JsonPrimitive)?.content?.trim()
            if (raw.isNullOrEmpty()) {
                return ToolResult(false, "Путь не указан.")
            }
            val file = try {
                io.resolve(raw)
            } catch (e: IllegalArgumentException) {
                return ToolResult(false, e.message ?: "Некорректный путь: «$raw».")
            }
            createdFile = file
            val content = (arguments["content"] as? JsonPrimitive)?.content
                ?: return ToolResult(false, "Содержимое документа не указано (content).")
            val requestedFormat = io.stringArgument(arguments, "format")
            val title = io.stringArgument(arguments, "title")
            val overwrite = (arguments["overwrite"] as? JsonPrimitive)
                ?.content?.toBooleanStrictOrNull() ?: false

            if (file.isDirectory) {
                return ToolResult(false, "Путь указывает на каталог: ${file.absolutePath}")
            }
            // No silent clobber: an existing target with overwrite=false is an honest error and
            // is left untouched.
            if (file.exists() && !overwrite) {
                return ToolResult(
                    false,
                    "Файл уже существует: «${file.absolutePath}». Перезапись отключена " +
                        "(overwrite=false)."
                )
            }

            val format = resolveCreationFormat(file, requestedFormat)
                ?: return ToolResult(
                    false,
                    "Не удалось определить формат: укажите параметр format или используйте " +
                        "известное расширение (PDF, DOCX, XLSX, ODT, ODS, RTF, CSV/TSV, " +
                        "TXT/MD/JSON/XML/HTML)."
                )

            when (format) {
                DocumentFormat.LEGACY_DOC, DocumentFormat.LEGACY_XLS, DocumentFormat.LEGACY_PPT ->
                    return ToolResult(
                        false,
                        "Старый бинарный формат (${format.name}, OLE2) создать нельзя. " +
                            "Используйте DOCX/XLSX/PPTX."
                    )
                DocumentFormat.BINARY_UNKNOWN, DocumentFormat.ZIP_UNKNOWN ->
                    return ToolResult(false, "Формат ${format.name} не может быть создан.")
                else -> Unit
            }

            val bytes = try {
                writer.buildDocument(format, content, title)
            } catch (e: Exception) {
                return ToolResult(
                    false,
                    "Не удалось сформировать документ (${format.name}): ${e.message}"
                )
            }
            if (bytes.isEmpty()) {
                return ToolResult(false, "Сформированный документ пуст.")
            }

            val writeError = io.atomicWrite(file, bytes)
            if (writeError != null) {
                return ToolResult(false, writeError)
            }
            // [atomicWrite] clears any pre-existing backup before renaming, so a `.bak` seen
            // here is the one this call just made and is ours to clean up.
            backupOwned = io.backupFile(file).isFile

            // Post-verify: re-open the created file through the SAME reader path as `doc_read`
            // and confirm the content/structure survived the write. A mismatch restores the
            // overwritten original (or deletes the new file) instead of reporting a success
            // that is not one.
            val verifyError = inspector.verifyCreated(file, format, content)
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
                "Создан документ: ${file.absolutePath}\n" +
                    "Формат: ${format.name}\n" +
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
            ToolResult(false, "Ошибка при создании документа: ${e.message}$suffix")
        }
    }

    /** Explicit `format` argument (name or extension) or the target file's extension → format. */
    fun resolveCreationFormat(file: File, explicit: String?): DocumentFormat? {
        val requested = explicit?.trim()?.removePrefix(".")?.lowercase(Locale.ROOT)
        if (!requested.isNullOrEmpty()) {
            return CREATION_FORMATS[requested]
        }
        return CREATION_FORMATS[file.extension.lowercase(Locale.ROOT)]
    }

    private companion object {
        /** Formats `doc_create` can produce, keyed by lowercase name/extension (with or without dot). */
        val CREATION_FORMATS = mapOf(
            "pdf" to DocumentFormat.PDF,
            "docx" to DocumentFormat.DOCX,
            "xlsx" to DocumentFormat.XLSX,
            "odt" to DocumentFormat.ODT,
            "ods" to DocumentFormat.ODS,
            "rtf" to DocumentFormat.RTF,
            "csv" to DocumentFormat.CSV,
            "tsv" to DocumentFormat.TSV,
            "txt" to DocumentFormat.TEXT,
            "text" to DocumentFormat.TEXT,
            "md" to DocumentFormat.MARKDOWN,
            "markdown" to DocumentFormat.MARKDOWN,
            "json" to DocumentFormat.JSON,
            "xml" to DocumentFormat.XML,
            "html" to DocumentFormat.HTML,
            "htm" to DocumentFormat.HTML,
            "doc" to DocumentFormat.LEGACY_DOC,
            "xls" to DocumentFormat.LEGACY_XLS,
            "ppt" to DocumentFormat.LEGACY_PPT,
            "pptx" to DocumentFormat.PPTX,
            "odp" to DocumentFormat.ODP,
        )
    }
}
