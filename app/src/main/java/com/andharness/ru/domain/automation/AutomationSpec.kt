package com.andharness.ru.domain.automation

/**
 * Catalog, validation and matching for automation rules (update.md §11.6).
 *
 * Pure and side-effect free so it can be unit-tested on the JVM: it holds the canonical trigger /
 * action names, the config formats, and the "does this event fire this rule?" decision. It never
 * touches Android APIs.
 *
 * Honest capability boundaries (see [TriggerSource]):
 * - [TRIGGER_TIME] is evaluated by WorkManager, so it is **inexact** (the OS may delay it).
 * - [TRIGGER_CHARGING], [TRIGGER_BATTERY_LOW], [TRIGGER_SCREEN_ON], [TRIGGER_SCREEN_OFF] are
 *   runtime-receiver triggers: they only fire while the app process is alive.
 * - [TRIGGER_BOOT] is a manifest receiver and survives process death.
 * - [TRIGGER_WIFI_CONNECTED], [TRIGGER_NETWORK] come from the default network callback, which
 *   again only exists while the process is alive.
 * - [TRIGGER_APP_OPENED] needs the accessibility service enabled; [TRIGGER_NOTIFICATION] needs the
 *   notification-listener access granted; [TRIGGER_SMS] needs the RECEIVE_SMS permission and is fed
 *   by the manifest `SmsReceiver` (https://developer.android.com/reference/android/Manifest.permission#RECEIVE_SMS).
 * - update.md idea #18 adds: [TRIGGER_CALENDAR_EVENT] (periodic WorkManager check of the calendar
 *   provider, so a few minutes late; needs READ_CALENDAR), [TRIGGER_BLE_PRESENCE]
 *   (in-process BLE scan with its own RSSI threshold; needs the Bluetooth/location permissions),
 *   [TRIGGER_SOUND_EVENT] (in-process microphone level monitor; needs RECORD_AUDIO and costs battery),
 *   [TRIGGER_GEO_DISTANCE] (in-process location poll that fires once the phone moved at least
 *   `distance_m` from the last stored point; needs ACCESS_FINE_LOCATION and only runs while the
 *   process is alive) and [TRIGGER_WIFI_OPEN] (in-process Wi-Fi scan that fires when an unsecured
 *   network becomes visible; needs NEARBY_WIFI_DEVICES on API 31+ / ACCESS_FINE_LOCATION below and
 *   only runs while the process is alive).
 * - Health checklist adds the `SensorManager`-based triggers [TRIGGER_HEART_RATE_THRESHOLD],
 *   [TRIGGER_STEPS_REACHED] and [TRIGGER_NO_MOVEMENT] (fed by `HealthSensorMonitor`, alive-process
 *   only; BODY_SENSORS / ACTIVITY_RECOGNITION as needed), plus the HONEST HEURISTIC
 *   [TRIGGER_SLEEP_WINDOW] — Android has no public sleep-stage sensor, so it only combines a
 *   user-defined time window with "screen off + still" and must never be presented as sleep tracking.
 *   Subscribing/creating a rule whose required sensor is absent fails honestly, never silently.
 */
object AutomationSpec {

    const val TRIGGER_TIME = "time"
    const val TRIGGER_CHARGING = "charging"
    const val TRIGGER_BATTERY_LOW = "battery_low"
    const val TRIGGER_SCREEN_ON = "screen_on"
    const val TRIGGER_SCREEN_OFF = "screen_off"
    const val TRIGGER_BOOT = "boot"
    const val TRIGGER_WIFI_CONNECTED = "wifi_connected"
    const val TRIGGER_NETWORK = "network"
    const val TRIGGER_APP_OPENED = "app_opened"
    const val TRIGGER_NOTIFICATION = "notification"

    /** BUG-006: incoming SMS delivered by the manifest `SmsReceiver` (needs RECEIVE_SMS). */
    const val TRIGGER_SMS = "sms"

    /** update.md idea #18 — deeper triggers. */
    const val TRIGGER_CALENDAR_EVENT = "calendar_event"
    const val TRIGGER_BLE_PRESENCE = "ble_presence"
    const val TRIGGER_SOUND_EVENT = "sound_event"
    const val TRIGGER_GEO_DISTANCE = "geo_distance"
    const val TRIGGER_WIFI_OPEN = "wifi_open"

