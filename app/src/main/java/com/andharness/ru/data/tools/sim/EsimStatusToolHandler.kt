package com.andharness.ru.data.tools.sim

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.euicc.EuiccManager
import androidx.annotation.RequiresApi
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * `esim_status`: read-only eSIM/eUICC state.
 *
 * `EuiccManager.isEnabled()` and `getEuiccInfo()` need no runtime permission but require the
 * `FEATURE_TELEPHONY_EUICC` feature (API 28+). `getEid()` requires carrier privileges and throws
 * `SecurityException` otherwise: that is reported honestly and the EID is never fabricated.
 *
 * https://developer.android.com/reference/android/telephony/euicc/EuiccManager#isEnabled()
 * https://developer.android.com/reference/android/telephony/euicc/EuiccManager#getEid()
 * https://developer.android.com/reference/android/telephony/euicc/EuiccManager#getEuiccInfo()
 * https://developer.android.com/reference/android/content/pm/PackageManager#FEATURE_TELEPHONY_EUICC
 */
internal class EsimStatusToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "esim_status"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                return@withContext unsupported()
            }
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_EUICC)) {
                return@withContext unsupported()
            }
            ToolResult(true, EsimStatusFormatter.format(readEsim()))
        } catch (e: Exception) {
            ToolResult(false, "Ошибка чтения состояния eSIM: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun readEsim(): EsimStatusInfo {
        val manager = context.getSystemService(EuiccManager::class.java)
            ?: return EsimStatusInfo(false, false, null, null, null)

        val enabled = try {
            manager.isEnabled
        } catch (_: Exception) {
            false
        }

        var eid: String? = null
        var eidBlockedReason: String? = null
        try {
            eid = manager.eid
        } catch (_: SecurityException) {
            eidBlockedReason = EsimStatusFormatter.CARRIER_PRIVILEGE_REASON
        } catch (_: Exception) {
            eidBlockedReason = "недоступно (ошибка чтения EID)"
        }

        val osVersion = try {
            manager.euiccInfo?.osVersion
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }

        return EsimStatusInfo(
            supported = true,
            enabled = enabled,
            eid = eid,
            eidBlockedReason = eidBlockedReason,
            osVersion = osVersion,
        )
    }

    private fun unsupported(): ToolResult = ToolResult(
        true,
        EsimStatusFormatter.format(
            EsimStatusInfo(false, false, null, null, null)
        )
    )
}
