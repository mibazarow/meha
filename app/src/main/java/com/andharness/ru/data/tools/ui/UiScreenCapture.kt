package com.andharness.ru.data.tools.ui

import android.accessibilityservice.AccessibilityService
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.view.Display
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.OutputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Accessibility screenshot capture and gallery persistence for the `screen_capture` tool.
 *
 * `takeScreenshot`'s callback is delivered by the system and can, in principle, never arrive
 * (service disconnected / OEM bug); an unbounded wait would then keep the agent turn — and the
 * composer — stuck. The explicit budget reports an honest failure.
 *
 * https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshot(int,%20java.util.concurrent.Executor,%20android.accessibilityservice.AccessibilityService.TakeScreenshotCallback)
 */
internal class UiScreenCapture(private val context: Context) {

    /** Bounded screenshot; returns null when the system rejects or never answers the capture. */
    suspend fun captureScreen(service: AccessibilityService): Bitmap? =
        withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val executor = Executor { it.run() }
                try {
                    service.takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        executor,
                        object : AccessibilityService.TakeScreenshotCallback {
                            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                                val hardwareBuffer = screenshot.hardwareBuffer
                                val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                                    ?.copy(Bitmap.Config.ARGB_8888, false)
                                hardwareBuffer.close()
                                if (cont.isActive) cont.resume(bitmap)
                            }

                            override fun onFailure(errorCode: Int) {
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                    )
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    /** Saves [bitmap] to `Pictures/Meha` and returns its relative path, or null on failure. */
    fun saveToGallery(bitmap: Bitmap): String? {
        return try {
            val name = "andharness_${System.currentTimeMillis()}.png"
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Meha")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                null
            } else {
                val stream: OutputStream? = resolver.openOutputStream(uri)
                // compress() returns false when the bitmap could not be encoded — that must fail.
                val compressed = stream?.use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                } ?: false
                if (!compressed) {
                    // Never leave an empty orphaned row in the gallery.
                    runCatching { resolver.delete(uri, null, null) }
                    null
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                        resolver.update(uri, done, null, null)
                    }
                    "Pictures/Meha/$name"
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        /** Budget for one system screenshot callback; a lost callback must not suspend forever. */
        const val CAPTURE_TIMEOUT_MS = 15_000L
    }
}
