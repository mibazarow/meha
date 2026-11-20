package com.andharness.ru.data.tools.calls

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CallLog
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.calls.CallLogFilter
import com.andharness.ru.domain.calls.CallLogNumberMatcher
import com.andharness.ru.domain.tools.CallApi
import com.andharness.ru.domain.tools.CallStartPlan
import com.andharness.ru.domain.tools.CallStartPlanner
import com.andharness.ru.domain.tools.Tool
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.data.tools.parseLimit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.resume

/**
 * Builds one [ToolHandler] per call tool: [call_log], [call_start].
 *
 * References:
 * - `CallLog.Calls.CONTENT_URI`, `CallLog.Calls.NUMBER`, `CallLog.Calls.CACHED_NAME`,
 *   `CallLog.Calls.TYPE`, `CallLog.Calls.DATE`, `CallLog.Calls.DURATION`:
 *   https://developer.android.com/reference/android/provider/CallLog.Calls
 * - `ContentResolver.query(uri, projection, selection, selectionArgs, sortOrder)`:
 *   https://developer.android.com/reference/android/content/ContentResolver#query(android.net.Uri,%20java.lang.String[],%20java.lang.String,%20java.lang.String[],%20java.lang.String)
 * - `Intent.ACTION_CALL` with `Uri.parse("tel:...")`:
 *   https://developer.android.com/guide/components/intents-actions#Make_a_phone_call
 * - `TelecomManager.placeCall(Uri, Bundle)` — the primary, Activity-free way to place the call
 *   (`call_start`; bug #6):
 *   https://developer.android.com/reference/android/telecom/TelecomManager#placeCall(android.net.Uri,%20android.os.Bundle)
 * - `TelephonyManager.sendUssdRequest(String, UssdResponseCallback, Handler)` (API 26+),
 *   `UssdResponseCallback.onReceiveUssdResponse` / `onReceiveUssdResponseFailed`:
 *   https://developer.android.com/reference/android/telephony/TelephonyManager#sendUssdRequest(java.lang.String,%20android.telephony.TelephonyManager.UssdResponseCallback,%20android.os.Handler)
 * - Requires `Manifest.permission.CALL_PHONE`:
 *   https://developer.android.com/reference/android/telephony/TelephonyManager#sendUssdRequest(java.lang.String,%20android.telephony.TelephonyManager.UssdResponseCallback,%20android.os.Handler)
 */
