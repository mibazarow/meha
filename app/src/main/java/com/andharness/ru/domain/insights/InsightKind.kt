package com.andharness.ru.domain.insights


/** Which local signal an [Insight] was computed from. */
enum class InsightKind(val token: String) {
    /** A tool that recurs on a weekday/time pattern in the audit journal. */
    ROUTINE("routine"),

    /** A saved skill whose `lastUsedAt`/`createdAt` shows it is no longer used. */
    STALE_SKILL("stale_skill"),

    /** The app that dominates the captured-notification buffer. */
    NOTIFICATION_LOAD("notification_load"),
    ;

    companion object {
        /** Tokens accepted by the `insights_list` `kind` parameter, in display order. */
        val tokens: List<String> = listOf(ROUTINE.token, STALE_SKILL.token, NOTIFICATION_LOAD.token)

        /**
         * Parses a `kind` argument. Accepts the canonical tokens (case-insensitive, `-` treated as
         * `_`) plus a couple of Russian synonyms; returns `null` for anything unknown so the tool can
         * answer honestly instead of silently ignoring the filter.
         */
        fun fromToken(raw: String?): InsightKind? =
            when (raw?.trim()?.lowercase()?.replace('-', '_')) {
                "routine", "рутина" -> ROUTINE
                "stale_skill", "skill", "навык" -> STALE_SKILL
                "notification_load", "notifications", "уведомления" -> NOTIFICATION_LOAD
                else -> null
            }
    }
}
