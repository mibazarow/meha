package com.andharness.ru.data.tools.calls

/**
 * Pure, Android-free decision logic for `ussd_request` (BUG-056).
 *
 * On Android 11+ `TelephonyManager.sendUssdRequest` is, in practice, only usable by the default
 * dialer; for any other app the network's USSD reply is not delivered and the call may simply time
 * out. This object decides, without touching the platform, whether a direct USSD attempt is even
 * worth making and how to phrase the honest fallback (open the dialer pre-filled with the code —
 * `ACTION_DIAL`, never `ACTION_CALL`).
 *
 * Keeping it plain Kotlin makes the "allowed vs. not-default-dialer" decision and the URI encoding
 * unit-testable on the JVM.
 *
 * References:
 * - https://developer.android.com/reference/android/telephony/TelephonyManager#sendUssdRequest(java.lang.String,%20android.telephony.TelephonyManager.UssdResponseCallback,%20android.os.Handler)
 * - https://developer.android.com/reference/android/telecom/TelecomManager#getDefaultDialerPackage()
 * - https://developer.android.com/guide/components/intents-actions#Dial_a_phone_number
 *   (`ACTION_DIAL` with a `tel:` URI only opens the dialer; the user presses the call button)
 */
object UssdFallbackPlanner {

    /** Why a direct USSD send was not attempted / did not produce a reply. */
    enum class Reason { NOT_DEFAULT_DIALER, TIMEOUT, FAILED }

    /** What `ussd_request` should do for [code]. */
    sealed interface Plan {
        /** The app is the default dialer — a direct `sendUssdRequest` may work. */
        data class Direct(val code: String) : Plan

        /** A direct send is pointless/failed — open [dialUri] pre-filled and let the user read it. */
        data class DialFallback(val dialUri: String, val message: String) : Plan
    }

    const val DEFAULT_DIALER_EXPLANATION =
        "Прямой USSD (TelephonyManager.sendUssdRequest) на Android 11+ фактически доступен только " +
            "приложению-дозвонщику по умолчанию, а Меха им не является, поэтому ответ сети " +
            "приложению не приходит."

    /**
     * Decide whether to attempt `sendUssdRequest` directly. Without the default-dialer role the
     * direct attempt is not made at all — the honest fallback is returned instead.
     */
    fun plan(code: String, isDefaultDialer: Boolean): Plan =
        if (isDefaultDialer) {
            Plan.Direct(code)
        } else {
            fallback(code, Reason.NOT_DEFAULT_DIALER)
        }

    /** The honest fallback for a failed/timed-out direct attempt. */
    fun fallback(code: String, reason: Reason): Plan.DialFallback =
        Plan.DialFallback(dialUri(code), message(code, reason))

    /**
     * A `tel:` URI safe for `ACTION_DIAL`: `#` (the URI fragment delimiter) and `%` are
     * percent-encoded so the dialer receives the whole code (e.g. `*100#` -> `tel:*100%23`).
     */
    fun dialUri(code: String): String = "tel:" + encode(code)

    fun encode(code: String): String = buildString {
        code.forEach { c ->
            when (c) {
                '#' -> append("%23")
                '%' -> append("%25")
                ' ' -> append("%20")
                else -> append(c)
            }
        }
    }

    /** The user-facing explanation of why no automatic result exists, plus how to get it manually. */
    fun message(code: String, reason: Reason): String = buildString {
        append("USSD автоматически не выполнен: ")
        append(
            when (reason) {
                Reason.NOT_DEFAULT_DIALER -> DEFAULT_DIALER_EXPLANATION
                Reason.TIMEOUT ->
                    "ответ сети не получен за отведённое время (на Android 11+ ответ USSD " +
                        "стороннему приложению обычно не приходит)."
                Reason.FAILED -> "сеть или платформа вернули ошибку."
            }
        )
        append(" Фолбэк: наберите ").append(code)
            .append(" вручную в приложении «Телефон» и прочитайте ответ сети; ")
            .append("дозвонщик открывается с готовым кодом ").append(dialUri(code))
            .append(". Звонок не совершается автоматически — нажмите «Вызов» сами.")
    }
}
