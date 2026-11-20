package com.andharness.ru.data.tools.camera

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import com.andharness.ru.data.tools.allowedToolRoots
import com.andharness.ru.data.tools.resolveSafePath
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Builds the camera [ToolHandler]s.
 *
 * `camera_capture` performs an **in-process, background** photo or video capture through
 * [Camera2Controller]: the system camera app is never launched (`ACTION_IMAGE_CAPTURE` /
 * `ACTION_VIDEO_CAPTURE` are not used), so the agent does not wait for a human shutter press, and
 * success is reported only after the file exists and is non-empty.
 *
 * For photos, `camera_capture` also accepts `analyze` (default `true`): after the file is saved it
 * asks [PhotoAnalyzer] to look at it. If the configured model supports images, the photo is sent and
 * its description is returned; otherwise the photo is NOT sent and the tool falls back to on-device
 * OCR, saying so verbatim ([com.andharness.ru.domain.vision.PhotoAnalysis.VISION_UNAVAILABLE_NOTE]).
 * `analyze=false` keeps the old "capture only" behaviour. Video is never analyzed.
 *
 * `camera_list` reports the camera characteristics (including the id to pass as `camera_id`).
 *
 * `camera_zoom` / `camera_manual` / `camera_burst` / `camera_video_record` / `camera_record_stop`
 * drive the same in-process Camera2 session. All of them, `camera_capture` included, accept the
 * lens selection parameters:
 * - `lens` — `front` or `back` (default `back`);
 * - `camera_id` — an explicit id taken from `camera_list`, which wins over `lens`.
 * A requested lens that is absent on the device is reported honestly instead of silently falling
 * back to another camera (see [CameraSelection]).
 *
 * References also used by the controller: Camera2 overview and `CameraManager` —
 * https://developer.android.com/media/camera/camera2
 * https://developer.android.com/reference/android/hardware/camera2/CameraManager
 * - `PackageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)` to detect a camera:
 *   https://developer.android.com/reference/android/content/pm/PackageManager#FEATURE_CAMERA_ANY
 */
