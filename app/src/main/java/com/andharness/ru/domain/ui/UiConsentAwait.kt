package com.andharness.ru.domain.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Publishes [uiRequest] to the UI and awaits the user's answer under the bounded
 * [UiConsentPolicy.TIMEOUT_MS] budget, so a `goAsync()` caller can never hold its broadcast past
 * Android's ANR window —
 * https://developer.android.com/reference/android/content/BroadcastReceiver#goAsync()
 *
 * The wait is skipped entirely when no foreground Activity can host the system dialog
 * ([UiConsentPolicy.canAwaitUi]): the honest answer in that case is an immediate
 * [UiResult.Failed] carrying [UiConsentPolicy.NO_UI_MESSAGE], never a full-length wait.
 *
 * The await runs on [Dispatchers.IO] even when the caller dispatches the tool handler on Main,
 * so the UI thread is never blocked. This is the single shared implementation of the bounded
 * consent pattern used by every interactive tool (BUG-014, BUG-016).
 */
internal suspend fun UiInteractionBridge.requestBoundedUi(
    uiRequest: UiRequest,
    timeoutMs: Long = UiConsentPolicy.TIMEOUT_MS,
): UiResult {
    if (!UiConsentPolicy.canAwaitUi(ActiveChatTracker.isAppForeground)) {
        return UiResult.Failed(UiConsentPolicy.NO_UI_MESSAGE)
    }
    return withContext(Dispatchers.IO) {
        request(uiRequest, timeoutMs)
    }
}
