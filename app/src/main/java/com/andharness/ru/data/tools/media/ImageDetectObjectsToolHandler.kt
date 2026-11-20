package com.andharness.ru.data.tools.media

import android.graphics.Bitmap
import android.util.Log
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetectorOptionsBase
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.resume

/**
 * `image_detect_objects`: detects objects in an image with ML Kit object detection and returns a
 * JSON array `{label, confidence, box{x,y,w,h}}`, sorted by confidence descending and capped at
 * `maxResults`. An empty result is reported honestly as `[]`.
 *
 * Engine: ML Kit **object detection**, BUNDLED variant (`com.google.mlkit:object-detection`) — the
 * coarse classifier ships in the APK and runs offline right after install, with no Google Play
 * services model download. Categories are the coarse ML Kit ones (Fashion good, Home good, Food,
 * Place, Plant).
 *
 * References:
 * - ML Kit object detection (`ObjectDetection.getClient(ObjectDetectorOptions)`,
 *   `ObjectDetectorOptions.Builder.setDetectorMode(...).enableMultipleObjects().enableClassification()`,
 *   `ObjectDetector.process(InputImage)`, `DetectedObject.getBoundingBox()` / `getLabels()`,
 *   `DetectedObject.Label.getText()` / `getConfidence()`, `PredefinedCategory`) —
 *   https://developers.google.com/ml-kit/vision/object-detection/android
 * - Model installation paths (object detection: bundled) —
 *   https://developers.google.com/ml-kit/tips/installation-paths
 */
internal class ImageDetectObjectsToolHandler(private val io: MediaPathIo) : ToolHandler {

    override val name = "image_detect_objects"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val resolved = ImageEditCommon.resolveSource(io, arguments, "path")
        resolved.error?.let { return@withContext ToolResult(false, it) }
        val src = resolved.file!!

        val maxResults = (try {
            ImageEditParsing.optInt(
                arguments,
                "maxResults",
                min = 1,
                max = ImageDetectionJson.HARD_MAX_RESULTS,
            )
        } catch (e: IllegalArgumentException) {
            return@withContext ToolResult(false, e.message ?: "Некорректный maxResults.")
        }) ?: ImageDetectionJson.DEFAULT_MAX_RESULTS

        var bitmap: Bitmap? = null
        val detector = ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptionsBase.SINGLE_IMAGE_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build(),
        )
        try {
            val decoded = ImageEditCommon.decodeCapped(src)
                ?: return@withContext ToolResult(
                    false,
                    "Не удалось декодировать изображение: «${src.name}» (формат или файл повреждён).",
                )
            bitmap = decoded.bitmap

            val outcome = await(detector.process(InputImage.fromBitmap(decoded.bitmap, 0)))
            if (outcome.isFailure) {
                val reason = outcome.exceptionOrNull()?.message
                return@withContext ToolResult(
                    false,
                    "ML Kit Object Detection не сработал" +
                        (if (reason.isNullOrBlank()) "." else ": $reason."),
                )
            }

            val shaped = ImageDetectionJson.shape(
                outcome.getOrThrow().map { toDetection(it) },
                maxResults,
            )
            if (shaped.count == 0) {
                ToolResult(true, "Объекты не обнаружены. JSON: []")
            } else {
                ToolResult(true, "Найдено объектов: ${shaped.count}. JSON: ${shaped.json}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            ToolResult(false, "Недостаточно памяти для анализа изображения.")
        } catch (e: Exception) {
            Log.e("MediaToolsHandler", "image_detect_objects failed", e)
            ToolResult(false, "Не удалось обнаружить объекты: ${e.message}")
        } finally {
            runCatching { detector.close() }
            bitmap?.recycle()
        }
    }

    /** Reduces an ML Kit [DetectedObject] to plain data: its bounding box and best-confidence label. */
    private fun toDetection(obj: DetectedObject): ImageDetectionJson.Detection {
        val box = obj.boundingBox
        val best = obj.labels.maxByOrNull { it.confidence }
        return ImageDetectionJson.Detection(
            label = best?.text,
            confidence = best?.confidence ?: 0f,
            left = box.left,
            top = box.top,
            width = box.width(),
            height = box.height(),
        )
    }

    /** Awaits an ML Kit `Task` as a [Result]; never throws for a failed task. */
    private suspend fun <T : Any> await(task: Task<T>): Result<T> =
        suspendCancellableCoroutine { cont ->
            task.addOnSuccessListener { value -> if (cont.isActive) cont.resume(Result.success(value)) }
                .addOnFailureListener { error -> if (cont.isActive) cont.resume(Result.failure(error)) }
        }
}
