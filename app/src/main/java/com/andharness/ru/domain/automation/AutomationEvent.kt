package com.andharness.ru.domain.automation

/**
 * One concrete occurrence of a trigger, handed from a real source (a receiver, a network
 * callback, the accessibility service, the notification listener or the time worker) to
 * [RuleEngine].
 *
 * [ruleId] optionally targets a single rule (used by the per-rule time worker); when null the
 * engine evaluates every enabled rule of [triggerType].
 */
data class AutomationEvent(
    val triggerType: String,
    val ruleId: Long? = null,
    val packageName: String? = null,
    val title: String? = null,
    val text: String? = null,
    val payload: String? = null,
    /** Free-form observed value: calendar event title or BLE address (idea #18). */
    val value: String? = null,
    /** Advertised BLE device name (idea #18, `ble_presence`). */
    val name: String? = null,
    /** Observed signal strength in dBm (idea #18, `ble_presence`). */
    val rssi: Int? = null,
    /** Observed microphone level in dBFS (idea #18, `sound_event`). */
    val level: Double? = null,
    val at: Long = System.currentTimeMillis()
)
