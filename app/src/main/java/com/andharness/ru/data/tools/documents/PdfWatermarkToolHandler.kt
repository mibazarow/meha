package com.andharness.ru.data.tools.documents

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.documents.PdfToolOptions
import com.andharness.ru.domain.tools.ToolHandler
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * `pdf_watermark`: draws a text watermark over existing pages and writes a NEW PDF. The watermark is
 * centred on each target page and drawn on top of the existing content (the content stream is opened
 * in [PDPageContentStream.AppendMode.APPEND]). On page rotation the watermark is best-effort: it is
 * placed in the page's own coordinate space, so a viewer that rotates the page will rotate it too.
 *
 * Options: `text` (required), optional `pages` (1-based range, default all), `opacity` (0..1,
 * default [PdfToolOptions.DEFAULT_WATERMARK_OPACITY]), `font_size` (points),
 * `angle` (degrees, counter-clockwise, default 45) and `color` (`#rrggbb`, `#rgb` or `r,g,b`).
 * The embedded Unicode font is used so Cyrillic watermarks stay readable.
 *
 * References:
 * - PdfBox-Android `PDPageContentStream` (APPEND mode / `setTextMatrix` / `setGraphicsStateParameters`) —
 *   https://github.com/TomRoush/PdfBox-Android
 * - Apache PDFBox `PDExtendedGraphicsState` (non-stroking alpha) —
 *   https://pdfbox.apache.org/docs/2.0.27/javadocs/org/apache/pdfbox/pdmodel/graphics/state/PDExtendedGraphicsState.html
 */
internal class PdfWatermarkToolHandler(
    private val io: DocumentToolIo,
    private val writer: PdfWriter,
) : ToolHandler {
    override val name = "pdf_watermark"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        return try {
            val rawSource = io.stringArgument(arguments, "source")
                ?: return ToolResult(false, "Не указан исходный PDF (source).")
            val rawDestination = io.stringArgument(arguments, "destination")
                ?: return ToolResult(false, "Путь к создаваемому файлу не указан (destination).")
            val text = io.stringArgument(arguments, "text")
                ?: return ToolResult(false, "Не указан текст водяного знака (text).")
            val rawOpacity = io.stringArgument(arguments, "opacity")
            val opacity = rawOpacity?.let { PdfToolOptions.opacity(it) }
                ?: if (rawOpacity == null) PdfToolOptions.DEFAULT_WATERMARK_OPACITY else return ToolResult(
                    false,
                    "Некорректная прозрачность «$rawOpacity»: укажите число от 0 до 1."
                )
            val rawColor = io.stringArgument(arguments, "color")
            val color = rawColor?.let { PdfToolOptions.color(it) }
                ?: if (rawColor == null) DEFAULT_COLOR else return ToolResult(
                    false,
                    "Некорректный цвет «$rawColor»: укажите #rrggbb, #rgb или r,g,b."
                )
            val fontSize = io.intArgument(arguments, "font_size")?.toFloat()?.takeIf { it > 0f }
                ?: PdfToolOptions.DEFAULT_WATERMARK_FONT_SIZE
            val angle = io.intArgument(arguments, "angle") ?: PdfToolOptions.DEFAULT_WATERMARK_ANGLE
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
                    val font = writer.pdfFont(document)
                    val safeText = writer.sanitizePdfText(text, font)
                    val radians = Math.toRadians(angle.toDouble())
                    for (pageNumber in range) {
                        val page = document.getPage(pageNumber - 1)
                        val box = page.mediaBox ?: PDRectangle.A4
                        val centerX = box.width / 2f
                        val centerY = box.height / 2f
                        val textWidth = writer.pdfTextWidth(safeText, font, fontSize)
                        PDPageContentStream(
                            document,
                            page,
                            PDPageContentStream.AppendMode.APPEND,
                            true,
                            true
                        ).use { stream ->
                            val state = PDExtendedGraphicsState()
                            state.nonStrokingAlphaConstant = opacity
                            stream.saveGraphicsState()
                            stream.setGraphicsStateParameters(state)
                            stream.setNonStrokingColor(color.first, color.second, color.third)
                            stream.beginText()
                            stream.setFont(font, fontSize)
                            val matrix = Matrix.getRotateInstance(radians, centerX, centerY).multiply(
                                Matrix.getTranslateInstance(-textWidth / 2f, -fontSize / 3f)
                            )
                            stream.setTextMatrix(matrix)
                            stream.showText(safeText)
                            stream.endText()
                            stream.restoreGraphicsState()
                        }
                    }
                    document.save(temp)
                }
            } catch (e: Exception) {
                temp.delete()
                return ToolResult(false, "Не удалось наложить водяной знак: ${e.message}")
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
                "Водяной знак наложен: ${file.absolutePath}\n" +
                    "Страницы: ${range.first}-${range.last} из ${source.pages}\n" +
                    "Прозрачность: $opacity, размер: ${fontSize.toInt()} пт\n" +
                    "Размер файла: ${DocumentSupport.formatBytes(file.length())}" +
                    if (backupNote != null) "\n$backupNote" else ""
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка при наложении водяного знака: ${e.message}")
        }
    }

    private companion object {
        /** Neutral grey used when the caller does not pass a `color`. */
        val DEFAULT_COLOR = Triple(128, 128, 128)
    }
}
