package com.andharness.ru.domain.objective

/**
 * Pure decision of whether a background runner may touch an objective RIGHT NOW, and — when it may
 * not — the honest reason (checklist §2, §5). Kept Android-free and unit-tested so every refusal is
 * explicit and nothing is ever run "because it seemed fine".
 *
 * Precedence (terminal first, so a finished objective is never reopened):
 * DONE/CANCELLED → PAUSED → BLOCKED → BUDGET → NO_LLM → DEFERRED → RUNNABLE.
 */
object ObjectiveRunGate {

    /**
     * Maximum number of background steps auto-run for one objective before it is left for the user
     * to continue explicitly (the "run budget"). Interactive tools (`objective_next`/
     * `objective_step_update`) do not consume this budget — only background attempts do.
     */
    const val MAX_BACKGROUND_RUNS = 25

    /**
     * @param blocked true when the objective's first unfinished step is [ObjectiveStepStatus.BLOCKED].
     * @param backgroundRuns how many background steps this objective has already consumed.
     */
    fun evaluate(
        status: ObjectiveStatus,
        blocked: Boolean,
        llmConfigured: Boolean,
        deferred: Boolean,
        backgroundRuns: Int,
        maxRuns: Int = MAX_BACKGROUND_RUNS
    ): ObjectiveRunOutcome = when {
        ObjectiveRules.isTerminal(status) -> ObjectiveRunOutcome.TERMINAL
        status == ObjectiveStatus.PAUSED -> ObjectiveRunOutcome.PAUSED
        blocked -> ObjectiveRunOutcome.BLOCKED
        backgroundRuns >= maxRuns -> ObjectiveRunOutcome.BUDGET_EXHAUSTED
        !llmConfigured -> ObjectiveRunOutcome.NO_LLM
        deferred -> ObjectiveRunOutcome.DEFERRED
        else -> ObjectiveRunOutcome.RUNNABLE
    }

    /** True when [evaluate] would let the objective run. */
    fun isRunnable(
        status: ObjectiveStatus,
        blocked: Boolean,
        llmConfigured: Boolean,
        deferred: Boolean,
        backgroundRuns: Int,
        maxRuns: Int = MAX_BACKGROUND_RUNS
    ): Boolean = evaluate(status, blocked, llmConfigured, deferred, backgroundRuns, maxRuns) ==
        ObjectiveRunOutcome.RUNNABLE
}
