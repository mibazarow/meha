package com.andharness.ru.data.tools.location

import android.content.Context
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * `geofence_remove`: unregisters a geofence from Play Services and removes its local record.
 *
 * Geofencing reference (`GeofencingClient.removeGeofences`) —
 * https://developer.android.com/develop/sensors-and-location/location/geofencing
 */
internal class GeofenceRemoveToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "geofence_remove"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val id = arguments.str("id")
            ?: return@withContext ToolResult(false, "Укажите id геозоны, которую нужно удалить.")
        val store = GeofenceStore(context)
        if (!store.exists(id)) {
            return@withContext ToolResult(
                false,
                "Геозона с id «$id» не найдена. Посмотрите список: geofence_list.",
            )
        }

        val client = try {
            LocationServices.getGeofencingClient(context)
        } catch (e: Exception) {
            return@withContext ToolResult(
                false,
                "Службы Google Play недоступны — удалить геозону нельзя: ${e.message}",
            )
        }
        val result = try {
            GeofenceTaskSupport.awaitTask(client.removeGeofences(listOf(id)))
        } catch (e: Exception) {
            return@withContext ToolResult(false, "Не удалось удалить геозону: ${e.message}")
        }
        if (result.isFailure) {
            val reason = result.exceptionOrNull()?.message ?: "неизвестная ошибка Play Services"
            return@withContext ToolResult(
                false,
                "Не удалось удалить геозону «$id»: $reason. Запись сохранена.",
            )
        }
        store.remove(id)
        ToolResult(true, "Геозона «$id» удалена.")
    }
}