    /** Health checklist: SensorManager-based triggers (see `HealthSensorMonitor`). */
    const val TRIGGER_HEART_RATE_THRESHOLD = "heart_rate_threshold"
    const val TRIGGER_STEPS_REACHED = "steps_reached"
    const val TRIGGER_NO_MOVEMENT = "no_movement"

    /**
     * Honest sleep heuristic: a user-defined time window plus "screen off + still". Android has no
     * public sleep-stage sensor, so this is NOT sleep tracking and must be labelled as a heuristic.
     */
    const val TRIGGER_SLEEP_WINDOW = "sleep_window"

    const val ACTION_START_CHAT = "start_chat"
    const val ACTION_AGENT_TASK = "agent_task"
    const val ACTION_NOTIFY = "notify"

    val TRIGGER_TYPES: List<String> = listOf(
        TRIGGER_TIME,
        TRIGGER_CHARGING,
        TRIGGER_BATTERY_LOW,
        TRIGGER_SCREEN_ON,
        TRIGGER_SCREEN_OFF,
        TRIGGER_BOOT,
        TRIGGER_WIFI_CONNECTED,
        TRIGGER_NETWORK,
        TRIGGER_APP_OPENED,
        TRIGGER_NOTIFICATION,
        TRIGGER_SMS,
        TRIGGER_CALENDAR_EVENT,
        TRIGGER_BLE_PRESENCE,
        TRIGGER_SOUND_EVENT,
        TRIGGER_GEO_DISTANCE,
        TRIGGER_WIFI_OPEN,
        TRIGGER_HEART_RATE_THRESHOLD,
        TRIGGER_STEPS_REACHED,
        TRIGGER_NO_MOVEMENT,
        TRIGGER_SLEEP_WINDOW
    )

    val ACTION_TYPES: List<String> = listOf(ACTION_START_CHAT, ACTION_AGENT_TASK, ACTION_NOTIFY)

    /** Where a trigger's events actually come from — surfaced so the agent can be honest about limits. */
    enum class TriggerSource {
        WORK_MANAGER,
        RUNTIME_RECEIVER,
        MANIFEST_RECEIVER,
        NETWORK_CALLBACK,
        ACCESSIBILITY,
        NOTIFICATION_LISTENER,
        CALENDAR_CHECK,
        BLE_SCAN,
        AUDIO_MONITOR,
        LOCATION_MONITOR,
        WIFI_SCAN,
        /** Health triggers fed by `SensorManager` while the process is alive (`HealthSensorMonitor`). */
        SENSOR_MONITOR,
        /** The honest `sleep_window` heuristic (window + screen off + still), not a sleep sensor. */
        SLEEP_HEURISTIC
    }

    fun sourceOf(triggerType: String): TriggerSource? = when (triggerType) {
        TRIGGER_TIME -> TriggerSource.WORK_MANAGER
        TRIGGER_CHARGING, TRIGGER_BATTERY_LOW, TRIGGER_SCREEN_ON, TRIGGER_SCREEN_OFF -> TriggerSource.RUNTIME_RECEIVER
        TRIGGER_BOOT -> TriggerSource.MANIFEST_RECEIVER
        TRIGGER_WIFI_CONNECTED, TRIGGER_NETWORK -> TriggerSource.NETWORK_CALLBACK
        TRIGGER_APP_OPENED -> TriggerSource.ACCESSIBILITY
        TRIGGER_NOTIFICATION -> TriggerSource.NOTIFICATION_LISTENER
        TRIGGER_SMS -> TriggerSource.MANIFEST_RECEIVER
        TRIGGER_CALENDAR_EVENT -> TriggerSource.CALENDAR_CHECK
        TRIGGER_BLE_PRESENCE -> TriggerSource.BLE_SCAN
        TRIGGER_SOUND_EVENT -> TriggerSource.AUDIO_MONITOR
        TRIGGER_GEO_DISTANCE -> TriggerSource.LOCATION_MONITOR
        TRIGGER_WIFI_OPEN -> TriggerSource.WIFI_SCAN
        TRIGGER_HEART_RATE_THRESHOLD, TRIGGER_STEPS_REACHED, TRIGGER_NO_MOVEMENT -> TriggerSource.SENSOR_MONITOR
        TRIGGER_SLEEP_WINDOW -> TriggerSource.SLEEP_HEURISTIC
        else -> null
    }

