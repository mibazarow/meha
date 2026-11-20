package com.andharness.ru.data.profile


/** Outcome of a [GlobalProfileStore.put], so the tool layer can report the real result honestly. */
enum class ProfileWriteOutcome {
    /** A brand-new fact was appended. */
    CREATED,

    /** An existing fact (matched by `id`) had its text changed. */
    UPDATED,

    /** The same text is already saved — nothing was written (dedupe). */
    DUPLICATE,

    /** `id` was given but no such fact exists. */
    NOT_FOUND,

    /** The text was blank or longer than [ProfileRules.MAX_TEXT_CHARS]. */
    INVALID,
}
