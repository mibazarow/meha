package com.andharness.ru.data.tools.sim

import android.content.Context
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * `sim_numbers`: read-only phone number(s) of the active SIM subscription(s).
 *
 * For each subscription it returns the slot, carrier, ICCID (when the platform exposes it) and the
 * number from `SubscriptionManager.getPhoneNumber(subId)` (API 33+) / `TelephonyManager.getLine1Number()`.
 * When no number is available the tool answers honestly with «не определено» and explains why — it
 * never invents or borrows a number (BUG-056).
 *
 * Requires `READ_PHONE_STATE` to enumerate subscriptions; the number itself additionally needs
 * `READ_PHONE_NUMBERS` (missing permission is reported per-row, not fabricated).
 *
 * https://developer.android.com/reference/android/telephony/SubscriptionManager#getActiveSubscriptionInfoList()
 * https://developer.android.com/reference/android/telephony/SubscriptionManager#getPhoneNumber(int)
 * https://developer.android.com/reference/android/telephony/TelephonyManager#getLine1Number()
 */
internal class SimNumbersToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "sim_numbers"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            when (val result = SimNumbersReader(context).read()) {
                is SimNumbersReader.Result.NoPhoneStatePermission ->
                    ToolResult(false, SimNumbersFormatter.noPermission())

                is SimNumbersReader.Result.Success ->
                    ToolResult(true, SimNumbersFormatter.format(result.rows))
            }
        } catch (e: Exception) {
            ToolResult(false, "Ошибка чтения номеров SIM: ${e.message}")
        }
    }
}
