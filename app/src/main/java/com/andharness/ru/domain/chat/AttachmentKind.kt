package com.andharness.ru.domain.chat


/**
 * Attachment domain model and the pure (JVM-testable) policy behind chat attachments (§19).
 *
 * Everything here is plain Kotlin — no Android APIs — so limits, mime classification, thumbnail
 * sampling and the message/prompt text can be unit-tested on the JVM.
 */

/** What an attachment fundamentally is, from the UX point of view (§19.2). */
enum class AttachmentKind { IMAGE, DOCUMENT, OTHER }
