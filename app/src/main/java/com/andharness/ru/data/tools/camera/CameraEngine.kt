package com.andharness.ru.data.tools.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.andharness.ru.data.tools.media.MediaScanRegistration
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * In-process Camera2 (framework, no extra dependency) control of the phone camera for the
 * `camera_capture` / `camera_zoom` / `camera_manual` / `camera_burst` / `camera_video_record` /
 * `camera_record_stop` tools. The system camera app is never launched: photo, burst and video are
 * produced here with a real [CameraDevice] + [CameraCaptureSession], so the file exists (and is
 * non-empty) before a tool reports success.
 *
 * Design:
 * - a dedicated [HandlerThread] owns every camera object; all suspending entry points run on its
 *   single-thread dispatcher, serialized by a [Mutex] (camera objects are not thread-safe);
 * - the camera is chosen by lens (`front` / `back`, default `back`) or by an explicit `camera_id`
 *   ([CameraLensResolver] maps the request through the pure [CameraSelection] logic);
 * - every produced file (photo, burst frame, video) is registered in the system MediaStore through
 *   [MediaScanRegistration] after the capture, so it appears in the device Gallery;
 * - one still-capture session is kept open and reused across calls, remembering the last applied
 *   [Settings] (zoom / ISO / exposure / focus) so a later tool call inherits them; the session is
 *   dropped automatically when the system disconnects the camera and is re-opened when a different
 *   camera id is requested;
 * - `camera_capture` closes its session right after the shot so the tool never holds the camera open
 *   in the background; `camera_zoom` / `camera_burst` keep it for the next call;
 * - video recording lives in [CameraRecordingController].
 *
 * This is the implementation behind the thin [Camera2Controller] façade.
 */
internal class CameraEngine {

