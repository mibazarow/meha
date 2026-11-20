package com.andharness.ru.data.clipboard

import kotlinx.serialization.Serializable

/**
 * One remembered clipboard item.
 *
 * [sourcePackage] is the package that produced the text when we know it (the app itself for
 * `clipboard_set`); it is null when the text was read from the system clipboard on foregrounding,
 * because Android exposes no public API for the clipboard's source package (see
 * [ClipboardHistoryRules]).
 */

@Serializable
data class ClipboardEntry(
    val id: String,
    val text: String,
    val sourcePackage: String? = null,
    val timestamp: Long = 0L,
)
