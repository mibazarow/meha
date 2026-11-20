package com.andharness.ru.data.tools.media

import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `exif_read`: reports the EXIF metadata of an image file. */
internal class ExifReadToolHandler(private val io: MediaPathIo) : ToolHandler {
    override val name = "exif_read"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val file = io.imageFile(arguments)
            if (!file.exists()) {
                return@withContext ToolResult(false, "Файл не найден: «${file.name}».")
            }
            if (!file.isFile) {
                return@withContext ToolResult(false, "Не файл: «${file.name}».")
            }
            // ExifInterface(String) throws IOException when the file cannot be parsed as a
            // supported image (JPEG/PNG/WebP/HEIF/AVIF); that is reported honestly below.
            // Reference: androidx.exifinterface.media.ExifInterface(String) and
            // getAttribute(String) —
            // https://developer.android.com/reference/androidx/exifinterface/media/ExifInterface
            val exif = ExifInterface(file.absolutePath)
            val lines = ArrayList<String>()

            fun add(label: String, tag: String) {
                val value = exif.getAttribute(tag)
                if (!value.isNullOrBlank()) lines.add("$label: $value")
            }

            add("Дата съёмки", ExifInterface.TAG_DATETIME_ORIGINAL)
            add("Дата", ExifInterface.TAG_DATETIME)
            add("Производитель", ExifInterface.TAG_MAKE)
            add("Модель", ExifInterface.TAG_MODEL)
            add("Ориентация", ExifInterface.TAG_ORIENTATION)
            add("Ширина", ExifInterface.TAG_IMAGE_WIDTH)
            add("Высота", ExifInterface.TAG_IMAGE_LENGTH)
            // JPEGs usually keep the real dimensions in the EXIF IFD
            // (PixelXDimension/PixelYDimension), so fall back to those tags.
            if (exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH).isNullOrBlank()) {
                add("Ширина", ExifInterface.TAG_PIXEL_X_DIMENSION)
            }
            if (exif.getAttribute(ExifInterface.TAG_IMAGE_LENGTH).isNullOrBlank()) {
                add("Высота", ExifInterface.TAG_PIXEL_Y_DIMENSION)
            }
            add("Широта", ExifInterface.TAG_GPS_LATITUDE)
            add("Широта (полушарие)", ExifInterface.TAG_GPS_LATITUDE_REF)
            add("Долгота", ExifInterface.TAG_GPS_LONGITUDE)
            add("Долгота (полушарие)", ExifInterface.TAG_GPS_LONGITUDE_REF)

            // Decimal coordinates, when the GPS block is present and parseable.
            // Reference: getLatLong() —
            // https://developer.android.com/reference/androidx/exifinterface/media/ExifInterface
            val latLong = exif.getLatLong()
            if (latLong != null && latLong.size == 2) {
                lines.add("Координаты: ${latLong[0]}, ${latLong[1]}")
            }

            if (lines.isEmpty()) {
                ToolResult(true, "EXIF-теги не найдены (файл прочитан как изображение, но метаданных нет).")
            } else {
                val header = "EXIF для «${file.name}»:"
                ToolResult(true, (listOf(header) + lines).joinToString("\n"))
            }
        } catch (e: Exception) {
            Log.e("MediaToolsHandler", "exif_read failed", e)
            ToolResult(false, "Не удалось прочитать EXIF: ${e.message}")
        }
    }
}
