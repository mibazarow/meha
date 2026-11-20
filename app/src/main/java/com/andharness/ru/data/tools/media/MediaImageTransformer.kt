package com.andharness.ru.data.tools.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.andharness.ru.domain.agent.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Bitmap decode/scale/encode pipeline shared by `image_thumbnail`, `image_resize` and
 * `image_convert`: bounds-first decoding, `inSampleSize` downsampling, optional scaling and
 * format-specific encoding, with a non-empty post-write verification.
 *
 * References:
 * - `BitmapFactory.Options.inJustDecodeBounds` / `outWidth` / `outHeight` / `inSampleSize` and
 *   `BitmapFactory.decodeFile(String, Options)` —
 *   https://developer.android.com/reference/android/graphics/BitmapFactory.Options
 * - `Bitmap.createScaledBitmap(Bitmap, int, int, boolean)` and
 *   `Bitmap.compress(CompressFormat, int, OutputStream)` —
 *   https://developer.android.com/reference/android/graphics/Bitmap
 */
internal class MediaImageTransformer(private val io: MediaPathIo) {

    /**
     * Shared implementation of `image_resize` and `image_convert`: decodes `src` bounds-first,
     * downscales it with `inSampleSize`, scales it to the requested size (keeping the aspect ratio
     * when only one dimension is given) and writes `dest` in the requested format.
     *
     * Both paths are resolved with the tool root, so an absolute path outside the allowed storage
     * root is rejected. The destination is never overwritten, and the result is verified to exist
     * and be non-empty before reporting success.
     */
    fun transformImage(arguments: JsonObject): ToolResult {
        val src = try {
            io.resolveMediaPath((arguments["src"] as? JsonPrimitive)?.content.orEmpty())
        } catch (e: IllegalArgumentException) {
            return ToolResult(false, e.message ?: "Некорректный путь источника.")
        }
        val dest = try {
            io.resolveMediaPath((arguments["dest"] as? JsonPrimitive)?.content.orEmpty())
        } catch (e: IllegalArgumentException) {
            return ToolResult(false, e.message ?: "Некорректный путь назначения.")
        }
        if (!src.exists()) {
            return ToolResult(false, "Файл не найден: «${src.name}».")
        }
        if (!src.isFile) {
            return ToolResult(false, "Не файл: «${src.name}».")
        }
        if (dest.exists()) {
            return ToolResult(
                false,
                "Файл назначения уже существует: «${dest.absolutePath}». Перезапись запрещена.",
            )
        }

        val format = try {
            parseImageFormat((arguments["format"] as? JsonPrimitive)?.content, dest)
        } catch (e: IllegalArgumentException) {
            return ToolResult(false, e.message ?: "Некорректный формат.")
        }
        val quality = try {
            parseImageQuality((arguments["quality"] as? JsonPrimitive)?.content)
        } catch (e: IllegalArgumentException) {
            return ToolResult(false, e.message ?: "Некорректное quality.")
        }

        // Bounds-only decode: reads width/height without allocating the bitmap, so an oversized
        // source can never OOM the process before we know its dimensions.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, bounds)
        val srcWidth = bounds.outWidth
        val srcHeight = bounds.outHeight
        if (srcWidth <= 0 || srcHeight <= 0) {
            return ToolResult(
                false,
                "Не удалось декодировать изображение: «${src.name}» (неподдерживаемый формат или повреждённый файл).",
            )
        }

        val target = try {
            imageTargetSize(arguments, srcWidth, srcHeight)
        } catch (e: IllegalArgumentException) {
            return ToolResult(false, e.message ?: "Некорректные размеры.")
        }
        val targetWidth = target[0]
        val targetHeight = target[1]

        var decoded: Bitmap? = null
        var output: Bitmap? = null
        try {
            val base = decodeForSize(src, targetWidth, targetHeight) ?: return ToolResult(
                false,
                "Не удалось декодировать изображение: «${src.name}» (неподдерживаемый формат или повреждённый файл).",
            )
            decoded = base
            val scaled = if (base.width == targetWidth && base.height == targetHeight) {
                base
            } else {
                Bitmap.createScaledBitmap(base, targetWidth, targetHeight, true)
            }
            output = scaled

            val parent = dest.parentFile
            if (parent == null || (!parent.exists() && !parent.mkdirs())) {
                return ToolResult(
                    false,
                    "Не удалось создать каталог для «${dest.absolutePath}».",
                )
            }
            // PNG ignores the quality hint; passing 100 keeps the write lossless.
            val effectiveQuality = if (format == ImageFormat.PNG) 100 else quality
            FileOutputStream(dest).use { out ->
                if (!scaled.compress(compressFormatFor(format), effectiveQuality, out)) {
                    dest.delete()
                    return ToolResult(
                        false,
                        "Не удалось закодировать «${format.extension}»: «${dest.absolutePath}».",
                    )
                }
            }

            // POST-VERIFY: the destination must exist and be non-empty.
            if (!dest.exists() || dest.length() <= 0L) {
                dest.delete()
                return ToolResult(
                    false,
                    "Изображение не записано (файл пуст или отсутствует): «${dest.absolutePath}».",
                )
            }

            return ToolResult(
                true,
                "Изображение сохранено: «${dest.absolutePath}» " +
                    "(${scaled.width}x${scaled.height} px, ${dest.length()} байт).",
            )
        } finally {
            val out = output
            if (out != null && out !== decoded) out.recycle()
            decoded?.recycle()
        }
    }

