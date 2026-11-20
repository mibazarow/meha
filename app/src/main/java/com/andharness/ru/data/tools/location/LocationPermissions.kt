package com.andharness.ru.data.tools.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Location-permission checks shared by the location tools.
 *
 * Reference: location permissions (incl. background location) —
 * https://developer.android.com/develop/sensors-and-location/location/permissions
 */
internal class LocationPermissions(private val context: Context) {

    fun fineLocationGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * `ACCESS_BACKGROUND_LOCATION` only exists from Android 10 (API 29); below that background
     * location is covered by the fine permission. Above it, the user must grant "Allow all the
     * time" in system settings.
     * https://developer.android.com/develop/sensors-and-location/location/permissions
     */
    fun backgroundLocationGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
}
