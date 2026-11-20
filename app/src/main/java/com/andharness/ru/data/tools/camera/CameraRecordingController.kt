package com.andharness.ru.data.tools.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.media.MediaRecorder
import android.os.Handler
import android.util.Log
import com.andharness.ru.data.tools.newMediaRecorder
import java.io.File

/**
 * Video recording half of [Camera2Controller]: builds a session around a [MediaRecorder] surface and
 * keeps it alive between `camera_video_record` and `camera_record_stop` through
 * [CameraRecordingState].
 *
 * The still-capture session is closed first ([closeStillSession]) so the camera is owned by the
 * recording; the [Handler] thread and [Mutex] stay with the engine, which serializes every call.
 *
 * References:
 * - Camera2 + `MediaRecorder` (`getSurface`, `TEMPLATE_RECORD`) —
 *   https://developer.android.com/media/camera/camera2
 *   https://developer.android.com/reference/android/media/MediaRecorder (state diagram)
 */
internal class CameraRecordingController(
    private val handler: Handler,
    private val lenses: CameraLensResolver,
    private val sizes: CameraCaptureSizes,
    private val factory: CameraSessionFactory,
    private val closeStillSession: () -> Unit,
) {

    /**
     * Starts recording into [target] (the caller must hold the engine mutex). Throws when a
     * recording is already active or the session cannot be built.
     */
    suspend fun start(
        context: Context,
        target: File,
        withAudio: Boolean,
        lens: String?,
        cameraId: String?,
    ): String {
        if (CameraRecordingState.active != null) {
            throw IllegalStateException("Запись уже идёт: ${CameraRecordingState.status.value.path}")
        }
        closeStillSession()
        target.parentFile?.mkdirs()

        val manager = lenses.cameraManager(context)
        val resolved = lenses.resolve(manager, lens, cameraId)
        val characteristics = manager.getCameraCharacteristics(resolved.id)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("Камера не сообщает конфигурацию потоков.")
        val videoSize = sizes.pickVideoSize(map)

        val device = factory.openDevice(manager, resolved.id, closeStillSession)
        val recorder = newMediaRecorder(context)
        var capture: CameraCaptureSession? = null
        try {
            // Order follows the MediaRecorder state diagram: sources → output format → encoders →
            // size/rate/bitrate → orientation → output file → prepare → start. Calling an encoder
            // before setOutputFormat() throws IllegalStateException("called in an invalid state").
            // https://developer.android.com/reference/android/media/MediaRecorder (state diagram)
            if (withAudio) {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            if (withAudio) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            }
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setVideoSize(videoSize.width, videoSize.height)
            recorder.setVideoFrameRate(30)
            recorder.setVideoEncodingBitRate(8_000_000)
            characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)?.let { recorder.setOrientationHint(it) }
            recorder.setOutputFile(target.absolutePath)
            recorder.prepare()

            capture = factory.createSession(device, listOf(recorder.surface))
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(recorder.surface)
            }.build()
            capture.setRepeatingRequest(request, null, handler)
            recorder.start()
        } catch (e: Exception) {
            runCatching { capture?.close() }
            runCatching { recorder.reset() }
            runCatching { recorder.release() }
            runCatching { device.close() }
            throw IllegalStateException("Не удалось начать запись видео: ${e.message}", e)
        }

        val started = capture
        CameraRecordingState.active = CameraRecordingState.ActiveRecording(target, withAudio) {
            val sessionToClose = started
            var failure: String? = null
            runCatching { sessionToClose?.stopRepeating() }
            runCatching { sessionToClose?.close() }
            try {
                recorder.stop()
            } catch (e: Exception) {
                failure = "Запись прервана: ${e.message}"
            } finally {
                runCatching { recorder.reset() }
                runCatching { recorder.release() }
                runCatching { device.close() }
            }
            CameraRecordingState.markStopped(failure)
            if (!target.exists() || target.length() <= 0L) {
                failure ?: "Файл записи пуст: ${target.absolutePath}"
            } else {
                "Запись остановлена: ${target.absolutePath} (${target.length()} байт" +
                    (failure?.let { ", $it" } ?: "") + ")."
            }
        }
        CameraRecordingState.markStarted(target, withAudio)
        Log.d(
            Camera2Controller.TAG,
            "camera_video_record start: id=${resolved.id} lens=${resolved.lens} " +
                "path=${target.absolutePath} audio=$withAudio size=${videoSize.width}x${videoSize.height}",
        )
        return "Запись видео начата: ${target.absolutePath} " +
            "(${videoSize.width}x${videoSize.height}${if (withAudio) ", со звуком" else ", без звука"}, " +
            "камера ${resolved.id}, ${CameraSelection.label(resolved.lens)}). " +
            "Остановите через camera_record_stop."
    }

    /**
     * Stops the active recording (the caller must hold the engine mutex) and returns only when the
     * finished file exists and is non-empty; otherwise throws so the tool reports an honest failure.
     */
    fun stopOrThrow(): String {
        val active = CameraRecordingState.active
            ?: throw IllegalStateException("Активной записи видео нет.")
        CameraRecordingState.active = null
        val result = active.stop()
        if (result.startsWith("Запись остановлена")) {
            Log.d(Camera2Controller.TAG, "camera_video_record stop: $result")
            return result
        }
        throw IllegalStateException(result)
    }
}
