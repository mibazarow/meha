package com.andharness.ru.domain.agent

import android.util.Log
import com.andharness.ru.domain.assist.AssistRepository
import com.andharness.ru.domain.settings.LlmSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pre-compaction fact extraction.
 *
 * When the chat history is compacted, the oldest turns are folded into a short rolling summary and
 * dropped from the wire. A durable fact (a name, a preference, an address, an important plan) that
 * the model happened not to persist via `memory_save` would then be silently lost. This component
 * runs one best-effort, hard-bounded LLM pass over the messages that are ABOUT to be dropped and
 * saves whatever durable facts it finds into long-term memory first.
 *
 * Guarantees:
 * - **Bounded input**: only the newest [MAX_INPUT_MESSAGES] user/assistant messages of the dropped
 *   slice, each capped to [MAX_INPUT_MESSAGE_CHARS], the whole transcript capped to [MAX_INPUT_CHARS].
 * - **Bounded output**: at most [MAX_FACTS] facts, each capped to [MAX_FACT_CHARS].
 * - **Bounded time**: the completion runs under `withTimeout([EXTRACT_TIMEOUT_MS])`.
 * - **Never blocks compaction**: a missing configuration, network error, model error, timeout or
 *   parse failure only logs and returns 0. Only the CALLER's own cancellation propagates.
 *
 * References:
 * - kotlinx.coroutines `withTimeout` (throws `TimeoutCancellationException`; a foreign
 *   `CancellationException` still cancels the caller):
 *   https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/withTimeout.html
 * - In-repo LLM contract: `app/src/main/java/com/andharness/ru/domain/agent/LlmClient.kt`
 *   (`fun complete(request: LlmRequest): Flow<LlmEvent>`).
 * - In-repo memory save contract: `app/src/main/java/com/andharness/ru/domain/assist/AssistRepository.kt`
 *   (`saveMemoryConsolidated(text, tags, importance)`).
 */

/** Max durable facts accepted from one extraction pass. */
internal const val MAX_FACTS = 5

/** Max characters kept per saved fact (longer model lines are truncated, not saved raw). */
internal const val MAX_FACT_CHARS = 280

/** Max (newest) dropped messages fed to the extraction prompt. */
internal const val MAX_INPUT_MESSAGES = 20

/** Max characters per message in the extraction prompt. */
internal const val MAX_INPUT_MESSAGE_CHARS = 500

/** Hard cap of the whole extraction transcript. */
internal const val MAX_INPUT_CHARS = 6_000

/** Hard wall-clock budget for the single extraction completion. */
internal const val EXTRACT_TIMEOUT_MS = 15_000L

/**
 * Provenance tag of an automatically extracted memory. It never carries user intent, so it stays
 * distinguishable from an explicit `memory_save` (which uses the model's own tags).
 */
internal const val AUTO_TAG = "auto"

/**
 * Importance of an automatically extracted fact: 1 («средняя»). Auto-extraction is useful but is
 * a guess — it must rank BELOW a fact the user/model explicitly saved (importance 2).
 */
/**
 * Importance of an automatically extracted fact: 0 (normal). These are PROACTIVE writes the user
 * never asked for, so they are the first thing `MemoryGcWorker` may collect once untouched for
 * 180 days (BUGS.md M7). A fact the user asks to remember explicitly goes through `memory_save`,
 * which keeps its own importance.
 */
internal const val AUTO_IMPORTANCE = 0

/** Short, strict extraction instruction (Russian, per the product language rules). */
internal const val FACTS_INSTRUCTION =
    "Ниже фрагмент диалога, который скоро будет удалён из контекста. " +
        "Верни до $MAX_FACTS долговечных фактов о пользователе/его делах, по одному в строке, " +
        "без пояснений; если фактов нет — пусто."

/** System framing that keeps the extraction pass from answering or chatting. */
internal const val FACTS_SYSTEM_PROMPT =
    "Ты извлекаешь только долговечные факты (имя, предпочтения, адреса, важные люди, планы, " +
        "привычки). Отвечай только списком строк без нумерации и пояснений. " +
        "Если долговечных фактов нет — верни пустой ответ."

/**
 * Pure parser of the extraction response. Unit-testable on the JVM (no Android/Room).
 *
 * Rules, in order, per line:
 * 1. trim surrounding whitespace;
 * 2. strip ONE OR MORE leading list markers: bullets `-`/`–`/`—`/`*`/`•` and numbering `N.`/`N)`,
 *    with optional whitespace around them;
 * 3. strip surrounding quotes (`"`, `'`, `«`, `»`);
 * 4. drop blank lines and lines with no letter/digit at all (a stray `-` or punctuation);
 * 5. cap the line to [maxChars] (truncate, then trim the trailing space);
 * 6. drop duplicates case-insensitively, keeping the first occurrence;
 * 7. stop after [maxFacts] facts.
 */
