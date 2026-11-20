package com.andharness.ru.data.tools.documents

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

    /**
     * Combines the PDFs listed in `sources`, in that order, into a single new file. Every source is
     * opened with PdfBox first, an existing destination is refused unless `overwrite=true`, and the
     * merged file is re-opened after the write to prove its page count equals the sum of the source
     * page counts. The merge runs into a temp file in the destination directory, so a mismatch or a
     * failure never leaves a partial result behind.
     */
internal class PdfMergeToolHandler(
    private val io: DocumentToolIo,
) : ToolHandler {
    override val name = "pdf_merge"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        return try {
            val rawSources = (arguments["sources"] as? JsonPrimitive)?.content
                ?: return ToolResult(false, "Не указаны исходные PDF (sources).")
            val rawDestination = (arguments["destination"] as? JsonPrimitive)?.content?.trim()
            if (rawDestination.isNullOrEmpty()) {
                return ToolResult(false, "Путь к создаваемому файлу не указан (destination).")
            }
            val overwrite = io.booleanArgument(arguments, "overwrite")

            val sourcePaths = rawSources.split(',', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (sourcePaths.isEmpty()) {
                return ToolResult(false, "Не указаны исходные PDF (sources).")
            }

            val sources = mutableListOf<File>()
            var expectedPages = 0
            for (raw in sourcePaths) {
                when (val resolved = io.resolvePdfSource(raw)) {
                    is PdfSource.Failure -> return ToolResult(false, resolved.message)
                    is PdfSource.Ok -> {
                        sources += resolved.file
                        expectedPages += resolved.pages
                    }
                }
            }

            val file = when (val resolved = io.resolveDestination(rawDestination, overwrite)) {
                is Destination.Failure -> return ToolResult(false, resolved.message)
                is Destination.Ok -> resolved.file
            }
            if (sources.any { it.canonicalFile == file.canonicalFile }) {
                return ToolResult(
                    false,
                    "Файл назначения совпадает с одним из исходных: ${file.absolutePath}"
                )
            }

            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp-${System.nanoTime()}")
            try {
                val merger = PDFMergerUtility()
                for (source in sources) merger.addSource(source)
                merger.destinationFileName = temp.absolutePath
                merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
            } catch (e: Exception) {
                temp.delete()
                return ToolResult(false, "Не удалось объединить PDF: ${e.message}")
            }

            // Post-verify: re-open the merged file with PdfBox and prove that no page was lost.
            val actualPages = try {
                PDDocument.load(temp).use { it.numberOfPages }
            } catch (e: Exception) {
                temp.delete()
                return ToolResult(
                    false,
                    "Не удалось открыть объединённый PDF для проверки: ${e.message}"
                )
            }
            if (actualPages != expectedPages) {
                temp.delete()
                return ToolResult(
                    false,
                    "Проверка после записи не пройдена: страниц $actualPages, ожидалось " +
                        "$expectedPages. Файл не создан, исходные PDF не изменены."
                )
            }

            val commitError = io.commitTemp(temp, file)
            if (commitError != null) return ToolResult(false, commitError)
            val backupNote = io.cleanupBackup(file)
            ToolResult(
                true,
                "Объединены PDF: ${sources.size} → ${file.absolutePath}\n" +
                    "Страниц: $expectedPages\n" +
                    "Размер: ${DocumentSupport.formatBytes(file.length())}" +
                    if (backupNote != null) "\n$backupNote" else ""
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка при объединении PDF: ${e.message}")
        }
    }
}
