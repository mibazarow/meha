package com.andharness.ru.data.tools.misc

import com.andharness.ru.domain.agent.LlmClient
import com.andharness.ru.domain.agent.LlmEvent
import com.andharness.ru.domain.agent.LlmMessage
import com.andharness.ru.domain.agent.LlmRequest
import kotlinx.coroutines.withTimeout

/**
 * Shared plumbing for the LLM-backed translation tools (`translate_text`, `translate_chat`,
 * `translate_screen`): ONE translation-only completion under a hard wall-clock budget.
 *
 * The strict «translator, not a chat partner» framing ([TRANSLATE_SYSTEM_PROMPT]) and the timeout
 * behaviour are identical for every translation tool, so they live here instead of being copied.
 *
 * [LlmEvent.Error] becomes an exception so each caller's failure path is uniform. A foreign
 * `CancellationException` (a stopped turn) is never swallowed: `withTimeout` cancels only its own
 * coroutine, so the caller rethrows a genuine cancellation.
 *
 * References:
 * - kotlinx.coroutines `withTimeout` (hard wall-clock budget) —
 *   https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/withTimeout.html
 * - In-repo LLM contract: `domain/agent/LlmClient.kt` (`fun complete(request): Flow<LlmEvent>`).
 * - OpenAI-compatible chat completions request/`messages` —
 *   https://platform.openai.com/docs/api-reference/chat/create
 */
internal suspend fun collectTranslateCompletion(
    llmClient: LlmClient,
    userPrompt: String,
    timeoutMs: Long = TRANSLATE_TIMEOUT_MS,
): String = withTimeout(timeoutMs) {
    val text = StringBuilder()
    llmClient.complete(
        LlmRequest(
            messages = listOf(
                LlmMessage("system", TRANSLATE_SYSTEM_PROMPT),
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

/** Honest "no model configured" message, identical for every translation tool. */
internal const val TRANSLATE_NOT_CONFIGURED: String =
    "Модель не настроена: задай endpoint, модель и ключ в настройках — без этого перевод невозможен."

/** Honest timeout message built from the shared [TRANSLATE_TIMEOUT_MS] budget. */
internal fun translateTimeoutMessage(): String =
    "Модель не ответила за ${TRANSLATE_TIMEOUT_MS / 1000} с — перевод не выполнен."

/** Honest empty-answer message, identical for every translation tool. */
internal const val TRANSLATE_EMPTY_ANSWER: String =
    "Модель вернула пустой ответ — перевод не получен."
