package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One persisted automation event: a trigger occurrence that actually fired at least one enabled
 * rule. Written by
 * [com.andharness.ru.data.automation.AutomationEventRepositoryImpl.record] from the rule engine
 * right after an action was dispatched, so the user/agent can ask «что было ночью?».
 *
 * Only firing events are stored — raw sensor/notification observations are never written here, so
 * the table stays small and honest. Index on [createdAt] matches the newest-first reads. Schema and
 * column names must match [com.andharness.ru.data.db.AutomationEventSchemaSql] used by the Room
 * migration.
 *
 * Official reference: https://developer.android.com/training/data-storage/room/defining-data
 */
@Entity(
    tableName = "automation_events",
    indices = [Index("createdAt")]
)
data class AutomationEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Canonical trigger type from [com.andharness.ru.domain.automation.AutomationSpec]. */
    val eventType: String,
    /** Free-form observed value (package, SSID, title, distance, BLE address, …). */
    val value: String? = null,
    /** Free-form observed payload from the trigger source. */
    val payload: String? = null,
    /** The rule that fired, or null when the row could not be tied to one. */
    val ruleId: Long? = null,
    /** The fired rule's name at the moment it fired (denormalised, so deletes stay readable). */
    val ruleName: String? = null,
    /** When the trigger occurred (the [com.andharness.ru.domain.automation.AutomationEvent.at]). */
    val createdAt: Long = System.currentTimeMillis()
)
