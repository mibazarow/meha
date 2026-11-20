package com.andharness.ru.domain.notify


/**
 * The memory gate of idea #17: given a triage result and a [MemorizeMode] policy, decide whether a
 * long-term memory note may be written. The app fixes the policy to [MemorizeMode.ALL] (HIGH and
 * NORMAL are memorized, LOW is not); the enum is kept for testability.
 *
 * One-time codes and passwords are **never** memorized, whatever the mode — a hard rule, not a
 * preference.
 */
object MemoryPolicy {
    fun shouldMemorize(result: TriageResult, mode: MemorizeMode): Boolean {
        if (mode == MemorizeMode.OFF || result.secret) return false
        return when (mode) {
            MemorizeMode.HIGH_ONLY -> result.importance == TriageImportance.HIGH
            MemorizeMode.ALL -> result.importance != TriageImportance.LOW
            MemorizeMode.OFF -> false
        }
    }

    /**
     * Memory importance stored for a note (see `memory_save`: 0 normal / 1 important / 2 critical).
     *
     * A NORMAL notification is noise: it is worth remembering for a while, but not forever. Giving
     * it a real 0 — instead of the old blanket 1 — is what lets `MemoryGcWorker` do its job
     * (BUGS.md M7): before this, no triage-written row was ever eligible for collection, so GC
     * existed but could never remove anything. HIGH stays 2 (never collected).
     */
    fun memoryImportance(result: TriageResult): Int =
        if (result.importance == TriageImportance.HIGH) 2 else 0
}
