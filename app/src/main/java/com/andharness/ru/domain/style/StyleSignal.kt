package com.andharness.ru.domain.style

/**
 * Measurements of ONE user message. Produced by [StyleAnalyzer] and folded into a [StyleProfile]
 * by the reducer; this type itself is never persisted.
 *
 * [profanityHits] is supplied by the caller (the profanity lexicon lives in its own component), so
 * the analyzer stays free of word lists for that axis.
 */
data class StyleSignal(
    val hasContent: Boolean,
    val length: Int,
    val address: AddressForm?,
    val hasEmoji: Boolean,
    val hasSlang: Boolean,
    val profanityHits: Int,
    val informalPunctuation: Boolean,
    val isDirective: Boolean,
) {
    companion object {
        /** The neutral signal for a blank/whitespace-only message. */
        val EMPTY = StyleSignal(
            hasContent = false,
            length = 0,
            address = null,
            hasEmoji = false,
            hasSlang = false,
            profanityHits = 0,
            informalPunctuation = false,
            isDirective = false,
        )
    }
}