class CallsToolsHandler(
    private val context: Context,
) {

    /**
     * A direct `sendUssdRequest`, bounded by [USSD_TIMEOUT_MS] (BUG-056).
     *
     * Returns `null` when the network did not answer within the budget. A non-default-dialer app
     * typically gets no callback at all, so the timeout is the honest failure signal — the caller
     * then offers the pre-filled dialer instead of hanging.
     */
    private suspend fun sendUssdDirect(code: String): ToolResult? =
        withTimeoutOrNull(USSD_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                try {
                    val tm = context.getSystemService(TelephonyManager::class.java)
                    tm.sendUssdRequest(code, object : TelephonyManager.UssdResponseCallback() {
                        override fun onReceiveUssdResponse(tm: TelephonyManager, request: String, response: CharSequence) {
                            if (cont.isActive) cont.resume(ToolResult(true, response.toString().trim().ifBlank { "Пустой ответ." }))
                        }
                        override fun onReceiveUssdResponseFailed(tm: TelephonyManager, request: String, failureCode: Int) {
                            if (cont.isActive) cont.resume(ToolResult(false, "USSD не выполнен (код $failureCode). Проверьте SIM/сеть и корректность кода."))
                        }
                    }, Handler(Looper.getMainLooper()))
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(ToolResult(false, "Ошибка USSD: ${e.message}"))
                }
            }
        }

    /** True when this app currently holds the platform's default-dialer role. */
    private fun isDefaultDialer(): Boolean = runCatching {
        context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage == context.packageName
    }.getOrDefault(false)

    /**
     * Honest fallback for `ussd_request` (BUG-056): never place a call and never fake a USSD result.
     * It opens the dialer pre-filled with the code via `ACTION_DIAL` (no `CALL_PHONE` needed, the
     * user still presses the call button) and returns the [UssdFallbackPlanner] explanation, so the
     * user can read the network's reply or enter the number manually.
     * https://developer.android.com/guide/components/intents-actions#Dial_a_phone_number
     */
    private fun openDialerFallback(
        code: String,
        reason: UssdFallbackPlanner.Reason,
    ): ToolResult {
        val message = UssdFallbackPlanner.message(code, reason)
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse(UssdFallbackPlanner.dialUri(code)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) {
            return ToolResult(
                false,
                "$message На устройстве нет приложения «Телефон» — введите код вручную в дозвонщике.",
            )
        }
        return try {
            context.startActivity(intent)
            ToolResult(
                false,
                "$message Дозвонщик открыт с кодом — нажмите «Вызов» и прочитайте ответ сети.",
            )
        } catch (e: ActivityNotFoundException) {
            ToolResult(false, "$message Не удалось открыть дозвонщик: ${e.message}")
        } catch (e: Exception) {
            ToolResult(false, "$message Ошибка открытия дозвонщика: ${e.message}")
        }
    }

    private fun hasCallPhonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Places the call for real (update.md bug #6).
     *
     * Primary path is `TelecomManager.placeCall(Uri, Bundle)` — the outgoing call always goes
     * through the system telecom service and, unlike an Activity launch, is not subject to the
     * Android 10+ background-activity-start restrictions. It requires `CALL_PHONE`.
     * https://developer.android.com/reference/android/telecom/TelecomManager#placeCall(android.net.Uri,%20android.os.Bundle)
     *
     * If Telecom is unavailable (no telephony / unsupported device) it falls back to
     * `Intent.ACTION_CALL`, which also places the call and requires `CALL_PHONE`. It never falls back
     * to `ACTION_DIAL` (which only opens the dialer — that is the separate `dial` tool) and it never
     * reports success without a real placement attempt.
     * https://developer.android.com/guide/components/intents-actions#Make_a_phone_call
     */
    private fun placeCall(plan: CallStartPlan.Place): ToolResult {
        // The documented tel: URI form for an outgoing call.
        val uri = Uri.parse("tel:${plan.number}")
        val telecom = context.getSystemService(TelecomManager::class.java)
        if (telecom != null) {
            try {
                telecom.placeCall(uri, Bundle())
                return ToolResult(
                    true,
                    CallStartPlanner.successMessage(plan.number, CallApi.TELECOM_PLACE_CALL, plan.emergency),
                )
            } catch (e: SecurityException) {
                // Permission is checked up front, but Telecom can still refuse (e.g. an ongoing
                // emergency call, or the app-ops state changed) — report the real reason.
                return ToolResult(false, "${Tool.PERMISSION_REQUIRED}:${Manifest.permission.CALL_PHONE}")
            } catch (e: Exception) {
                Log.w("CallsToolsHandler", "placeCall failed, falling back to ACTION_CALL", e)
            }
        }
        return actionCall(plan, uri)
    }

    private fun actionCall(plan: CallStartPlan.Place, uri: Uri): ToolResult {
        val intent = Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) {
            return ToolResult(
                false,
                CallStartPlanner.failureMessage(
                    plan.number,
                    CallApi.ACTION_CALL,
                    "на устройстве нет обработчика ACTION_CALL",
                ),
            )
        }
        return try {
            context.startActivity(intent)
            ToolResult(
                true,
                CallStartPlanner.successMessage(plan.number, CallApi.ACTION_CALL, plan.emergency),
            )
        } catch (e: SecurityException) {
            ToolResult(false, "${Tool.PERMISSION_REQUIRED}:${Manifest.permission.CALL_PHONE}")
        } catch (e: ActivityNotFoundException) {
            ToolResult(false, CallStartPlanner.failureMessage(plan.number, CallApi.ACTION_CALL, e.message))
        }
    }

    /**
     * The `_ID`s of call-log rows whose stored `NUMBER` matches [phone] after digit-based
     * normalization ([CallLogNumberMatcher]). Returns an empty list when nothing matches or the
     * cursor is unavailable; the caller reports that honestly instead of deleting everything.
     */
    private fun callLogIdsForPhone(phone: String): List<Long> {
        val ids = ArrayList<Long>()
        val projection = arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER)
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            null,
            null,
            null,
        )?.use { c ->
            val idIdx = c.getColumnIndex(CallLog.Calls._ID)
            val numIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
            if (idIdx < 0 || numIdx < 0) return@use
            while (c.moveToNext()) {
                if (CallLogNumberMatcher.matches(phone, c.getString(numIdx))) {
                    ids.add(c.getLong(idIdx))
                }
            }
        }
        return ids
    }

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "call_log"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val limit = parseLimit(arguments["limit"] as? JsonPrimitive)
                    // Optional filters. An unknown non-blank `type` is an honest error; a blank one
                    // means "no filter". `number` is matched in Kotlin (not SQL) because
                    // `CallLog.Calls.NUMBER` stores the number as the user entered it, so raw
                    // equality misses differently-formatted variants.
                    // https://developer.android.com/reference/android/provider/CallLog.Calls#NUMBER
                    val typeRaw = (arguments["type"] as? JsonPrimitive)?.content
                    val typeKey = if (typeRaw.isNullOrBlank()) null else CallLogFilter.typeName(typeRaw)
                    if (!typeRaw.isNullOrBlank() && typeKey == null) {
                        return@withContext ToolResult(
                            false,
                            "Неизвестный type «$typeRaw». Допустимо: incoming, outgoing, missed, rejected.",
                        )
                    }
                    val typeValue = when (typeKey) {
                        CallLogFilter.TYPE_INCOMING -> CallLog.Calls.INCOMING_TYPE
                        CallLogFilter.TYPE_OUTGOING -> CallLog.Calls.OUTGOING_TYPE
                        CallLogFilter.TYPE_MISSED -> CallLog.Calls.MISSED_TYPE
                        CallLogFilter.TYPE_REJECTED -> CallLog.Calls.REJECTED_TYPE
                        else -> null
                    }
                    val daysRaw = (arguments["days"] as? JsonPrimitive)?.content
                    val days = if (daysRaw.isNullOrBlank()) {
                        null
                    } else {
                        val parsed = daysRaw.trim().toIntOrNull()
                        if (parsed == null || parsed <= 0) {
                            return@withContext ToolResult(false, "days должен быть положительным целым числом.")
                        }
                        parsed
                    }
                    val numberFilter = (arguments["number"] as? JsonPrimitive)?.content
                        ?.trim()?.takeIf { it.isNotEmpty() }
                    val filter = CallLogFilter.build(typeValue, days, System.currentTimeMillis())

                    val projection = arrayOf(
                        CallLog.Calls.NUMBER,
                        CallLog.Calls.CACHED_NAME,
                        CallLog.Calls.TYPE,
                        CallLog.Calls.DATE,
                        CallLog.Calls.DURATION,
                    )
                    val sortOrder = CallLog.Calls.DATE + " DESC"
                    val resolver = context.contentResolver
                    val cursor = resolver.query(
                        CallLog.Calls.CONTENT_URI,
                        projection,
                        filter.clause,
                        filter.args.toTypedArray().takeIf { it.isNotEmpty() },
                        sortOrder,
                    )
                    val lines = ArrayList<String>()
                    cursor?.use { c ->
                        val typeIdx = c.getColumnIndex(CallLog.Calls.TYPE)
                        val numIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
                        val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)
                        val durIdx = c.getColumnIndex(CallLog.Calls.DURATION)
                        while (c.moveToNext() && lines.size < limit) {
                            val number = c.getString(numIdx)
                            if (!CallLogFilter.matchesNumber(numberFilter, number)) continue
                            val type = c.getString(typeIdx)
                            val date = c.getString(dateIdx)
                            val duration = c.getString(durIdx)
                            lines.add("$type | $number | $date | $duration")
                        }
                    }
                    if (lines.isEmpty()) {
                        ToolResult(true, "Журнал звонков пуст.")
                    } else {
                        ToolResult(true, lines.joinToString("\n"))
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "call_start"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                val phoneArg = arguments["phone"] as? JsonPrimitive
                when (val plan = CallStartPlanner.plan(
                    rawPhone = phoneArg?.content,
                    hasCallPhonePermission = hasCallPhonePermission(),
                )) {
                    CallStartPlan.BlankNumber -> ToolResult(false, "Укажите phone.")
                    CallStartPlan.NeedsPermission ->
                        ToolResult(false, "${Tool.PERMISSION_REQUIRED}:${Manifest.permission.CALL_PHONE}")
                    is CallStartPlan.Place -> placeCall(plan)
                }
            }
        },
        object : ToolHandler {
            override val name = "ussd_request"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val codeArg = arguments["code"] as? JsonPrimitive
                    if (codeArg == null || codeArg.content.isBlank()) {
                        return@withContext ToolResult(false, "Укажите code (например *#06#).")
                    }
                    val code = codeArg.content.trim()
                    // This tool is intentionally NOT gated by CALL_PHONE: its honest fallback is
                    // ACTION_DIAL, which needs no permission. It only attempts the direct USSD when
                    // the app is the default dialer (otherwise the reply is never delivered) and it
                    // has CALL_PHONE; any failure ends in the pre-filled-dialer fallback.
                    when (val plan = UssdFallbackPlanner.plan(code, isDefaultDialer())) {
                        is UssdFallbackPlanner.Plan.Direct -> {
                            if (!hasCallPhonePermission()) {
                                openDialerFallback(code, UssdFallbackPlanner.Reason.NOT_DEFAULT_DIALER)
                            } else {
                                when (val direct = sendUssdDirect(code)) {
                                    null -> openDialerFallback(code, UssdFallbackPlanner.Reason.TIMEOUT)
                                    else ->
                                        if (direct.success) {
                                            direct
                                        } else {
                                            openDialerFallback(code, UssdFallbackPlanner.Reason.FAILED)
                                        }
                                }
                            }
                        }

                        is UssdFallbackPlanner.Plan.DialFallback ->
                            openDialerFallback(code, UssdFallbackPlanner.Reason.NOT_DEFAULT_DIALER)
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка USSD: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "call_log_delete"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val all = (arguments["all"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
                    val id = (arguments["id"] as? JsonPrimitive)?.content?.toLongOrNull()
                    val phone = (arguments["phone"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                    val (selection, args) = when {
                        all -> null to null
                        id != null -> CallLog.Calls._ID + " = ?" to arrayOf(id.toString())
                        phone != null -> {
                            // `CallLog.Calls.NUMBER` stores the number "as the user entered it", so a
                            // raw `NUMBER = ?` misses differently-formatted variants (8… vs +7…,
                            // spaces/dashes). Match on significant digits instead, then delete the
                            // concrete `_ID`s — never fall back to deleting the whole log.
                            // https://developer.android.com/reference/android/provider/CallLog.Calls#NUMBER
                            val ids = callLogIdsForPhone(phone)
                            if (ids.isEmpty()) {
                                return@withContext ToolResult(
                                    false,
                                    "Записей с номером «$phone» в журнале не найдено.",
                                )
                            }
                            // `ContentResolver.delete` with a `_ID IN (?, ?, …)` selection:
                            // https://developer.android.com/reference/android/content/ContentResolver#delete(android.net.Uri,%20java.lang.String,%20java.lang.String[])
                            val placeholders = ids.joinToString(", ") { "?" }
                            CallLog.Calls._ID + " IN ($placeholders)" to
                                ids.map { it.toString() }.toTypedArray()
                        }
                        else -> return@withContext ToolResult(false, "Укажите all=true, id или phone.")
                    }
                    val deleted = context.contentResolver.delete(CallLog.Calls.CONTENT_URI, selection, args)
                    ToolResult(true, "Удалено записей журнала: $deleted")
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "blocked_number_ui"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                        return@withContext ToolResult(false, "Экран блокировки номеров появился в Android 7 (API 24).")
                    }
                    val number = (arguments["number"] as? JsonPrimitive)?.content?.trim().orEmpty()
                    val telecom = context.getSystemService(TelecomManager::class.java)
                        ?: return@withContext ToolResult(false, "TelecomManager недоступен на этом устройстве.")

                    // createManageBlockedNumbersIntent() launches the platform's blocked-numbers UI.
                    // Writing the blocked-number provider is limited to the system, the default SMS
                    // app, the default phone app and carrier apps — a normal app can only open this
                    // screen, which is stated honestly below.
                    // https://developer.android.com/reference/android/telecom/TelecomManager#createManageBlockedNumbersIntent()
                    val intent = telecom.createManageBlockedNumbersIntent()
                        ?: return@withContext ToolResult(
                            false,
                            "Система не предоставила экран управления блокировкой номеров.",
                        )
                    if (context.packageManager.resolveActivity(intent, 0) == null) {
                        return@withContext ToolResult(
                            false,
                            "На устройстве нет активности для управления блокировкой номеров.",
                        )
                    }
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    val note = if (number.isNotEmpty()) {
                        " Добавьте номер $number вручную: запись в системный список заблокированных номеров " +
                            "доступна только системному приложению, приложению SMS/телефона по умолчанию или оператору."
                    } else {
                        " Запись в системный список заблокированных номеров обычному приложению недоступна — " +
                            "изменить его можно только на этом системном экране."
                    }
                    ToolResult(true, "Открыт системный экран блокировки номеров.$note")
                } catch (e: ActivityNotFoundException) {
                    ToolResult(false, "На устройстве нет обработчика системного экрана блокировки номеров.")
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "call_observe_status"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val telecom = context.getSystemService(TelecomManager::class.java)
                        ?: return@withContext ToolResult(false, "TelecomManager недоступен на этом устройстве.")

                    // isInCall() reports dialing/ringing/active/holding calls and requires
                    // READ_PHONE_STATE; isInManagedCall() (API 31+) narrows that to managed
                    // ConnectionService calls. This API does not expose the caller's number.
                    // https://developer.android.com/reference/android/telecom/TelecomManager#isInCall()
                    // https://developer.android.com/reference/android/telecom/TelecomManager#isInManagedCall()
                    val inCall = telecom.isInCall()
                    val managed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        telecom.isInManagedCall()
                    } else {
                        null
                    }
                    val lines = ArrayList<String>()
                    lines.add(if (inCall) "Идёт вызов (isInCall=true)." else "Вызов не идёт (isInCall=false).")
                    if (managed != null) {
                        lines.add(
                            if (managed) {
                                "Есть вызов из управляемого ConnectionService (isInManagedCall=true)."
                            } else {
                                "Управляемых вызовов нет (isInManagedCall=false)."
                            },
                        )
                    } else {
                        lines.add("isInManagedCall() доступен с Android 12 (API 31) — здесь не проверяется.")
                    }
                    ToolResult(true, lines.joinToString("\n"))
                } catch (e: SecurityException) {
                    ToolResult(false, "Нет разрешения READ_PHONE_STATE для чтения состояния вызова.")
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
    )

    private companion object {
        /** How long to wait for a direct USSD reply before falling back to the dialer. */
        const val USSD_TIMEOUT_MS = 20_000L
    }
}
