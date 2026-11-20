package com.andharness.ru.ui.chats

import android.net.Uri
import com.andharness.ru.data.attachments.AttachmentStore
import com.andharness.ru.domain.chat.AttachmentKind
import com.andharness.ru.inbound.InboundSeedStore
import java.io.File

/**
 * Consumes the seed an inbound entry point parked for a chat (update.md idea #10): the share /
 * process-text receivers create the chat and store its seed before navigating. Reading the seed
 * converts its already app-private attachments into stage-ready [PendingAttachment]s and hands the
 * result back to the owner, which decides how to present (and whether to auto-send) it.
 */
internal class InboundSeedConsumer(
    private val inboundSeedStore: InboundSeedStore,
    private val attachmentStore: AttachmentStore
) {

    /**
     * The staged content of an inbound seed, ready to be written into the chat's UI state. [autoRun]
     * is true for a plain text share the receiver prefixed with the default instruction, meaning the
     * message must be sent right away.
     */
    data class Seed(
        val text: String,
        val attachments: List<PendingAttachment>,
        val notice: String?,
        val autoRun: Boolean
    )

    /**
     * Takes (exactly once) the seed for [chatId], or returns null when there is none. Selected text
     * and shared files are only staged; only [Seed.autoRun] asks the owner to send immediately.
     */
    suspend fun consume(chatId: Long): Seed? {
        val seed = inboundSeedStore.take(chatId) ?: return null
        val pending = ArrayList<PendingAttachment>(seed.attachments.size)
        for (attachment in seed.attachments) {
            val file = File(attachment.path)
            val uri = Uri.fromFile(file)
            pending += PendingAttachment(
                uri = uri,
                name = attachment.name,
                mime = attachment.mime,
                size = file.length(),
                kind = attachment.kind,
                thumb = if (attachment.kind == AttachmentKind.IMAGE) {
                    attachmentStore.loadThumbnail(uri)
                } else {
                    null
                },
                // Already in app-private storage — `send()` must not copy it again.
                stored = attachment
            )
        }
        return Seed(
            text = seed.text,
            attachments = pending,
            notice = seed.notice,
            autoRun = seed.autoRun
        )
    }
}
