package com.andharness.ru.bg

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Pixel→file helpers of the single-frame screen capture: region cropping and atomic PNG writing
 * with a decode-based post-check.
 *
 * Stateless; safe to call from the capture [android.os.HandlerThread].
 */
internal object ScreenCaptureFiles {

    private const val TAG = "ScreenRecordService"

    /**
     * Returns the requested region of [full], or [full] itself when no region was requested.
     * A region outside the screen is clamped; a degenerate region (zero/negative size) returns null
     * so the tool can report an honest error instead of silently saving an empty image.
     */
    fun cropToRegion(full: Bitmap, region: IntArray?, width: Int, height: Int): Bitmap? {
        if (region == null) return full
        val left = region[0]
        val top = region[1]
        val w = region[2]
        val h = region[3]
        if (left == ScreenRecordService.NO_REGION || top == ScreenRecordService.NO_REGION ||
            w == ScreenRecordService.NO_REGION || h == ScreenRecordService.NO_REGION
        ) {
            return full
        }
        if (w <= 0 || h <= 0) return null
        val safeLeft = left.coerceIn(0, width - 1)
        val safeTop = top.coerceIn(0, height - 1)
        val safeW = w.coerceAtMost(width - safeLeft)
        val safeH = h.coerceAtMost(height - safeTop)
        if (safeW <= 0 || safeH <= 0) return null
        return Bitmap.createBitmap(full, safeLeft, safeTop, safeW, safeH)
    }

    /** Writes PNG through a temp file + rename, then post-verifies by decoding the saved file. */
    fun savePng(bitmap: Bitmap, path: String): Boolean {
        val target = File(path)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        return try {
            FileOutputStream(tmp).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    tmp.delete()
                    return false
                }
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return false
            }
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, opts)
            target.length() > 0L && opts.outWidth > 0 && opts.outHeight > 0
        } catch (e: Exception) {
            Log.e(TAG, "savePng failed", e)
            tmp.delete()
            false
        }
    }
}
