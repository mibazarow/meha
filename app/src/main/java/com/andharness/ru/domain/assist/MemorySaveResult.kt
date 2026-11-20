package com.andharness.ru.domain.assist


/**
 * Outcome of a consolidated `memory_save`: [created] is false when an existing row was updated.
 *
 * [rejected] is true when the input never reached the table because it is not a fact at all
 * («ок», «спасибо», «говорящий сказал…»). It is reported instead of pretending the memory was
 * stored, so the model does not go on believing it remembers something that does not exist.
 */
data class MemorySaveResult(
    val id: Long,
    val created: Boolean,
    val text: String,
    val tags: String,
    val importance: Int,
    val rejected: Boolean = false
)
