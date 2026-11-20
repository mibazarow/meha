package com.andharness.ru.bg

import android.app.Activity
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.IntentCompat
import com.andharness.ru.data.notifications.NotificationHelper
import com.andharness.ru.data.tools.screen.ScreenCaptureState
import java.io.File

/**
 * Single-frame screen capture of `screen_capture_region` (ACTION_CAPTURE): owns the [ImageReader],
 * the capture [HandlerThread], the region crop / PNG save and the projection + reader + display
 * teardown.
 *
 * The hosting [ScreenRecordService] keeps the foreground-service contract — it supplies the
 * `startForeground`/`stopForeground`/`stopSelf` lambdas — while this collaborator owns the capture
 * state machine. API order is unchanged (foreground `mediaProjection` BEFORE touching the
 * projection) as required on Android 14+.
 *
 * References:
 * - [ImageReader] — https://developer.android.com/reference/android/media/ImageReader
 * - https://developer.android.com/media/grow/media-projection
 */
internal class ScreenFrameCapture(
    private val context: Context,
    private val notifications: NotificationHelper,
    private val startForeground: (Notification) -> Boolean,
    private val stopForeground: () -> Unit,
    private val stopSelf: (Int?) -> Unit,
) {

    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var capturePath: String? = null
    private var captureRegion: IntArray? = null
    private var captureTimeout: Runnable? = null
    private var captureStopping = false

    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null

    /** True while a capture is running (the service refuses a second one). */
    fun isActive(): Boolean = imageReader != null

    /**
     * Captures exactly one frame of the default display through [ImageReader] and saves it as PNG.
     *
     * A region is NOT a separate projection: the platform captures the whole display and this
     * collaborator crops the frame to the requested rectangle, which the tool reports honestly. If
     * no region is given the whole frame is saved.
     */
    fun begin(intent: Intent, startId: Int) {
        captureStopping = false
        notifications.ensureChannels()

        val foreground = startForeground(ScreenRecordNotifications.capture(context))
        if (!foreground) {
            ScreenCaptureState.failure("Не удалось перевести службу захвата экрана в foreground.")
            stopSelf(startId)
            return
        }

        val resultCode = intent.getIntExtra(ScreenRecordService.EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = IntentCompat.getParcelableExtra(intent, ScreenRecordService.EXTRA_RESULT_DATA, Intent::class.java)
        val path = intent.getStringExtra(ScreenRecordService.EXTRA_OUTPUT_PATH)
        if (resultCode != Activity.RESULT_OK || resultData == null || path.isNullOrEmpty()) {
            ScreenCaptureState.failure("Не получено согласие на захват экрана.")
            finish(null)
            stopSelf(startId)
            return
        }

        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            manager.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed (capture)", e)
            null
        }
        if (projection == null) {
            ScreenCaptureState.failure("Система не выдала проекцию экрана (согласие отклонено или истекло).")
            finish(null)
            stopSelf(startId)
            return
        }
        mediaProjection = projection

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection.onStop() (capture)")
                finish("Система остановила захват экрана.")
                stopSelf(null)
            }
        }
        projectionCallback = callback
        projection.registerCallback(callback, null)

        capturePath = path
        captureRegion = intArrayOf(
            intent.getIntExtra(ScreenRecordService.EXTRA_REGION_LEFT, ScreenRecordService.NO_REGION),
            intent.getIntExtra(ScreenRecordService.EXTRA_REGION_TOP, ScreenRecordService.NO_REGION),
            intent.getIntExtra(ScreenRecordService.EXTRA_REGION_WIDTH, ScreenRecordService.NO_REGION),
            intent.getIntExtra(ScreenRecordService.EXTRA_REGION_HEIGHT, ScreenRecordService.NO_REGION),
        )

        val metrics = ScreenGeometry.screenMetrics(context)
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        val thread = HandlerThread("andharness-screen-capture").also { it.start() }
        captureThread = thread
        val handler = Handler(thread.looper)
        captureHandler = handler

        val reader = try {
            ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        } catch (e: Exception) {
            Log.e(TAG, "ImageReader.newInstance failed", e)
            null
        }
        if (reader == null) {
            ScreenCaptureState.failure("Не удалось подготовить захват кадра ($width×$height).")
            finish(null)
            stopSelf(startId)
            return
        }
        imageReader = reader
        reader.setOnImageAvailableListener({ r -> onFrameAvailable(r) }, handler)

        val display = try {
            projection.createVirtualDisplay(
                "andharness-capture",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplay failed (capture)", e)
            null
        }
        if (display == null) {
            ScreenCaptureState.failure("Не удалось создать виртуальный дисплей для захвата кадра.")
            finish(null)
            stopSelf(startId)
            return
        }
        virtualDisplay = display

        val timeout = Runnable {
            Log.w(TAG, "capture timed out")
            ScreenCaptureState.failure("Кадр не получен за ${CAPTURE_TIMEOUT_MS / 1000} с.")
            finish(null)
            stopSelf(null)
        }
        captureTimeout = timeout
        handler.postDelayed(timeout, CAPTURE_TIMEOUT_MS)
        Log.i(TAG, "capture started: $path (${width}x$height)")
    }

    /** Converts the first available frame to a bitmap, crops it and saves it as PNG (atomic). */
    private fun onFrameAvailable(reader: ImageReader) {
        if (captureStopping) return
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            Log.e(TAG, "acquireLatestImage failed", e)
            null
        } ?: return

        var failure: String? = null
        try {
            val width = image.width
            val height = image.height
            val plane = image.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * width
            val padded = Bitmap.createBitmap(
                width + rowPadding / plane.pixelStride,
                height,
                Bitmap.Config.ARGB_8888,
            )
            padded.copyPixelsFromBuffer(plane.buffer)
            val full = if (padded.width == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height)

            val cropped = ScreenCaptureFiles.cropToRegion(full, captureRegion, width, height)
            val out = capturePath
            if (cropped == null) {
                failure = "Некорректный регион захвата: ширина и высота должны быть больше нуля и попадать в экран ${width}×${height}."
            } else if (out.isNullOrEmpty()) {
                failure = "Не задан путь для сохранения кадра."
            } else if (ScreenCaptureFiles.savePng(cropped, out)) {
                ScreenCaptureState.success(File(out).absolutePath)
            } else {
                failure = "Не удалось сохранить кадр в «$out» (проверка файла не пройдена)."
            }
        } catch (e: Exception) {
            Log.e(TAG, "frame processing failed", e)
            failure = "Ошибка обработки кадра: ${e.message}"
        } finally {
            try {
                image.close()
            } catch (_: Exception) {
            }
        }

        if (failure != null) ScreenCaptureState.failure(failure)
        finish(null)
        stopSelf(null)
    }

    /** Releases every capture resource exactly once and drops the foreground notification. */
    fun finish(errorOverride: String?) {
        if (captureStopping) return
        captureStopping = true

        captureTimeout?.let { captureHandler?.removeCallbacks(it) }
        captureTimeout = null
        captureHandler = null

        val reader = imageReader
        imageReader = null
        try {
            reader?.setOnImageAvailableListener(null, null)
        } catch (_: Exception) {
        }
        try {
            reader?.close()
        } catch (_: Exception) {
        }

        val thread = captureThread
        captureThread = null
        try {
            thread?.quitSafely()
        } catch (_: Exception) {
        }

        val display = virtualDisplay
        virtualDisplay = null
        try {
            display?.release()
        } catch (_: Exception) {
        }

        val projection = mediaProjection
        mediaProjection = null
        val callback = projectionCallback
        projectionCallback = null
        try {
            if (projection != null && callback != null) projection.unregisterCallback(callback)
        } catch (_: Exception) {
        }
        try {
            projection?.stop()
        } catch (_: Exception) {
        }

        capturePath = null
        captureRegion = null
        if (errorOverride != null) ScreenCaptureState.failure(errorOverride)
        stopForeground()
        Log.i(TAG, "capture finished${errorOverride?.let { ": $it" } ?: ""}")
    }

    private companion object {
        private const val TAG = "ScreenRecordService"

        /** Bounded wait for the very first frame before the capture is reported as failed. */
        private const val CAPTURE_TIMEOUT_MS = 8_000L
    }
}
