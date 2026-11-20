package com.andharness.ru.data.tools.location

import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `location_updates_get`: reads the newest collected location points from the stream buffer. */
internal class LocationUpdatesGetToolHandler : ToolHandler {

    override val name = "location_updates_get"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val limit = arguments.str("limit")?.let {
            it.toIntOrNull() ?: return@withContext ToolResult(
                false,
                "limit должно быть целым числом.",
            )
        } ?: DEFAULT_HISTORY_LIMIT
        val fixes = LocationUpdatesState.recent(limit)
        if (fixes.isEmpty()) {
            val status = LocationUpdatesState.status.value
            val hint = if (status.running) {
                "Поток запущен, но точек пока нет — подождите немного и повторите."
            } else {
                "Запустите поток: location_updates_start."
            }
            return@withContext ToolResult(false, "Полученных точек локации пока нет. $hint")
        }
        val running = LocationUpdatesState.status.value.running
        val header = "${if (running) "Поток активен" else "Поток остановлен"}; " +
            "показаны ${fixes.size} из ${LocationUpdatesState.fixes.value.size} последних точек (новые сверху):"
        val lines = fixes.mapIndexed { index, fix ->
            "${index + 1}. lat=${fix.latitude}, lon=${fix.longitude}, " +
                "accuracy=${fix.accuracyMeters}м, provider=${fix.provider ?: "?"}, время=${fix.timeMs}"
        }
        ToolResult(true, (listOf(header) + lines).joinToString("\n"))
    }

    private companion object {
        private const val DEFAULT_HISTORY_LIMIT = 20
    }
}
