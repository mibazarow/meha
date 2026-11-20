package com.andharness.ru.di.toolhandlers

import android.content.Context
import com.andharness.ru.data.communications.RecordingImporter
import com.andharness.ru.data.communications.RecordingsScanner
import com.andharness.ru.data.communications.TranscriptionService
import com.andharness.ru.data.embedding.LocalEmbedder
import com.andharness.ru.data.tools.communications.CommunicationsToolsHandler
import com.andharness.ru.domain.agent.LlmClient
import com.andharness.ru.domain.assist.AssistRepository
import com.andharness.ru.domain.communications.RecordingsRepository
import com.andharness.ru.domain.communications.SpeakerRepository
import com.andharness.ru.domain.communications.TranscriptRepository
import com.andharness.ru.domain.person.InteractionRepository
import com.andharness.ru.domain.person.PersonAliasRepository
import com.andharness.ru.domain.person.PersonIdentityRepository
import com.andharness.ru.domain.person.PersonRepository
import com.andharness.ru.domain.messages.MessageSearch
import com.andharness.ru.domain.messages.StoredMessagesRepository
import com.andharness.ru.domain.settings.LlmSettingsStore
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.ui.UiInteractionBridge

/**
 * Communications tools batch 1 (recordings / transcripts / unified search / person brief). Wires the
 * per-tool handlers through [CommunicationsToolsHandler], together with the shared person read-side
 * so person resolution follows the same honest «never guess» path as the `person_*` tools.
 */
internal fun communicationsToolHandlers(
    recordingsRepository: RecordingsRepository,
    transcriptRepository: TranscriptRepository,
    speakerRepository: SpeakerRepository,
    transcriptionService: TranscriptionService,
    personRepository: PersonRepository,
    personIdentityRepository: PersonIdentityRepository,
    personAliasRepository: PersonAliasRepository,
    interactionRepository: InteractionRepository,
    assistRepository: AssistRepository,
    recordingsScanner: RecordingsScanner,
    recordingImporter: RecordingImporter,
    context: Context,
    uiInteractionBridge: UiInteractionBridge,
    localEmbedder: LocalEmbedder? = null,
    settingsStore: LlmSettingsStore? = null,
    messageSearch: MessageSearch,
    storedMessagesRepository: StoredMessagesRepository,
    llmClient: LlmClient,
): List<ToolHandler> = CommunicationsToolsHandler(
    recordingsRepository = recordingsRepository,
    transcriptRepository = transcriptRepository,
    speakerRepository = speakerRepository,
    transcriptionService = transcriptionService,
    personRepository = personRepository,
    personIdentityRepository = personIdentityRepository,
    personAliasRepository = personAliasRepository,
    interactionRepository = interactionRepository,
    assistRepository = assistRepository,
    recordingsScanner = recordingsScanner,
    recordingImporter = recordingImporter,
    context = context,
    uiInteractionBridge = uiInteractionBridge,
    localEmbedder = localEmbedder,
    settingsStore = settingsStore,
    messageSearch = messageSearch,
    storedMessagesRepository = storedMessagesRepository,
    llmClient = llmClient,
).handlers()
