package com.andharness.ru.domain.style

import kotlinx.serialization.Serializable

/**
 * One axis of the user's communication style. Only form is described here — never content,
 * opinions or facts — so the learned profile can be injected into every chat without leaking
 * what the user talked about.
 */
@Serializable
enum class StyleTraitKey {
    /** Informal vs formal address (ты / вы). */
    ADDRESS,

    /** Prefers short messages rather than long explanations. */
    BREVITY,

    /** Uses colloquialisms and slang. */
    SLANG,

    /** Uses mild swearing as an intensifier, never as an insult. */
    PROFANITY,

    /** Uses emoji in messages. */
    EMOJI,

    /** Relaxed punctuation: lowercase starts, «!!!», ellipses, missing final punctuation. */
    PUNCTUATION,

    /** Prefers imperative, to-the-point requests. */
    DIRECTNESS,

    /** Jokes and irony; only ever inferred by the LLM style card, not by counters. */
    HUMOR,
}
