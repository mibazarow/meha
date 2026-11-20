package com.andharness.ru.data.attachments

import com.andharness.ru.domain.chat.AttachmentKind

/** Metadata read from a content provider before the attachment is accepted. */
data class AttachmentInfo(
    val name: String,
    val mime: String,
    val size: Long,
    val kind: AttachmentKind
)
