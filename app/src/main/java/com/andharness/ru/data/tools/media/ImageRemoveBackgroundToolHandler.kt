package com.andharness.ru.data.tools.media

import android.graphics.Bitmap
import android.util.Log
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.nio.FloatBuffer
import kotlin.coroutines.resume

/**
 * `image_remove_background`: cuts the subject out of an image and writes a PNG with a transparent
 * background.
 *
 * Engine: ML Kit **subject segmentation** (`com.google.android.gms:play-services-mlkit-subject-
 * segmentation`). This API is UNBUNDLED-only: the model is downloaded by Google Play services at
 * runtime, so it needs a connection and a one-time download, and it is a beta API with no SLA. When
 * the model/services are unavailable the tool fails with an honest message and never fabricates a
 * result.
 *
 * Output is built by applying ML Kit's foreground confidence mask to the source pixels as alpha
 * (so colours match the original exactly and the background is truly transparent); if the mask is
 * missing it falls back to the foreground bitmap, and if both are missing it reports failure.
 *
 * References:
 * - ML Kit subject segmentation (`SubjectSegmentation.getClient`, `SubjectSegmenter.process`,
 *   `SubjectSegmentationResult.getForegroundConfidenceMask()` / `getForegroundBitmap()`,
 *   `SubjectSegmenterOptions.Builder.enableForegroundConfidenceMask()` / `enableForegroundBitmap()`)
 *   — https://developers.google.com/ml-kit/vision/subject-segmentation/android
 * - Model installation paths (subject segmentation: unbundled only) —
 *   https://developers.google.com/ml-kit/tips/installation-paths
 */
internal class ImageRemoveBackgroundToolHandler(private val io: MediaPathIo) : ToolHandler {

    override val name = "image_remove_background"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val srcResolved = ImageEditCommon.resolveSource(io, arguments, "src")
        srcResolved.error?.let { return@withContext ToolResult(false, it) }
        val src = srcResolved.file!!
        val destResolved = ImageEditCommon.prepareDestination(io, arguments, "dest")
        destResolved.error?.let { return@withContext ToolResult(false, it) }
        val dest = destResolved.file!!

        val decoded = ImageEditCommon.decodeCapped(src)
            ?: return@withContext ToolResult(
                false,
                "Не удалось декодировать изображение: «${src.name}» (формат или файл повреждён).",
            )
        val source = decoded.bitmap

        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .enableForegroundBitmap()
            .build()
        val segmenter = SubjectSegmentation.getClient(options)

        var output: Bitmap? = null
        try {
            val outcome = await(segmenter.process(InputImage.fromBitmap(source, 0)))
            if (outcome.isFailure) {
                val reason = outcome.exceptionOrNull()?.message
                return@withContext ToolResult(
                    false,
                    "ML Kit Subject Segmentation недоступен" +
                        (if (reason.isNullOrBlank()) "" else " ($reason)") +
                        ". Модель загружается через Google Play services: проверь подключение и " +
                        "повтори (первый запуск может ждать загрузки модели).",
                )
            }
            val result = outcome.getOrThrow()
            val foreground = result.foregroundBitmap
            val mask = result.foregroundConfidenceMask

            val cutout: Bitmap = when {
                mask != null -> applyMask(source, mask)
                foreground != null -> foreground
                else -> return@withContext ToolResult(
                    false,
                    "ML Kit не вернул ни маску, ни изображение субъекта — фон не удалён.",
                )
            }
            output = cutout

            val error = ImageEditCommon.writeBitmap(dest, cutout)
            if (error != null) return@withContext ToolResult(false, error)

            ToolResult(
                true,
                "Фон удалён: «${dest.absolutePath}» (${cutout.width}x${cutout.height} px, " +
                    "${dest.length()} байт). Сохранено с прозрачным фоном (PNG).",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            ToolResult(false, "Недостаточно памяти для обработки изображения.")
        } catch (e: Exception) {
            Log.e("MediaToolsHandler", "image_remove_background failed", e)
            ToolResult(false, "Не удалось удалить фон: ${e.message}")
        } finally {
            runCatching { segmenter.close() }
            if (output !== source) output?.recycle()
            source.recycle()
        }
    }

    /**
     * Copies [source]'s colours into a new ARGB bitmap whose alpha channel is [mask] (row-major,
     * `width * height` values in `0..1`). A short mask is padded with alpha 0 rather than trusted.
     */
    private fun applyMask(source: Bitmap, mask: FloatBuffer): Bitmap {
        val width = source.width
        val height = source.height
        val count = width * height
        val pixels = IntArray(count)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val out = IntArray(count)
        val available = mask.capacity()
        for (i in 0 until count) {
            val alpha = if (i < available) {
                mask.get(i).coerceIn(0f, 1f)
            } else {
                0f
            }
            val color = pixels[i]
            out[i] = (color and 0x00FFFFFF) or ((alpha * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24)
        }
        return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
    }

    /** Awaits an ML Kit `Task` as a [Result]; never throws for a failed task. */
    private suspend fun <T : Any> await(
        task: com.google.android.gms.tasks.Task<T>,
    ): Result<T> = suspendCancellableCoroutine { cont ->
        task.addOnSuccessListener { value -> if (cont.isActive) cont.resume(Result.success(value)) }
            .addOnFailureListener { error -> if (cont.isActive) cont.resume(Result.failure(error)) }
    }
}
