package com.andharness.ru.domain.agent


/** Outcome of [AgentOrchestrator.run]: the turn started, or a run was already in progress. */
enum class AgentRunOutcome { STARTED, BUSY }
