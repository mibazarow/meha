package com.andharness.ru.data.tools.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.andharness.ru.bg.ScreenRecordService
import com.andharness.ru.data.tools.allowedToolRoots
import com.andharness.ru.data.tools.resolveSafePath
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.ui.UiInteractionBridge
import com.andharness.ru.domain.ui.UiRequest
import com.andharness.ru.domain.ui.UiResult
import com.andharness.ru.domain.ui.requestBoundedUi
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `screen_capture_region` — captures a still frame of a region/window, or records a short
 * full-screen video when `duration_ms` is given.
 *
 * Honesty rules:
 * - a numeric region is a real crop of the captured frame (the platform captures the whole
 *   display; there is no offset-crop projection) and the result says so,
 * - a single app window needs `MediaProjectionConfig.createConfigForUserChoice()` (API 34+); on
 *   older devices the tool refuses instead of silently capturing the whole screen,
 * - `duration_ms` cannot be combined with a region: `MediaRecorder` records the whole display,
 *   so a region video is refused instead of being faked,
 * - the media projection token is per-session, so consent is requested on every call.
 * References:
 * - https://developer.android.com/reference/android/media/projection/MediaProjectionConfig
 * - https://developer.android.com/reference/android/media/projection/MediaProjectionManager
 */
internal class ScreenCaptureRegionToolHandler(
    private val context: Context,
    private val uiBridge: UiInteractionBridge,
) : ToolHandler {

    override val name = "screen_capture_region"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val rawPath = (arguments["path"] as? JsonPrimitive)?.content
        if (rawPath.isNullOrBlank()) {
            return@withContext ToolResult(false, "Укажите path — путь к файлу результата (например Pictures/region.png).")
        }

        val left = intArg(arguments, "left")
        val top = intArg(arguments, "top")
        val width = intArg(arguments, "width")
        val height = intArg(arguments, "height")
        val regionRequested = left != null && top != null && width != null && height != null
        val anyRegionArg = left != null || top != null || width != null || height != null
        if (anyRegionArg && !regionRequested) {
            return@withContext ToolResult(
                false,
                "Для региона нужны все четыре параметра: left, top, width, height (целые числа в пикселях).",
            )
        }

        val windowTarget = ((arguments["window"] as? JsonPrimitive)?.content
            ?: (arguments["app"] as? JsonPrimitive)?.content)?.trim()?.takeIf { it.isNotEmpty() }
        if (windowTarget != null && regionRequested) {
            return@withContext ToolResult(
                false,
                "Нельзя одновременно задавать регион (left/top/width/height) и window/app — выберите что-то одно.",
            )
        }

        val durationMs = (arguments["duration_ms"] as? JsonPrimitive)?.content?.trim()?.toLongOrNull()
        if (durationMs != null && durationMs <= 0L) {
            return@withContext ToolResult(false, "duration_ms должен быть положительным числом миллисекунд.")
        }
        if (durationMs != null && durationMs > ScreenSupport.MAX_DURATION_MS) {
            return@withContext ToolResult(
                false,
                "duration_ms больше максимума ${ScreenSupport.MAX_DURATION_MS} мс (${ScreenSupport.MAX_DURATION_MS / 1000} с).",
            )
        }

        if (ScreenRecordingState.status.value.active) {
            return@withContext ToolResult(
                false,
                "Идёт запись экрана — сначала остановите её (screen_record_stop).",
            )
        }

        val file = try {
            resolveSafePath(context.allowedToolRoots(), rawPath)
        } catch (e: IllegalArgumentException) {
            return@withContext ToolResult(false, e.message ?: "Некорректный путь.")
        }
        if (file.exists()) {
            return@withContext ToolResult(
                false,
                "Файл уже существует: «${file.absolutePath}». Перезапись запрещена — укажите другой путь.",
            )
        }
        file.parentFile?.mkdirs()

        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            ?: return@withContext ToolResult(false, "MediaProjection недоступен на этом устройстве.")

        val windowCapture = windowTarget != null
        if (windowCapture && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return@withContext ToolResult(
                false,
                "Захват отдельного окна приложения требует Android 14 (API 34): используется " +
                    "MediaProjectionConfig с выбором пользователя. На этом устройстве (Android " +
                    "${Build.VERSION.SDK_INT}) доступен только захват всего экрана или его региона. " +
                    "Уберите параметр window/app или задайте регион left/top/width/height.",
            )
        }
        if (durationMs != null && regionRequested) {
            return@withContext ToolResult(
                false,
                "Запись видео региона не поддерживается платформой: MediaRecorder пишет весь экран " +
                    "целиком. Для кадра региона уберите duration_ms; для видео используйте " +
                    "screen_record_start (весь экран).",
            )
        }

        val consentIntent = if (windowCapture) {
            userChoiceConsentIntent(manager)
        } else {
            manager.createScreenCaptureIntent()
        }
        val consent = uiBridge.requestBoundedUi(
            UiRequest.ScreenCaptureConsent(consentIntent),
            timeoutMs = ScreenSupport.CONSENT_TIMEOUT_MS,
        )
        val activityResult = consent as? UiResult.ActivityResult
            ?: return@withContext ToolResult(false, ScreenSupport.consentFailureMessage(consent))
        if (activityResult.resultCode != Activity.RESULT_OK || activityResult.data == null) {
            return@withContext ToolResult(
                false,
                "Пользователь не дал согласие на захват экрана. Согласие запрашивается на каждую сессию.",
            )
        }
        val resultData = activityResult.data!!

        if (durationMs != null) {
            // Timed capture is a short full-screen video (the service owns the recorder).
            ScreenRecordingState.reset()
            if (!ScreenRecordService.start(
                    context,
                    activityResult.resultCode,
                    resultData,
                    file.absolutePath,
                    false,
                )
            ) {
                return@withContext ToolResult(false, "Не удалось запустить службу записи экрана.")
            }
            val started = withTimeoutOrNull(ScreenSupport.START_TIMEOUT_MS) {
                ScreenRecordingState.status.first { it.active || it.error != null }
            } ?: return@withContext ToolResult(
                false,
                "Служба записи экрана не подтвердила старт за ${ScreenSupport.START_TIMEOUT_MS / 1000} с.",
            )
            if (started.error != null) {
                return@withContext ToolResult(false, "Не удалось начать запись экрана: ${started.error}")
            }
            delay(durationMs)
            ScreenRecordService.stop(context)
            withTimeoutOrNull(ScreenSupport.STOP_TIMEOUT_MS) {
                ScreenRecordingState.status.first { !it.active }
            } ?: return@withContext ToolResult(
                false,
                "Не удалось остановить запись экрана за ${ScreenSupport.STOP_TIMEOUT_MS / 1000} с.",
            )
            val saved = File(file.absolutePath)
            return@withContext if (saved.exists() && saved.length() > 0L) {
                val source = if (windowCapture) {
                    " Захвачено выбранное пользователем окно приложения (MediaProjectionConfig, Android 14+)."
                } else {
                    " Захвачен весь экран."
                }
                ToolResult(
                    true,
                    "Видео записано: «${saved.absolutePath}» (${saved.length()} байт, ${durationMs} мс).$source",
                )
            } else {
                ToolResult(false, "Запись завершена, но файл пуст или не создан: «${file.absolutePath}».")
            }
        }

        // One still frame (captured + cropped inside the service).
        ScreenCaptureState.reset()
        if (!ScreenRecordService.capture(
                context,
                activityResult.resultCode,
                resultData,
                file.absolutePath,
                left ?: ScreenRecordService.NO_REGION,
                top ?: ScreenRecordService.NO_REGION,
                width ?: ScreenRecordService.NO_REGION,
                height ?: ScreenRecordService.NO_REGION,
            )
        ) {
            return@withContext ToolResult(false, "Не удалось запустить службу захвата кадра.")
        }
        val captured = withTimeoutOrNull(ScreenSupport.CAPTURE_TIMEOUT_MS) {
            ScreenCaptureState.status.first { it.done }
        } ?: return@withContext ToolResult(
            false,
            "Служба захвата не вернула результат за ${ScreenSupport.CAPTURE_TIMEOUT_MS / 1000} с.",
        )
        if (!captured.ok) {
            return@withContext ToolResult(false, "Захват не удался: ${captured.error ?: "неизвестная ошибка"}.")
        }
        val savedFile = File(captured.path ?: file.absolutePath)
        val source = when {
            windowCapture -> " Захвачено выбранное пользователем окно приложения (MediaProjectionConfig, Android 14+)."
            regionRequested -> " Кадр всего экрана обрезан до региона $left,$top ${width}×$height " +
                "(регион — это честный кроп захваченного кадра)."
            else -> " Захвачен весь экран."
        }
        ToolResult(
            true,
            "Кадр сохранён: «${savedFile.absolutePath}» (${savedFile.length()} байт).$source",
        )
    }

    /** Parses an optional integer argument; returns null when the key is absent or not a number. */
    private fun intArg(arguments: JsonObject, key: String): Int? =
        (arguments[key] as? JsonPrimitive)?.content?.trim()?.toIntOrNull()

    /** API-34+ consent with the system's "choose region/app" picker (MediaProjectionConfig). */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun userChoiceConsentIntent(manager: MediaProjectionManager): Intent =
        manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
}
