package com.andharness.ru.data.tools.location

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.resume

/**
 * `location_get`: actively requests a fresh fix and reports coordinates.
 *
 * References:
 * - `LocationManager` / `isProviderEnabled(provider)` / `getLastKnownLocation(provider)`:
 *   https://developer.android.com/reference/android/location/LocationManager
 * - `LocationManager.requestSingleUpdate(provider, listener, oper)` (actively requests a fresh fix;
 *   works on all supported APIs, unlike `getCurrentLocation` which is API 30+):
 *   https://developer.android.com/reference/android/location/LocationManager#requestSingleUpdate(java.lang.String,%20android.location.LocationListener,%20android.os.Looper)
 * - `LocationListener.onLocationChanged(location)` (resumed with the fresh fix):
 *   https://developer.android.com/reference/android/location/LocationListener
 */
internal class LocationGetToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "location_get"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            val enabledProviders = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            ).filter { lm.isProviderEnabled(it) }
            if (enabledProviders.isEmpty()) {
                return@withContext ToolResult(
                    false,
                    "Геолокация выключена на устройстве (GPS/сеть недоступны). Включите геолокацию и повторите.",
                )
            }

            val fresh = requestFreshLocation(lm, enabledProviders)
            val location = fresh
                ?: enabledProviders.firstNotNullOfOrNull { lm.getLastKnownLocation(it) }
            if (location == null) {
                return@withContext ToolResult(
                    false,
                    "Не удалось получить координаты за ~8с (нет GPS-фикса). Убедитесь, что геолокация включена и телефон имеет доступ к небу/сети, затем повторите.",
                )
            }

            ToolResult(true, "lat=${location.latitude}, lon=${location.longitude}, accuracy=${location.accuracy}m")
        } catch (e: SecurityException) {
            ToolResult(false, "Нет разрешения на геолокацию (нужен ACCESS_FINE_LOCATION).")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }

    private suspend fun requestFreshLocation(
        lm: LocationManager,
        enabledProviders: List<String>,
    ): Location? {
        var result: Location? = null
        withTimeoutOrNull(FRESH_FIX_TIMEOUT_MS) {
            for (provider in enabledProviders) {
                val location = try {
                    fetchSingleUpdate(lm, provider)
                } catch (_: Exception) {
                    null // Try the next enabled provider.
                }
                if (location != null) {
                    result = location
                    break
                }
            }
        }
        return result
    }

    private suspend fun fetchSingleUpdate(lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    lm.removeUpdates(this)
                    if (!cont.isCompleted) cont.resume(location)
                }

                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            try {
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            } catch (_: Exception) {
                lm.removeUpdates(listener)
                if (!cont.isCompleted) cont.resume(null)
            }
            cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
        }

    private companion object {
        private const val FRESH_FIX_TIMEOUT_MS = 8_000L
    }
}
