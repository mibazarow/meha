package com.andharness.ru.data.tools.communications

import android.content.Context
import com.andharness.ru.data.communications.RecordingImporter
import com.andharness.ru.data.communications.RecordingsScanner
import com.andharness.ru.data.communications.TranscriptionService
import com.andharness.ru.data.embedding.LocalEmbedder
import com.andharness.ru.data.tools.person.PersonReadSupport
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
 * Communications tools batch 1: local recordings, transcripts, unified communication search and a
 * person brief. A thin coordinator that wires one [ToolHandler] per tool in catalog order
 * (`CommunicationsTools.defs()`), reusing the person read-side ([PersonReadSupport]) for honest
 * person resolution so the brief never guesses which person is meant.
 *
 * Honesty rules enforced by the per-tool handlers:
 * - `recording_transcribe` reports the REAL pipeline outcome and fails loudly when the speech model
 *   is missing — it never downloads a model and never fabricates a transcript;
 * - transcript tools only ever read persisted segments; a recording without a transcript says so;
 * - `communication_search` / `person_brief` label every hit with its channel and state plainly when
 *   a store had nothing (or could not be read), instead of inventing content.
 */
class CommunicationsToolsHandler(
    private val recordingsRepository: RecordingsRepository,
    private val transcriptRepository: TranscriptRepository,
    private val speakerRepository: SpeakerRepository,
    private val transcriptionService: TranscriptionService,
    private val personRepository: PersonRepository,
    private val personIdentityRepository: PersonIdentityRepository,
    private val personAliasRepository: PersonAliasRepository,
    private val interactionRepository: InteractionRepository,
    private val assistRepository: AssistRepository,
    private val recordingsScanner: RecordingsScanner,
    private val recordingImporter: RecordingImporter,
    private val context: Context,
    private val uiInteractionBridge: UiInteractionBridge,
    private val localEmbedder: LocalEmbedder? = null,
    private val settingsStore: LlmSettingsStore? = null,
    private val messageSearch: MessageSearch,
    private val storedMessagesRepository: StoredMessagesRepository,
    private val llmClient: LlmClient,
) {

    fun handlers(): List<ToolHandler> {
        val args = CommunicationsToolArgs()
        val readSupport = PersonReadSupport(
            personRepository = personRepository,
            identityRepository = personIdentityRepository,
            aliasRepository = personAliasRepository,
            interactionRepository = interactionRepository,
        )
        return listOf(
            RecordingsListToolHandler(recordingsRepository, args),
            RecordingTranscribeToolHandler(recordingsRepository, transcriptionService, args),
            TranscriptGetToolHandler(
                recordingsRepository,
                transcriptRepository,
                speakerRepository,
                personRepository,
                args,
            ),
            TranscriptSearchToolHandler(recordingsRepository, transcriptRepository, args),
            CommunicationSearchToolHandler(
                recordingsRepository,
                transcriptRepository,
                interactionRepository,
                assistRepository,
                personRepository,
                args,
                localEmbedder,
                settingsStore,
                messageSearch,
            ),
            MessageSearchToolHandler(
                messageSearch = messageSearch,
                readSupport = readSupport,
                args = args,
            ),
            MessagesSummarizeToolHandler(
                messageSearch = messageSearch,
                storedMessagesRepository = storedMessagesRepository,
                readSupport = readSupport,
                assistRepository = assistRepository,
                llmClient = llmClient,
                settingsStore = settingsStore,
                args = args,
            ),
            PersonBriefToolHandler(
                readSupport,
                personRepository,
                interactionRepository,
                recordingsRepository,
                transcriptRepository,
                assistRepository,
                args,
            ),
            SpeakerRegistryListToolHandler(speakerRepository, personRepository, args),
            SpeakerNameAssignToolHandler(speakerRepository, personRepository, readSupport, args),
            SpeakerRegistryForgetToolHandler(speakerRepository, args),
            RecordingsScanToolHandler(recordingsScanner, args),
            RecordingImportToolHandler(recordingImporter, args),
            MessengerVoiceCaptureToolHandler(context, uiInteractionBridge, recordingImporter, args),
            CommitmentsExtractToolHandler(
                recordingsRepository,
                transcriptRepository,
                assistRepository,
                args,
            ),
        )
    }
}