    /** Normalises a user-supplied trigger string (case/space tolerant) to a canonical name, or null. */
    fun normalizeTrigger(raw: String): String? {
        val key = raw.trim().lowercase()
            .replace('-', '_')
            .replace(' ', '_')
        return TRIGGER_TYPES.firstOrNull { it == key }
    }

    /** Normalises a user-supplied action string to a canonical name, or null. */
    fun normalizeAction(raw: String): String? {
        val key = raw.trim().lowercase()
            .replace('-', '_')
            .replace(' ', '_')
        return ACTION_TYPES.firstOrNull { it == key }
    }

    /** Parses a `HH:MM` (or `HH.MM` / `HH-MM`) 24-hour time, or null when malformed/out of range. */
    fun parseTime(config: String): Pair<Int, Int>? {
        val m = Regex("^\\s*(\\d{1,2})[:.\\-](\\d{2})\\s*$").find(config.trim()) ?: return null
        val hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }

    /**
     * Parsed `notify` action config: `title | text` with an OPTIONAL third `| chatId` segment.
     * With no separator the whole config is the text and the title falls back to "Меха".
     */
    data class NotifySpec(val title: String, val text: String, val chatId: Long?)

    fun parseNotify(config: String): NotifySpec? {
        val raw = config.trim()
        if (raw.isEmpty()) return null
        val parts = raw.split('|')
        val title: String
        val text: String
        var chatId: Long? = null
        when {
            parts.size == 1 -> {
                title = DEFAULT_NOTIFY_TITLE
                text = parts[0].trim()
            }
            else -> {
                title = parts[0].trim().ifEmpty { DEFAULT_NOTIFY_TITLE }
                text = parts[1].trim()
                if (parts.size >= 3) chatId = parts[2].trim().toLongOrNull()?.takeIf { it > 0L }
            }
        }
        if (text.isEmpty()) return null
        return NotifySpec(title, text, chatId)
    }

    const val DEFAULT_NOTIFY_TITLE = "Меха"

