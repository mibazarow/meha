package com.andharness.ru.data.tools.location

import kotlinx.serialization.Serializable

/** A registered geofence as tracked locally (source of truth for `geofence_list`). */
@Serializable
data class GeofenceRecord(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val radiusM: Float,
    val expirationHours: Int? = null,
    val dwell: Boolean = false,
    val addedAtMs: Long,
)
