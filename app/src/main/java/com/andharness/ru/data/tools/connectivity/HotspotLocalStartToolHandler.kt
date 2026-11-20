package com.andharness.ru.data.tools.connectivity

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/** `hotspot_local_start`: starts a local-only hotspot and holds its reservation. */
internal class HotspotLocalStartToolHandler(
    private val context: Context,
    private val access: ConnectivityAccess,
    private val session: HotspotSession,
) : ToolHandler {
    override val name = "hotspot_local_start"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        // startLocalOnlyHotspot is API 26+; kept as an explicit guard even though minSdk is 26.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return@withContext ToolResult(
                false,
                "Локальная точка доступа доступна на Android 12+ (API 31+); " +
                    "на этом устройстве API ${Build.VERSION.SDK_INT}.",
            )
        }
        if (session.reservation != null) {
            return@withContext ToolResult(
                false,
                "Локальная точка доступа уже запущена. Сначала вызовите hotspot_local_stop.",
            )
        }
        access.nearbyWifiPermissionError()?.let { return@withContext ToolResult(false, it) }
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return@withContext ToolResult(false, "WifiManager недоступен на устройстве.")
        var started: WifiManager.LocalOnlyHotspotReservation? = null
        var failureMessage: String? = null
        val signal = CompletableDeferred<Unit>()
        val callback = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                started = reservation
                signal.complete(Unit)
            }

            override fun onStopped() {
                failureMessage = "Система остановила локальную точку доступа."
                signal.complete(Unit)
            }

            override fun onFailed(reason: Int) {
                failureMessage = "Не удалось запустить локальную точку доступа: ${hotspotFailureReason(reason)}"
                signal.complete(Unit)
            }
        }
        try {
            // The reservation must be kept alive: the access point stays up only while the
            // app holds it (closed later by hotspot_local_stop).
            wm.startLocalOnlyHotspot(callback, Handler(Looper.getMainLooper()))
            withTimeoutOrNull(HOTSPOT_TIMEOUT_MS) { signal.await() }
        } catch (e: Exception) {
            return@withContext ToolResult(false, "Ошибка запуска локальной точки доступа: ${e.message}")
        }
        val reservation = started
            ?: return@withContext ToolResult(
                false,
                failureMessage
                    ?: "Таймаут ${HOTSPOT_TIMEOUT_MS / 1000} с: система не подтвердила запуск точки доступа.",
            )
        session.reservation = reservation
        val credentials = hotspotCredentials(reservation)
        ToolResult(
            true,
            "Локальная точка доступа запущена (без раздачи интернета).\n" +
                "SSID: ${credentials.first}\n" +
                "Пароль: ${credentials.second ?: "нет (открытая сеть)"}\n" +
                "Резервирование удерживается приложением до вызова hotspot_local_stop.",
        )
    }

    /**
     * Human-readable reason for [WifiManager.LocalOnlyHotspotCallback.onFailed].
     * https://developer.android.com/reference/android/net/wifi/WifiManager.LocalOnlyHotspotCallback
     */
    private fun hotspotFailureReason(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "нет свободного канала (код $reason)"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
            "несовместимый режим Wi-Fi (код $reason)"

        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED ->
            "раздача запрещена политикой устройства (код $reason)"

        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC -> "общая ошибка (код $reason)"
        else -> "код $reason"
    }

    /**
     * SSID + passphrase of an active local-only hotspot: [SoftApConfiguration] on API 30+,
     * the deprecated [WifiConfiguration] below.
     * https://developer.android.com/develop/connectivity/wifi/localonlyhotspot
     */
    private fun hotspotCredentials(
        reservation: WifiManager.LocalOnlyHotspotReservation,
    ): Pair<String, String?> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val config = reservation.softApConfiguration
            (config?.ssid ?: "неизвестно") to config?.passphrase
        } else {
            @Suppress("DEPRECATION")
            val config = reservation.wifiConfiguration
            (config?.SSID?.trim('"') ?: "неизвестно") to config?.preSharedKey
        }

    private companion object {
        /** Bounded wait for LocalOnlyHotspotCallback.onStarted. */
        private const val HOTSPOT_TIMEOUT_MS = 30_000L
    }
}
