package com.andharness.ru.data.tools.location

import kotlinx.serialization.Serializable

/** One recorded geofence transition, persisted when the receiver fires. */
@Serializable
data class GeofenceEvent(
    val geofenceId: String,
    /** `ENTER`, `EXIT`, `DWELL` or `UNKNOWN`. */
    val transition: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val timeMs: Long,
)
