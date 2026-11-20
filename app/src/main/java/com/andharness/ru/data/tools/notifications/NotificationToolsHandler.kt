package com.andharness.ru.data.tools.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * Builds one [ToolHandler] per own-app notification-channel / DND tool:
 * [notification_channels_list], [notification_channels_create], [dnd_status].
 *
 * All three operate on ANDharness' OWN notification channels and on the device-wide DND access
 * state, so none of them declares `needsPermission` and none is `dangerous`.
 *
 * References (official Android docs):
 * - Notification channels: list, create, importance semantics ("once you create a notification
 *   channel, you cannot change the notification behaviors — the user has complete control", and
 *   importance cannot be raised after the user lowers it):
 *   https://developer.android.com/develop/ui/views/notifications/channels
 * - `NotificationManager.getNotificationChannels()` (API 26+):
 *   https://developer.android.com/reference/android/app/NotificationManager#getNotificationChannels()
 * - `NotificationManager.getNotificationChannel(String)` and `createNotificationChannel`:
 *   https://developer.android.com/reference/android/app/NotificationManager#createNotificationChannel(android.app.NotificationChannel)
 * - `NotificationManager.isNotificationPolicyAccessGranted()` (DND special access, API 23+):
 *   https://developer.android.com/reference/android/app/NotificationManager#isNotificationPolicyAccessGranted()
 * - `NotificationManager.getCurrentInterruptionFilter()` (DND mode):
 *   https://developer.android.com/reference/android/app/NotificationManager#getCurrentInterruptionFilter()
 *
 * Every unavailable/restricted state is reported honestly as a clear Russian message, never as a
 * fabricated value.
 */
class NotificationToolsHandler(
    private val context: Context,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "notification_channels_list"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val nm = notificationManager()
                        ?: return@withContext ToolResult(false, "NotificationManager недоступен на этом устройстве.")
                    // getNotificationChannels() is API 26+; minSdk is 26, so it is always available here.
                    val channels = nm.notificationChannels.sortedBy { it.id }
                    if (channels.isEmpty()) {
                        return@withContext ToolResult(true, "У приложения нет каналов уведомлений.")
                    }
                    val lines = channels.map { channel ->
                        val group = channel.group?.let { " | группа=$it" }.orEmpty()
                        "${channel.id} | ${channel.name} | важность=${importanceName(channel.importance)}$group"
                    }
                    ToolResult(true, "Каналы уведомлений (${channels.size}):\n" + lines.joinToString("\n"))
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "notification_channels_create"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val id = (arguments["id"] as? JsonPrimitive)?.content?.trim().orEmpty()
                    if (id.isEmpty()) return@withContext ToolResult(false, "Укажите id канала.")
                    val name = (arguments["name"] as? JsonPrimitive)?.content?.trim().orEmpty()
                    if (name.isEmpty()) return@withContext ToolResult(false, "Укажите name канала.")
                    val rawImportance = (arguments["importance"] as? JsonPrimitive)
                        ?.content?.trim()?.lowercase(Locale.US)
                    val importance = when (rawImportance) {
                        null, "", "default" -> NotificationManager.IMPORTANCE_DEFAULT
                        "low" -> NotificationManager.IMPORTANCE_LOW
                        "high" -> NotificationManager.IMPORTANCE_HIGH
                        else -> return@withContext ToolResult(false, "importance должен быть low, default или high.")
                    }
                    val nm = notificationManager()
                        ?: return@withContext ToolResult(false, "NotificationManager недоступен на этом устройстве.")
                    val existing = nm.getNotificationChannel(id)
                    nm.createNotificationChannel(NotificationChannel(id, name, importance))
                    val note = when {
                        existing == null -> ""
                        importance > existing.importance ->
                            " Важно: важность уже существующего канала нельзя повысить — если пользователь понизил её сам, новое значение не применится."
                        importance < existing.importance -> " Важность канала понижена."
                        else -> ""
                    }
                    ToolResult(
                        true,
                        "Канал «$id» создан/обновлён (имя=$name, важность=${importanceName(importance)}).$note"
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "dnd_status"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val nm = notificationManager()
                        ?: return@withContext ToolResult(false, "NotificationManager недоступен на этом устройстве.")
                    if (!nm.isNotificationPolicyAccessGranted()) {
                        return@withContext ToolResult(
                            true,
                            "Доступ к политике уведомлений (DND) не выдан: приложение не может читать или менять режим «Не беспокоить». " +
                                "Пользователь может выдать его в системных настройках: «Не беспокоить» → «Доступ к режиму Не беспокоить»."
                        )
                    }
                    val filter = nm.currentInterruptionFilter
                    ToolResult(
                        true,
                        "Доступ к политике уведомлений (DND) выдан. Текущий фильтр: ${filterName(filter)} ($filter)."
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
    )

    private fun notificationManager(): NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    private fun importanceName(importance: Int): String = when (importance) {
        NotificationManager.IMPORTANCE_NONE -> "нет (NONE)"
        NotificationManager.IMPORTANCE_MIN -> "минимум (MIN)"
        NotificationManager.IMPORTANCE_LOW -> "низкая (LOW)"
        NotificationManager.IMPORTANCE_DEFAULT -> "обычная (DEFAULT)"
        NotificationManager.IMPORTANCE_HIGH -> "высокая (HIGH)"
        else -> "неизвестно ($importance)"
    }

    private fun filterName(filter: Int): String = when (filter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> "все уведомления (ALL)"
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "только приоритетные (PRIORITY)"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "не беспокоить (NONE)"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "только будильники (ALARMS)"
        NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> "неизвестно (UNKNOWN)"
        else -> "неизвестно ($filter)"
    }
}
