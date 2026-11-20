package com.andharness.ru.data.tools.media

import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.FileOutputStream

/** `image_thumbnail`: writes a square PNG thumbnail of an image or video file. */
internal class ImageThumbnailToolHandler(
    private val io: MediaPathIo,
    private val transformer: MediaImageTransformer,
) : ToolHandler {
    override val name = "image_thumbnail"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        var source: Bitmap? = null
        var thumbnail: Bitmap? = null
        try {
            val sizeRaw = ((arguments["size"] as? JsonPrimitive)?.content ?: "").trim()
            val requested = if (sizeRaw.isEmpty()) DEFAULT_THUMBNAIL_SIZE else {
                sizeRaw.toIntOrNull()
                    ?: return@withContext ToolResult(false, "Некорректный size: «$sizeRaw».")
            }
            if (requested < MIN_THUMBNAIL_SIZE) {
                return@withContext ToolResult(
                    false,
                    "size должен быть не меньше $MIN_THUMBNAIL_SIZE пикселей.",
                )
            }
            val size = requested.coerceAtMost(MAX_THUMBNAIL_SIZE)

            val input = io.imageFile(arguments)
            val destination = io.pathFile(arguments, "destination")
            if (!input.exists()) {
                return@withContext ToolResult(false, "Файл не найден: «${input.name}».")
            }
            if (!input.isFile) {
                return@withContext ToolResult(false, "Не файл: «${input.name}».")
            }
            if (destination.exists()) {
                return@withContext ToolResult(
                    false,
                    "Файл назначения уже существует: «${destination.absolutePath}». Перезапись запрещена.",
                )
            }
            val parent = destination.parentFile
            if (parent == null || (!parent.exists() && !parent.mkdirs())) {
                return@withContext ToolResult(
                    false,
                    "Не удалось создать каталог для «${destination.absolutePath}».",
                )
            }

            val isVideo = input.extension.lowercase() in VIDEO_EXTENSIONS
            // Image path: bounds-only decode first (inJustDecodeBounds) so the full-size
            // bitmap is never loaded, then a downsampled decode (inSampleSize) and
            // ThumbnailUtils.extractThumbnail for the final square crop/scale.
            // Video path: ThumbnailUtils.createVideoThumbnail.
            // References:
            // https://developer.android.com/reference/android/graphics/BitmapFactory
            // https://developer.android.com/reference/android/media/ThumbnailUtils
            if (isVideo) {
                // createVideoThumbnail(File, Size, CancellationSignal) is API 29+; below Q
                // only the deprecated createVideoThumbnail(String, int) exists.
                val rawVideo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ThumbnailUtils.createVideoThumbnail(input, Size(size, size), null)
                } else {
                    @Suppress("DEPRECATION")
                    ThumbnailUtils.createVideoThumbnail(
                        input.absolutePath,
                        MediaStore.Video.Thumbnails.MINI_KIND,
                    )
                }
                if (rawVideo == null) {
                    return@withContext ToolResult(
                        false,
                        "Не удалось создать миниатюру видео: «${input.name}» (формат не поддержан или файл повреждён).",
                    )
                }
                source = rawVideo
            } else {
                val decoded = transformer.decodeSampledBitmap(input, size)
                if (decoded == null) {
                    return@withContext ToolResult(
                        false,
                        "Не удалось декодировать изображение: «${input.name}» (неподдерживаемый формат или повреждённый файл).",
                    )
                }
                source = decoded
            }

            val bitmap = source
            val result = ThumbnailUtils.extractThumbnail(bitmap, size, size)
            thumbnail = result

            FileOutputStream(destination).use { out ->
                if (!result.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    return@withContext ToolResult(
                        false,
                        "Не удалось записать PNG: «${destination.absolutePath}».",
                    )
                }
            }

            // POST-VERIFY: the destination must exist and be non-empty.
            if (!destination.exists() || destination.length() <= 0L) {
                destination.delete()
                return@withContext ToolResult(
                    false,
                    "Миниатюра не записана (файл пуст или отсутствует): «${destination.absolutePath}».",
                )
            }

            ToolResult(
                true,
                "Миниатюра сохранена: «${destination.absolutePath}» " +
                    "(${result.width}x${result.height} px, ${destination.length()} байт).",
            )
        } catch (e: Exception) {
            Log.e("MediaToolsHandler", "image_thumbnail failed", e)
            ToolResult(false, "Не удалось создать миниатюру: ${e.message}")
        } finally {
            thumbnail?.recycle()
            if (source !== thumbnail) {
                source?.recycle()
            }
        }
    }

    private companion object {
        /** `image_thumbnail` default larger-side size in pixels. */
        private const val DEFAULT_THUMBNAIL_SIZE = 256

        /** `image_thumbnail` minimum accepted `size`. */
        private const val MIN_THUMBNAIL_SIZE = 16

        /** `image_thumbnail` upper bound for `size`, to keep memory use sane. */
        private const val MAX_THUMBNAIL_SIZE = 1024

        /**
         * Extensions handled through the video branch of `image_thumbnail`. Anything else goes
         * through the image branch (BitmapFactory + extractThumbnail).
         */
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "m4v", "mkv", "webm", "3gp", "3g2", "avi", "mov", "wmv", "flv", "ts", "mpe", "mpeg", "mpg",
        )
    }
}
