package com.andharness.ru.domain.agent

/**
 * Pure, Android-free decision logic behind the two "the chat screen can never stay stuck"
 * guarantees of the agent orchestrator (update.md bug #7):
 *
 * 1. **A published "busy" state must never be a lie.** When the UI is told that a turn is running
 *    (the composer is disabled and Back is expected to keep working) but the coroutine that owned
 *    that turn is already gone, the state is *stale*: it must be reset so the composer comes back
 *    and the next turn is not rejected with `AgentRunOutcome.BUSY` forever.
 * 2. **A turn can never run unbounded.** A wall-clock cap and a "no progress" stall budget bound
 *    every turn, so a hung tool call / lost await can at worst delay the reset — never prevent it.
 *
 * Both rules are expressed here (rather than inline in [AgentOrchestrator]) so they are unit
 * testable without Android, a database or a running coroutine.
 */
object RunLifecyclePolicy {

    /** Agent states in which a turn is considered to be in progress. */
    val ACTIVE_STATES: Set<AgentState> = setOf(
        AgentState.RUNNING,
        AgentState.TOOL_EXEC,
        AgentState.WAITING_PERMISSION
    )

    /**
     * No-progress budget for one turn. Every bounded phase of a turn is shorter than this: one tool
     * call is capped at 4 min, the permission gate at 1 min, and one LLM completion only reports
     * "no progress" after the full streaming budget of `LlmTimeouts.IDLE_PROGRESS_TIMEOUT_SEC`
     * (1024 s ≈ 17 min). This budget is therefore set
     * to 18 min (1 080 000 ms) — STRICTLY above the largest LLM silence/data budget — so the run
     * watchdog can only fire once the LLM layer itself would already have given up. A turn that
     * produces no progress marker at all for this long is genuinely hung, not merely slow.
     *
     * This relationship is asserted by `RunLifecyclePolicyTest` against `LlmTimeouts`, so the two
     * can never drift apart again.
     */
    const val RUN_STALL_TIMEOUT_MS: Long = 1080_000L

    /**
     * Absolute wall-clock backstop for one turn. It must strictly exceed the overall cap of a single
     * completion, `LlmTimeouts.MAX_COMPLETION_SEC` (3600 s = 60 min), so it never pre-empts a
     * completion that is legally allowed to run that long: set to 61 min (3 660 000 ms) =
     * 3600 s + 60 s margin. It only exists so that even a turn that keeps emitting tiny progress
     * markers can never occupy the chat screen indefinitely.
     *
     * This relationship is asserted by `RunLifecyclePolicyTest` against `LlmTimeouts`, so the two
     * can never drift apart again.
     */
    const val RUN_HARD_CAP_MS: Long = 3_660_000L

    /** True when [state] means "a turn is being worked on". */
    fun isActive(state: AgentState): Boolean = state in ACTIVE_STATES

    /**
     * A published active state is stale when the coroutine that owned the turn is no longer active,
     * so nothing will ever clear it. It must be reset instead of being trusted.
     */
    fun isStale(state: AgentState, ownerJobActive: Boolean): Boolean =
        isActive(state) && !ownerJobActive

    /**
     * True when a turn that started at [startedAtMs] produced no progress by [nowMs] for at least
     * [stallTimeoutMs], or outlived [hardCapMs] regardless of progress. [startedAtMs] `<= 0` means
     * "no turn started" and is never reported as overdue.
     */
    fun isOverdue(
        startedAtMs: Long,
        lastProgressAtMs: Long,
        nowMs: Long,
        stallTimeoutMs: Long = RUN_STALL_TIMEOUT_MS,
        hardCapMs: Long = RUN_HARD_CAP_MS
    ): Boolean {
        if (startedAtMs <= 0L) return false
        if (nowMs - startedAtMs >= hardCapMs) return true
        return nowMs - lastProgressAtMs >= stallTimeoutMs
    }

    /**
     * Human-readable duration for the watchdog log line, e.g. `45 s`, `3 min 5 s`, `1 h 2 min`.
     * Kept pure and here (next to [isOverdue]) so the "how long did it stall" wording is unit-tested
     * and can never print an epoch (a raw `elapsedMs()` of an untracked turn used to be
     * `System.currentTimeMillis()` and was logged as a huge `1790061945s`). A non-positive value is
     * the honest "0 s" — no turn was being tracked.
     */
    fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "0 s"
        val totalSeconds = ms / 1_000L
        if (totalSeconds < 60L) return "$totalSeconds s"
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        if (minutes < 60L) return "$minutes min $seconds s"
        val hours = minutes / 60L
        val remainingMinutes = minutes % 60L
        return "$hours h $remainingMinutes min"
    }

    /**
     * Honest, user-facing explanation published when a hung turn is force-reset. It never pretends
     * the work succeeded, and it tells the user they can simply continue.
     */
    const val FORCED_STOP_MESSAGE: String =
        "Прогон остановлен автоматически: агент слишком долго не подавал признаков работы " +
            "(зависший инструмент или потерянное ожидание). Действие НЕ подтверждено — " +
            "проверьте результат и повторите запрос. Приложение перезапускать не нужно."
}