    /**
     * Validates a trigger config, returning a human-readable Russian error or null when the config is
     * acceptable. Callers reject the rule on a non-null result (honest failure, never a silent stub).
     */
    fun validateTrigger(triggerType: String, config: String): String? = when (triggerType) {
        TRIGGER_TIME -> if (parseTime(config) == null) {
            "Для триггера «time» нужен trigger_config в формате HH:MM (например 08:30)."
        } else null
        TRIGGER_APP_OPENED -> null // blank = любое приложение; иначе имя пакета
        TRIGGER_NOTIFICATION -> null // blank = любое уведомление; иначе пакет/подстрока
        TRIGGER_SMS -> null // оба фильтра необязательны: blank = любое входящее SMS
        TRIGGER_CALENDAR_EVENT -> if (TriggerConfigs.parseCalendar(config) == null) {
            "Для триггера «calendar_event» нужен trigger_config вида «title_contains=встреча; minutes_before=5» " +
                "(минуты 0–1440, по умолчанию 5; пусто — любое событие)."
        } else null
        TRIGGER_BLE_PRESENCE -> if (TriggerConfigs.parseBlePresence(config) == null) {
            "Для триггера «ble_presence» нужен trigger_config с address (MAC) или name_contains, " +
                "например «name_contains=Mi Band; rssi_threshold=-70; timeout_sec=15» " +
                "(порог RSSI от -100 до 0 dBm, таймаут 1–600 с)."
        } else null
        TRIGGER_SOUND_EVENT -> if (TriggerConfigs.parseSoundEvent(config) == null) {
            "Для триггера «sound_event» нужен trigger_config вида «level_threshold=-20; sustain_ms=500» " +
                "(порог от -90 до 0 dBFS, длительность 100–60000 мс)."
        } else null
        TRIGGER_GEO_DISTANCE -> if (TriggerConfigs.parseGeoDistance(config) == null) {
            "Для триггера «geo_distance» нужен trigger_config с distance_m, например " +
                "«distance_m=300; min_interval_sec=600» " +
                "(distance_m от 10 до 1000000 метров, min_interval_sec 60–86400 с, по умолчанию 600)."
        } else null
        TRIGGER_WIFI_OPEN -> if (TriggerConfigs.parseOpenWifi(config) == null) {
            "Для триггера «wifi_open» нужен trigger_config вида «ssid_contains=…; min_interval_sec=600» " +
                "(ssid_contains необязателен, без него — любая открытая сеть; " +
                "min_interval_sec от 60 до 86400 с, по умолчанию 600)."
        } else null
        TRIGGER_HEART_RATE_THRESHOLD -> if (TriggerConfigs.parseHeartRate(config) == null) {
            "Для триггера «heart_rate_threshold» нужен trigger_config «above=100» или «below=50» " +
                "(bpm 20–250, строгое сравнение) и необязательный «dwell_sec=10» (0–3600 с, " +
                "по умолчанию 0 — сразу)."
        } else null
        TRIGGER_STEPS_REACHED -> if (TriggerConfigs.parseSteps(config) == null) {
            "Для триггера «steps_reached» нужен trigger_config «target=5000» " +
                "(шагов 1–1000000; отсчёт от момента подписки)."
        } else null
        TRIGGER_NO_MOVEMENT -> if (TriggerConfigs.parseNoMovement(config) == null) {
            "Для триггера «no_movement» нужен trigger_config «minutes=30» " +
                "(1–1440 минут без значимого движения)."
        } else null
        TRIGGER_SLEEP_WINDOW -> if (TriggerConfigs.parseSleepWindow(config) == null) {
            "Для триггера «sleep_window» нужен trigger_config «start=23:00; end=07:00». " +
                "Это эвристика (окно + экран выключен + телефон неподвижен), а не измерение сна."
        } else null
        else -> null // конфиг не нужен и игнорируется
    }

    /** Validates an action config, returning a human-readable Russian error or null when acceptable. */
    fun validateAction(actionType: String, config: String): String? = when (actionType) {
        ACTION_START_CHAT -> if (config.isBlank()) {
            "Для действия «start_chat» нужен action_config — текст запроса агенту."
        } else null
        ACTION_AGENT_TASK -> if (config.isBlank()) {
            "Для действия «agent_task» нужен action_config — шаблон запроса агенту " +
                "(подстановки {trigger}, {value}, {payload}, {name})."
        } else null
        ACTION_NOTIFY -> if (parseNotify(config) == null) {
            "Для действия «notify» нужен action_config «заголовок | текст» (можно добавить «| chatId»)."
        } else null
        else -> "Неизвестный тип действия: $actionType"
    }

    /**
     * Validates a whole rule (trigger + action) with the same canonical checks
     * `automation_rule_create` uses, returning the first human-readable Russian error or null when
     * the rule is acceptable. Trigger/action types are normalised first, so a rule stored in
     * another case (`TIME`, `NOTIFY`) is checked against its canonical form. Used by the
     * accept-suggestion path so no invalid rule can enter the database.
     */
    fun validateRule(
        triggerType: String,
        triggerConfig: String,
        actionType: String,
        actionConfig: String,
    ): String? {
        val trigger = normalizeTrigger(triggerType) ?: triggerType
        val action = normalizeAction(actionType) ?: actionType
        return validateTrigger(trigger, triggerConfig) ?: validateAction(action, actionConfig)
    }

