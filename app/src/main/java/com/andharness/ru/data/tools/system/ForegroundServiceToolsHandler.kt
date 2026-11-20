package com.andharness.ru.data.tools.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.andharness.ru.bg.GenericForegroundService
import com.andharness.ru.bg.ShortForegroundService
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import com.andharness.ru.data.tools.str

/**
 * Builds one [ToolHandler] per foreground-service / keep-alive tool: `fgs_start`, `fgs_stop`,
 * `keep_alive_start`, `keep_alive_stop`.
 *
 * Everything is driven through [ForegroundServiceState]: the service reports what it actually
 * achieved and the tools read that back with a bounded wait, so `fgs_stop` / `keep_alive_stop` never
 * claim success they cannot verify. Failures caused by the platform (background-start restriction,
 * missing type permission, shortService budget) are reported as the real exception message.
 *
 * References:
 * - Foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types
 * - Background-start restrictions:
 *   https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
 * - `PowerManager.newWakeLock(int, String)`:
 *   https://developer.android.com/reference/android/os/PowerManager#newWakeLock(int,%20java.lang.String)
 */
class ForegroundServiceToolsHandler(
    private val context: Context,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "fgs_start"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val rawType = arguments.str("type")
                        ?: return@withContext ToolResult(
                            false,
                            "Укажите type: dataSync | specialUse | shortService."
                        )
                    val type = canonicalType(rawType)
                        ?: return@withContext ToolResult(
                            false,
                            "Неизвестный type «$rawType». Доступно: dataSync | specialUse | shortService."
                        )
                    val previous = ForegroundServiceState.status.value.runningType

                    val accepted = if (type == ShortForegroundService.TYPE_SHORT_SERVICE) {
                        ShortForegroundService.start(context)
                    } else {
                        GenericForegroundService.start(context, type)
                    }
                    if (!accepted) {
                        return@withContext ToolResult(
                            false,
                            ForegroundServiceState.status.value.error
                                ?: "Система не разрешила запуск foreground-сервиса."
                        )
                    }

                    val confirmed = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) {
                        ForegroundServiceState.status.first { it.runningType == type || it.error != null }
                    }
                    if (confirmed?.runningType != type) {
                        return@withContext ToolResult(
                            false,
                            confirmed?.error ?: "Сервис запущен, но не отчитался о переходе в foreground за " +
                                "${CONFIRM_TIMEOUT_MS}мс."
                        )
                    }
                    ToolResult(true, buildString {
                        append("Foreground-сервис запущен с типом $type.")
                        if (previous != null && previous != type) {
                            append("\nРанее был активен тип $previous — теперь активен $type.")
                        }
                        when (type) {
                            ShortForegroundService.TYPE_SHORT_SERVICE ->
                                append("\nТип shortService ограничен ~3 минутами: сервис остановится сам через 2,5 минуты.")
                            GenericForegroundService.TYPE_SPECIAL_USE ->
                                append("\nТип specialUse не требует дополнительных разрешений, но подлежит проверке Google Play.")
                            else ->
                                append("\nТип dataSync требует разрешения FOREGROUND_SERVICE_DATA_SYNC (объявлено).")
                        }
                    })
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "fgs_stop"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val running = ForegroundServiceState.status.value.runningType
                    if (running == null) {
                        return@withContext ToolResult(true, "Foreground-сервис не запущен — останавливать нечего.")
                    }
                    GenericForegroundService.stop(context)
                    ShortForegroundService.stop(context)
                    val stopped = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) {
                        ForegroundServiceState.status.first { it.runningType == null }
                    }
                    if (stopped != null) {
                        ToolResult(true, "Foreground-сервис ($running) остановлен, уведомление снято.")
                    } else {
                        ToolResult(
                            false,
                            "Команда остановки отправлена, но сервис всё ещё активен " +
                                "(${ForegroundServiceState.status.value.runningType})."
                        )
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "keep_alive_start"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.WAKE_LOCK) ==
                        PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        return@withContext ToolResult(false, "PERMISSION_REQUIRED:android.permission.WAKE_LOCK")
                    }
                    val powerManager = context.getSystemService(PowerManager::class.java)
                        ?: return@withContext ToolResult(false, "PowerManager недоступен на этом устройстве.")
                    val label = arguments.str("label")?.take(40) ?: "keep_alive"

                    // Only one keep-alive lock at a time: release a previous one first.
                    ForegroundServiceState.releaseWakeLock()

                    val lock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "Meha:$label",
                    ).apply {
                        setReferenceCounted(false)
                        // Bounded: the lock is released by the system after the timeout even if the
                        // agent forgets to call keep_alive_stop.
                        acquire(MAX_WAKE_LOCK_MS)
                    }
                    ForegroundServiceState.holdWakeLock(lock)
                    ForegroundServiceState.setKeepAlive(true)

                    val accepted = GenericForegroundService.start(context, GenericForegroundService.TYPE_SPECIAL_USE)
                    if (!accepted) {
                        ForegroundServiceState.releaseWakeLock()
                        ForegroundServiceState.setKeepAlive(false)
                        return@withContext ToolResult(
                            false,
                            ForegroundServiceState.status.value.error
                                ?: "Wake lock взят, но foreground-сервис запустить не удалось."
                        )
                    }
                    val confirmed = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) {
                        ForegroundServiceState.status.first {
                            it.runningType != null || it.error != null
                        }
                    }
                    if (confirmed?.runningType == null) {
                        ForegroundServiceState.releaseWakeLock()
                        ForegroundServiceState.setKeepAlive(false)
                        return@withContext ToolResult(
                            false,
                            confirmed?.error
                                ?: "Wake lock взят, но foreground-сервис не отчитался о запуске."
                        )
                    }
                    ToolResult(
                        true,
                        "Процесс удерживается: foreground-сервис (specialUse) + PARTIAL_WAKE_LOCK " +
                            "«Meha:$label» на срок до ${MAX_WAKE_LOCK_MS / 60_000} мин.\n" +
                            "Побочный эффект: screen не блокируется, но CPU не уходит в сон — расход батареи повышен. " +
                            "Остановить — keep_alive_stop. Если процесс будет убит системой, сервис НЕ перезапустится сам."
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "keep_alive_stop"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val released = ForegroundServiceState.releaseWakeLock()
                    ForegroundServiceState.setKeepAlive(false)

                    val running = ForegroundServiceState.status.value.runningType
                    var serviceReport = "foreground-сервис не был запущен"
                    if (running != null) {
                        GenericForegroundService.stop(context)
                        ShortForegroundService.stop(context)
                        val stopped = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) {
                            ForegroundServiceState.status.first { it.runningType == null }
                        }
                        serviceReport = if (stopped != null) {
                            "foreground-сервис ($running) остановлен"
                        } else {
                            "foreground-сервис (${ForegroundServiceState.status.value.runningType}) всё ещё активен"
                        }
                    }
                    ToolResult(
                        true,
                        "Wake lock освобождён: ${if (released) "да" else "не был активен"}; $serviceReport."
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
    )

    /** Maps the tool's `type` argument onto a canonical foreground-service type name. */
    private fun canonicalType(raw: String): String? = when (
        raw.trim().lowercase().replace("_", "").replace("-", "")
    ) {
        "datasync" -> GenericForegroundService.TYPE_DATA_SYNC
        "specialuse" -> GenericForegroundService.TYPE_SPECIAL_USE
        "shortservice" -> ShortForegroundService.TYPE_SHORT_SERVICE
        else -> null
    }


    private companion object {
        /** How long to wait for the service to confirm a start/stop transition. */
        const val CONFIRM_TIMEOUT_MS = 3_000L

        /** Hard cap on the keep-alive wake lock (1 hour). */
        const val MAX_WAKE_LOCK_MS = 60L * 60L * 1_000L
    }
}
