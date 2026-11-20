package com.andharness.ru.domain.agent

/**
 * Deterministic, phrase-only detector for the "reported a device action without ever calling a tool"
 * failure: the assistant's final text claims it OPENED / CLOSED / TURNED ON / SENT / DELETED / etc.
 * something on the phone, yet the whole turn produced ZERO tool invocations, so nothing could
 * actually have happened.
 *
 * It complements [FalseSuccessGuard]: that guard keys on the USER request reading as an action (and
 * on the last tool erroring), so it misses a claim whose user wording does not look like a request.
 * This detector keys ONLY on the assistant claim plus the presence of tool calls, and recognises a
 * wider, device-specific set of completion verbs (including English).
 *
 * It is deliberately CONSERVATIVE:
 *  - it NEVER fires when the turn invoked ANY tool (successful or failed): a failed-tool outcome
 *    remains [FalseSuccessGuard]'s business, and a real tool call means the model actually acted;
 *  - refusals / negations («не смог», "couldn't"), plans/futures ("I will open…") and questions are
 *    not treated as claims;
 *  - it matches explicit PAST/completed device-action verbs (substring-stem for Russian, whole-word
 *    for English), so a plan («открою», "I will open") does not match.
 *
 * Android-free and side-effect-free, so the contract is unit-tested on the JVM and can never throw
 * or block. [com.andharness.ru.data.agent.AgentOrchestratorImpl] runs it at the end of a plain-text
 * turn: when it fires, ONE corrective system message is injected and ONE extra model turn is run;
 * if the model still claims the action, the user-visible text is REPLACED with [HONEST_FALLBACK].
 * Never loops.
 */
object DeviceActionClaimDetector {

    /** One tool invocation of the turn: its [name] and whether it reported [success]. */
    data class ToolCall(val name: String, val success: Boolean)

    /**
     * Corrective system message injected ONCE when a device-action claim has no supporting tool
     * call. Mirrors the hard prompt rule at the exact moment it was violated.
     */
    const val CORRECTION_PROMPT: String =
        "СТОП. Ты сообщил о действии на телефоне, но не вызвал ни одного инструмента. " +
            "Вызови подходящий инструмент и доложи по его результату либо честно скажи, что не выполнил."

    /**
     * Honest replacement shown INSTEAD of the model's text when, even after the single correction,
     * it still claims a device action without any tool call. Replacing (not appending) guarantees
     * the misleading claim cannot stand.
     */
    const val HONEST_FALLBACK: String =
        "Похоже, действие не выполнено: инструмент не вызывался. Скажи, и я попробую ещё раз."

    /**
     * Russian completion roots (past/accomplished forms of device operations). Substring-stem based,
     * because Russian is morphologically rich (a root also matches «открыла», «удалили», …). Only
     * accomplished past forms are listed on purpose so a plan / infinitive («открыть») never matches.
     */
    private val RU_CLAIM_ROOTS = listOf(
        "открыл", "закрыл", "включил", "выключил", "отключил", "переключил",
        "установил", "удалил", "отправил", "создал", "добавил", "изменил",
        "переименовал", "переместил", "скопировал", "сохранил", "закрепил",
        "запустил", "позвонил", "снял", "записал", "оплатил", "скачал",
        "загрузил", "поставил", "сделал"
    )

    /**
     * English completion words (whole-word matched, so «sent» never matches «present» / «absent»).
     */
    private val EN_CLAIM_ROOTS = listOf(
        "opened", "closed", "turned on", "turned off", "switched on", "switched off",
        "installed", "uninstalled", "deleted", "removed", "sent", "created", "added",
        "changed", "renamed", "moved", "copied", "saved", "pinned", "launched",
        "recorded", "downloaded", "uploaded", "enabled", "disabled", "paid"
    )

    /**
     * Refusal / incapability phrasing (ru + en): the model already says it could NOT do it, so the
     * text is not a success claim. Case-insensitive, substring-based.
     */
    private val NON_CLAIM_PHRASES = listOf(
        "не могу", "не смог", "не смогла", "не смогли", "не удалось", "не получилось",
        "не вышло", "не получится", "невозможно", "не умею", "не сумел", "нет инструмента",
        "не поддержива", "не позволя", "не выполн",
        "could not", "couldn't", "cannot", "can't", "unable to", "failed to", "didn't",
        "did not", "not able", "tried to"
    )

    /** Planning / future-tone phrasing that is not a completion claim. */
    private val NON_CLAIM_PLAN_PHRASES = listOf(
        "собираюсь", "планирую", "попробую", "попытаюсь", "хочу",
        "i will", "i'll", "i am going to", "i'm going to", "let me", "going to", "plan to"
    )

    /** English negation cues: a bare «not» near the verb means the claim is denied. */
    private val EN_NEGATION_CUES = listOf("not ", "n't ", "never ")

    /**
     * True when [assistantText] CLAIMS a completed device action. Conservative on purpose: a blank
     * text, an explicit refusal/negation, a plan/future sentence, or a question never matches.
     */
    fun claimsCompletedDeviceAction(assistantText: String): Boolean {
        if (assistantText.isBlank()) return false
        val lower = assistantText.lowercase()
        if (lower.trimEnd().endsWith("?")) return false
        if (NON_CLAIM_PHRASES.any { lower.contains(it) }) return false
        if (NON_CLAIM_PLAN_PHRASES.any { lower.contains(it) }) return false
        if (EN_NEGATION_CUES.any { lower.contains(it) }) return false
        if (RU_CLAIM_ROOTS.any { lower.contains("не $it") }) return false
        return RU_CLAIM_ROOTS.any { lower.contains(it) } ||
            EN_CLAIM_ROOTS.any { containsWord(lower, it) }
    }

    /**
     * The core predicate: the reply claims a completed device action and the turn made NO tool call
     * at all. Any tool invocation in the turn (successful or not) makes this return false: a real
     * call means the model acted, and a failed outcome is handled by [FalseSuccessGuard].
     */
    fun shouldCorrect(
        assistantText: String,
        toolCalls: List<ToolCall>
    ): Boolean = toolCalls.isEmpty() && claimsCompletedDeviceAction(assistantText)

    /** Whole-word (non-letter-bounded) match of [word] in the already-lowercased [lower]. */
    private fun containsWord(lower: String, word: String): Boolean {
        var from = 0
        while (true) {
            val idx = lower.indexOf(word, from)
            if (idx < 0) return false
            val before = idx == 0 || !lower[idx - 1].isLetter()
            val end = idx + word.length
            val after = end >= lower.length || !lower[end].isLetter()
            if (before && after) return true
            from = idx + 1
        }
    }
}
