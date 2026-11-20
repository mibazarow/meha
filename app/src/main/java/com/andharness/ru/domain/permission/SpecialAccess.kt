package com.andharness.ru.domain.permission

/**
 * Pseudo-permission tokens for Android "special access" grants that cannot be requested through the
 * normal runtime-permission dialog (they need the user to toggle something in system Settings).
 * Tools return `PERMISSION_REQUIRED:<token>` so the chat can open the right screen; once the user
 * grants it, the grant is remembered by the OS for all future calls.
 */
object SpecialAccess {
    const val ACCESSIBILITY = "special:accessibility"
    const val NOTIFICATION_ACCESS = "special:notification_access"
    const val USAGE_ACCESS = "special:usage_access"
    const val DEVICE_ADMIN = "special:device_admin"

    /**
     * Autofill service selection (backlog #15): the user must pick ANDharness in the system autofill
     * picker. `Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE` (API 26+) opens that screen.
     * https://developer.android.com/reference/android/provider/Settings#ACTION_REQUEST_SET_AUTOFILL_SERVICE
     */
    const val AUTOFILL = "special:autofill"

    /**
     * Draw-over-other-apps access for the floating assistant («Плавающий помощник»). Granted by the
     * user in system Settings; checked at runtime with `Settings.canDrawOverlays`.
     * https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_OVERLAY_PERMISSION
     */
    const val OVERLAY = "special:overlay"

    /**
     * Permission to install unknown apps (`app_install_apk`). Declaring
     * `REQUEST_INSTALL_PACKAGES` in the manifest is not enough: the user must also allow it in
     * system Settings. Checked with `PackageManager.canRequestPackageInstalls()` and granted through
     * `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`.
     * https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()
     * https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_UNKNOWN_APP_SOURCES
     */
    const val INSTALL_UNKNOWN_APPS = "special:install_unknown_apps"

    /**
     * The manifest/special permissions handled by the existing settings-screen flow. Values come
     * from [PermissionNames] (byte-identical to the `android.Manifest.permission` constants,
     * verified against android.jar) so the domain layer stays free of Android framework imports.
     */
    const val MANAGE_EXTERNAL_STORAGE = PermissionNames.MANAGE_EXTERNAL_STORAGE
    const val WRITE_SETTINGS = PermissionNames.WRITE_SETTINGS

    private val TOKENS = setOf(
        ACCESSIBILITY,
        NOTIFICATION_ACCESS,
        USAGE_ACCESS,
        DEVICE_ADMIN,
        AUTOFILL,
        INSTALL_UNKNOWN_APPS,
    )

    /** Builds the tool-result marker the orchestrator recognizes as "permission required". */
    fun required(token: String): String = "PERMISSION_REQUIRED:$token"

    fun isToken(permission: String): Boolean = permission in TOKENS

    /** True for both the pseudo-tokens above and the manifest-level special permissions. */
    fun isSpecial(permission: String): Boolean =
        isToken(permission) ||
            permission == MANAGE_EXTERNAL_STORAGE ||
            permission == WRITE_SETTINGS
}
