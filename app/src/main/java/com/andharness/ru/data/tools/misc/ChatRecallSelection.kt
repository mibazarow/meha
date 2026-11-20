package com.andharness.ru.data.tools.misc


/** The messages a recall summary is built from plus how many were left out, and why. */
internal data class ChatRecallSelection(
    val lines: List<ChatRecallLine>,
    val skippedEmpty: Int,
    val droppedForLength: Int,
)
