package com.andharness.ru.domain.notifications

import kotlinx.serialization.Serializable

/**
 * One per-app rule. [note] is a free-form, bounded reminder shown back by `notification_rules_list`.
 */

@Serializable
data class AppRule(
    val appPackage: String,
    val importance: RuleImportance = RuleImportance.NORMAL,
    val note: String? = null,
)
