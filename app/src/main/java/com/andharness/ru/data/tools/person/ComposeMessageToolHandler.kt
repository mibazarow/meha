package com.andharness.ru.data.tools.person

import com.andharness.ru.data.message.MessageSendOutcome
import com.andharness.ru.data.message.MessageSender
import com.andharness.ru.data.person.PersonIndexer
import com.andharness.ru.data.person.PersonRef
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.message.ChannelDecision
import com.andharness.ru.domain.message.MessageChannel
import com.andharness.ru.domain.message.MessageChannelRequest
import com.andharness.ru.domain.message.MessageChannelSelector
import com.andharness.ru.domain.person.IdentityType
import com.andharness.ru.domain.person.InteractionChannel
import com.andharness.ru.domain.person.InteractionDirection
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `compose_message`: resolves first, then sends over the chosen channel — never to a guess. */
internal class ComposeMessageToolHandler(
    private val messageSender: MessageSender,
    private val recipientResolver: PersonRecipientResolver,
    private val indexer: PersonIndexer,
    private val args: PersonToolArgs,
) : ToolHandler {

    override val name = "compose_message"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            composeMessage(arguments)
        } catch (e: Exception) {
            ToolResult(false, "Ошибка отправки сообщения: ${e.message}")
        }
    }

    private suspend fun composeMessage(arguments: JsonObject): ToolResult {
        val to = args.string(arguments, "to")
            ?: return ToolResult(false, "Укажите to — имя человека или номер/ник.")
        val text = args.string(arguments, "text")
            ?: return ToolResult(false, "Укажите text — текст сообщения.")
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
            is ChannelDecision.Unavailable -> return ToolResult(false, "Не отправлено: ${decision.reason}")
        }

        return when (channel) {
            MessageChannel.SMS -> {
                val target = recipient.phone!!
                when (val outcome = messageSender.sendSms(target, text)) {
                    is MessageSendOutcome.Sent -> {
                        val recorded = recordOutgoingSms(recipient, target, text)
                        ToolResult(
                            true,
                            "${outcome.detail} Получатель: ${recipient.displayName} (#$target).$recorded"
                        )
                    }
                    is MessageSendOutcome.Failed -> ToolResult(false, "Не отправлено: ${outcome.reason}")
                    is MessageSendOutcome.Opened ->
                        ToolResult(false, "Не отправлено: неожиданный результат SMS-отправки. Ничего не подтверждено.")
                }
            }
            MessageChannel.MESSENGER -> {
                val identity = recipient.messenger!!
                val appPackage = identity.appPackage.takeIf { it.isNotBlank() }
                when (val outcome = messageSender.openMessenger(text, appPackage)) {
                    is MessageSendOutcome.Opened -> ToolResult(
                        true,
                        "${outcome.detail} Получатель: ${recipient.displayName} (${identity.value}). " +
                            "Честно: текст только открыт для отправки, доставка НЕ подтверждена."
                    )
                    is MessageSendOutcome.Failed -> ToolResult(false, "Не отправлено: ${outcome.reason}")
                    is MessageSendOutcome.Sent ->
                        ToolResult(false, "Не отправлено: неожиданный результат мессенджер-отправки.")
                }
            }
        }
    }

    /** Records an OUTGOING SMS interaction (metadata + bounded gist only); returns a short suffix. */
    private suspend fun recordOutgoingSms(
        recipient: PersonRecipientResolver.Recipient,
        target: String,
        text: String,
    ): String {
        val ref = recipient.personId?.let { PersonRef.Existing(it) }
            ?: PersonRef.Identity(IdentityType.PHONE, target)
        val id = indexer.recordInteraction(
            channel = InteractionChannel.SMS,
            direction = InteractionDirection.OUTGOING,
            at = System.currentTimeMillis(),
            personIdOrIdentity = ref,
            summary = text,
        )
        return if (id != null) {
            " Взаимодействие записано в персональную базу (метаданные + gist)."
        } else {
            " Взаимодействие не записалось (отправка при этом подтверждена)."
        }
    }
}
