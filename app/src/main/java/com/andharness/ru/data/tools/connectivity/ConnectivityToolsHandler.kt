package com.andharness.ru.data.tools.connectivity

import android.content.Context
import com.andharness.ru.domain.tools.ToolHandler

/**
 * Builds one [ToolHandler] per connectivity tool: [network_info], [wifi_info],
 * [bluetooth_devices], [ble_scan], [ble_connect], [dns_lookup], [ping], [wifi_scan],
 * [network_detail], [nsd_discover], [telephony_info], [wifi_connect_specifier],
 * [wifi_suggest_add], [wifi_direct_discover], [wifi_direct_connect], [wifi_direct_disconnect],
 * [hotspot_local_start], [hotspot_local_stop], [wifi_rtt_range], [network_data_usage] — in their
 * original registration order.
 *
 * References (official Android docs):
 * - `ConnectivityManager.getNetworkCapabilities` + `NetworkCapabilities.TRANSPORT_WIFI/CELLULAR`:
 *   https://developer.android.com/reference/android/net/ConnectivityManager
 *   https://developer.android.com/reference/android/net/NetworkCapabilities
 * - `ConnectivityManager.getLinkProperties` + `LinkAddress` (interface name / IP):
 *   https://developer.android.com/reference/android/net/LinkProperties
 * - `WifiManager.getConnectionInfo` (DEPRECATED but still functional; SSID needs location
 *   permission and location services enabled, otherwise returns "<unknown ssid>"):
 *   https://developer.android.com/reference/android/net/wifi/WifiManager#getConnectionInfo()
 * - `BluetoothAdapter.getBondedDevices` (API 31+ requires the runtime BLUETOOTH_CONNECT):
 *   https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#getBondedDevices()
 * - BLE scanning: `BluetoothAdapter.getBluetoothLeScanner()` +
 *   `BluetoothLeScanner.startScan(filters, ScanSettings, ScanCallback)` (an unfiltered scan stops
 *   while the screen is off), `ScanCallback.onScanResult` / `onScanFailed`,
 *   `ScanRecord.getDeviceName`, `ScanSettings.SCAN_MODE_LOW_LATENCY`:
 *   https://developer.android.com/develop/connectivity/bluetooth/ble/find-ble-devices
 *   https://developer.android.com/reference/android/bluetooth/le/BluetoothLeScanner
 * - Bluetooth permission split: BLUETOOTH_SCAN / BLUETOOTH_CONNECT on API 31+, legacy
 *   BLUETOOTH + BLUETOOTH_ADMIN + ACCESS_FINE_LOCATION on API <= 30. `neverForLocation` is NOT
 *   set because the MAC addresses are required:
 *   https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
 * - BLE connections: `BluetoothDevice.connectGatt(context, autoConnect, callback, TRANSPORT_LE)`,
 *   `BluetoothGatt.discoverServices()` + `onServicesDiscovered`, `BluetoothGatt.getServices`,
 *   `BluetoothGattCharacteristic.getProperties`, `BluetoothGatt.disconnect()` / `close()`:
 *   https://developer.android.com/develop/connectivity/bluetooth/bt-connections
 *   https://developer.android.com/reference/android/bluetooth/BluetoothGatt
 * - `ConnectivityManager.getNetworkCapabilities` / `isActiveNetworkMetered` /
 *   `NetworkCapabilities.linkDownstreamBandwidthKbps` / `NET_CAPABILITY_VALIDATED`:
 *   https://developer.android.com/develop/connectivity/network-ops/reading-network-state
 * - `NsdManager.discoverServices` + `NsdManager.DiscoveryListener` + `NsdManager.resolveService`:
 *   https://developer.android.com/reference/android/net/nsd/NsdManager
 * - `TelephonyManager` (`networkOperatorName`, `simOperatorName`, `phoneType`,
 *   `isNetworkRoaming`) and `SubscriptionManager.getActiveSubscriptionInfoList`:
 *   https://developer.android.com/reference/android/telephony/TelephonyManager
 *   https://developer.android.com/reference/android/telephony/SubscriptionManager
 * - `WifiNetworkSpecifier.Builder` + `ConnectivityManager.requestNetwork(NetworkRequest,
 *   NetworkCallback)` (API 29+; no location permission for the specifier itself):
 *   https://developer.android.com/reference/android/net/wifi/WifiNetworkSpecifier
 * - `WifiNetworkSuggestion.Builder` + `WifiManager.addNetworkSuggestions` (API 29+; the system,
 *   not the app, decides whether to auto-connect):
 *   https://developer.android.com/develop/connectivity/wifi/wifi-suggest
 * - Wi-Fi Direct P2P: `WifiP2pManager.initialize` / `discoverPeers` / `requestPeers` /
 *   `connect` / `requestConnectionInfo` / `removeGroup` / `cancelConnect` and the
 *   `WIFI_P2P_PEERS_CHANGED_ACTION` / `WIFI_P2P_CONNECTION_CHANGED_ACTION` broadcasts:
 *   https://developer.android.com/develop/connectivity/wifi/wifi-direct
 * - Local-only hotspot: `WifiManager.startLocalOnlyHotspot` (API 26+, no internet sharing) +
 *   `LocalOnlyHotspotCallback.onStarted/onStopped/onFailed` +
 *   `LocalOnlyHotspotReservation.close()`:
 *   https://developer.android.com/develop/connectivity/wifi/localonlyhotspot
 * - Wi-Fi RTT ranging: `WifiRttManager.isAvailable` + `startRanging(RangingRequest, Executor,
 *   RangingResultCallback)` + `RangingRequest.Builder.addAccessPoint(ScanResult)` +
 *   `RangingResult.getDistanceMm/getStatus` (API 28+):
 *   https://developer.android.com/develop/connectivity/wifi/wifi-rtt
 * - Permission split for the Wi-Fi APIs above: `NEARBY_WIFI_DEVICES` on API 31+ and
 *   `ACCESS_FINE_LOCATION` on API <= 30:
 *   https://developer.android.com/develop/connectivity/wifi/wifi-permissions
 * - Device-wide traffic for [network_data_usage] via `NetworkStatsManager.querySummaryForDevice`
 *   per transport (Wi-Fi / mobile); requires the `PACKAGE_USAGE_STATS` special access, checked via
 *   `AppOpsManager.OPSTR_GET_USAGE_STATS`:
 *   https://developer.android.com/reference/android/app/usage/NetworkStatsManager
 */
