package com.andharness.ru.di.toolhandlers

import android.content.Context
import com.andharness.ru.data.core.WatchdogConfigStore
import com.andharness.ru.data.diagnostics.PowerGuard
import com.andharness.ru.data.diagnostics.ProcessExitReader
import com.andharness.ru.data.intercept.NumberChatManager
import com.andharness.ru.data.intercept.NumberChatStore
import com.andharness.ru.data.message.MessageSendStore
import com.andharness.ru.data.message.MessageSender
import com.andharness.ru.data.osint.OsintCacheStore
import com.andharness.ru.data.person.MessagingIndexConfigStore
import com.andharness.ru.data.person.PersonBackfillStore
import com.andharness.ru.data.person.PersonIndexer
import com.andharness.ru.data.ssh.KnownHostsStore
import com.andharness.ru.data.ssh.SshClient
import com.andharness.ru.data.ssh.SshSecretStore
import com.andharness.ru.data.tools.diagnostics.DiagnosticsToolsHandler
import com.andharness.ru.data.tools.documents.DocumentToolsHandler
import com.andharness.ru.data.tools.intercept.InterceptToolsHandler
import com.andharness.ru.data.tools.person.PersonToolsHandler
import com.andharness.ru.data.tools.ssh.SshToolsHandler
import com.andharness.ru.data.vision.OcrRecognizer
import com.andharness.ru.di.PdfModule
import com.andharness.ru.domain.assist.AssistRepository
import com.andharness.ru.domain.automation.AutomationRuleRepository
import com.andharness.ru.domain.chat.CurrentChatProvider
import com.andharness.ru.domain.crash.CrashLogRepository
import com.andharness.ru.domain.crash.RestartStateRepository
import com.andharness.ru.domain.person.InteractionRepository
import com.andharness.ru.domain.person.PersonAliasRepository
import com.andharness.ru.domain.person.PersonIdentityRepository
import com.andharness.ru.domain.person.PersonRepository
import com.andharness.ru.domain.ssh.SshServerRepository
import com.andharness.ru.domain.tools.ToolHandler

/** Documents, diagnostics, intercept and the person/SSH domains' tool handlers. */
internal fun documentsPersonToolHandlers(
    context: Context,
    pdfInitializer: PdfModule.PdfBoxInitializer,
    ocrRecognizer: OcrRecognizer,
    crashLogRepository: CrashLogRepository,
    restartStateRepository: RestartStateRepository,
    watchdogConfigStore: WatchdogConfigStore,
    automationRuleRepository: AutomationRuleRepository,
    processExitReader: ProcessExitReader,
    powerGuard: PowerGuard,
    assistRepository: AssistRepository,
    numberChatManager: NumberChatManager,
    osintCacheStore: OsintCacheStore,
    numberChatStore: NumberChatStore,
    messagingIndexConfigStore: MessagingIndexConfigStore,
    personBackfillStore: PersonBackfillStore,
    personIndexer: PersonIndexer,
    personRepository: PersonRepository,
    personIdentityRepository: PersonIdentityRepository,
    personAliasRepository: PersonAliasRepository,
    interactionRepository: InteractionRepository,
    currentChatProvider: CurrentChatProvider,
    messageSender: MessageSender,
    messageSendStore: MessageSendStore,
    sshServerRepository: SshServerRepository,
    sshSecretStore: SshSecretStore,
    knownHostsStore: KnownHostsStore,
    sshClient: SshClient,
): List<ToolHandler> =
    (DocumentToolsHandler(context, pdfInitializer, ocrRecognizer).handlers()
        + DiagnosticsToolsHandler(
            context,
            crashLogRepository,
            restartStateRepository,
            watchdogConfigStore,
            automationRuleRepository,
            processExitReader,
            powerGuard,
            assistRepository
        ).handlers()
        + InterceptToolsHandler(
            context,
            numberChatManager,
            osintCacheStore,
            numberChatStore
        ).handlers()
        + PersonToolsHandler(
            context,
            messagingIndexConfigStore,
            personBackfillStore,
            personIndexer,
            personRepository,
            personIdentityRepository,
            personAliasRepository,
            interactionRepository,
            currentChatProvider,
            messageSender,
            messageSendStore
        ).handlers()
        + SshToolsHandler(
            context,
            sshServerRepository,
            sshSecretStore,
            knownHostsStore,
            sshClient
        ).handlers()
)
