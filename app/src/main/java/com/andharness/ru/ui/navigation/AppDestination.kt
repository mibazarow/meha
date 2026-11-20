package com.andharness.ru.ui.navigation

object AppDestination {
    /**
     * Самый первый экран приложения на первом запуске: выбор языка интерфейса ПЕРЕД лицензией,
     * чтобы соглашение и весь дальнейший поток отрисовались на выбранном языке. Показывается,
     * пока язык не выбран явно; затем — [LICENSE].
     */
    const val LANGUAGE = "language"

    /** Второй экран при неподтверждённом лицензионном соглашении (EULA). */
    const val LICENSE = "license"
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val CHATS = "chats"
    const val CHAT = "chat/{chatId}"
    const val SETTINGS = "settings"

    /** «Подключение к провайдеру»: OpenAI-совместимый провайдер или локальная нейросеть. */
    const val PROVIDER_CONNECTION = "provider_connection"

    const val PERMISSIONS = "permissions"
    const val SCHEDULED_TASKS = "scheduled_tasks"

    /** View/manage screen for automation rules created by the agent (update.md §11.6). */
    const val AUTOMATION_RULES = "automation_rules"

    /** Library of saved app skills (M2, SPEC §24.2): search, run, delete, rename. */
    const val SKILLS = "skills"

    /** Read-only in-app database viewer (update.md «Запланировано»). */
    const val DB_VIEWER = "db_viewer"

    /** View/search/delete screen for the agent's long-term memory. */
    const val MEMORY = "memory"

    /** Human-readable «Что агент сделал» report with undo for reversible actions. */
    const val AGENT_ACTIVITY = "agent_activity"

    /** Predictive automation suggestions mined from usage stats (update.md idea #16). */
    const val SUGGESTIONS = "suggestions"

    /** «Связи»: local recordings list, import and transcript search. */
    const val COMMUNICATIONS = "communications"

    /** Transcript viewer for one recording; [transcript] builds a concrete route. */
    const val TRANSCRIPT = "transcript/{recordingId}"

    /** «Люди»: list of the unified personal base, search by name. */
    const val PEOPLE = "people"

    /** One person's merged timeline; [personTimeline] builds a concrete route. */
    const val PERSON_TIMELINE = "person/{personId}"

    fun chat(chatId: Long): String = "chat/$chatId"

    fun transcript(recordingId: Long): String = "transcript/$recordingId"

    fun personTimeline(personId: Long): String = "person/$personId"
}
