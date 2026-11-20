package com.andharness.ru.data.tools.communications

import com.andharness.ru.data.embedding.LocalEmbedder
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.assist.AssistRepository
import com.andharness.ru.domain.communications.CommunicationSearchComposer
import com.andharness.ru.domain.communications.RecordingsRepository
import com.andharness.ru.domain.communications.TranscriptRepository
import com.andharness.ru.domain.communications.TranscriptSemanticRanker
import com.andharness.ru.domain.person.InteractionRepository
import com.andharness.ru.domain.person.PersonRepository
import com.andharness.ru.domain.messages.MessageSearch
import com.andharness.ru.domain.retrieval.SemanticSearchGate
import com.andharness.ru.domain.settings.LlmSettingsStore
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

private const val DEFAULT_UNIFIED_LIMIT = 30
private const val MAX_UNIFIED_LIMIT = 100
private const val CHANNEL_TRANSCRIPT = "РАСШИФРОВКА"
private const val CHANNEL_MEMORY = "ПАМЯТЬ"

/** Relevance of a lexical (FTS) transcript match; semantic cosine can only reach 1.0, so it never wins by score alone. */
private const val LEXICAL_RANK = 1.0

/** Bounded candidate pool for semantic transcript search: the newest N stored segment vectors. */
private const val SEMANTIC_SCAN_LIMIT = 400

/** Hard budget on the offline query embedding; on timeout the search silently falls back to FTS only. */
private const val EMBED_QUERY_TIMEOUT_MS = 5_000L

/**
 * `communication_search`: merges transcript hits (lexical FTS4 + meaning-based vector search),
 * personal-base interactions and (best-effort) memories into one relevance-then-recency ordered list
 * through [CommunicationSearchComposer]. It never claims more than the stores actually returned: an
 * empty result is reported honestly, and the reply states that transcripts are only searched among
 * already-transcribed recordings and memory is best-effort.
 *
 * The semantic layer is best-effort and bounded: it only runs when the process embedder and the
 * `semanticSearchEnabled` switch are available, scans at most [SEMANTIC_SCAN_LIMIT] already-embedded
 * segments, and degrades to FTS-only on any failure (no model, timeout, missing vectors) — never an
 * error.
 */