    /**
     * Decides whether [event] should fire [rule]: the rule must be enabled, its trigger type must
     * match, and any config filter (package for `app_opened`, package/title/text substring for
     * `notification`, sender/body substring for `sms`) must match. A targeted
     * [AutomationEvent.ruleId] restricts to that rule.
     */
    fun matches(rule: AutomationRule, event: AutomationEvent): Boolean {
        if (!rule.enabled) return false
        if (event.ruleId != null && rule.id != event.ruleId) return false
        if (rule.triggerType != event.triggerType) return false
        val wanted = rule.triggerConfig.trim()
        return when (rule.triggerType) {
            TRIGGER_APP_OPENED -> wanted.isEmpty() ||
                wanted.equals(event.packageName.orEmpty(), ignoreCase = true)
            TRIGGER_NOTIFICATION -> {
                if (wanted.isEmpty()) {
                    true
                } else {
                    val haystack = listOfNotNull(event.packageName, event.title, event.text)
                        .joinToString(" ")
                    haystack.contains(wanted, ignoreCase = true)
                }
            }
            // `value` carries the sender, `payload` the (bounded) body — see SmsReceiver.
            TRIGGER_SMS -> TriggerConfigs.parseSms(wanted).matches(event.value, event.payload)
            TRIGGER_CALENDAR_EVENT ->
                TriggerConfigs.parseCalendar(wanted)?.matchesTitle(event.value) == true
            TRIGGER_BLE_PRESENCE -> {
                val spec = TriggerConfigs.parseBlePresence(wanted)
                spec != null && spec.matches(event.value, event.name, event.rssi)
            }
            TRIGGER_SOUND_EVENT -> {
                val spec = TriggerConfigs.parseSoundEvent(wanted)
                spec != null && event.level != null && spec.triggers(event.level)
            }
            TRIGGER_GEO_DISTANCE -> {
                // The monitor already applied the debounce and put the moved distance (metres) into
                // `value`, so `matches` only re-checks the threshold (honest, like the BLE path).
                val spec = TriggerConfigs.parseGeoDistance(wanted)
                val moved = event.value?.trim()?.toDoubleOrNull()
                spec != null && moved != null && spec.triggers(moved)
            }
            TRIGGER_WIFI_OPEN -> {
                // The monitor already filtered to OPEN networks and applied the dedupe/debounce, so
                // `matches` only re-checks the SSID filter against the reported SSID in `value`.
                val spec = TriggerConfigs.parseOpenWifi(wanted)
                spec != null && spec.matchesSsid(event.value)
            }
            TRIGGER_HEART_RATE_THRESHOLD -> {
                // The monitor applied the dwell state machine and put the observed bpm into `value`;
                // here only the (strict) threshold is re-checked, like the BLE/wifi paths.
                val spec = TriggerConfigs.parseHeartRate(wanted)
                val bpm = event.value?.trim()?.toIntOrNull()
                spec != null && bpm != null && spec.holds(bpm)
            }
            TRIGGER_STEPS_REACHED -> {
                // `value` carries the step DELTA since the baseline captured at subscribe time.
                val spec = TriggerConfigs.parseSteps(wanted)
                val steps = event.value?.trim()?.toLongOrNull()
                spec != null && steps != null && steps >= spec.target
            }
            TRIGGER_NO_MOVEMENT -> {
                // `value` carries the whole minutes without significant motion.
                val spec = TriggerConfigs.parseNoMovement(wanted)
                val idle = event.value?.trim()?.toLongOrNull()
                spec != null && idle != null && idle >= spec.idleMinutes
            }
            // The sleep heuristic has already been applied by the monitor (window + screen off +
            // still), so there is nothing further to filter here.
            TRIGGER_SLEEP_WINDOW -> true
            else -> true
        }
    }

