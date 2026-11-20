package com.andharness.ru.data.tools.camera

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import java.util.Locale

/**
 * Pure-ish zoom/exposure math over [CameraCharacteristics]: supported zoom range, the pre-API-30
 * crop-region fallback, clamping and numeric formatting for honest tool messages.
 *
 * References:
 * - `CONTROL_ZOOM_RATIO_RANGE`, `SCALER_AVAILABLE_MAX_DIGITAL_ZOOM`,
 *   `SENSOR_INFO_ACTIVE_ARRAY_SIZE` —
 *   https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics
 */
internal object CameraZoomMath {

    fun minZoom(characteristics: CameraCharacteristics): Float =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower ?: 1f
        } else {
            1f
        }

    fun maxZoom(characteristics: CameraCharacteristics): Float {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper?.let { return it }
        }
        return characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
    }

    fun cropRegion(characteristics: CameraCharacteristics, zoom: Float): Rect? {
        val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val width = (active.width() / zoom).toInt()
        val height = (active.height() / zoom).toInt()
        if (width <= 0 || height <= 0) return active
        val dx = (active.width() - width) / 2
        val dy = (active.height() - height) / 2
        return Rect(active.left + dx, active.top + dy, active.left + dx + width, active.top + dy + height)
    }

    fun clamp(value: Float, minimum: Float, maximum: Float): Float =
        if (maximum <= minimum) minimum else value.coerceIn(minimum, maximum)

    fun format(value: Float): String =
        if (value == value.toLong().toFloat()) value.toLong().toString() else String.format(Locale.US, "%.2f", value)

    fun format(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else String.format(Locale.US, "%.2f", value)
}
