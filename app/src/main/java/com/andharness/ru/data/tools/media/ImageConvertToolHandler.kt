package com.andharness.ru.data.tools.media

import android.util.Log
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `image_convert`: converts an image to another format. */
internal class ImageConvertToolHandler(
    private val transformer: MediaImageTransformer,
) : ToolHandler {
    override val name = "image_convert"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            transformer.transformImage(arguments)
        } catch (e: OutOfMemoryError) {
            ToolResult(false, "Недостаточно памяти для обработки изображения.")
        } catch (e: Exception) {
            Log.e("MediaToolsHandler", "image_convert failed", e)
            ToolResult(false, "Не удалось конвертировать изображение: ${e.message}")
        }
    }
}
