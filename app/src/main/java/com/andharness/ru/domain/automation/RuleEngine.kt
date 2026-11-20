package com.andharness.ru.domain.automation

/**
 * Evaluates automation rules against real trigger events and runs their actions.
 *
 * [dispatch] is non-blocking (used by broadcast receivers, network callbacks and service hooks);
 * [handle] is the suspend entry point used by the time worker and by tests.
 */
interface RuleEngine {
    fun dispatch(event: AutomationEvent)

    /** Runs every enabled rule matching [event]; returns the ids of the rules actually executed. */
    suspend fun handle(event: AutomationEvent): List<Long>
}
