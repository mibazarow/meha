package com.andharness.ru.data.tools.automation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.andharness.ru.data.automation.AutomationTriggerManager
import com.andharness.ru.data.tools.sensors.HealthSensorAvailability
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.automation.AutomationEventLog
import com.andharness.ru.domain.automation.AutomationEventRepository
import com.andharness.ru.domain.automation.AutomationEventRetention
import com.andharness.ru.domain.automation.AutomationRule
import com.andharness.ru.domain.automation.AutomationRuleRepository
import com.andharness.ru.domain.automation.AutomationScheduler
import com.andharness.ru.domain.automation.AutomationSpec
import com.andharness.ru.domain.automation.TriggerConfigs
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.service.AccessibilityAutomationService
import com.andharness.ru.service.NotificationRelayService
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.andharness.ru.data.tools.str

/**
 * Builds one [ToolHandler] per automation tool (update.md §11.7): `automation_rule_create`,
 * `automation_rule_list`, `automation_rule_toggle`, `automation_rule_delete` and `events_recent`
 * (the persistent log of rules that actually fired).
 *
 * A rule is a "when <trigger> then <action>" pair on REAL, non-root sources only (see
 * [AutomationSpec]). The handlers validate every config and report honest errors instead of storing
 * a rule that could never fire.
 */
