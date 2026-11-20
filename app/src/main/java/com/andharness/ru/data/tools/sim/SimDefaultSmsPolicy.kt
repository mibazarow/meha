package com.andharness.ru.data.tools.sim

/**
 * Pure, Android-free decision logic for `sim_set_default_sms`.
 *
 * Honesty contract: changing the default SMS subscription is NOT available to a normal app.
 * `SubscriptionManager.setDefaultSmsSubId(int)` is `@SystemApi` and annotated
 * `@RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)`, a signature|privileged
 * permission that third-party apps cannot hold (the same applies to `setDefaultDataSubId` and
 * `setDefaultVoiceSubId`):
 * https://developer.android.com/reference/android/telephony/SubscriptionManager#setDefaultSmsSubId(int)
 *
 * Being the default SMS app (`RoleManager.ROLE_SMS` / `Telephony.Sms.getDefaultSmsPackage`) grants
 * the ability to *send* SMS, but no API to change the system default subscription. This class
 * therefore NEVER produces a success path: it only validates the id and explains the missing
 * privilege. The Android handler is expected to return `ToolResult(success = false, ...)`.
 */
object SimDefaultSmsPolicy {

    /** Signature|privileged permission that guards the (system-only) default-SIM setters. */
    const val MISSING_PRIVILEGE = "android.permission.MODIFY_PHONE_STATE"

    /** `SubscriptionManager.INVALID_SUBSCRIPTION_ID`. */
    const val INVALID_SUBSCRIPTION_ID = -1

    /** Parses a user-supplied subscription id; null when absent, non-numeric or invalid. */
    fun parseSubscriptionId(raw: String?): Int? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: return null
        return value.takeIf { it != INVALID_SUBSCRIPTION_ID && it >= 0 }
    }

    fun invalidIdMessage(): String =
        "Некорректный subscription_id. Укажите числовой id из sim_slots_list."

    fun unknownIdMessage(id: Int): String =
        "Подписка id=$id не найдена среди активных SIM — менять нечего."

    /**
     * Honest refusal for a well-formed, existing subscription. Names the missing privilege and
     * explicitly says the default-SMS-app role does not help.
     */
    fun refusalMessage(id: Int, isDefaultSmsApp: Boolean): String {
        val roleNote =
            if (isDefaultSmsApp) {
                "Приложение сейчас является SMS-приложением по умолчанию, но и это не даёт права " +
                    "менять SIM по умолчанию: роль позволяет отправлять SMS, но не менять подписку."
            } else {
                "Роль SMS-приложения по умолчанию такого права тоже не даёт."
            }
        return "Смена SIM по умолчанию для SMS (id=$id) недоступна обычному приложению: " +
            "публичного API нет, требуется системная привилегия $MISSING_PRIVILEGE " +
            "(signature|privileged). $roleNote Пользователь может сменить её вручную в системных " +
            "настройках. Инструмент НЕ выполняет изменение и не сообщает об успехе."
    }
}
