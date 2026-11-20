package com.andharness.ru.data.tools.misc

import com.andharness.ru.data.clipboard.ClipboardHistoryLogic
import com.andharness.ru.data.clipboard.ClipboardHistoryStore
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds the three clipboard-history tools:
 * - [clipboard_history_list] — read-only feed of recent items (newest first: index, time, source,
 *   bounded preview);
 * - [clipboard_history_get] — read-only retrieval of the FULL text by index/id (or the newest when
 *   neither is given) — the "smart paste" building block;
 * - [clipboard_history_clear] — empties the history (not dangerous; the system clipboard is
 *   untouched).
 *
 * Honesty about the platform: Android 10+ forbids background clipboard reads
 * (https://developer.android.com/about/versions/10/privacy/changes#clipboard-data), so the history
 * only contains what this app copied (`clipboard_set`) or read while it had focus (foregrounding).
 * The list result restates that so the model never promises content the app could not have seen.
 */
class ClipboardHistoryToolsHandler(
    private val store: ClipboardHistoryStore,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "clipboard_history_list"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val requested = (arguments["limit"] as? JsonPrimitive)?.content?.toIntOrNull()
                    val limit = (requested ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
                    val items = store.list(limit)
                    if (items.isEmpty()) {
                        return@withContext ToolResult(true, "История буфера обмена пуста.")
                    }
                    val body = items.mapIndexed { position, entry ->
                        "#${position + 1} | ${formatTime(entry.timestamp)} | " +
                            "${entry.sourcePackage ?: "источник неизвестен"} | " +
                            ClipboardHistoryLogic.preview(entry.text)
                    }.joinToString("\n")
                    ToolResult(
                        true,
                        buildString {
                            append("История буфера обмена (всего ${store.count()}, новые сверху):\n")
                            append(body)
                            append("\nПолный текст — clipboard_history_get (index или id).")
                            append(
                                " Учти: Android 10+ не даёт читать буфер в фоне, поэтому здесь " +
                                    "только то, что скопировала сама Меха или что было в буфере " +
                                    "в момент открытия приложения."
                            )
                        },
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка чтения истории буфера обмена: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "clipboard_history_get"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val index = (arguments["index"] as? JsonPrimitive)?.content?.trim()?.toIntOrNull()
                    val id = (arguments["id"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
                    val entry = ClipboardHistoryLogic.select(store.all(), index = index, id = id)
                    if (entry == null) {
                        val what = when {
                            id != null -> "запись с id «$id»"
                            index != null -> "запись №$index"
                            else -> "ни одной записи"
                        }
                        return@withContext ToolResult(
                            false,
                            "В истории буфера обмена нет такой записи: $what не найдена" +
                                " (или история пуста). Посмотри clipboard_history_list.",
                        )
                    }
                    // The full text is returned verbatim so the caller can paste it (clipboard_set /
                    // ui_type) without the preview truncation.
                    ToolResult(true, entry.text)
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка чтения истории буфера обмена: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "clipboard_history_clear"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val removed = store.count()
                    store.clear()
                    ToolResult(
                        true,
                        "История буфера обмена очищена (удалено записей: $removed). " +
                            "Системный буфер обмена не тронут.",
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка очистки истории буфера обмена: ${e.message}")
                }
            }
        },
    )

    private companion object {
        /** Default number of rows [clipboard_history_list] shows. */
        const val DEFAULT_LIMIT = 20

        /** Hard cap on rows [clipboard_history_list] returns (bounded output). */
        const val MAX_LIMIT = 50

        private val TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd.MM HH:mm", Locale.US)

        fun formatTime(epochMillis: Long): String = try {
            Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(TIME_FORMAT)
        } catch (_: Exception) {
            epochMillis.toString()
        }
    }
}
