package com.andharness.ru.data.tools.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [VpnStatusFormatter] — the pure, Android-free decision mapping of `vpn_status`:
 * whether the active network carries `TRANSPORT_VPN`, how many VPN networks exist, and whether
 * Меха's own consent (VpnService.prepare) is prepared.
 */
class VpnStatusFormatterTest {

    @Test
    fun vpnTransportPresentIsReported() {
        val output = VpnStatusFormatter.format(
            VpnStatusInfo(
                activeNetworkPresent = true,
                transports = listOf("VPN", "WIFI"),
                vpnOnActive = true,
                vpnNetworkCount = 1,
                consentPrepared = true,
            )
        )
        assertTrue(output.contains("VPN-транспорт в активной сети: да"))
        assertTrue(output.contains("VPN, WIFI"))
        assertTrue(output.contains("Сетей с VPN-транспортом: 1"))
        assertTrue(output.contains("Согласие Меха на VPN (VpnService.prepare): получено"))
    }

    @Test
    fun absentVpnTransportIsReported() {
        val output = VpnStatusFormatter.format(
            VpnStatusInfo(
                activeNetworkPresent = true,
                transports = listOf("CELLULAR"),
                vpnOnActive = false,
                vpnNetworkCount = 0,
                consentPrepared = false,
            )
        )
        assertTrue(output.contains("VPN-транспорт в активной сети: нет"))
        assertTrue(output.contains("Сетей с VPN-транспортом: 0"))
        assertTrue(output.contains("не получено"))
        assertFalse(output.contains("VPN-транспорт в активной сети: да"))
    }

    @Test
    fun unknownConsentIsReportedHonestly() {
        val output = VpnStatusFormatter.format(
            VpnStatusInfo(false, emptyList(), false, 0, consentPrepared = null)
        )
        assertTrue(output.contains("Сеть по умолчанию: нет"))
        assertTrue(output.contains("Транспорт активной сети: неизвестно"))
        assertTrue(output.contains("неизвестно"))
    }
}
