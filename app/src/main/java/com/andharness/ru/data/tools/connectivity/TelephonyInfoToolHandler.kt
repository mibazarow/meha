package com.andharness.ru.data.tools.connectivity

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `telephony_info`: operator, phone type, roaming and active SIM count (honest about permissions). */
internal class TelephonyInfoToolHandler(
    private val context: Context,
) : ToolHandler {
    override val name = "telephony_info"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val tm = context.getSystemService(TelephonyManager::class.java)
                ?: return@withContext ToolResult(false, "TelephonyManager недоступен на устройстве.")
            val granted = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED

            val sb = StringBuilder()
            sb.appendLine("Разрешение READ_PHONE_STATE: ${if (granted) "выдано" else "не выдано"}")
            sb.appendLine("Оператор сети: ${safeTelephonyValue { tm.networkOperatorName }}")
            sb.appendLine("Оператор SIM: ${safeTelephonyValue { tm.simOperatorName }}")
            sb.appendLine(
                "Тип телефона: " + when {
                    !granted -> "недоступно (нет разрешения READ_PHONE_STATE)"
                    else -> safeTelephonyValue {
                        when (tm.phoneType) {
                            TelephonyManager.PHONE_TYPE_GSM -> "GSM"
                            TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
                            TelephonyManager.PHONE_TYPE_SIP -> "SIP"
                            TelephonyManager.PHONE_TYPE_NONE -> "нет/неизвестно"
                            else -> "неизвестно (${tm.phoneType})"
                        }
                    }
                }
            )
            sb.appendLine(
                "Роуминг: " + when {
                    !granted -> "недоступно (нет разрешения READ_PHONE_STATE)"
                    else -> safeTelephonyValue { if (tm.isNetworkRoaming) "да" else "нет" }
                }
            )
            sb.appendLine("Активных SIM (подписок): ${activeSubscriptionCount(context, granted)}")
            ToolResult(true, sb.toString().trim())
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }

    /**
     * Runs [block] and returns either its non-blank value or an honest "недоступно" marker;
     * never fabricates a value. https://developer.android.com/reference/android/telephony/TelephonyManager
     */
    private fun safeTelephonyValue(block: () -> String?): String =
        try {
            block()?.trim()?.takeIf { it.isNotEmpty() } ?: "недоступно"
        } catch (_: SecurityException) {
            "недоступно (нет разрешения READ_PHONE_STATE)"
        } catch (_: Exception) {
            "недоступно"
        }

    /**
     * Number of active SIM subscriptions via [SubscriptionManager]; guarded because the list
     * requires READ_PHONE_STATE and is honest about being unavailable.
     * https://developer.android.com/reference/android/telephony/SubscriptionManager#getActiveSubscriptionInfoList()
     */
    private fun activeSubscriptionCount(context: Context, permissionGranted: Boolean): String {
        if (!permissionGranted) return "недоступно (нет разрешения READ_PHONE_STATE)"
        return try {
            val sm = context.getSystemService(SubscriptionManager::class.java)
                ?: return "недоступно"
            @Suppress("MissingPermission")
            val list = sm.activeSubscriptionInfoList
            if (list == null) "недоступно" else list.size.toString()
        } catch (_: SecurityException) {
            "недоступно (нет разрешения READ_PHONE_STATE)"
        } catch (e: Exception) {
            "недоступно (${e.message})"
        }
    }
}
