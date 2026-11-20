package com.andharness.ru.data.tools.documents

import android.graphics.Bitmap
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.documents.PdfToolOptions
import com.andharness.ru.domain.tools.ToolHandler
import com.tom_roush.pdfbox.contentstream.PDFStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.DrawObject
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileOutputStream

/**
 * `pdf_extract_images`: walks the content streams of the selected pages and writes every embedded
 * image to `output_dir` at its natural resolution as a PNG. Page content is decoded by a
 * [PDFStreamEngine] subclass that intercepts the `Do` operator and, for every `PDImageXObject`,
 * hands the decoded `Bitmap` to this handler (forms are still recursed by the base engine).
 *
 * Images are named `page-001-image-01.png`. Existing targets are skipped unless `overwrite=true`,
 * and every skip is reported honestly rather than silently overwritten.
 *
 * References:
 * - PdfBox-Android `PDFStreamEngine` / `PDImageXObject.getImage()` (returns an Android `Bitmap`) —
 *   https://github.com/TomRoush/PdfBox-Android
 * - Android `Bitmap.compress` —
 *   https://developer.android.com/reference/android/graphics/Bitmap#compress(android.graphics.Bitmap.CompressFormat,%20int,%20java.io.OutputStream)
 */
internal class PdfExtractImagesToolHandler(
    private val io: DocumentToolIo,
) : ToolHandler {
    override val name = "pdf_extract_images"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val rawPath = io.stringArgument(arguments, "path")
                ?: return@withContext ToolResult(false, "Не указан путь к PDF (path).")
            val rawDir = io.stringArgument(arguments, "output_dir")
                ?: return@withContext ToolResult(false, "Не указан каталог назначения (output_dir).")
            val overwrite = io.booleanArgument(arguments, "overwrite")

            val source = when (val resolved = io.resolvePdfSource(rawPath)) {
                is PdfSource.Failure -> return@withContext ToolResult(false, resolved.message)
                is PdfSource.Ok -> resolved
            }
            val rawPages = io.stringArgument(arguments, "pages")
            val range = if (rawPages == null) {
                1..source.pages
            } else {
                io.parsePageRange(rawPages, source.pages)
                    ?: return@withContext ToolResult(
                        false,
                        "Некорректный диапазон страниц «$rawPages»: в документе ${source.pages} стр."
                    )
            }

            val dir = try {
                io.resolve(rawDir)
            } catch (e: IllegalArgumentException) {
                return@withContext ToolResult(false, e.message ?: "Некорректный путь: «$rawDir».")
            }
            if (dir.isFile) {
                return@withContext ToolResult(false, "Путь указывает на файл, а не каталог: ${dir.absolutePath}")
            }
            if (!dir.exists() && !dir.mkdirs()) {
                return@withContext ToolResult(false, "Не удалось создать каталог: ${dir.absolutePath}")
            }

            val written = mutableListOf<String>()
            val skipped = mutableListOf<String>()
            var imageCount = 0

            try {
                PDDocument.load(source.file).use { document ->
                    for (pageNumber in range) {
                        var pageIndex = 0
                        val engine = ImageExtractingStreamEngine { image ->
                            imageCount += 1
                            pageIndex += 1
                            val name = PdfToolOptions.imageFileName(pageNumber, pageIndex)
                            extractOne(image, dir, name, overwrite, written, skipped)
                        }
                        engine.processPage(document.getPage(pageNumber - 1))
                    }
                }
            } catch (e: Exception) {
                return@withContext ToolResult(false, "Не удалось извлечь изображения: ${e.message}")
            }

            if (written.isEmpty() && skipped.isEmpty()) {
                return@withContext ToolResult(
                    true,
                    "Встроенных изображений не найдено (страницы ${range.first}-${range.last} из " +
                        "${source.pages}).\nКаталог: ${dir.absolutePath}"
                )
            }
            ToolResult(
                true,
                "Извлечено изображений: ${written.size} → ${dir.absolutePath}\n" +
                    "Всего найдено встроенных изображений: $imageCount\n" +
                    "Страницы: ${range.first}-${range.last} из ${source.pages}" +
                    if (skipped.isNotEmpty()) {
                        "\nПропущено (уже существуют, overwrite=false): ${skipped.joinToString(", ")}"
                    } else {
                        ""
                    }
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка при извлечении изображений: ${e.message}")
        }
    }

    /**
     * Decodes one image to a PNG in [dir], or records it in [skipped] when the target already exists
     * and [overwrite] is off. A null bitmap (an image the decoder could not materialise) is skipped
     * silently because the page was still scanned; the totals in the result make the shortfall visible.
     */
    private fun extractOne(
        image: PDImageXObject,
        dir: File,
        name: String,
        overwrite: Boolean,
        written: MutableList<String>,
        skipped: MutableList<String>,
    ) {
        val bitmap = image.image ?: return
        try {
            val target = File(dir, name)
            if (target.exists() && !overwrite) {
                skipped += name
            } else {
                FileOutputStream(target).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                written += name
            }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Intercepts the `Do` operator: an image XObject is handed to [onImage], anything else (notably
     * a form XObject, which may itself contain images) is delegated to the base engine.
     */
    private class ImageExtractingStreamEngine(
        private val onImage: (PDImageXObject) -> Unit,
    ) : PDFStreamEngine() {

        init {
            // Guarantee form recursion even if the base registry did not include `Do`.
            addOperator(DrawObject())
        }

        override fun processOperator(operation: Operator, arguments: List<COSBase>) {
            if (operation.name == "Do" && arguments.size == 1) {
                val name = arguments[0] as? COSName
                if (name != null) {
                    val xobject = resources?.getXObject(name)
                    if (xobject is PDImageXObject) {
                        onImage(xobject)
                        return
                    }
                }
            }
            super.processOperator(operation, arguments)
        }
    }
}
