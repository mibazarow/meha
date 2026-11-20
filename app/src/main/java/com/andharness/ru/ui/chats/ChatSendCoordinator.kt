package com.andharness.ru.ui.chats

import com.andharness.ru.data.attachments.AttachmentStore
import com.andharness.ru.domain.agent.AgentOrchestrator
import com.andharness.ru.domain.agent.AgentRunOutcome
import com.andharness.ru.domain.chat.AttachmentPolicy
import com.andharness.ru.domain.chat.ChatAttachment
import com.andharness.ru.domain.chat.ChatTitleGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Owns the «send» agent-turn orchestration for one chat: it clears the composer, copies staged
 * attachments to stable app-private storage (§19.3), hands everything to the
 * [AgentOrchestrator], restores the message honestly when the agent is busy, and renames the chat
 * from its first message.
 */
internal class ChatSendCoordinator(
    private val chatId: Long,
    private val state: ChatStateHolder,
    private val scope: CoroutineScope,
    private val orchestrator: AgentOrchestrator,
    private val attachmentStore: AttachmentStore,
    private val titleGenerator: ChatTitleGenerator
) {

    fun send() {
        val current = state.current
        val hasAttachments = current.attachments.isNotEmpty()
        if ((current.input.isBlank() && !hasAttachments) || current.isStreaming) {
            return
        }
        val text = current.input
        val pending = current.attachments
        val isFirstMessage = current.messages.isEmpty()
        state.update {
            it.copy(input = "", attachments = emptyList(), error = null, attachmentNotice = null)
        }
        scope.launch {
            // §19.3: copy each attachment to a stable app-private path before handing it to the
            // agent — the picked URI may be temporary or permission-scoped.
            val saved = mutableListOf<ChatAttachment>()
            var failed = 0
            for (attachment in pending) {
                // Inbound shares (update.md idea #10) were already copied to app-private storage by
                // the receiving Activity, so they are handed over as-is instead of being copied
                // again.
                val chatAttachment = attachment.stored ?: attachmentStore.save(
                    uri = attachment.uri,
                    name = attachment.name,
                    mime = attachment.mime,
                    kind = attachment.kind
                )
                if (chatAttachment != null) {
                    saved += chatAttachment
                } else {
                    failed++
                }
            }
            if (saved.isEmpty() && pending.isNotEmpty()) {
                state.update {
                    it.copy(
                        attachmentNotice = "Не удалось приложить файлы: не удалось прочитать " +
                            "вложение. Попробуйте выбрать файл заново."
                    )
                }
                return@launch
            }
            if (failed > 0) {
                state.update {
                    it.copy(
                        attachmentNotice = "Не удалось приложить $failed вложение(й) из " +
                            "${pending.size} — файл не был прочитан."
                    )
                }
            }
            // The turn is STARTED by the orchestrator, which owns it on an application-lifetime
            // scope: this ViewModel scope only triggers it. Leaving the screen therefore cannot
            // cancel the turn (only the explicit «Стоп» / cancel(chatId) can).
            val outcome = orchestrator.start(chatId, text, saved)
            if (outcome == AgentRunOutcome.BUSY) {
                // Another run is active, so nothing was sent. Give the message back to the user
                // instead of silently dropping it.
                state.update {
                    it.copy(
                        input = text,
                        attachments = pending,
                        error = "Агент уже выполняет другую задачу — сообщение не отправлено. " +
                            "Дождитесь завершения или нажмите «Стоп»."
                    )
                }
                return@launch
            }
            if (isFirstMessage) {
                titleGenerator.renameFromFirstMessage(
                    chatId,
                    AttachmentPolicy.buildUserText(text, saved)
                )
            }
        }
    }
}
