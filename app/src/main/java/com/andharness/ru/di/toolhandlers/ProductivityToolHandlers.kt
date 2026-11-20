package com.andharness.ru.di.toolhandlers

import android.content.Context
import com.andharness.ru.data.clipboard.ClipboardHistoryStore
import com.andharness.ru.data.notifications.NotificationHelper
import com.andharness.ru.data.profile.GlobalProfileStore
import com.andharness.ru.data.schedule.ScheduledTaskActions
import com.andharness.ru.data.tools.assist.AssistToolsHandler
import com.andharness.ru.data.tools.intents.IntentToolsHandler
import com.andharness.ru.data.tools.media.MediaActionToolsHandler
import com.andharness.ru.data.tools.misc.ClipboardHistoryToolsHandler
import com.andharness.ru.data.tools.misc.MiscToolsHandler
import com.andharness.ru.data.tools.profile.ProfileToolsHandler
import com.andharness.ru.data.tools.schedule.ScheduleToolsHandler
import com.andharness.ru.data.tools.web.WebAutomationToolsHandler
import com.andharness.ru.domain.undo.UndoRecorder
import com.andharness.ru.domain.assist.AssistRepository
import com.andharness.ru.domain.chat.CurrentChatProvider
import com.andharness.ru.domain.common.TimeProvider
import com.andharness.ru.domain.schedule.ScheduleRepository
import com.andharness.ru.domain.tools.ToolHandler
import okhttp3.OkHttpClient

/** Misc/productivity tool handlers: clipboard, intents, web automation, schedule, assist, profile. */
internal fun productivityToolHandlers(
    context: Context,
    notificationHelper: NotificationHelper,
    currentChatProvider: CurrentChatProvider,
    clipboardHistoryStore: ClipboardHistoryStore,
    okHttpClient: OkHttpClient,
    scheduleRepository: ScheduleRepository,
    scheduledTaskActions: ScheduledTaskActions,
    timeProvider: TimeProvider,
    undoRecorder: UndoRecorder,
    assistRepository: AssistRepository,
    globalProfileStore: GlobalProfileStore,
): List<ToolHandler> =
    (MiscToolsHandler(context, notificationHelper, currentChatProvider, clipboardHistoryStore).handlers()
        + ClipboardHistoryToolsHandler(clipboardHistoryStore).handlers()
        + IntentToolsHandler(context).handlers()
        + MediaActionToolsHandler(context).handlers()
        + WebAutomationToolsHandler(context, okHttpClient).handlers()
        + ScheduleToolsHandler(scheduleRepository, scheduledTaskActions, currentChatProvider, timeProvider, undoRecorder).handlers()
        + AssistToolsHandler(assistRepository, undoRecorder).handlers()
        + ProfileToolsHandler(globalProfileStore).handlers()
)
