package com.andharness.ru.data.tools.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * `image_adjust`: brightness / contrast / saturation via a single [ColorMatrix], plus a light
 * unsharp-mask sharpening pass, writing a NEW file.
 *
 * brightness/contrast/saturation are in `-1..1` (0 = unchanged). Saturation uses
 * `ColorMatrix.setSaturation`; brightness adds a per-channel offset; contrast scales the channels
 * around mid-grey. The matrices are concatenated with `postConcat`, then applied with a
 * [ColorMatrixColorFilter] while drawing. Sharpness is `0..1` and blends the classic plus-shaped
 * sharpening kernel with the identity kernel (`center = 1 + 4a`, `edge = -a`). References:
 * - `ColorMatrix`, `setSaturation(float)`, `postConcat(ColorMatrix)` —
 *   https://developer.android.com/reference/android/graphics/ColorMatrix
 * - `ColorMatrixColorFilter` / `Paint.setColorFilter(ColorFilter)` —
 *   https://developer.android.com/reference/android/graphics/ColorMatrixColorFilter
 * - `Bitmap.getPixels(int[], int, int, int, int, int, int)` / `setPixels(...)` —
 *   https://developer.android.com/reference/android/graphics/Bitmap#getPixels(int%5B%5D,%20int,%20int,%20int,%20int,%20int,%20int)
 */
internal class ImageAdjustToolHandler(private val io: MediaPathIo) : ToolHandler {
    override val name = "image_adjust"

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

            val brightness: Double
            val contrast: Double
            val saturation: Double
            val sharpness: Double
            try {
                brightness = ImageEditParsing.optDoubleClamped(arguments, "brightness", -1.0, 1.0, 0.0)
                contrast = ImageEditParsing.optDoubleClamped(arguments, "contrast", -1.0, 1.0, 0.0)
                saturation = ImageEditParsing.optDoubleClamped(arguments, "saturation", -1.0, 1.0, 0.0)
                sharpness = ImageEditParsing.optDoubleClamped(arguments, "sharpness", 0.0, 1.0, 0.0)
            } catch (e: IllegalArgumentException) {
                return@withContext ToolResult(false, e.message ?: "Некорректные параметры настройки.")
            }

            if (brightness == 0.0 && contrast == 0.0 && saturation == 0.0 && sharpness == 0.0) {
                return@withContext ToolResult(
                    false,
                    "Не задано ни одного изменения (brightness / contrast / saturation / sharpness).",
                )
            }

            val decoded = ImageEditCommon.decodeCapped(src)
                ?: return@withContext ToolResult(
                    false,
                    "Не удалось декодировать изображение: «${src.name}» (формат или файл повреждён).",
                )
            source = decoded.bitmap

            val matrix = ColorMatrix().apply { setSaturation((1.0 + saturation).toFloat()) }
            if (brightness != 0.0) {
                val offset = (brightness * 255.0).toFloat()
                matrix.postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            1f, 0f, 0f, 0f, offset,
                            0f, 1f, 0f, 0f, offset,
                            0f, 0f, 1f, 0f, offset,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    ),
                )
            }
            if (contrast != 0.0) {
                val scale = (1.0 + contrast).toFloat()
                val translate = 128f * (1f - scale)
                matrix.postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            scale, 0f, 0f, 0f, translate,
                            0f, scale, 0f, 0f, translate,
                            0f, 0f, scale, 0f, translate,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    ),
                )
            }

            result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(matrix)
            }
            canvas.drawBitmap(source, 0f, 0f, paint)

            applySharpen(result, sharpness.toFloat())

            val error = ImageEditCommon.writeBitmap(dest, result)
            if (error != null) return@withContext ToolResult(false, error)

            ToolResult(true, ImageEditCommon.successMessage(dest, result, decoded.downscaled))
        } catch (e: OutOfMemoryError) {
            ToolResult(false, "Недостаточно памяти для обработки изображения.")
        } catch (e: Exception) {
            Log.e("MediaToolsHandler", "image_adjust failed", e)
            ToolResult(false, "Не удалось настроить изображение: ${e.message}")
        } finally {
            if (result !== source) result?.recycle()
            source?.recycle()
        }
    }

    /**
     * In-place light unsharp mask: a plus-shaped 3x3 kernel blended with the identity by [amount]
     * (`center = 1 + 4a`, `edge = -a`). Alpha is preserved. No-op for `amount <= 0` or a bitmap
     * smaller than 3x3.
     */
    private fun applySharpen(bitmap: Bitmap, amount: Float) {
        val a = amount.coerceIn(0f, 1f)
        if (a <= 0f) return
        val width = bitmap.width
        val height = bitmap.height
        if (width < 3 || height < 3) return
        val src = IntArray(width * height)
        bitmap.getPixels(src, 0, width, 0, 0, width, height)
        val out = src.copyOf()
        val center = 1f + 4f * a
        val edge = -a
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val c = src[i]
                val up = src[i - width]
                val down = src[i + width]
                val left = src[i - 1]
                val right = src[i + 1]
                val r = center * ((c ushr 16) and 0xFF) +
                    edge * (((up ushr 16) and 0xFF) + ((down ushr 16) and 0xFF) +
                        ((left ushr 16) and 0xFF) + ((right ushr 16) and 0xFF))
                val g = center * ((c ushr 8) and 0xFF) +
                    edge * (((up ushr 8) and 0xFF) + ((down ushr 8) and 0xFF) +
                        ((left ushr 8) and 0xFF) + ((right ushr 8) and 0xFF))
                val b = center * (c and 0xFF) +
                    edge * ((up and 0xFF) + (down and 0xFF) + (left and 0xFF) + (right and 0xFF))
                out[i] = (c and 0xFF000000.toInt()) or
                    (r.toInt().coerceIn(0, 255) shl 16) or
                    (g.toInt().coerceIn(0, 255) shl 8) or
                    b.toInt().coerceIn(0, 255)
            }
        }
        bitmap.setPixels(out, 0, width, 0, 0, width, height)
    }
}
