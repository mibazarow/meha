package com.andharness.ru.data.tools.connectivity

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.os.Process

/**
 * Permission checks and Wi-Fi Direct channel setup shared by the connectivity tools.
 *
 * `PACKAGE_USAGE_STATS` is a special access, not a runtime permission: it is declared in the
 * manifest AND granted by the user in Settings. Verified through
 * [AppOpsManager.OPSTR_GET_USAGE_STATS].
 * https://developer.android.com/reference/android/app/AppOpsManager#OPSTR_GET_USAGE_STATS
 *
 * The Wi-Fi scan/connect/ranging APIs need `NEARBY_WIFI_DEVICES` on API 31+ and
 * `ACCESS_FINE_LOCATION` on API <= 30:
 * https://developer.android.com/develop/connectivity/wifi/wifi-permissions
 */
internal class ConnectivityAccess(private val context: Context) {

    /** True when the runtime permission is currently granted to this app. */
    fun hasPermission(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Whether the app holds the `PACKAGE_USAGE_STATS` special access. */
    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * The permission required by the Wi-Fi scan/connect/ranging APIs on this API level, or null when
     * it is already granted. Returned as a `PERMISSION_REQUIRED:<perm>` marker (see
     * [com.andharness.ru.domain.permission.SpecialAccess]).
     */
    fun nearbyWifiPermissionError(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (hasPermission(Manifest.permission.NEARBY_WIFI_DEVICES)) {
                null
            } else {
                "PERMISSION_REQUIRED:${Manifest.permission.NEARBY_WIFI_DEVICES}"
            }
        } else if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            null
        } else {
            "PERMISSION_REQUIRED:${Manifest.permission.ACCESS_FINE_LOCATION}"
        }

    /**
     * Initializes a fresh `WifiP2pManager` channel. P2P callbacks are delivered on the channel's
     * Looper, so the main looper is used. Returns null when Wi-Fi Direct is not available or the
     * app lacks the required permission.
     * https://developer.android.com/develop/connectivity/wifi/wifi-direct
     */
    fun openP2pChannel(manager: WifiP2pManager): WifiP2pManager.Channel? =
        try {
            manager.initialize(context.applicationContext, Looper.getMainLooper(), null)
        } catch (_: Exception) {
            null
        }
}
