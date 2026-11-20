package com.andharness.ru.data.tools.settings

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.notification.Condition
import androidx.annotation.RequiresApi
import com.andharness.ru.bg.DndConditionProviderService
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.undo.UndoKinds
import com.andharness.ru.domain.undo.UndoPayload
import com.andharness.ru.domain.undo.UndoRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * Builds one [ToolHandler] per Settings tool: [settings_system_get],
 * [settings_secure_get], [settings_global_get], [settings_known_keys], plus the WRITE-side
 * [settings_system_set] / [settings_write_access_request] and the DND switch [dnd_set].
 *
 * The four readers are strictly READ-ONLY and need NO permission, so none of them declares
 * `needsPermission` and none is `dangerous`:
 * - `Settings.System.getString(ContentResolver, String)` returns the value or `null` when the key
 *   is not present: https://developer.android.com/reference/android/provider/Settings.System
 * - `Settings.Secure.getString(ContentResolver, String)` — READING a normal secure setting is
 *   allowed for ordinary apps; only WRITING secure settings requires WRITE_SECURE_SETTINGS, a
 *   privileged/system permission this app does not request (and does not use here):
 *   https://developer.android.com/reference/android/provider/Settings.Secure
 * - `Settings.Global.getString(ContentResolver, String)` — reading is allowed for ordinary apps;
 *   writing global settings also requires WRITE_SECURE_SETTINGS:
 *   https://developer.android.com/reference/android/provider/Settings.Global
 *
 * A `null` result is the documented "key not present" signal, so it is surfaced to the model as an
 * honest "not found / not set" message — never as an empty string or a fabricated value.
 *
 * Write-side references (official docs):
 * - `Settings.System.canWrite(Context)` — whether the app currently holds the user-granted
 *   WRITE_SETTINGS special access:
 *   https://developer.android.com/reference/android/provider/Settings.System#canWrite(android.content.Context)
 * - `Settings.ACTION_MANAGE_WRITE_SETTINGS` — the system screen that grants it:
 *   https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_WRITE_SETTINGS
 * - `Settings.System.putString(ContentResolver, String, String)` returns a boolean success flag;
 *   values can still be ignored by the OEM, so the write is always re-read and compared:
 *   https://developer.android.com/reference/android/provider/Settings.System#putString(android.content.ContentResolver,%20java.lang.String,%20java.lang.String)
 * - DND ([dnd_set]): gated by `NotificationManager.isNotificationPolicyAccessGranted()`. On
 *   Android 15 (targetSdk 35, API 35) `setInterruptionFilter(int)` can no longer change the global
 *   filter, so the tool manages a real `AutomaticZenRule` owned by
 *   [com.andharness.ru.bg.DndConditionProviderService] and pushes the condition state with
 *   `NotificationManager.setAutomaticZenRuleState(String, Condition)`. `setInterruptionFilter` is
 *   kept ONLY as the fallback for older Android versions, where it is still permitted:
 *   https://developer.android.com/reference/android/app/NotificationManager#addAutomaticZenRule(android.app.AutomaticZenRule) ,
 *   https://developer.android.com/reference/android/app/AutomaticZenRule ,
 *   https://developer.android.com/reference/android/app/NotificationManager#setAutomaticZenRuleState(java.lang.String,%20android.service.notification.Condition) ,
 *   https://developer.android.com/reference/android/app/NotificationManager#setInterruptionFilter(int)
 */
