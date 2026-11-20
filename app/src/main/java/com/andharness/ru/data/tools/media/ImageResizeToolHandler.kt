package com.andharness.ru.data.tools.media

import android.util.Log
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `image_resize`: scales an image to the requested size. */
internal class ImageResizeToolHandler(
    private val transformer: MediaImageTransformer,
) : ToolHandler {
    override val name = "image_resize"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            transformer.transformImage(arguments)
        } catch (e: OutOfMemoryError) {
            ToolResult(false, "Недостаточно памяти для обработки изображения.")
        } catch (e: Exception) {
            Log.e("MediaToolsHandler", "image_resize failed", e)
            ToolResult(false, "Не удалось изменить размер изображения: ${e.message}")
        }
    }
}
