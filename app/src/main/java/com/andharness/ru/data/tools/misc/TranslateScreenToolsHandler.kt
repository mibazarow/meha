package com.andharness.ru.data.tools.misc

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import com.andharness.ru.data.tools.str
import com.andharness.ru.data.vision.OcrRecognizer
import com.andharness.ru.domain.agent.LlmClient
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.permission.SpecialAccess
import com.andharness.ru.domain.settings.LlmSettingsStore
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.service.AccessibilityAutomationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * `translate_screen` (checklist §3): translates the text currently visible on the screen, entirely
 * on-device except for the configured LLM call.
 *
 * The path is REUSED, not reinvented:
 * 1. the current screen is captured with `AccessibilityService.takeScreenshot` — the same
 *    accessibility screenshot path `screen_capture` / `screen_understand` use;
 * 2. the text is read with the shared on-device [OcrRecognizer] (Tesseract `rus+eng`, ML Kit Latin as
 *    the honest fallback) — the same OCR path `ocr_image` / `screen_understand` use;
 * 3. the recognized text is bounded ([cleanOcrText], [ocrDownscaleFactor]) and translated by the
 *    shared [collectTranslateCompletion] in ONE request.
 *
 * Honest errors (never faked): the accessibility service is OFF → the `PERMISSION_REQUIRED` marker;
 * the screenshot fails (e.g. a secure window) or returns nothing → an explicit reason; OCR finds no
 * text; the model is not configured / times out / returns an empty answer.
 *
 * References (official docs):
 * - `AccessibilityService#takeScreenshot` (API 30) —
 *   https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshot(int,%20java.util.concurrent.Executor,%20android.accessibilityservice.AccessibilityService.TakeScreenshotCallback)
 * - screenshot error codes (incl. `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW`) —
 *   https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
 * - ML Kit Text Recognition v2 / Tesseract4Android (the shared OCR engine) — see `OcrRecognizer`.
 */
