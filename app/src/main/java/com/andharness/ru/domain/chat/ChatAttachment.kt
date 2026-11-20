package com.andharness.ru.domain.chat


/**
 * An attachment that has already been copied to a stable, tool-readable path and is about to be
 * handed to the agent together with the user message.
 */
data class ChatAttachment(
    val path: String,
    val name: String,
    val mime: String,
    val kind: AttachmentKind
)
