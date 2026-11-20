package com.andharness.ru.data.tools.vpn

/**
 * Android-free snapshot of the device VPN state, built by `VpnToolsHandler` from
 * `ConnectivityManager` + `NetworkCapabilities.TRANSPORT_VPN`.
 *
 * [transports] holds the transports of the active network (VPN/WIFI/CELLULAR/ETHERNET), [vpnOnActive]
 * is true when the active network itself carries `TRANSPORT_VPN`, and [vpnNetworkCount] counts every
 * network with that transport. [consentPrepared] is `VpnService.prepare() == null` (Меха's own
 * consent), or `null` when it could not be determined.
 *
 * Official reference:
 * https://developer.android.com/reference/android/net/NetworkCapabilities#TRANSPORT_VPN
 * https://developer.android.com/reference/android/net/VpnService#prepare(android.content.Context)
 */
data class VpnStatusInfo(
    val activeNetworkPresent: Boolean,
    val transports: List<String>,
    val vpnOnActive: Boolean,
    val vpnNetworkCount: Int,
    val consentPrepared: Boolean?,
)
