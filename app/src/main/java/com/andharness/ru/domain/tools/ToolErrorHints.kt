package com.andharness.ru.domain.tools

/**
 * Turns a raw tool failure into short, actionable Russian hints.
 *
 * This is a deliberately simple heuristic layer (not a parser): it inspects the tool name and the
 * failed output for well-known failure signatures and points at the fix (grant a permission, install
 * an app, enable a special-access service, check the network). When a specific signature matches, its
 * `Подсказка:` line is returned first; in every case (signature or not) the alternative-tool line
 * from [ToolAlternatives] is appended, so the agent tries another tool instead of giving up. It is
 * pure JVM code with no Android dependencies so it can be unit-tested off-device.
 *
 * Referenced Android states:
 * - All-files access (MANAGE_EXTERNAL_STORAGE):
 *   https://developer.android.com/training/data-storage/manage-all-files
 * - Accessibility service:
 *   https://developer.android.com/guide/topics/ui/accessibility/service
 * - Notification listener access:
 *   https://developer.android.com/reference/android/service/notification/NotificationListenerService
 * - Exact alarms (SCHEDULE_EXACT_ALARM):
 *   https://developer.android.com/develop/background-work/services/alarms/schedule
 */
object ToolErrorHints {

    /**
     * Returns an actionable hint for a failed tool call, or `""` when nothing useful applies.
     * Never returns a generic filler — an empty string means "leave the message untouched".
     */
    fun hint(toolName: String, error: String): String {
        if (error.isBlank()) return ""
        // `sim_set_default_sms` refuses on purpose: it names MODIFY_PHONE_STATE, a
        // signature|privileged permission the user cannot grant. A "grant the permission" hint
        // would be misleading, so this tool never gets one.
        if (toolName == "sim_set_default_sms") return ""
        val e = error.lowercase()
        val action = when {
            isExactAlarmIssue(e) -> EXACT_ALARM
            isAllFilesIssue(e) -> ALL_FILES
            isAccessibilityIssue(e) -> ACCESSIBILITY
            isNotificationAccessIssue(e) -> NOTIFICATION_ACCESS
            isAppMissing(e) -> APP_MISSING
            isInstallUnknownAppsIssue(e) -> INSTALL_UNKNOWN_APPS
            isBatteryDeferral(e) -> BATTERY
            isNetworkIssue(e) -> NETWORK
            isPermissionIssue(e) -> permission(toolName)
            else -> ""
        }
        // Always append the "try another tool" line when the registry has one, so the agent changes
        // approach instead of repeating the same failing call. `ToolAlternatives` filters out every
        // destructive/irreversible suggestion, and returns `""` for unknown/blank tools.
        val alternatives = ToolAlternatives.format(toolName)
        return listOf(action, alternatives).filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun isExactAlarmIssue(e: String): Boolean =
        e.contains("schedule_exact_alarm") ||
            e.contains("exact alarm") ||
            e.contains("exact_alarm") ||
            (e.contains("будильник") && (e.contains("неточн") || e.contains("не выдано") || e.contains("запрещ")))

    private fun isAllFilesIssue(e: String): Boolean =
        e.contains("manage_external_storage") ||
            e.contains("всех файлов") ||
            e.contains("all-files") ||
            e.contains("all files") ||
            (e.contains("storage") &&
                (e.contains("permission") || e.contains("access") || e.contains("denied") ||
                    e.contains("разрешени") || e.contains("доступ")))

    private fun isAccessibilityIssue(e: String): Boolean =
        e.contains("accessibility") ||
            e.contains("notenabled") ||
            e.contains("спецвозможност") ||
            e.contains("спец. возможност") ||
            e.contains("служба спец")

    private fun isNotificationAccessIssue(e: String): Boolean =
        e.contains("notification listener") ||
            e.contains("notificationlistener") ||
            e.contains("notification_access") ||
            e.contains("доступ к уведомлени")

    private fun isAppMissing(e: String): Boolean =
        e.contains("activitynotfound") ||
            e.contains("not installed") ||
            e.contains("app not found") ||
            e.contains("нет приложения") ||
            e.contains("приложение не установлено") ||
            e.contains("приложение не найдено")

    private fun isInstallUnknownAppsIssue(e: String): Boolean =
        e.contains("install_unknown_apps") ||
            e.contains("unknown app") ||
            (e.contains("неизвестн") && e.contains("установ")) ||
            (e.contains("установк") && e.contains("запрещ"))

    private fun isBatteryDeferral(e: String): Boolean =        e.contains("powerguard") ||
            e.contains("battery") ||
            e.contains("энергосбереж") ||
            e.contains("оптимизац") && e.contains("батар")

    private fun isNetworkIssue(e: String): Boolean =
        e.contains("unknownhost") ||
            e.contains("unable to resolve host") ||
            e.contains("timeout") ||
            e.contains("timed out") ||
            e.contains("connection") ||
            e.contains("network") ||
            e.contains("socket") ||
            e.contains("интернет") ||
            e.contains("сети") ||
            e.contains("подключиться")

    private fun isPermissionIssue(e: String): Boolean =
        e.contains("permission") ||
            e.contains("securityexception") ||
            e.contains("not granted") ||
            e.contains("denied") ||
            e.contains("разрешени") ||
            e.contains("не выдано")

    private fun permission(toolName: String): String =
        "Подсказка: не выдано разрешение на «${capability(toolName)}». " +
            "Откройте «Настройки → Приложения → Меха → Разрешения», выдайте доступ и повторите."

    /** Plain-words label of what the tool needs, so the user knows which permission to grant. */
    private fun capability(toolName: String): String = when {
        toolName.startsWith("files_") || toolName.startsWith("documents_") || toolName.startsWith("archive_") ->
            "доступ к файлам"
        toolName.startsWith("contacts_") -> "доступ к контактам"
        toolName.startsWith("sms_") || toolName.startsWith("calls_") -> "доступ к СМС и звонкам"
        toolName.startsWith("camera_") -> "доступ к камере"
        toolName.startsWith("microphone_") || toolName.startsWith("audio_") || toolName.startsWith("speech_") ->
            "доступ к микрофону"
        toolName.startsWith("location_") -> "доступ к геолокации"
        toolName.startsWith("calendar_") -> "доступ к календарю"
        toolName.startsWith("screen_") || toolName.startsWith("ui_") -> "управление экраном"
        toolName.startsWith("notification") -> "доступ к уведомлениям"
        toolName.startsWith("bluetooth_") || toolName.startsWith("connectivity_") -> "доступ к сети и устройствам"
        else -> "необходимое разрешение"
    }

    private const val EXACT_ALARM =
        "Подсказка: точные будильники запрещены системой. Откройте " +
            "«Настройки → Приложения → Специальный доступ → Будильники и напоминания» и разрешите их Мехе."

    private const val ALL_FILES =
        "Подсказка: нужно разрешение «Доступ ко всем файлам». Откройте в приложении " +
            "«Все разрешения → Особые доступы → Доступ ко всем файлам» и включите его " +
            "(либо системно: «Настройки → Приложения → Меха → Доступ ко всем файлам»)."

    private const val ACCESSIBILITY =
        "Подсказка: включите службу спецвозможностей Меха. Откройте " +
            "«Настройки → Доступность → Меха» и разрешите управление интерфейсом."

    private const val NOTIFICATION_ACCESS =
        "Подсказка: включите доступ к уведомлениям. Откройте " +
            "«Настройки → Уведомления → Доступ к уведомлениям → Меха» и разрешите его."

    private const val APP_MISSING =
        "Подсказка: приложение не установлено. Установите его по имени пакета инструментом " +
            "«app_store_open» — только после подтверждения пользователя."

    private const val INSTALL_UNKNOWN_APPS =
        "Подсказка: Мехе не разрешена установка неизвестных приложений. Откройте «Все разрешения → " +
            "Особые доступы → Установка неизвестных приложений» и включите её (либо системно: " +
            "«Настройки → Приложения → Специальный доступ → Установка неизвестных приложений → Меха»)."

    private const val BATTERY =
        "Подсказка: задача отложена системой для экономии заряда (PowerGuard). " +
            "Подключите зарядку или исключите Меху из оптимизации батареи."

    private const val NETWORK =
        "Подсказка: проверьте подключение к интернету (Wi-Fi или мобильная сеть) и повторите попытку."
}
