package com.andharness.ru.data.tools.media

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
import com.andharness.ru.bg.AudioCaptureService
import com.andharness.ru.data.tools.allowedToolRoots
import com.andharness.ru.data.tools.resolveSafePath
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.Tool
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.ui.UiConsentPolicy
import com.andharness.ru.domain.ui.UiInteractionBridge
import com.andharness.ru.domain.ui.UiRequest
import com.andharness.ru.domain.ui.UiResult
import com.andharness.ru.domain.ui.requestBoundedUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * `audio_capture_app`: records the audio played by other apps into a WAV file.
 *
 * Reference: playback capture (`AudioPlaybackCaptureConfiguration`) + MediaProjection consent —
 * https://developer.android.com/media/platform/av-capture
 */
internal class AudioCaptureAppToolHandler(
    private val context: Context,
    private val uiBridge: UiInteractionBridge,
) : ToolHandler {

    override val name = "audio_capture_app"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return@withContext ToolResult(
                    false,
                    "Захват звука других приложений требует Android 10 (API 29) или новее.",
                )
            }
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return@withContext ToolResult(
                    false,
                    "${Tool.PERMISSION_REQUIRED}:${Manifest.permission.RECORD_AUDIO}",
                )
            }

            val raw = (arguments["path"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (raw.isEmpty()) {
                return@withContext ToolResult(false, "Укажите path — путь к файлу записи (.wav).")
            }
            val destination = try {
                resolveSafePath(context.allowedToolRoots(), raw)
            } catch (e: IllegalArgumentException) {
                return@withContext ToolResult(false, e.message ?: "Некорректный путь записи.")
            }
            if (destination.exists()) {
                return@withContext ToolResult(
                    false,
                    "Файл уже существует: «${destination.absolutePath}». Перезапись запрещена.",
                )
            }
            destination.parentFile?.mkdirs()

            val durationRaw = (arguments["duration_ms"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val requested = if (durationRaw.isEmpty()) {
                AudioCaptureService.DEFAULT_DURATION_MS
            } else {
                durationRaw.toLongOrNull()
                    ?: return@withContext ToolResult(false, "Некорректный duration_ms: «$durationRaw».")
            }
            val durationMs = requested.coerceIn(
                AudioCaptureService.MIN_DURATION_MS,
                AudioCaptureService.MAX_DURATION_MS,
            )

            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as? MediaProjectionManager
                ?: return@withContext ToolResult(false, "MediaProjection недоступен на этом устройстве.")

            // Per-session consent (the token must not be reused on Android 14+), bounded by the
            // shared UI-consent policy so a broadcast caller never ANRs (BUG-014/BUG-016).
            val consent = uiBridge.requestBoundedUi(
                UiRequest.ScreenCaptureConsent(manager.createScreenCaptureIntent()),
                timeoutMs = CONSENT_TIMEOUT_MS,
            )
            val activityResult = consent as? UiResult.ActivityResult
                ?: return@withContext ToolResult(false, captureConsentFailure(consent))
            if (activityResult.resultCode != Activity.RESULT_OK || activityResult.data == null) {
                return@withContext ToolResult(
                    false,
                    "Пользователь не дал согласие на захват системного звука.",
                )
            }

            AudioCaptureState.reset()
            if (!AudioCaptureService.start(
                    context,
                    activityResult.resultCode,
                    activityResult.data,
                    destination.absolutePath,
                    durationMs,
                )
            ) {
                return@withContext ToolResult(false, "Не удалось запустить службу захвата звука.")
            }

            val budget = durationMs + START_SLACK_MS
            val finalState = withTimeoutOrNull(budget) {
                AudioCaptureState.status.first { it.finished }
            } ?: return@withContext ToolResult(
                false,
                "Служба захвата звука не завершилась за ${budget / 1000}с.",
            )
            finalState.error?.let { return@withContext ToolResult(false, it) }

            val file = File(destination.absolutePath)
            if (!file.exists() || file.length() <= 0L) {
                return@withContext ToolResult(
                    false,
                    "Файл записи не создан или пуст: «${destination.absolutePath}».",
                )
            }

            val suffix = if (finalState.silent) {
                " Уровень сигнала очень низкий (${AudioFormatting.formatDouble(finalState.rmsDbfs)} dBFS): " +
                    "вероятно, приложение-источник запретило захват своего звука."
            } else {
                " Уровень сигнала: ${AudioFormatting.formatDouble(finalState.rmsDbfs)} dBFS."
            }
            ToolResult(
                true,
                "Захвачено ${finalState.bytes} байт за ${durationMs / 1000}с → " +
                    "«${destination.absolutePath}» (WAV, 44.1 кГц, стерео, PCM16).$suffix",
            )
        } catch (e: Exception) {
            Log.e(AudioLog.TAG, "audio_capture_app failed", e)
            ToolResult(false, "Не удалось записать звук приложений: ${e.message}")
        }
    }

    private fun captureConsentFailure(result: UiResult): String = when (result) {
        is UiResult.Failed -> "Не удалось получить подтверждение: ${result.message}"
        UiResult.Cancelled -> "Запрос согласия на захват звука отменён (диалог закрыт)."
        is UiResult.ConsentResult -> "Не получено согласие на захват системного звука."
        is UiResult.UriResult -> "Неожиданный ответ интерфейса: согласие на захват звука не получено."
        is UiResult.ActivityResult -> "Не удалось получить результат согласия на захват звука."
        UiResult.PrintDialogShown -> "Неожиданный ответ интерфейса: согласие на захват звука не получено."
    }

    private companion object {
        /** `audio_capture_app`: time for the user to accept the system consent dialog. */
        private const val CONSENT_TIMEOUT_MS = UiConsentPolicy.TIMEOUT_MS

        /** Slack on top of the requested duration for the capture service to start/stop. */
        private const val START_SLACK_MS = 15_000L
    }
}
