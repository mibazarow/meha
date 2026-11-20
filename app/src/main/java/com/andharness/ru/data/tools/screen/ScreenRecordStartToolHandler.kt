package com.andharness.ru.data.tools.screen

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import com.andharness.ru.bg.ScreenRecordService
import com.andharness.ru.data.tools.allowedToolRoots
import com.andharness.ru.data.tools.resolveSafePath
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.ui.UiInteractionBridge
import com.andharness.ru.domain.ui.UiRequest
import com.andharness.ru.domain.ui.UiResult
import com.andharness.ru.domain.ui.requestBoundedUi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `screen_record_start`: resolves the output path, obtains per-session user consent through
 * [UiInteractionBridge], launches [ScreenRecordService] and waits for the honest start outcome.
 *
 * Real recording is done by [ScreenRecordService] (MediaProjection foreground service); this handler
 * only resolves the output path, obtains consent, launches the service and reports the honest
 * outcome.
 * References:
 * - https://developer.android.com/media/grow/media-projection
 * - https://developer.android.com/about/versions/14/changes/fgs-types-required
 * - https://developer.android.com/reference/android/media/projection/MediaProjectionManager
 */
internal class ScreenRecordStartToolHandler(
    private val context: Context,
    private val uiBridge: UiInteractionBridge,
) : ToolHandler {

    override val name = "screen_record_start"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val rawPath = (arguments["path"] as? JsonPrimitive)?.content
        if (rawPath.isNullOrBlank()) {
            return ToolResult(false, "Укажите path — путь к файлу записи (например Movies/record.mp4).")
        }
        val withAudio = (arguments["with_audio"] as? JsonPrimitive)
            ?.content?.toBooleanStrictOrNull() ?: false

        if (ScreenRecordingState.status.value.active) {
            val current = ScreenRecordingState.status.value.path
            return ToolResult(false, "Запись экрана уже идёт: «$current». Сначала остановите её (screen_record_stop).")
        }

        val file = try {
            resolveSafePath(context.allowedToolRoots(), rawPath)
        } catch (e: IllegalArgumentException) {
            return ToolResult(false, e.message ?: "Некорректный путь записи.")
        }
        if (file.exists()) {
            return ToolResult(
                false,
                "Файл уже существует: «${file.absolutePath}». Перезапись запрещена — укажите другой путь.",
            )
        }
        file.parentFile?.mkdirs()

        // Optional microphone audio needs the runtime RECORD_AUDIO permission; fail honestly
        // (never silently record without audio) instead of starting a video-only recording.
        if (withAudio && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult(
                false,
                "Для записи со звуком нужно разрешение RECORD_AUDIO — оно не выдано. " +
                    "Запустите без with_audio или выдайте разрешение на микрофон.",
            )
        }

        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            ?: return ToolResult(false, "MediaProjection недоступен на этом устройстве.")

        // STEP 1: per-session consent via the Activity (the UI fulfils the request), bounded by
        // the shared UI-consent policy so a broadcast caller never ANRs (BUG-014/BUG-016).
        val consentIntent = manager.createScreenCaptureIntent()
        val consent = uiBridge.requestBoundedUi(
            UiRequest.ScreenCaptureConsent(consentIntent),
            timeoutMs = ScreenSupport.CONSENT_TIMEOUT_MS,
        )
        val activityResult = consent as? UiResult.ActivityResult
            ?: return ToolResult(false, ScreenSupport.consentFailureMessage(consent))
        if (activityResult.resultCode != Activity.RESULT_OK || activityResult.data == null) {
            return ToolResult(
                false,
                "Пользователь не дал согласие на запись экрана. " +
                    "Согласие запрашивается заново для каждой сессии записи.",
            )
        }

        // STEP 2+3: the service goes foreground (type mediaProjection) before obtaining the
        // projection, then creates the virtual display and starts the recorder.
        ScreenRecordingState.reset()
        if (!ScreenRecordService.start(
                context,
                activityResult.resultCode,
                activityResult.data,
                file.absolutePath,
                withAudio,
            )
        ) {
            return ToolResult(false, "Не удалось запустить службу записи экрана.")
        }

        val started = withTimeoutOrNull(ScreenSupport.START_TIMEOUT_MS) {
            ScreenRecordingState.status.first { it.active || it.error != null }
        } ?: return ToolResult(
            false,
            "Служба записи экрана не подтвердила старт за ${ScreenSupport.START_TIMEOUT_MS / 1000} с.",
        )
        if (started.error != null) {
            return ToolResult(false, "Не удалось начать запись экрана: ${started.error}")
        }
        return ToolResult(
            true,
            "Запись экрана начата: «${file.absolutePath}»" +
                (if (withAudio) " (со звуком микрофона)" else "") +
                ". Остановите её инструментом screen_record_stop.",
        )
    }
}
