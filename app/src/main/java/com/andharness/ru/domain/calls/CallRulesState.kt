package com.andharness.ru.domain.calls

import kotlinx.serialization.Serializable

/**
 * What is persisted on disk: the [rules] plus the bounded auto-reply cooldown log
 * ([autoReplyLog] maps a normalized number to the epoch-millis of its last automatic reply).
 * Keeping the log inside the store (not in [CallRules]) keeps the policy object pure.
 */

@Serializable
data class CallRulesState(
    val rules: CallRules = CallRules(),
    val autoReplyLog: Map<String, Long> = emptyMap(),
)
