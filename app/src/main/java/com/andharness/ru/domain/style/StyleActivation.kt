package com.andharness.ru.domain.style

/**
 * Gates that decide when a learned trait is strong and repeated enough to influence the prompt.
 *
 * The thresholds deliberately require recurrence across distinct days, not just many messages in
 * one conversation: a single emotional evening must never reshape the assistant's persona. Every
 * threshold is public so the reducer's tests and the renderer share one source of truth.
 */
object StyleActivation {

    /** Minimum measurable messages for most traits. */
    const val MIN_OBSERVATIONS = 8

    /** Minimum distinct days for most traits. */
    const val MIN_DAYS = 2

    /** Address: share of the dominant form («ты» or «вы»). */
    const val ADDRESS_MIN_SHARE = 0.65

    /** Brevity: share of messages at or below [StyleAnalyzer.SHORT_MESSAGE_CHARS]. */
    const val BREVITY_MIN_SHARE = 0.6
    const val BREVITY_MIN_OBSERVATIONS = 10

    /** Slang: share of messages containing a slang token. */
    const val SLANG_MIN_SHARE = 0.15
    const val SLANG_MIN_DAYS = 3

    /** Profanity: share of messages with at least one swear hit. */
    const val PROFANITY_MIN_SHARE = 0.08
    const val PROFANITY_MIN_HIT_MESSAGES = 5
    const val PROFANITY_MIN_DAYS = 4

    /** Emoji: share of messages containing an emoji. */
    const val EMOJI_MIN_SHARE = 0.25

    /** Relaxed punctuation: share of messages with informal punctuation. */
    const val PUNCTUATION_MIN_SHARE = 0.3
    const val PUNCTUATION_MIN_OBSERVATIONS = 10

    /** Directness: share of imperative requests. */
    const val DIRECTNESS_MIN_SHARE = 0.4

    /** Traits that only the LLM style card may introduce. */
    const val HUMOR_MIN_VALUE = 0.5

    /** `true` when [trait] may be rendered into the prompt. */
    fun isActive(trait: StyleTrait): Boolean = when (trait.key) {
        StyleTraitKey.ADDRESS ->
            maxOf(trait.value, 1.0 - trait.value) >= ADDRESS_MIN_SHARE &&
                trait.observations >= MIN_OBSERVATIONS &&
                trait.dayKeys.size >= MIN_DAYS

        StyleTraitKey.BREVITY ->
            trait.value >= BREVITY_MIN_SHARE &&
                trait.observations >= BREVITY_MIN_OBSERVATIONS &&
                trait.dayKeys.size >= MIN_DAYS

        StyleTraitKey.SLANG ->
            trait.value >= SLANG_MIN_SHARE &&
                trait.observations >= MIN_OBSERVATIONS &&
                trait.dayKeys.size >= SLANG_MIN_DAYS

        StyleTraitKey.PROFANITY ->
            trait.value >= PROFANITY_MIN_SHARE &&
                trait.hitMessages >= PROFANITY_MIN_HIT_MESSAGES &&
                trait.dayKeys.size >= PROFANITY_MIN_DAYS

        StyleTraitKey.EMOJI ->
            trait.value >= EMOJI_MIN_SHARE &&
                trait.observations >= MIN_OBSERVATIONS &&
                trait.dayKeys.size >= MIN_DAYS

        StyleTraitKey.PUNCTUATION ->
            trait.value >= PUNCTUATION_MIN_SHARE &&
                trait.observations >= PUNCTUATION_MIN_OBSERVATIONS &&
                trait.dayKeys.size >= MIN_DAYS

        StyleTraitKey.DIRECTNESS ->
            trait.value >= DIRECTNESS_MIN_SHARE &&
                trait.observations >= MIN_OBSERVATIONS &&
                trait.dayKeys.size >= MIN_DAYS

        StyleTraitKey.HUMOR ->
            trait.value >= HUMOR_MIN_VALUE && trait.observations >= 1
    }

    /** The dominant address form, once [StyleTraitKey.ADDRESS] is active. */
    fun addressForm(trait: StyleTrait): AddressForm =
        if (trait.value >= 0.5) AddressForm.TY else AddressForm.VY

    /**
     * Active traits in render priority order, capped to [limit].
     *
     * Address and brevity shape the most visible edges of the persona, so they come first; humor is
     * last because it is the easiest to overdo.
     */
    fun activeTraits(profile: StyleProfile, limit: Int = 3): List<StyleTrait> {
        if (limit <= 0) return emptyList()
        val order = listOf(
            StyleTraitKey.ADDRESS,
            StyleTraitKey.BREVITY,
            StyleTraitKey.PROFANITY,
            StyleTraitKey.SLANG,
            StyleTraitKey.EMOJI,
            StyleTraitKey.PUNCTUATION,
            StyleTraitKey.DIRECTNESS,
            StyleTraitKey.HUMOR,
        )
        val byKey = profile.traits.associateBy { it.key }
        return order.asSequence()
            .mapNotNull { byKey[it] }
            .filter { isActive(it) }
            .take(limit)
            .toList()
    }
}
