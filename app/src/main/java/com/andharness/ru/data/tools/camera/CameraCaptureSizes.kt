package com.andharness.ru.data.tools.camera

import android.graphics.ImageFormat
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
import android.util.Size

/**
 * Output-size selection for still (JPEG) and video captures, from
 * `StreamConfigurationMap.getOutputSizes(...)`.
 *
 * References:
 * - `StreamConfigurationMap#getOutputSizes` —
 *   https://developer.android.com/reference/android/hardware/camera2/params/StreamConfigurationMap
 */
internal object CameraCaptureSizes {

    private const val MAX_JPEG_PIXELS = 8_000_000L

    /** Largest supported JPEG size that stays within [MAX_JPEG_PIXELS] (falling back to the largest). */
    fun pickJpegSize(map: StreamConfigurationMap): Size {
        val sizes = map.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
        if (sizes.isEmpty()) throw IllegalStateException("Камера не поддерживает JPEG-съёмку.")
        val capped = sizes.filter { it.width.toLong() * it.height <= MAX_JPEG_PIXELS }
        val pool = if (capped.isNotEmpty()) capped else sizes.toList()
        return pool.maxByOrNull { it.width.toLong() * it.height }
            ?: throw IllegalStateException("Камера не сообщает размеры кадров.")
    }

    /** 720p when offered, otherwise the largest size within 1920×1080. */
    fun pickVideoSize(map: StreamConfigurationMap): Size {
        val sizes = map.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()
        if (sizes.isEmpty()) throw IllegalStateException("Камера не поддерживает запись видео.")
        sizes.firstOrNull { it.width == 1280 && it.height == 720 }?.let { return it }
        return sizes.filter { it.width <= 1920 && it.height <= 1080 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.maxByOrNull { it.width.toLong() * it.height }
            ?: throw IllegalStateException("Камера не сообщает размеры видео.")
    }
}
