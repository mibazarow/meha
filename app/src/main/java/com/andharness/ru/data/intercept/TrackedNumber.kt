package com.andharness.ru.data.intercept

import kotlinx.serialization.Serializable

/** One number the interception pipeline has seen and created/updated a chat for (update.md §2.6). */
@Serializable
data class TrackedNumber(
    val number: String,
    val chatId: Long,
    val firstSeenAt: Long,
    val lastEventAt: Long,
    val lastEventKind: String,
)
