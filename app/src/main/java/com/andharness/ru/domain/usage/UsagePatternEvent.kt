package com.andharness.ru.domain.usage


/**
 * One normalised usage event.
 *
 * [endTime] is the moment the app left the foreground, or `0`/`startTime` when the reader could not
 * pair a resume with a pause — such an event is still counted, only the duration-based noise filter
 * is skipped for it.
 */
data class UsagePatternEvent(
    val packageName: String,
    val startTime: Long,
    val endTime: Long,
    val kind: UsageEventKind = UsageEventKind.APP_FOREGROUND
)
