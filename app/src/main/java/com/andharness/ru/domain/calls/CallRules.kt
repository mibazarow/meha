package com.andharness.ru.domain.calls

import kotlinx.serialization.Serializable

/**
 * The complete call/SMS auto-handling rule set.
 *
 * **The defaults are a strict no-op**: [enabled] is `false`, so with a fresh/no file the call
 * screener allows every call (its historical behaviour) and no SMS is ever sent automatically.
 */

@Serializable
data class CallRules(
    /** Master opt-in. `false` = nothing is auto-handled (default). */
    val enabled: Boolean = false,

    /** Reject calls whose number does not resolve to a contact (spam/unknown). */
    val blockUnknown: Boolean = false,

    /** Numbers that are always rejected (unless also allowlisted — the allowlist wins). */
    val blockedNumbers: List<String> = emptyList(),

    /** Numbers that always pass (never rejected, never auto-replied to). The allowlist wins. */
    val allowNumbers: List<String> = emptyList(),

    /** Send [autoReplyText] to an unknown caller/message sender. */
    val autoReplySms: Boolean = false,

    /** The text sent by the auto-reply. Bounded by [CallRulesCodec.MAX_REPLY_CHARS]. */
    val autoReplyText: String = DEFAULT_AUTO_REPLY_TEXT,

    /** Send [autoReplyText] while the device is already in a call (busy). */
    val autoReplyWhenBusy: Boolean = false,

    /** When a call from a known contact is rejected/missed, create a one-shot call-back reminder. */
    val callBackReminder: Boolean = false,
) {

    companion object {
        /** Shown for a fresh rule set; the user can change it with `call_rules_set`. */
        const val DEFAULT_AUTO_REPLY_TEXT =
            "Я сейчас не могу ответить и перезвоню, как только смогу."
    }
}
