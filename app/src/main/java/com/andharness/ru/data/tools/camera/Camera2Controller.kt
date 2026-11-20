package com.andharness.ru.data.tools.camera

import android.content.Context
import java.io.File

/**
 * Public façade for the in-process Camera2 (framework, no extra dependency) control of the phone
 * camera used by the `camera_capture` / `camera_zoom` / `camera_manual` / `camera_burst` /
 * `camera_video_record` / `camera_record_stop` tools. The system camera app is never launched:
 * photo, burst and video are produced with a real `CameraDevice` + `CameraCaptureSession`, so the
 * file exists (and is non-empty) before a tool reports success.
 *
 * The work is split into collaborators: [CameraEngine] owns the camera thread, the still-capture
 * session and the capture/zoom/manual/burst calls; [CameraRecordingController] owns video recording;
 * [CameraLensResolver] maps the requested lens/id; [CameraCaptureSizes] picks output sizes;
 * [CameraZoomMath] holds the zoom/exposure math; [CameraFiles] writes atomically.
 *
 * Documentation used for every API below:
 * - Camera2 overview / capture sessions —
 *   https://developer.android.com/media/camera/camera2
 * - `CameraManager.getCameraIdList()` and `CameraCharacteristics.LENS_FACING` (lens selection) —
 *   https://developer.android.com/reference/android/hardware/camera2/CameraManager
 *   https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#LENS_FACING
 * - `CameraDevice.StateCallback`, `CameraCaptureSession.StateCallback` —
 *   https://developer.android.com/reference/android/hardware/camera2/CameraDevice.StateCallback
 *   https://developer.android.com/reference/android/hardware/camera2/CameraCaptureSession.StateCallback
 * - `CameraCharacteristics` (SENSOR_INFO_SENSITIVITY_RANGE, SENSOR_INFO_EXPOSURE_TIME_RANGE,
 *   LENS_INFO_MINIMUM_FOCUS_DISTANCE, SCALER_AVAILABLE_MAX_DIGITAL_ZOOM, SENSOR_ORIENTATION,
 *   SENSOR_INFO_ACTIVE_ARRAY_SIZE, CONTROL_ZOOM_RATIO_RANGE) —
 *   https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics
 * - `CaptureRequest` (CONTROL_AE_MODE/CONTROL_AE_MODE_OFF, SENSOR_SENSITIVITY, SENSOR_EXPOSURE_TIME,
 *   CONTROL_AF_MODE/CONTROL_AF_MODE_OFF, LENS_FOCUS_DISTANCE, CONTROL_ZOOM_RATIO, SCALER_CROP_REGION) —
 *   https://developer.android.com/reference/android/hardware/camera2/CaptureRequest
 * - `ImageReader` JPEG capture —
 *   https://developer.android.com/reference/android/media/ImageReader
 * - Camera2 + `MediaRecorder` video (`MediaRecorder#getSurface`, `TEMPLATE_RECORD`) —
 *   https://developer.android.com/media/camera/camera2
 *   https://developer.android.com/reference/android/media/MediaRecorder
 * - `StreamConfigurationMap#getOutputSizes` for video sizes —
 *   https://developer.android.com/reference/android/hardware/camera2/params/StreamConfigurationMap
 * - `Handler.asCoroutineDispatcher()` —
 *   https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-android/kotlinx.coroutines.android/as-coroutine-dispatcher.html
 * - `MediaScannerConnection.scanFile(...)` for gallery registration ([MediaScanRegistration]) —
 *   https://developer.android.com/reference/android/media/MediaScannerConnection#scanFile(android.content.Context,%20java.lang.String%5B%5D,%20java.lang.String%5B%5D,%20android.media.MediaScannerConnection.OnScanCompletedListener)
 */
object Camera2Controller {

    /** Tag used for the on-device evidence that a capture really went through Camera2. */
    const val TAG = "Andharness"

    /** Hard cap on `camera_burst` frames. */
    const val MAX_BURST = 20

    private val engine = CameraEngine()

    /** `camera_capture`: one JPEG from [lens]/[cameraId] written to [target]. */
    suspend fun capturePhoto(
        context: Context,
        target: File,
        lens: String? = null,
        cameraId: String? = null,
    ): String = engine.capturePhoto(context, target, lens, cameraId)

    /** `camera_zoom`: applies [level] (e.g. "2.0") or [step] ("in"/"out"). */
    suspend fun zoom(
        context: Context,
        level: String?,
        step: String?,
        lens: String? = null,
        cameraId: String? = null,
    ): String = engine.zoom(context, level, step, lens, cameraId)

    /** `camera_manual`: manual ISO / exposure / focus through a Camera2 `CaptureRequest`. */
    suspend fun manual(
        context: Context,
        iso: String?,
        exposureMs: String?,
        focusDistance: String?,
        lens: String? = null,
        cameraId: String? = null,
    ): String = engine.manual(context, iso, exposureMs, focusDistance, lens, cameraId)

    /** `camera_burst`: [count] stills (max [MAX_BURST]) into [destinationDir]. */
    suspend fun burst(
        context: Context,
        count: Int,
        intervalMs: Long,
        destinationDir: File,
        lens: String? = null,
        cameraId: String? = null,
    ): String = engine.burst(context, count, intervalMs, destinationDir, lens, cameraId)

    /** `camera_video_record`: starts recording, optionally stopping after [durationMs]. */
    suspend fun videoRecord(
        context: Context,
        target: File,
        withAudio: Boolean,
        durationMs: Long?,
        lens: String? = null,
        cameraId: String? = null,
    ): String = engine.videoRecord(context, target, withAudio, durationMs, lens, cameraId)

    /** `camera_record_stop`: stops the active recording and registers the finished file. */
    suspend fun stopVideo(context: Context): String = engine.stopVideo(context)
}
