package com.andharness.ru.data.tools.documents

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

    /**
     * Copies a 1-based page range (`N` or `A-B`) out of `source` into a new file. The range is
     * validated against the source page count, the requested pages are imported with PdfBox
     * `PDDocument.importPage`, and the result is re-opened to prove it has exactly the requested
     * number of pages. The source document is only ever read.
     */
internal class PdfSplitToolHandler(
    private val io: DocumentToolIo,
) : ToolHandler {
    override val name = "pdf_split"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        return try {
            val rawSource = (arguments["source"] as? JsonPrimitive)?.content?.trim()
            if (rawSource.isNullOrEmpty()) {
                return ToolResult(false, "Путь к исходному PDF не указан (source).")
            }
            val rawPages = (arguments["pages"] as? JsonPrimitive)?.content?.trim()
            if (rawPages.isNullOrEmpty()) {
                return ToolResult(
                    false,
                    "Диапазон страниц не указан (pages), например «1-3» или «5»."
                )
            }
            val rawDestination = (arguments["destination"] as? JsonPrimitive)?.content?.trim()
            if (rawDestination.isNullOrEmpty()) {
                return ToolResult(false, "Путь к создаваемому файлу не указан (destination).")
            }
            val overwrite = io.booleanArgument(arguments, "overwrite")

            val source = when (val resolved = io.resolvePdfSource(rawSource)) {
                is PdfSource.Failure -> return ToolResult(false, resolved.message)
                is PdfSource.Ok -> resolved
            }
            val range = io.parsePageRange(rawPages, source.pages)
                ?: return ToolResult(
                    false,
                    "Некорректный диапазон страниц «$rawPages»: в документе ${source.pages} стр. " +
                        "Укажите диапазон вида «1-3» или одну страницу «5»."
                )

            val file = when (val resolved = io.resolveDestination(rawDestination, overwrite)) {
                is Destination.Failure -> return ToolResult(false, resolved.message)
                is Destination.Ok -> resolved.file
            }
            if (source.file.canonicalFile == file.canonicalFile) {
                return ToolResult(
                    false,
                    "Файл назначения совпадает с исходным PDF: ${file.absolutePath}"
                )
            }

            val expectedPages = range.last - range.first + 1
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp-${System.nanoTime()}")
            try {
                PDDocument.load(source.file).use { document ->
                    PDDocument().use { target ->
                        for (pageNumber in range) {
                            val page = document.getPage(pageNumber - 1)
                            val imported = target.importPage(page)
                            // importPage warns when the source page only inherits /Resources;
                            // copying them explicitly keeps the extracted pages renderable.
                            val resources = page.resources
                            if (resources != null) imported.resources = resources
                        }
                        target.save(temp)
                    }
                }
            } catch (e: Exception) {
                temp.delete()
                return ToolResult(false, "Не удалось извлечь страницы: ${e.message}")
            }

            // Post-verify: re-open the extracted file with PdfBox and prove the page count.
            val actualPages = try {
                PDDocument.load(temp).use { it.numberOfPages }
            } catch (e: Exception) {
                temp.delete()
                return ToolResult(
                    false,
                    "Не удалось открыть созданный PDF для проверки: ${e.message}"
                )
            }
            if (actualPages != expectedPages) {
                temp.delete()
                return ToolResult(
                    false,
                    "Проверка после записи не пройдена: страниц $actualPages, ожидалось " +
                        "$expectedPages. Файл не создан, исходный PDF не изменён."
                )
            }

            val commitError = io.commitTemp(temp, file)
            if (commitError != null) return ToolResult(false, commitError)
            val backupNote = io.cleanupBackup(file)
            ToolResult(
                true,
                "Создан PDF: ${file.absolutePath}\n" +
                    "Страниц: $expectedPages (диапазон ${range.first}-${range.last} из " +
                    "${source.pages})\n" +
                    "Размер: ${DocumentSupport.formatBytes(file.length())}" +
                    if (backupNote != null) "\n$backupNote" else ""
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка при извлечении страниц: ${e.message}")
        }
    }
}
