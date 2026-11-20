package com.andharness.ru.data.tools.vision

import android.content.Context
import android.graphics.BitmapFactory
import com.andharness.ru.data.tools.allowedToolRoots
import com.andharness.ru.data.tools.resolveSafePath
import com.andharness.ru.data.vision.OcrRecognizer
import com.andharness.ru.data.vision.VisionCapability
import com.andharness.ru.data.vision.VisionPolicy
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Builds the [ocr_image] tool (on-device text recognition through the shared [OcrRecognizer],
 * which uses the offline Tesseract engine for Cyrillic and falls back to ML Kit Latin) plus the
 * Vision capability tools of update.md §3: [vision_status] (what is known about the current model
 * and why) and [vision_check] (force a fresh probe).
 *
 * Engine reference: https://github.com/adaptech-cz/Tesseract4Android
 * ML Kit Latin fallback: https://developers.google.com/ml-kit/vision/text-recognition/v2/android
 */
class VisionToolsHandler(
    private val context: Context,
    private val visionCapability: VisionCapability,
    private val ocrRecognizer: OcrRecognizer,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "ocr_image"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                val pathArg = (arguments["path"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                    ?: return@withContext ToolResult(false, "Укажите path к изображению.")
                try {
                    val file = try {
                        resolveSafePath(context.allowedToolRoots(), pathArg)
                    } catch (e: IllegalArgumentException) {
                        return@withContext ToolResult(false, e.message ?: "Некорректный путь.")
                    }
                    if (!file.isFile) return@withContext ToolResult(false, "Файл не найден: «$pathArg».")
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                        ?: return@withContext ToolResult(false, "Не удалось прочитать изображение.")
                    try {
                        val result = ocrRecognizer.recognize(bitmap)
                        val text = result.text.ifBlank { "Текст на изображении не найден." }
                        ToolResult(true, "$text\n\n(${result.statusLine})")
                    } finally {
                        bitmap.recycle()
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "vision_status"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    ToolResult(true, VisionPolicy.describe(visionCapability.resolve()))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "vision_check"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    // Force a real probe regardless of the allowlist/cache, then report honestly.
                    ToolResult(true, VisionPolicy.describe(visionCapability.resolve(forceProbe = true)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
    )
}
