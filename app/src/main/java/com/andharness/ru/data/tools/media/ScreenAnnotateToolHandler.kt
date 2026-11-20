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
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * `screen_annotate`: draws a list of shapes (rectangles, ellipses, arrows, text, highlights) from a
 * JSON `shapes` array onto a source image (e.g. a screenshot) and writes a NEW file.
 *
 * The source is never modified: the annotations are drawn on a mutable ARGB copy. Shape parsing is
 * pure ([ImageEditParsing.parseShapes]); this handler only paints. References:
 * - `Canvas.drawRect(RectF, Paint)` / `drawOval(RectF, Paint)` / `drawLine(...)` / `drawText(...)` —
 *   https://developer.android.com/reference/android/graphics/Canvas
 * - `Paint`, `Paint.Style.STROKE`/`FILL`, `Paint.setStrokeWidth(float)` —
 *   https://developer.android.com/reference/android/graphics/Paint
 */
internal class ScreenAnnotateToolHandler(private val io: MediaPathIo) : ToolHandler {
    override val name = "screen_annotate"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        var source: Bitmap? = null
        var result: Bitmap? = null
        try {
            val shapesRaw = ImageEditParsing.optString(arguments, "shapes")
                ?: return@withContext ToolResult(false, "Укажи shapes — JSON-массив фигур.")
            val shapes = try {
                ImageEditParsing.parseShapes(shapesRaw)
            } catch (e: IllegalArgumentException) {
                return@withContext ToolResult(false, e.message ?: "Некорректные shapes.")
            }

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

            val mutable = source.copy(Bitmap.Config.ARGB_8888, true)
            if (mutable != null) {
                result = mutable
            } else {
                result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                Canvas(result).drawBitmap(source, 0f, 0f, null)
            }
            val canvas = Canvas(result)

            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

            for (shape in shapes) {
                when (shape) {
                    is HighlightShape -> {
                        fill.color = shape.color
                        canvas.drawRect(
                            RectF(shape.x, shape.y, shape.x + shape.width, shape.y + shape.height),
                            fill,
                        )
                    }
                    is RectShape -> {
                        val rect = RectF(shape.x, shape.y, shape.x + shape.width, shape.y + shape.height)
                        if (shape.filled) {
                            fill.color = shape.color
                            canvas.drawRect(rect, fill)
                        } else {
                            stroke.color = shape.color
                            stroke.strokeWidth = shape.strokeWidth
                            canvas.drawRect(rect, stroke)
                        }
                    }
                    is EllipseShape -> {
                        val rect = RectF(shape.x, shape.y, shape.x + shape.width, shape.y + shape.height)
                        if (shape.filled) {
                            fill.color = shape.color
                            canvas.drawOval(rect, fill)
                        } else {
                            stroke.color = shape.color
                            stroke.strokeWidth = shape.strokeWidth
                            canvas.drawOval(rect, stroke)
                        }
                    }
                    is ArrowShape -> drawArrow(canvas, shape, stroke)
                    is TextShape -> {
                        textPaint.color = shape.color
                        textPaint.textSize = shape.size.coerceAtLeast(8f)
                        canvas.drawText(shape.text, shape.x, shape.y, textPaint)
                    }
                }
            }

            val error = ImageEditCommon.writeBitmap(dest, result)
            if (error != null) return@withContext ToolResult(false, error)

            ToolResult(true, ImageEditCommon.successMessage(dest, result, decoded.downscaled))
        } catch (e: OutOfMemoryError) {
            ToolResult(false, "Недостаточно памяти для обработки изображения.")
        } catch (e: Exception) {
            Log.e("MediaToolsHandler", "screen_annotate failed", e)
            ToolResult(false, "Не удалось нанести аннотации: ${e.message}")
        } finally {
            if (result !== source) result?.recycle()
            source?.recycle()
        }
    }

    /** Draws the arrow shaft plus a two-barb head at `(x2, y2)`. */
    private fun drawArrow(canvas: Canvas, shape: ArrowShape, stroke: Paint) {
        stroke.color = shape.color
        stroke.strokeWidth = shape.strokeWidth
        canvas.drawLine(shape.x1, shape.y1, shape.x2, shape.y2, stroke)
        val angle = atan2((shape.y2 - shape.y1).toDouble(), (shape.x2 - shape.x1).toDouble())
        val headLength = max(12f, shape.strokeWidth * 4f)
        val spread = 0.5
        for (sign in intArrayOf(-1, 1)) {
            val barbAngle = angle + PI + sign * spread
            val bx = shape.x2 + (headLength * cos(barbAngle)).toFloat()
            val by = shape.y2 + (headLength * sin(barbAngle)).toFloat()
            canvas.drawLine(shape.x2, shape.y2, bx, by, stroke)
        }
    }
}
