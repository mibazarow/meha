package com.andharness.ru.ui.permissions

/**
 * Decides whether to explain Android's "restricted settings" gate on the permissions screen.
 *
 * Since Android 13, the system refuses to enable Accessibility and Notification access for apps
 * installed from outside a store until the user explicitly allows restricted settings in the app's
 * info page. That block is invisible in our own list (the toggle simply looks "off"), so the screen
 * shows a short how-to exactly when it can be the cause — and never to store installs, where the
 * gate does not apply. Pure so the decision is JVM-tested.
 *
 * Reference: https://developer.android.com/guide/topics/permissions/overview#restricted-settings
 */
internal object RestrictedSettingsHint {

    /** Installers that Android treats as stores (so the restricted-settings gate does not apply). */
    private val TRUSTED_INSTALLERS = setOf(
        "com.android.vending",          // Google Play
        "com.huawei.appmarket",         // AppGallery
        "com.xiaomi.market",            // GetApps
        "com.sec.android.app.samsungapps",
        "com.amazon.venezia",
    )

    /**
     * @param sdkInt                device API level (33 = Android 13, where the gate appeared).
     * @param installerPackage      package that installed us (`getInstallSourceInfo`), null for ADB.
     * @param sensitiveAccessDenied `true` when Accessibility or Notification access is not granted.
     */
    fun shouldShow(
        sdkInt: Int,
        installerPackage: String?,
        sensitiveAccessDenied: Boolean,
    ): Boolean = sdkInt >= 33 &&
        sensitiveAccessDenied &&
        installerPackage !in TRUSTED_INSTALLERS
}
