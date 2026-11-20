package com.andharness.ru.ui.chats

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.andharness.ru.R
import com.andharness.ru.data.tools.media.MediaViewer
import com.andharness.ru.domain.chat.AttachmentKind
import com.andharness.ru.domain.chat.AttachmentPolicy
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Inline previews for the attachments of a USER message: images render a real thumbnail decoded from
 * the app-private file, other files render a compact name chip. Tapping any of them opens the file
 * with the system viewer through the same [MediaViewer] path the `media_open` tool uses.
 *
 * This replaces the raw `[Вложения: …]` text block (produced by [AttachmentPolicy.buildUserText]) in
 * the user's own bubble. The block itself is untouched in the persisted message — the model still
 * receives the explicit file paths.
 */
@Composable
internal fun UserAttachmentPreviews(
    attachments: List<AttachmentPolicy.ParsedUserAttachment>,
    modifier: Modifier = Modifier
) {
    if (attachments.isEmpty()) return
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        attachments.forEach { attachment ->
            if (attachment.kind == AttachmentKind.IMAGE && attachment.path.isNotBlank()) {
                UserImagePreview(attachment)
            } else {
                UserFileChip(attachment)
            }
        }
    }
}

/**
 * An image thumbnail. Decoding runs off the main thread with `inSampleSize`
 * ([AttachmentPolicy.sampleSize]) so a full-resolution photo is never loaded into memory; while it
 * loads — or if it cannot be decoded — a calm placeholder is shown instead of a blocked frame.
 */
@Composable
private fun UserImagePreview(attachment: AttachmentPolicy.ParsedUserAttachment) {
    val context = LocalContext.current
    val bitmap = rememberFileBitmap(attachment.path)
    val openLabel = stringResource(R.string.chat_search_result_open)
    val shape = RoundedCornerShape(10.dp)
    val size = bitmap?.let { displaySize(it.width, it.height) }
    Box(
        modifier = Modifier
            .then(
                if (size != null) {
                    Modifier.size(size.first, size.second)
                } else {
                    Modifier.size(160.dp, 120.dp)
                }
            )
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .clickable { openLocalFile(context, attachment.path, attachment.mime) }
            .semantics { contentDescription = "$openLabel: ${attachment.name}" },
        contentAlignment = Alignment.Center
    ) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = attachment.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_attach_file),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/** A compact non-image attachment chip: document/file glyph + name; tappable to open. */
@Composable
private fun UserFileChip(attachment: AttachmentPolicy.ParsedUserAttachment) {
    val context = LocalContext.current
    val openLabel = stringResource(R.string.chat_search_result_open)
    val icon = if (attachment.kind == AttachmentKind.DOCUMENT) {
        R.drawable.ic_document
    } else {
        R.drawable.ic_attach_file
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { openLocalFile(context, attachment.path, attachment.mime) }
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics { contentDescription = "$openLabel: ${attachment.name}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = attachment.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Decodes [path] to a downscaled [ImageBitmap] off the main thread; `null` on any failure. */
@Composable
private fun rememberFileBitmap(path: String): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                val sample = AttachmentPolicy.sampleSize(
                    bounds.outWidth,
                    bounds.outHeight,
                    DECODE_TARGET_PX
                )
                BitmapFactory
                    .decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}

/** Largest on-screen size of an image preview, preserving the source aspect ratio. */
private fun displaySize(width: Int, height: Int): Pair<Dp, Dp> {
    if (width <= 0 || height <= 0) return MAX_PREVIEW_WIDTH to MAX_PREVIEW_HEIGHT
    val ratio = width.toFloat() / height.toFloat()
    return if (ratio >= 1f) {
        MAX_PREVIEW_WIDTH to (MAX_PREVIEW_WIDTH / ratio)
    } else {
        (MAX_PREVIEW_HEIGHT * ratio) to MAX_PREVIEW_HEIGHT
    }
}

/**
 * Opens an app-private attachment file with the system viewer. Exposed through the shared
 * `FileProvider` (the `attachments/` dir sits under `<external-files-path>`, see `file_paths.xml`),
 * so the receiver gets a grantable `content://` URI instead of a raw `file://`.
 */
private fun openLocalFile(context: Context, path: String, mime: String) {
    val file = File(path)
    if (!file.isFile) return
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull() ?: return
    MediaViewer.open(context, uri, mime.ifBlank { "*/*" })
}

/** Decode target: the longer side of the thumbnail handed to `inSampleSize`. */
private const val DECODE_TARGET_PX = 512

private val MAX_PREVIEW_WIDTH = 240.dp
private val MAX_PREVIEW_HEIGHT = 280.dp
