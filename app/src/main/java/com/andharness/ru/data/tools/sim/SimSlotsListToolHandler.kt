package com.andharness.ru.data.tools.sim

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * `sim_slots_list`: read-only list of active SIM slots/subscriptions.
 *
 * Public APIs only: `SubscriptionManager.getActiveSubscriptionInfoList()` and the default-role
 * getters. Requires READ_PHONE_STATE; without it (or without any SIM) the tool answers honestly and
 * never fabricates rows.
 *
 * https://developer.android.com/reference/android/telephony/SubscriptionManager#getActiveSubscriptionInfoList()
 * https://developer.android.com/reference/android/telephony/SubscriptionManager#getDefaultVoiceSubscriptionId()
 * https://developer.android.com/reference/android/telephony/SubscriptionManager#getDefaultSmsSubscriptionId()
 * https://developer.android.com/reference/android/telephony/SubscriptionManager#getDefaultDataSubscriptionId()
 * https://developer.android.com/reference/android/telephony/SubscriptionInfo
 */
internal class SimSlotsListToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "sim_slots_list"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext ToolResult(false, SimSlotsFormatter.noPermission())
        }
        try {
            val manager = context.getSystemService(SubscriptionManager::class.java)
                ?: return@withContext ToolResult(false, "SubscriptionManager недоступен на устройстве.")
            val infos = try {
                @Suppress("MissingPermission")
                manager.activeSubscriptionInfoList
            } catch (_: SecurityException) {
                return@withContext ToolResult(false, SimSlotsFormatter.noPermission())
            } catch (_: UnsupportedOperationException) {
                return@withContext ToolResult(true, SimSlotsFormatter.format(emptyList()))
            }

            val voiceId = defaultId { SubscriptionManager.getDefaultVoiceSubscriptionId() }
            val smsId = defaultId { SubscriptionManager.getDefaultSmsSubscriptionId() }
            val dataId = defaultId { SubscriptionManager.getDefaultDataSubscriptionId() }

            val slots = infos.orEmpty().map { info ->
                SimSlotInfo(
                    subscriptionId = info.subscriptionId,
                    slotIndex = info.simSlotIndex,
                    carrierName = info.carrierName?.toString(),
                    displayName = info.displayName?.toString(),
                    embedded = isEmbedded(info),
                    isDefaultVoice = info.subscriptionId == voiceId,
                    isDefaultSms = info.subscriptionId == smsId,
                    isDefaultData = info.subscriptionId == dataId,
                )
            }
            ToolResult(true, SimSlotsFormatter.format(slots))
        } catch (e: Exception) {
            ToolResult(false, "Ошибка чтения SIM-слотов: ${e.message}")
        }
    }

    /** Default-role getters are public but may throw on non-subscription devices. */
    private inline fun defaultId(getter: () -> Int): Int = try {
        getter()
    } catch (_: SecurityException) {
        SubscriptionManager.INVALID_SUBSCRIPTION_ID
    } catch (_: UnsupportedOperationException) {
        SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }

    /** `SubscriptionInfo.isEmbedded()` is API 28+. */
    @Suppress("DEPRECATION")
    private fun isEmbedded(info: SubscriptionInfo): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.isEmbedded
}
