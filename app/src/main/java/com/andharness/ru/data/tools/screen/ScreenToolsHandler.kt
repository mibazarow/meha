package com.andharness.ru.data.tools.screen

import android.content.Context
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.ui.UiInteractionBridge

/**
 * Builds the screen-recording tools: `screen_record_start`, `screen_record_stop`,
 * `screen_record_status`, `screen_capture_region`, in their original registration order.
 *
 * This class is a thin coordinator: each tool lives in its own `<Tool>Handler` class and the shared
 * timeouts / consent-outcome mapping are in [ScreenSupport]. Real recording is done by
 * `ScreenRecordService` (MediaProjection foreground service).
 *
 * Consent is required for EVERY session: on Android 14+ (API 34+) a `MediaProjection` token must not
 * be reused, so the system dialog is shown again on each capture.
 * References:
 * - https://developer.android.com/media/grow/media-projection
 * - https://developer.android.com/about/versions/14/changes/fgs-types-required
 * - https://developer.android.com/reference/android/media/projection/MediaProjectionManager
 */
class ScreenToolsHandler(
    private val context: Context,
    private val uiBridge: UiInteractionBridge,
) {

    fun handlers(): List<ToolHandler> = listOf(
        ScreenRecordStartToolHandler(context, uiBridge),
        ScreenRecordStopToolHandler(context),
        ScreenRecordStatusToolHandler(),
        ScreenCaptureRegionToolHandler(context, uiBridge),
    )
}
