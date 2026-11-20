package com.andharness.ru.data.tools.sms

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `sms_send_on_sim`: sends an SMS from a specific SIM subscription and waits for the SENT
 * broadcast.
 *
 * References:
 * - `SmsManager.createForSubscriptionId(int).sendTextMessage(...)`:
 *   https://developer.android.com/reference/android/telephony/SmsManager#createForSubscriptionId(int)
 */
internal class SmsSendOnSimToolHandler(
    private val support: SmsSupport,
) : ToolHandler {

    override val name = "sms_send_on_sim"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val number = (arguments["number"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (number.isEmpty()) {
                return@withContext ToolResult(false, "Укажите number.")
            }
            val text = (arguments["text"] as? JsonPrimitive)?.content.orEmpty()
            if (text.isEmpty()) {
                return@withContext ToolResult(false, "Укажите text.")
            }
            val subscriptionId = (arguments["subscription_id"] as? JsonPrimitive)?.content?.trim()?.toIntOrNull()
                ?: return@withContext ToolResult(
                    false,
                    "Укажите subscription_id (число из subscription_list).",
                )
            val manager = support.smsManagerFor(subscriptionId)
                ?: return@withContext ToolResult(
                    false,
                    "SmsManager недоступен для подписки $subscriptionId.",
                )
            val resultCode = support.awaitSentResult(
                timeoutMs = 30_000L,
                send = { pending -> manager.sendTextMessage(number, null, text, pending, null) },
            )
            support.sentResultToToolResult(resultCode, number)
        } catch (e: SecurityException) {
            ToolResult(false, "Нет разрешения на отправку SMS (нужен SEND_SMS): ${e.message}")
        } catch (e: IllegalArgumentException) {
            ToolResult(false, "Неверная подписка или аргументы: ${e.message}")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
