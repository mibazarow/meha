package com.andharness.ru.domain.objective

/**
 * Background execution of long-running objectives (checklist §2 «Параллельные задачи/фоновые цели»).
 *
 * [ObjectiveRunner] drives ONE bounded step of an objective: it calls the existing agent turn
 * (`AgentOrchestrator.run`) for the objective's chat with a short "continue the objective" instruction
 * and lets the agent itself checkpoint the step (`objective_step_update`). It never invents progress:
 * the only thing it does on its own is bump the objective's `backgroundRuns`/`lastWorkedAt` so the run
 * budget and the "last worked" view stay truthful.
 *
 * The runner deliberately does NOT re-implement the turn's wall-clock/stall bounds — those belong to
 * `RunLifecyclePolicy` and the orchestrator, which the runner always goes through. It only adds a
 * tighter per-step budget so one background objective cannot occupy the agent forever.
 */
interface ObjectiveRunner {

    /**
     * Runs at most one bounded agent step for the objective identified by [objectiveId].
     * Never throws: an unexpected failure is reported as [ObjectiveRunOutcome.FAILED].
     */
    suspend fun runStep(objectiveId: String): ObjectiveRunResult

    companion object {
        /** Hard wall-clock budget of ONE background objective step (10 min). */
        const val STEP_BUDGET_MS: Long = 600_000L
    }
}

/** Honest outcome of one background objective attempt. */
enum class ObjectiveRunOutcome {
    /** Gate value: the objective may proceed. Never reported as a final attempt outcome. */
    RUNNABLE,

    /** The objective was accepted into the background queue (see the workers status for running/queued). */
    STARTED,

    /** One bounded agent turn was actually run for the objective. */
    ADVANCED,

    /** The agent was already busy with another turn, so nothing was run for this objective. */
    BUSY,

    /** No LLM provider is configured — nothing was run and no progress was faked. */
    NO_LLM,

    /** No objective matched the given id/title. */
    NOT_FOUND,

    /** The objective is DONE/CANCELLED — terminal states are never re-run. */
    TERMINAL,

    /** The objective is PAUSED — it is not auto-run until the user resumes it. */
    PAUSED,

    /**
     * The objective is bound to a `chatId` that no longer exists (the chat was deleted). Running it
     * would insert messages whose FOREIGN KEY no longer resolves, so it is NOT run; the first
     * unfinished step is marked BLOCKED with an honest note so the objective stops being auto-run.
     */
    NO_CHAT,

    /** The first unfinished step is BLOCKED — it is not auto-run until the user/agent unblocks it. */
    BLOCKED,

    /** The objective exhausted its background run budget and is not auto-run further. */
    BUDGET_EXHAUSTED,

    /** The power guard asked to defer non-essential background work right now. */
    DEFERRED,

    /** An unexpected error occurred; the attempt did NOT succeed. */
    FAILED
}

/** Result of [ObjectiveRunner.runStep]. [detail] is an honest, short machine-readable/separable note. */
data class ObjectiveRunResult(val outcome: ObjectiveRunOutcome, val detail: String = "")

/** Which objective(s) the background coordinator considers RUNNING vs QUEUED right now. */
enum class ObjectiveWorkState { RUNNING, QUEUED }

/** One objective in the workers status. [state] separates "running" from merely "queued". */
data class ObjectiveWorkItem(val objectiveId: String, val title: String, val state: ObjectiveWorkState)

/**
 * Honest, point-in-time snapshot of background objective execution. `running`/`queued` are
 * process-scoped on purpose: a run cannot survive a process restart, so after a restart nothing is
 * reported as running (the periodic job simply re-enqueues eligible objectives).
 */
data class ObjectiveWorkersStatus(
    val running: List<ObjectiveWorkItem> = emptyList(),
    val queued: List<ObjectiveWorkItem> = emptyList(),
    val concurrencyLimit: Int = ObjectiveConcurrencyPolicy.DEFAULT,
    val effectiveLimit: Int = ObjectiveConcurrencyPolicy.DEFAULT,
    val lastTickAt: Long = 0L,
    val lastError: String? = null,
    val llmConfigured: Boolean = true,
    val note: String = ""
) {
    /** True while at least one objective is actually running. */
    val busy: Boolean get() = running.isNotEmpty()
}
