package com.andharness.ru.data.tools.communications

import com.andharness.ru.data.tools.person.PersonReadSupport
import com.andharness.ru.domain.agent.LlmClient
import com.andharness.ru.domain.agent.LlmEvent
import com.andharness.ru.domain.agent.LlmMessage
import com.andharness.ru.domain.agent.LlmRequest
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.assist.AssistRepository
import com.andharness.ru.domain.messages.MessageSearch
import com.andharness.ru.domain.messages.MessageSearchNormalization
import com.andharness.ru.domain.messages.StoredMessagesRepository
import com.andharness.ru.domain.person.PersonResolver
import com.andharness.ru.domain.settings.LlmSettingsStore
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.util.TimeZone

/** Honest "no model configured" message: a summary is impossible without an LLM. */
private const val SUMMARY_NOT_CONFIGURED =
    "Модель не настроена: задай endpoint, модель и ключ в настройках — без этого резюме невозможно."

/** Honest empty-answer message. */
private const val SUMMARY_EMPTY_ANSWER = "Модель вернула пустой ответ — резюме не получено."

/**
 * `messages_summarize`: finds relevant stored SMS/MMS and captured messenger/email/chat messages and
 * asks the user's OWN configured LLM to summarise what was discussed — e.g. "что писали с Авито про
 * телефон" or "о чём договорились с <человеком>".
 *
 * Candidate gathering is honest and bounded:
 * - with a `topic`, the hybrid `message_search` (lexical + semantic) proposes the hits;
 * - without a topic, the newest stored messages (`recent`) — optionally restricted to a `person`
 *   and/or `source` — are used;
 * - the `window` (today|7d|30d|all, default 30d) is applied to both paths, `limit` (default 40) and a
 *   [SUMMARY_MAX_CHARS] character budget bound the prompt;
 * - `person` is resolved the same «never guess» way as the person tools: an ambiguous/unknown name
 *   returns the candidate list and nothing is summarised.
 *
 * Failure handling: an empty candidate set, an unconfigured model, a timeout, an LLM error and an
 * empty model answer all produce an honest error — content is never fabricated. With `save=true` the
 * summary is additionally stored as a memory note tagged [SUMMARY_TAG] (linked to `person:<id>` when
 * a person is given).
 *
 * All pure logic (window parsing, selection/truncation, prompt and rendering) lives in
 * `MessagesSummarizeSupport.kt` and is unit-tested on the JVM. This class only does the I/O.
 *
 * References:
 * - In-repo LLM contract: `domain/agent/LlmClient.kt` (`fun complete(request): Flow<LlmEvent>`), the
 *   same single-completion pattern as `chat_recall` (`data/tools/misc/ChatReadToolsHandler.kt`).
 * - Room-backed reads: `domain/messages/StoredMessagesRepository.kt` —
 *   https://developer.android.com/training/data-storage/room/accessing-data
 * - kotlinx.coroutines `withTimeout` —
 *   https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-timeout.html
 */
