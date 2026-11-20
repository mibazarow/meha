package com.andharness.ru.domain.agent

/**
 * Priority of ONE agent run. The orchestrator supports several simultaneous runs (bounded by
 * [RunConcurrencyLimiter]); an INTERACTIVE run (a chat the user is waiting on, or the floating
 * overlay assistant) always outranks a BACKGROUND run (a long-running objective / scheduled task).
 *
 * The concrete guarantees for a BACKGROUND run are:
 * - it may never occupy the last free slot — at least one slot is reserved for INTERACTIVE
 *   (`backgroundCap = limit - 1`), so a chat is admitted immediately while a background run proceeds;
 * - when the device is degraded to a single-slot limit ([com.andharness.ru.domain.objective.ObjectiveConcurrencyPolicy]),
 *   it yields its slot at the next checkpoint if a chat is waiting;
 * - it is never a reason for an INTERACTIVE run to be rejected: at worst the chat is queued.
 */
enum class AgentRunPriority { INTERACTIVE, BACKGROUND }
