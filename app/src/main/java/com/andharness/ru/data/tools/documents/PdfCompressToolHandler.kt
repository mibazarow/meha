package com.andharness.ru.data.tools.documents

import android.graphics.Bitmap
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.math.max

/**
 * `pdf_compress`: best-effort size reduction that writes a NEW PDF and honestly reports what it
 * achieved. Two reversible steps are applied:
 *
 * 1. the document is re-saved, which re-serialises the file through PdfBox (unreferenced objects are
 *    not written back);
 * 2. when `max_image_dimension` is given, every embedded raster image whose longer side exceeds it
 *    is downscaled and re-encoded as JPEG at `image_quality` (1..100, default 75). Image masks,
 *    stencils and images with a soft mask are left untouched so transparency is never broken.
 *
 * The reply always states the before/after byte sizes and the number of images downscaled, and warns
 * honestly when the result did not get smaller (a re-save of an already-optimised PDF can grow it).
 *
 * References:
 * - PdfBox-Android `PDDocument.save` / `PDResources.getXObject` / `JPEGFactory.createFromImage` /
 *   `PDImageXObject.getImage` — https://github.com/TomRoush/PdfBox-Android
 * - Android `Bitmap.createScaledBitmap` —
 *   https://developer.android.com/reference/android/graphics/Bitmap#createScaledBitmap(android.graphics.Bitmap,%20int,%20int,%20boolean)
 */
internal class PdfCompressToolHandler(
    private val io: DocumentToolIo,
) : ToolHandler {
    override val name = "pdf_compress"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val rawSource = io.stringArgument(arguments, "source")
                ?: return@withContext ToolResult(false, "Не указан исходный PDF (source).")
            val rawDestination = io.stringArgument(arguments, "destination")
                ?: return@withContext ToolResult(false, "Путь к создаваемому файлу не указан (destination).")
            val overwrite = io.booleanArgument(arguments, "overwrite")

            val maxDimension = io.intArgument(arguments, "max_image_dimension") ?: 0
            if (maxDimension < 0) {
                return@withContext ToolResult(false, "max_image_dimension не может быть отрицательным.")
            }
            val quality = io.intArgument(arguments, "image_quality") ?: DEFAULT_IMAGE_QUALITY
            if (quality !in 1..100) {
                return@withContext ToolResult(false, "image_quality должен быть от 1 до 100.")
            }

            val source = when (val resolved = io.resolvePdfSource(rawSource)) {
                is PdfSource.Failure -> return@withContext ToolResult(false, resolved.message)
                is PdfSource.Ok -> resolved
            }
            val file = when (val resolved = io.resolveDestination(rawDestination, overwrite)) {
                is Destination.Failure -> return@withContext ToolResult(false, resolved.message)
                is Destination.Ok -> resolved.file
            }
            if (source.file.canonicalFile == file.canonicalFile) {
                return@withContext ToolResult(false, "Файл назначения совпадает с исходным PDF: ${file.absolutePath}")
            }

            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp-${System.nanoTime()}")
            var downscaled = 0
            try {
                PDDocument.load(source.file).use { document ->
                    if (maxDimension > 0) {
                        downscaled = downscaleImages(document, maxDimension, quality)
                    }
                    document.save(temp)
                }
            } catch (e: Exception) {
                temp.delete()
                return@withContext ToolResult(false, "Не удалось сжать PDF: ${e.message}")
            }

            val actualPages = try {
                PDDocument.load(temp).use { it.numberOfPages }
            } catch (e: Exception) {
                temp.delete()
                return@withContext ToolResult(false, "Не удалось открыть созданный PDF для проверки: ${e.message}")
            }
            if (actualPages != source.pages) {
                temp.delete()
                return@withContext ToolResult(
                    false,
                    "Проверка после записи не пройдена: страниц $actualPages, ожидалось " +
                        "${source.pages}. Файл не создан, исходный PDF не изменён."
                )
            }

            val originalSize = source.file.length()
            val newSize = temp.length()
            val commitError = io.commitTemp(temp, file)
            if (commitError != null) return@withContext ToolResult(false, commitError)
            val backupNote = io.cleanupBackup(file)

            val delta = originalSize - newSize
            val saving = if (originalSize > 0) delta * 100.0 / originalSize else 0.0
            val verdict = when {
                delta > 0 -> "Уменьшение: ${DocumentSupport.formatBytes(delta)} " +
                    "(${String.format(java.util.Locale.ROOT, "%.0f", saving)}%)"
                delta == 0L -> "Размер не изменился."
                else -> "Размер ВЫРОС на ${DocumentSupport.formatBytes(-delta)}: этот PDF уже " +
                    "оптимизирован. Файл всё равно создан."
            }
            val message = buildString {
                append("Сжатие PDF: ${file.absolutePath}\n")
                append("Было: ${DocumentSupport.formatBytes(originalSize)}\n")
                append("Стало: ${DocumentSupport.formatBytes(newSize)}\n")
                append("$verdict\n")
                append("Изображений уменьшено: $downscaled")
                if (downscaled == 0 && maxDimension == 0) {
                    append("\n(без перекодирования изображений; задайте max_image_dimension, чтобы уменьшить картинки)")
                }
                if (backupNote != null) append("\n$backupNote")
            }
            ToolResult(true, message)
        } catch (e: Exception) {
            ToolResult(false, "Ошибка при сжатии PDF: ${e.message}")
        }
    }

    /**
     * Downscales every eligible embedded image across all pages and returns how many were re-encoded.
     * Ineligible images (stencils, soft-masked/alpha images, or already small ones) are left as-is.
     */
    private fun downscaleImages(document: PDDocument, maxDimension: Int, quality: Int): Int {
        var count = 0
        for (page in document.pages) {
            val resources = page.resources ?: continue
            val names = resources.xObjectNames.toList()
            for (name in names) {
                val xObject = try {
                    resources.getXObject(name)
                } catch (e: Exception) {
                    continue
                }
                if (xObject !is PDImageXObject) continue
                if (xObject.isStencil) continue
                if (xObject.softMask != null) continue
                val bitmap = try {
                    xObject.image
                } catch (e: Exception) {
                    null
                } ?: continue
                try {
                    val longSide = max(bitmap.width, bitmap.height)
                    if (longSide <= maxDimension) continue
                    val scale = maxDimension.toFloat() / longSide
                    val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
                    val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
                    val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
                    try {
                        val replacement = JPEGFactory.createFromImage(document, scaled, quality / 100f)
                        resources.put(name, replacement)
                        count += 1
                    } finally {
                        if (scaled !== bitmap) scaled.recycle()
                    }
                } catch (e: Exception) {
                    // Best-effort: a single image that cannot be re-encoded must not fail the whole run.
                } finally {
                    bitmap.recycle()
                }
            }
        }
        return count
    }

    private companion object {
        /** JPEG quality (1..100) used when an image is downscaled. */
        const val DEFAULT_IMAGE_QUALITY = 75
    }
}
