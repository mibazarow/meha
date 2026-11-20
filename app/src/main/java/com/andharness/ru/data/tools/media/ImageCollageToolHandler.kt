package com.andharness.ru.data.tools.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * `image_collage`: lays N images out in a grid (auto rows/cols, spacing, background) and writes a
 * NEW file. Each source is scaled to FIT its cell preserving the aspect ratio and centred; the
 * cell size is derived from the largest source (or `cellWidth`/`cellHeight`) and shrunk so no output
 * side exceeds [ImageEditCommon.MAX_EDIT_SIDE]. References:
 * - `Canvas.drawBitmap(Bitmap, Rect, Rect, Paint)` (source→dest fit) —
 *   https://developer.android.com/reference/android/graphics/Canvas#drawBitmap(android.graphics.Bitmap,%20android.graphics.Rect,%20android.graphics.Rect,%20android.graphics.Paint)
 * - `Bitmap.createBitmap(int, int, Bitmap.Config)` —
 *   https://developer.android.com/reference/android/graphics/Bitmap#createBitmap(int,%20int,%20android.graphics.Bitmap.Config)
 */
internal class ImageCollageToolHandler(private val io: MediaPathIo) : ToolHandler {
    override val name = "image_collage"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val bitmaps = ArrayList<Bitmap>()
        var result: Bitmap? = null
        try {
            val paths = ImageEditParsing.splitPaths(ImageEditParsing.optString(arguments, "srcs"))
            if (paths.isEmpty()) {
                return@withContext ToolResult(false, "Укажи srcs — пути к изображениям через запятую.")
            }
            val files = ArrayList<File>(paths.size)
            for (raw in paths) {
                val resolved = try {
                    io.resolveMediaPath(raw)
                } catch (e: IllegalArgumentException) {
                    return@withContext ToolResult(false, e.message ?: "Некорректный путь «$raw».")
                }
                if (!resolved.exists() || !resolved.isFile) {
                    return@withContext ToolResult(false, "Файл не найден: «$raw».")
                }
                files.add(resolved)
            }
            val destResolved = ImageEditCommon.prepareDestination(io, arguments, "dest")
            destResolved.error?.let { return@withContext ToolResult(false, it) }
            val dest = destResolved.file!!

            val rowsArg: Int?
            val colsArg: Int?
            val spacing: Int
            val background: Int
            val cellWidthArg: Int?
            val cellHeightArg: Int?
            try {
                rowsArg = ImageEditParsing.optInt(arguments, "rows", min = 1)
                colsArg = ImageEditParsing.optInt(arguments, "cols", min = 1)
                spacing = ImageEditParsing.optInt(arguments, "spacing", min = 0) ?: 8
                background = ImageEditParsing.parseColor(
                    ImageEditParsing.optString(arguments, "background"),
                    0xFF000000.toInt(),
                )
                cellWidthArg = ImageEditParsing.optInt(arguments, "cellWidth", min = 1)
                cellHeightArg = ImageEditParsing.optInt(arguments, "cellHeight", min = 1)
            } catch (e: IllegalArgumentException) {
                return@withContext ToolResult(false, e.message ?: "Некорректные параметры коллажа.")
            }

            val grid = try {
                ImageEditParsing.grid(files.size, rowsArg, colsArg)
            } catch (e: IllegalArgumentException) {
                return@withContext ToolResult(false, e.message ?: "Некорректная сетка коллажа.")
            }
            val rows = grid[0]
            val cols = grid[1]

            var maxWidth = 1
            var maxHeight = 1
            for (file in files) {
                val bounds = ImageEditCommon.sourceBounds(file)
                    ?: return@withContext ToolResult(
                        false,
                        "Не удалось прочитать изображение: «${file.name}».",
                    )
                maxWidth = max(maxWidth, bounds[0])
                maxHeight = max(maxHeight, bounds[1])
            }

            var cellWidth = cellWidthArg ?: maxWidth
            var cellHeight = cellHeightArg ?: maxHeight
            val totalWidth = cols * cellWidth + (cols + 1) * spacing
            val totalHeight = rows * cellHeight + (rows + 1) * spacing
            val shrink = min(
                1f.toDouble(),
                min(
                    ImageEditCommon.MAX_EDIT_SIDE.toDouble() / totalWidth,
                    ImageEditCommon.MAX_EDIT_SIDE.toDouble() / totalHeight,
                ),
            )
            if (shrink < 1.0) {
                cellWidth = (cellWidth * shrink).roundToInt().coerceAtLeast(1)
                cellHeight = (cellHeight * shrink).roundToInt().coerceAtLeast(1)
            }
            val outWidth = cols * cellWidth + (cols + 1) * spacing
            val outHeight = rows * cellHeight + (rows + 1) * spacing

            val decodeCap = max(cellWidth, cellHeight)
            var downscaled = false
            for (file in files) {
                val decoded = ImageEditCommon.decodeCapped(file, decodeCap)
                    ?: return@withContext ToolResult(
                        false,
                        "Не удалось декодировать изображение: «${file.name}».",
                    )
                downscaled = downscaled || decoded.downscaled
                bitmaps.add(decoded.bitmap)
            }

            result = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            canvas.drawColor(background)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            bitmaps.forEachIndexed { index, bitmap ->
                val origin = ImageEditParsing.collageCell(index, cols, cellWidth, cellHeight, spacing)
                val scale = min(
                    cellWidth.toFloat() / bitmap.width,
                    cellHeight.toFloat() / bitmap.height,
                )
                val drawWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
                val drawHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
                val left = origin[0] + (cellWidth - drawWidth) / 2
                val top = origin[1] + (cellHeight - drawHeight) / 2
                canvas.drawBitmap(
                    bitmap,
                    Rect(0, 0, bitmap.width, bitmap.height),
                    Rect(left, top, left + drawWidth, top + drawHeight),
                    paint,
                )
            }

            val error = ImageEditCommon.writeBitmap(dest, result)
            if (error != null) return@withContext ToolResult(false, error)

            ToolResult(true, ImageEditCommon.successMessage(dest, result, downscaled))
        } catch (e: OutOfMemoryError) {
            ToolResult(false, "Недостаточно памяти для сборки коллажа.")
        } catch (e: Exception) {
            Log.e("MediaToolsHandler", "image_collage failed", e)
            ToolResult(false, "Не удалось собрать коллаж: ${e.message}")
        } finally {
            bitmaps.forEach { it.recycle() }
            result?.recycle()
        }
    }
}