internal fun parseFacts(
    raw: String,
    maxFacts: Int = MAX_FACTS,
    maxChars: Int = MAX_FACT_CHARS
): List<String> {
    if (maxFacts <= 0 || maxChars <= 0) return emptyList()
    val seen = LinkedHashSet<String>()
    val facts = ArrayList<String>(minOf(maxFacts, 8))
    for (line in raw.split('\n')) {
        var text = line.trim()
        // Strip every leading list marker ("- ", "1. ", "2) ", "- - "), never looping forever.
        while (true) {
            val stripped = text.replace(FACT_MARKER, "").trim()
            if (stripped == text) break
            text = stripped
        }
        text = text.trim('"', '\'', '«', '»').trim()
        if (text.isEmpty()) continue
        if (text.none { it.isLetterOrDigit() }) continue
        val capped = if (text.length > maxChars) text.take(maxChars).trimEnd() else text
        if (capped.isEmpty()) continue
        if (!seen.add(capped.lowercase())) continue
        facts += capped
        if (facts.size >= maxFacts) break
    }
    return facts
}

/** Leading bullet or numbering marker, anchored at the start of a (trimmed) line. */
private val FACT_MARKER = Regex("^(?:[-–—*•]+|\\d+[.)])\\s*")

/**
 * Best-effort extractor that runs one bounded LLM completion and persists the parsed facts through
 * [AssistRepository.saveMemoryConsolidated] with tag [AUTO_TAG] and importance [AUTO_IMPORTANCE].
 */
@Singleton
class FactExtractor @Inject constructor(
    private val llmClient: LlmClient,
    private val settingsStore: LlmSettingsStore,
    private val assistRepository: AssistRepository
) {

    /**
     * Extracts durable facts from [dropped] (the messages about to leave the context) and saves
     * them to long-term memory. Returns how many facts were actually persisted.
     *
     * Best-effort: returns 0 when the LLM is not configured, the input has no usable text, or any
     * LLM/parse/save step fails. A cancellation of the CALLER is always rethrown so the agent turn
     * can still be stopped.
     */
    suspend fun extractAndSave(dropped: List<LlmMessage>): Int {
        if (dropped.isEmpty()) return 0
        // Skip entirely when no model is configured — never attempt a doomed network call.
        val configured = runCatching { settingsStore.isConfigured().first() }.getOrDefault(false)
        if (!configured) return 0

        val transcript = buildTranscript(dropped)
        if (transcript.isBlank()) return 0

        val raw = try {
            collectCompletion(transcript)
        } catch (timeout: TimeoutCancellationException) {
            Log.w(TAG, "Авто-извлечение фактов: превышен лимит ${EXTRACT_TIMEOUT_MS / 1000}s — пропущено.")
            return 0
        } catch (cancellation: CancellationException) {
            // Never swallow the caller's cancellation: compaction must stay cancellable.
            throw cancellation
        } catch (e: Exception) {
            Log.w(TAG, "Авто-извлечение фактов перед сжатием не удалось: ${e.message}")
            return 0
        }

        val facts = runCatching { parseFacts(raw) }.getOrDefault(emptyList())
        if (facts.isEmpty()) return 0

        var saved = 0
        for (fact in facts) {
            val result = runCatching {
                assistRepository.saveMemoryConsolidated(
                    text = fact,
                    tags = AUTO_TAG,
                    importance = AUTO_IMPORTANCE
                )
            }
            // A rejected fact (filler / narration) never reached the table: it is not "saved".
            if (result.getOrNull()?.rejected == false) {
                saved++
            } else if (result.isSuccess) {
                Log.d(TAG, "Авто-факт отброшен фильтром мусора.")
            } else {
                Log.w(TAG, "Авто-факт не сохранён: ${result.exceptionOrNull()?.message}")
            }
        }
        if (saved > 0) {
            Log.i(TAG, "Авто-извлечение перед сжатием: сохранено $saved факт(ов).")
        }
        return saved
    }

    /**
     * Runs the single extraction completion under the hard [EXTRACT_TIMEOUT_MS] budget and returns
     * the concatenated text deltas. An [LlmEvent.Error] is turned into an exception so the caller's
     * failure path (log + 0) applies uniformly.
     */
    private suspend fun collectCompletion(transcript: String): String = withTimeout(EXTRACT_TIMEOUT_MS) {
        val text = StringBuilder()
        llmClient.complete(
            LlmRequest(
                messages = listOf(
                    LlmMessage("system", FACTS_SYSTEM_PROMPT),
                    LlmMessage("user", "$FACTS_INSTRUCTION\n\n$transcript")
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

    /**
     * Bounded plain-text transcript of the dropped slice: user/assistant messages only, the newest
     * [MAX_INPUT_MESSAGES], each capped to [MAX_INPUT_MESSAGE_CHARS], the whole text capped to
     * [MAX_INPUT_CHARS] keeping the newest tail.
     */
    private fun buildTranscript(dropped: List<LlmMessage>): String {
        val tail = dropped.asSequence()
            .filter { it.role == "user" || it.role == "assistant" }
            .mapNotNull { message ->
                val content = message.content?.trim()
                if (content.isNullOrBlank()) null else message.role to content
            }
            .toList()
            .takeLast(MAX_INPUT_MESSAGES)
        val joined = tail.joinToString("\n") { (role, content) ->
            val speaker = if (role == "user") "Пользователь" else "Агент"
            val bounded = if (content.length > MAX_INPUT_MESSAGE_CHARS) {
                content.take(MAX_INPUT_MESSAGE_CHARS) + "…"
            } else {
                content
            }
            "$speaker: $bounded"
        }
        return if (joined.length > MAX_INPUT_CHARS) joined.takeLast(MAX_INPUT_CHARS) else joined
    }

    private companion object {
        const val TAG = "Andharness"
    }
}
