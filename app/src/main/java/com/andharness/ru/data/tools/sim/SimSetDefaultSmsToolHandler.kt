package com.andharness.ru.data.tools.sim

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telephony.SubscriptionManager
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `sim_set_default_sms`: attempted change of the default SMS subscription.
 *
 * There is NO public API a normal app may use: `SubscriptionManager.setDefaultSmsSubId(int)` is
 * `@SystemApi` + `@RequiresPermission(MODIFY_PHONE_STATE)`. This handler therefore NEVER sets
 * anything and NEVER returns success — it validates the id and returns an honest refusal naming the
 * missing privilege. See [SimDefaultSmsPolicy].
 *
 * https://developer.android.com/reference/android/telephony/SubscriptionManager#setDefaultSmsSubId(int)
 * https://developer.android.com/reference/android/provider/Telephony.Sms#getDefaultSmsPackage(android.content.Context)
 */
internal class SimSetDefaultSmsToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "sim_set_default_sms"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val raw = (arguments["subscription_id"] as? JsonPrimitive)?.content
        val subscriptionId = SimDefaultSmsPolicy.parseSubscriptionId(raw)
            ?: return@withContext ToolResult(false, SimDefaultSmsPolicy.invalidIdMessage())

        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext ToolResult(false, SimSlotsFormatter.noPermission())
        }
        try {
            @Suppress("MissingPermission")
            val activeIds = context.getSystemService(SubscriptionManager::class.java)
                ?.activeSubscriptionInfoList
                ?.map { it.subscriptionId }
                ?.toSet()
                .orEmpty()
            if (subscriptionId !in activeIds) {
                return@withContext ToolResult(
                    false,
                    SimDefaultSmsPolicy.unknownIdMessage(subscriptionId),
                )
            }
        } catch (_: SecurityException) {
            return@withContext ToolResult(false, SimSlotsFormatter.noPermission())
        } catch (_: Exception) {
            // Could not verify the id; fall through to the honest privilege refusal.
        }

        val isDefaultSmsApp = try {
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        } catch (_: Exception) {
            false
        }
        ToolResult(false, SimDefaultSmsPolicy.refusalMessage(subscriptionId, isDefaultSmsApp))
    }
}
