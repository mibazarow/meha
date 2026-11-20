package com.andharness.ru.data.tools.media

import android.graphics.Bitmap
import android.util.Log
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * `image_crop`: crops an image to a rectangle and writes a NEW file.
 *
 * The rectangle comes either from `x`/`y`/`width`/`height` (pixels) or from `box`
 * (`x1,y1,x2,y2` as fractions 0..1 of the source). Reference:
 * `Bitmap.createBitmap(Bitmap, int, int, int, int)` —
 * https://developer.android.com/reference/android/graphics/Bitmap#createBitmap(android.graphics.Bitmap,%20int,%20int,%20int,%20int)
 */
internal class ImageCropToolHandler(private val io: MediaPathIo) : ToolHandler {
    override val name = "image_crop"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        var source: Bitmap? = null
        var result: Bitmap? = null
        try {
            val srcResolved = ImageEditCommon.resolveSource(io, arguments, "src")
            srcResolved.error?.let { return@withContext ToolResult(false, it) }
            val src = srcResolved.file!!
            val destResolved = ImageEditCommon.prepareDestination(io, arguments, "dest")
            destResolved.error?.let { return@withContext ToolResult(false, it) }
            val dest = destResolved.file!!

            val decoded = ImageEditCommon.decodeCapped(src)
                ?: return@withContext ToolResult(
                    false,
                    "Не удалось декодировать изображение: «${src.name}» (формат или файл повреждён).",
                )
            source = decoded.bitmap

            val rect = try {
                ImageEditParsing.cropRect(arguments, source.width, source.height)
            } catch (e: IllegalArgumentException) {
                return@withContext ToolResult(false, e.message ?: "Некорректная область обрезки.")
            }

            result = Bitmap.createBitmap(source, rect[0], rect[1], rect[2], rect[3])

            val error = ImageEditCommon.writeBitmap(dest, result)
            if (error != null) return@withContext ToolResult(false, error)

            ToolResult(true, ImageEditCommon.successMessage(dest, result, decoded.downscaled))
        } catch (e: OutOfMemoryError) {
            ToolResult(false, "Недостаточно памяти для обработки изображения.")
        } catch (e: Exception) {
            Log.e("MediaToolsHandler", "image_crop failed", e)
            ToolResult(false, "Не удалось обрезать изображение: ${e.message}")
        } finally {
            if (result !== source) result?.recycle()
            source?.recycle()
        }
    }
}
