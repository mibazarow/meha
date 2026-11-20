package com.andharness.ru.data.message


/**
 * Honest outcome of one delivery attempt.
 *
 * - [Sent] — the platform **confirmed** the hand-off (SMS SENT broadcast `RESULT_OK`);
 * - [Opened] — a messenger was opened with the text pre-filled, but nothing was delivered and no
 *   delivery can be confirmed (update.md §4.7);
 * - [Failed] — the attempt did not go through; [reason] is the real, reported cause.
 */
sealed interface MessageSendOutcome {

    data class Sent(val detail: String) : MessageSendOutcome

    data class Opened(val detail: String) : MessageSendOutcome

    data class Failed(val reason: String) : MessageSendOutcome
}
