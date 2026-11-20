package com.andharness.ru.data.profile

import kotlinx.serialization.Serializable

/**
 * One durable fact the user explicitly asked to remember about themselves, shared across ALL chats.
 *
 * This is intentionally NOT free-form memory: only facts the user explicitly saved through
 * `profile_set` end up here, and the whole list is injected into every chat's system prompt (bounded),
 * so privacy is preserved by construction — nothing is inferred or scraped in.
 */

@Serializable
data class ProfileFact(
    val id: String,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long,
)
