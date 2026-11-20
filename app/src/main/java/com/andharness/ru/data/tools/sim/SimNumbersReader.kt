package com.andharness.ru.data.tools.sim

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/**
 * Android side of `sim_numbers` / the `sim_numbers` device fact: reads the active subscriptions and
 * the phone number the platform is willing to report for each of them.
 *
 * Honesty (BUG-056/BUG-057): every value is taken from a public API and may be `null`; nothing is
 * guessed. Enumerating subscriptions needs `READ_PHONE_STATE`; reading a number needs
 * `READ_PHONE_NUMBERS` (API 26+) and, on most SIMs, the operator must have provisioned the MSISDN —
 * otherwise `SimNumberRow.number` stays `null` and is reported as «не определено».
 *
 * Official references:
 * - https://developer.android.com/reference/android/telephony/SubscriptionManager#getActiveSubscriptionInfoList()
 * - https://developer.android.com/reference/android/telephony/SubscriptionManager#getPhoneNumber(int)
 *   (`getPhoneNumber(subId)`, API 33+, returns an empty string when unavailable, needs READ_PHONE_NUMBERS)
 * - https://developer.android.com/reference/android/telephony/TelephonyManager#createForSubscriptionId(int)
 * - https://developer.android.com/reference/android/telephony/TelephonyManager#getLine1Number()
 * - https://developer.android.com/reference/android/telephony/SubscriptionInfo#getIccId()
 *   (carrier-privilege gated since API 29 — usually `null` for an ordinary app)
 */
internal class SimNumbersReader(private val context: Context) {

    sealed interface Result {
        /** `READ_PHONE_STATE` is not granted, so even the subscription list is unavailable. */
        object NoPhoneStatePermission : Result

        /** Read attempt completed; [rows] may be empty (no active SIM) but is never fabricated. */
        data class Success(val rows: List<SimNumberRow>) : Result
    }

    fun read(): Result {
        if (!granted(Manifest.permission.READ_PHONE_STATE)) return Result.NoPhoneStatePermission

        val manager = context.getSystemService(SubscriptionManager::class.java)
            ?: return Result.Success(emptyList())
        val infos = try {
            @Suppress("MissingPermission")
            manager.activeSubscriptionInfoList
        } catch (_: SecurityException) {
            return Result.NoPhoneStatePermission
        } catch (_: UnsupportedOperationException) {
            return Result.Success(emptyList())
        }

        val hasNumbersPermission = granted(Manifest.permission.READ_PHONE_NUMBERS)
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val rows = infos.orEmpty().map { info ->
            SimNumberRow(
                subscriptionId = info.subscriptionId,
                slotIndex = info.simSlotIndex,
                carrierName = info.carrierName?.toString(),
                iccid = readIccId(info),
                number = readNumber(manager, telephony, info.subscriptionId),
                numberUnavailableReason = if (hasNumbersPermission) null else REASON_NO_NUMBERS_PERMISSION,
            )
        }
        return Result.Success(rows)
    }

    /**
     * API 33+ `SubscriptionManager.getPhoneNumber(subId)` first (the platform's recommended API),
     * then `TelephonyManager.getLine1Number()` for the subscription as a fallback. Both may return
     * `null`/empty — that is reported honestly, never replaced by a guess.
     */
    private fun readNumber(
        manager: SubscriptionManager,
        telephony: TelephonyManager?,
        subId: Int,
    ): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { manager.getPhoneNumber(subId) }
                .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        @Suppress("DEPRECATION")
        return runCatching {
            val perSubscription = telephony?.createForSubscriptionId(subId)
            (perSubscription ?: telephony)?.line1Number
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** `getIccId()` is carrier-privilege gated since API 29; `null` is the honest common case. */
    @Suppress("DEPRECATION")
    private fun readIccId(info: SubscriptionInfo): String? =
        runCatching { info.iccId }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val REASON_NO_NUMBERS_PERMISSION = "нет разрешения READ_PHONE_NUMBERS"
    }
}
