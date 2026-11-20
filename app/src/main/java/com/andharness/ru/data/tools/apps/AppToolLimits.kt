package com.andharness.ru.data.tools.apps

import android.app.AppOpsManager

/** Shared numeric/size limits and the default app-op set of the app tools. */
internal object AppToolLimits {
    const val MAX_COMPONENTS = 60

    /**
     * Sub-directory of the app cache where `app_install_apk` parks a downloaded APK before handing
     * it to the system installer. Covered by the shared FileProvider's `<cache-path …>` root.
     */
    const val APK_CACHE_DIR = "apk_install"

    /** Copy buffer used while streaming a downloaded APK to disk. */
    const val COPY_BUFFER_SIZE = 8 * 1024

    /**
     * Default destination folder for `app_backup_apk`, relative to the effective tool root (public
     * storage when "all files access" is granted, otherwise the app's external files dir). It is
     * resolved through `resolveSafePath`, like every other file path argument.
     */
    const val DEFAULT_BACKUP_DIR = "Download/apk_backup"

    /** Default / maximum look-back window (days) for `app_data_usage`. */
    const val DEFAULT_TRAFFIC_DAYS = 7
    const val MAX_TRAFFIC_DAYS = 90

    /**
     * A useful set of app-ops reported by `app_ops_self` when no explicit `op` is given. These map
     * 1:1 to the runtime permissions / special accesses this app actually uses.
     * https://developer.android.com/reference/android/app/AppOpsManager
     */
    val SELF_APP_OPS = listOf(
        AppOpsManager.OPSTR_COARSE_LOCATION,
        AppOpsManager.OPSTR_FINE_LOCATION,
        AppOpsManager.OPSTR_CAMERA,
        AppOpsManager.OPSTR_RECORD_AUDIO,
        AppOpsManager.OPSTR_READ_CONTACTS,
        AppOpsManager.OPSTR_WRITE_CONTACTS,
        AppOpsManager.OPSTR_READ_SMS,
        AppOpsManager.OPSTR_SEND_SMS,
        AppOpsManager.OPSTR_RECEIVE_SMS,
        AppOpsManager.OPSTR_CALL_PHONE,
        AppOpsManager.OPSTR_READ_CALL_LOG,
        AppOpsManager.OPSTR_WRITE_CALL_LOG,
        AppOpsManager.OPSTR_READ_CALENDAR,
        AppOpsManager.OPSTR_WRITE_CALENDAR,
        AppOpsManager.OPSTR_READ_EXTERNAL_STORAGE,
        AppOpsManager.OPSTR_WRITE_EXTERNAL_STORAGE,
        AppOpsManager.OPSTR_READ_PHONE_STATE,
        AppOpsManager.OPSTR_WRITE_SETTINGS,
        AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
        AppOpsManager.OPSTR_GET_USAGE_STATS,
    )
}