class CameraToolsHandler(
    private val context: Context,
    private val photoAnalyzer: PhotoAnalyzer,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "camera_capture"
            override suspend fun execute(arguments: JsonObject): ToolResult = try {
                if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                    ToolResult(false, "На устройстве нет камеры.")
                } else {
                    val type = captureType(arguments)
                    val target = destination(arguments, type)
                    val lens = (arguments["lens"] as? JsonPrimitive)?.content
                    val cameraId = (arguments["camera_id"] as? JsonPrimitive)?.content
                    var message = if (type == "video") {
                        Camera2Controller.videoRecord(
                            context = context,
                            target = target,
                            withAudio = (arguments["with_audio"] as? JsonPrimitive)?.content
                                ?.toBooleanStrictOrNull() ?: true,
                            durationMs = videoDurationMs(arguments),
                            lens = lens,
                            cameraId = cameraId,
                        )
                    } else {
                        Camera2Controller.capturePhoto(context, target, lens, cameraId)
                    }
                    // Photo analysis is opt-out (default true): describe it when the model supports
                    // images, otherwise honestly fall back to on-device OCR. Video is never analyzed.
                    val analyze = (arguments["analyze"] as? JsonPrimitive)?.content
                        ?.trim()?.lowercase()?.toBooleanStrictOrNull() ?: true
                    if (type != "video" && analyze) {
                        val report = photoAnalyzer.analyze(target)
                        message = "$message\n${report.text}"
                    }
                    ToolResult(true, message)
                }
            } catch (e: SecurityException) {
                ToolResult(false, "Нет разрешения на камеру (нужен CAMERA).")
            } catch (e: IllegalArgumentException) {
                ToolResult(false, e.message ?: "Некорректные параметры съёмки.")
            } catch (e: IllegalStateException) {
                ToolResult(false, "Съёмка не удалась: ${e.message ?: "камера недоступна."}")
            } catch (e: Exception) {
                ToolResult(false, "Ошибка съёмки: ${e.message}")
            }
        },
        object : ToolHandler {
            override val name = "camera_list"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                        return@withContext ToolResult(false, "На устройстве нет камеры.")
                    }
                    val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                        ?: return@withContext ToolResult(false, "Служба камер недоступна.")

                    // getCameraIdList() throws CameraAccessException; getCameraCharacteristics(id)
                    // throws CameraAccessException and needs the CAMERA permission on many devices.
                    // References: CameraManager and CameraCharacteristics —
                    // https://developer.android.com/reference/android/hardware/camera2/CameraManager
                    // https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics
                    val ids = manager.cameraIdList
                    if (ids.isEmpty()) {
                        return@withContext ToolResult(true, "Камеры не найдены.")
                    }

                    val report = StringBuilder("Камеры: ${ids.size}")
                    for (id in ids) {
                        val ch = manager.getCameraCharacteristics(id)
                        val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
                            CameraCharacteristics.LENS_FACING_FRONT -> "фронтальная (lens=front)"
                            CameraCharacteristics.LENS_FACING_BACK -> "основная (lens=back)"
                            CameraCharacteristics.LENS_FACING_EXTERNAL -> "внешняя (lens=external)"
                            else -> "неизвестно"
                        }
                        report.append("\n\n[id=$id] — передайте camera_id=\"$id\"")
                        report.append("\n  Положение: $facing")

                        // SENSOR_ORIENTATION / FLASH_INFO_AVAILABLE / LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                        // are CameraCharacteristics keys; SCALER_STREAM_CONFIGURATION_MAP exposes the
                        // supported output sizes. References:
                        // https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics
                        // https://developer.android.com/reference/android/hardware/camera2/params/StreamConfigurationMap
                        ch.get(CameraCharacteristics.SENSOR_ORIENTATION)?.let {
                            report.append("\n  Ориентация сенсора: $it°")
                        }
                        val flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
                        report.append("\n  Вспышка: ${if (flash == true) "есть" else "нет"}")
                        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        if (focal != null && focal.isNotEmpty()) {
                            report.append("\n  Фокусные расстояния: ${focal.joinToString(", ")} мм")
                        }
                        val sizes: Array<Size> =
                            ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                                ?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
                        if (sizes.isNotEmpty()) {
                            report.append(
                                "\n  Размеры JPEG (до 5): " +
                                    sizes.take(5).joinToString(", ") { "${it.width}x${it.height}" },
                            )
                        }
                    }
                    ToolResult(true, report.toString())
                } catch (e: SecurityException) {
                    ToolResult(false, "Нет разрешения на камеру (нужен CAMERA).")
                } catch (e: CameraAccessException) {
                    ToolResult(false, "Не удалось получить характеристики камер: ${e.message}")
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        // ---- programmatic Camera2 control (zoom / manual / burst / video) ----
        object : ToolHandler {
            override val name = "camera_zoom"
            override suspend fun execute(arguments: JsonObject): ToolResult =
                cameraResult("Зум") {
                    Camera2Controller.zoom(
                        context = context,
                        level = (arguments["level"] as? JsonPrimitive)?.content,
                        step = (arguments["step"] as? JsonPrimitive)?.content,
                        lens = (arguments["lens"] as? JsonPrimitive)?.content,
                        cameraId = (arguments["camera_id"] as? JsonPrimitive)?.content,
                    )
                }
        },
        object : ToolHandler {
            override val name = "camera_manual"
            override suspend fun execute(arguments: JsonObject): ToolResult =
                cameraResult("Ручные параметры") {
                    Camera2Controller.manual(
                        context = context,
                        iso = (arguments["iso"] as? JsonPrimitive)?.content,
                        exposureMs = (arguments["exposure_ms"] as? JsonPrimitive)?.content,
                        focusDistance = (arguments["focus_distance"] as? JsonPrimitive)?.content,
                        lens = (arguments["lens"] as? JsonPrimitive)?.content,
                        cameraId = (arguments["camera_id"] as? JsonPrimitive)?.content,
                    )
                }
        },
        object : ToolHandler {
            override val name = "camera_burst"
            override suspend fun execute(arguments: JsonObject): ToolResult = try {
                val count = (arguments["count"] as? JsonPrimitive)?.content?.toIntOrNull()
                if (count == null) {
                    ToolResult(false, "Укажите count — число кадров (1–${Camera2Controller.MAX_BURST}).")
                } else {
                    val intervalMs = ((arguments["interval_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L)
                        .coerceIn(0L, 5_000L)
                    val directory = resolveSafePath(
                        context.allowedToolRoots(),
                        (arguments["destination_dir"] as? JsonPrimitive)?.content ?: "",
                    )
                    ToolResult(
                        true,
                        Camera2Controller.burst(
                            context = context,
                            count = count,
                            intervalMs = intervalMs,
                            destinationDir = directory,
                            lens = (arguments["lens"] as? JsonPrimitive)?.content,
                            cameraId = (arguments["camera_id"] as? JsonPrimitive)?.content,
                        ),
                    )
                }
            } catch (e: IllegalArgumentException) {
                ToolResult(false, e.message ?: "Некорректные параметры.")
            } catch (e: SecurityException) {
                ToolResult(false, "Нет доступа к хранилищу или камере.")
            } catch (e: Exception) {
                ToolResult(false, "Ошибка серийной съёмки: ${e.message}")
            }
        },
        object : ToolHandler {
            override val name = "camera_video_record"
            override suspend fun execute(arguments: JsonObject): ToolResult = try {
                val rawPath = (arguments["path"] as? JsonPrimitive)?.content
                if (rawPath.isNullOrBlank()) {
                    ToolResult(false, "Укажите path — путь к файлу видео.")
                } else {
                    val target: File = resolveSafePath(context.allowedToolRoots(), rawPath)
                    if (!target.name.contains('.')) {
                        ToolResult(false, "Укажите путь с расширением файла, например DCIM/video.mp4.")
                    } else {
                        val withAudio = (arguments["with_audio"] as? JsonPrimitive)?.content
                            ?.lowercase()?.toBooleanStrictOrNull() ?: true
                        val durationMs = (arguments["duration_ms"] as? JsonPrimitive)?.content?.toLongOrNull()
                            ?.takeIf { it > 0L }
                        ToolResult(
                            true,
                            Camera2Controller.videoRecord(
                                context = context,
                                target = target,
                                withAudio = withAudio,
                                durationMs = durationMs,
                                lens = (arguments["lens"] as? JsonPrimitive)?.content,
                                cameraId = (arguments["camera_id"] as? JsonPrimitive)?.content,
                            ),
                        )
                    }
                }
            } catch (e: IllegalArgumentException) {
                ToolResult(false, e.message ?: "Некорректные параметры.")
            } catch (e: SecurityException) {
                ToolResult(false, "Нет доступа к камере, микрофону или хранилищу.")
            } catch (e: IllegalStateException) {
                ToolResult(false, "Ошибка записи видео: ${e.message ?: "камера недоступна."}")
            } catch (e: Exception) {
                ToolResult(false, "Ошибка записи видео: ${e.message}")
            }
        },
        object : ToolHandler {
            override val name = "camera_record_stop"
            override suspend fun execute(arguments: JsonObject): ToolResult = try {
                ToolResult(true, Camera2Controller.stopVideo(context))
            } catch (e: IllegalStateException) {
                ToolResult(false, e.message ?: "Остановка записи не удалась.")
            } catch (e: Exception) {
                ToolResult(false, "Ошибка остановки записи: ${e.message}")
            }
        },
    )

    /** Maps the controller's honest exceptions onto a [ToolResult] without faking success. */
    private suspend fun cameraResult(label: String, block: suspend () -> String): ToolResult = try {
        ToolResult(true, block())
    } catch (e: SecurityException) {
        ToolResult(false, "Нет разрешения на камеру (нужен CAMERA).")
    } catch (e: IllegalArgumentException) {
        ToolResult(false, e.message ?: "Некорректные параметры.")
    } catch (e: IllegalStateException) {
        ToolResult(false, "$label: ${e.message ?: "камера недоступна."}")
    } catch (e: Exception) {
        ToolResult(false, "$label: ${e.message}")
    }

    /**
     * Picks photo or video: an explicit `type` wins, otherwise the destination extension decides.
     */
    private fun captureType(arguments: JsonObject): String {
        val explicit = (arguments["type"] as? JsonPrimitive)?.content?.trim()?.lowercase()
        if (explicit == "video") return "video"
        if (explicit == "photo" || explicit == "image") return "photo"
        val path = (arguments["path"] as? JsonPrimitive)?.content?.trim()?.lowercase().orEmpty()
        return if (VIDEO_EXTENSIONS.any { path.endsWith(it) }) "video" else "photo"
    }

    /**
     * Destination file: the optional `path` argument resolved inside the allowed storage roots,
     * otherwise a generated `DCIM/Meha/…` name. A missing extension is filled in from the
     * capture type.
     */
    private fun destination(arguments: JsonObject, type: String): File {
        val raw = (arguments["path"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val fallback = if (type == "video") {
            "DCIM/Meha/video_${System.currentTimeMillis()}.mp4"
        } else {
            "DCIM/Meha/photo_${System.currentTimeMillis()}.jpg"
        }
        val resolved = resolveSafePath(context.allowedToolRoots(), raw.ifEmpty { fallback })
        return if (resolved.name.contains('.')) {
            resolved
        } else {
            File(resolved.parentFile, resolved.name + if (type == "video") ".mp4" else ".jpg")
        }
    }

    /**
     * Video length for `camera_capture` with `type=video`: `duration` (seconds, the schema's unit)
     * or `duration_ms`, defaulting to [DEFAULT_VIDEO_MS] and clamped to 1–600 s.
     */
    private fun videoDurationMs(arguments: JsonObject): Long {
        val seconds = (arguments["duration"] as? JsonPrimitive)?.content?.trim()?.toDoubleOrNull()
        val ms = if (seconds != null && seconds > 0.0) {
            (seconds * 1000.0).toLong()
        } else {
            (arguments["duration_ms"] as? JsonPrimitive)?.content?.trim()?.toLongOrNull()
                ?: DEFAULT_VIDEO_MS
        }
        return ms.coerceIn(MIN_VIDEO_MS, MAX_VIDEO_MS)
    }

    private companion object {
        val VIDEO_EXTENSIONS = listOf(".mp4", ".3gp", ".mkv", ".webm")
        const val DEFAULT_VIDEO_MS = 10_000L
        const val MIN_VIDEO_MS = 1_000L
        const val MAX_VIDEO_MS = 600_000L
    }
}
