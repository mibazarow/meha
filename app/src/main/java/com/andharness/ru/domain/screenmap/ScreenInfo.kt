package com.andharness.ru.domain.screenmap

import kotlinx.serialization.Serializable

/**
 * One remembered screen of an app, identified by a stable [signature] (foreground Activity short name
 * + short hash of the top node labels — see [ScreenSignature]). [lastSeen] is the wall-clock time of
 * the most recent successful observation, [title] a best-effort human label, and [elements] the
 * bounded list of notable interactive controls.
 */
@Serializable
data class ScreenInfo(
    val signature: String,
    val title: String? = null,
    val lastSeen: Long = 0L,
    val elements: List<ScreenElementInfo> = emptyList(),
)
