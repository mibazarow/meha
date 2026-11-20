package com.andharness.ru.domain.agent

/**
 * Deterministic, phrase-only guard against the "did not even try" failure (BUG-055): the model ends
 * its turn with a refusal / incapability («не могу», «невозможно», "I can't", ...) WITHOUT having
 * called a single tool during the whole run. Before such a refusal is accepted, the orchestrator
 * feeds back one corrective system message asking the model to actually use the available tools.
 *
 * Pure Kotlin (no Android / Room / coroutines), so it is trivially unit-testable on the JVM and can
 * never throw or block. [AgentOrchestratorImpl] applies [shouldCorrectOnce] on a FINAL plain-text
 * answer (no tool calls in the batch): when it returns true, one corrective message is injected and
 * exactly ONE extra model turn runs. The one-shot is guaranteed by the caller's per-run boolean
 * (`RunState.noAttemptCorrected`) — a model that refuses again is accepted as final, so no loop.
 */
object NoAttemptGuard {

    /**
     * Corrective system message appended once when the model refused without trying any tool.
     * Mirrors the hard prompt rule («ДЕЙСТВУЙ, А НЕ СПРАШИВАЙ») at the exact moment it was violated.
     */
    const val CORRECTIVE_MESSAGE: String =
        "Ты ещё не попробовал ни одного инструмента. Выполни действие доступными инструментами; " +
            "если реально невозможно — объясни это после попыток."

    /**
     * Refusal / incapability phrasing (ru + en). Case-insensitive, substring-based — the language is
     * morphologically rich, so roots work better than whole words.
     */
    private val INCAPABILITY_PHRASES = listOf(
        "не могу", "невозможно", "нет возможности", "к сожалению, я не",
        "не получится", "i can't", "unable to", "cannot"
    )

    /** True when [finalText] reads like a refusal / incapability report. */
    fun looksLikeIncapability(finalText: String): Boolean {
        if (finalText.isBlank()) return false
        val lower = finalText.lowercase()
        return INCAPABILITY_PHRASES.any { lower.contains(it) }
    }

    /**
     * The core predicate: true only when the run made ZERO tool calls AND the final text is a
     * refusal / incapability. A run that used any tool, or a normal (non-refusal) answer, is never
     * corrected.
     */
    fun shouldCorrect(toolCallsMadeInRun: Int, finalText: String): Boolean =
        toolCallsMadeInRun == 0 && looksLikeIncapability(finalText)

    /**
     * One-shot variant used by the orchestrator: same as [shouldCorrect] but also false once the
     * correction has already been applied in THIS run ([alreadyCorrected]). This is what bounds the
     * NoAttemptGuard to a single extra iteration (_no loops_).
     */
    fun shouldCorrectOnce(
        alreadyCorrected: Boolean,
        toolCallsMadeInRun: Int,
        finalText: String
    ): Boolean = !alreadyCorrected && shouldCorrect(toolCallsMadeInRun, finalText)
}
