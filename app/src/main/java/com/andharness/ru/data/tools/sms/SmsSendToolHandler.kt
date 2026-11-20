package com.andharness.ru.data.tools.sms

import android.content.Context
import android.telephony.SmsManager
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `sms_send`: sends an SMS and waits for the radio's SENT broadcast (RESULT_OK) before reporting
 * success.
 *
 * References:
 * - `context.getSystemService(SmsManager::class.java).sendTextMessage(destinationAddress, sendingServiceCenterAddress, text, sentIntent, deliveryIntent)`:
 *   https://developer.android.com/reference/android/telephony/SmsManager#sendTextMessage(java.lang.String,%20java.lang.String,%20java.lang.String,%20android.content.Intent,%20android.content.Intent)
 */
internal class SmsSendToolHandler(
    private val context: Context,
    private val support: SmsSupport,
) : ToolHandler {

    override val name = "sms_send"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val phoneArg = arguments["phone"] as? JsonPrimitive
            val textArg = arguments["text"] as? JsonPrimitive
            if (phoneArg == null || phoneArg.content.isEmpty()) {
                return@withContext ToolResult(false, "Укажите phone.")
            }
            if (textArg == null || textArg.content.isEmpty()) {
                return@withContext ToolResult(false, "Укажите text.")
            }
            val phone = phoneArg.content
            val text = textArg.content
            val smsManager = context.getSystemService(SmsManager::class.java)
                ?: return@withContext ToolResult(false, "SmsManager недоступен на этом устройстве.")
            // Honest send: the radio's SENT broadcast (RESULT_OK) is required for success,
            // exactly like sms_send_on_sim / mms_send below.
            val resultCode = support.awaitSentResult(
                timeoutMs = 30_000L,
                send = { pending -> smsManager.sendTextMessage(phone, null, text, pending, null) },
            )
            support.sentResultToToolResult(resultCode, phone)
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
