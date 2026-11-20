package com.andharness.ru.data.agent

import com.andharness.ru.domain.formatting.collapseWhitespace

/**
 * Lightweight, deterministic heuristics used by [AgentOrchestratorImpl] to catch the case where the
 * user asked the agent to DO something on the phone but the model produced a plain-text answer
 * without calling any tool (a frequent failure of weak/OpenAI-compatible local tool-calling).
 *
 * When that happens the orchestrator issues one extra system prompt asking the model to actually
 * emit the matching tool call (bounded to a single retry), unless the model already clearly stated
 * it cannot / will not do it.
 */

/** System prompt appended once to nudge the model to actually call a tool for an action request. */
const val ACTION_COERCION_PROMPT: String =
    "Пользователь попросил выполнить действие на телефоне, но ты не вызвал ни одного инструмента.\n" +
        "Если для этого действия существует инструмент — вызови его СЕЙЧАС (только через вызов функции).\n" +
        "Если подходящего инструмента нет или действие технически недоступно — коротко и честно скажи это пользователю.\n" +
        "Не описывай действие словами вместо вызова инструмента."

private val ACTION_VERBS = listOf(
    // Russian imperatives / infinitives for device actions.
    "включи", "выключи", "отключи", "включить", "выключить", "отключить",
    "открой", "закрой", "открыть", "закрыть",
    "создай", "создать", "удали", "удалить", "сотри",
    "скачай", "скачать", "загрузи", "загрузить", "запиши", "записать",
    "отправь", "отправить", "перемести", "переместить", "скопируй", "скопировать",
    "переименуй", "запусти", "запустить", "установи", "добавь", "сохрани", "удали",
    // English.
    "turn on", "turn off", "enable", "disable", "open", "close",
    "create", "delete", "remove", "download", "write", "send", "move", "copy",
    "rename", "launch", "install", "record", "add", "save"
)

/**
 * True when [message] reads like a directive to perform an action on the device (as opposed to an
 * ordinary question or a casual sentence). Interrogative messages (ending with "?") are treated as
 * questions, not actions.
 */
fun looksLikeActionRequest(message: String): Boolean {
    val m = message.trim()
    if (m.endsWith("?")) return false
    val lower = m.lowercase()
    return ACTION_VERBS.any { lower.contains(it) }
}

private val REFUSAL_PATTERNS = listOf(
    "не могу", "не могу сам", "не могу вызвать", "не получилось", "не удалось", "не получится",
    "не вышло", "не смог", "сделайте в настройках", "в настройках", "недоступ", "не умею",
    "нет инструмента", "не имеет", "не поддержива", "не позволя", "вручную", "я не могу",
    "не получается", "отказался", "не сумел", "не смогу"
)

/**
 * True when the model's [replyText] already states it could not / will not perform the requested
 * action (so we should NOT re-prompt it to call a tool).
 */
fun looksLikeExplicitRefusal(replyText: String): Boolean {
    val lower = replyText.lowercase()
    return REFUSAL_PATTERNS.any { lower.contains(it) }
}

/**
 * Length-bounded prefix (chars) of a tool-result output folded into the progress digest, so a
 * repeated call that produced the same observable result is recognised as a loop while a changed
 * value (on↔off, a counter, fresh sensor data) is progress. Kept short on purpose: the guard only
 * needs to notice a *change*, not reproduce the whole result (which [ToolOutputLimiter] already
 * bounds before it is fed back to the model).
 */
private const val DIGEST_MAX_LEN = 120

/**
 * Short, stable digest of a tool-result output, used by the progress-aware anti-loop guard
 * ([LoopGuard]) to tell whether a repeated tool call actually changed anything. It folds the output
 * to a length-bounded, whitespace-normalized form prefixed by its length, so cosmetic rewording of
 * an unchanged result does not look like progress while a changed value does. A missing result (a
 * cancelled call) maps to a stable marker.
 */
fun resultDigest(output: String?): String {
    if (output == null) return "cancelled"
    val normalized = collapseWhitespace(output)
    val body = if (normalized.length <= DIGEST_MAX_LEN) normalized else normalized.take(DIGEST_MAX_LEN)
    return "${normalized.length}:${body}"
}

private val REPEAT_REQUEST_PATTERNS = listOf(
    "повтори", "повторите", "повторяй", "повторяйте",
    "сделай ещё", "сделай снова", "сделайте ещё", "сделайте снова",
    "ещё раз", "ещё разик", "ещё разок", "ещё один",
    "повтори ещё", "повторите ещё",
    "repeat", "repeating", "repeat again", "once more", "again"
)

/**
 * Heuristic: did the user explicitly ask to repeat an action? When they did, identical call+result
 * batches are intentional (a metronome, «повтори 5 раз», a retry-until-condition loop), so the
 * anti-loop guard is bypassed and the per-run iteration/wall-clock caps ([AgentOrchestrator] loop /
 * [RunLifecyclePolicy]) are the only bound. Matches common Russian and English repeat requests.
 */
fun looksLikeRepeatRequest(message: String): Boolean {
    val lower = message.lowercase()
    return REPEAT_REQUEST_PATTERNS.any { lower.contains(it) }
}
