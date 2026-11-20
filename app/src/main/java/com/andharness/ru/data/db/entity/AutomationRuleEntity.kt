package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A persisted "when <trigger> then <action>" automation rule (update.md §11, Room v13).
 *
 * [triggerType] / [actionType] are canonical strings from
 * [com.andharness.ru.domain.automation.AutomationSpec]; [triggerConfig] / [actionConfig] are the
 * type-specific payloads (e.g. `HH:MM` for a time trigger, `title | text` for a notify action).
 * [lastRunAt] is null until the rule has fired at least once.
 */
@Entity(tableName = "automation_rules")
data class AutomationRuleEntity(
    @PrimaryKey(autoGenerate = true)
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