internal class MessagesSummarizeToolHandler(
    private val messageSearch: MessageSearch,
    private val storedMessagesRepository: StoredMessagesRepository,
    private val readSupport: PersonReadSupport,
    private val assistRepository: AssistRepository,
    private val llmClient: LlmClient,
    private val settingsStore: LlmSettingsStore?,
    private val args: CommunicationsToolArgs,
) : ToolHandler {

    override val name = "messages_summarize"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val window = args.string(arguments, "window") ?: DEFAULT_SUMMARY_WINDOW
            val since = parseWindowSinceMillis(window, now, zoneOffsetMs(now))
                ?: return@withContext ToolResult(
                    false,
                    "Некорректное окно «$window»: укажи today, 7d, 30d или all.",
                )
            val topic = args.string(arguments, "topic")
            val source = MessageSearchNormalization.normalizeSource(args.string(arguments, "source"))
            val limit = clampSummaryLimit(args.string(arguments, "limit")?.toIntOrNull())
            val save = args.boolOrNull(arguments, "save") ?: false

            var personId: Long? = null
            var personLabel: String? = null
            val personRaw = args.string(arguments, "person")
            if (personRaw != null) {
                val numeric = personRaw.toLongOrNull()
                if (numeric != null) {
                    personId = numeric
                    personLabel = "#$numeric"
                } else {
                    val resolved = readSupport.resolveQuery(personRaw, null)
                    val best = resolved.best
                    if (resolved.outcome != PersonResolver.ResolveOutcome.RESOLVED || best == null) {
                        return@withContext ToolResult(
                            false,
                            readSupport.formatResolve(personRaw, null, resolved),
                        )
                    }
                    personId = best.person.id
                    personLabel = best.person.displayName
                }
            }

            val candidates = gather(topic, limit, source, personId, since)
            val selection = selectSummaryLines(candidates, limit)
            if (selection.lines.isEmpty()) {
                return@withContext ToolResult(
                    false,
                    emptySummaryMessage(selection, topic, personLabel),
                )
            }

            val configured = settingsStore?.let {
                runCatching { it.isConfigured().first() }.getOrDefault(false)
            } ?: false
            if (!configured) return@withContext ToolResult(false, SUMMARY_NOT_CONFIGURED)

            val raw = try {
                collectSummary(
                    buildMessagesSummaryPrompt(selection, topic, personLabel, source, window)
                )
            } catch (timeout: TimeoutCancellationException) {
                return@withContext ToolResult(
                    false,
                    "Модель не ответила за ${SUMMARY_TIMEOUT_MS / 1000} с — резюме не получено.",
                )
            } catch (cancellation: CancellationException) {
                // Never swallow a stopped turn.
                throw cancellation
            } catch (e: Exception) {
                return@withContext ToolResult(false, "Ошибка резюмирования: ${e.message}")
            }

            val summary = raw.trim()
            if (summary.isEmpty()) {
                return@withContext ToolResult(false, SUMMARY_EMPTY_ANSWER)
            }

            val savedId = if (save) saveMemory(selection, topic, personLabel, personId, summary) else null
            ToolResult(
                true,
                formatMessagesSummary(selection, topic, personLabel, source, window, summary, savedId),
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка резюмирования сообщений: ${e.message}")
        }
    }

    /**
     * Candidate messages inside the window: hybrid `message_search` when [topic] is present, else the
     * newest stored messages filtered by [source]/[personId]. Both paths are bounded by [limit].
     */
    private suspend fun gather(
        topic: String?,
        limit: Int,
        source: String?,
        personId: Long?,
        since: Long,
    ): List<SummaryMessage> {
        val candidates = if (topic != null) {
            messageSearch.search(
                query = topic,
                limit = limit,
                source = source,
                personId = personId,
                semantic = true,
            ).map { SummaryMessage(it.at, it.sender, it.sourceType, it.text) }
        } else {
            storedMessagesRepository.recent(limit = limit, sourceType = source, personId = personId)
                .map { SummaryMessage(it.at, it.sender, it.sourceType, it.text) }
        }
        return candidates.filter { it.at >= since }
    }

    /** Stores the summary as a memory note; a write failure degrades to no saved id, never an error. */
    private suspend fun saveMemory(
        selection: SummarySelection,
        topic: String?,
        personLabel: String?,
        personId: Long?,
        summary: String,
    ): Long? {
        val tags = buildList {
            add(SUMMARY_TAG)
            topic?.let { add(it) }
            personId?.let { add("person:$it") }
        }.joinToString(",")
        val text = buildString {
            append("Резюме сообщений")
            if (topic != null) append(" по теме «").append(topic).append('»')
            if (personLabel != null) append(" с ").append(personLabel)
            append(": ").append(summary.trim())
            if (selection.droppedForLength > 0 || selection.skippedEmpty > 0) {
                append(" (учтено сообщений: ${selection.lines.size})")
            }
        }
        return runCatching { assistRepository.saveMemory(text, tags, importance = 1) }
            .getOrNull()
            ?.takeIf { it > 0L }
    }

    /**
     * Runs the SINGLE summarisation completion under the hard [SUMMARY_TIMEOUT_MS] budget. A
     * `LlmEvent.Error` becomes an exception so the caller's failure path is uniform; a foreign
     * cancellation (a stopped turn) is never swallowed.
     */
    private suspend fun collectSummary(userPrompt: String): String =
        withTimeout(SUMMARY_TIMEOUT_MS) {
            val text = StringBuilder()
            llmClient.complete(
                LlmRequest(
                    messages = listOf(
                        LlmMessage("system", MESSAGES_SUMMARY_SYSTEM_PROMPT),
                        LlmMessage("user", userPrompt),
                    )
                )
            ).collect { event ->
                when (event) {
                    is LlmEvent.Text -> text.append(event.delta)
                    is LlmEvent.Error -> throw IllegalStateException(event.message)
                    else -> Unit
                }
            }
            text.toString()
        }

    private fun zoneOffsetMs(nowMs: Long): Long = TimeZone.getDefault().getOffset(nowMs).toLong()
}
