package com.andharness.ru.ui.chats


/**
 * The single transient status line shown for the current chat turn.
 *
 * Precedence: context compaction > tool activity > streaming. The compaction notice is an
 * in-memory UI status only: it appears while a request is being prepared with a compacted context,
 * disappears as soon as real output arrives, and is NEVER persisted as a chat message (the summary
 * text itself is model context and is never shown to the user).
 *
 * Kept pure and free of Compose so the precedence and the transience can be unit-tested.
 */
internal enum class ChatStatusKind {
    /** Context is being compacted before the request is sent. */
    COMPACTION,

    /** A tool call is in flight. */
    TOOL_RUNNING,

    /** A tool call finished successfully. */
    TOOL_SUCCEEDED,

    /** A tool call failed. */
    TOOL_FAILED,

    /** The model is producing internal reasoning (shown as «Меха думает…», text hidden). */
    THINKING,

    /** The assistant is preparing the answer (shown as «Меха печатает…», no partial text). */
    STREAMING,

    /** The provider has been silent for a while; the line suggests checking balance/key/limits. */
    PROVIDER_SLOW,
}
