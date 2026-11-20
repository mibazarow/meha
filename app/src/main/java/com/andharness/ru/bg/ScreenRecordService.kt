package com.andharness.ru.bg

import android.app.Activity
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.andharness.ru.data.notifications.NotificationHelper
import com.andharness.ru.data.tools.screen.ScreenCaptureState
import com.andharness.ru.data.tools.screen.ScreenRecordingState
import com.andharness.ru.data.tools.newMediaRecorder
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject

/**
 * Foreground service that records the screen (and optionally microphone audio) with
 * `MediaProjection` + `MediaRecorder` and writes an H.264/MP4 file. Single-frame capture
 * (`screen_capture_region`) is delegated to [ScreenFrameCapture]; this class is the service host and
 * the recording coordinator.
 *
 * API ORDER (mandatory on Android 14+, API 34+):
 * 1. The Activity already obtained user consent via
 *    `MediaProjectionManager.createScreenCaptureIntent()`; its `resultCode`/`data` are passed here
 *    as Intent extras.
 * 2. The service calls [ServiceCompat.startForeground] with
 *    [ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION] BEFORE touching the projection.
 * 3. Only then `getMediaProjection(resultCode, data)`, a registered
 *    [MediaProjection.Callback], `createVirtualDisplay(...)` and the [MediaRecorder].
 * 4. On stop: stop/release the recorder, release the virtual display, unregister and stop the
 *    projection, then remove the notification and stop the service.
 *
 * References:
 * - https://developer.android.com/media/grow/media-projection
 * - https://developer.android.com/about/versions/14/changes/fgs-types-required
 * - https://developer.android.com/develop/background-work/services/fgs/service-types
 * - https://developer.android.com/reference/android/media/projection/MediaProjectionManager
 */
@AndroidEntryPoint
class ScreenRecordService : Service() {

    @Inject
    lateinit var notifications: NotificationHelper

    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var outputPath: String? = null
    private var withAudio = false
    private var stopping = false

    /** Single-frame capture state machine (`screen_capture_region`, ACTION_CAPTURE). */
    private val capture: ScreenFrameCapture by lazy {
        ScreenFrameCapture(
            context = this,
            notifications = notifications,
            startForeground = ::startCaptureForeground,
            stopForeground = { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) },
            stopSelf = { startId -> if (startId == null) stopSelf() else stopSelf(startId) },
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent, startId)
            ACTION_CAPTURE -> handleCapture(intent, startId)
            ACTION_STOP -> {
                stopRecording()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent, startId: Int) {
        if (recorder != null) {
            Log.w(TAG, "start ignored: already recording")
            return
        }

        withAudio = intent.getBooleanExtra(EXTRA_WITH_AUDIO, false)
        notifications.ensureChannels()

        // STEP 2: go foreground BEFORE acquiring the projection (mandatory on Android 14+).
        val foreground = try {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.FGS_ID_SCREEN_RECORD,
                ScreenRecordNotifications.recording(this, withAudio),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            false
        }
        if (!foreground) {
            ScreenRecordingState.markStopped("Не удалось перевести службу записи экрана в foreground.")
            stopSelf(startId)
            return
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        val path = intent.getStringExtra(EXTRA_OUTPUT_PATH)
        if (resultCode != Activity.RESULT_OK || resultData == null || path.isNullOrEmpty()) {
            Log.e(TAG, "invalid consent extras (resultCode=$resultCode data=${resultData != null} path=$path)")
            stopRecording("Не получено согласие на запись экрана.")
            stopSelf(startId)
            return
        }

        // STEP 3a: obtain the projection token from the consent result.
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            manager.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e)
            null
        }
        if (projection == null) {
            Log.e(TAG, "getMediaProjection returned null")
            stopRecording("Система не выдала проекцию экрана (согласие отклонено или истекло).")
            stopSelf(startId)
            return
        }
        mediaProjection = projection

