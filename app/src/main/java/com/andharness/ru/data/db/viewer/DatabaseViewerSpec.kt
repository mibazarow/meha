package com.andharness.ru.data.db.viewer

/**
 * Pure, Android-free description of the read-only database viewer (update.md «Запланировано» →
 * «Просмотр базы данных»). It holds the human-readable sections, the **hard allow-list** of table
 * names the viewer may `SELECT` from, and the cell-formatting helpers. Keeping it free of Android
 * types makes it unit-testable and makes it impossible for a caller to smuggle an arbitrary table
 * name into a query: only names in [allTables] are accepted.
 */

object DatabaseViewerSpec {

    /** Rows shown per page; the same limit is used for every table. */
    const val PAGE_SIZE = 50

    /** Longest value rendered for a single cell before it is elided with an ellipsis. */
    private const val MAX_CELL_LENGTH = 600

    /**
     * Tables that grow without bound and therefore render newest-first: `interactions`,
     * `messages` and `device_fact_events`, the audit/RAG logs, and the per-segment transcript
     * stores (`transcript_segments`, `transcript_vectors`, `speaker_voice_embeddings`).
     */
    val BIG_TABLES: Set<String> =
        setOf(
            "interactions", "messages", "device_fact_events", "tool_audit", "rag_chunks",
            "automation_events", "transcript_segments", "transcript_vectors",
            "speaker_voice_embeddings"
        )

    /** Human-readable labels, keyed by the physical Room table name. */
    private val LABELS: Map<String, String> = mapOf(
        "persons" to "Люди",
        "person_identities" to "Идентификаторы людей",
        "interactions" to "Взаимодействия",
        "person_aliases" to "Псевдонимы людей",
        "notes" to "Заметки (память)",
        "device_facts" to "Факты об устройстве",
        "device_fact_events" to "События фактов",
        "scheduled_tasks" to "Задачи по расписанию",
        "automation_rules" to "Правила автоматизации",
        "automation_events" to "События автоматизации",
        "chats" to "Чаты",
        "messages" to "Сообщения",
        "todos" to "Дела",
        "saf_grants" to "Доступы к папкам",
        "ssh_servers" to "SSH-серверы",
        "crash_logs" to "Крэш-логи",
        "exact_alarms" to "Точные будильники",
        "trash_entries" to "Корзина",
        "folder_watches" to "Наблюдение за папками",
        "folder_watch_events" to "События наблюдения",
        "settings" to "Настройки",
        "restart_state" to "Состояние перезапуска",
        "tool_audit" to "Аудит вызовов инструментов",
        "llm_usage" to "Использование моделей",
        "rag_chunks" to "RAG (индекс)",
        "recordings" to "Записи",
        "transcript_segments" to "Сегменты транскрипта",
        "transcript_vectors" to "Векторы транскрипта",
        "speakers" to "Голоса (спикеры)",
        "speaker_voice_embeddings" to "Голосовые эмбеддинги"
    )

    /**
     * The curated sections, in display order. Every name here must also be present in [LABELS];
     * the invariant is asserted by the unit test.
     */
    private val NAMED_SECTIONS: List<Pair<String, List<String>>> = listOf(
        "Люди и общение" to listOf("persons", "person_identities", "interactions"),
        "Память" to listOf("notes", "device_facts"),
        "Расписания" to listOf("scheduled_tasks"),
        "Автоматизации" to listOf("automation_rules", "automation_events"),
        "Чаты" to listOf("chats", "messages"),
        "Коммуникации" to listOf(
            "recordings",
            "transcript_segments",
            "transcript_vectors",
            "speakers",
            "speaker_voice_embeddings"
        ),
        "Заметки и дела" to listOf("todos"),
        "Доступ к папкам" to listOf("saf_grants"),
        "SSH-серверы" to listOf("ssh_servers"),
        "Аудит" to listOf("tool_audit"),
        "RAG (индекс)" to listOf("rag_chunks"),
        "Диагностика" to listOf(
            "crash_logs",
            "device_fact_events",
            "exact_alarms",
            "trash_entries",
            "folder_watches"
        )
    )

    /** Every table the viewer is allowed to read. Any other name is rejected. */
    val allTables: Set<String> = LABELS.keys

    private val namedTables: Set<String> = NAMED_SECTIONS.flatMap { it.second }.toSet()

    /** Section list shown on the viewer's first screen, including «Остальные таблицы». */
    val sections: List<DbSection> = buildList {
        NAMED_SECTIONS.forEach { (title, tables) ->
            add(DbSection(title, tables.map(::ref)))
        }
        val remaining = LABELS.keys
            .filterNot { it in namedTables }
            .sorted()
            .map(::ref)
        if (remaining.isNotEmpty()) {
            add(DbSection("Остальные таблицы", remaining))
        }
    }

    private fun ref(table: String): DbTableRef =
        DbTableRef(table = table, label = LABELS.getValue(table), isBig = table in BIG_TABLES)

    fun label(table: String): String = LABELS[table] ?: table

    fun isBig(table: String): Boolean = table in BIG_TABLES

    /** True only for a table in the hard allow-list. */
    fun isAllowed(table: String): Boolean = table in allTables

    fun totalCountText(count: Int): String = when (count) {
        0 -> "пусто"
        1 -> "1 запись"
        in 2..4 -> "$count записи"
        else -> "$count записей"
    }

    /**
     * Heuristic: does a column carry an epoch-millisecond timestamp? Uses the physical column name
     * (camelCase or snake_case). Deliberately case-sensitive on `At` so a column such as `format`
     * is not mistaken for a timestamp.
     */
    fun isTimestampColumn(column: String): Boolean {
        val lower = column.lowercase()
        if (lower == "at" || lower == "time" || lower == "date" || lower == "timestamp") return true
        return column.endsWith("At") ||
            column.endsWith("_at") ||
            column.endsWith("Ms") ||
            column.endsWith("_ms") ||
            lower.endsWith("timestamp") ||
            lower.endsWith("_date") ||
            lower.endsWith("date") ||
            lower.contains("time")
    }

    /** Epoch millis sanity window: 1973-03-03 … year ~2286; filters out small counters. */
    fun looksLikeEpochMillis(value: Long): Boolean =
        value in 100_000_000_000L..10_000_000_000_000L

    /** Renders an integer cell: timestamps become local date/time, everything else stays numeric. */
    fun formatInteger(column: String, value: Long): String =
        if (isTimestampColumn(column) && looksLikeEpochMillis(value)) {
            com.andharness.ru.data.tools.formatTimestamp(value)
        } else {
            value.toString()
        }

    /** Elides a very long text cell so one blob cannot blow up the list. */
    fun truncate(text: String): String =
        if (text.length > MAX_CELL_LENGTH) text.take(MAX_CELL_LENGTH) + "…" else text
}
