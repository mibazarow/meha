package com.andharness.ru.data.tools.communications

import com.andharness.ru.data.tools.formatTimestamp
import com.andharness.ru.data.tools.person.PersonReadSupport
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.formatting.collapseWhitespace
import com.andharness.ru.domain.messages.MessageSearch
import com.andharness.ru.domain.messages.MessageSearchHit
import com.andharness.ru.domain.messages.MessageSearchNormalization
import com.andharness.ru.domain.person.PersonResolver
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

private const val DEFAULT_LIMIT = 20
private const val MAX_LIMIT = 100

/** Maximum characters of a message body rendered per hit; the service keeps the full text. */
private const val SNIPPET_CHARS = 300

/**
 * `message_search`: hybrid (lexical FTS4 + meaning-based vector) search over the FULL text of
 * captured messages — SMS/MMS and messenger/email/chat messages captured from notifications.
 *
 * Honest and bounded:
 * - an empty result is reported as such, never padded;
 * - `source` filters to one persisted source type (sms|mms|messenger|email|chat|all);
 * - `person` is resolved the same «never guess» way as the person tools — an ambiguous or unknown
 *   name returns the candidate list and changes nothing;
 * - `semantic=false` searches lexically only, and semantics silently degrade to lexical when the
 *   on-device embedder is off/unavailable;
 * - each hit's body is cut to [SNIPPET_CHARS] characters for the reply.
 */
internal class MessageSearchToolHandler(
    private val messageSearch: MessageSearch,
    private val readSupport: PersonReadSupport,
    private val args: CommunicationsToolArgs,
) : ToolHandler {

    override val name = "message_search"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val query = args.string(arguments, "query")
                ?: return@withContext ToolResult(false, "Укажите query для поиска по сообщениям.")
            val limit = args.int(arguments, "limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
            val source = MessageSearchNormalization.normalizeSource(args.string(arguments, "source"))
            val semantic = args.boolOrNull(arguments, "semantic") ?: true

            var personId: Long? = null
            val personRaw = args.string(arguments, "person")
            if (personRaw != null) {
                val numeric = personRaw.toLongOrNull()
                if (numeric != null) {
                    personId = numeric
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
                }
            }

            val hits = messageSearch.search(
                query = query,
                limit = limit,
                source = source,
                personId = personId,
                semantic = semantic,
            )
            ToolResult(true, render(query, source, personId, semantic, hits))
        } catch (e: Exception) {
            ToolResult(false, "Ошибка поиска по сообщениям: ${e.message}")
        }
    }

    private fun render(
        query: String,
        source: String?,
        personId: Long?,
        semantic: Boolean,
        hits: List<MessageSearchHit>,
    ): String = buildString {
        append("Поиск по сообщениям «")
        append(query)
        append("» — найдено: ")
        append(hits.size)
        if (source != null) append(" (источник: ").append(source).append(')')
        if (personId != null) append(" (person_id=").append(personId).append(')')
        append(" (")
        append(if (semantic) "лексический + семантический" else "только лексический")
        appendLine("):")
        if (hits.isEmpty()) {
            append("Ничего не найдено.")
        } else {
            hits.forEach { hit ->
                append("- [")
                append(formatTimestamp(hit.at))
                append("] ")
                append(hit.sourceType)
                hit.sender?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                hit.conversationKey?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                append(": ")
                append(snippet(hit.text))
                append(" (message #").append(hit.messageId).append(')')
                appendLine()
            }
        }
        append(
            "\nЧестно: ищутся только уже сохранённые сообщения (тексты SMS/MMS и перехваченные " +
                "сообщения мессенджеров/почты). Семантика ищет по смыслу среди уже " +
                "проиндексированных сообщений и при выключенных эмбеддингах честно откатывается к " +
                "лексическому поиску; результаты ограничены запрошенным limit."
        )
    }

    private fun snippet(text: String): String {
        val collapsed = collapseWhitespace(text)
        if (collapsed.length <= SNIPPET_CHARS) return collapsed
        return collapsed.take(SNIPPET_CHARS - 1).trimEnd() + '…'
    }
}
