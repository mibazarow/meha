package com.andharness.ru.domain.screenmap

/**
 * Pure Russian rendering of the screen map for the `screen_map_*` tools. Android-free and
 * side-effect free, so the exact wording (including the honest "this is a stale cache" warning) is
 * unit-tested on the JVM.
 */
object ScreenMapRender {

    /** Honest reminder appended to every non-empty rendering. */
    const val STALE_WARNING =
        "Это КЭШ того, что агент уже видел, и он может устареть: перед действием проверь живой экран " +
            "через ui_tree."

    /** Summary of all remembered apps (used by `screen_map_list` and an empty `screen_map_get`). */
    fun summary(maps: List<AppScreenMap>, now: Long): String {
        if (maps.isEmpty()) {
            return "Карта экранов пуста — агент ещё ничего не запоминал. Это кэш: он наполняется сам " +
                "после успешных ui_tree/ui_find/ui_tap/ui_type/ui_scroll."
        }
        val sorted = maps.sortedByDescending { map -> map.screens.maxOfOrNull { it.lastSeen } ?: 0L }
        return buildString {
            append("Карта экранов: приложений ").append(maps.size).append('.')
            sorted.forEach { map ->
                val last = map.screens.maxOfOrNull { it.lastSeen } ?: 0L
                append("\n- ").append(map.appPackage)
                    .append(": экранов ").append(map.screens.size)
                    .append(", последний раз ").append(ago(now, last))
            }
            append("\n").append(STALE_WARNING)
        }
    }

    /** Full rendering of one app's screens. [appPackage] is echoed for an honest "not found". */
    fun app(map: AppScreenMap?, appPackage: String, now: Long): String {
        if (map == null || map.screens.isEmpty()) {
            return "В карте экранов нет приложения «$appPackage». Это кэш: он наполняется сам, когда " +
                "агент работает в приложении. Сними живой экран через ui_tree."
        }
        val screens = map.screens.sortedByDescending { it.lastSeen }
        return buildString {
            append("Карта экранов ").append(map.appPackage).append(": экранов ").append(screens.size).append('.')
            screens.forEachIndexed { index, screen ->
                append("\n\n").append(index + 1).append(") ")
                    .append(screen.title ?: "(без названия)")
                    .append(" — ").append(ago(now, screen.lastSeen))
                    .append("\n   подпись: ").append(screen.signature)
                if (screen.elements.isEmpty()) {
                    append("\n   элементов не записано")
                } else {
                    screen.elements.forEach { element -> append("\n   ").append(elementLine(element)) }
                }
            }
            append("\n\n").append(STALE_WARNING)
        }
    }

    /** Honest result of `screen_map_forget`: [removed] apps removed, `null` package = clear all. */
    fun forget(removed: Int, appPackage: String?): String {
        val target = appPackage?.trim().orEmpty()
        return when {
            target.isEmpty() && removed == 0 -> "Карта экранов и так пуста — забывать нечего."
            target.isEmpty() -> "Карта экранов очищена: удалено приложений $removed."
            removed == 0 -> "В карте экранов нет приложения «$target» — забывать нечего."
            else -> "Карта экранов приложения «$target» очищена."
        }
    }

    /** One element line: role, label, view id and class (only the present parts). */
    private fun elementLine(element: ScreenElementInfo): String {
        val label = element.text?.takeIf { it.isNotBlank() }
            ?: element.contentDescription?.takeIf { it.isNotBlank() }
            ?: element.resourceId
            ?: "(без подписи)"
        return buildString {
            append('[').append(element.role).append("] «").append(label).append('»')
            element.resourceId?.takeIf { it.isNotBlank() }?.let { append(" id=").append(it) }
            element.className?.takeIf { it.isNotBlank() }?.let { append(" class=").append(it) }
        }
    }

    /** Human relative time for a stored timestamp ("только что", "5 мин назад", ...). */
    fun ago(now: Long, timestamp: Long): String {
        if (timestamp <= 0L) return "неизвестно"
        val minutes = (now - timestamp) / 60_000L
        return when {
            minutes < 1L -> "только что"
            minutes < 60L -> "$minutes мин назад"
            minutes < 60L * 24L -> "${minutes / 60L} ч назад"
            else -> "${minutes / (60L * 24L)} дн назад"
        }
    }
}
