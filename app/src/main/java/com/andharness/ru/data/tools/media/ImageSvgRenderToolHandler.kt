package com.andharness.ru.data.tools.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileInputStream

/**
 * `image_svg_render`: rasterises a static SVG (from a file or an app asset) to a PNG at a requested
 * width/height and writes a NEW file.
 *
 * Source: exactly one of `src` (shared-storage path) or `asset` (app asset name). Sizing is decided
 * by the pure [ImageExtrasMath.svgTargetSize]. Rendering uses AndroidSVG; it renders static SVG and
 * silently ignores unsupported constructs (SVG filters, some CSS), which the tool description
 * states honestly.
 *
 * References:
 * - AndroidSVG `SVG.getFromInputStream(InputStream)` / `SVG.getFromAsset(AssetManager, String)` /
 *   `SVG.getDocumentWidth()` / `getDocumentHeight()` / `SVG.renderToPicture(int, int)` —
 *   https://github.com/BigBadaboom/androidsvg
 * - `Bitmap.createBitmap(int, int, Config)`, `Canvas(Bitmap)`, `Canvas.drawPicture(Picture)` —
 *   https://developer.android.com/reference/android/graphics/Canvas#drawPicture(android.graphics.Picture)
 */
internal class ImageSvgRenderToolHandler(
    private val context: Context,
    private val io: MediaPathIo,
) : ToolHandler {

    override val name = "image_svg_render"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val assetName = ImageEditParsing.optString(arguments, "asset")
        val srcArg = ImageEditParsing.optString(arguments, "src")
        if (assetName == null && srcArg == null) {
            return@withContext ToolResult(false, "Укажи src (путь к .svg) или asset (встроенный ресурс).")
        }
        if (assetName != null && srcArg != null) {
            return@withContext ToolResult(false, "Укажи только одно: src или asset.")
        }

        val destResolved = ImageEditCommon.prepareDestination(io, arguments, "dest")
        destResolved.error?.let { return@withContext ToolResult(false, it) }
        val dest = destResolved.file!!

        var sourceFile: File? = null
        if (srcArg != null) {
            val resolved = ImageEditCommon.resolveSource(io, arguments, "src")
            resolved.error?.let { return@withContext ToolResult(false, it) }
            val resolvedFile = resolved.file!!
            sourceFile = resolvedFile
            if (!resolvedFile.extension.equals("svg", ignoreCase = true)) {
                return@withContext ToolResult(false, "Файл источника не SVG: «${resolvedFile.name}».")
            }
        }

        val width = try {
            ImageEditParsing.optInt(arguments, "width", min = 1, max = ImageExtrasMath.MAX_RENDER_SIDE)
        } catch (e: IllegalArgumentException) {
            return@withContext ToolResult(false, e.message ?: "Некорректный width.")
        }
        val height = try {
            ImageEditParsing.optInt(arguments, "height", min = 1, max = ImageExtrasMath.MAX_RENDER_SIDE)
        } catch (e: IllegalArgumentException) {
            return@withContext ToolResult(false, e.message ?: "Некорректный height.")
        }

        var bitmap: Bitmap? = null
        try {
            val svg = try {
                if (assetName != null) {
                    SVG.getFromAsset(context.assets, assetName)
                } else {
                    FileInputStream(sourceFile!!).use { SVG.getFromInputStream(it) }
                }
            } catch (e: Exception) {
                return@withContext ToolResult(
                    false,
                    "Не удалось разобрать SVG (${assetName ?: sourceFile?.name}): ${e.message}",
                )
            }

            val target = ImageExtrasMath.svgTargetSize(width, height, svg.documentWidth, svg.documentHeight)

            val picture = try {
                svg.renderToPicture(target[0], target[1])
            } catch (e: Exception) {
                return@withContext ToolResult(false, "Не удалось отрисовать SVG: ${e.message}")
            }

            val rendered = Bitmap.createBitmap(target[0], target[1], Bitmap.Config.ARGB_8888)
            bitmap = rendered
            Canvas(rendered).drawPicture(picture)

            val error = ImageEditCommon.writeBitmap(dest, rendered)
            if (error != null) return@withContext ToolResult(false, error)

            ToolResult(
                true,
                "SVG отрисован: «${dest.absolutePath}» (${target[0]}x${target[1]} px, " +
                    "${dest.length()} байт). Неподдерживаемые возможности SVG (фильтры, часть CSS) " +
                    "пропущены.",
            )
        } catch (e: OutOfMemoryError) {
            ToolResult(false, "Недостаточно памяти для отрисовки SVG.")
        } catch (e: Exception) {
            Log.e("MediaToolsHandler", "image_svg_render failed", e)
            ToolResult(false, "Не удалось отрисовать SVG: ${e.message}")
        } finally {
            bitmap?.recycle()
        }
    }
}
