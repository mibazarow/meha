package com.andharness.ru.domain.sms


/** What the engine concluded for one inbound SMS. */
enum class SmsPhotoDecision {
    /** Do nothing (disabled, untrusted, ineligible, no command, or cooling down). */
    IGNORE,

    /** Take a photo and reply to the sender with the analysis. */
    REPORT,
}