class ConnectivityToolsHandler(
    private val context: Context,
) {

    /**
     * The active local-only hotspot reservation shared by [hotspot_local_start] and
     * [hotspot_local_stop]: the reservation must stay alive for as long as the access point should
     * run.
     * https://developer.android.com/develop/connectivity/wifi/localonlyhotspot
     */
    private val hotspotSession = HotspotSession()

    fun handlers(): List<ToolHandler> {
        val access = ConnectivityAccess(context)
        return listOf(
            NetworkInfoToolHandler(context),
            WifiInfoToolHandler(context),
            BluetoothDevicesToolHandler(context),
            BleScanToolHandler(context, access),
            BleConnectToolHandler(context, access),
            DnsLookupToolHandler(),
            PingToolHandler(),
            WifiScanToolHandler(context),
            NetworkDetailToolHandler(context),
            NsdDiscoverToolHandler(context),
            TelephonyInfoToolHandler(context),
            WifiConnectSpecifierToolHandler(context, access),
            WifiSuggestAddToolHandler(context, access),
            WifiDirectDiscoverToolHandler(context, access),
            WifiDirectConnectToolHandler(context, access),
            WifiDirectDisconnectToolHandler(context, access),
            HotspotLocalStartToolHandler(context, access, hotspotSession),
            HotspotLocalStopToolHandler(hotspotSession),
            WifiRttRangeToolHandler(context, access),
            NetworkDataUsageToolHandler(context, access),
        )
    }
}
