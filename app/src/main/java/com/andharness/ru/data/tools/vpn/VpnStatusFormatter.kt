package com.andharness.ru.data.tools.vpn

/**
 * Pure, Android-free formatting for `vpn_status`.
 *
 * Honesty contract: this reports only observable state. It never claims that Меха runs a tunnel —
 * the app has no `VpnService` implementation and `vpn_prepare` only requests system consent.
 */
object VpnStatusFormatter {

    fun format(info: VpnStatusInfo): String = buildString {
        appendLine("Сеть по умолчанию: ${if (info.activeNetworkPresent) "есть" else "нет"}")
        appendLine(
            "Транспорт активной сети: " +
                (info.transports.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "неизвестно")
        )
        appendLine("VPN-транспорт в активной сети: ${if (info.vpnOnActive) "да" else "нет"}")
        appendLine("Сетей с VPN-транспортом: ${info.vpnNetworkCount}")
        appendLine(
            "Согласие Меха на VPN (VpnService.prepare): " + when (info.consentPrepared) {
                true -> "получено (prepare() вернул null)"
                false -> "не получено (prepare() требует системный диалог)"
                null -> "неизвестно"
            }
        )
        append(
            "Примечание: это только состояние VPN-транспорта системы; собственный туннель " +
                "приложение не поднимает (см. vpn_prepare)."
        )
    }
}
