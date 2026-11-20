package com.andharness.ru.data.tools.misc

import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.LlmClient
import com.andharness.ru.domain.agent.LlmEvent
import com.andharness.ru.domain.agent.LlmMessage
import com.andharness.ru.domain.agent.LlmRequest
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.settings.LlmSettingsStore
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject

/**
 * `translate_text` — translates text to a target language with the user's OWN configured LLM.
 * No external translation API is used: the same OpenAI-compatible client that powers the agent is
 * asked for a strict, translation-only completion.
 *
 * The prompt builder [buildTranslatePrompt], the system framing [TRANSLATE_SYSTEM_PROMPT] and the
 * response cleaner [cleanTranslation] are pure (no Android APIs), so they are unit-tested on the JVM.
 *
 * References:
 * - kotlinx.coroutines `withTimeout` (hard wall-clock budget; a foreign `CancellationException`
 *   still propagates so a stopped turn stays stoppable):
 *   https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/withTimeout.html
 * - In-repo LLM contract: `domain/agent/LlmClient.kt` (`fun complete(request): Flow<LlmEvent>`).
 * - OpenAI-compatible chat completions request/`messages`:
 *   https://platform.openai.com/docs/api-reference/chat/create
 */

/** Hard cap of the text accepted for one translation (keeps the request well inside a context window). */
internal const val MAX_TRANSLATE_INPUT_CHARS = 8_000

/** Hard wall-clock budget for the single translation completion. */
internal const val TRANSLATE_TIMEOUT_MS = 30_000L

/**
 * Strict system framing: the model is a translator, not a chat partner. It must return ONLY the
 * translation — no preamble, commentary, quotes, markdown fences or the source text.
 */
internal const val TRANSLATE_SYSTEM_PROMPT: String =
    "Ты — переводчик. Переводи текст пользователя на указанный целевой язык. " +
        "Отвечай ТОЛЬКО переводом: без пояснений, вступлений, комментариев, кавычек и " +
        "markdown-оформления. Не добавляй исходный текст и ничего не дописывай от себя. " +
        "Сохраняй смысл, имена собственные, числа, ссылки и разметку. " +
        "Если текст уже на целевом языке — верни его без изменений."

/**
 * Builds the single user message. [to] is required; [from] defaults to auto-detection; [tone] is an
 * optional style hint. Pure and unit-tested.
 */
internal fun buildTranslatePrompt(text: String, to: String, from: String?, tone: String?): String =
    buildString {
        appendLine("Целевой язык: $to")
        appendLine(
            if (from != null) "Исходный язык: $from"
            else "Исходный язык: определи автоматически"
        )
        if (tone != null) appendLine("Желаемый стиль: $tone")
        appendLine("Верни только перевод, без пояснений.")
        appendLine("Текст:")
        append(text)
    }

/** Quote pairs stripped from the outside of a model answer, longest/most specific first. */
private val QUOTE_PAIRS = listOf(
    "\u00ab" to "\u00bb", // « »
    "\u201e" to "\u201c", // „ “
    "\u201c" to "\u201d", // “ ”
    "\"" to "\"",
    "'" to "'"
)

/** A single wrapping Markdown code fence, with an optional info string (```lang … ```). */
private val CODE_FENCE = Regex("^```[^\\n]*\\n([\\s\\S]*?)\\n?```$")

/**
 * Post-processes a raw model answer into just the translation: trims whitespace, strips ONE wrapping
 * Markdown code fence and any surrounding quote pairs (repeatedly, so `«"text"»` collapses too).
 * Pure and unit-tested.
 */
internal fun cleanTranslation(raw: String): String {
    var text = raw.trim()
    CODE_FENCE.matchEntire(text)?.let { text = it.groupValues[1].trim() }
    var changed = true
    while (changed) {
        changed = false
        for ((open, close) in QUOTE_PAIRS) {
            if (text.length >= open.length + close.length &&
                text.startsWith(open) && text.endsWith(close)
            ) {
                text = text.substring(open.length, text.length - close.length).trim()
                changed = true
            }
        }
    }
    return text
}

