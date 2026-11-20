package com.andharness.ru.domain.automation

/**
 * One persisted automation event: a trigger occurrence that actually fired an
 * enabled rule — the durable counterpart of the transient [AutomationEvent].
 *
 * Unlike [AutomationEvent], this is a historical row: [ruleId]/[ruleName] name the rule that fired
 * (denormalised so the row stays readable after the rule is deleted), and [createdAt] is the moment
 * the trigger occurred.
 */
data class AutomationEventRecord(
    val id: Long = 0,
    val eventType: String,
    val value: String? = null,
    val payload: String? = null,
    val ruleId: Long? = null,
    val ruleName: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
