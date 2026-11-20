package com.andharness.ru.domain.automation

/**
 * A persisted "when <trigger> then <action>" automation rule (update.md §11.6).
 *
 * [triggerType] and [actionType] are the canonical strings from [AutomationSpec];
 * [triggerConfig] / [actionConfig] are free-form, type-specific strings whose validity is
 * enforced by [AutomationSpec] (a blank config usually means "any"/"no extra arguments").
 */
data class AutomationRule(
    val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    val triggerType: String,
    val triggerConfig: String = "",
    val actionType: String,
    val actionConfig: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val lastRunAt: Long? = null
)