class AutomationToolsHandler(
    private val context: Context,
    private val repository: AutomationRuleRepository,
    private val eventRepository: AutomationEventRepository,
    private val scheduler: AutomationScheduler,
    private val triggerManager: Lazy<AutomationTriggerManager>,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "automation_rule_create"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val name = arguments.str("name")
                        ?: return@withContext ToolResult(false, "Укажите name — название правила.")
                    val rawTrigger = arguments.str("trigger_type")
                        ?: return@withContext ToolResult(false, "Укажите trigger_type (${AutomationSpec.TRIGGER_TYPES.joinToString(", ")}).")
                    val triggerType = AutomationSpec.normalizeTrigger(rawTrigger)
                        ?: return@withContext ToolResult(false, "Неизвестный trigger_type «$rawTrigger». Доступны: ${AutomationSpec.TRIGGER_TYPES.joinToString(", ")}.")
                    val rawAction = arguments.str("action_type")
                        ?: return@withContext ToolResult(false, "Укажите action_type (${AutomationSpec.ACTION_TYPES.joinToString(", ")}).")
                    val actionType = AutomationSpec.normalizeAction(rawAction)
                        ?: return@withContext ToolResult(false, "Неизвестный action_type «$rawAction». Доступны: ${AutomationSpec.ACTION_TYPES.joinToString(", ")}.")
                    val triggerConfig = arguments.str("trigger_config").orEmpty()
                    val actionConfig = arguments.str("action_config").orEmpty()

                    AutomationSpec.validateTrigger(triggerType, triggerConfig)?.let {
                        return@withContext ToolResult(false, it)
                    }
                    healthSensorError(triggerType)?.let {
                        return@withContext ToolResult(false, it)
                    }
                    AutomationSpec.validateAction(actionType, actionConfig)?.let {
                        return@withContext ToolResult(false, it)
                    }

                    val id = repository.create(
                        AutomationRule(
                            id = 0,
                            name = name,
                            enabled = true,
                            triggerType = triggerType,
                            triggerConfig = triggerConfig,
                            actionType = actionType,
                            actionConfig = actionConfig
                        )
                    )
                    val rule = repository.getById(id)
                    if (rule != null) scheduler.schedule(rule)
                    triggerManager.get().refresh()

                    ToolResult(true, buildString {
                        append("Создано правило #$id «$name»: ")
                        append("когда ${AutomationSpec.describeTrigger(triggerType, triggerConfig)} → ")
                        append(AutomationSpec.describeAction(actionType, actionConfig))
                        availabilityWarning(triggerType)?.let { append("\n$it") }
                    })
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "automation_rule_list"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val rules = repository.getAll()
                    if (rules.isEmpty()) {
                        ToolResult(true, "Правил автоматизации нет.")
                    } else {
                        ToolResult(true, rules.joinToString("\n") { r ->
                            val last = r.lastRunAt?.let { " | последний запуск: $it" }.orEmpty()
                            "#${r.id} | ${if (r.enabled) "вкл" else "выкл"} | ${
                                AutomationSpec.describeTrigger(r.triggerType, r.triggerConfig)
                            } → ${AutomationSpec.describeAction(r.actionType, r.actionConfig)}$last"
                        })
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "automation_rule_toggle"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val id = arguments.str("id")?.toLongOrNull()
                        ?: return@withContext ToolResult(false, "Укажите id правила (см. automation_rule_list).")
                    val enabled = (arguments["enabled"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
                        ?: return@withContext ToolResult(false, "Укажите enabled (true/false).")
                    if (!repository.setEnabled(id, enabled)) {
                        return@withContext ToolResult(false, "Правило #$id не найдено.")
                    }
                    val rule = repository.getById(id)
                    if (rule != null) {
                        if (enabled) scheduler.schedule(rule) else scheduler.cancel(id)
                    }
                    triggerManager.get().refresh()
                    ToolResult(
                        true,
                        "Правило #$id ${if (enabled) "включено" else "выключено"}." +
                            if (enabled) availabilityWarning(rule?.triggerType)?.let { "\n$it" }.orEmpty() else ""
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "automation_rule_delete"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val id = arguments.str("id")?.toLongOrNull()
                        ?: return@withContext ToolResult(false, "Укажите id правила (см. automation_rule_list).")
                    if (!repository.delete(id)) {
                        return@withContext ToolResult(false, "Правило #$id не найдено.")
                    }
                    scheduler.cancel(id)
                    triggerManager.get().refresh()
                    ToolResult(true, "Правило #$id удалено.")
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "events_recent"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val limit = AutomationEventRetention.bound(arguments.str("limit")?.toIntOrNull())
                    val type = arguments.str("type")?.trim()?.takeIf { it.isNotEmpty() }
                    val events = if (type != null) {
                        eventRepository.recentByType(type, limit)
                    } else {
                        eventRepository.recent(limit)
                    }
                    val header = if (type != null) {
                        "Последние события автоматизации (тип $type):"
                    } else {
                        "Последние события автоматизации:"
                    }
                    if (events.isEmpty()) {
                        val suffix = if (type != null) " типа $type" else ""
                        ToolResult(true, "События автоматизации$suffix не найдены.")
                    } else {
                        ToolResult(true, "$header\n${AutomationEventLog.format(events)}")
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка чтения журнала автоматизации: ${e.message}")
                }
            }
        },
    )

    /**
     * Honest note about the runtime requirements of a trigger — e.g. that power/screen/network
     * triggers only work while the app process is alive, or that a11y / notification access must be
     * enabled. Returns null when there is nothing to warn about.
     */
    private fun availabilityWarning(triggerType: String?): String? = when (triggerType) {
        AutomationSpec.TRIGGER_TIME ->
            "Триггер time выполняет WorkManager — срабатывание неточное (система может задержать)."
        AutomationSpec.TRIGGER_CHARGING, AutomationSpec.TRIGGER_BATTERY_LOW,
        AutomationSpec.TRIGGER_SCREEN_ON, AutomationSpec.TRIGGER_SCREEN_OFF,
        AutomationSpec.TRIGGER_NETWORK, AutomationSpec.TRIGGER_WIFI_CONNECTED ->
            "Триггер работает только пока живёт процесс приложения (после перезапуска правила — при следующем запуске)."
        AutomationSpec.TRIGGER_APP_OPENED ->
            if (AccessibilityAutomationService.isEnabled()) null
            else "Нужен включённый доступ спецвозможностей (Accessibility), иначе триггер не сработает."
        AutomationSpec.TRIGGER_NOTIFICATION ->
            if (NotificationRelayService.isEnabled()) null
            else "Нужен выданный доступ к уведомлениям (Notification access), иначе триггер не сработает."
        AutomationSpec.TRIGGER_SMS ->
            if (granted(Manifest.permission.RECEIVE_SMS)) null
            else "Нужно разрешение на приём SMS (RECEIVE_SMS), иначе триггер не сработает."
        AutomationSpec.TRIGGER_CALENDAR_EVENT ->
            if (granted(Manifest.permission.READ_CALENDAR)) {
                "Проверка календаря идёт через WorkManager (раз в ~15 минут) — срабатывание может быть на несколько минут позже."
            } else {
                "Нужно разрешение на чтение календаря (READ_CALENDAR), иначе триггер не сработает."
            }
        AutomationSpec.TRIGGER_BLE_PRESENCE ->
            if (blePermissionsGranted()) {
                "Сканирование BLE работает только пока живёт процесс приложения и останавливается при выключенном экране."
            } else {
                "Нужны разрешения Bluetooth (сканирование/подключение) или геолокация, иначе триггер не сработает."
            }
        AutomationSpec.TRIGGER_SOUND_EVENT ->
            if (granted(Manifest.permission.RECORD_AUDIO)) {
                "Монитор уровня звука работает только пока живёт процесс приложения и расходует батарею; " +
                    "пока открыт микрофон, другие приложения не могут писать звук."
            } else {
                "Нужно разрешение на запись звука (RECORD_AUDIO), иначе триггер не сработает."
            }
        AutomationSpec.TRIGGER_HEART_RATE_THRESHOLD ->
            if (granted(Manifest.permission.BODY_SENSORS)) {
                "Пульс отслеживается, только пока живёт процесс приложения."
            } else {
                "Нужно разрешение на датчик пульса (BODY_SENSORS), иначе триггер не сработает."
            }
        AutomationSpec.TRIGGER_STEPS_REACHED ->
            if (granted(Manifest.permission.ACTIVITY_RECOGNITION)) {
                "Отсчёт шагов идёт от момента включения правила и работает только пока живёт процесс приложения."
            } else {
                "Нужно разрешение ACTIVITY_RECOGNITION (распознавание движения), иначе триггер не сработает."
            }
        AutomationSpec.TRIGGER_NO_MOVEMENT ->
            "Триггер работает только пока живёт процесс приложения: отсчёт «нет движения» начинается " +
                "с момента включения правила."
        AutomationSpec.TRIGGER_SLEEP_WINDOW ->
            "Это эвристика (окно времени + экран выключен + телефон неподвижен), а не измерение фаз сна; " +
                "работает только пока живёт процесс приложения."
        else -> null
    }

    /**
     * Honest refusal for a health trigger whose required sensor is absent on this device. A missing
     * sensor is reported as an error (never a silent success) so the agent can tell the user instead
     * of storing a rule that can never fire. Returns null for non-health triggers and when a suitable
     * sensor exists.
     */
    private fun healthSensorError(triggerType: String): String? {
        if (TriggerConfigs.healthSensorAlternatives(triggerType) == null) return null
        val available = HealthSensorAvailability.available(context)
        val missing = TriggerConfigs.missingHealthSensors(triggerType, available) ?: return null
        val names = missing.joinToString(", ") { TriggerConfigs.healthSensorName(it) }
        return "Триггер «$triggerType» недоступен на этом устройстве: не найден датчик ($names). " +
            "Список датчиков можно посмотреть инструментом health_sensors_list."
    }

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun blePermissionsGranted(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        granted(Manifest.permission.ACCESS_FINE_LOCATION)
    }

}
