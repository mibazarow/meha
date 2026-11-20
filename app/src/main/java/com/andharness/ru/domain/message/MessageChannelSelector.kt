package com.andharness.ru.domain.message


/**
 * Pure, deterministic channel selection for `compose_message` / `schedule_message` (update.md §4.7).
 *
 * Rules:
 * - an explicit channel is honoured **only when the person actually has a handle for it** — the
 *   selector never falls back silently to another channel the user did not ask for;
 * - [MessageChannelRequest.AUTO] prefers **SMS** when a phone identity exists, otherwise the
 *   messenger;
 * - when nothing is available the decision is [ChannelDecision.Unavailable] with an honest reason,
 *   so the agent asks the user instead of guessing.
 *
 * Android-free and unit-testable on the JVM.
 */
object MessageChannelSelector {

    fun select(
        request: MessageChannelRequest,
        hasPhone: Boolean,
        hasMessenger: Boolean,
    ): ChannelDecision = when (request) {
        MessageChannelRequest.SMS -> if (hasPhone) {
            ChannelDecision.Chosen(MessageChannel.SMS)
        } else {
            ChannelDecision.Unavailable(
                "У этого человека нет PHONE-идентичности, поэтому SMS отправить нельзя. " +
                    "Доступен мессенджер: ${if (hasMessenger) "да" else "нет"}."
            )
        }
        MessageChannelRequest.MESSENGER -> if (hasMessenger) {
            ChannelDecision.Chosen(MessageChannel.MESSENGER)
        } else {
            ChannelDecision.Unavailable(
                "У этого человека нет MESSENGER-идентичности, поэтому написать в мессенджер нельзя. " +
                    "Телефон для SMS: ${if (hasPhone) "есть" else "нет"}."
            )
        }
        MessageChannelRequest.AUTO -> when {
            hasPhone -> ChannelDecision.Chosen(MessageChannel.SMS)
            hasMessenger -> ChannelDecision.Chosen(MessageChannel.MESSENGER)
            else -> ChannelDecision.Unavailable(
                "Ни телефона, ни мессенджер-идентичности нет — отправить некуда. " +
                    "Уточни у пользователя номер или ник."
            )
        }
    }
}
