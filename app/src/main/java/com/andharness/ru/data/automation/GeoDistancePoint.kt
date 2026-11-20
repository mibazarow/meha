package com.andharness.ru.data.automation

import kotlinx.serialization.Serializable

/**
 * The last location known to a `geo_distance` rule plus when it last fired.
 *
 * [lastFiredAt] is `0` for a baseline that was stored but has never fired (the first fix per rule).
 */
@Serializable
data class GeoDistancePoint(
    val ruleId: Long,
    val latitude: Double,
    val longitude: Double,
    val lastFiredAt: Long = 0L,
)