    /** Short human-readable Russian description of a trigger, used by `automation_rule_list`. */
    fun describeTrigger(triggerType: String, config: String): String {
        val cfg = config.trim()
        return when (triggerType) {
            TRIGGER_TIME -> {
                val hhmm = parseTime(cfg)?.let { "%02d:%02d".format(it.first, it.second) }
                    ?: cfg.ifEmpty { "—" }
                "в $hhmm (WorkManager, неточно)"
            }
            TRIGGER_CHARGING -> "подключение зарядки"
            TRIGGER_BATTERY_LOW -> "низкий заряд батареи"
            TRIGGER_SCREEN_ON -> "включение экрана"
            TRIGGER_SCREEN_OFF -> "выключение экрана"
            TRIGGER_BOOT -> "загрузка устройства"
            TRIGGER_WIFI_CONNECTED -> "подключение к Wi-Fi"
            TRIGGER_NETWORK -> "появление сети"
            TRIGGER_APP_OPENED -> "открытие приложения" + if (cfg.isEmpty()) " (любого)" else ": $cfg"
            TRIGGER_NOTIFICATION -> "уведомление" + if (cfg.isEmpty()) " (любое)" else ": $cfg"
            TRIGGER_SMS -> {
                val spec = TriggerConfigs.parseSms(cfg)
                when {
                    spec.fromContains != null && spec.textContains != null ->
                        "SMS от «${spec.fromContains}» с текстом «${spec.textContains}»"
                    spec.fromContains != null -> "SMS от «${spec.fromContains}»"
                    spec.textContains != null -> "SMS с текстом «${spec.textContains}»"
                    else -> "SMS (любое)"
                }
            }
            TRIGGER_CALENDAR_EVENT -> TriggerConfigs.parseCalendar(cfg)?.let { spec ->
                "событие календаря" +
                    (spec.titleContains?.let { " «$it»" } ?: "") +
                    " за ${spec.minutesBefore} мин"
            } ?: "событие календаря: $cfg"
            TRIGGER_BLE_PRESENCE -> TriggerConfigs.parseBlePresence(cfg)?.let { spec ->
                "появление BLE-устройства" +
                    (spec.address?.let { " $it" } ?: spec.nameContains?.let { " «$it»" } ?: "") +
                    " (RSSI ≥ ${spec.rssiThreshold} dBm)"
            } ?: "появление BLE-устройства: $cfg"
            TRIGGER_SOUND_EVENT -> TriggerConfigs.parseSoundEvent(cfg)?.let { spec ->
                "громкий звук (≥ ${spec.levelThresholdDbfs} dBFS не короче ${spec.sustainMs} мс)"
            } ?: "громкий звук: $cfg"
            TRIGGER_GEO_DISTANCE -> TriggerConfigs.parseGeoDistance(cfg)?.let { spec ->
                "смещение от последней точки ≥ ${TriggerConfigs.formatDistance(spec.distanceM)} м " +
                    "(не чаще ${spec.minIntervalSec / 60} мин)"
            } ?: "смещение локации: $cfg"
            TRIGGER_WIFI_OPEN -> TriggerConfigs.parseOpenWifi(cfg)?.let { spec ->
                "появление открытой Wi-Fi сети" +
                    (spec.ssidContains?.let { " «$it»" } ?: "") +
                    " (не чаще ${spec.minIntervalSec / 60} мин)"
            } ?: "появление открытой Wi-Fi сети: $cfg"
            TRIGGER_HEART_RATE_THRESHOLD -> TriggerConfigs.parseHeartRate(cfg)?.let { spec ->
                "пульс " + (spec.above?.let { "выше $it" } ?: "ниже ${spec.below}") + " уд/мин" +
                    if (spec.dwellSec > 0) " не менее ${spec.dwellSec} с" else ""
            } ?: "пульс: $cfg"
            TRIGGER_STEPS_REACHED -> TriggerConfigs.parseSteps(cfg)?.let { spec ->
                "шагов с момента подписки: ${spec.target}"
            } ?: "шаги: $cfg"
            TRIGGER_NO_MOVEMENT -> TriggerConfigs.parseNoMovement(cfg)?.let { spec ->
                "нет значимого движения ${spec.idleMinutes} мин"
            } ?: "нет движения: $cfg"
            TRIGGER_SLEEP_WINDOW -> TriggerConfigs.parseSleepWindow(cfg)?.let { spec ->
                "сон-эвристика ${TriggerConfigs.formatMinuteOfDay(spec.startMinute)}–" +
                    "${TriggerConfigs.formatMinuteOfDay(spec.endMinute)} " +
                    "(экран выключен и телефон неподвижен — не измерение сна)"
            } ?: "сон-эвристика: $cfg"
            else -> triggerType
        }
    }

    /** Short human-readable Russian description of an action, used by `automation_rule_list`. */
    fun describeAction(actionType: String, config: String): String = when (actionType) {
        ACTION_START_CHAT -> "запуск агента: " + config.replace('\n', ' ').take(60)
        ACTION_AGENT_TASK -> "задача агенту по событию: " + config.replace('\n', ' ').take(60)
        ACTION_NOTIFY -> parseNotify(config)?.let { "уведомление «${it.title}»: ${it.text.take(60)}" }
            ?: "уведомление"
        else -> actionType
    }
}
