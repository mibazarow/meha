package com.andharness.ru.domain.update

import kotlinx.serialization.Serializable

/**
 * Descriptor of the latest published app build, served as JSON by the update endpoint
 * ([com.andharness.ru.data.update.UpdateConfig.MANIFEST_URL]).
 *
 * Unknown keys are ignored by the parser so the server can add fields (e.g. per-ABI URLs) without
 * breaking older clients. Optional fields default to blank/zero and are validated by
 * [UpdatePolicy.validate] before use.
 *
 * Reference: https://kotlinlang.org/api/kotlinx.serialization/
 */
@Serializable
data class UpdateManifest(
    val versionCode: Long,
    val url: String,
    val versionName: String = "",
    val sha256: String = "",
    val sizeBytes: Long = 0,
    val releasedAt: String = "",
    val notes: String = "",
    val minSupportedVersionCode: Long = 0,
    val mandatory: Boolean = false,
)