/**
 * Translates text on demand via the configured LLM. Honest failures: no model configured, model
 * error, timeout or an empty answer all return `success = false` with a clear Russian message —
 * never a fabricated translation. An unknown target/source language is likewise rejected before any
 * LLM call (see [TranslateLanguages]).
 */
class TranslateToolsHandler(
    private val llmClient: LlmClient,
    private val settingsStore: LlmSettingsStore,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "translate_text"

            override suspend fun execute(arguments: JsonObject): ToolResult =
                withContext(Dispatchers.IO) {
                    val text = arguments.str("text")
                        ?: return@withContext ToolResult(false, "Укажи text — текст для перевода.")
                    val toRaw = arguments.str("to")
                        ?: return@withContext ToolResult(
                            false,
                            "Укажи to — целевой язык, например ru, en или «немецкий»."
                        )
                    if (text.length > MAX_TRANSLATE_INPUT_CHARS) {
                        return@withContext ToolResult(
                            false,
                            "Текст слишком длинный (${text.length} символов, максимум " +
                                "$MAX_TRANSLATE_INPUT_CHARS). Сократи его и повтори."
                        )
                    }
                    val to = TranslateLanguages.resolve(toRaw)
                        ?: return@withContext ToolResult(
                            false,
                            "Язык «$toRaw» не поддерживается. Укажи код или название из списка, " +
                                "например: ${TranslateLanguages.EXAMPLES} — или «русский», " +
                                "«английский», «немецкий»."
                        )
                    val fromRaw = arguments.str("from")
                    val from = if (TranslateLanguages.isAuto(fromRaw)) {
                        null
                    } else {
                        TranslateLanguages.resolve(fromRaw)
                            ?: return@withContext ToolResult(
                                false,
                                "Исходный язык «$fromRaw» не поддерживается. Укажи код или " +
                                    "название из списка (например: ${TranslateLanguages.EXAMPLES}) " +
                                    "либо auto — чтобы определить язык автоматически."
                            )
                    }
                    val tone = arguments.str("tone")

                    val configured = runCatching { settingsStore.isConfigured().first() }
                        .getOrDefault(false)
                    if (!configured) {
                        return@withContext ToolResult(
                            false,
                            "Модель не настроена: задай endpoint, модель и ключ в настройках — " +
                                "без этого перевод невозможен."
                        )
                    }

                    val raw = try {
                        collectTranslation(buildTranslatePrompt(text, to, from, tone))
                    } catch (timeout: TimeoutCancellationException) {
                        return@withContext ToolResult(
                            false,
                            "Модель не ответила за ${TRANSLATE_TIMEOUT_MS / 1000} с — перевод не выполнен."
                        )
                    } catch (cancellation: CancellationException) {
                        // Never swallow a stopped turn.
                        throw cancellation
                    } catch (e: Exception) {
                        return@withContext ToolResult(false, "Ошибка перевода: ${e.message}")
                    }

                    val translated = cleanTranslation(raw)
                    if (translated.isBlank()) {
                        return@withContext ToolResult(
                            false,
                            "Модель вернула пустой ответ — перевод не получен."
                        )
                    }
                    ToolResult(true, translated)
                }
        },
    )

    /**
     * Runs the single translation completion under the hard [TRANSLATE_TIMEOUT_MS] budget and returns
     * the concatenated text deltas. An [LlmEvent.Error] becomes an exception so the caller's failure
     * path applies uniformly.
     */
    private suspend fun collectTranslation(prompt: String): String = withTimeout(TRANSLATE_TIMEOUT_MS) {
        val text = StringBuilder()
        llmClient.complete(
            LlmRequest(
                messages = listOf(
                    LlmMessage("system", TRANSLATE_SYSTEM_PROMPT),
                    LlmMessage("user", prompt)
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
}
