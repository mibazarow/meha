package com.andharness.ru.data.permission

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
import android.os.Environment
import android.os.Process
import android.provider.Settings
import android.view.autofill.AutofillManager
import dagger.hilt.android.qualifiers.ApplicationContext
import com.andharness.ru.data.update.AndroidInstallLauncher
import com.andharness.ru.domain.permission.DevicePermission
import com.andharness.ru.domain.permission.PermissionManager
import com.andharness.ru.domain.permission.PermissionRegistry
import com.andharness.ru.domain.permission.SpecialAccess
import com.andharness.ru.service.AccessibilityAutomationService
import com.andharness.ru.service.AndharnessDeviceAdminReceiver
import com.andharness.ru.service.NotificationRelayService
import androidx.core.content.ContextCompat
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PermissionManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : PermissionManager {

    override fun isGranted(permission: String?): Boolean {
        if (permission.isNullOrBlank()) return true
        // These are special app-op based permissions, NOT runtime permissions:
        // checkSelfPermission returns DENIED for them even when the special access is granted.
        return when (permission) {
            Manifest.permission.MANAGE_EXTERNAL_STORAGE -> Environment.isExternalStorageManager()
            Manifest.permission.WRITE_SETTINGS -> Settings.System.canWrite(context)
            SpecialAccess.ACCESSIBILITY -> AccessibilityAutomationService.isEnabled()
            SpecialAccess.NOTIFICATION_ACCESS -> NotificationRelayService.isEnabled()
            SpecialAccess.USAGE_ACCESS -> hasUsageAccess()
            SpecialAccess.DEVICE_ADMIN -> isDeviceAdmin()
            SpecialAccess.AUTOFILL -> isAutofillEnabled()
            // "Install unknown apps" is an app-op the user toggles in Settings; declaring
            // REQUEST_INSTALL_PACKAGES alone is not enough.
            // https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()
            SpecialAccess.INSTALL_UNKNOWN_APPS -> AndroidInstallLauncher.canRequestPackageInstalls(context)
            else -> try {
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            } catch (e: SecurityException) {
                false
            }
        }
    }

    private fun hasUsageAccess(): Boolean = try {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }

    private fun isDeviceAdmin(): Boolean = try {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        dpm.isAdminActive(ComponentName(context, AndharnessDeviceAdminReceiver::class.java))
    } catch (e: Exception) {
        false
    }

    /**
     * True when the OS currently uses ANDharness as its autofill service.
     * `hasEnabledAutofillServices()` reports whether the *calling* app is the enabled one (API 26+).
     * https://developer.android.com/reference/android/view/autofill/AutofillManager#hasEnabledAutofillServices()
     */
    private fun isAutofillEnabled(): Boolean = try {
        context.getSystemService(AutofillManager::class.java)
            ?.hasEnabledAutofillServices() == true
    } catch (e: Exception) {
        false
    }

    override fun all(): List<DevicePermission> = PermissionRegistry.all()
}
