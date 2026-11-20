package com.andharness.ru.domain.memory


/**
 * One entry of the device-fact invalidation journal (TZ §12.3).
 *
 * @param key key of the affected fact.
 * @param reason human-readable cause of the invalidation.
 * @param at epoch millis when the invalidation was recorded.
 */
data class DeviceFactEvent(
    val id: Long,
    val key: String,
    val reason: String,
    val at: Long
)
