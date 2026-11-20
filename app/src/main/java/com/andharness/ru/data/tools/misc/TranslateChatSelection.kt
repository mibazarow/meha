package com.andharness.ru.data.tools.misc

/** The messages actually sent to the LLM plus how many were left out, and why. */
internal data class TranslateChatSelection(
    val lines: List<TranslateChatLine>,
    val skippedEmpty: Int,
    val droppedForLength: Int,
)
