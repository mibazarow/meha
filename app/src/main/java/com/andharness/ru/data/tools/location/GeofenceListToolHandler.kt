package com.andharness.ru.data.tools.location

import android.content.Context
import com.andharness.ru.data.tools.formatTimestamp
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `geofence_list`: lists the locally tracked geofences and their last recorded transition. */
internal class GeofenceListToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "geofence_list"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val store = GeofenceStore(context)
        val fences = store.list()
        if (fences.isEmpty()) {
            return@withContext ToolResult(true, "Геозон нет. Добавьте: geofence_add.")
        }
        val events = store.events()
        val lines = fences.map { fence ->
            val last = events.lastOrNull { it.geofenceId == fence.id }
            val expiry = fence.expirationHours?.let { "$it ч" } ?: "бессрочно"
            val dwell = if (fence.dwell) ", пребывание (dwell)" else ""
            val added = formatTimestamp(fence.addedAtMs, "yyyy-MM-dd HH:mm")
            val lastEvent = last?.let {
                ", последнее событие: ${it.transition} в ${formatTimestamp(it.timeMs, "yyyy-MM-dd HH:mm")}"
            } ?: ""
            "• ${fence.id}: ${fence.latitude}, ${fence.longitude}, R=${fence.radiusM}м, " +
                "истекает: $expiry$dwell, добавлена: $added$lastEvent"
        }
        ToolResult(
            true,
            "Геозон: ${fences.size} (лимит ~${GeofenceStore.MAX_GEOFENCES}):\n" + lines.joinToString("\n"),
        )
    }
}
