package com.andharness.ru.data.tools.sms

import android.content.Context
import android.telephony.SubscriptionManager
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * `subscription_list`: lists the active SIM subscriptions with slot, carrier, display name and
 * default flag.
 *
 * Requires READ_PHONE_STATE (or carrier privileges); may throw SecurityException.
 * https://developer.android.com/reference/android/telephony/SubscriptionManager#getActiveSubscriptionInfoList()
 */
internal class SubscriptionListToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "subscription_list"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val manager = context.getSystemService(SubscriptionManager::class.java)
                ?: return@withContext ToolResult(true, "SubscriptionManager недоступен на этом устройстве.")
            val infos = try {
                manager.activeSubscriptionInfoList
            } catch (e: SecurityException) {
                return@withContext ToolResult(false, "Нет разрешения на чтение подписок (нужен READ_PHONE_STATE).")
            }
            if (infos.isNullOrEmpty()) {
                return@withContext ToolResult(true, "Активные SIM-подписки не найдены.")
            }
            val defaultId = SubscriptionManager.getDefaultSubscriptionId()
            val lines = infos.map { info ->
                val carrier = info.carrierName?.toString()?.takeIf { it.isNotBlank() } ?: "(без оператора)"
                val display = info.displayName?.toString()?.takeIf { it.isNotBlank() } ?: "(без имени)"
                val isDefault = info.subscriptionId == defaultId
                "id=${info.subscriptionId} | slot=${info.simSlotIndex} | оператор: $carrier | имя: $display | default=$isDefault"
            }
            ToolResult(true, lines.joinToString("\n"))
        } catch (e: UnsupportedOperationException) {
            ToolResult(true, "Устройство не поддерживает подписки SIM.")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
