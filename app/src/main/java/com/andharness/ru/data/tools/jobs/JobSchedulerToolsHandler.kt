package com.andharness.ru.data.tools.jobs

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import com.andharness.ru.bg.AndharnessJobService
import com.andharness.ru.data.jobs.JobSpec
import com.andharness.ru.data.jobs.JobSpecStore
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.chat.CurrentChatProvider
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.andharness.ru.data.tools.str

/**
 * Builds one [ToolHandler] per JobScheduler tool: `job_schedule`, `job_list`, `job_cancel`.
 *
 * Jobs are periodic (JobScheduler's minimum period is 15 minutes), and each one either runs an agent
 * chat with the stored prompt or posts the stored notification text when it fires. Everything is
 * persisted in [JobSpecStore] so the payload survives a process death, and both `job_list` and
 * `job_cancel` read back the REAL scheduler state (`getAllPendingJobs` / `getPendingJob`) instead of
 * trusting our own bookkeeping — an honest answer, not an optimistic one.
 *
 * References:
 * - `JobScheduler` / `JobInfo`: https://developer.android.com/reference/android/app/job/JobScheduler
 * - `JobInfo.Builder.setPeriodic(long)` (>= 15 min):
 *   https://developer.android.com/reference/android/app/job/JobInfo.Builder#setPeriodic(long)
 */
class JobSchedulerToolsHandler(
    private val context: Context,
    private val currentChat: CurrentChatProvider = CurrentChatProvider(),
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "job_schedule"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val scheduler = context.getSystemService(JobScheduler::class.java)
                        ?: return@withContext ToolResult(false, "JobScheduler недоступен на этом устройстве.")
                    val intervalRaw = arguments.str("interval_min")
                        ?: return@withContext ToolResult(false, "Укажите interval_min — период в минутах (минимум 15).")
                    val requested = intervalRaw.toIntOrNull()
                        ?: return@withContext ToolResult(false, "interval_min должно быть целым числом минут (например 30).")
                    if (requested <= 0) {
                        return@withContext ToolResult(false, "interval_min должно быть положительным (минимум 15 минут).")
                    }
                    val intervalMin = requested.coerceAtLeast(MIN_INTERVAL_MIN)

                    val prompt = arguments.str("prompt")
                    val notifyText = arguments.str("notify_text")
                    if (prompt == null && notifyText == null) {
                        return@withContext ToolResult(
                            false,
                            "Укажите prompt (текст запроса агенту) или notify_text (текст уведомления) — иначе задача ничего не сделает."
                        )
                    }
                    val requiresCharging = boolArg(arguments, "requires_charging") ?: false
                    val requiresNetwork = boolArg(arguments, "requires_network") ?: false

                    val store = JobSpecStore(context)
                    val id = store.nextId()
                    val builder = JobInfo.Builder(id, ComponentName(context, AndharnessJobService::class.java))
                        .setPeriodic(intervalMin * 60_000L)
                        .setRequiresCharging(requiresCharging)
                    if (requiresNetwork) {
                        builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    }
                    val result = scheduler.schedule(builder.build())
                    if (result != JobScheduler.RESULT_SUCCESS) {
                        return@withContext ToolResult(
                            false,
                            "Система отказалась планировать задачу (код $result). Проверьте период (минимум 15 минут) и лимит задач приложения."
                        )
                    }

                    val spec = JobSpec(
                        id = id,
                        intervalMin = intervalMin,
                        requiresCharging = requiresCharging,
                        requiresNetwork = requiresNetwork,
                        prompt = prompt,
                        notifyText = notifyText,
                        chatId = currentChat.chatId(),
                    )
                    store.save(spec)

                    val pending = scheduler.getPendingJob(id) != null
                    val constraints = buildList {
                        if (requiresCharging) add("зарядка")
                        if (requiresNetwork) add("сеть")
                    }.joinToString(", ").ifEmpty { "без особых условий" }
                    ToolResult(
                        true,
                        buildString {
                            append("Задача #$id запланирована: каждые $intervalMin мин ($constraints). ")
                            append(if (prompt != null) "При срабатывании запустится агент с запросом: ${prompt.take(80)}"
                            else "При срабатывании придёт уведомление.")
                            if (requested < MIN_INTERVAL_MIN) {
                                append("\nПериод увеличен до $MIN_INTERVAL_MIN мин: JobScheduler не поддерживает более частые периодические задачи.")
                            }
                            append("\nПериодичность неточная — система может задержать запуск (особенно в Doze).")
                            if (!pending) append("\nВнимание: задача не видна в списке ожидающих сразу после планирования.")
                        }
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "job_list"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val scheduler = context.getSystemService(JobScheduler::class.java)
                        ?: return@withContext ToolResult(false, "JobScheduler недоступен на этом устройстве.")
                    val pending = scheduler.allPendingJobs.orEmpty()
                    if (pending.isEmpty()) {
                        ToolResult(true, "Запланированных задач JobScheduler нет.")
                    } else {
                        val store = JobSpecStore(context)
                        ToolResult(true, pending.joinToString("\n") { info ->
                            val spec = store.get(info.id)
                            val payload = spec?.prompt?.take(80)
                                ?: spec?.notifyText?.take(80)
                                ?: "—"
                            val constraints = buildList {
                                if (info.isRequireCharging) add("зарядка")
                                if (info.networkType != JobInfo.NETWORK_TYPE_NONE) add("сеть")
                            }.joinToString(", ").ifEmpty { "нет" }
                            val periodMin = info.intervalMillis / 60_000L
                            "#${info.id} | каждые ${periodMin}мин | условия: $constraints | $payload"
                        })
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "job_cancel"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val scheduler = context.getSystemService(JobScheduler::class.java)
                        ?: return@withContext ToolResult(false, "JobScheduler недоступен на этом устройстве.")
                    val id = arguments.str("id")?.toIntOrNull()
                        ?: return@withContext ToolResult(false, "Укажите id задачи (см. job_list).")
                    val store = JobSpecStore(context)
                    val known = scheduler.getPendingJob(id) != null || store.get(id) != null
                    if (!known) {
                        return@withContext ToolResult(false, "Задача #$id не найдена (см. job_list).")
                    }
                    scheduler.cancel(id)
                    store.remove(id)

                    // Verify against the real scheduler state, with a bounded wait: cancel() is
                    // applied asynchronously by the system in some cases.
                    var gone = false
                    for (attempt in 1..VERIFY_ATTEMPTS) {
                        if (scheduler.getPendingJob(id) == null) {
                            gone = true
                            break
                        }
                        if (attempt < VERIFY_ATTEMPTS) delay(VERIFY_DELAY_MS)
                    }
                    if (gone) {
                        ToolResult(true, "Задача #$id отменена и удалена из планировщика.")
                    } else {
                        ToolResult(false, "Команда отмены отправлена, но задача #$id всё ещё числится в планировщике.")
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
    )

    private fun boolArg(arguments: JsonObject, key: String): Boolean? =
        (arguments[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()


    private companion object {
        /** JobScheduler's documented minimum periodic interval. */
        const val MIN_INTERVAL_MIN = 15
        const val VERIFY_ATTEMPTS = 5
        const val VERIFY_DELAY_MS = 100L
    }
}
