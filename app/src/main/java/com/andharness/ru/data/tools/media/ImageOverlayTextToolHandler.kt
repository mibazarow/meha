package com.andharness.ru.data.tools.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * `image_overlay_text`: draws arbitrary text (size, colour, alignment, optional background box)
 * onto an image and writes a NEW file.
 *
 * Position comes either from explicit `x`/`y` (pixels; `align` shifts text around the x anchor) or
 * from `position` (a corner/center, placed with [ImageEditParsing.watermarkOrigin]). `padding` is
 * the background-box inset and the placement margin. References:
 * - `Canvas.drawText(String, float, float, Paint)` / `drawRect(RectF, Paint)` —
 *   https://developer.android.com/reference/android/graphics/Canvas
 * - `Paint.setTextAlign(Paint.Align)` / `setTextSize(float)` / `measureText(String)` —
 *   https://developer.android.com/reference/android/graphics/Paint
 */
internal class ImageOverlayTextToolHandler(private val io: MediaPathIo) : ToolHandler {
    override val name = "image_overlay_text"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        var source: Bitmap? = null
        var result: Bitmap? = null
        try {
            val text = ImageEditParsing.optString(arguments, "text")
                ?: return@withContext ToolResult(false, "Укажи text — текст для нанесения.")
            val srcResolved = ImageEditCommon.resolveSource(io, arguments, "src")
            srcResolved.error?.let { return@withContext ToolResult(false, it) }
            val src = srcResolved.file!!
            val destResolved = ImageEditCommon.prepareDestination(io, arguments, "dest")
            destResolved.error?.let { return@withContext ToolResult(false, it) }
            val dest = destResolved.file!!

            val position = ImageEditParsing.optString(arguments, "position")
            val color: Int
            val background: Int?
            val opacity: Double
            val padding: Int
            val sizeArg: Int?
            val align: String
            val xArg: Int?
            val yArg: Int?
            try {
                color = ImageEditParsing.parseColor(
                    ImageEditParsing.optString(arguments, "color"),
                    0xFFFFFFFF.toInt(),
                )
                background = ImageEditParsing.optString(arguments, "background")?.let {
                    ImageEditParsing.parseColor(it)
                }
                opacity = ImageEditParsing.optDoubleClamped(arguments, "opacity", 0.0, 1.0, 1.0)
                padding = ImageEditParsing.optInt(arguments, "padding", min = 0) ?: 8
                sizeArg = ImageEditParsing.optInt(arguments, "size", min = 1)
                align = ImageEditParsing.parseAlign(ImageEditParsing.optString(arguments, "align"))
                xArg = ImageEditParsing.optInt(arguments, "x")
                yArg = ImageEditParsing.optInt(arguments, "y")
            } catch (e: IllegalArgumentException) {
                return@withContext ToolResult(false, e.message ?: "Некорректные параметры текста.")
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

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                alpha = (opacity * 255.0).roundToInt().coerceIn(0, 255)
                textSize = (sizeArg ?: (maxOf(source.width, source.height) / 20f).roundToInt())
                    .coerceAtLeast(8).toFloat()
            }
            val metrics = paint.fontMetrics
            val textWidth = ceil(paint.measureText(text)).toInt().coerceAtLeast(1)
            val textHeight = ceil(metrics.descent - metrics.ascent).toInt().coerceAtLeast(1)

            val originX: Int
            val originY: Int
            if (xArg != null || yArg != null) {
                val anchorX = xArg ?: 0
                originX = when (align) {
                    "center" -> anchorX - textWidth / 2
                    "right" -> anchorX - textWidth
                    else -> anchorX
                }
                originY = yArg ?: 0
            } else {
                val placed = ImageEditParsing.watermarkOrigin(
                    position, canvas.width, canvas.height, textWidth, textHeight, padding,
                )
                originX = placed[0]
                originY = placed[1]
            }

            if (background != null) {
                val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = background
                    style = Paint.Style.FILL
                }
                canvas.drawRect(
                    RectF(
                        (originX - padding).toFloat(),
                        (originY - padding).toFloat(),
                        (originX + textWidth + padding).toFloat(),
                        (originY + textHeight + padding).toFloat(),
                    ),
                    box,
                )
            }
            canvas.drawText(text, originX.toFloat(), originY - metrics.ascent, paint)

            val error = ImageEditCommon.writeBitmap(dest, result)
            if (error != null) return@withContext ToolResult(false, error)

            ToolResult(true, ImageEditCommon.successMessage(dest, result, decoded.downscaled))
        } catch (e: OutOfMemoryError) {
            ToolResult(false, "Недостаточно памяти для обработки изображения.")
        } catch (e: Exception) {
            Log.e("MediaToolsHandler", "image_overlay_text failed", e)
            ToolResult(false, "Не удалось нанести текст: ${e.message}")
        } finally {
            if (result !== source) result?.recycle()
            source?.recycle()
        }
    }
}
