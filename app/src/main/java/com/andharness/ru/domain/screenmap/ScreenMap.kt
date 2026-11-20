package com.andharness.ru.domain.screenmap

import kotlinx.serialization.Serializable

/**
 * The remembered screen map of one app package. `screens` is ordered oldest-seen first so the
 * least-recently-seen screen is the first to be evicted when the per-app bound is reached.
 */
@Serializable
data class AppScreenMap(
    val appPackage: String,
    val screens: List<ScreenInfo> = emptyList(),
)
