package com.andharness.ru.inbound

import android.content.Intent
import android.net.Uri
import android.os.Build
import com.andharness.ru.data.attachments.AttachmentStore
import com.andharness.ru.domain.chat.AttachmentLimit
import com.andharness.ru.domain.chat.AttachmentPolicy
import com.andharness.ru.domain.chat.ChatAttachment
import com.andharness.ru.domain.chat.ChatRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a share-sheet / process-text Intent into a real chat (update.md idea #10).
 *
 * Runs inside the receiving Activity, i.e. while the caller's URI grant is still valid, so shared
 * files are copied into app-private storage right here (via [AttachmentStore], the same store the
 * composer uses) instead of handing a short-lived `content://` URI to the agent later. The chat is
 * created up front and the prepared [InboundChatSeed] is parked in [InboundSeedStore] under its id;
 * the receiving Activity then just opens `MainActivity` on that chat — no second copy of the agent
 * loop, no duplicate chat-creation path.
 *
 * An Intent carrying [InboundIntents.EXTRA_INBOUND_EXPLAIN] (see [ExplainReceiverActivity]) is the
 * «что это?» path: the seed is composed by [InboundExplainPolicy] and auto-run.
 *
 * Referenced APIs:
 * - `Intent.ACTION_SEND` / `ACTION_SEND_MULTIPLE` / `EXTRA_TEXT` / `EXTRA_STREAM` —
 *   https://developer.android.com/training/sharing/receive
 * - `Intent.ACTION_PROCESS_TEXT` / `EXTRA_PROCESS_TEXT` —
 *   https://developer.android.com/reference/android/content/Intent#ACTION_PROCESS_TEXT
 * - Parcelable-typed extras on API 33+ (`getParcelableExtra(String, Class)`) —
 *   https://developer.android.com/about/versions/13/changes/parcelable-extras
 */
@Singleton
class InboundIntentHandler @Inject constructor(
    private val chatRepository: ChatRepository,
    private val attachmentStore: AttachmentStore,
    private val seedStore: InboundSeedStore
) {

    /**
     * Creates the chat for [intent] and returns its id, or null when the Intent carries nothing we
     * can use (the caller then simply opens the app normally rather than showing an empty chat).
     */
    suspend fun handle(intent: Intent): Long? {
        val action = intent.action ?: return null
        val isProcessText = action == Intent.ACTION_PROCESS_TEXT
        val isSend = action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE
        if (!isProcessText && !isSend) return null

        val explain = intent.getBooleanExtra(InboundIntents.EXTRA_INBOUND_EXPLAIN, false)
        val text = extractText(intent, isProcessText)
        val uris = extractUris(intent, action)
        if (text.isBlank() && uris.isEmpty()) return null

        val saved = ArrayList<ChatAttachment>()
        var dropped = 0
        for (uri in uris) {
            val info = attachmentStore.describe(uri)
            when (AttachmentPolicy.validate(saved.size, info.size)) {
                is AttachmentLimit.TooMany, is AttachmentLimit.TooLarge -> {
                    dropped++
                    continue
                }
                AttachmentLimit.Ok -> Unit
            }
            val attachment = attachmentStore.save(uri, info.name, info.mime, info.kind)
            if (attachment != null) saved += attachment else dropped++
        }

        // «Что это?» (explain) mode: the user explicitly asked Меха to analyse what they shared, so
        // the chat is seeded with an explain instruction (plus any shared text, quoted) and sent
        // right away — including for an image, where the agent then either sends it inline (model
        // supports Vision) or reads it with the on-device ocr_image tool (honest fallback, decided
        // later by AgentOrchestratorImpl, never faked here).
        //
        // A plain text share (no explain flag) keeps its older behaviour: the user already
        // expressed intent by sharing the text, so it is answered with the default «объясни и
        // кратко перескажи» instruction. Everything else (files, selected text) is only staged for
        // the user to finish and send themselves.
        val seedText = if (explain) {
            InboundExplainPolicy.buildSeedText(text, saved)
        } else if (isSend && action == Intent.ACTION_SEND && saved.isEmpty() &&
            text.isNotBlank() && !text.startsWith(TEXT_SHARE_PREFIX)
        ) {
            TEXT_SHARE_PREFIX + text
        } else {
            text
        }

        val autoRun = if (explain) {
            seedText.isNotBlank()
        } else {
            isSend && action == Intent.ACTION_SEND && saved.isEmpty() && text.isNotBlank()
        }

        val chatId = chatRepository.createChat(defaultTitle(action, saved, explain))
        seedStore.put(
            chatId,
            InboundChatSeed(
                text = seedText,
                attachments = saved,
                autoRun = autoRun,
                notice = droppedNotice(dropped)
            )
        )
        return chatId
    }

    /** `EXTRA_PROCESS_TEXT` for the text toolbar, `EXTRA_TEXT` for a share. */
    private fun extractText(intent: Intent, isProcessText: Boolean): String {
        val raw = if (isProcessText) {
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
        } else {
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
        }
        return raw?.toString().orEmpty().trim()
    }

    /**
     * Single URI for `ACTION_SEND`, a list for `ACTION_SEND_MULTIPLE`. `EXTRA_STREAM` is a
     * Parcelable, and the typed getters are the non-deprecated form on API 33+.
     */
    private fun extractUris(intent: Intent, action: String): List<Uri> = when (action) {
        Intent.ACTION_SEND -> listOfNotNull(singleStream(intent))
        Intent.ACTION_SEND_MULTIPLE -> multiStream(intent)
        else -> emptyList()
    }

    private fun singleStream(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    private fun multiStream(intent: Intent): List<Uri> {
        val typed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        }
        if (!typed.isNullOrEmpty()) return typed
        // Not every sender uses the documented `ArrayList<Uri>`: some put a plain string array of
        // URIs into EXTRA_STREAM. Parse those instead of silently dropping the shared files.
        val raw = @Suppress("DEPRECATION") intent.getStringArrayExtra(Intent.EXTRA_STREAM)
            ?: return emptyList()
        return raw.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
            .filter { !it.scheme.isNullOrBlank() }
    }

    /**
     * Temporary chat title. It is replaced by the model-generated one as soon as the first message
     * is sent (see `ChatTitleGenerator.renameFromFirstMessage`), so it only has to be descriptive
     * in the list until then.
     */
    private fun defaultTitle(
        action: String,
        saved: List<ChatAttachment>,
        explain: Boolean
    ): String = when {
        explain -> InboundExplainPolicy.title(saved)
        action == Intent.ACTION_PROCESS_TEXT -> "Выделенный текст"
        saved.isNotEmpty() -> {
            val names = saved.take(2).joinToString(", ") { it.name }
            val more = if (saved.size > 2) " и ещё ${saved.size - 2}" else ""
            "Общий доступ: $names$more"
        }
        else -> "Пересланный текст"
    }

    private fun droppedNotice(dropped: Int): String? {
        if (dropped == 0) return null
        return "Не удалось приложить $dropped файл(ов) из общего доступа: превышен размер " +
            "(${AttachmentPolicy.humanSize(AttachmentPolicy.MAX_SIZE_BYTES)}) или лимит " +
            "${AttachmentPolicy.MAX_ATTACHMENTS} вложений."
    }

    companion object {
        /**
         * Default instruction for a text share. Chosen because a shared block of text carries no
         * question of its own; the user can still stop the run and continue in the same chat.
         */
        const val TEXT_SHARE_PREFIX = "Объясни и кратко перескажи этот текст:\n\n"
    }
}
