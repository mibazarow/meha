package com.andharness.ru.data.tools.person

import android.content.Context
import com.andharness.ru.bg.MessageSendWorker
import com.andharness.ru.data.message.MessageSendStore
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * `schedule_message_cancel` (BUG-018): cancels a deferred message by id. Both the persisted row and
 * the WorkManager unique work are removed, so the worker cannot fire; a missing id is reported
 * honestly rather than as a failure (it may have already fired or been cancelled).
 */
internal class ScheduleMessageCancelToolHandler(
    private val context: Context,
    private val messageSendStore: MessageSendStore,
    private val readSupport: PersonReadSupport,
    private val args: PersonToolArgs,
) : ToolHandler {

    override val name = "schedule_message_cancel"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            scheduleMessageCancel(arguments)
        } catch (e: Exception) {
            ToolResult(false, "Ошибка отмены отложенного сообщения: ${e.message}")
        }
    }

    private fun scheduleMessageCancel(arguments: JsonObject): ToolResult {
        val id = args.long(arguments, "id")
            ?: return ToolResult(
                false,
                "Укажите числовой id отложенного сообщения (его выдаёт schedule_message и " +
                    "показывает schedule_message_list)."
            )
        val job = messageSendStore.get(id)
            ?: return ToolResult(
                true,
                "Отложенное сообщение #$id не найдено — оно уже отправлено или отменено ранее. " +
                    "Отменять нечего."
            )

        MessageSendWorker.cancel(context, id)
        messageSendStore.remove(id)

        return ToolResult(
            true,
            "Отложенное сообщение #$id для ${job.displayName} (${job.target}) на " +
                "${readSupport.formatDate(job.scheduledAt)} отменено: задача снята с планировщика WorkManager " +
                "и удалена из хранилища, отправка не произойдёт."
        )
    }
}