class TranslateScreenToolsHandler(
    private val llmClient: LlmClient,
    private val settingsStore: LlmSettingsStore,
    private val ocrRecognizer: OcrRecognizer,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "translate_screen"

            override suspend fun execute(arguments: JsonObject): ToolResult {
                val toRaw = arguments.str("to")
                    ?: return ToolResult(
                        false,
                        "Укажи to — целевой язык, например ru, en или «немецкий»."
                    )
                val to = TranslateLanguages.resolve(toRaw)
                    ?: return ToolResult(
                        false,
                        unsupportedLanguageMessage("Язык", toRaw, allowAuto = false)
                    )
                val fromRaw = arguments.str("from")
                val from = if (fromRaw == null || TranslateLanguages.isAuto(fromRaw)) {
                    null
                } else {
                    TranslateLanguages.resolve(fromRaw)
                        ?: return ToolResult(
                            false,
                            unsupportedLanguageMessage("Исходный язык", fromRaw, allowAuto = true)
                        )
                }
                val includeOriginal = arguments.str("include_original")
                    ?.toBooleanStrictOrNull() ?: false

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    return ToolResult(
                        false,
                        "Снимок экрана через службу спецвозможностей доступен только на Android 11+ " +
                            "(устройство API ${Build.VERSION.SDK_INT})."
                    )
                }
                val service = withContext(Dispatchers.Main) {
                    AccessibilityAutomationService.instance
                } ?: return ToolResult(false, SpecialAccess.required(SpecialAccess.ACCESSIBILITY))

                // The callback is delivered by the system; capture and node reads stay on Main (the
                // same threading rule the existing screenshot tools follow).
                val capture = withContext(Dispatchers.Main) { captureScreen(service) }
                val bitmap = capture.bitmap
                    ?: return ToolResult(false, captureFailureMessage(capture.errorCode))

                val ocr = try {
                    val scaled = withContext(Dispatchers.Default) { scaleForOcr(bitmap) }
                    try {
                        ocrRecognizer.recognize(scaled)
                    } finally {
                        if (scaled !== bitmap) withContext(Dispatchers.Default) { scaled.recycle() }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Exception) {
                    return ToolResult(false, "Не удалось распознать текст на экране: ${e.message}")
                } finally {
                    bitmap.recycle()
                }

                val ocrText = withContext(Dispatchers.Default) { cleanOcrText(ocr.text) }
                if (ocrText.isBlank()) {
                    return ToolResult(
                        false,
                        "На экране не распознан текст (${ocr.statusLine}) — переводить нечего."
                    )
                }

                val configured = runCatching { settingsStore.isConfigured().first() }
                    .getOrDefault(false)
                if (!configured) {
                    return ToolResult(false, TRANSLATE_NOT_CONFIGURED)
                }

                val raw = try {
                    collectTranslateCompletion(
                        llmClient,
                        buildTranslatePrompt(ocrText, to, from, tone = null),
                    )
                } catch (timeout: TimeoutCancellationException) {
                    return ToolResult(false, translateTimeoutMessage())
                } catch (cancellation: CancellationException) {
                    // Never swallow a stopped turn.
                    throw cancellation
                } catch (e: Exception) {
                    return ToolResult(false, "Ошибка перевода: ${e.message}")
                }

                val translated = cleanTranslation(raw)
                if (translated.isBlank()) {
                    return ToolResult(false, TRANSLATE_EMPTY_ANSWER)
                }
                val header = buildString {
                    appendLine("Перевод экрана на «$to».")
                    appendLine("${ocr.statusLine}.")
                }
                return ToolResult(
                    true,
                    (header + formatScreenTranslation(translated, ocrText, includeOriginal)).trim(),
                )
            }
        },
    )

    /** Outcome of one screenshot attempt: a bitmap or the platform error code (null = no callback). */
    private data class Capture(val bitmap: Bitmap?, val errorCode: Int?)

    /**
     * Bounded screenshot: the system delivers `takeScreenshot`'s callback and it can, in principle,
     * never arrive (service disconnected / OEM bug). The explicit budget turns that into an honest
     * failure instead of suspending the tool forever.
     */
    private suspend fun captureScreen(service: AccessibilityAutomationService): Capture =
        withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val executor = Executor { it.run() }
                try {
                    service.takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        executor,
                        object : AccessibilityService.TakeScreenshotCallback {
                            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                                val hardwareBuffer = screenshot.hardwareBuffer
                                val bitmap = Bitmap
                                    .wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                                    ?.copy(Bitmap.Config.ARGB_8888, false)
                                hardwareBuffer.close()
                                if (cont.isActive) cont.resume(Capture(bitmap, null))
                            }

                            override fun onFailure(errorCode: Int) {
                                if (cont.isActive) cont.resume(Capture(null, errorCode))
                            }
                        }
                    )
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(Capture(null, null))
                }
            }
        } ?: Capture(null, null)

    /**
     * Downscales the screenshot for OCR when it is larger than [MAX_OCR_DIMEN] on the long side,
     * keeping the OCR input bounded. Returns the SAME bitmap when no downscale is needed (the caller
     * must therefore only recycle the result when it is a different instance).
     */
    private fun scaleForOcr(bitmap: Bitmap): Bitmap {
        val factor = ocrDownscaleFactor(bitmap.width, bitmap.height)
        if (factor <= 1) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width / factor).coerceAtLeast(1),
            (bitmap.height / factor).coerceAtLeast(1),
            true,
        )
    }

    /** Honest reason a screenshot could not be obtained. */
    private fun captureFailureMessage(errorCode: Int?): String = when (errorCode) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
            "Не удалось прочитать экран: окно содержит защищённый контент (secure window). " +
                "Перевод не выполнен."
        null ->
            "Не удалось снять экран: система не вернула снимок (служба спецвозможностей " +
                "недоступна или не ответила вовремя). Перевод не выполнен."
        else ->
            "Снимок экрана не удался (код $errorCode). Перевод не выполнен."
    }

    private companion object {
        /** Budget for one system screenshot callback; a lost callback must not suspend forever. */
        const val CAPTURE_TIMEOUT_MS = 15_000L
    }
}
