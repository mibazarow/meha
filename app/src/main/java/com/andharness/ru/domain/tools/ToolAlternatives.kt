package com.andharness.ru.domain.tools

/**
 * Curated "what else to try" registry for a failed tool call.
 *
 * The agent loop must not give up after one failed tool: this object maps a failed tool (by exact
 * name, otherwise by capability family prefix) to a short, ordered list of alternative tools with a
 * one-line honest reason. [ToolErrorHints] renders the list as an appended `Попробуй также: …` line.
 *
 * This is deliberately pure JVM — no Android dependencies — so the whole registry is unit-testable
 * off-device. It is only advisory text: it never executes anything and never changes a tool's
 * success/failure result.
 *
 * SAFETY (enforced in code, see [IRREVERSIBLE]): destructive/irreversible tools are NEVER offered as
 * a fallback. A failed read or benign action must never be "recovered" by deleting, trashing,
 * uninstalling, sending, calling or formatting anything. The filter is applied to every suggestion,
 * so even a curated pair such as `compose_message → sms_send` is dropped (sending an SMS is
 * irreversible) — the safe direction `sms_send → compose_message` still works.
 */
object ToolAlternatives {

    /** One alternative tool plus the honest one-line reason to prefer it. */
    data class Suggestion(val tool: String, val reason: String)

    /** How many alternatives a single hint may name. Keeps the appended line short and bounded. */
    const val MAX_SUGGESTIONS = 3

    /** Max characters of a single reason, so one entry can never dominate the line. */
    const val MAX_REASON_CHARS = 80

    /** Hard cap on the rendered `Попробуй также:` line. */
    const val MAX_HINT_CHARS = 400

    /**
     * Destructive or irreversible tools that must never be suggested as a recovery path. Recovery
     * means "try to accomplish the same goal another way", not "risk destroying data by mistake".
     */
    val IRREVERSIBLE: Set<String> = setOf(
        // Deletes / wipes.
        "files_delete",
        "files_trash",
        "files_trash_empty",
        "media_delete",
        "media_trash",
        "photos_delete",
        "contact_delete",
        "contact_photo_clear",
        "call_log_delete",
        "calendar_delete",
        "note_delete",
        "memory_delete",
        "todo_delete",
        "schedule_delete",
        "saf_delete",
        "automation_rule_delete",
        "skill_delete",
        "ui_macro_delete",
        "clipboard_history_clear",
        "crash_log_clear",
        "folder_watch_clear",
        // Removals of configured resources.
        "mcp_server_remove",
        "ssh_server_remove",
        "trusted_source_remove",
        "geofence_remove",
        "saf_grant_remove",
        // Uninstall and outbound communication / calls.
        "app_uninstall",
        "sms_send",
        "sms_send_on_sim",
        "mms_send",
        "call_start",
    )

    /**
     * Exact tool-name overrides. Higher priority than the capability-family rules in [category].
     */
    private val exact: Map<String, List<Suggestion>> = mapOf(
        "wallpaper_set" to listOf(
            Suggestion(
                "download_file",
                "скачать картинку по URL локально, затем повторить wallpaper_set с локальным path"
            ),
            Suggestion("image_search", "получить прямой URL картинки, если ссылка ведёт на страницу"),
        ),
        "web_search" to listOf(
            Suggestion("image_search", "если нужна картинка — вернуть прямые URL изображений"),
            Suggestion("web_open", "открыть страницу-результат и прочитать её через web_readable"),
            Suggestion("http_request", "запросить страницу или API напрямую, без разбора выдачи"),
        ),
        "web_fetch" to listOf(
            Suggestion("http_request", "обычный HTTP GET без JavaScript, тот же контент"),
            Suggestion("web_open", "открыть страницу в браузере с сессией и прочитать через web_readable"),
        ),
        "web_readable" to listOf(
            Suggestion("web_open", "сначала открыть страницу (web_readable требует открытой сессии)"),
            Suggestion("http_request", "взять HTML напрямую, если браузер недоступен"),
        ),
        "sms_send" to listOf(
            Suggestion("compose_message", "написать сообщение в мессенджере без разрешения SEND_SMS"),
        ),
        // Honest pair in the spec is sms_send <-> compose_message, but the sms_send direction is
        // irreversible and is therefore filtered out by [IRREVERSIBLE] — see the safety note above.
        "compose_message" to listOf(
            Suggestion("sms_send", "отправить СМС напрямую, если мессенджер недоступен"),
        ),
        "files_read" to listOf(
            Suggestion("download_file", "если файла нет локально — скачать его по URL"),
            Suggestion("files_open", "открыть файл подходящим приложением"),
            Suggestion("files_list", "проверить, что файл лежит в указанной папке"),
        ),
        "wifi_set" to listOf(
            Suggestion("open_settings", "открыть панель Wi-Fi: менять состояние напрямую система не разрешает"),
        ),
        "bluetooth_set" to listOf(
            Suggestion("open_settings", "открыть панель Bluetooth: включать адаптер напрямую система не разрешает"),
        ),
        "image_search" to listOf(
            Suggestion("web_search", "найти страницы с картинками, если поиск изображений недоступен"),
            Suggestion("http_request", "запросить провайдера изображений напрямую"),
        ),
    )

