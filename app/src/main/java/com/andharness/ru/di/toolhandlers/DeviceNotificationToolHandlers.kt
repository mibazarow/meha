package com.andharness.ru.di.toolhandlers

import android.content.Context
import com.andharness.ru.data.memory.DeviceFactStore
import com.andharness.ru.data.tools.device.DeviceAdminToolsHandler
import com.andharness.ru.data.tools.memory.DeviceFactsToolsHandler
import com.andharness.ru.data.tools.notifications.NotificationListenerToolsHandler
import com.andharness.ru.data.tools.notifications.NotificationToolsHandler
import com.andharness.ru.data.tools.saf.SafToolsHandler
import com.andharness.ru.data.tools.speech.SpeechToolsHandler
import com.andharness.ru.data.tools.trash.TrashToolsHandler
import com.andharness.ru.data.tools.usage.UsageToolsHandler
import com.andharness.ru.data.tools.vision.VisionToolsHandler
import com.andharness.ru.data.tools.watch.FolderWatchToolsHandler
import com.andharness.ru.data.tools.web.WebFetchToolsHandler
import com.andharness.ru.data.vision.OcrRecognizer
import com.andharness.ru.data.vision.VisionCapability
import com.andharness.ru.domain.saf.SafGrantRepository
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.trash.TrashRepository
import com.andharness.ru.domain.ui.UiInteractionBridge
import com.andharness.ru.domain.watch.FolderWatchRepository
import okhttp3.OkHttpClient

/** Notifications, usage, admin/speech/vision, folder-watch, web-fetch, SAF, trash and facts handlers. */
internal fun deviceNotificationToolHandlers(
    context: Context,
    uiInteractionBridge: UiInteractionBridge,
    visionCapability: VisionCapability,
    ocrRecognizer: OcrRecognizer,
    folderWatchRepository: FolderWatchRepository,
    safGrantRepository: SafGrantRepository,
    trashRepository: TrashRepository,
    deviceFactStore: DeviceFactStore,
    okHttpClient: OkHttpClient,
): List<ToolHandler> =
    (NotificationListenerToolsHandler(context).handlers()
        + NotificationToolsHandler(context).handlers()
        + UsageToolsHandler(context).handlers()
        + DeviceAdminToolsHandler(context, uiInteractionBridge).handlers()
        + SpeechToolsHandler(context).handlers()
        + VisionToolsHandler(context, visionCapability, ocrRecognizer).handlers()
        + FolderWatchToolsHandler(context, folderWatchRepository).handlers()
        + WebFetchToolsHandler(okHttpClient).all()
        + SafToolsHandler(context, uiInteractionBridge, safGrantRepository).handlers()
        + TrashToolsHandler(context, trashRepository).handlers()
        + DeviceFactsToolsHandler(deviceFactStore).handlers()
)