    /**
     * Decodes [file] downsampled so that its larger side is at least [target] px, without
     * loading the full-size bitmap into memory. Returns `null` when the file is not a decodable
     * image. References: `BitmapFactory.Options.inJustDecodeBounds` / `outWidth` / `outHeight` /
     * `inSampleSize` and `BitmapFactory.decodeFile(String, Options)` —
     * https://developer.android.com/reference/android/graphics/BitmapFactory
     */
    fun decodeSampledBitmap(file: File, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) {
            return null
        }
        val larger = maxOf(width, height)
        var sampleSize = 1
        while (larger / (sampleSize * 2) >= target) {
            sampleSize *= 2
        }
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
    }

    /** Codecs supported by `image_resize` / `image_convert`, with the canonical extension. */
    private enum class ImageFormat(val extension: String) {
        JPEG("jpeg"),
        PNG("png"),
        WEBP("webp"),
    }

    /**
     * Decodes [file] downsampled for a [targetWidth] x [targetHeight] output. `inSampleSize` is
     * doubled while the decoded bitmap still covers the target, and further doubled so a
     * pathological source never decodes above [MAX_IMAGE_SIDE] px on a side. Returns `null` when
     * the file is not a decodable image.
     */
    private fun decodeForSize(file: File, targetWidth: Int, targetHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        var sampleSize = 1
        while (width / (sampleSize * 2) >= targetWidth && height / (sampleSize * 2) >= targetHeight) {
            sampleSize *= 2
        }
        while (width / sampleSize > MAX_IMAGE_SIDE || height / sampleSize > MAX_IMAGE_SIDE) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    /**
     * Computes the destination size: an explicit `maxSide` fits the longer side, a single
     * `width`/`height` keeps the aspect ratio, both are taken verbatim, and no size argument keeps
     * the source size. Throws [IllegalArgumentException] when a side exceeds [MAX_IMAGE_SIDE].
     */
    private fun imageTargetSize(arguments: JsonObject, srcWidth: Int, srcHeight: Int): IntArray {
        val width = optionalImageDimension(arguments["width"] as? JsonPrimitive, "width")
        val height = optionalImageDimension(arguments["height"] as? JsonPrimitive, "height")
        val maxSide = optionalImageDimension(arguments["maxSide"] as? JsonPrimitive, "maxSide")
        var targetWidth = srcWidth
        var targetHeight = srcHeight
        when {
            maxSide != null -> {
                val scale = maxSide.toDouble() / maxOf(srcWidth, srcHeight).toDouble()
                targetWidth = (srcWidth * scale).roundToInt().coerceAtLeast(1)
                targetHeight = (srcHeight * scale).roundToInt().coerceAtLeast(1)
            }
            width != null && height != null -> {
                targetWidth = width
                targetHeight = height
            }
            width != null -> {
                targetWidth = width
                targetHeight = (srcHeight.toDouble() * width / srcWidth).roundToInt().coerceAtLeast(1)
            }
            height != null -> {
                targetHeight = height
                targetWidth = (srcWidth.toDouble() * height / srcHeight).roundToInt().coerceAtLeast(1)
            }
        }
        if (targetWidth > MAX_IMAGE_SIDE || targetHeight > MAX_IMAGE_SIDE) {
            throw IllegalArgumentException(
                "Размер ${targetWidth}x$targetHeight px превышает предел $MAX_IMAGE_SIDE px по стороне.",
            )
        }
        return intArrayOf(targetWidth, targetHeight)
    }

    private fun optionalImageDimension(value: JsonPrimitive?, name: String): Int? {
        val raw = value?.content?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val parsed = raw.toIntOrNull() ?: throw IllegalArgumentException("Некорректный $name: «$raw».")
        if (parsed <= 0) throw IllegalArgumentException("$name должен быть больше нуля.")
        return parsed
    }

    private fun parseImageQuality(raw: String?): Int {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return DEFAULT_IMAGE_QUALITY
        val parsed = value.toIntOrNull() ?: throw IllegalArgumentException("Некорректный quality: «$value».")
        if (parsed !in MIN_IMAGE_QUALITY..MAX_IMAGE_QUALITY) {
            throw IllegalArgumentException(
                "quality должен быть от $MIN_IMAGE_QUALITY до $MAX_IMAGE_QUALITY.",
            )
        }
        return parsed
    }

    /**
     * `format` as given, else derived from the `dest` extension, else JPEG.
     * Throws [IllegalArgumentException] for an unrecognized explicit format.
     */
    private fun parseImageFormat(raw: String?, dest: File): ImageFormat {
        val name = raw?.trim()?.lowercase().orEmpty()
        if (name.isNotEmpty()) {
            return when (name) {
                "jpeg", "jpg" -> ImageFormat.JPEG
                "png" -> ImageFormat.PNG
                "webp" -> ImageFormat.WEBP
                else -> throw IllegalArgumentException(
                    "Неизвестный формат «$raw». Допустимо: jpeg, png, webp.",
                )
            }
        }
        return when (dest.extension.lowercase()) {
            "jpg", "jpeg" -> ImageFormat.JPEG
            "png" -> ImageFormat.PNG
            "webp" -> ImageFormat.WEBP
            else -> ImageFormat.JPEG
        }
    }

    /**
     * Maps an [ImageFormat] to its encoder. `CompressFormat.WEBP` is deprecated on API 30+ in
     * favour of `WEBP_LOSSY`/`WEBP_LOSSLESS`, but it is the only constant available down to
     * minSdk 26, so it is kept for a single code path on every supported API level.
     * Reference: https://developer.android.com/reference/android/graphics/Bitmap.CompressFormat
     */
    @Suppress("DEPRECATION")
    private fun compressFormatFor(format: ImageFormat): Bitmap.CompressFormat = when (format) {
        ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
        ImageFormat.PNG -> Bitmap.CompressFormat.PNG
        ImageFormat.WEBP -> Bitmap.CompressFormat.WEBP
    }

    private companion object {
        /** `image_resize` / `image_convert` default `quality` for JPEG and WebP. */
        private const val DEFAULT_IMAGE_QUALITY = 85

        /** `image_resize` / `image_convert` minimum accepted `quality`. */
        private const val MIN_IMAGE_QUALITY = 1

        /** `image_resize` / `image_convert` maximum accepted `quality`. */
        private const val MAX_IMAGE_QUALITY = 100

        /** `image_resize` / `image_convert` hard cap on a single output/decoded side, in pixels. */
        private const val MAX_IMAGE_SIDE = 8192
    }
}
