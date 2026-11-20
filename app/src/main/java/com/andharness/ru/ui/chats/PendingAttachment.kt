package com.andharness.ru.ui.chats

import android.graphics.Bitmap
import android.net.Uri
import com.andharness.ru.domain.chat.AttachmentKind
import com.andharness.ru.domain.chat.ChatAttachment

/**
 * An attachment picked by the user but not sent yet (§19.2). Holds the source [uri] plus the
 * metadata shown on its tile; [thumb] is a small decoded preview for images (null for documents
 * and for images that could not be decoded — the tile then shows an honest failure state).
 *
 * [stored] is set when the file was already copied into app-private storage by an inbound entry
 * point (update.md idea #10 — share sheet / process text), so `send()` must not copy it a second
 * time.
 */
data class PendingAttachment(
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long,
    val kind: AttachmentKind,
    val thumb: Bitmap? = null,
    val stored: ChatAttachment? = null
)
