package com.andharness.ru.data.tools.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileOutputStream

/**
 * Android-`Bitmap` plumbing shared by the new image-editing tools (`image_crop`, `image_rotate`,
 * `image_adjust`, `image_watermark`, `image_overlay_text`, `image_collage`, `screen_annotate`).
 *
 * The design mirrors [MediaImageTransformer]: bounds-first decode (so a huge source can never OOM
 * the process before its size is known), a single `inSampleSize` downsample that caps the larger
 * side, an atomic temp-file write, and a non-empty post-write verification. Sources are never
 * mutated — every tool writes a NEW destination file.
 *
 * References:
 * - `BitmapFactory.Options.inJustDecodeBounds` / `outWidth` / `outHeight` / `inSampleSize` and
 *   `BitmapFactory.decodeFile(String, Options)` —
 *   https://developer.android.com/reference/android/graphics/BitmapFactory.Options
 * - `Bitmap.compress(CompressFormat, int, OutputStream)` and `Bitmap.CompressFormat` —
 *   https://developer.android.com/reference/android/graphics/Bitmap#compress(android.graphics.Bitmap.CompressFormat,%20int,%20java.io.OutputStream)
 * - `FileOutputStream` + `FileDescriptor.sync()` and `File.renameTo(File)` (atomic same-volume
 *   replace) — https://developer.android.com/reference/java/io/File#renameTo(java.io.File)
 */
internal object ImageEditCommon {

    /** Hard cap on a single edited output side, in pixels, to keep memory sane. */
    const val MAX_EDIT_SIDE = 4096

    /** A decoded bitmap together with whether the source was downsampled to fit [MAX_EDIT_SIDE]. */
    class Decoded(val bitmap: Bitmap, val downscaled: Boolean)

    /** A resolved [file] or the [error] message to return to the model. */
    class Resolved(val file: File?, val error: String?)

    /**
     * Decodes [file] bounds-first, doubling `inSampleSize` until the larger side fits [maxSide].
     * Returns `null` when the file is not a decodable image; [Decoded.downscaled] is `true` when the
     * decode shrank the source.
     */
    fun decodeCapped(file: File, maxSide: Int = MAX_EDIT_SIDE): Decoded? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        var sampleSize = 1
        while (maxOf(width, height) / sampleSize > maxSide) sampleSize *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        return Decoded(bitmap, sampleSize > 1)
    }

    /** Bounds-only decode: `[width, height]` without allocating the bitmap, or `null` if invalid. */
    fun sourceBounds(file: File): IntArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return intArrayOf(bounds.outWidth, bounds.outHeight)
    }

    /** Resolves the [key] source argument against the tool root and checks it is a readable file. */    fun resolveSource(io: MediaPathIo, arguments: JsonObject, key: String = "src"): Resolved {
        val raw = (arguments[key] as? JsonPrimitive)?.content.orEmpty()
        val file = try {
            io.resolveMediaPath(raw)
        } catch (e: IllegalArgumentException) {
            return Resolved(null, e.message ?: "Некорректный путь источника.")
        }
        if (!file.exists() || !file.isFile) {
            return Resolved(null, "Файл не найден: «${file.name}».")
        }
        return Resolved(file, null)
    }

    /**
     * Resolves the [key] destination argument, refuses to overwrite an existing file and ensures
     * the parent directory exists.
     */
    fun prepareDestination(io: MediaPathIo, arguments: JsonObject, key: String = "dest"): Resolved {
        val raw = (arguments[key] as? JsonPrimitive)?.content.orEmpty()
        val file = try {
            io.resolveMediaPath(raw)
        } catch (e: IllegalArgumentException) {
            return Resolved(null, e.message ?: "Некорректный путь назначения.")
        }
        if (file.exists()) {
            return Resolved(
                null,
                "Файл назначения уже существует: «${file.absolutePath}». Перезапись запрещена.",
            )
        }
        val parent = file.parentFile
        if (parent == null || (!parent.exists() && !parent.mkdirs())) {
            return Resolved(null, "Не удалось создать каталог для «${file.absolutePath}».")
        }
        return Resolved(file, null)
    }

    /** Encoder picked from the destination extension; PNG (lossless) when unknown. */
    fun formatFor(dest: File): Bitmap.CompressFormat = when (dest.extension.lowercase()) {
        "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
        "webp" -> webpFormat()
        else -> Bitmap.CompressFormat.PNG
    }

    /**
     * `CompressFormat.WEBP` is deprecated on API 30+ but is the only constant available down to the
     * project's minSdk, so it is kept for one code path on every supported API level.
     * Reference: https://developer.android.com/reference/android/graphics/Bitmap.CompressFormat
     */
    @Suppress("DEPRECATION")
    private fun webpFormat(): Bitmap.CompressFormat = Bitmap.CompressFormat.WEBP

    /** Lossless for PNG; 92 for JPEG/WebP. */
    private fun qualityFor(format: Bitmap.CompressFormat): Int =
        if (format == Bitmap.CompressFormat.PNG) 100 else 92

    /**
     * Atomically writes [bitmap] to [dest] (same-directory temp + fsync + rename) in the format
     * implied by the extension, then verifies the file exists and is non-empty. Returns `null` on
     * success or an honest error message.
     */
    fun writeBitmap(dest: File, bitmap: Bitmap): String? {
        val format = formatFor(dest)
        val temp = File(dest.parentFile, "${dest.name}.tmp-${System.nanoTime()}")
        try {
            FileOutputStream(temp).use { out ->
                if (!bitmap.compress(format, qualityFor(format), out)) {
                    temp.delete()
                    return "Не удалось закодировать изображение: «${dest.absolutePath}»."
                }
                out.flush()
                out.fd.sync()
            }
        } catch (e: Exception) {
            temp.delete()
            return "Не удалось записать файл: ${e.message}"
        }
        if (!temp.renameTo(dest)) {
            temp.delete()
            return "Не удалось сохранить файл: «${dest.absolutePath}»."
        }
        if (!dest.exists() || dest.length() <= 0L) {
            dest.delete()
            return "Изображение не записано (файл пуст или отсутствует): «${dest.absolutePath}»."
        }
        return null
    }

    /** Honest success sentence: path, real output dimensions, byte size and any downscale note. */
    fun successMessage(dest: File, bitmap: Bitmap, downscaled: Boolean): String {
        val note = if (downscaled) " Источник был уменьшен для экономии памяти." else ""
        return "Изображение сохранено: «${dest.absolutePath}» " +
            "(${bitmap.width}x${bitmap.height} px, ${dest.length()} байт).$note"
    }
}
