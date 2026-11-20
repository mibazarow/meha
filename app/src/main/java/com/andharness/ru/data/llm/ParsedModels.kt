package com.andharness.ru.data.llm


/**
 * Models discovered in a `GET {baseUrl}/models` payload.
 *
 * @param malformed true when the body was not JSON at all (or not an object/array); an honest
 *                  failure that the caller must surface, as opposed to a valid-but-empty list.
 */
data class ParsedModels(
    val models: List<String> = emptyList(),
    val contextById: Map<String, Long> = emptyMap(),
    val malformed: Boolean = false,
)
