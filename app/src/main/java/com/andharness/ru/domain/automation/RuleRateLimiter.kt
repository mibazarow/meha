package com.andharness.ru.domain.automation

import java.util.concurrent.ConcurrentHashMap

/**
 * Pure, JVM-testable circuit breaker for automation rules (update.md §11.6).
 *
 * Input validation (see [AutomationSpec.validateTrigger]) rejects obviously broken configs, but it
 * cannot catch a *semantically* degenerate rule that is valid yet always true — e.g. a
 * `sound_event` rule whose threshold used to accept `-100 dBFS`, which fires on every audio sample
 * and spammed the user roughly once a minute. This limiter is the runtime safety net:
 *
 * - **[MIN_INTERVAL_MS]**: no rule may fire more often than once every 5 s. A trigger that fires
 *   within the interval is dropped (the action is not executed and `lastRunAt` is not advanced).
 * - **[NOISY_MAX_FIRES]/[NOISY_WINDOW_MS]**: a rule that fires 6 times in a sliding 5-minute window
 *   is flagged once and returned as [Decision.DisableNoisy], so the engine can persist it as
 *   disabled and tell the user. After the flag the limiter reports [Decision.SkipThrottled] for that
 *   rule until [reset], which keeps a failing `setEnabled` from producing a notification storm.
 *
 * The limiter is deliberately free of Android imports and of any clock: [evaluate] takes [nowMs]
 * from the caller, so behaviour is fully deterministic in unit tests. All state is in memory — no
 * schema change, no migration — and therefore resets on process death, which is acceptable because
 * the noisy-rule flag is also persisted (the rule is disabled in the repository).
 */
class RuleRateLimiter {

    /** Outcome of [evaluate]; the engine switches on it before running a rule's action. */
    sealed interface Decision {
        /** The rule may run now. */
        object Fire : Decision

        /** The call happened too soon after the previous fire; the action must be skipped. */
        object SkipThrottled : Decision

        /** The rule fired too often in the window; the engine must disable it and notify the user. */
        object DisableNoisy : Decision
    }

    private class RuleState {
        var lastFireAtMs: Long? = null
        val fireTimestampsMs = ArrayDeque<Long>()
        var noisy = false
    }

    private val lock = Any()
    private val states = ConcurrentHashMap<Long, RuleState>()

    /**
     * Decides what to do with [ruleId] at [nowMs]. Records the fire when it returns
     * [Decision.Fire] or [Decision.DisableNoisy]; a throttled call is not recorded, so it neither
     * counts towards the noisy window nor advances the minimum-interval timestamp.
     */
    fun evaluate(ruleId: Long, nowMs: Long): Decision = synchronized(lock) {
        val state = states.getOrPut(ruleId) { RuleState() }

        if (state.noisy) return@synchronized Decision.SkipThrottled

        val lastFireAt = state.lastFireAtMs
        if (lastFireAt != null && nowMs - lastFireAt < MIN_INTERVAL_MS) {
            return@synchronized Decision.SkipThrottled
        }
        state.lastFireAtMs = nowMs

        val windowStart = nowMs - NOISY_WINDOW_MS
        while (state.fireTimestampsMs.isNotEmpty() && state.fireTimestampsMs.first() < windowStart) {
            state.fireTimestampsMs.removeFirst()
        }
        state.fireTimestampsMs.addLast(nowMs)
        if (state.fireTimestampsMs.size >= NOISY_MAX_FIRES) {
            state.noisy = true
            return@synchronized Decision.DisableNoisy
        }
        Decision.Fire
    }

    /** Forgets all state for [ruleId] (tests, and re-arming a rule the user has re-enabled). */
    fun reset(ruleId: Long) {
        synchronized(lock) {
            states.remove(ruleId)
        }
    }

    companion object {
        /** Minimum spacing between two fires of the same rule. */
        const val MIN_INTERVAL_MS = 5_000L

        /** Width of the sliding window used to detect a noisy rule. */
        const val NOISY_WINDOW_MS = 5 * 60_000L

        /** Fires within [NOISY_WINDOW_MS] after which a rule is considered noisy and disabled. */
        const val NOISY_MAX_FIRES = 6
    }
}
