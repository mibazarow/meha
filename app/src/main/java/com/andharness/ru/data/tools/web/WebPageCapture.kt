package com.andharness.ru.data.tools.web

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Captures a tab [WebView] into a PNG. The offscreen view may never be painted, so [capture]
 * returns null when the view has no size; the caller then reports an honest failure instead of a
 * blank image.
 */
internal class WebPageCapture(private val appContext: Context) {

    /** Draws [view] into a bitmap on the main thread; returns null when the view has no size. */
    fun capture(view: WebView): Bitmap? = try {
        var width = view.width
        var height = view.height
        if (width <= 0 || height <= 0) {
            val metrics = appContext.resources.displayMetrics
            width = metrics.widthPixels.coerceAtLeast(1)
            height = metrics.heightPixels.coerceAtLeast(1)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, width, height)
        }
        val full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(full)
        canvas.drawColor(Color.WHITE)
        view.draw(canvas)
        if (width > MAX_SCREENSHOT_WIDTH) {
            val scale = MAX_SCREENSHOT_WIDTH.toFloat() / width
            val outHeight = (height * scale).toInt().coerceAtLeast(1)
            val shrunk = Bitmap.createScaledBitmap(full, MAX_SCREENSHOT_WIDTH, outHeight, true)
            if (shrunk !== full) full.recycle()
            shrunk
        } else {
            full
        }
    } catch (e: Exception) {
        null
    }

    /** Writes [bitmap] to [file] as PNG via a temp file + atomic rename, then post-verifies. */
    suspend fun writePngAtomically(bitmap: Bitmap, file: File): Long = withContext(Dispatchers.IO) {
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IllegalStateException("Не удалось создать директорию «${parent.absolutePath}».")
        }
        val dir = parent ?: appContext.filesDir
        val temp = File(dir, ".web-screenshot-${System.nanoTime()}.part")
        try {
            FileOutputStream(temp).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IllegalStateException("Не удалось закодировать PNG.")
                }
                out.flush()
                out.fd.sync()
            }
            if (file.exists() && !file.delete()) {
                throw IllegalStateException("Не удалось перезаписать «${file.absolutePath}».")
            }
            if (!temp.renameTo(file)) {
                throw IllegalStateException("Не удалось сохранить «${file.absolutePath}».")
            }
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
        if (!file.exists() || file.length() <= 0L) {
            file.delete()
            throw IllegalStateException("Проверка не пройдена: файл «${file.absolutePath}» не создан или пуст.")
        }
        file.length()
    }

    private companion object {
        private const val MAX_SCREENSHOT_WIDTH = 1280
    }
}
