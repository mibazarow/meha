package com.andharness.ru.domain.notifications

import kotlinx.serialization.Serializable

/** How much the notification digest shows (checklist §1: «только важное»). */

@Serializable
enum class SummaryMode {
    /** No captured notifications are listed (digest is honestly empty). */
    OFF,

    /** Only HIGH-priority notifications are listed. */
    IMPORTANT_ONLY,

    /** Everything captured (default; the pre-rules behaviour). */
    ALL,
}
