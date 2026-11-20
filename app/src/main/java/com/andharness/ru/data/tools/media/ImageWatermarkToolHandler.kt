package com.andharness.ru.data.tools.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * `image_watermark`: draws a text or small-image watermark onto an image (corners/center, optional
 * tiling) and writes a NEW file.
 *
 * A watermark is either `text` (with `size` px, `color`, `opacity`) or a small `image` overlay
 * scaled to `size` px wide; exactly one of the two is used (text first). Without `tiled`, the
 * watermark is placed by [ImageEditParsing.watermarkOrigin]; `tiled` repeats it in a grid across
 * the whole image. References:
 * - `Canvas.drawText(String, float, float, Paint)` / `drawBitmap(Bitmap, float, float, Paint)` —
 *   https://developer.android.com/reference/android/graphics/Canvas
 * - `Paint.measureText(String)` / `getFontMetrics()` / `setAlpha(int)` / `setTextSize(float)` —
 *   https://developer.android.com/reference/android/graphics/Paint
 */
internal class ImageWatermarkToolHandler(private val io: MediaPathIo) : ToolHandler {
    override val name = "image_watermark"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        var source: Bitmap? = null
        var result: Bitmap? = null
        var overlay: Bitmap? = null
        var scaledOverlay: Bitmap? = null
        try {
            val text = ImageEditParsing.optString(arguments, "text")
            val overlayPath = ImageEditParsing.optString(arguments, "image")
            if (text == null && overlayPath == null) {
                return@withContext ToolResult(false, "Укажи text (текстовый знак) или image (картинку-знак).")
            }

            val srcResolved = ImageEditCommon.resolveSource(io, arguments, "src")
            srcResolved.error?.let { return@withContext ToolResult(false, it) }
            val src = srcResolved.file!!
            val destResolved = ImageEditCommon.prepareDestination(io, arguments, "dest")
            destResolved.error?.let { return@withContext ToolResult(false, it) }
            val dest = destResolved.file!!

            val position = ImageEditParsing.optString(arguments, "position")
            val opacity: Double
            val margin: Int
            val tiled: Boolean
            val color: Int
            val sizeArg: Int?
            try {
                opacity = ImageEditParsing.optDoubleClamped(arguments, "opacity", 0.0, 1.0, 1.0)
                margin = ImageEditParsing.optInt(arguments, "margin", min = 0) ?: 16
                tiled = ImageEditParsing.optBool(arguments, "tiled")
                color = ImageEditParsing.parseColor(
                    ImageEditParsing.optString(arguments, "color"),
                    0xFFFFFFFF.toInt(),
                )
                sizeArg = ImageEditParsing.optInt(arguments, "size", min = 1)
            } catch (e: IllegalArgumentException) {
                return@withContext ToolResult(false, e.message ?: "Некорректные параметры водяного знака.")
            }

            val decoded = ImageEditCommon.decodeCapped(src)
                ?: return@withContext ToolResult(
                    false,
                    "Не удалось декодировать изображение: «${src.name}» (формат или файл повреждён).",
                )
            source = decoded.bitmap
            result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            canvas.drawBitmap(source, 0f, 0f, null)

            val alpha = (opacity * 255.0).roundToInt().coerceIn(0, 255)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            var markWidth: Int
            var markHeight: Int

            if (text != null) {
                paint.typeface = Typeface.DEFAULT_BOLD
                paint.color = color
                paint.alpha = alpha
                val fontSize = (sizeArg ?: (maxOf(source.width, source.height) / 16f).roundToInt())
                    .coerceAtLeast(8)
                paint.textSize = fontSize.toFloat()
                val metrics = paint.fontMetrics
                markWidth = ceil(paint.measureText(text)).toInt().coerceAtLeast(1)
                markHeight = ceil(metrics.descent - metrics.ascent).toInt().coerceAtLeast(1)
                val baselineOffset = -metrics.ascent
                if (tiled) {
                    val gap = (markHeight / 2).coerceAtLeast(8)
                    ImageEditParsing.tileOrigins(
                        canvas.width, canvas.height, markWidth + gap, markHeight + gap, margin,
                    ).forEach { origin ->
                        canvas.drawText(text, origin[0].toFloat(), origin[1] + baselineOffset, paint)
                    }
                } else {
                    val origin = ImageEditParsing.watermarkOrigin(
                        position, canvas.width, canvas.height, markWidth, markHeight, margin,
                    )
                    canvas.drawText(text, origin[0].toFloat(), (origin[1] + baselineOffset), paint)
                }
            } else {
                val overlayResolved = ImageEditCommon.resolveSource(io, arguments, "image")
                overlayResolved.error?.let { return@withContext ToolResult(false, it) }
                val decodedOverlay = ImageEditCommon.decodeCapped(overlayResolved.file!!)
                    ?: return@withContext ToolResult(
                        false,
                        "Не удалось декодировать картинку-знак: «${overlayResolved.file.name}».",
                    )
                overlay = decodedOverlay.bitmap
                val targetWidth = (sizeArg ?: (source.width / 5).coerceAtLeast(1)).coerceAtLeast(1)
                val targetHeight = (overlay.height.toDouble() * targetWidth / overlay.width)
                    .roundToInt().coerceAtLeast(1)
                val sealed = if (overlay.width == targetWidth && overlay.height == targetHeight) {
                    overlay
                } else {
                    Bitmap.createScaledBitmap(overlay, targetWidth, targetHeight, true).also {
                        scaledOverlay = it
                    }
                }
                paint.alpha = alpha
                markWidth = sealed.width
                markHeight = sealed.height
                if (tiled) {
                    val gap = (maxOf(markWidth, markHeight) / 2).coerceAtLeast(8)
                    ImageEditParsing.tileOrigins(
                        canvas.width, canvas.height, markWidth + gap, markHeight + gap, margin,
                    ).forEach { origin ->
                        canvas.drawBitmap(sealed, origin[0].toFloat(), origin[1].toFloat(), paint)
                    }
                } else {
                    val origin = ImageEditParsing.watermarkOrigin(
                        position, canvas.width, canvas.height, markWidth, markHeight, margin,
                    )
                    canvas.drawBitmap(sealed, origin[0].toFloat(), origin[1].toFloat(), paint)
                }
            }

            val error = ImageEditCommon.writeBitmap(dest, result)
            if (error != null) return@withContext ToolResult(false, error)

            ToolResult(true, ImageEditCommon.successMessage(dest, result, decoded.downscaled))
        } catch (e: OutOfMemoryError) {
            ToolResult(false, "Недостаточно памяти для обработки изображения.")
        } catch (e: Exception) {
            Log.e("MediaToolsHandler", "image_watermark failed", e)
            ToolResult(false, "Не удалось нанести водяной знак: ${e.message}")
        } finally {
            if (scaledOverlay !== overlay) scaledOverlay?.recycle()
            overlay?.recycle()
            if (result !== source) result?.recycle()
            source?.recycle()
        }
    }
}
