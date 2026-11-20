package com.andharness.ru.domain.agent


/** A tool invocation requested by the model (arguments is a JSON string). */
data class LlmToolCall(
    val id: String,
    val name: String,
    val arguments: String
)
