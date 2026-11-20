package com.andharness.ru.domain.style

import kotlinx.serialization.Serializable

/**
 * Persistent, cross-chat style profile. Contains only bounded counters and a short natural-language
 * card — never raw messages — so it stays private and cheap to inject into the prompt.
 *
 * Days are stored as epoch days (`LocalDate.toEpochDay`) and timestamps as epoch milliseconds, which
 * keeps the JSON timezone-independent and trivial to bound.
 */
@Serializable
data class StyleProfile(
    val version: Int = 1,
    val samples: Int = 0,
    val sessions: Int = 0,
    val firstDay: Long? = null,
    val lastDay: Long? = null,
    val lastMessageAt: Long = 0L,
    val mode: StyleMode = StyleMode.AUTO,
    /**
     * Whether mild swearing may be mirrored at all. On by default; a user can turn it off without
     * losing the rest of the learned style. The hard limits (no insults/aggression, max one word)
     * still apply when it is on.
     */
    val profanityEnabled: Boolean = true,
    val traits: List<StyleTrait> = emptyList(),
    val card: String? = null,
    val cardUpdatedAt: Long = 0L,
    val manualCard: String? = null,
) {
    /** The trait for [key], or null when it has not been observed yet. */
    fun trait(key: StyleTraitKey): StyleTrait? = traits.firstOrNull { it.key == key }

    /** Replaces the trait with the same key in place, or appends it, preserving list order. */
    fun withTrait(trait: StyleTrait): StyleProfile {
        val index = traits.indexOfFirst { it.key == trait.key }
        val updated = if (index >= 0) {
            traits.toMutableList().also { it[index] = trait }
        } else {
            traits + trait
        }
        return copy(traits = updated)
    }
}
