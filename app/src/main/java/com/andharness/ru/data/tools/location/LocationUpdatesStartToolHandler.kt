package com.andharness.ru.data.tools.location

import android.content.Context
import com.andharness.ru.bg.LocationUpdatesService
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * `location_updates_start`: starts the continuous location stream owned by
 * [LocationUpdatesService] and reports whether it confirmed the start.
 */
internal class LocationUpdatesStartToolHandler(
    private val context: Context,
    private val permissions: LocationPermissions,
) : ToolHandler {

    override val name = "location_updates_start"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val intervalSec = arguments.str("interval_sec")?.let {
            it.toIntOrNull() ?: return@withContext ToolResult(
                false,
                "interval_sec должно быть целым числом секунд.",
            )
        } ?: LocationUpdatesService.DEFAULT_INTERVAL_SEC
        if (intervalSec < LocationUpdatesService.MIN_INTERVAL_SEC) {
            return@withContext ToolResult(
                false,
                "interval_sec должен быть не меньше ${LocationUpdatesService.MIN_INTERVAL_SEC} секунд.",
            )
        }
        val minDistanceM = arguments.str("min_distance_m")?.let {
            it.toFloatOrNull() ?: return@withContext ToolResult(
                false,
                "min_distance_m должно быть числом метров.",
            )
        } ?: LocationUpdatesService.DEFAULT_MIN_DISTANCE_M
        if (minDistanceM < 0f) {
            return@withContext ToolResult(false, "min_distance_m не может быть отрицательным.")
        }
        val highAccuracy = arguments.str("high_accuracy")?.toBooleanStrictOrNull() ?: false

        if (!permissions.fineLocationGranted()) {
            return@withContext ToolResult(
                false,
                "Для потока обновлений локации нужно разрешение ACCESS_FINE_LOCATION — оно не выдано. " +
                    "Выдайте разрешение на местоположение и повторите.",
            )
        }
        if (LocationUpdatesState.status.value.running) {
            val s = LocationUpdatesState.status.value
            return@withContext ToolResult(
                false,
                "Поток обновлений локации уже запущен (интервал ${s.intervalSec}с). " +
                    "Сначала остановите его: location_updates_stop.",
            )
        }

        LocationUpdatesState.reset()
        if (!LocationUpdatesService.start(context, intervalSec, minDistanceM, highAccuracy)) {
            return@withContext ToolResult(false, "Не удалось запустить службу обновлений локации.")
        }

        val started = withTimeoutOrNull(START_TIMEOUT_MS) {
            LocationUpdatesState.status.first { it.running || it.error != null }
        } ?: return@withContext ToolResult(
            false,
            "Служба обновлений локации не подтвердила старт за ${START_TIMEOUT_MS / 1000} с.",
        )
        if (started.error != null) {
            return@withContext ToolResult(
                false,
                "Не удалось начать обновления локации: ${started.error}",
            )
        }

        val backgroundNote = if (permissions.backgroundLocationGranted()) {
            ""
        } else {
            " Внимание: разрешение «Местоположение всегда» (ACCESS_BACKGROUND_LOCATION) не выдано — " +
                "когда приложение не на экране, система может ограничить или приостановить обновления. " +
                "Выдайте его в настройках: Приложения → Меха → Разрешения → Местоположение → «Всегда разрешать»."
        }
        ToolResult(
            true,
            "Поток обновлений локации запущен: интервал ${intervalSec}с, минимальное смещение " +
                "${minDistanceM}м, точность ${if (highAccuracy) "высокая (GPS)" else "обычная (сеть)"}." +
                backgroundNote +
                " Точки читайте через location_updates_get, остановите через location_updates_stop.",
        )
    }

    private companion object {
        private const val START_TIMEOUT_MS = 5_000L
    }
}
