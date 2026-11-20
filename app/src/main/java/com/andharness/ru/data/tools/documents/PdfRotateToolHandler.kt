package com.andharness.ru.data.tools.documents

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.documents.PdfToolOptions
import com.andharness.ru.domain.tools.ToolHandler
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * `pdf_rotate`: rotates pages by 90/180/270 degrees, writing a NEW PDF. All pages are rotated
 * unless a 1-based `pages` range (`3` or `1-3`) is given. Rotation is added to each page's existing
 * `/Rotate` value and normalised into `0/90/180/270` (PDF `/Rotate` is clockwise). The source is
 * only read; the result is written to a temp file, re-opened to prove the page count is unchanged,
 * and only then swapped in.
 *
 * References:
 * - PdfBox-Android `PDDocument` / `PDPage.getRotation` / `setRotation` —
 *   https://github.com/TomRoush/PdfBox-Android
 * - Apache PDFBox `PDPage` — https://pdfbox.apache.org/docs/2.0.27/javadocs/org/apache/pdfbox/pdmodel/PDPage.html
 */
internal class PdfRotateToolHandler(
    private val io: DocumentToolIo,
) : ToolHandler {
    override val name = "pdf_rotate"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        return try {
            val rawSource = io.stringArgument(arguments, "source")
                ?: return ToolResult(false, "Не указан исходный PDF (source).")
            val rawDestination = io.stringArgument(arguments, "destination")
                ?: return ToolResult(false, "Путь к создаваемому файлу не указан (destination).")
            val rawAngle = io.stringArgument(arguments, "angle")
            val angle = PdfToolOptions.rotationDegrees(rawAngle)
                ?: return ToolResult(
                    false,
                    "Некорректный угол поворота «${rawAngle ?: ""}»: допускаются 90, 180, 270 " +
                        "(или 0)."
                )
            val overwrite = io.booleanArgument(arguments, "overwrite")

            val source = when (val resolved = io.resolvePdfSource(rawSource)) {
                is PdfSource.Failure -> return ToolResult(false, resolved.message)
                is PdfSource.Ok -> resolved
            }
            val rawPages = io.stringArgument(arguments, "pages")
            val range = if (rawPages == null) {
                1..source.pages
            } else {
                io.parsePageRange(rawPages, source.pages)
                    ?: return ToolResult(
                        false,
                        "Некорректный диапазон страниц «$rawPages»: в документе ${source.pages} стр."
                    )
            }

            val file = when (val resolved = io.resolveDestination(rawDestination, overwrite)) {
                is Destination.Failure -> return ToolResult(false, resolved.message)
                is Destination.Ok -> resolved.file
            }
            if (source.file.canonicalFile == file.canonicalFile) {
                return ToolResult(false, "Файл назначения совпадает с исходным PDF: ${file.absolutePath}")
            }

            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp-${System.nanoTime()}")
            try {
                PDDocument.load(source.file).use { document ->
                    for (pageNumber in range) {
                        val page = document.getPage(pageNumber - 1)
                        val rotated = ((page.rotation + angle) % 360 + 360) % 360
                        page.rotation = rotated
                    }
                    document.save(temp)
                }
            } catch (e: Exception) {
                temp.delete()
                return ToolResult(false, "Не удалось повернуть страницы: ${e.message}")
            }

            val actualPages = try {
                PDDocument.load(temp).use { it.numberOfPages }
            } catch (e: Exception) {
                temp.delete()
                return ToolResult(false, "Не удалось открыть созданный PDF для проверки: ${e.message}")
            }
            if (actualPages != source.pages) {
                temp.delete()
                return ToolResult(
                    false,
                    "Проверка после записи не пройдена: страниц $actualPages, ожидалось " +
                        "${source.pages}. Файл не создан, исходный PDF не изменён."
                )
            }

            val commitError = io.commitTemp(temp, file)
            if (commitError != null) return ToolResult(false, commitError)
            val backupNote = io.cleanupBackup(file)
            ToolResult(
                true,
                "Повёрнут PDF: ${file.absolutePath}\n" +
                    "Поворот: +${angle}°\n" +
                    "Страницы: ${range.first}-${range.last} из ${source.pages}\n" +
                    "Размер: ${DocumentSupport.formatBytes(file.length())}" +
                    if (backupNote != null) "\n$backupNote" else ""
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка при повороте PDF: ${e.message}")
        }
    }
}
