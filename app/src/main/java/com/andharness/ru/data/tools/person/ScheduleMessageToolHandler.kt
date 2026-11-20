package com.andharness.ru.data.tools.person

import android.content.Context
import com.andharness.ru.bg.MessageSendWorker
import com.andharness.ru.data.message.DeferredMessage
import com.andharness.ru.data.message.MessageSendStore
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.chat.CurrentChatProvider
import com.andharness.ru.domain.message.ChannelDecision
import com.andharness.ru.domain.message.MessageChannel
import com.andharness.ru.domain.message.MessageChannelRequest
import com.andharness.ru.domain.message.MessageChannelSelector
import com.andharness.ru.domain.message.MessageScheduleTime
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * `schedule_message`: resolves the recipient now, then hands a `DeferredMessage` to the bounded
 * `MessageSendWorker`. It is a direct send, not an LLM task; the delivery report arrives by
 * notification.
 */
internal class ScheduleMessageToolHandler(
    private val context: Context,
    private val messageSendStore: MessageSendStore,
    private val recipientResolver: PersonRecipientResolver,
    private val readSupport: PersonReadSupport,
    private val currentChatProvider: CurrentChatProvider,
    private val args: PersonToolArgs,
) : ToolHandler {

    override val name = "schedule_message"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            scheduleMessage(arguments)
        } catch (e: Exception) {
            ToolResult(false, "Ошибка планирования сообщения: ${e.message}")
        }
    }

    private suspend fun scheduleMessage(arguments: JsonObject): ToolResult {
        val to = args.string(arguments, "to")
            ?: return ToolResult(false, "Укажите to — имя человека или номер/ник.")
        val text = args.string(arguments, "text")
            ?: return ToolResult(false, "Укажите text — текст сообщения.")
        val rawAt = args.string(arguments, "at")
            ?: return ToolResult(false, "Укажите at — время отправки (epoch millis или HH:MM).")
        val request = MessageChannelRequest.from(args.string(arguments, "channel"))
            ?: return ToolResult(false, "Неизвестный channel. Доступно: AUTO, SMS, MESSENGER.")

        val recipient = when (val resolution = recipientResolver.resolve(to, request)) {
            is PersonRecipientResolver.RecipientResolution.NeedUser -> return ToolResult(false, resolution.text)
            is PersonRecipientResolver.RecipientResolution.NotFound -> return ToolResult(false, resolution.text)
            is PersonRecipientResolver.RecipientResolution.Ready -> resolution.recipient
        }

        val channel = when (
            val decision = MessageChannelSelector.select(
                request = request,
                hasPhone = recipient.phone != null,
                hasMessenger = recipient.messenger != null,
            )
        ) {
            is ChannelDecision.Chosen -> decision.channel
            is ChannelDecision.Unavailable ->
                return ToolResult(false, "Не запланировано: ${decision.reason}")
        }

        val now = System.currentTimeMillis()
        val at = MessageScheduleTime.parse(rawAt, now)
            ?: return ToolResult(
                false,
                "Не понял время «$rawAt». Передай epoch millis или HH:MM (например 09:30). " +
                    "Ничего не запланировано."
            )
        if (at <= now) {
            return ToolResult(
                false,
                "Время ${readSupport.formatDate(at)} уже прошло — отложенная отправка не создана. " +
                    "Для немедленной отправки используй compose_message."
            )
        }

        val (target, targetPackage) = when (channel) {
            MessageChannel.SMS -> recipient.phone!! to null
            MessageChannel.MESSENGER -> recipient.messenger!!.value to
                recipient.messenger.appPackage.takeIf { it.isNotBlank() }
        }
        val chatId = args.long(arguments, "chat_id") ?: currentChatProvider.chatId()

        val job = DeferredMessage(
            id = messageSendStore.nextId(),
            personId = recipient.personId,
            displayName = recipient.displayName,
            target = target,
            targetPackage = targetPackage,
            channel = channel,
            text = text,
            scheduledAt = at,
            chatId = chatId,
        )
        messageSendStore.add(job)
        MessageSendWorker.enqueue(context, job)

        return ToolResult(
            true,
            "Отложенное сообщение #${job.id} запланировано на ${readSupport.formatDate(at)} (${channel.name}) " +
                "для ${recipient.displayName} ($target). Это прямая отправка сообщения, а не запуск " +
                "LLM-задачи. Отчёт о доставке придёт уведомлением в " +
                (if (chatId != null) "чат #$chatId." else "чат (чат не определён).") +
                " Честно: если к моменту отправки цель или канал станут неразрешимыми, либо отправка " +
                "не подтвердится, уведомление сообщит об этом. Отменить до отправки: " +
                "schedule_message_cancel с id #${job.id}."
        )
    }
}
