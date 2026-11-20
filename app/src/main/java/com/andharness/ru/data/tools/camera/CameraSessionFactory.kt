package com.andharness.ru.data.tools.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.view.Surface
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Framework wrappers that open a [CameraDevice] and configure a [CameraCaptureSession] on the
 * engine's single camera [Handler] thread.
 *
 * References:
 * - `CameraDevice.StateCallback`, `CameraCaptureSession.StateCallback` —
 *   https://developer.android.com/reference/android/hardware/camera2/CameraDevice.StateCallback
 *   https://developer.android.com/reference/android/hardware/camera2/CameraCaptureSession.StateCallback
 */
internal class CameraSessionFactory(private val handler: Handler) {

    /**
     * Opens [cameraId], suspending until the device is ready. [onDisconnected] is invoked when the
     * system takes the camera away, so the owner can drop its cached session/settings.
     */
    suspend fun openDevice(
        manager: CameraManager,
        cameraId: String,
        onDisconnected: () -> Unit,
    ): CameraDevice = suspendCancellableCoroutine { continuation ->
        val callback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (continuation.isActive) continuation.resume(camera) else camera.close()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                onDisconnected()
                if (continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException("Камера отключена системой."))
                }
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                if (continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException("Ошибка камеры (код $error)."))
                }
            }
        }
        try {
            manager.openCamera(cameraId, callback, handler)
        } catch (e: Exception) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
    }

    /** Creates a capture session for [outputs], suspending until it is configured. */
    suspend fun createSession(device: CameraDevice, outputs: List<Surface>): CameraCaptureSession =
        suspendCancellableCoroutine { continuation ->
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (continuation.isActive) continuation.resume(session) else session.close()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("Не удалось настроить сессию захвата."))
                    }
                }
            }
            try {
                @Suppress("DEPRECATION")
                device.createCaptureSession(outputs, callback, handler)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
}
