package com.andharness.ru.di

import android.content.Context
import com.andharness.ru.data.attachments.AttachmentStore
import com.andharness.ru.data.alarm.ExactAlarmScheduler
import com.andharness.ru.data.automation.AutomationTriggerManager
import com.andharness.ru.data.clipboard.ClipboardHistoryStore
import com.andharness.ru.data.communications.RecordingImporter
import com.andharness.ru.data.communications.RecordingsScanner
import com.andharness.ru.data.communications.TranscriptionService
import com.andharness.ru.data.core.WatchdogConfigStore
import com.andharness.ru.data.diagnostics.PowerGuard
import com.andharness.ru.data.diagnostics.ProcessExitReader
import com.andharness.ru.data.embedding.LocalEmbedder
import com.andharness.ru.data.index.DeviceSearchService
import com.andharness.ru.data.intercept.NumberChatManager
import com.andharness.ru.data.intercept.NumberChatStore
import com.andharness.ru.data.mcp.McpClient
import com.andharness.ru.data.mcp.McpSecretStore
import com.andharness.ru.data.mcp.McpServerStore
import com.andharness.ru.data.memory.DeviceFactStore
import com.andharness.ru.data.message.MessageSendStore
import com.andharness.ru.data.message.MessageSender
import com.andharness.ru.data.notifications.NotificationHelper
import com.andharness.ru.data.notifications.NotificationRulesStore
import com.andharness.ru.data.objective.ObjectiveRunCoordinator
import com.andharness.ru.data.objective.ObjectiveStore
import com.andharness.ru.data.osint.OsintCacheStore
import com.andharness.ru.data.person.MessagingIndexConfigStore
import com.andharness.ru.data.person.PersonBackfillStore
import com.andharness.ru.data.person.PersonIndexer
import com.andharness.ru.data.profile.GlobalProfileStore
import com.andharness.ru.data.schedule.ScheduledTaskActions
import com.andharness.ru.data.screenmap.ScreenMapStore
import com.andharness.ru.data.skill.SkillStore
import com.andharness.ru.data.ssh.KnownHostsStore
import com.andharness.ru.data.ssh.SshClient
import com.andharness.ru.data.ssh.SshSecretStore
import com.andharness.ru.data.calls.CallRulesStore
import com.andharness.ru.data.tools.camera.PhotoAnalyzer
import com.andharness.ru.domain.undo.UndoExecutor
import com.andharness.ru.domain.undo.UndoRecorder
import com.andharness.ru.data.undo.UndoStore
import com.andharness.ru.data.usage.AutomationSuggestionEngine
import com.andharness.ru.data.vision.OcrRecognizer
import com.andharness.ru.data.vision.VisionCapability
import com.andharness.ru.di.toolhandlers.automationSkillToolHandlers
import com.andharness.ru.di.toolhandlers.communicationsToolHandlers
import com.andharness.ru.di.toolhandlers.contactsMediaToolHandlers
import com.andharness.ru.di.toolhandlers.deviceNotificationToolHandlers
import com.andharness.ru.di.toolhandlers.deviceSearchToolHandlers
import com.andharness.ru.di.toolhandlers.documentsPersonToolHandlers
import com.andharness.ru.di.toolhandlers.filesSystemToolHandlers
import com.andharness.ru.di.toolhandlers.insightsToolHandlers
import com.andharness.ru.di.toolhandlers.llmSettingsToolHandlers
import com.andharness.ru.di.toolhandlers.mcpToolHandlers
import com.andharness.ru.di.toolhandlers.objectiveToolHandlers
import com.andharness.ru.di.toolhandlers.productivityToolHandlers
import com.andharness.ru.domain.agent.LlmClient
import com.andharness.ru.domain.alarm.ExactAlarmRepository
import com.andharness.ru.domain.assist.AssistRepository
import com.andharness.ru.domain.audit.ToolAuditRepository
import com.andharness.ru.domain.automation.AutomationEventRepository
import com.andharness.ru.domain.automation.AutomationRuleRepository
import com.andharness.ru.domain.automation.AutomationScheduler
import com.andharness.ru.domain.chat.ChatRepository
import com.andharness.ru.domain.chat.CurrentChatProvider
import com.andharness.ru.domain.common.TimeProvider
import com.andharness.ru.domain.communications.RecordingsRepository
import com.andharness.ru.domain.communications.SpeakerRepository
import com.andharness.ru.domain.communications.TranscriptRepository
import com.andharness.ru.domain.crash.CrashLogRepository
import com.andharness.ru.domain.mcp.McpDynamicTools
import com.andharness.ru.domain.crash.RestartStateRepository
import com.andharness.ru.domain.notify.NotificationCaptureLog
import com.andharness.ru.domain.messages.MessageSearch
import com.andharness.ru.domain.messages.StoredMessagesRepository
import com.andharness.ru.domain.person.InteractionRepository
import com.andharness.ru.domain.person.PersonAliasRepository
import com.andharness.ru.domain.person.PersonIdentityRepository
import com.andharness.ru.domain.person.PersonRepository
import com.andharness.ru.domain.rag.RagRepository
import com.andharness.ru.domain.saf.SafGrantRepository
import com.andharness.ru.domain.schedule.ScheduleRepository
import com.andharness.ru.domain.settings.LlmSettingsStore
import com.andharness.ru.domain.ssh.SshServerRepository
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.trash.TrashRepository
import com.andharness.ru.domain.ui.UiInteractionBridge
import com.andharness.ru.domain.watch.FolderWatchRepository
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * The single `Set<ToolHandler>` multibinding point. The provider name, signature and returned type
 * are frozen (Hilt resolves by type, and `ToolRegistryImpl` dispatches by each handler's `name`).
 * The body is assembled from per-domain helpers (see `di/toolhandlers/`) **in the original order**;
 * the resulting set is identical to the pre-split single expression.
 */
@Module
@InstallIn(SingletonComponent::class)
object ToolHandlersModule {

    @Provides
    @Singleton
    fun provideToolHandlers(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
        scheduleRepository: ScheduleRepository,
        scheduledTaskActions: ScheduledTaskActions,
        assistRepository: AssistRepository,
        llmSettingsStore: LlmSettingsStore,
        llmClient: LlmClient,
        uiInteractionBridge: UiInteractionBridge,
        safGrantRepository: SafGrantRepository,
        trashRepository: TrashRepository,
        folderWatchRepository: FolderWatchRepository,
        pdfInitializer: PdfModule.PdfBoxInitializer,
        notificationHelper: NotificationHelper,
        currentChatProvider: CurrentChatProvider,
        chatRepository: ChatRepository,
        timeProvider: TimeProvider,
        exactAlarmRepository: ExactAlarmRepository,
        exactAlarmScheduler: ExactAlarmScheduler,
        automationRuleRepository: AutomationRuleRepository,
        automationEventRepository: AutomationEventRepository,
        automationScheduler: AutomationScheduler,
        automationTriggerManager: Lazy<AutomationTriggerManager>,
        deviceFactStore: DeviceFactStore,
        crashLogRepository: CrashLogRepository,
        restartStateRepository: RestartStateRepository,
        watchdogConfigStore: WatchdogConfigStore,
        numberChatManager: NumberChatManager,
        numberChatStore: NumberChatStore,
        osintCacheStore: OsintCacheStore,
        visionCapability: VisionCapability,
        personIndexer: PersonIndexer,
        messagingIndexConfigStore: MessagingIndexConfigStore,
        personBackfillStore: PersonBackfillStore,
        personRepository: PersonRepository,
        personIdentityRepository: PersonIdentityRepository,
        personAliasRepository: PersonAliasRepository,
        interactionRepository: InteractionRepository,
        recordingsRepository: RecordingsRepository,
        transcriptRepository: TranscriptRepository,
        speakerRepository: SpeakerRepository,
        transcriptionService: TranscriptionService,
        recordingsScanner: RecordingsScanner,
        recordingImporter: RecordingImporter,
        messageSender: MessageSender,
        messageSendStore: MessageSendStore,
        sshServerRepository: SshServerRepository,
        sshSecretStore: SshSecretStore,
        knownHostsStore: KnownHostsStore,
        sshClient: SshClient,
        toolAuditRepository: ToolAuditRepository,
        ragRepository: RagRepository,
        processExitReader: ProcessExitReader,
        powerGuard: PowerGuard,
        notificationCaptureLog: NotificationCaptureLog,
        notificationRulesStore: NotificationRulesStore,
        callRulesStore: CallRulesStore,
        automationSuggestionEngine: AutomationSuggestionEngine,
        ocrRecognizer: OcrRecognizer,
        attachmentStore: AttachmentStore,
        skillStore: SkillStore,
        undoStore: UndoStore,
        undoExecutor: UndoExecutor,
        undoRecorder: UndoRecorder,
        clipboardHistoryStore: ClipboardHistoryStore,
        screenMapStore: ScreenMapStore,
        photoAnalyzer: PhotoAnalyzer,
        smsPhotoRuleStore: com.andharness.ru.data.sms.SmsPhotoRuleStore,
        globalProfileStore: GlobalProfileStore,
        mcpServerStore: McpServerStore,
        mcpSecretStore: McpSecretStore,
        mcpClient: McpClient,
        mcpDynamicTools: McpDynamicTools,
        objectiveStore: ObjectiveStore,
        objectiveCoordinator: ObjectiveRunCoordinator,
        localEmbedder: LocalEmbedder,
        messageSearch: MessageSearch,
        storedMessagesRepository: StoredMessagesRepository,
        deviceSearchService: DeviceSearchService,
    ): Set<@JvmSuppressWildcards ToolHandler> =
        (filesSystemToolHandlers(context, okHttpClient, uiInteractionBridge)
            + contactsMediaToolHandlers(
                context, okHttpClient, undoRecorder, photoAnalyzer, uiInteractionBridge
            )
            + productivityToolHandlers(
                context,
                notificationHelper,
                currentChatProvider,
                clipboardHistoryStore,
                okHttpClient,
                scheduleRepository,
                scheduledTaskActions,
                timeProvider,
                undoRecorder,
                assistRepository,
                globalProfileStore
            )
            + llmSettingsToolHandlers(
                context,
                llmSettingsStore,
                llmClient,
                chatRepository,
                ocrRecognizer,
                currentChatProvider,
                undoRecorder
            )
            + automationSkillToolHandlers(
                context,
                exactAlarmRepository,
                exactAlarmScheduler,
                currentChatProvider,
                automationRuleRepository,
                automationEventRepository,
                automationScheduler,
                automationTriggerManager,
                screenMapStore,
                skillStore,
                visionCapability,
                ocrRecognizer,
                attachmentStore,
                uiInteractionBridge,
                assistRepository,
                personRepository,
                personIdentityRepository,
                personAliasRepository,
                interactionRepository
            )
            + deviceNotificationToolHandlers(
                context,
                uiInteractionBridge,
                visionCapability,
                ocrRecognizer,
                folderWatchRepository,
                safGrantRepository,
                trashRepository,
                deviceFactStore,
                okHttpClient
            )
            + documentsPersonToolHandlers(
                context,
                pdfInitializer,
                ocrRecognizer,
                crashLogRepository,
                restartStateRepository,
                watchdogConfigStore,
                automationRuleRepository,
                processExitReader,
                powerGuard,
                assistRepository,
                numberChatManager,
                osintCacheStore,
                numberChatStore,
                messagingIndexConfigStore,
                personBackfillStore,
                personIndexer,
                personRepository,
                personIdentityRepository,
                personAliasRepository,
                interactionRepository,
                currentChatProvider,
                messageSender,
                messageSendStore,
                sshServerRepository,
                sshSecretStore,
                knownHostsStore,
                sshClient
            )
            + communicationsToolHandlers(
                recordingsRepository,
                transcriptRepository,
                speakerRepository,
                transcriptionService,
                personRepository,
                personIdentityRepository,
                personAliasRepository,
                interactionRepository,
                assistRepository,
                recordingsScanner,
                recordingImporter,
                context,
                uiInteractionBridge,
                localEmbedder,
                llmSettingsStore,
                messageSearch,
                storedMessagesRepository,
                llmClient
            )
            + insightsToolHandlers(
                context,
                toolAuditRepository,
                skillStore,
                notificationCaptureLog,
                ragRepository,
                notificationRulesStore,
                callRulesStore,
                smsPhotoRuleStore,
                automationSuggestionEngine,
                undoStore,
                undoExecutor
            )
            + mcpToolHandlers(mcpServerStore, mcpSecretStore, mcpClient, mcpDynamicTools)
            + objectiveToolHandlers(objectiveStore, currentChatProvider, objectiveCoordinator)
            + deviceSearchToolHandlers(deviceSearchService, context)).toSet()
}
