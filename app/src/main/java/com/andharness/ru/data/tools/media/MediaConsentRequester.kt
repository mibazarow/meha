package com.andharness.ru.data.tools.media

import android.content.IntentSender
import com.andharness.ru.domain.ui.UiConsentPolicy
import com.andharness.ru.domain.ui.UiInteractionBridge
import com.andharness.ru.domain.ui.UiRequest
import com.andharness.ru.domain.ui.UiResult
import com.andharness.ru.domain.ui.requestBoundedUi

/**
 * Publishes a [UiRequest.MediaConsent] and awaits the user's answer OFF the main thread via the
 * shared bounded-consent helper [requestBoundedUi]: it skips the wait when no foreground Activity
 * can host the system dialog and otherwise bounds the wait by [timeoutMs], which must stay inside
 * [UiConsentPolicy.BROADCAST_ANR_WINDOW_MS] — otherwise the debug bridge's `goAsync()` broadcast
 * would be held for the whole timeout and ANR (BUG-014).
 */
internal class MediaConsentRequester(private val uiBridge: UiInteractionBridge) {

    suspend fun request(
        intentSender: IntentSender,
        action: String,
        timeoutMs: Long,
    ): UiResult = uiBridge.requestBoundedUi(
        UiRequest.MediaConsent(intentSender, action),
        timeoutMs,
    )

    /**
     * Translates a [UiResult] consent outcome into an honest error message, or `null` when the
     * user granted the request.
     */
    fun errorMessage(consent: UiResult, declined: String, cancelled: String): String? =
        when (consent) {
            is UiResult.ConsentResult -> if (consent.granted) null else declined
            UiResult.Cancelled -> cancelled
            is UiResult.Failed -> "Не удалось получить подтверждение: ${consent.message}"
            is UiResult.UriResult -> "Неожиданный ответ диалога подтверждения."
            is UiResult.ActivityResult -> "Неожиданный ответ диалога подтверждения."
            UiResult.PrintDialogShown -> "Неожиданный ответ диалога подтверждения."
        }
}
