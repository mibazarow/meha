package com.andharness.ru.data.style

import com.andharness.ru.domain.style.StyleProfile
import com.andharness.ru.domain.style.StyleTrait
import com.andharness.ru.domain.style.StyleTraitKey

/**
 * Bounds and normalization for the persisted [StyleProfile]. A hand-edited or corrupted JSON file
 * must never inject an oversized card, an out-of-range value or duplicate traits into the prompt.
 *
 * Pure and Android-free so it is exercised on the JVM.
 */
object StyleProfileRules {

    /** Current schema version written by this build. */
    const val CURRENT_VERSION = 1

    /** Hard cap of one learned card (LLM or manual), in characters. */
    const val MAX_CARD_CHARS = 200

    /** One trait per key, so the list can never exceed the enum size. */
    val MAX_TRAITS = StyleTraitKey.entries.size

    /** Distinct days retained per trait. */
    const val MAX_DAY_KEYS = 14

    /** Trims and collapses a card; blank becomes null. Overlong cards are rejected (null). */
    fun normalizeCard(raw: String?): String? {
        val clean = raw?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        if (clean.isEmpty() || clean.length > MAX_CARD_CHARS) return null
        return clean
    }

    /** Best-effort cap for a model-produced card: truncates instead of rejecting. */
    fun capCard(raw: String?): String? {
        val clean = raw?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        if (clean.isEmpty()) return null
        return if (clean.length <= MAX_CARD_CHARS) clean else clean.take(MAX_CARD_CHARS).trimEnd()
    }

    /**
     * Clamps numeric fields, dedupes traits (last one per key wins), caps days and normalizes cards.
     * Never throws.
     */
    fun sanitize(profile: StyleProfile): StyleProfile {
        val byKey = LinkedHashMap<StyleTraitKey, StyleTrait>()
        for (trait in profile.traits) {
            byKey[trait.key] = sanitizeTrait(trait)
        }
        return profile.copy(
            version = CURRENT_VERSION,
            samples = profile.samples.coerceAtLeast(0),
            sessions = profile.sessions.coerceAtLeast(0),
            traits = byKey.values.take(MAX_TRAITS),
            card = normalizeCard(profile.card),
            manualCard = normalizeCard(profile.manualCard),
        )
    }

    private fun sanitizeTrait(trait: StyleTrait): StyleTrait {
        val observations = trait.observations.coerceAtLeast(0)
        return trait.copy(
            value = trait.value.coerceIn(0.0, 1.0),
            observations = observations,
            hitMessages = trait.hitMessages.coerceIn(0, observations),
            dayKeys = trait.dayKeys.distinct().sorted().takeLast(MAX_DAY_KEYS),
            lastSeenAt = trait.lastSeenAt.coerceAtLeast(0L),
            lastHitAt = trait.lastHitAt.coerceAtLeast(0L),
        )
    }
}
