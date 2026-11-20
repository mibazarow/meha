package com.andharness.ru.domain.tools

object CallStartPlanner {

    /**
     * Numbers that a normal app cannot silently dial: the platform routes them through the
     * preloaded dialer and shows a confirmation. Kept deliberately small and international; it is
     * only used to add an honest caveat, never to refuse placing the call.
     */
    private val EMERGENCY_NUMBERS = setOf(
        "112", "911", "999", "000", "102", "103", "104", "108", "110", "118", "119",
    )

    fun plan(rawPhone: String?, hasCallPhonePermission: Boolean): CallStartPlan {
        val number = rawPhone?.trim().orEmpty()
        if (number.isEmpty()) return CallStartPlan.BlankNumber
        if (!hasCallPhonePermission) return CallStartPlan.NeedsPermission
        return CallStartPlan.Place(number, isEmergency(number))
    }

    /** Compares digit-only, so `+7 999 123-45-67` and `79991234567` normalise the same way. */
    fun normalizeDigits(number: String): String = number.filter { it.isDigit() || it == '+' }

    fun isEmergency(number: String): Boolean = normalizeDigits(number) in EMERGENCY_NUMBERS

    /**
     * Honest success wording: says exactly which API placed the call and, for emergency numbers,
     * that the system may still add a confirmation. It never claims the call was answered.
     */
    fun successMessage(number: String, api: CallApi, emergency: Boolean): String = buildString {
        append("Звонок на $number размещён через ${api.label} (передан телефонной службе).")
        if (emergency) {
            append(" Экстренный номер: система может показать дополнительное подтверждение.")
        }
    }

    /** Honest failure wording, including the real exception when there was one. */
    fun failureMessage(number: String, api: CallApi, detail: String?): String {
        val reason = detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
        return "Не удалось разместить звонок на $number через ${api.label}$reason"
    }
}
