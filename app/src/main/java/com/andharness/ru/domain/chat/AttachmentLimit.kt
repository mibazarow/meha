package com.andharness.ru.domain.chat


/** Outcome of the attachment limits check (§19.2/§19.4). */
sealed interface AttachmentLimit {
    /** The attachment may be added. */
    data object Ok : AttachmentLimit
    data class TooMany(val max: Int) : AttachmentLimit
    data class TooLarge(val maxBytes: Long) : AttachmentLimit
}
