package com.andharness.ru.data.tools.intercept

import android.content.Context
import android.provider.CallLog
import com.andharness.ru.data.intercept.ContactLookup
import com.andharness.ru.data.intercept.NumberChatManager
import com.andharness.ru.data.intercept.NumberChatStore
import com.andharness.ru.data.osint.NumberNormalizer
import com.andharness.ru.data.osint.NumberProfile
import com.andharness.ru.data.osint.OsintCacheStore
import com.andharness.ru.data.tools.parseLimit
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.andharness.ru.data.tools.str

/**
 * Tools for call/SMS interception + number OSINT + the per-number auto-chat (update.md §2.7/§8):
 * [number_osint], [number_profile], [auto_number_chat_status], [auto_number_chat_toggle],
 * [unknown_numbers_list].
 *
 * All of them are non-dangerous and honest:
 * - [number_osint] never fabricates — a missing result is reported as "not found";
 * - [number_profile] only derives the country/region prefix heuristic and says the line type is not
 *   derivable;
 * - [auto_number_chat_status] reports the real `ROLE_CALL_SCREENING` state and the delay of the
 *   call-log fallback instead of pretending interception is real-time.
 *
 * The toggle drives the persisted interception flag in `WatchdogConfigStore`, the same flag the
 * background core checks, so enabling it also starts the core (update.md §1.3).
 */
class InterceptToolsHandler(
    private val context: Context,
    private val manager: NumberChatManager,
    private val osintCache: OsintCacheStore,
    private val store: NumberChatStore,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "number_osint"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val number = arguments.str("number")
                        ?: return@withContext ToolResult(false, "Укажите number (номер телефона).")
                    val force = (arguments["force"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
                    val outcome = manager.process(
                        rawNumber = number,
                        eventKind = NumberChatManager.EVENT_MANUAL,
                        force = force,
                    )
                    val osint = outcome.osint
                    if (osint == null) {
                        return@withContext ToolResult(true, outcome.note)
                    }
                    val body = buildString {
                        appendLine("OSINT по номеру ${osint.number}:")
                        appendLine(
                            "источник: ${if (osint.fromCache) "кэш (свежий, повторный веб-поиск не выполнялся)" else "свободный веб-поиск, бюджет 15 с, не более 5 источников"}"
                        )
                        appendLine(osint.summary)
                        if (osint.sources.isNotEmpty()) {
                            appendLine()
                            appendLine("Ссылки (${osint.sources.size} из максимум 5):")
                            osint.sources.forEach { appendLine("- ${it.title}: ${it.url}") }
                        }
                    }.trim()
                    ToolResult(true, "$body\n\n${outcome.note}")
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка OSINT: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "number_profile"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val number = arguments.str("number")
                        ?: return@withContext ToolResult(false, "Укажите number (номер телефона).")
                    val normalized = NumberNormalizer.normalize(number)
                        ?: return@withContext ToolResult(
                            true,
                            "Номер «$number» не распознан (не телефонный номер) — профиль не выводим, " +
                                "данные не выдуманы.",
                        )
                    val known = ContactLookup.displayName(context, normalized)
                    val lines = buildString {
                        appendLine("Номер: $number")
                        appendLine("Нормализовано: $normalized")
                        appendLine("Профиль: ${NumberProfile.describe(normalized)}")
                        appendLine("Оператор: не выводимо локально (нет доступа к данным оператора по чужому номеру).")
                        appendLine("Оценка спам/риск: не выводима — не выдумывается.")
                        if (known != null) {
                            append("Контакт: «$known» (номер известен).")
                        } else {
                            append("Контакт: не найден (номер неизвестный).")
                        }
                    }
                    ToolResult(true, lines)
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка профиля номера: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "auto_number_chat_status"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    ToolResult(true, manager.status())
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка статуса авто-чата: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "auto_number_chat_toggle"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val raw = (arguments["enabled"] as? JsonPrimitive)?.content?.trim().orEmpty()
                    val enabled = raw.toBooleanStrictOrNull()
                        ?: return@withContext ToolResult(
                            false,
                            "Укажите enabled = true или false (передан «$raw»).",
                        )
                    ToolResult(true, manager.setEnabled(enabled))
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка переключения авто-чата: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "unknown_numbers_list"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val limit = parseLimit(arguments["limit"] as? JsonPrimitive).coerceIn(1, 100)
                    val tracked = store.all().take(limit)
                    val lines = ArrayList<String>()
                    if (tracked.isEmpty()) {
                        lines.add("Незнакомых номеров пока нет: авто-чат ещё не создавался.")
                    } else {
                        tracked.forEach {
                            lines.add(
                                "${it.number} → чат ${it.chatId} | событие: ${it.lastEventKind} | " +
                                    "последнее: ${it.lastEventAt}"
                            )
                        }
                    }
                    val recent = recentUnknownNumbers(limit)
                    if (recent.isNotEmpty()) {
                        lines.add("")
                        lines.add("Незнакомые номера из журнала вызовов (не обработанные):")
                        recent.forEach { lines.add("  $it") }
                    }
                    lines.add("")
                    lines.add("Перехват: ${if (manager.isEnabled()) "включён" else "выключен"}; " +
                        "роль CALL_SCREENING: ${if (manager.isCallScreeningRoleHeld()) "выдана" else "не выдана"}.")
                    if (!manager.isCallScreeningRoleHeld()) {
                        lines.add(
                            "Честно: без роли CALL_SCREENING реальный перехват невозможен — только " +
                                "фолбэк по журналу вызовов с задержкой до 15 минут."
                        )
                    }
                    lines.add("Кэш OSINT: ${osintCache.all().size} записей.")
                    ToolResult(true, lines.joinToString("\n"))
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка списка номеров: ${e.message}")
                }
            }
        },
    )

    /** Recent call-log numbers that are neither contacts nor already tracked (best-effort). */
    private fun recentUnknownNumbers(limit: Int): List<String> {
        val out = LinkedHashSet<String>()
        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER),
                null,
                null,
                "${CallLog.Calls.DATE} DESC",
            )?.use { cursor ->
                val index = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                while (cursor.moveToNext() && out.size < limit) {
                    val raw = if (index >= 0) cursor.getString(index) else null
                    if (raw.isNullOrBlank()) continue
                    val normalized = NumberNormalizer.normalize(raw) ?: continue
                    if (store.get(normalized) != null) continue
                    if (ContactLookup.isKnown(context, normalized)) continue
                    out.add(normalized)
                }
            }
            out.toList()
        } catch (e: SecurityException) {
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

}
