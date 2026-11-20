package com.andharness.ru.data.tools.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.math.ceil

/**
 * `image_rotate`: rotates by an arbitrary angle and/or mirrors an image, writing a NEW file.
 *
 * A single [Matrix] carries the rotation and the optional horizontal/vertical mirror; the four
 * source corners are mapped through it to size the output bitmap, then translated into place and
 * drawn onto a [Canvas] that may be pre-filled with the `background` colour (needed for angles that
 * are not multiples of 90°). References:
 * - `Matrix.postRotate(float)` / `postScale(float, float)` / `mapPoints(float[])` /
 *   `postTranslate(float, float)` — https://developer.android.com/reference/android/graphics/Matrix
 * - `Canvas.drawBitmap(Bitmap, Matrix, Paint)` / `Canvas.drawColor(int)` —
 *   https://developer.android.com/reference/android/graphics/Canvas
 */
internal class ImageRotateToolHandler(private val io: MediaPathIo) : ToolHandler {
    override val name = "image_rotate"

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

            val angle = try {
                ImageEditParsing.optInt(arguments, "angle") ?: 0
            } catch (e: IllegalArgumentException) {
                return@withContext ToolResult(false, e.message ?: "Некорректный angle.")
            }
            val flip = try {
                ImageEditParsing.parseFlip(ImageEditParsing.optString(arguments, "flip"))
            } catch (e: IllegalArgumentException) {
                return@withContext ToolResult(false, e.message ?: "Некорректный flip.")
            }
            val mirrored = ImageEditParsing.flipHorizontal(flip) || ImageEditParsing.flipVertical(flip)
            if (angle % 360 == 0 && !mirrored) {
                return@withContext ToolResult(false, "Укажи angle (например 90/180/270) или flip (h/v/both).")
            }

            val background = ImageEditParsing.optString(arguments, "background")
            val backgroundColor = try {
                background?.let { ImageEditParsing.parseColor(it) }
            } catch (e: IllegalArgumentException) {
                return@withContext ToolResult(false, e.message ?: "Некорректный background.")
            }

            val decoded = ImageEditCommon.decodeCapped(src)
                ?: return@withContext ToolResult(
                    false,
                    "Не удалось декодировать изображение: «${src.name}» (формат или файл повреждён).",
                )
            source = decoded.bitmap

            val matrix = Matrix()
            if (angle % 360 != 0) matrix.postRotate(angle.toFloat())
            if (ImageEditParsing.flipHorizontal(flip)) matrix.postScale(-1f, 1f)
            if (ImageEditParsing.flipVertical(flip)) matrix.postScale(1f, -1f)

            val points = floatArrayOf(
                0f, 0f,
                source.width.toFloat(), 0f,
                source.width.toFloat(), source.height.toFloat(),
                0f, source.height.toFloat(),
            )
            matrix.mapPoints(points)
            val minX = minOf(points[0], points[2], points[4], points[6])
            val maxX = maxOf(points[0], points[2], points[4], points[6])
            val minY = minOf(points[1], points[3], points[5], points[7])
            val maxY = maxOf(points[1], points[3], points[5], points[7])
            val outWidth = ceil(maxX - minX).toInt().coerceAtLeast(1)
            val outHeight = ceil(maxY - minY).toInt().coerceAtLeast(1)
            matrix.postTranslate(-minX, -minY)

            result = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            if (backgroundColor != null) canvas.drawColor(backgroundColor)
            canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))

            val error = ImageEditCommon.writeBitmap(dest, result)
            if (error != null) return@withContext ToolResult(false, error)

            ToolResult(true, ImageEditCommon.successMessage(dest, result, decoded.downscaled))
        } catch (e: OutOfMemoryError) {
            ToolResult(false, "Недостаточно памяти для обработки изображения.")
        } catch (e: Exception) {
            Log.e("MediaToolsHandler", "image_rotate failed", e)
            ToolResult(false, "Не удалось повернуть изображение: ${e.message}")
        } finally {
            if (result !== source) result?.recycle()
            source?.recycle()
        }
    }
}