        // Android 14+ requires a registered callback before createVirtualDisplay(); it also fires
        // when the user stops sharing from the system UI, so cleanup happens there too.
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection.onStop()")
                stopRecording()
                stopSelf()
            }
        }
        projectionCallback = callback
        projection.registerCallback(callback, null)

        // STEP 3b: configure the recorder, then the virtual display that feeds it.
        val file = File(path)
        file.parentFile?.mkdirs()
        val metrics = ScreenGeometry.screenMetrics(this)
        val (width, height) = ScreenGeometry.fitEven(metrics.widthPixels, metrics.heightPixels)

        val target = try {
            createRecorder(file, width, height, withAudio)
        } catch (e: Exception) {
            Log.e(TAG, "createRecorder failed", e)
            stopRecording("Не удалось подготовить запись экрана: ${e.message}")
            stopSelf(startId)
            return
        }
        recorder = target
        try {
            target.prepare()
            target.start()
        } catch (e: Exception) {
            Log.e(TAG, "recorder start failed (${width}x${height}, audio=$withAudio)", e)
            releaseRecorder()
            stopRecording("Не удалось начать запись экрана: ${e.message}")
            stopSelf(startId)
            return
        }

        val display = try {
            projection.createVirtualDisplay(
                "andharness-screen",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                target.surface,
                null,
                null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplay failed", e)
            null
        }
        if (display == null) {
            stopRecording("Не удалось создать виртуальный дисплей для записи экрана.")
            stopSelf(startId)
            return
        }
        virtualDisplay = display
        outputPath = path
        ScreenRecordingState.markStarted(path, withAudio)
        Log.i(TAG, "recording started: $path (${width}x${height}, audio=$withAudio)")
    }

    /** Routes a frame-capture request to [ScreenFrameCapture], refusing it while recording. */
    private fun handleCapture(intent: Intent, startId: Int) {
        if (capture.isActive()) {
            Log.w(TAG, "capture ignored: already capturing")
            return
        }
        if (recorder != null) {
            Log.w(TAG, "capture ignored: recording in progress")
            ScreenCaptureState.failure("Идёт запись экрана — сначала остановите её (screen_record_stop).")
            stopSelf(startId)
            return
        }
        capture.begin(intent, startId)
    }

    /**
     * Stops and releases every recording resource exactly once and finalizes the state for the tools.
     * [errorOverride] is used by the failure paths in [handleStart] so a specific reason is not
     * cleared by the generic cleanup below.
     */
    private fun stopRecording(errorOverride: String? = null) {
        if (stopping) return
        stopping = true

        val rec = recorder
        recorder = null
        var failure: String? = null
        if (rec != null) {
            try {
                rec.stop()
            } catch (e: Exception) {
                // stop() throws when no frames were written yet; the file is then unusable.
                Log.e(TAG, "recorder.stop failed", e)
                failure = "Запись остановлена, но файл не сформирован: ${e.message}"
            }
            try {
                rec.reset()
            } catch (_: Exception) {
            }
            try {
                rec.release()
            } catch (_: Exception) {
            }
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

        val path = outputPath
        outputPath = null
        val fileError = if (path != null) {
            val file = File(path)
            if (!file.exists() || file.length() == 0L) {
                "Запись остановлена, но файл пуст или не создан: «$path»."
            } else {
                null
            }
        } else {
            null
        }
        ScreenRecordingState.markStopped(errorOverride ?: fileError ?: failure)

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "recording stopped (${path ?: "no path"})")
    }

    override fun onDestroy() {
        stopRecording()
        capture.finish(null)
        super.onDestroy()
    }

    /** Foreground entry point for the frame capture (logs and returns false on failure). */
    private fun startCaptureForeground(notification: Notification): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NotificationHelper.FGS_ID_SCREEN_CAPTURE,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        true
    } catch (e: Exception) {
        Log.e(TAG, "startForeground failed (capture)", e)
        false
    }

    /**
     * Builds (not starts) the [MediaRecorder]. Order of the setters follows the documented sequence
     * (audio source, video source, output format, encoders, output file):
     * https://developer.android.com/media/platform/mediarecorder#configuration
     */
    private fun createRecorder(file: File, width: Int, height: Int, audio: Boolean): MediaRecorder {
        val rec = newMediaRecorder(this)
        if (audio) rec.setAudioSource(MediaRecorder.AudioSource.MIC)
        rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        if (audio) rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        rec.setVideoSize(width, height)
        rec.setVideoFrameRate(FRAME_RATE)
        rec.setVideoEncodingBitRate(BITRATE)
        rec.setOutputFile(file.absolutePath)
        return rec
    }

    private fun releaseRecorder() {
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
    }

    companion object {
        private const val TAG = "ScreenRecordService"
        private const val FRAME_RATE = 30
        private const val BITRATE = 8_000_000

        /** Sentinel for "region coordinate not supplied". */
        const val NO_REGION = -1

        const val ACTION_START = "com.andharness.ru.action.SCREEN_RECORD_START"
        const val ACTION_STOP = "com.andharness.ru.action.SCREEN_RECORD_STOP"
        const val ACTION_CAPTURE = "com.andharness.ru.action.SCREEN_CAPTURE"
        const val EXTRA_RESULT_CODE = "screen_record_result_code"
        const val EXTRA_RESULT_DATA = "screen_record_result_data"
        const val EXTRA_OUTPUT_PATH = "screen_record_output_path"
        const val EXTRA_WITH_AUDIO = "screen_record_with_audio"
        const val EXTRA_REGION_LEFT = "screen_capture_region_left"
        const val EXTRA_REGION_TOP = "screen_capture_region_top"
        const val EXTRA_REGION_WIDTH = "screen_capture_region_width"
        const val EXTRA_REGION_HEIGHT = "screen_capture_region_height"

        /** Starts a one-frame capture; returns false when the OS refuses to start the service. */
        fun capture(
            context: Context,
            resultCode: Int,
            data: Intent,
            outputPath: String,
            left: Int = NO_REGION,
            top: Int = NO_REGION,
            width: Int = NO_REGION,
            height: Int = NO_REGION,
        ): Boolean {
            val intent = Intent(context, ScreenRecordService::class.java)
                .setAction(ACTION_CAPTURE)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_OUTPUT_PATH, outputPath)
                .putExtra(EXTRA_REGION_LEFT, left)
                .putExtra(EXTRA_REGION_TOP, top)
                .putExtra(EXTRA_REGION_WIDTH, width)
                .putExtra(EXTRA_REGION_HEIGHT, height)
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService failed (capture)", e)
                false
            }
        }

        /** Starts the recording foreground service; returns false when the OS refuses to start it. */
        fun start(
            context: Context,
            resultCode: Int,
            data: Intent,
            outputPath: String,
            withAudio: Boolean,
        ): Boolean {
            val intent = Intent(context, ScreenRecordService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_OUTPUT_PATH, outputPath)
                .putExtra(EXTRA_WITH_AUDIO, withAudio)
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService failed", e)
                false
            }
        }

        /** Asks a running service to stop and finalize the file. */
        fun stop(context: Context) {
            val intent = Intent(context, ScreenRecordService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "stop request failed", e)
            }
        }
    }
}
