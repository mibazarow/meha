package com.andharness.ru.data.tools.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/**
 * Resolves the requested camera (by lens or explicit id) through the pure [CameraSelection] logic.
 *
 * `CameraCharacteristics.LENS_FACING` is mapped to [CameraSelection.Lens]; a missing lens or an
 * unknown id becomes an honest [IllegalArgumentException] that the tool layer reports as a failure.
 *
 * References:
 * - `CameraManager.getCameraIdList()` / `CameraCharacteristics.LENS_FACING` —
 *   https://developer.android.com/reference/android/hardware/camera2/CameraManager
 *   https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#LENS_FACING
 */
internal class CameraLensResolver {

    /** The system camera service; never null on a device with a camera. */
    fun cameraManager(context: Context): CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: throw IllegalStateException("Служба камер недоступна.")

    /**
     * Maps every `cameraIdList()` entry plus its `LENS_FACING` into the pure [CameraSelection] and
     * resolves the requested lens / explicit id.
     */
    fun resolve(manager: CameraManager, lens: String?, cameraId: String?): ResolvedCamera {
        val cameras = manager.cameraIdList.mapIndexed { index, id ->
            CameraSelection.Camera(
                id = id,
                lens = facingOf(manager.getCameraCharacteristics(id)),
                index = index,
            )
        }
        return when (val result = CameraSelection.resolve(lens, cameraId, cameras)) {
            is CameraSelection.Result.Found -> ResolvedCamera(result.id, result.lens)
            is CameraSelection.Result.Missing -> throw IllegalArgumentException(result.message)
        }
    }

    /** `CameraCharacteristics.LENS_FACING` → pure [CameraSelection.Lens]. */
    private fun facingOf(characteristics: CameraCharacteristics): CameraSelection.Lens =
        when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_FRONT -> CameraSelection.Lens.FRONT
            CameraCharacteristics.LENS_FACING_BACK -> CameraSelection.Lens.BACK
            CameraCharacteristics.LENS_FACING_EXTERNAL -> CameraSelection.Lens.EXTERNAL
            else -> CameraSelection.Lens.UNKNOWN
        }
}
