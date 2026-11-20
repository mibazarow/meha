package com.andharness.ru.data.tools.ui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import com.andharness.ru.data.attachments.AttachmentStore
import com.andharness.ru.data.tools.effectiveToolRoot
import com.andharness.ru.data.tools.str
import com.andharness.ru.data.vision.OcrRecognizer
import com.andharness.ru.data.vision.VisionCapability
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.permission.SpecialAccess
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.vision.ScreenFusion
import com.andharness.ru.service.AccessibilityAutomationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Builds the `screen_understand` tool (update.md idea #14): «что на экране?» answered by FUSING the
 * accessibility node tree with on-device OCR of a real screenshot, so WebViews, canvases, games and
 * any custom-drawn UI the tree misses are still covered.
 *
 * The heavy lifting is Android-free and lives in `ScreenFusion` (parse → overlap removal → bounded
 * formatting), which is unit-tested from fixtures. This handler only:
 * 1. reads the tree through the shared [AccessibilityTree] (same extraction as `ui_tree`);
 * 2. takes a screenshot with `AccessibilityService.takeScreenshot` (API 30+, `canTakeScreenshot` is
 *    enabled in `accessibility_service_config.xml`) and runs the shared on-device [OcrRecognizer]
 *    over it (Tesseract for Cyrillic, ML Kit Latin as the honest fallback);
 * 3. optionally attaches the screenshot to the model request as a multimodal image part (and also
 *    saves it as a PNG fallback) when the current model supports vision.
 *
 * Honest limits (reported, never faked):
 * - the accessibility service is off → the tool returns the `PERMISSION_REQUIRED` special-access
 *   marker;
 * - `takeScreenshot` needs Android 11+ and fails on secure windows (for example a password field or
 *   a banking screen) → the answer states that OCR was skipped and why;
 * - `attach_image` only has an effect when `vision_status` says the model accepts images; an
 *   UNKNOWN verdict is never treated as support.
 *
 * References (official docs):
 * - `AccessibilityService#takeScreenshot` (API 30) —
 *   https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshot(int,%20java.util.concurrent.Executor,%20android.accessibilityservice.AccessibilityService.TakeScreenshotCallback)
 * - screenshot error codes (incl. `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW`, API 34) —
 *   https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
 * - ML Kit Text Recognition v2 —
 *   https://developers.google.com/ml-kit/vision/text-recognition/v2/android
 */
class ScreenUnderstandToolsHandler(
    private val context: Context,
    private val visionCapability: VisionCapability,
    private val ocrRecognizer: OcrRecognizer,
    private val attachmentStore: AttachmentStore,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "screen_understand"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.Main) {
                val service = AccessibilityAutomationService.instance ?: return@withContext notEnabled()
                try {
                    val verbose = arguments.str("verbose")?.toBooleanStrictOrNull() ?: false
                    val attachImage = arguments.str("attach_image")?.toBooleanStrictOrNull() ?: false
                    val maxChars = arguments.str("max_chars")?.toIntOrNull()
                        ?.coerceIn(ScreenFusion.MIN_MAX_CHARS, ScreenFusion.MAX_MAX_CHARS)
                        ?: ScreenFusion.DEFAULT_MAX_CHARS

                    val root = service.rootInActiveWindow
                    // Only the accessibility reads stay on Main (node access must happen on a
                    // Looper thread). The pure string work — formatting the tree, fusing it with
                    // OCR, bounding the result — is CPU-bound and is done OFF the main thread, so a
                    // big screen dump can never freeze the chat screen (update.md bug #7).
                    val elements = if (root != null) AccessibilityTree.collect(root) else emptyList()
                    val treeText = withContext(Dispatchers.Default) {
                        if (elements.isEmpty()) "" else AccessibilityTree.format(elements)
                    }
                    val packageName = root?.packageName?.toString()?.takeIf { it.isNotBlank() }

                    val capture = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        captureScreen(service)
                    } else {
                        Capture(bitmap = null, errorCode = null)
                    }
                    val screenshotNote = when {
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.R ->
                            "Скриншот недоступен: служба спецвозможностей снимает экран только на " +
                                "Android 11+ (устройство API ${Build.VERSION.SDK_INT}) — OCR пропущен, " +
                                "показано только дерево."
                        capture.bitmap == null &&
                            capture.errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
                            "Скриншот недоступен: окно содержит защищённый контент (secure window) — " +
                                "OCR пропущен, показано только дерево."
                        capture.bitmap == null ->
                            "Скриншот не удался (код ${capture.errorCode ?: "неизвестен"}) — OCR пропущен, " +
                                "показано только дерево."
                        else -> null
                    }

                    val screenshot = capture.bitmap
                    val ocr = if (screenshot != null) {
                        ocrRecognizer.recognize(screenshot)
                    } else {
                        null
                    }
                    val ocrText = ocr?.text.orEmpty()

                    val visionSupported = if (attachImage && screenshot != null) {
                        runCatching { visionCapability.supportsVision() }.getOrDefault(false)
                    } else {
                        false
                    }
                    val plan = ScreenFusion.shouldAttachImage(attachImage, visionSupported)
                    // Encode the image part BEFORE the bitmap is recycled: the attachment store
                    // downscales + JPEG-encodes it with the shared policy (§19.3/§19.4) and does NOT
                    // own the bitmap. Encoding failure never fails the tool — the PNG path stays as
                    // the fallback for models that cannot take images.
                    val imagePart = if (plan.attach && screenshot != null) {
                        withContext(Dispatchers.IO) { attachmentStore.imageContentPart(screenshot) }
                    } else {
                        null
                    }
                    val savedPath = if (attachImage && screenshot != null) {
                        withContext(Dispatchers.IO) { saveScreenshot(screenshot) }
                    } else {
                        null
                    }
                    screenshot?.recycle()

                    val fused = withContext(Dispatchers.Default) {
                        ScreenFusion.fuse(treeText, ocrText)
                    }
                    val header = buildString {
                        appendLine("Понимание экрана: дерево спецвозможностей + OCR скриншота.")
                        packageName?.let { appendLine("Активное приложение: $it") }
                        screenshotNote?.let { appendLine(it) }
                        ocr?.let { appendLine("${it.statusLine}.") }
                        if (attachImage) {
                            appendLine(
                                when {
                                    imagePart != null && savedPath != null ->
                                        "Скриншот приложен к запросу как изображение — ${plan.reason}; " +
                                            "файл также сохранён: $savedPath (доступен для files_read/ocr_image)."
                                    imagePart != null ->
                                        "Скриншот приложен к запросу как изображение — ${plan.reason}."
                                    plan.attach && savedPath != null ->
                                        "Скриншот сохранён: $savedPath, но приложить изображение не удалось; " +
                                            "файл доступен для визуального анализа (files_read/ocr_image)."
                                    savedPath != null ->
                                        "Скриншот сохранён: $savedPath, но модели не передаётся: ${plan.reason}. " +
                                            "Файл доступен для визуального анализа (files_read/ocr_image)."
                                    else ->
                                        "Скриншот не сохранён: ${plan.reason}."
                                }
                            )
                        }
                    }
                    val body = withContext(Dispatchers.Default) {
                        ScreenFusion.format(
                            fused = fused,
                            maxChars = maxChars,
                            verbose = verbose,
                            rawTree = treeText,
                            rawOcr = ocrText,
                        )
                    }
                    ToolResult(
                        success = true,
                        output = (header + body).trim(),
                        imageParts = listOfNotNull(imagePart),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
    )

    /** Outcome of one screenshot attempt: a bitmap or the platform error code. */
    private data class Capture(val bitmap: Bitmap?, val errorCode: Int?)

    /**
     * Bounded screenshot: `takeScreenshot`'s callback is delivered by the system and can, in
     * principle, never arrive (service disconnected / OEM bug). A lost callback would otherwise
     * suspend this tool until the orchestrator's 4-minute cap — and any direct caller (debug bridge)
     * would suspend forever. The explicit budget turns that into an honest "skipped" screenshot.
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
     * Saves the screenshot under the same storage root the file tools operate on, so the returned
     * path is readable with `files_read` / `ocr_image` in both limited and all-files mode. Returns
     * null when the image could not be written.
     */
    private fun saveScreenshot(bitmap: Bitmap): String? = try {
        val dir = File(context.effectiveToolRoot(), "screenshots")
        if (!dir.isDirectory && !dir.mkdirs()) {
            null
        } else {
            val file = File(dir, "screen_understand_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) file.absolutePath else null
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun notEnabled(): ToolResult =
        ToolResult(false, SpecialAccess.required(SpecialAccess.ACCESSIBILITY))

    private companion object {
        /** Budget for one system screenshot callback; a lost callback must not suspend forever. */
        const val CAPTURE_TIMEOUT_MS = 15_000L
    }
}
