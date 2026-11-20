package com.andharness.ru.data.tools.media

import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `exif_write`: writes one allow-listed EXIF tag and verifies it persisted. */
internal class ExifWriteToolHandler(private val io: MediaPathIo) : ToolHandler {
    override val name = "exif_write"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val rawTag = (arguments["tag"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val value = (arguments["value"] as? JsonPrimitive)?.content.orEmpty()
            if (rawTag.isEmpty()) {
                return@withContext ToolResult(false, "Укажите tag.")
            }
            // Accept both the short Android/EXIF name (`DateTimeOriginal`) and the
            // `ExifInterface.TAG_*` constant name (`TAG_DATETIME_ORIGINAL`), any case.
            val tag = ExifTagNormalizer.normalize(rawTag)
            if (tag == null || tag !in WRITABLE_EXIF_TAGS) {
                return@withContext ToolResult(
                    false,
                    "Недопустимый EXIF-тег «$rawTag». Разрешены (регистр не важен): " +
                        "${ExifTagNormalizer.acceptedForms}."
                )
            }
            val file = io.imageFile(arguments)
            if (!file.exists()) {
                return@withContext ToolResult(false, "Файл не найден: «${file.name}».")
            }
            if (!file.isFile) {
                return@withContext ToolResult(false, "Не файл: «${file.name}».")
            }

            // setAttribute(String, String) + saveAttributes() persist the tag back into the
            // image file. Reference:
            // https://developer.android.com/reference/androidx/exifinterface/media/ExifInterface
            ExifInterface(file.absolutePath).apply {
                setAttribute(tag, value)
                saveAttributes()
            }

            // POST-VERIFY: re-open the file and compare the persisted attribute.
            val persisted = ExifInterface(file.absolutePath).getAttribute(tag)
            if (persisted == value) {
                ToolResult(true, "Тег $tag записан: $persisted")
            } else {
                ToolResult(
                    false,
                    "Не удалось подтвердить запись тега $tag: в файле «${persisted ?: "(нет)"}», ожидалось «$value»."
                )
            }
        } catch (e: Exception) {
            Log.e("MediaToolsHandler", "exif_write failed", e)
            ToolResult(false, "Не удалось записать EXIF: ${e.message}")
        }
    }

    private companion object {
        /**
         * Tags `exif_write` accepts. Kept to tags whose `setAttribute` value is a plain string and
         * whose meaning is stable, so the tool never corrupts unrelated EXIF structure. Input is
         * normalized by [ExifTagNormalizer] (short name or `TAG_*` constant name, any case); the
         * normalized value is still validated against this allow-list.
         * Tag names/values verified against
         * https://developer.android.com/reference/androidx/exifinterface/media/ExifInterface
         */
        private val WRITABLE_EXIF_TAGS = listOf(
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
        )
    }
}
