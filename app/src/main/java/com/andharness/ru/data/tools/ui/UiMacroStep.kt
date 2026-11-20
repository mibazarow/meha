package com.andharness.ru.data.tools.ui

import kotlinx.serialization.Serializable

/** One recorded UI action: the tool that ran plus the exact JSON arguments it received. */

@Serializable
data class UiMacroStep(
    val tool: String,
    val argsJson: String,
)
