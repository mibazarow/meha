package com.andharness.ru.data.tools.connectivity

import android.net.wifi.WifiManager

/**
 * Shared local-only hotspot reservation for [HotspotLocalStartToolHandler] and
 * [HotspotLocalStopToolHandler]: the reservation must stay alive for as long as the access point
 * should run, and both tools are created by the same [ConnectivityToolsHandler] instance.
 * https://developer.android.com/develop/connectivity/wifi/localonlyhotspot
 */
internal class HotspotSession {

    /** The active reservation, or null when the local-only hotspot is not running. */
    var reservation: WifiManager.LocalOnlyHotspotReservation? = null
}
