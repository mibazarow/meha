package com.andharness.ru.data.tools.person

import com.andharness.ru.data.message.MessageSendStore
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.message.ScheduledMessageFormat
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * `schedule_message_list` (BUG-018): lists deferred messages still waiting in [MessageSendStore]:
 * id, recipient, channel, scheduled time, a short text preview and a pending/overdue status.
 */
internal class ScheduleMessageListToolHandler(
    private val messageSendStore: MessageSendStore,
    private val readSupport: PersonReadSupport,
) : ToolHandler {

    override val name = "schedule_message_list"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            scheduleMessageList()
        } catch (e: Exception) {
            ToolResult(false, "Ошибка чтения отложенных сообщений: ${e.message}")
        }
    }

    private fun scheduleMessageList(): ToolResult {
        val jobs = messageSendStore.list().sortedBy { it.scheduledAt }
        if (jobs.isEmpty()) {
            return ToolResult(true, "Отложенных сообщений нет.")
        }
        val now = System.currentTimeMillis()
        val body = buildString {
            appendLine("Отложенные сообщения (${jobs.size}):")
            jobs.forEach { job ->
                appendLine(
                    "  - #${job.id} — ${job.displayName} (${job.target}), канал ${job.channel.name}, " +
                        "на ${readSupport.formatDate(job.scheduledAt)} " +
                        "[${ScheduledMessageFormat.statusLabel(job.scheduledAt, now)}], " +
                        "текст: «${ScheduledMessageFormat.preview(job.text)}»"
                )
            }
            append(
                "Отменить ненужное: schedule_message_cancel с id. Честно: в списке только ещё не " +
                    "сработавшие задачи — отправленное удаляется из хранилища в момент отправки и " +
                    "приходит отдельным уведомлением, поэтому здесь не показывается."
            )
        }
        return ToolResult(true, body)
    }
}
