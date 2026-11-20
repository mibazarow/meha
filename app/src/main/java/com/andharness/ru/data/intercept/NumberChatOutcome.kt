package com.andharness.ru.data.intercept

import com.andharness.ru.data.osint.NumberOsint

/** Outcome of one interception event, rendered honestly for tools/logs. */
data class NumberChatOutcome(
    val number: String?,
    val chatId: Long?,
    val knownContact: String?,
    val osint: NumberOsint?,
    val note: String,
)
