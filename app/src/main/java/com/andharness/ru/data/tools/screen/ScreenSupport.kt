package com.andharness.ru.data.tools.screen

import com.andharness.ru.domain.ui.UiConsentPolicy
import com.andharness.ru.domain.ui.UiResult

/**
 * Timeouts and consent-outcome mapping shared by the screen tools (`screen_record_start`,
 * `screen_record_stop`, `screen_record_status`, `screen_capture_region`).
 *
 * Consent is required for EVERY session: on Android 14+ (API 34+) a `MediaProjection` token must not
 * be reused, so the system dialog is shown again on each capture.
 * References:
 * - https://developer.android.com/media/grow/media-projection
 * - https://developer.android.com/about/versions/14/changes/fgs-types-required
 * - https://developer.android.com/reference/android/media/projection/MediaProjectionManager
 */
internal object ScreenSupport {

    /** The user needs time to read and accept the system screen-capture dialog. */
    const val CONSENT_TIMEOUT_MS = UiConsentPolicy.TIMEOUT_MS

    /** Bounded wait for the service to report that recording actually began. */
    const val START_TIMEOUT_MS = 8_000L

    /** Bounded wait for the service to release the recorder and finalize the file. */
    const val STOP_TIMEOUT_MS = 10_000L

    /** Bounded wait for the single-frame capture service to report a verified outcome. */
    const val CAPTURE_TIMEOUT_MS = 15_000L

    /** Upper bound for a timed `screen_capture_region` recording, so the tool stays bounded. */
    const val MAX_DURATION_MS = 60_000L

    /** Turns a non-[UiResult.ActivityResult] bridge outcome into a Russian failure reason. */
    fun consentFailureMessage(result: UiResult): String = when (result) {
        is UiResult.Failed -> result.message
        UiResult.Cancelled -> "Запрос согласия на запись экрана отменён (диалог закрыт)."
        is UiResult.ConsentResult -> "Не получено согласие на запись экрана."
        is UiResult.UriResult -> "Неожиданный ответ интерфейса: согласие на запись экрана не получено."
        is UiResult.ActivityResult -> "Не удалось получить результат согласия на запись экрана."
        UiResult.PrintDialogShown -> "Неожиданный ответ интерфейса: согласие на запись экрана не получено."
    }
}
