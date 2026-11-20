package com.andharness.ru.data.tools.watch

import android.content.Context
import android.os.FileObserver
import com.andharness.ru.data.tools.effectiveToolRoot
import com.andharness.ru.data.tools.resolveSafePath
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.watch.FolderWatchRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import com.andharness.ru.data.tools.formatTimestamp

/**
 * Builds one [ToolHandler] per folder_watch_* tool (TZ item B4).
 *
 * DESIGN — persist the definitions, be honest about the observers:
 * watch rows and their events live in Room through [FolderWatchRepository], so
 * `folder_watch_list` still shows every watch after a process restart; the *observer*, however,
 * is a `FileObserver` that cannot survive process death and is therefore kept only in the
 * in-memory [running] map. After a restart a watch is reported as «активен: нет» instead of
 * silently disappearing — the limitation is surfaced, not hidden.
 *
 * `android.os.FileObserver` reference:
 * - https://developer.android.com/reference/android/os/FileObserver
 *   Two documented limitations that this design does NOT hide:
 *   1. FileObserver watches a single directory and is NOT recursive — children are not watched.
 *   2. The watch dies with the process; it is not restored on restart.
 *   The `FileObserver(String, int)` constructor is the API-<29-compatible overload; the
 *   `FileObserver(File, int)` overload only exists from API 29. minSdk is 26, so the String
 *   constructor is used. `startWatching()`/`stopWatching()`:
 * - https://developer.android.com/reference/android/os/FileObserver#startWatching()
 * - https://developer.android.com/reference/android/os/FileObserver#stopWatching()
 *
 * Coroutines on the observer thread — `FileObserver.onEvent` is delivered on a dedicated
 * observer thread, so the suspend repository call is launched on the handler-owned
 * [eventScope] instead of blocking that thread:
 * - https://developer.android.com/kotlin/coroutines
 * - https://kotlinlang.org/docs/coroutine-context-and-dispatchers.html
 */