    /** Archive tools (`files_zip*`, `files_7z*`, `files_rar*`, `files_tar*`, `files_*zip*`). */
    private fun archiveSuggestions(toolName: String): List<Suggestion> {
        val extract = when {
            toolName.startsWith("files_7z") -> Suggestion("files_7z_extract", "распаковать 7z-архив")
            toolName.startsWith("files_rar") -> Suggestion("files_rar_extract", "распаковать RAR (только чтение)")
            toolName.startsWith("files_tar") -> Suggestion("files_tar_extract", "распаковать TAR")
            toolName.startsWith("files_zip") || toolName == "files_unzip" ->
                Suggestion("files_unzip", "распаковать ZIP-архив")
            else -> Suggestion("files_unzip", "распаковать архив подходящим инструментом")
        }
        return listOf(
            extract,
            Suggestion("files_stat", "проверить, существует ли архив по этому пути"),
            Suggestion("files_open", "открыть архив подходящим приложением"),
        )
    }

    /** Capability-family fallback: prefix → curated alternatives. */
    private fun category(toolName: String): List<Suggestion> = when {
        isArchive(toolName) -> archiveSuggestions(toolName)
        isDocument(toolName) -> listOf(
            Suggestion("doc_read", "извлечь текст из PDF/DOCX/DOC/XLSX одним универсальным инструментом"),
            Suggestion("files_open", "открыть документ подходящим приложением"),
            Suggestion("pdf_ocr", "если PDF отсканирован — распознать текст через OCR"),
        )
        isMedia(toolName) -> listOf(
            Suggestion("media_metadata", "посмотреть метаданные медиафайла (длительность, разрешение)"),
            Suggestion("photos_list", "найти нужное фото в галерее"),
            Suggestion("files_open", "открыть медиафайл подходящим приложением"),
        )
        isImage(toolName) -> listOf(
            Suggestion("image_convert", "пересохранить изображение в поддерживаемый формат"),
            Suggestion("image_resize", "изменить размер изображения"),
            Suggestion("image_thumbnail", "сделать миниатюру изображения"),
        )
        else -> emptyList()
    }

    private fun isArchive(n: String): Boolean =
        n.startsWith("files_zip") ||
            n.startsWith("files_7z") ||
            n.startsWith("files_rar") ||
            n.startsWith("files_tar") ||
            n.startsWith("files_gzip") ||
            n.startsWith("files_gunzip") ||
            n == "files_unzip"

    private fun isDocument(n: String): Boolean =
        n.startsWith("doc_") || n.startsWith("docx_") || n.startsWith("pdf_")

    private fun isMedia(n: String): Boolean =
        n.startsWith("media_") || n.startsWith("photos_")

    private fun isImage(n: String): Boolean = n.startsWith("image_")

    /**
     * Ordered alternatives for a failed [toolName], already safety-filtered and bounded.
     * Returns an empty list (never a filler) for an unknown/blank tool, when no candidate exists,
     * or when every candidate was filtered out by [IRREVERSIBLE].
     *
     * Note the filter is one-directional on purpose: a *failed* irreversible tool may still be
     * pointed at a safe alternative (`sms_send → compose_message`), but an irreversible tool is
     * never itself offered as the alternative (so `compose_message` never suggests `sms_send`).
     */
    fun suggestions(toolName: String): List<Suggestion> {
        if (toolName.isBlank()) return emptyList()
        val raw = exact[toolName] ?: category(toolName)
        return raw
            .filter { it.tool != toolName && it.tool !in IRREVERSIBLE }
            .distinctBy { it.tool }
            .take(MAX_SUGGESTIONS)
    }

    /**
     * Renders [suggestions] as one bounded, actionable line, or `""` when there is nothing honest to
     * suggest. Expected shape: `Попробуй также: <tool1> — <reason1>; <tool2> — <reason2>.`
     */
    fun format(toolName: String): String {
        val list = suggestions(toolName)
        if (list.isEmpty()) return ""
        val body = list.joinToString("; ") { "${it.tool} — ${it.reason.take(MAX_REASON_CHARS)}" }
        val line = "Попробуй также: $body."
        return if (line.length <= MAX_HINT_CHARS) line else line.take(MAX_HINT_CHARS)
    }
}