    private val thread = HandlerThread("andharness-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val dispatcher = handler.asCoroutineDispatcher()
    private val mutex = Mutex()

    private val lenses = CameraLensResolver()
    private val sizes = CameraCaptureSizes
    private val factory = CameraSessionFactory(handler)
    private val recording = CameraRecordingController(handler, lenses, sizes, factory) { closeSession() }

    private class Session(
        val cameraId: String,
        val lens: CameraSelection.Lens,
        val device: CameraDevice,
        val reader: ImageReader,
        val capture: CameraCaptureSession,
    )

    /** Still-capture settings remembered for the open session and reapplied on every request. */
    private data class Settings(
        val zoomRatio: Float? = null,
        val iso: Int? = null,
        val exposureNs: Long? = null,
        val focusDiopters: Float? = null,
    )

    private var session: Session? = null
    private var settings = Settings()

    // ---------------------------------------------------------------- public tools

    /**
     * `camera_capture` (photo): captures one JPEG in-process from the requested camera and writes it
     * to [target]. The session is closed right afterwards so the tool never holds the camera open in
     * the background. Success is reported only once [target] exists and is non-empty
     * ([CameraFiles.writeAtomically] throws otherwise). The saved file is then registered in the
     * MediaStore ([MediaScanRegistration]); a scan problem is reported in the message but never fails
     * the call.
     */
    suspend fun capturePhoto(
        context: Context,
        target: File,
        lens: String? = null,
        cameraId: String? = null,
    ): String = withContext(dispatcher) {
        val description = mutex.withLock {
            val manager = lenses.cameraManager(context)
            val resolved = lenses.resolve(manager, lens, cameraId)
            val characteristics = manager.getCameraCharacteristics(resolved.id)
            try {
                target.parentFile?.mkdirs()
                val active = ensureSession(context, resolved)
                val bytes = captureJpeg(active, characteristics)
                CameraFiles.writeAtomically(target, bytes)
                Log.d(
                    Camera2Controller.TAG,
                    "camera_capture photo: id=${resolved.id} lens=${resolved.lens} " +
                        "path=${target.absolutePath} bytes=${target.length()}",
                )
                "Фото снято: ${target.absolutePath} (${target.length()} байт, " +
                    "камера ${resolved.id} — ${CameraSelection.label(resolved.lens)})."
            } finally {
                closeSession()
            }
        }
        // Gallery registration runs after the session is closed, outside the camera mutex: a slow
        // MediaStore scan must not keep the camera open or delay the next tool call.
        "$description ${MediaScanRegistration.register(context, target).galleryNote}"
    }

    /** `camera_zoom`: applies [level] (e.g. "2.0") or [step] ("in"/"out") to the requested camera. */
    suspend fun zoom(
        context: Context,
        level: String?,
        step: String?,
        lens: String? = null,
        cameraId: String? = null,
    ): String = withContext(dispatcher) {
        mutex.withLock {
            val manager = lenses.cameraManager(context)
            val resolved = lenses.resolve(manager, lens, cameraId)
            val characteristics = manager.getCameraCharacteristics(resolved.id)
            val minZoom = CameraZoomMath.minZoom(characteristics)
            val maxZoom = CameraZoomMath.maxZoom(characteristics)

            val requested: Float = when {
                !level.isNullOrBlank() ->
                    level.trim().toFloatOrNull()
                        ?: throw IllegalArgumentException("level должен быть числом, например 2.0.")
                !step.isNullOrBlank() -> {
                    val current = settings.zoomRatio ?: minZoom
                    when (step.trim().lowercase()) {
                        "in", "in+", "увеличить", "ближе" -> current * 1.5f
                        "out", "out-", "уменьшить", "дальше" -> current / 1.5f
                        else -> throw IllegalArgumentException("step должен быть in или out.")
                    }
                }
                else -> throw IllegalArgumentException("Укажите level (например 2.0) или step (in|out).")
            }
            val applied = CameraZoomMath.clamp(requested, minZoom, maxZoom)

            val active = ensureSession(context, resolved)
            settings = settings.copy(zoomRatio = applied)
            captureJpeg(active, characteristics)
            "Зум применён: ${CameraZoomMath.format(applied)}× (поддерживается " +
                "${CameraZoomMath.format(minZoom)}–${CameraZoomMath.format(maxZoom)}×; " +
                "камера ${resolved.id}, ${CameraSelection.label(resolved.lens)})."
        }
    }

    /** `camera_manual`: manual ISO / exposure / focus through a Camera2 `CaptureRequest`. */
    suspend fun manual(
        context: Context,
        iso: String?,
        exposureMs: String?,
        focusDistance: String?,
        lens: String? = null,
        cameraId: String? = null,
    ): String = withContext(dispatcher) {
        mutex.withLock {
            if (iso.isNullOrBlank() && exposureMs.isNullOrBlank() && focusDistance.isNullOrBlank()) {
                throw IllegalArgumentException("Укажите хотя бы один параметр: iso, exposure_ms или focus_distance.")
            }
            val manager = lenses.cameraManager(context)
            val resolved = lenses.resolve(manager, lens, cameraId)
            val characteristics = manager.getCameraCharacteristics(resolved.id)
            val notes = mutableListOf<String>()
            var next = settings

            if (!iso.isNullOrBlank()) {
                val range = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    ?: throw IllegalStateException("Камера не сообщает диапазон ISO — ручной ISO не поддерживается.")
                val value = iso.trim().toIntOrNull()
                    ?: throw IllegalArgumentException("iso должен быть целым числом, например 100.")
                val clamped = value.coerceIn(range.lower, range.upper)
                next = next.copy(iso = clamped)
                notes += "ISO=$clamped (диапазон ${range.lower}–${range.upper})"
            }

            if (!exposureMs.isNullOrBlank()) {
                val range = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    ?: throw IllegalStateException("Камера не сообщает диапазон выдержки — ручная выдержка не поддерживается.")
                val ms = exposureMs.trim().toDoubleOrNull()
                    ?: throw IllegalArgumentException("exposure_ms должен быть числом, например 100.")
                val requestedNs = (ms.coerceIn(0.0, MAX_EXPOSURE_MS) * 1_000_000.0).toLong()
                val clampedNs = requestedNs.coerceIn(range.lower.coerceAtLeast(1L), range.upper)
                next = next.copy(exposureNs = clampedNs)
                notes += "Выдержка=${CameraZoomMath.format(clampedNs / 1_000_000.0)} мс " +
                    "(диапазон ${CameraZoomMath.format(range.lower / 1_000_000.0)}–" +
                    "${CameraZoomMath.format(range.upper / 1_000_000.0)} мс)"
            }

            if (!focusDistance.isNullOrBlank()) {
                val minimum = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                if (minimum <= 0f) {
                    throw IllegalStateException("Камера не поддерживает ручную фокусировку (нет LENS_INFO_MINIMUM_FOCUS_DISTANCE).")
                }
                val diopters = focusDistance.trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("focus_distance должен быть числом (диоптрии).")
                val clamped = diopters.coerceIn(0f, minimum)
                next = next.copy(focusDiopters = clamped)
                notes += "Фокус=${CameraZoomMath.format(clamped)} дптр (0 = бесконечность, " +
                    "максимум ${CameraZoomMath.format(minimum)})"
            }

            val active = ensureSession(context, resolved)
            settings = next
            captureJpeg(active, characteristics)
            "Ручные параметры применены (камера ${resolved.id}, " +
                "${CameraSelection.label(resolved.lens)}): ${notes.joinToString(", ")}."
        }
    }

    /**
     * `camera_burst`: [count] stills (max [Camera2Controller.MAX_BURST]) into [destinationDir]. All
     * saved frames are registered in the MediaStore with one bounded [MediaScanRegistration] call.
     */
    suspend fun burst(
        context: Context,
        count: Int,
        intervalMs: Long,
        destinationDir: File,
        lens: String? = null,
        cameraId: String? = null,
    ): String = withContext(dispatcher) {
        val (frames, description) = mutex.withLock {
            if (count < 1) throw IllegalArgumentException("count должен быть не меньше 1.")
            val total = count.coerceAtMost(Camera2Controller.MAX_BURST)
            if (!destinationDir.exists() && !destinationDir.mkdirs()) {
                throw IllegalStateException("Не удалось создать папку: ${destinationDir.absolutePath}")
            }
            if (!destinationDir.isDirectory) {
                throw IllegalStateException("Это не папка: ${destinationDir.absolutePath}")
            }

            val manager = lenses.cameraManager(context)
            val resolved = lenses.resolve(manager, lens, cameraId)
            val characteristics = manager.getCameraCharacteristics(resolved.id)
            val active = ensureSession(context, resolved)

            val saved = mutableListOf<File>()
            var bytesTotal = 0L
            for (index in 1..total) {
                if (index > 1 && intervalMs > 0L) delay(intervalMs)
                val bytes = captureJpeg(active, characteristics)
                val file = File(destinationDir, "burst_${System.currentTimeMillis()}_$index.jpg")
                CameraFiles.writeAtomically(file, bytes)
                check(file.length() > 0L) { "Кадр $index записан пустым: ${file.name}" }
                bytesTotal += file.length()
                saved += file
            }
            val truncated = if (count > Camera2Controller.MAX_BURST) {
                " (запрошено $count, ограничено ${Camera2Controller.MAX_BURST})"
            } else {
                ""
            }
            Log.d(
                Camera2Controller.TAG,
                "camera_burst: id=${resolved.id} lens=${resolved.lens} frames=${saved.size} " +
                    "bytes=$bytesTotal dir=${destinationDir.absolutePath}",
            )
            saved to (
                "Серия снята: ${saved.size} кадр(ов)$truncated в ${destinationDir.absolutePath}, " +
                    "${bytesTotal} байт (камера ${resolved.id}, " +
                    "${CameraSelection.label(resolved.lens)}). Файлы: ${saved.joinToString(", ") { it.name }}"
                )
        }
        val scan = MediaScanRegistration.register(context, frames)
        "$description ${scan.galleryNote}"
    }

    /**
     * `camera_video_record`: starts recording into [target]. When [durationMs] is given the call
     * records for that long and stops; otherwise it returns immediately and the recording keeps
     * running until `camera_record_stop`.
     */
    suspend fun videoRecord(
        context: Context,
        target: File,
        withAudio: Boolean,
        durationMs: Long?,
        lens: String? = null,
        cameraId: String? = null,
    ): String {
        val started = withContext(dispatcher) {
            mutex.withLock { recording.start(context, target, withAudio, lens, cameraId) }
        }
        if (durationMs == null) return started
        delay(durationMs.coerceAtLeast(1L))
        val stopped = withContext(dispatcher) { mutex.withLock { recording.stopOrThrow() } }
        val scan = MediaScanRegistration.register(context, target)
        return "$started\n$stopped ${scan.galleryNote}"
    }

    /**
     * `camera_record_stop`: stops the recording started by `camera_video_record`, if any, and
     * registers the finished file in the MediaStore ([MediaScanRegistration]).
     */
    suspend fun stopVideo(context: Context): String = withContext(dispatcher) {
        val (stopped, recorded) = mutex.withLock {
            val target = CameraRecordingState.active?.target
            recording.stopOrThrow() to target
        }
        if (recorded == null) {
            stopped
        } else {
            "$stopped ${MediaScanRegistration.register(context, recorded).galleryNote}"
        }
    }

    // ---------------------------------------------------------------- session lifecycle

    /** Opens (or reuses) the still-capture session; the camera stays owned by this process. */
    private suspend fun ensureSession(context: Context, camera: ResolvedCamera): Session {
        session?.let { existing ->
            if (existing.cameraId == camera.id) return existing
            // A different camera was requested: drop the old session and the zoom/ISO/focus settings
            // that only make sense for the camera they were applied to.
            closeSession()
            settings = Settings()
        }
        val manager = lenses.cameraManager(context)
        val characteristics = manager.getCameraCharacteristics(camera.id)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("Камера не сообщает конфигурацию потоков.")
        val size = sizes.pickJpegSize(map)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        val device = factory.openDevice(manager, camera.id, ::clearSessionOnDisconnect)
        val capture = try {
            factory.createSession(device, listOf(reader.surface))
        } catch (e: Exception) {
            runCatching { device.close() }
            runCatching { reader.close() }
            throw e
        }
        val opened = Session(camera.id, camera.lens, device, reader, capture)
        session = opened
        return opened
    }

    private fun closeSession() {
        session?.let { active ->
            runCatching { active.capture.close() }
            runCatching { active.reader.close() }
            runCatching { active.device.close() }
        }
        session = null
    }

    /** Drops the cached session/settings after the system disconnected the camera. */
    private fun clearSessionOnDisconnect() {
        session?.let { stale ->
            runCatching { stale.capture.close() }
            runCatching { stale.reader.close() }
        }
        session = null
        settings = Settings()
    }

    // ---------------------------------------------------------------- still capture

    private suspend fun captureJpeg(active: Session, characteristics: CameraCharacteristics): ByteArray {
        val request = buildStillRequest(active, characteristics)
        try {
            return withTimeout(CAPTURE_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    active.reader.setOnImageAvailableListener({ reader ->
                        val image = try {
                            reader.acquireNextImage()
                        } catch (e: Exception) {
                            null
                        }
                        if (image == null) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(IllegalStateException("Кадр не получен."))
                            }
                            return@setOnImageAvailableListener
                        }
                        try {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            if (continuation.isActive) continuation.resume(bytes)
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        } finally {
                            image.close()
                        }
                    }, handler)
                    try {
                        active.capture.capture(request, null, handler)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        } finally {
            active.reader.setOnImageAvailableListener(null, handler)
        }
    }

    private fun buildStillRequest(active: Session, characteristics: CameraCharacteristics): CaptureRequest {
        val builder = active.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        builder.addTarget(active.reader.surface)
        applySettings(builder, characteristics)
        return builder.build()
    }

    private fun applySettings(builder: CaptureRequest.Builder, characteristics: CameraCharacteristics) {
        val current = settings
        current.zoomRatio?.let { ratio ->
            if (ratio > 1.001f) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, ratio)
                } else {
                    CameraZoomMath.cropRegion(characteristics, ratio)
                        ?.let { builder.set(CaptureRequest.SCALER_CROP_REGION, it) }
                }
            }
        }
        if (current.iso != null || current.exposureNs != null) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            current.iso?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
            current.exposureNs?.let { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
        }
        current.focusDiopters?.let { diopters ->
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, diopters)
        }
    }

    private companion object {
        private const val MAX_EXPOSURE_MS = 1000.0
        private const val CAPTURE_TIMEOUT_MS = 60_000L
    }
}
