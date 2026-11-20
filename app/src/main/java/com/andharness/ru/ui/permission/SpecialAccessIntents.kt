package com.andharness.ru.ui.permission

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.andharness.ru.data.update.AndroidInstallLauncher
import com.andharness.ru.domain.permission.SpecialAccess
import com.andharness.ru.service.AndharnessDeviceAdminReceiver

/**
 * Single source of truth for the system-settings screen each «special access» token maps to.
 *
 * Tools request a grant by returning `PERMISSION_REQUIRED:<token>`; the UI turns that token into the
 * matching Settings intent here, so the chat and «Подсказки» open the exact same screen.
 *
 * Official references for the actions used:
 * - All-files access — https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
 * - Modify system settings — https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_WRITE_SETTINGS
 * - Accessibility — https://developer.android.com/reference/android/provider/Settings#ACTION_ACCESSIBILITY_SETTINGS
 * - Notification listener — https://developer.android.com/reference/android/provider/Settings#ACTION_NOTIFICATION_LISTENER_SETTINGS
 * - Usage access — https://developer.android.com/reference/android/provider/Settings#ACTION_USAGE_ACCESS_SETTINGS
 * - Device admin — https://developer.android.com/reference/android/app/admin/DevicePolicyManager#ACTION_ADD_DEVICE_ADMIN
 * - Autofill service — https://developer.android.com/reference/android/provider/Settings#ACTION_REQUEST_SET_AUTOFILL_SERVICE
 * - Draw over other apps (`SYSTEM_ALERT_WINDOW`) —
 *   https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_OVERLAY_PERMISSION
 * - Install unknown apps (`REQUEST_INSTALL_PACKAGES`, `app_install_apk`) —
 *   https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_UNKNOWN_APP_SOURCES
 */
object SpecialAccessIntents {

    /** Returns the system settings Intent for a special access token, or null if there is none. */
    fun intentFor(context: Context, token: String): Intent? = when (token) {
        SpecialAccess.MANAGE_EXTERNAL_STORAGE ->
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        SpecialAccess.WRITE_SETTINGS ->
            Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        SpecialAccess.ACCESSIBILITY ->
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        SpecialAccess.NOTIFICATION_ACCESS ->
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        SpecialAccess.USAGE_ACCESS ->
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        SpecialAccess.DEVICE_ADMIN ->
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(
                    DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    ComponentName(context, AndharnessDeviceAdminReceiver::class.java)
                )
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Нужно, чтобы ассистент мог блокировать экран."
                )
            }
        SpecialAccess.AUTOFILL ->
            Intent(
                Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE,
                Uri.parse("package:${context.packageName}")
            )
        SpecialAccess.OVERLAY ->
            // Since Android 11 the system ignores the `package:` data and opens the top-level
            // "Display over other apps" list, where the user picks Меха themselves.
            // https://developer.android.com/about/versions/11/privacy/permissions
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        SpecialAccess.INSTALL_UNKNOWN_APPS ->
            // Reuse the single owner of the install-unknown-apps intent (package-scoped where the
            // device supports it) instead of duplicating the action here.
            AndroidInstallLauncher.unknownSourcesIntent(context)
        else -> null
    }
}
