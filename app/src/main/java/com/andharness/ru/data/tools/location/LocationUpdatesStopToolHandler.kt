package com.andharness.ru.data.tools.location

import android.content.Context
import com.andharness.ru.bg.LocationUpdatesService
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/** `location_updates_stop`: stops the continuous location stream and reports the points collected. */
internal class LocationUpdatesStopToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "location_updates_stop"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        if (!LocationUpdatesState.status.value.running) {
            return@withContext ToolResult(false, "Поток обновлений локации не запущен.")
        }
        LocationUpdatesService.stop(context)
        val stopped = withTimeoutOrNull(STOP_TIMEOUT_MS) {
            LocationUpdatesState.status.first { !it.running }
        } ?: return@withContext ToolResult(
            false,
            "Не удалось остановить поток обновлений локации за ${STOP_TIMEOUT_MS / 1000} с.",
        )
        val count = LocationUpdatesState.fixes.value.size
        ToolResult(
            true,
            if (stopped.error != null) {
                "Поток обновлений локации остановлен (${stopped.error}). Получено точек: $count."
            } else {
                "Поток обновлений локации остановлен. Получено точек: $count."
            },
        )
    }

    private companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
