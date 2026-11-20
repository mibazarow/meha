package com.andharness.ru.domain.agent


enum class AgentState {
    IDLE, RUNNING, TOOL_EXEC, WAITING_PERMISSION, DONE, ERROR, CANCELLED,

    /**
     * Accepted but waiting for a free run slot (a background run holds it). Only the interactive
     * path uses it today; it is deliberately NOT one of [RunLifecyclePolicy.ACTIVE_STATES], so the
     * stall/hard-cap watchdog is never armed for a merely queued run.
     */
    QUEUED
}