class FolderWatchToolsHandler(
    private val context: Context,
    private val folderWatchRepository: FolderWatchRepository,
) {

    /** Running observers only: id -> observer. Watches themselves live in the repository. */
    private val running = ConcurrentHashMap<Long, FileObserver>()

    /**
     * Handler-owned scope for persisting events. `SupervisorJob` keeps one failing insert from
     * cancelling the rest; the scope is created once per handler instance (Hilt singleton).
     */
    private val eventScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "folder_watch_start"
            override suspend fun execute(arguments: JsonObject): ToolResult {
                return try {
                    val raw = (arguments["path"] as? JsonPrimitive)?.content.orEmpty()
                    if (raw.isBlank()) return ToolResult(false, "Ошибка: укажите path.")

                    val maskName = (arguments["mask"] as? JsonPrimitive)?.content?.trim()?.lowercase()
                    val mask = maskFromName(maskName)
                        ?: return ToolResult(
                            false,
                            "Ошибка: неизвестная маска «$maskName». Допустимо: create, modify, delete, move, all."
                        )

                    // resolveSafePath accepts an absolute path only when it stays inside the root and
                    // rejects `..` / NUL / escapes (see ToolUtils.resolveSafePath).
                    val dir = try {
                        resolveSafePath(context.effectiveToolRoot(), raw)
                    } catch (e: IllegalArgumentException) {
                        return ToolResult(false, "Ошибка: ${e.message}")
                    }
                    if (!dir.isDirectory) {
                        return ToolResult(
                            false,
                            "Ошибка: «${dir.absolutePath}» не является существующей директорией."
                        )
                    }

                    val id = folderWatchRepository.addWatch(dir.absolutePath, mask)

                    // The id is captured as a `val` so that events arriving after a later stop can
                    // never be attributed to a different (re-created) watch.
                    // FileObserver is a Java abstract class with a single abstract method; Kotlin SAM
                    // conversion is not guaranteed for abstract classes, so an explicit anonymous
                    // subclass is used (guaranteed to compile and behave identically).
                    // FileObserver(String, int) — https://developer.android.com/reference/android/os/FileObserver
                    val observer = try {
                        object : FileObserver(dir.absolutePath, mask) {
                            override fun onEvent(event: Int, path: String?) {
                                val watchId = id
                                val eventName = path ?: ""
                                eventScope.launch {
                                    runCatching {
                                        folderWatchRepository.addEvent(watchId, event, eventName)
                                    }
                                }
                            }
                        }.also { it.startWatching() }
                    } catch (e: Exception) {
                        folderWatchRepository.removeWatch(id)
                        return ToolResult(
                            false,
                            "Ошибка: не удалось запустить наблюдение: ${e.message}"
                        )
                    }

                    running[id] = observer

                    ToolResult(
                        true,
                        "Наблюдение #$id запущено: ${dir.absolutePath}, маска=$mask. " +
                            "Ограничение: FileObserver не рекурсивен (следит только за этой папкой) " +
                            "и перестаёт работать после завершения процесса."
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "folder_watch_stop"
            override suspend fun execute(arguments: JsonObject): ToolResult {
                return try {
                    val id = watchId(arguments) ?: return ToolResult(false, "Ошибка: укажите watch_id.")
                    if (folderWatchRepository.getWatch(id) == null) {
                        return ToolResult(false, "Наблюдение #$id не найдено.")
                    }
                    // `?.` tolerates an observer that is not running (e.g. after a process restart).
                    running.remove(id)?.stopWatching()
                    folderWatchRepository.removeWatch(id)
                    // Verify the watch is really gone before claiming success.
                    if (folderWatchRepository.getWatch(id) != null) {
                        ToolResult(false, "Не удалось остановить наблюдение #$id: запись всё ещё сохранена.")
                    } else {
                        ToolResult(true, "Наблюдение #$id остановлено.")
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "folder_watch_list"
            override suspend fun execute(arguments: JsonObject): ToolResult = try {
                val watches = folderWatchRepository.listWatches()
                if (watches.isEmpty()) {
                    ToolResult(true, "Нет сохранённых наблюдений.")
                } else {
                    val lines = watches
                        .sortedBy { it.id }
                        .map { w ->
                            val active = if (running.containsKey(w.id)) "да" else "нет"
                            "#${w.id} ${w.path} mask=${w.mask} — активен: $active"
                        }
                    ToolResult(true, (lines + RESTART_NOTE).joinToString("\n"))
                }
            } catch (e: Exception) {
                ToolResult(false, "Ошибка: ${e.message}")
            }
        },
        object : ToolHandler {
            override val name = "folder_watch_events"
            override suspend fun execute(arguments: JsonObject): ToolResult {
                return try {
                    val id = watchId(arguments) ?: return ToolResult(false, "Ошибка: укажите watch_id.")
                    if (folderWatchRepository.getWatch(id) == null) {
                        return ToolResult(false, "Наблюдение #$id не найдено.")
                    }
                    val limit = ((arguments["limit"] as? JsonPrimitive)?.content?.toIntOrNull()
                        ?: DEFAULT_EVENTS_LIMIT)
                        .coerceAtLeast(1)
                        .coerceAtMost(MAX_EVENTS_LIMIT)

                    val items = folderWatchRepository.events(id, limit)
                    val total = folderWatchRepository.eventCount(id)
                    val head = "Всего событий: $total, показано: ${items.size}."
                    if (items.isEmpty()) {
                        ToolResult(true, "$head\nСобытий пока нет.")
                    } else {
                        val lines = items.map { e ->
                            "${formatTime(e.timestamp)} ${e.event} ${e.name}".trim()
                        }
                        ToolResult(true, "$head\n" + lines.joinToString("\n"))
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "folder_watch_clear"
            override suspend fun execute(arguments: JsonObject): ToolResult {
                return try {
                    val id = watchId(arguments) ?: return ToolResult(false, "Ошибка: укажите watch_id.")
                    if (folderWatchRepository.getWatch(id) == null) {
                        return ToolResult(false, "Наблюдение #$id не найдено.")
                    }
                    folderWatchRepository.clearEvents(id)
                    val remaining = folderWatchRepository.eventCount(id)
                    if (remaining == 0) {
                        ToolResult(true, "События наблюдения #$id очищены.")
                    } else {
                        ToolResult(false, "Не удалось очистить события: осталось $remaining.")
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
    )

    /** Parses the optional numeric `watch_id` argument; null when absent or not a number. */
    private fun watchId(arguments: JsonObject): Long? =
        (arguments["watch_id"] as? JsonPrimitive)?.content?.trim()?.toLongOrNull()

    private fun formatTime(millis: Long): String = formatTimestamp(millis, "yyyy-MM-dd HH:mm:ss")

    /** Maps the tool's `mask` argument to a FileObserver event mask; null when unknown. */
    private fun maskFromName(name: String?): Int? = when (name) {
        null, "", "all" -> ALL_MASK
        "create" -> FileObserver.CREATE
        "modify" -> FileObserver.MODIFY or FileObserver.CLOSE_WRITE
        "delete" -> FileObserver.DELETE
        "move" -> FileObserver.MOVED_FROM or FileObserver.MOVED_TO
        else -> null
    }

    private companion object {
        private const val DEFAULT_EVENTS_LIMIT = 100
        private const val MAX_EVENTS_LIMIT = 500

        /** Honest, always-shown note about the lifetime of an observer. */
        private const val RESTART_NOTE =
            "Наблюдения не переживают перезапуск процесса: после рестарта список сохраняется, " +
                "но статус — неактивен."

        /** Mask used for the default / `all` argument. */
        private val ALL_MASK = FileObserver.CREATE or
            FileObserver.MODIFY or
            FileObserver.DELETE or
            FileObserver.MOVED_FROM or
            FileObserver.MOVED_TO or
            FileObserver.CLOSE_WRITE

    }
}
