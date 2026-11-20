package com.andharness.ru.data.index

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a device image (a `content://` URI) into the small encoded byte payload handed to the image
 * embedder. The full-resolution image is NEVER decoded into memory: the decoder first reads only the
 * dimensions and then decodes at a power-of-two [sampleSizeFor] so the largest side lands just above
 * [MAX_SIDE] (≈512 px), which is what the embedder needs and keeps peak memory bounded regardless of
 * the source image's size.
 *
 * Two `openInputStream` passes are used (bounds, then sampled pixels) because an `InputStream` cannot
 * be rewound; re-opening is cheaper and safer than buffering the whole file.
 *
 * References:
 * - `BitmapFactory.Options.inJustDecodeBounds` (dimensions only, no pixels) and `inSampleSize`
 *   (power-of-two downscale) —
 *   https://developer.android.com/reference/android/graphics/BitmapFactory.Options
 * - `BitmapFactory.decodeStream` (reused by the project in the image tools) —
 *   https://developer.android.com/reference/android/graphics/BitmapFactory#decodeStream(java.io.InputStream)
 * - `Bitmap.compress` (JPEG) + `ContentResolver.openInputStream` —
 *   https://developer.android.com/reference/android/graphics/Bitmap#compress(android.graphics.Bitmap.CompressFormat,%20int,%20java.io.OutputStream)
 *   https://developer.android.com/reference/android/content/ContentResolver#openInputStream(android.net.Uri)
 */
@Singleton
class ImageThumbnailDecoder @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Decodes the image at [sourceUri] downscaled to ≈[maxSide] px and JPEG-compresses it, or returns
     * `null` on ANY failure (unreadable URI, undecodable image, compression error). Never throws and
     * never runs the work on the caller's thread expectation — callers invoke it from an IO context.
     */
    fun decodeJpeg(sourceUri: String, maxSide: Int = MAX_SIDE): ByteArray? {
        val bitmap = decodeBitmap(sourceUri, maxSide) ?: return null
        return try {
            ByteArrayOutputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                    return null
                }
                out.toByteArray()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Thumbnail compression failed for $sourceUri", e)
            null
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Decodes the image at [sourceUri] as a downscaled [Bitmap] (longer side ≈[maxSide] px) for
     * direct display — the chat's `device_search` photo previews reuse exactly the same
     * bounded-memory sampling as the embedder payload. Returns `null` on ANY failure; never throws.
     * The caller OWNS the returned bitmap (the UI wraps it with `asImageBitmap()`), so it must not be
     * recycled by this decoder — callers run it from an IO context, not the main thread.
     */
    fun decodeBitmap(sourceUri: String, maxSide: Int = MAX_SIDE): Bitmap? {
        val uri = runCatching { Uri.parse(sourceUri) }.getOrNull() ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(width, height, maxSide)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Thumbnail decode failed for $sourceUri", e)
            null
        }
    }

    companion object {
        private const val TAG = "Andharness"

        /** Target length of the longer side, in pixels — comfortably within the embedder's budget. */
        const val MAX_SIDE = 512

        /** JPEG quality for the downscaled payload; high enough that vision quality is unaffected. */
        const val JPEG_QUALITY = 90

        /**
         * Power-of-two sample size that brings the LONGER side of a [width]×[height] image to just
         * below or at [maxSide] (never below half). Pure and side-effect free so it is unit-tested
         * without a device. A non-positive dimension or limit yields `1` (no downscale).
         */
        fun sampleSizeFor(width: Int, height: Int, maxSide: Int): Int {
            if (width <= 0 || height <= 0 || maxSide <= 0) return 1
            var sample = 1
            var longest = maxOf(width, height)
            while (longest / 2 >= maxSide) {
                longest /= 2
                sample *= 2
            }
            return sample
        }
    }
}
