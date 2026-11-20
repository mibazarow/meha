package com.andharness.ru.data.tools.documents

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.andharness.ru.data.vision.OcrRecognizer
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

    /**
     * Renders one page of an existing PDF to a bitmap with [PdfRenderer] and runs the shared
     * on-device [OcrRecognizer] over it (Tesseract for Cyrillic, ML Kit Latin as the honest
     * fallback), returning the recognised text. The page is opened through a
     * `ParcelFileDescriptor` (so the whole file is never loaded into memory), scaled to roughly
     * [TARGET_RENDER_WIDTH] pixels of width for legibility, and every native resource (page,
     * renderer, descriptor, bitmap) is released in a `finally` block.
     *
     * References:
     * - `PdfRenderer` / `PdfRenderer.Page` —
     *   https://developer.android.com/reference/android/graphics/pdf/PdfRenderer
     * - `ParcelFileDescriptor` —
     *   https://developer.android.com/reference/android/os/ParcelFileDescriptor
     * - Tesseract4Android — https://github.com/adaptech-cz/Tesseract4Android
     */
internal class PdfOcrToolHandler(
    private val io: DocumentToolIo,
    private val ocrRecognizer: OcrRecognizer,
) : ToolHandler {
    override val name = "pdf_ocr"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val raw = (arguments["path"] as? JsonPrimitive)?.content?.trim()
        if (raw.isNullOrEmpty()) {
            return@withContext ToolResult(false, "Путь не указан.")
        }
        val file = try {
            io.resolve(raw)
        } catch (e: IllegalArgumentException) {
            return@withContext ToolResult(false, e.message ?: "Некорректный путь: «$raw».")
        }
        if (!file.isFile) {
            return@withContext ToolResult(false, "Файл не найден: ${file.absolutePath}")
        }
        if (DocumentFormatDetector.detect(file) != DocumentFormat.PDF) {
            return@withContext ToolResult(
                false,
                "Файл не является PDF: ${file.absolutePath}. Распознавание доступно только для PDF."
            )
        }
        val pageNumber = io.intArgument(arguments, "page") ?: 1

        var descriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        var bitmap: Bitmap? = null
        try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(descriptor)
            if (pageNumber < 1 || pageNumber > renderer.pageCount) {
                return@withContext ToolResult(
                    false,
                    "Страница $pageNumber вне диапазона: в документе ${renderer.pageCount} стр."
                )
            }
            page = renderer.openPage(pageNumber - 1)
            // Render at about 1600 px of width so small print stays legible to the OCR engine.
            val scale = TARGET_RENDER_WIDTH.toFloat() / page.width
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            val rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap = rendered
            rendered.eraseColor(Color.WHITE)
            page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            val result = ocrRecognizer.recognize(rendered)
            val text = result.text
            if (text.isBlank()) {
                ToolResult(
                    true,
                    "Текст не распознан (страница $pageNumber).\n(${result.statusLine})"
                )
            } else {
                ToolResult(
                    true,
                    "PDF: ${file.absolutePath}\nСтраница: $pageNumber\n\n$text\n\n(${result.statusLine})"
                )
            }
        } catch (e: Exception) {
            ToolResult(false, "Ошибка распознавания PDF: ${e.message}")
        } finally {
            runCatching { page?.close() }
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
            runCatching { bitmap?.recycle() }
        }
    }

    private companion object {
        /** Target pixel width a PDF page is rendered at before OCR (keeps small print legible). */
        const val TARGET_RENDER_WIDTH = 1600
    }
}
