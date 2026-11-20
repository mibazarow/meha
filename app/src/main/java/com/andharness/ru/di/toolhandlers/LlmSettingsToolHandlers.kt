package com.andharness.ru.di.toolhandlers

import android.content.Context
import com.andharness.ru.data.tools.jobs.JobSchedulerToolsHandler
import com.andharness.ru.data.tools.misc.ChatManageToolsHandler
import com.andharness.ru.data.tools.misc.ChatReadToolsHandler
import com.andharness.ru.data.tools.misc.TranslateChatToolsHandler
import com.andharness.ru.data.tools.misc.TranslateScreenToolsHandler
import com.andharness.ru.data.tools.misc.TranslateToolsHandler
import com.andharness.ru.data.tools.sensors.HealthSensorsToolsHandler
import com.andharness.ru.data.tools.sensors.SensorToolsHandler
import com.andharness.ru.data.tools.settings.SettingsToolsHandler
import com.andharness.ru.data.tools.system.ForegroundServiceToolsHandler
import com.andharness.ru.data.tools.system.SelfConfigToolsHandler
import com.andharness.ru.domain.undo.UndoRecorder
import com.andharness.ru.data.vision.OcrRecognizer
import com.andharness.ru.domain.agent.LlmClient
import com.andharness.ru.domain.chat.ChatRepository
import com.andharness.ru.domain.chat.CurrentChatProvider
import com.andharness.ru.domain.settings.LlmSettingsStore
import com.andharness.ru.domain.tools.ToolHandler

/** LLM/translate, sensor, settings, job-scheduler and foreground-service handlers. */
internal fun llmSettingsToolHandlers(
    context: Context,
    llmSettingsStore: LlmSettingsStore,
    llmClient: LlmClient,
    chatRepository: ChatRepository,
    ocrRecognizer: OcrRecognizer,
    currentChatProvider: CurrentChatProvider,
    undoRecorder: UndoRecorder,
): List<ToolHandler> =
    (SelfConfigToolsHandler(llmSettingsStore).handlers()
        + TranslateToolsHandler(llmClient, llmSettingsStore).handlers()
        + TranslateChatToolsHandler(llmClient, llmSettingsStore, chatRepository).handlers()
        + ChatReadToolsHandler(llmClient, llmSettingsStore, chatRepository).handlers()
        + ChatManageToolsHandler(chatRepository, context).handlers()
        + TranslateScreenToolsHandler(llmClient, llmSettingsStore, ocrRecognizer).handlers()
        + SensorToolsHandler(context).handlers()
        + HealthSensorsToolsHandler(context).handlers()
        + SettingsToolsHandler(context, undoRecorder).handlers()
        + JobSchedulerToolsHandler(context, currentChatProvider).handlers()
        + ForegroundServiceToolsHandler(context).handlers()
)
