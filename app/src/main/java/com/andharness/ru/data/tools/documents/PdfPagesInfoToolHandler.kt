package com.andharness.ru.data.tools.documents

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * `pdf_pages`: a quick, read-only structural listing of a PDF — the page count and, per page, the
 * media-box size (points and millimetres) plus the effective `/Rotate`. The list is capped at
 * [MAX_LISTED_PAGES] entries so a huge document cannot flood the context; the real page count is
 * always reported in full.
 *
 * References:
 * - PdfBox-Android `PDDocument.getNumberOfPages` / `PDPage.getMediaBox` / `getRotation` —
 *   https://github.com/TomRoush/PdfBox-Android
 * - Apache PDFBox `PDPage` — https://pdfbox.apache.org/docs/2.0.27/javadocs/org/apache/pdfbox/pdmodel/PDPage.html
 */
internal class PdfPagesInfoToolHandler(
    private val io: DocumentToolIo,
) : ToolHandler {
    override val name = "pdf_pages"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val rawPath = io.stringArgument(arguments, "path")
            ?: return@withContext ToolResult(false, "Не указан путь к PDF (path).")
        val file = try {
            io.resolve(rawPath)
        } catch (e: IllegalArgumentException) {
            return@withContext ToolResult(false, e.message ?: "Некорректный путь: «$rawPath».")
        }
        if (!file.isFile) {
            return@withContext ToolResult(false, "Файл не найден: ${file.absolutePath}")
        }
        try {
            PDDocument.load(file).use { document ->
                val pageCount = document.numberOfPages
                val builder = StringBuilder()
                builder.append("PDF: ${file.absolutePath}\n")
                builder.append("Страниц: $pageCount\n")
                builder.append("Размер файла: ${DocumentSupport.formatBytes(file.length())}\n")
                val listed = minOf(pageCount, MAX_LISTED_PAGES)
                if (listed > 0) builder.append("Размеры страниц (пт / мм, поворот):\n")
                for (index in 0 until listed) {
                    val page = document.getPage(index)
                    val box = page.mediaBox ?: PDRectangle.A4
                    val widthPt = box.width
                    val heightPt = box.height
                    val widthMm = widthPt * POINTS_TO_MM
                    val heightMm = heightPt * POINTS_TO_MM
                    builder.append(
                        "стр. ${index + 1}: " +
                            "${widthPt.toInt()}×${heightPt.toInt()} пт " +
                            "(${widthMm.toInt()}×${heightMm.toInt()} мм), " +
                            "поворот ${page.rotation}°\n"
                    )
                }
                if (pageCount > listed) {
                    builder.append("…показаны первые $listed из $pageCount страниц.\n")
                }
                ToolResult(true, builder.toString().trimEnd())
            }
        } catch (e: Exception) {
            ToolResult(false, "Не удалось прочитать PDF: ${e.message}")
        }
    }

    private companion object {
        /** Upper bound on the per-page lines, so a large document stays readable. */
        const val MAX_LISTED_PAGES = 200

        /** Millimetres per PDF point (1 pt = 1/72 in, 1 in = 25.4 mm). */
        const val POINTS_TO_MM = 25.4f / 72f
    }
}
