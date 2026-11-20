package com.andharness.ru.data.agent

import android.util.Log
import com.andharness.ru.data.style.StyleProfileStore
import com.andharness.ru.domain.agent.LlmClient
import com.andharness.ru.domain.agent.LlmEvent
import com.andharness.ru.domain.agent.LlmMessage
import com.andharness.ru.domain.agent.LlmRequest
import com.andharness.ru.domain.settings.LlmSettingsStore
import com.andharness.ru.domain.style.ProfanityLexicon
import com.andharness.ru.domain.style.StyleProfile
import com.andharness.ru.domain.style.StyleMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/** Minimum observed user messages before one LLM pass is worth its cost. */
internal const val STYLE_MIN_SAMPLES = 20

/** Do not spend a model call on the style card more often than this. */
internal const val STYLE_CARD_COOLDOWN_MS = 6 * 60 * 60 * 1000L

/** Hard wall-clock budget for the single style-card completion. */
internal const val STYLE_EXTRACT_TIMEOUT_MS = 12_000L

internal const val STYLE_MAX_INPUT_MESSAGES = 20
internal const val STYLE_MAX_INPUT_MESSAGE_CHARS = 300
internal const val STYLE_MAX_INPUT_CHARS = 4_000

/** Instructions for the style-card pass: manner only, never content or personality judgments. */
internal const val STYLE_SYSTEM_PROMPT =
    "Ты описываешь ТОЛЬКО манеру речи пользователя по его собственным сообщениям. " +
        "Не пересказывай темы, факты и содержание; не оценивай личность, эмоции или психику. " +
        "Верни ОДНУ строку: 1–3 короткие черты через запятую, без кавычек, нумерации и пояснений. " +
        "Пример: коротко, на ты, с лёгкой иронией, без эмодзи. Если данных мало — пустую строку."

internal const val STYLE_INSTRUCTION = "Сообщения пользователя (только его реплики):"

/**
 * Best-effort LLM pass that turns a bounded sample of the user's own messages into a short
 * natural-language style card (humor, irony, anglicisms — nuances counters cannot infer).
 *
 * Guarantees:
 * - **Bounded input/output/time**: at most [STYLE_MAX_INPUT_MESSAGES] user messages, each capped,
 *   one card of at most `StyleProfileRules.MAX_CARD_CHARS`, one completion under
 *   [STYLE_EXTRACT_TIMEOUT_MS].
 * - **Cheap**: skipped below [STYLE_MIN_SAMPLES] total samples, while [StyleMode.NEUTRAL] is chosen,
 *   and for [STYLE_CARD_COOLDOWN_MS] after the previous card.
 * - **Never blocks compaction**: every failure logs and returns false; only the caller's
 *   cancellation propagates.
 * - **Guardrailed**: a card containing an insult or slur is dropped (`ProfanityLexicon`), and the
 *   card is capped/trimmed by the store.
 *
 * References: in-repo [LlmClient] contract (`complete(request): Flow<LlmEvent>`), kotlinx.coroutines
 * `withTimeout` (https://kotlinlang.org/api/kotlinx-coroutines/kotlinx-coroutines-core/kotlinx.coroutines/withTimeout.html).
 */
@Singleton
class StyleCardExtractor @Inject constructor(
    private val llmClient: LlmClient,
    private val settingsStore: LlmSettingsStore,
    private val styleProfileStore: StyleProfileStore? = null,
) {

    private val tag = "Andharness"

    /**
     * Updates the stored card when due. Returns true only when a new card was persisted.
     * [dropped] is the slice about to leave the context; only its user messages are used.
     */
    suspend fun updateIfDue(
        dropped: List<LlmMessage>,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val store = styleProfileStore ?: return false
        if (!shouldRun(store.current(), now)) return false
        val configured = runCatching { settingsStore.isConfigured().first() }.getOrDefault(false)
        if (!configured) return false

        val transcript = buildTranscript(dropped)
        if (transcript.isBlank()) return false

        val raw = try {
            collectCompletion(transcript)
        } catch (timeout: TimeoutCancellationException) {
            Log.w(tag, "Стиль-карточка: превышен лимит ${STYLE_EXTRACT_TIMEOUT_MS / 1000}s — пропущено.")
            return false
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Log.w(tag, "Стиль-карточка не получена: ${e.message}")
            return false
        }

        val card = parseStyleCard(raw) ?: return false
        return store.setCard(card, now)
    }

    /** Pure gate: enough evidence, not neutral, and outside the cooldown. */
    internal fun shouldRun(profile: StyleProfile, now: Long): Boolean {
        if (profile.mode == StyleMode.NEUTRAL) return false
        if (profile.samples < STYLE_MIN_SAMPLES) return false
        if (profile.card == null) return true
        return now - profile.cardUpdatedAt >= STYLE_CARD_COOLDOWN_MS
    }

    private suspend fun collectCompletion(transcript: String): String =
        withTimeout(STYLE_EXTRACT_TIMEOUT_MS) {
            val text = StringBuilder()
            llmClient.complete(
                LlmRequest(
                    messages = listOf(
                        LlmMessage("system", STYLE_SYSTEM_PROMPT),
                        LlmMessage("user", "$STYLE_INSTRUCTION\n\n$transcript"),
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

    /** Bounded transcript of the user's own messages, newest tail, no assistant text. */
    internal fun buildTranscript(dropped: List<LlmMessage>): String {
        val joined = dropped.asSequence()
            .filter { it.role == "user" }
            .mapNotNull { it.content?.trim()?.takeIf { text -> text.isNotBlank() } }
            .toList()
            .takeLast(STYLE_MAX_INPUT_MESSAGES)
            .joinToString("\n") { text ->
                if (text.length > STYLE_MAX_INPUT_MESSAGE_CHARS) {
                    text.take(STYLE_MAX_INPUT_MESSAGE_CHARS) + "…"
                } else {
                    text
                }
            }
        return if (joined.length > STYLE_MAX_INPUT_CHARS) joined.takeLast(STYLE_MAX_INPUT_CHARS) else joined
    }
}

/**
 * Parses one model line into a safe card: strips bullets/numbering/quotes, collapses whitespace,
 * keeps the first line, caps length and drops anything hostile. `null` means "no usable card".
 */
internal fun parseStyleCard(raw: String, maxChars: Int = 200): String? {
    if (maxChars <= 0) return null
    val line = raw.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }
        ?: return null
    var stripped = line
        .replace(Regex("^(?:[-–—*•]+|\\d+[.)])\\s*"), "")
        .trim()
        .replace(Regex("\\s+"), " ")
    val quoteChars = charArrayOf('"', '\'', '«', '»')
    while (stripped.isNotEmpty() && (stripped.first() in quoteChars || stripped.first() in ". ,;:")) {
        stripped = stripped.drop(1).trimStart()
    }
    while (stripped.isNotEmpty() && (stripped.last() in quoteChars || stripped.last() in ". ,;:")) {
        stripped = stripped.dropLast(1).trimEnd()
    }
    if (stripped.isEmpty()) return null
    if (ProfanityLexicon.containsHardLanguage(stripped)) return null
    val capped = if (stripped.length <= maxChars) stripped else stripped.take(maxChars).trim()
    return capped.ifBlank { null }
}
