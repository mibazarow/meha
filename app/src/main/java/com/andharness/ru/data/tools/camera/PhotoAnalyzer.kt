package com.andharness.ru.data.tools.camera

import android.graphics.BitmapFactory
import com.andharness.ru.data.attachments.AttachmentStore
import com.andharness.ru.data.vision.OcrRecognizer
import com.andharness.ru.data.vision.VisionCapability
import com.andharness.ru.domain.agent.LlmClient
import com.andharness.ru.domain.agent.LlmContentPart
import com.andharness.ru.domain.agent.LlmEvent
import com.andharness.ru.domain.agent.LlmMessage
import com.andharness.ru.domain.agent.LlmRequest
import com.andharness.ru.domain.settings.LlmSettingsStore
import com.andharness.ru.domain.vision.PhotoAnalysis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared "look at a captured photo" service used by the `camera_capture` tool (when `analyze=true`)
 * and by the trusted-SMS photo report.
 *
 * Honest by construction, per the pure [PhotoAnalysis] policy:
 * - the photo reaches the model **only** when the configured model is known to accept images
 *   (`VisionCapability.supportsVision`); the returned text is then the model's description;
 * - otherwise the image is NOT sent; the method runs the on-device [OcrRecognizer] and says so
 *   verbatim ([PhotoAnalysis.VISION_UNAVAILABLE_NOTE]);
 * - a missing configuration, an encoding failure, a model error/timeout or a blank answer all fall
 *   back to OCR with an honest reason instead of a fabricated description.
 *
 * References:
 * - OpenAI vision (`image_url` content part): https://platform.openai.com/docs/guides/vision
 * - ML Kit text recognition: https://developers.google.com/ml-kit/vision/text-recognition/v2/android
 * - kotlinx.coroutines `withTimeout` (hard wall-clock budget):
 *   https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/withTimeout.html
 */
@Singleton
class PhotoAnalyzer @Inject constructor(
    private val visionCapability: VisionCapability,
    private val ocrRecognizer: OcrRecognizer,
    private val attachmentStore: AttachmentStore,
    private val llmClient: LlmClient,
    private val settingsStore: LlmSettingsStore,
) {

    /** The outcome of one analysis: the rendered text plus whether the model actually saw the image. */
    data class Report(
        val visionAvailable: Boolean,
        val text: String,
        val plan: PhotoAnalysis.Plan,
    )

    /**
     * Analyzes the photo at [file]. Never throws for ordinary failures: every branch returns an
     * honest [Report]. A stopped turn (`CancellationException` that is not a timeout) still propagates.
     */
    suspend fun analyze(file: File): Report {
        if (!file.isFile) {
            return Report(
                visionAvailable = false,
                text = PhotoAnalysis.renderOcrUnavailable("файл фото не найден: ${file.path}"),
                plan = PhotoAnalysis.plan(analyzeRequested = true, visionSupported = false),
            )
        }
        val visionSupported = runCatching { visionCapability.supportsVision() }.getOrDefault(false)
        val plan = PhotoAnalysis.plan(analyzeRequested = true, visionSupported = visionSupported)
        if (plan.mode != PhotoAnalysis.Mode.VISION) return ocrReport(file, plan, null)

        val configured = runCatching { settingsStore.isConfigured().first() }.getOrDefault(false)
        if (!configured) return ocrReport(file, plan, "модель не настроена")

        val imagePart = runCatching { attachmentStore.imageContentPart(file) }.getOrNull()
            ?: return ocrReport(file, plan, "не удалось подготовить изображение для отправки")

        val raw = try {
            collectDescription(imagePart)
        } catch (timeout: TimeoutCancellationException) {
            return ocrReport(file, plan, "модель не ответила за ${VISION_TIMEOUT_MS / 1000} с")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            return ocrReport(file, plan, "ошибка модели: ${e.message}")
        }
        val description = PhotoAnalysis.cleanDescription(raw)
        if (description.isBlank()) return ocrReport(file, plan, "модель вернула пустой ответ")
        return Report(visionAvailable = true, text = PhotoAnalysis.renderVision(description), plan = plan)
    }

    /** Runs the on-device OCR fallback; [visionReason] is appended honestly when vision did not run. */
    private suspend fun ocrReport(
        file: File,
        plan: PhotoAnalysis.Plan,
        visionReason: String?,
    ): Report = withContext(Dispatchers.IO) {
        val ocr = runCatching {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@runCatching null
            try {
                ocrRecognizer.recognize(bitmap)
            } finally {
                bitmap.recycle()
            }
        }.getOrNull()
        val text = if (ocr == null) {
            PhotoAnalysis.renderOcrUnavailable(visionReason ?: "изображение не удалось прочитать")
        } else {
            val prefix = visionReason?.let { "Vision недоступен: $it. " }.orEmpty()
            prefix + PhotoAnalysis.renderOcr(ocr.text, ocr.statusLine)
        }
        Report(visionAvailable = false, text = text, plan = plan)
    }

    /** One bounded completion carrying the image part; an [LlmEvent.Error] becomes an exception. */
    private suspend fun collectDescription(imagePart: LlmContentPart.ImageUrl): String =
        withTimeout(VISION_TIMEOUT_MS) {
            val text = StringBuilder()
            llmClient.complete(
                LlmRequest(
                    messages = listOf(
                        LlmMessage("system", PhotoAnalysis.DESCRIBE_SYSTEM_PROMPT),
                        LlmMessage(
                            role = "user",
                            contentParts = listOf(
                                LlmContentPart.Text(PhotoAnalysis.DESCRIBE_PROMPT),
                                imagePart,
                            ),
                        ),
                    ),
                )
            ).collect { event ->
                when (event) {
                    is LlmEvent.Text -> text.append(event.delta)
                    is LlmEvent.Error -> throw IllegalStateException(event.message)
                    else -> Unit
                }
            }
            text.toString()
        }

    private companion object {
        /** Hard wall-clock budget for the single image-description completion. */
        const val VISION_TIMEOUT_MS = 45_000L
    }
}
