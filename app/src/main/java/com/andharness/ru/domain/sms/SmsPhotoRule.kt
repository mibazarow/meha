package com.andharness.ru.domain.sms

import kotlinx.serialization.Serializable

/**
 * Trusted-SMS photo report: when a **trusted** number sends a photo command ("сделай фото",
 * "что на фото", "take a photo", …), the app takes a photo and replies by SMS with a description
 * (or an honest OCR fallback when the configured model has no vision).
 *
 * The model is deliberately **Android-free** (only `kotlinx.serialization`), so the command matcher,
 * the trusted/cooldown decision and the codec are exercised on the JVM; the Android layer only
 * performs the camera capture, the analysis and the SMS send.
 *
 * ## Safety model (why this is safe to ship)
 * - **Opt-in**: [SmsPhotoRule.enabled] defaults to `false`; with an empty/missing file nothing is
 *   captured and no SMS is ever sent.
 * - **Trusted-only**: a reply is considered only for a number present in the trusted-source store
 *   ([decide] receives `senderTrusted`), never for an arbitrary sender.
 * - **Never service/short codes**: [isEligibleSender] rejects alphanumeric sender ids, short codes,
 *   emergency and service (8-800) numbers even if one were mistakenly trusted.
 * - **Cooldown**: at most one reply per number per [SmsPhotoRule.cooldownSeconds] (default 60 s), so a
 *   duplicated/looping SMS can never answer itself in a loop.
 *
 * ## Platform references
 * - SMS sending and the premium/short-code restriction enforced by the platform:
 *   https://developer.android.com/reference/android/telephony/SmsManager#sendTextMessage(java.lang.String,%20java.lang.String,%20java.lang.String,%20android.app.PendingIntent,%20android.content.Intent)
 * - `Telephony.Sms.Intents.SMS_RECEIVED_ACTION` (the inbound path this rule hooks into):
 *   https://developer.android.com/reference/android/provider/Telephony.Sms.Intents#SMS_RECEIVED_ACTION
 */

@Serializable
data class SmsPhotoRule(
    /** Master opt-in. `false` = nothing is captured or replied to (default). */
    val enabled: Boolean = false,

    /** Text prepended to the reply so the sender knows what the message is. Bounded by the codec. */
    val replyTextPrefix: String = DEFAULT_REPLY_PREFIX,

    /** Per-number cooldown in seconds; `0` disables it. Bounded by the codec. */
    val cooldownSeconds: Int = DEFAULT_COOLDOWN_SECONDS,
) {
    companion object {
        /** Default reply prefix; a human stays able to tell an automatic report apart. */
        const val DEFAULT_REPLY_PREFIX = "Фото: "

        /** Default per-number cooldown: 60 seconds. */
        const val DEFAULT_COOLDOWN_SECONDS = 60
    }
}
