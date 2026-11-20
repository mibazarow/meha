package com.andharness.ru.data.tools.location

import android.content.Context
import com.andharness.ru.domain.tools.ToolHandler

/**
 * Builds one [ToolHandler] per location tool: `location_get`, `location_last_known`,
 * `location_updates_start/stop/get` and `geofence_add/remove/list`, in their original registration
 * order. This class is a thin coordinator; each tool lives in its own `<Tool>Handler` class and the
 * shared permission checks are in [LocationPermissions].
 *
 * The continuous stream is owned by [com.andharness.ru.bg.LocationUpdatesService] (foreground
 * service, type `location`) and exposed through [LocationUpdatesState]; geofences are registered
 * through Play Services (`com.google.android.gms.location`) and tracked locally in [GeofenceStore].
 *
 * References:
 * - `LocationManager` and `Context.LOCATION_SERVICE`:
 *   https://developer.android.com/reference/android/location/LocationManager
 * - Location permissions (incl. background location):
 *   https://developer.android.com/develop/sensors-and-location/location/permissions
 * - `LocationManager.isProviderEnabled(provider)` (returns false when GPS/network is off):
 *   https://developer.android.com/reference/android/location/LocationManager#isProviderEnabled(java.lang.String)
 * - `LocationManager.requestSingleUpdate(provider, listener, oper)` (actively requests a fresh fix;
 *   works on all supported APIs, unlike `getCurrentLocation` which is API 30+):
 *   https://developer.android.com/reference/android/location/LocationManager#requestSingleUpdate(java.lang.String,%20android.location.LocationListener,%20android.os.Looper)
 * - `LocationListener.onLocationChanged(location)` (resumed with the fresh fix):
 *   https://developer.android.com/reference/android/location/LocationListener
 * - `LocationManager.getLastKnownLocation(provider)` (returns null if no last known location):
 *   https://developer.android.com/reference/android/location/LocationManager#getLastKnownLocation(java.lang.String)
 * - Geofencing (`GeofencingClient`, `GeofencingRequest`, `Geofence`):
 *   https://developer.android.com/develop/sensors-and-location/location/geofencing
 * - Requires `Manifest.permission.ACCESS_FINE_LOCATION` (throws SecurityException otherwise):
 *   https://developer.android.com/reference/android/location/LocationManager
 */
class LocationToolsHandler(
    private val context: Context,
) {

    fun handlers(): List<ToolHandler> {
        val permissions = LocationPermissions(context)
        return listOf(
            LocationGetToolHandler(context),
            LocationLastKnownToolHandler(context),
            LocationUpdatesStartToolHandler(context, permissions),
            LocationUpdatesStopToolHandler(context),
            LocationUpdatesGetToolHandler(),
            GeofenceAddToolHandler(context, permissions),
            GeofenceRemoveToolHandler(context),
            GeofenceListToolHandler(context),
        )
    }
}
