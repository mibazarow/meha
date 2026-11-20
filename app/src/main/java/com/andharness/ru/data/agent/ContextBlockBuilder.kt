package com.andharness.ru.data.agent

import com.andharness.ru.data.db.dao.MemoryPersonDao
import com.andharness.ru.data.db.entity.MessageEntity
import com.andharness.ru.data.db.entity.NoteEntity
import com.andharness.ru.data.mcp.McpServerStore
import com.andharness.ru.data.objective.ObjectiveStore
import com.andharness.ru.data.profile.GlobalProfileStore
import com.andharness.ru.data.style.StyleProfileStore
import com.andharness.ru.data.trust.TrustedSourceBlock
import com.andharness.ru.data.trust.TrustedSourceStore
import com.andharness.ru.domain.agent.CurrentTimeContext
import com.andharness.ru.domain.agent.RecallIntent
import com.andharness.ru.domain.assist.AssistRepository
import com.andharness.ru.domain.common.TimeProvider
import com.andharness.ru.domain.device.DeviceInfoProvider
import com.andharness.ru.domain.memory.DeviceFactRepository
import com.andharness.ru.domain.memory.DeviceFactSummary
import com.andharness.ru.domain.person.InteractionRepository
import com.andharness.ru.domain.person.PersonContextBlock
import com.andharness.ru.domain.person.PersonRepository

/**
 * How many persons/interactions are scanned to build the personal-base context block. The renderer
 * itself applies the hard caps ([PersonContextBlock]); this only bounds the database read.
 */
private const val PERSON_BLOCK_SCAN = 30

/** Max non-blank dialogue messages folded into the automatic lexical-recall query (tail). */
private const val RECALL_QUERY_MESSAGES = 3

/**
 * Hard cap of the automatic recall query, keeping the NEWEST tail. Without it a single huge turn
 * would dominate lexical recall; with it the request plus the assistant's last reply always survive.
 */
private const val RECALL_QUERY_CHARS = 400

/** How many ranked memories are requested for automatic injection (only lexical matches are used). */
private const val MEMORY_RECALL_LIMIT = 8

/** Per-memory caps of the injected block, so [MEMORY_RECALL_LIMIT] memories still stay compact. */
private const val MEMORY_TEXT_CHARS = 160
private const val MEMORY_TAGS_CHARS = 80

/** Max distinct people surfaced as "related people" next to the injected memories. */
private const val RELATED_PEOPLE_LIMIT = 8

/**
 * Builds the automatic lexical-recall query from the newest [RECALL_QUERY_MESSAGES] non-blank
 * dialogue messages (user + assistant), mirroring the tool-RAG tail: the latest user request plus
 * the assistant's last reply, so a follow-up such as «а что я решил?» still carries the earlier
 * topic. Blank/tool messages are ignored; the result is capped to [RECALL_QUERY_CHARS] characters
 * keeping the newest tail. Pure (no Android/Room) so it is unit-testable on the JVM.
 */
internal fun buildRecallQuery(messages: List<MessageEntity>): String {
    val tail = messages.asSequence()
        .filter { it.role == "user" || it.role == "assistant" }
        .map { it.content.trim() }
        .filter { it.isNotBlank() }
        .toList()
        .takeLast(RECALL_QUERY_MESSAGES)
    if (tail.isEmpty()) return ""
    val joined = tail.joinToString("\n")
    return if (joined.length <= RECALL_QUERY_CHARS) {
        joined
    } else {
        joined.takeLast(RECALL_QUERY_CHARS).trim()
    }
}

/**
 * Renders one injected memory with its provenance — id, importance and tags — so the model can cite
 * and manage it (memory_save/memory_search) instead of seeing anonymous text. Bounded per item so
 * the whole block stays compact. Pure (no Android/Room) so it is unit-testable on the JVM.
 *
 * The body is cut on a sentence boundary, never mid-word (MEMORY_TASKS §3): a memory truncated to
 * «…потому что он не» reads like a fact nobody ever stated. Tags are never cut — a truncated tag is
 * a tag the model cannot search by — so an oversized tag list is omitted instead.
 */
internal fun formatMemoryLine(note: NoteEntity): String {
    val text = trimToSentence(note.text.trim(), MEMORY_TEXT_CHARS)
    val tags = note.tags.trim()
    val tagsPart = if (tags.isEmpty() || tags.length > MEMORY_TAGS_CHARS) "" else ", теги: $tags"
    return "- [${note.id}] $text (важность ${note.importance}$tagsPart)"
}

/**
 * Truncates [text] to at most [max] characters without splitting a word: the cut lands after the
 * last sentence terminator inside the limit when there is one reasonably far in, otherwise after the
 * last space. The ellipsis is added only when something was actually cut. Pure.
 */
internal fun trimToSentence(text: String, max: Int): String {
    if (text.length <= max) return text
    val window = text.substring(0, max)
    val sentenceEnd = window.indexOfLast { it == '.' || it == '!' || it == '?' }
    val cut = when {
        sentenceEnd >= max / 2 -> sentenceEnd + 1
        else -> window.lastIndexOf(' ').takeIf { it > 0 } ?: max
    }
    return text.substring(0, cut).trimEnd() + "…"
}