class SettingsToolsHandler(
    private val context: Context,
    private val undoRecorder: UndoRecorder = UndoRecorder.NoOp,
) {

    fun handlers(): List<ToolHandler> = listOf(
        readHandler("settings_system_get") { key ->
            Settings.System.getString(context.contentResolver, key)
        },
        readHandler("settings_secure_get") { key ->
            Settings.Secure.getString(context.contentResolver, key)
        },
        readHandler("settings_global_get") { key ->
            Settings.Global.getString(context.contentResolver, key)
        },
        object : ToolHandler {
            override val name = "settings_known_keys"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                ToolResult(true, KNOWN_KEYS)
            }
        },
        object : ToolHandler {
            override val name = "settings_system_set"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                val key = normalizeSettingsKey((arguments["key"] as? JsonPrimitive)?.content.orEmpty())
                if (key.isEmpty()) {
                    return@withContext ToolResult(false, "Укажите ключ настройки в параметре key.")
                }
                val value = (arguments["value"] as? JsonPrimitive)?.content
                    ?: return@withContext ToolResult(false, "Укажите value — значение для записи.")
                // The documented gate: ordinary apps may write Settings.System only after the user
                // grants the WRITE_SETTINGS special access.
                if (!Settings.System.canWrite(context)) {
                    return@withContext ToolResult(
                        false,
                        "Нет доступа на запись Settings.System: разрешение WRITE_SETTINGS не выдано. " +
                            "Откройте системный экран «Изменение системных настроек» (инструмент " +
                            "settings_write_access_request), разрешите доступ этому приложению и повторите запись.",
                    )
                }
                try {
                    // Capture the value BEFORE the write so `undo_last` can restore it. An absent
                    // value (null) means the key was unset; the inverse then deletes it.
                    val previous = Settings.System.getString(context.contentResolver, key)
                    val written = Settings.System.putString(context.contentResolver, key, value)
                    if (!written) {
                        return@withContext ToolResult(
                            false,
                            "Settings.System.putString вернул false для «$key» — значение не записано.",
                        )
                    }
                    // Honest verification: some keys are ignored/overridden by the OEM.
                    val readBack = Settings.System.getString(context.contentResolver, key)
                    if (readBack != value) {
                        ToolResult(
                            false,
                            "Запись «$key» не подтверждена: после записи прочитано «$readBack», ожидалось «$value». " +
                                "Ключ может игнорироваться производителем или подменяться системой.",
                        )
                    } else {
                        // The write is confirmed, so the inverse is known exactly: restore `previous`.
                        undoRecorder.record(
                            toolName = "settings_system_set",
                            description = "Настройка «$key»: было «${previous ?: "не задано"}», стало «$value»",
                            undoKind = UndoKinds.SETTINGS_RESTORE,
                            payload = buildMap {
                                put(UndoPayload.KEY, key)
                                if (previous != null) put(UndoPayload.PREVIOUS_VALUE, previous)
                            },
                        )
                        ToolResult(true, "Записано: $key = $value (проверено повторным чтением).")
                    }
                } catch (e: SecurityException) {
                    ToolResult(false, "Нет доступа на запись настройки «$key»: ${e.message ?: "SecurityException"}.")
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка записи настройки «$key»: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "settings_write_access_request"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                if (Settings.System.canWrite(context)) {
                    return@withContext ToolResult(true, "Доступ на запись Settings.System уже выдан.")
                }
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (intent.resolveActivity(context.packageManager) == null) {
                        return@withContext ToolResult(
                            false,
                            "Системный экран предоставления доступа к системным настройкам недоступен на этом устройстве.",
                        )
                    }
                    context.startActivity(intent)
                    ToolResult(
                        true,
                        "Открыт системный экран «Изменение системных настроек». Разрешите доступ вручную в списке " +
                            "приложений. Результат приходит асинхронно, поэтому доступ проверяется заново при каждой " +
                            "записи — после выдачи разрешения повторите settings_system_set.",
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Не удалось открыть экран предоставления доступа: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "dnd_set"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                val raw = (arguments["mode"] as? JsonPrimitive)?.content?.trim()?.lowercase(Locale.US).orEmpty()
                val filter = when (raw) {
                    "off", "all" -> NotificationManager.INTERRUPTION_FILTER_ALL
                    "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
                    "none" -> NotificationManager.INTERRUPTION_FILTER_NONE
                    else -> return@withContext ToolResult(
                        false,
                        "Укажите mode: off, priority, alarms, none или all.",
                    )
                }
                val nm = context.getSystemService(NotificationManager::class.java)
                    ?: return@withContext ToolResult(false, "NotificationManager недоступен на этом устройстве.")
                if (!nm.isNotificationPolicyAccessGranted()) {
                    return@withContext ToolResult(
                        false,
                        "Нет доступа к политике уведомлений («Не беспокоить»). Разрешите его в системных настройках: " +
                            "«Не беспокоить» → «Доступ к режиму Не беспокоить» → выберите это приложение.",
                    )
                }
                // Android 15 (API 35, targetSdk 35) forbids changing the global filter with
                // setInterruptionFilter(); the supported path is a real AutomaticZenRule owned by
                // our condition provider. On older versions the direct call is still permitted.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    setDndViaAutomaticZenRule(nm, filter)
                } else {
                    setDndViaInterruptionFilter(nm, filter)
                }
            }
        },
    )

    /**
     * Android 15+ (API 35+) path: creates/updates the ANDharness [AutomaticZenRule] owned by
     * [DndConditionProviderService] and activates/deactivates it by publishing the condition state
     * with [NotificationManager.setAutomaticZenRuleState]. The result is verified against
     * [NotificationManager.getCurrentInterruptionFilter] and reported honestly — the system applies
     * the most restrictive policy among all active rules, so another rule can win.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    @Suppress("DEPRECATION")
    private fun setDndViaAutomaticZenRule(nm: NotificationManager, filter: Int): ToolResult {
        val owner = ComponentName(context, DndConditionProviderService::class.java)
        val conditionId = DndConditionProviderService.CONDITION_ID
        val enabled = filter != NotificationManager.INTERRUPTION_FILTER_ALL
        return try {
            val existingId = nm.automaticZenRules.entries.firstOrNull { it.value.owner == owner }?.key
            val rule = AutomaticZenRule(
                DndConditionProviderService.RULE_NAME,
                owner,
                conditionId,
                filter,
                enabled,
            )
            val ruleId = if (existingId == null) {
                nm.addAutomaticZenRule(rule)
                    ?: return ToolResult(
                        false,
                        "Система не создала правило AutomaticZenRule для режима «Не беспокоить».",
                    )
            } else {
                if (!nm.updateAutomaticZenRule(existingId, rule)) {
                    return ToolResult(
                        false,
                        "updateAutomaticZenRule вернул false — правило «$existingId» не обновлено.",
                    )
                }
                existingId
            }

            val conditionState = if (enabled) Condition.STATE_TRUE else Condition.STATE_FALSE
            nm.setAutomaticZenRuleState(
                ruleId,
                Condition(conditionId, DndConditionProviderService.RULE_NAME, conditionState),
            )

            val actual = nm.currentInterruptionFilter
            val ruleActive = nm.getAutomaticZenRule(ruleId)?.isEnabled
            if (actual == filter) {
                ToolResult(
                    true,
                    "Режим «Не беспокоить» переключён через правило AutomaticZenRule: " +
                        "${filterName(filter)} ($filter). Правило «$ruleId», включено=$ruleActive, " +
                        "состояние условия=$conditionState.",
                )
            } else {
                ToolResult(
                    false,
                    "Запрошен режим ${filterName(filter)} ($filter), но система применяет " +
                        "${filterName(actual)} ($actual): активно более строгое правило AutomaticZenRule " +
                        "или система отклонила изменение. Правило Меха «$ruleId» включено=$ruleActive.",
                )
            }
        } catch (e: SecurityException) {
            ToolResult(
                false,
                "Система отклонила управление правилом AutomaticZenRule: ${e.message ?: "SecurityException"}.",
            )
        } catch (e: IllegalArgumentException) {
            ToolResult(
                false,
                "AutomaticZenRule отклонён системой: ${e.message ?: "IllegalArgumentException"} " +
                    "(служба-провайдер условия должна быть объявлена и включена).",
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка смены режима «Не беспокоить» через AutomaticZenRule: ${e.message}")
        }
    }

    /**
     * Pre-Android-15 fallback: [NotificationManager.setInterruptionFilter] still changes the global
     * filter there, so it is used and then verified.
     * https://developer.android.com/reference/android/app/NotificationManager#setInterruptionFilter(int)
     */
    private fun setDndViaInterruptionFilter(nm: NotificationManager, filter: Int): ToolResult = try {
        nm.setInterruptionFilter(filter)
        val actual = nm.currentInterruptionFilter
        if (actual == filter) {
            ToolResult(true, "Режим «Не беспокоить» переключён: ${filterName(filter)} ($filter).")
        } else {
            ToolResult(
                false,
                "Запрошен режим ${filterName(filter)} ($filter), но система применяет " +
                    "${filterName(actual)} ($actual): активно более строгое правило или система отклонила изменение.",
            )
        }
    } catch (e: SecurityException) {
        ToolResult(
            false,
            "Система отклонила изменение режима «Не беспокоить»: ${e.message ?: "SecurityException"}.",
        )
    } catch (e: Exception) {
        ToolResult(false, "Ошибка смены режима «Не беспокоить»: ${e.message}")
    }

    /**
     * Builds a read-only handler that looks up a single Settings key with [read].
     *
     * Honesty rules:
     * - a blank/missing `key` argument is a clear usage error (never a fake lookup),
     * - a `null` value means the key is unknown/unset and is reported as such,
     * - a [SecurityException] (some entries are restricted to system apps) is reported honestly
     *   instead of being swallowed or turned into a success.
     */
    private fun readHandler(
        toolName: String,
        read: (String) -> String?,
    ): ToolHandler = object : ToolHandler {
        override val name = toolName
        override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val key = normalizeSettingsKey((arguments["key"] as? JsonPrimitive)?.content.orEmpty())
            if (key.isEmpty()) {
                return@withContext ToolResult(false, "Укажите ключ настройки в параметре key.")
            }
            try {
                val value = read(key)
                if (value == null) {
                    ToolResult(
                        false,
                        "Настройка «$key» не найдена или не задана: Settings вернул null. " +
                            "Проверьте точное имя ключа; список частых ключей — в инструменте settings_known_keys.",
                    )
                } else {
                    ToolResult(true, "$key = $value")
                }
            } catch (e: SecurityException) {
                ToolResult(
                    false,
                    "Нет доступа к настройке «$key»: чтение ограничено системой (${e.message ?: "SecurityException"}).",
                )
            } catch (e: Exception) {
                ToolResult(false, "Ошибка чтения настройки «$key»: ${e.message}")
            }
        }
    }

    private fun filterName(filter: Int): String = when (filter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> "все уведомления (ALL)"
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "только приоритетные (PRIORITY)"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "не беспокоить (NONE)"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "только будильники (ALARMS)"
        NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> "неизвестно (UNKNOWN)"
        else -> "неизвестно ($filter)"
    }

    private companion object {
        private val KNOWN_KEYS: String = """
            Часто используемые ключи Settings (значение читается соответствующим инструментом).

            Settings.System → settings_system_get:
            - screen_brightness — яркость экрана, 0–255
            - screen_brightness_mode — режим яркости (0 = ручной, 1 = авто)
            - screen_off_timeout — таймаут выключения экрана, мс
            - font_scale — масштаб шрифта (1.0 = обычный)
            - time_12_24 — формат времени (12 или 24)
            - vibrate_on — вибрация (1 = включена)
            - sound_effects_enabled — звуковые эффекты (1 = включены)

            Settings.Secure → settings_secure_get:
            - android_id — уникальный идентификатор устройства
            - default_input_method — текущая клавиатура (ID метода ввода)
            - enabled_accessibility_services — включённые службы доступности
            - accessibility_enabled — включена ли доступность (1 = да)

            Settings.Global → settings_global_get:
            - airplane_mode_on — режим полёта (1 = включён)
            - wifi_on — Wi-Fi включён (1 = да)
            - adb_enabled — отладка по USB (1 = включена)
            - device_name — имя устройства
        """.trimIndent()
    }
}

/**
 * Normalizes a Settings key to the exact form Android expects.
 *
 * Framework keys in [Settings.System], [Settings.Secure] and [Settings.Global] are all lowercase
 * strings (e.g. `screen_brightness`, `wifi_on`, `android_id`) and [Settings.System.getString] /
 * [Settings.System.putString] are case-sensitive, so a key typed in another case silently misses.
 * Trimming and lowercasing (Locale.US) makes the get/set tools accept any casing and keeps read and
 * write consistent:
 * https://developer.android.com/reference/android/provider/Settings.System#getString(android.content.ContentResolver,%20java.lang.String)
 */
internal fun normalizeSettingsKey(raw: String): String =
    raw.trim().lowercase(Locale.US)

