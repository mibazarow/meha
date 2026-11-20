package com.andharness.ru.domain.usage


/** The kind of automation a suggestion proposes. */
enum class SuggestionKind {
    /** "<app> is opened around <hour>" → a `time` trigger with a reminder notification. */
    APP_USAGE,

    /** "the phone is put away around <hour>" → a bedtime / Do-Not-Disturb reminder. */
    QUIET_NIGHT
}