/**
 * Tell the model WHICH chat this turn belongs to, so «переименуй этот чат»/«покажи текущий чат»
 * resolve to the right id instead of the agent asking which chat is meant. Rendered only when a chat
 * id is present; [chatId] is the id of the run's chat. Pure (no Android/Room) so it is unit-testable.
 */
internal fun renderCurrentChatBlock(chatId: Long?): String =
    if (chatId == null) {
        ""
    } else {
        "ТЕКУЩИЙ ЧАТ: id=$chatId. Фразы «этот чат»/«текущий чат» относятся к нему."
    }

/**
 * Builds every non-history block injected into the system prompt: automatic lexical-recall memory,
 * the device summary, the fresh device-fact cache, the personal base, the trusted-source allowlist,
 * the shared profile, the current chat id and the current-time block. Each block is produced in the
 * exact order and with the exact rendering the prompt contract expects; best-effort blocks never throw.
 */
internal class ContextBlockBuilder(
    private val assistRepository: AssistRepository,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val deviceFactRepository: DeviceFactRepository,
    private val personRepository: PersonRepository,
    private val interactionRepository: InteractionRepository,
    private val timeProvider: TimeProvider,
    private val memoryPersonDao: MemoryPersonDao?,
    private val trustedSourceStore: TrustedSourceStore?,
    private val globalProfileStore: GlobalProfileStore?,
    private val mcpServerStore: McpServerStore?,
    private val objectiveStore: ObjectiveStore?,
    private val styleProfileStore: StyleProfileStore?
) {

    /** The assembled blocks, consumed by [SystemPromptBuilder] / the local prompt builder. */
    data class Blocks(
        val memoryBlock: String,
        val deviceSummary: String,
        val deviceFactsBlock: String,
        val peopleBlock: String,
        val trustedSourcesBlock: String,
        val profileBlock: String,
        val mcpBlock: String,
        val objectiveBlock: String,
        val currentChatBlock: String,
        val nowBlock: String,
        /**
         * Deterministic nudge for turns that ask about the past (see [RecallIntent]); empty when the
         * turn is not a recall question. Rendered into the runtime tail, never persisted.
         */
        val recallHintBlock: String,
        /**
         * Learned communication style (form only); empty when there is not enough evidence, when
         * the user chose NEUTRAL, or when no store is wired. Rendered into the runtime tail.
         */
        val styleBlock: String = ""
    )

    /** Builds all blocks from the full (non-tool) message history, in the original evaluation order. */
    suspend fun build(allMessages: List<MessageEntity>, chatId: Long?): Blocks {
        // Automatic lexical recall (no embeddings): pull memories relevant to the recent dialogue
        // and inject them into the system prompt so the agent "remembers" across sessions. The query
        // spans the last few turns (not only the newest user message), so a follow-up such as
        // «напомни об этом» still carries the earlier topic.
        val recallQuery = buildRecallQuery(allMessages)
        val memoryHits =
            if (recallQuery.isBlank()) emptyList()
            else assistRepository.searchMemoriesRanked(recallQuery, limit = MEMORY_RECALL_LIMIT)
                .filter { it.lexicallyMatched }
        val memoryBlock = if (memoryHits.isEmpty()) {
            ""
        } else {
            buildString {
                append(memoryHits.joinToString("\n") { formatMemoryLine(it.note) })
                val people = relatedPeople(memoryHits.map { it.note.id })
                if (people.isNotEmpty()) {
                    append("\nСвязанные люди: ")
                    append(people.joinToString(", "))
                }
            }
        }

        val deviceSummary = deviceInfoProvider.summary()

        // Device-fact cache (TZ §12.3): a compact block rendered only from facts that are still
        // fresh. Anything stale is intentionally omitted — the agent is told to call
        // device_facts_refresh when it needs exact data instead of trusting an outdated value.
        val deviceFactsBlock = runCatching {
            DeviceFactSummary.render(deviceFactRepository.all(), System.currentTimeMillis())
        }.getOrDefault("")

        // Personal-base block (update.md §4.6): a compact, always-present «ЛЮДИ / ПОСЛЕДНИЕ
        // ВЗАИМОДЕЙСТВИЯ» view so the agent consults the base instead of re-scanning the phone.
        // Hard-capped by PersonContextBlock; an empty base renders "" and the block is omitted.
        val peopleBlock = personContextBlock()

        // Trusted-source allowlist (prompt-injection defense): a compact, always-present line so the
        // agent checks the source BEFORE acting on any instruction found in external text. Empty
        // list still renders «ДОВЕРЕННЫЕ ИСТОЧНИКИ: нет», so the rule always has a concrete anchor.
        val trustedSourcesBlock = runCatching {
            trustedSourceStore?.renderBlock() ?: TrustedSourceBlock.render(emptyList())
        }.getOrDefault(TrustedSourceBlock.render(emptyList()))

        // Shared cross-chat PROFILE: facts the user explicitly saved (name, preferences, recurring
        // context). It is injected into EVERY chat (not recall-gated) because it is small, bounded
        // and deliberately explicit. An empty profile renders "" and the block is omitted entirely.
        val profileBlock = runCatching {
            globalProfileStore?.renderBlock().orEmpty()
        }.getOrDefault("")

        // Learned communication style (form only). Kept in the runtime tail so the byte-stable
        // static system prompt is untouched; an empty profile renders "" and injects nothing.
        val styleBlock = runCatching {
            styleProfileStore?.current()?.let { StyleBlockBuilder.render(it) }.orEmpty()
        }.getOrDefault("")

        // MCP servers configured from chat: a compact, always-present line (when the store is wired)
        // so the agent knows it can add/list/test/remove external MCP servers from chat and that
        // their tools appear after a successful connection. `null` (e.g. in tests) renders nothing.
        val mcpBlock = runCatching {
            mcpServerStore?.renderBlock().orEmpty()
        }.getOrDefault("")

        // Long-running objective (checklist §1): the ACTIVE objective bound to THIS chat is injected
        // as a compact resume block so the agent can continue it across turns and after a process
        // restart. No objective for the chat → "" and the block is omitted (unrelated chats untouched).
        // PAUSED/DONE/CANCELLED objectives are never injected — a pause is only resumed explicitly.
        val objectiveBlock = runCatching {
            objectiveStore?.renderBlock(chatId).orEmpty()
        }.getOrDefault("")

        // The chat id of THIS run, so the model can resolve «этот чат» / «текущий чат» itself and
        // pass the id to the chat_* tools (which also default to it when omitted). Only rendered
        // when an id is present; unrelated (null-chat) compiles are unaffected.
        val currentChatBlock = renderCurrentChatBlock(chatId)

        // Bug #1: the agent has no clock of its own, so the current local date/time/day-of-week and
        // timezone are rendered FRESH here, on EVERY compile (never cached across the session). This
        // is what makes "который час" and time-relative tool calls (alarm/timer/schedule) correct.
        val nowBlock = CurrentTimeContext.render(
            timeProvider.nowMillis(),
            timeProvider.zoneId(),
            timeProvider.locale()
        )

        // Deterministic recall nudge: a question about the past ("что мы решили…", "кто писал…")
        // makes the model call the meaning-based recall tools BEFORE answering, instead of
        // relying on the visible context or a literal substring search.
        val recallHintBlock = RecallIntent.hintFor(
            allMessages.lastOrNull { it.role == "user" && it.content.isNotBlank() }
                ?.content
                .orEmpty()
        ).orEmpty()

        return Blocks(
            memoryBlock = memoryBlock,
            deviceSummary = deviceSummary,
            deviceFactsBlock = deviceFactsBlock,
            peopleBlock = peopleBlock,
            trustedSourcesBlock = trustedSourcesBlock,
            profileBlock = profileBlock,
            mcpBlock = mcpBlock,
            objectiveBlock = objectiveBlock,
            currentChatBlock = currentChatBlock,
            nowBlock = nowBlock,
            recallHintBlock = recallHintBlock,
            styleBlock = styleBlock
        )
    }

    /**
     * Builds the bounded personal-base block from the newest interactions and the most recently
     * touched people. Wrapped in `runCatching` so a personal-base failure can never break the
     * prompt; an empty base yields "" and the callers omit the block.
     */
    private suspend fun personContextBlock(): String = runCatching {
        val ids = LinkedHashSet<Long>()
        interactionRepository.recentOverall(PERSON_BLOCK_SCAN).forEach { ids += it.personId }
        personRepository.recent(PERSON_BLOCK_SCAN).forEach { ids += it.id }
        val entries = ids.take(PERSON_BLOCK_SCAN).mapNotNull { id ->
            val person = personRepository.get(id) ?: return@mapNotNull null
            PersonContextBlock.Entry(
                person = person,
                interactions = interactionRepository.recentFor(
                    id,
                    PersonContextBlock.MAX_INTERACTIONS_PER_PERSON
                )
            )
        }
        PersonContextBlock.render(entries)
    }.getOrDefault("")

    /**
     * Имена людей, связанных с отданными в контекст воспоминаниями (граф `memory ↔ person`), чтобы
     * агент мог назвать собеседника, даже если в тексте памяти имени нет. Ограничено и best-effort:
     * при `null`-DAO или ошибке — пустой список, контекст не ломается.
     */
    private suspend fun relatedPeople(noteIds: List<Long>): List<String> {
        val dao = memoryPersonDao ?: return emptyList()
        if (noteIds.isEmpty()) return emptyList()
        return runCatching {
            dao.personIdsForNotes(noteIds)
                .take(RELATED_PEOPLE_LIMIT)
                .mapNotNull { personRepository.get(it)?.displayName?.trim()?.takeIf { name -> name.isNotBlank() } }
                .distinct()
        }.getOrDefault(emptyList())
    }
}
