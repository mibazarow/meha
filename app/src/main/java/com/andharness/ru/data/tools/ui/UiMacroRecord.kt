package com.andharness.ru.data.tools.ui

import kotlinx.serialization.Serializable

/** A saved, replayable UI macro: an ordered list of [UiMacroStep]s under a human name. */

@Serializable
data class UiMacroRecord(
    val id: String,
    val name: String,
    val createdAt: Long,
    val steps: List<UiMacroStep> = emptyList(),
)