internal class CommunicationSearchToolHandler(
    private val recordingsRepository: RecordingsRepository,
    private val transcriptRepository: TranscriptRepository,
    private val interactionRepository: InteractionRepository,
    private val assistRepository: AssistRepository,
    private val personRepository: PersonRepository,
    private val args: CommunicationsToolArgs,
    private val localEmbedder: LocalEmbedder? = null,
    private val settingsStore: LlmSettingsStore? = null,
    private val messageSearch: MessageSearch? = null,
) : ToolHandler {

    override val name = "communication_search"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val query = args.string(arguments, "query")
                ?: return@withContext ToolResult(false, "Укажите query для единого поиска.")
            val limit = args.int(arguments, "limit", DEFAULT_UNIFIED_LIMIT)
                .coerceIn(1, MAX_UNIFIED_LIMIT)

            val items = ArrayList<CommunicationSearchComposer.Item>()
            items += transcriptItems(query, limit)
            items += interactionItems(query, limit)
            items += messageItems(query, limit)
            items += memoryItems(query, limit)

            val merged = CommunicationSearchComposer.merge(items, limit)
            ToolResult(
                true,
                buildString {
                    append("Единый поиск «")
                    append(query)
                    append("» — записей: ")
                    append(merged.size)
                    appendLine(
                        " (расшифровки, взаимодействия, сохранённые сообщения, память; " +
                            "релевантные выше, затем новые):"
                    )
                    append(CommunicationSearchComposer.render(merged))
                    append(
                        "\n\nЧестно: расшифровки ищутся только среди уже распознанных записей " +
                            "(лексически и, при включённой семантике, по смыслу уже " +
                            "проиндексированных), взаимодействия — по метаданным и краткому " +
                            "резюме, сохранённые сообщения — по полному тексту (лексически и, " +
                            "при доступных эмбеддингах, по смыслу), память — best-effort."
                    )
                }
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка единого поиска: ${e.message}")
        }
    }

    private suspend fun transcriptItems(
        query: String,
        limit: Int,
    ): List<CommunicationSearchComposer.Item> {
        val lexicalIds = transcriptRepository.searchFts(query, limit)
        val semanticHits = semanticTranscriptHits(query, limit)
        val ids = TranscriptSemanticRanker.mergeHits(lexicalIds, semanticHits, limit)
        val segments = transcriptRepository.segmentsByIds(ids)
        if (segments.isEmpty()) return emptyList()
        val lexicalSet = lexicalIds.toHashSet()
        val cosineById = semanticHits.toMap()
        val recordings = segments.map { it.recordingId }.distinct()
            .mapNotNull { id -> recordingsRepository.getById(id)?.let { id to it } }
            .toMap()
        return segments.map { segment ->
            val recording = recordings[segment.recordingId]
            val rank = maxOf(
                if (segment.id in lexicalSet) LEXICAL_RANK else 0.0,
                cosineById[segment.id]?.toDouble() ?: 0.0,
            )
            CommunicationSearchComposer.Item(
                at = recording?.createdAt ?: 0L,
                channel = CHANNEL_TRANSCRIPT,
                title = recording?.displayName?.takeIf { it.isNotBlank() }
                    ?: "запись #${segment.recordingId}",
                text = segment.text,
                ref = "recordings #${segment.recordingId} [" +
                    "${formatTimecode(segment.startMs)}-${formatTimecode(segment.endMs)}]",
                rank = rank,
            )
        }
    }

    /**
     * Semantic transcript hits: embed [query] with the bundled offline model, cosine-rank it against
     * a BOUNDED pool of already-stored segment vectors and return `(segmentId, cosine)` best first.
     * Best-effort by construction: whenever semantics are disabled/unavailable, the query embedding
     * times out, or no indexed vectors exist, this returns an empty list and the caller keeps the
     * lexical FTS hits — no error surfaces to the tool.
     */
    private suspend fun semanticTranscriptHits(
        query: String,
        limit: Int,
    ): List<Pair<Long, Float>> {
        val embedder = localEmbedder ?: return emptyList()
        val store = settingsStore ?: return emptyList()
        return runCatching {
            val enabled = store.config.firstOrNull()?.semanticSearchEnabled == true
            if (!SemanticSearchGate.shouldEmbed(enabled = enabled, text = query)) {
                return@runCatching emptyList()
            }
            val stored = transcriptRepository.recentVectors(SEMANTIC_SCAN_LIMIT)
            if (stored.isEmpty()) return@runCatching emptyList()
            val queryVector = withTimeoutOrNull(EMBED_QUERY_TIMEOUT_MS) {
                embedder.embed(listOf(query)).getOrNull()?.firstOrNull()
            } ?: return@runCatching emptyList()
            TranscriptSemanticRanker.rank(queryVector, stored, limit)
        }.getOrDefault(emptyList())
    }

    private suspend fun interactionItems(
        query: String,
        limit: Int,
    ): List<CommunicationSearchComposer.Item> {
        val interactions = interactionRepository.search(query, limit)
        if (interactions.isEmpty()) return emptyList()
        val persons = interactions.map { it.personId }.distinct()
            .mapNotNull { id -> personRepository.get(id)?.let { id to it.displayName } }
            .toMap()
        return interactions.map { interaction ->
            val summary = listOfNotNull(
                interaction.title?.takeIf { it.isNotBlank() },
                interaction.summary?.takeIf { it.isNotBlank() },
            ).joinToString(" — ").ifBlank { "(без описания)" }
            CommunicationSearchComposer.Item(
                at = interaction.at,
                channel = interaction.channel.name,
                title = persons[interaction.personId] ?: "персона #${interaction.personId}",
                text = summary,
                ref = "interaction #${interaction.id}",
            )
        }
    }

    /** Best-effort: a memory failure must never fail the whole search. */
    private suspend fun memoryItems(
        query: String,
        limit: Int,
    ): List<CommunicationSearchComposer.Item> = runCatching {
        assistRepository.searchMemoriesRanked(query, limit)
            .filter { it.lexicallyMatched }
            .map { hit ->
                CommunicationSearchComposer.Item(
                    at = hit.note.createdAt,
                    channel = CHANNEL_MEMORY,
                    title = "",
                    text = hit.note.text,
                    ref = "memory #${hit.note.id}",
                )
            }
    }.getOrDefault(emptyList())

    /**
     * Hybrid stored-message hits (full SMS / captured messenger bodies) via [MessageSearch]. The
     * channel label is the persisted source type (`SMS`, `MESSENGER`, …); the relevance hint is the
     * semantic cosine (0.0 for lexical-only hits, so those fall back to newest-first). Best-effort:
     * when the binding is absent or the search fails, this returns nothing and the other channels
     * still answer.
     */
    private suspend fun messageItems(
        query: String,
        limit: Int,
    ): List<CommunicationSearchComposer.Item> {
        val search = messageSearch ?: return emptyList()
        return runCatching {
            search.search(query = query, limit = limit, semantic = true).map { hit ->
                CommunicationSearchComposer.Item(
                    at = hit.at,
                    channel = hit.sourceType,
                    title = hit.sender?.takeIf { it.isNotBlank() }
                        ?: hit.conversationKey?.takeIf { it.isNotBlank() }.orEmpty(),
                    text = hit.text,
                    ref = "message #${hit.messageId}",
                    rank = hit.score,
                )
            }
        }.getOrDefault(emptyList())
    }
}
