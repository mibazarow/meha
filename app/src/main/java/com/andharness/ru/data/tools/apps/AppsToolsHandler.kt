package com.andharness.ru.data.tools.apps

import android.content.Context
import com.andharness.ru.domain.tools.ToolHandler
import okhttp3.OkHttpClient

/**
 * Builds one [ToolHandler] per app tool: [app_list], [app_launch], [app_uninstall],
 * [app_package_info], [app_permissions], [app_activities], [app_services], [app_receivers],
 * [role_status], [app_ops_self], [shortcut_pin], [widget_pin], [app_data_usage], [app_is_installed],
 * [app_store_open], [app_store_search], [app_backup_apk], [app_deep_link], [app_install_apk],
 * [app_suggest_for] — in their original registration order. This class is a thin coordinator; each
 * tool lives in its own handler class.
 *
 * The read-only metadata tools ([app_package_info], [app_permissions], [app_activities],
 * [app_services], [app_receivers], [role_status]) declare no `needsPermission` and are not
 * `dangerous`. They return an honest "not found / unavailable" message when package visibility
 * (`<queries>` / `QUERY_ALL_PACKAGES`) hides the package or when the platform is too old for an API.
 *
 * References:
 * - `PackageManager.getInstalledApplications(int flags)` returns a `List<ApplicationInfo>`,
 *   one per installed application for the current user:
 *   https://developer.android.com/reference/android/content/pm/PackageManager#getInstalledApplications(int)
 * - `PackageManager.getApplicationLabel(ApplicationInfo)` loads the app's display label:
 *   https://developer.android.com/reference/android/content/pm/PackageManager#getApplicationLabel(android.content.pm.ApplicationInfo)
 * - `PackageManager.getLaunchIntentForPackage(String packageName)` returns a "good" intent to
 *   launch a package's front-door activity, or `null` if the package has no such activity:
 *   https://developer.android.com/reference/android/content/pm/PackageManager#getLaunchIntentForPackage(java.lang.String)
 * - `Intent.ACTION_DELETE` with a `package:` URI launches the system uninstaller and shows a
 *   confirmation dialog:
 *   https://developer.android.com/reference/android/content/Intent#ACTION_DELETE
 * - `QUERY_ALL_PACKAGES` (declared in AndroidManifest.xml) grants visibility into all installed
 *   packages on Android 11+ (package visibility):
 *   https://developer.android.com/training/package-visibility#all-packages
 * - `PackageInfo.requestedPermissions` / `requestedPermissionsFlags` (populated by
 *   `PackageManager.GET_PERMISSIONS`), and `PackageInfo.REQUESTED_PERMISSION_GRANTED`:
 *   https://developer.android.com/reference/android/content/pm/PackageInfo
 * - `RoleManager.isRoleAvailable(String)` / `isRoleHeld(String)` (API 29+; `getRoleHolders` is
 *   `@SystemApi` and therefore not callable by ordinary apps):
 *   https://developer.android.com/reference/android/app/role/RoleManager
 * - `AppOpsManager.checkOpNoThrow` / `unsafeCheckOpNoThrow` read the app-op mode of an operation:
 *   https://developer.android.com/reference/android/app/AppOpsManager
 * - `ApplicationInfo.enabled`, `ApplicationInfo.sourceDir` (base APK path) and
 *   `ApplicationInfo.splitSourceDirs` (split APK paths, API 21+) are read for [app_is_installed]
 *   and [app_backup_apk]:
 *   https://developer.android.com/reference/android/content/pm/ApplicationInfo
 * - Implicit `ACTION_VIEW` intents to the `market://` scheme (Play Store) with a `https://`
 *   web fallback, and `ActivityNotFoundException` when no activity handles the scheme:
 *   https://developer.android.com/guide/components/intents-common
 * - `Uri.encode(String)` percent-encodes a Play search query:
 *   https://developer.android.com/reference/android/net/Uri
 * - [app_install_apk] prompts the system package installer and is always honest that the user must
 *   tap «Установить»:
 *   https://developer.android.com/reference/android/content/Intent#ACTION_INSTALL_PACKAGE
 *   https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()
 *   https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_UNKNOWN_APP_SOURCES
 */
class AppsToolsHandler(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
) {

    fun handlers(): List<ToolHandler> {
        val inspector = AppPackageInspector()
        val store = AppStoreLauncher(context)
        val ops = AppOpsAccess(context)
        return listOf(
            AppListToolHandler(context),
            AppLaunchToolHandler(context),
            AppUninstallToolHandler(context),
            AppDeepLinkToolHandler(context),
            AppInstallApkToolHandler(context, okHttpClient),
            AppIsInstalledToolHandler(context, inspector),
            AppStoreOpenToolHandler(store),
            AppStoreSearchToolHandler(store),
            AppSuggestForToolHandler(context),
            AppBackupApkToolHandler(context, inspector),
            AppPackageInfoToolHandler(context, inspector),
            AppPermissionsToolHandler(context, inspector),
            AppActivitiesToolHandler(context, inspector),
            AppServicesToolHandler(context, inspector),
            AppReceiversToolHandler(context, inspector),
            RoleStatusToolHandler(context),
            AppOpsSelfToolHandler(context, ops),
            ShortcutPinToolHandler(context),
            WidgetPinToolHandler(context),
            AppDataUsageToolHandler(context, ops, inspector),
        )
    }
}
