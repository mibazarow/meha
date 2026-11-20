package com.andharness.ru.domain.person

import java.util.Locale

/**
 * Pure policy of the messenger/mail notification ingestion (update.md §4.2/§4.4).
 *
 * Indexing messenger/e-mail via the notification listener is an **explicit opt-in** and is further
 * restricted to a user-editable package list; both rules live here so they can be unit-tested on the
 * JVM without Android.
 *
 * The same package check is also the honesty gate of the config tool: nothing is indexed while the
 * opt-in is off, and a notification from a package outside the list is ignored (its text is never
 * read into the personal base).
 */
object MessagingIndexPolicy {

    /**
     * Default opt-in package list: popular messengers, the common mail clients (update.md §4.2,
     * "по умолчанию — популярные мессенджеры и почтовые клиенты") and the usual SMS apps (BUG-006),
     * so an SMS notification can be indexed even when the direct `SmsReceiver` broadcast is
     * unavailable (e.g. RECEIVE_SMS not granted).
     */
    val DEFAULT_PACKAGES: Set<String> = setOf(
        // messengers
        "org.telegram.messenger",
        "org.thunderdog.challegram",
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.viber.voip",
        "com.vkontakte.android",
        "com.instagram.android",
        "com.facebook.orca",
        "org.thoughtcrime.securesms",
        // mail
        "com.google.android.gm",
        "com.microsoft.outlook",
        "com.yandex.mail",
        // SMS apps (BUG-006)
        "com.google.android.apps.messaging",
        "com.android.messaging",
        "com.samsung.android.messaging",
        "com.textra",
    )

    /** Packages treated as mail; everything else in the list is treated as a messenger. */
    val EMAIL_PACKAGES: Set<String> = setOf(
        "com.google.android.gm",
        "com.microsoft.outlook",
        "com.yandex.mail",
        "com.samsung.android.email.provider",
    )

    /**
     * SMS clients (BUG-006): their notifications are filed under [InteractionChannel.SMS] instead of
     * the generic MESSENGER channel. Signal (`org.thoughtcrime.securesms`) is deliberately NOT here:
     * it is an encrypted messenger, not an SMS client.
     */
    val SMS_PACKAGES: Set<String> = setOf(
        "com.google.android.apps.messaging",
        "com.android.messaging",
        "com.samsung.android.messaging",
        "com.textra",
    )

    /**
     * The honesty gate: a notification is only eligible when the user opted in ([enabled]) **and**
     * its [packageName] is in [allowedPackages].
     */
    fun isEligible(
        enabled: Boolean,
        packageName: String?,
        allowedPackages: Collection<String>
    ): Boolean {
        if (!enabled) return false
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return false
        return allowedPackages.any { it.trim().equals(pkg, ignoreCase = true) }
    }

    /**
     * Channel the event is filed under: [InteractionChannel.SMS] for SMS clients,
     * [InteractionChannel.EMAIL] for mail clients, else MESSENGER.
     */
    fun channelFor(packageName: String?): InteractionChannel = when {
        matches(packageName, SMS_PACKAGES) -> InteractionChannel.SMS
        matches(packageName, EMAIL_PACKAGES) -> InteractionChannel.EMAIL
        else -> InteractionChannel.MESSENGER
    }

    /**
     * Best-effort identity type for a notification party. A title that is itself a valid e-mail
     * address becomes an [IdentityType.EMAIL] handle; anything else (a conversation/sender name) is
     * an [IdentityType.MESSENGER] handle scoped to the app package.
     */
    fun identityTypeFor(title: String?): IdentityType =
        if (IdentityNormalizer.normalizeEmail(title) != null) IdentityType.EMAIL
        else IdentityType.MESSENGER

    /** Parses a comma/newline separated package list into a trimmed, de-duplicated set. */
    fun parsePackages(raw: String?): Set<String> = raw
        .orEmpty()
        .split(',', '\n', ';')
        .map { it.trim().lowercase(Locale.US) }
        .filter { it.isNotEmpty() }
        .toSet()

    private fun matches(packageName: String?, set: Set<String>): Boolean {
        val pkg = packageName?.trim().orEmpty()
        return pkg.isNotEmpty() && set.any { it.equals(pkg, ignoreCase = true) }
    }
}
