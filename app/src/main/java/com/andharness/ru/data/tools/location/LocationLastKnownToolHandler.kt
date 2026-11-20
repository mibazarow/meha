package com.andharness.ru.data.tools.location

import android.content.Context
import android.location.LocationManager
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * `location_last_known`: reports each provider's cached fix without requesting updates.
 *
 * Reference: `LocationManager.getLastKnownLocation(provider)` (returns null if no last known
 * location) —
 * https://developer.android.com/reference/android/location/LocationManager#getLastKnownLocation(java.lang.String)
 */
internal class LocationLastKnownToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "location_last_known"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
            )
            val lines = ArrayList<String>()
            var any = false
            for (provider in providers) {
                val enabled = try {
                    lm.isProviderEnabled(provider)
                } catch (_: Exception) {
                    false
                }
                // getLastKnownLocation returns null when the provider has no cached fix;
                // no updates are requested here (unlike location_get). Reference:
                // https://developer.android.com/reference/android/location/LocationManager#getLastKnownLocation(java.lang.String)
                val location = try {
                    lm.getLastKnownLocation(provider)
                } catch (_: SecurityException) {
                    null
                } catch (_: Exception) {
                    null
                }
                if (location == null) {
                    val suffix = if (enabled) "" else " (провайдер выключен)"
                    lines.add("$provider: сохранённой локации нет$suffix")
                } else {
                    any = true
                    lines.add(
                        "$provider: lat=${location.latitude}, lon=${location.longitude}, " +
                            "accuracy=${location.accuracy}м, время=${location.time}",
                    )
                }
            }
            if (!any) {
                ToolResult(
                    false,
                    "Сохранённой локации нет ни у GPS, ни у сетевого провайдера. " +
                        lines.joinToString("; "),
                )
            } else {
                ToolResult(true, lines.joinToString("\n"))
            }
        } catch (e: SecurityException) {
            ToolResult(false, "Нет разрешения на геолокацию (нужен ACCESS_FINE_LOCATION).")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
