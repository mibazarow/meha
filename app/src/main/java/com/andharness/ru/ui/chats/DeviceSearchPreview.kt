package com.andharness.ru.ui.chats

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andharness.ru.R
import com.andharness.ru.data.index.ImageThumbnailDecoder
import com.andharness.ru.data.tools.media.MediaActionPlanner
import com.andharness.ru.data.tools.media.MediaViewer
import com.andharness.ru.domain.chat.DeviceSearchCard
import com.andharness.ru.domain.chat.DeviceSearchCardKind
import com.andharness.ru.domain.chat.DeviceSearchPayload
import com.andharness.ru.domain.formatting.collapseWhitespace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The rich preview block for a `device_search` tool row: a compact, Telegram-ish list of the found
 * items. An image shows a real thumbnail (decoded off the main thread via the shared
 * [ImageThumbnailDecoder] and `remember`ed), a document shows the generic document glyph plus its
 * name/snippet, and anything unrecognised falls back to a plain tappable link row. Tapping any row
 * opens it with the system viewer through the SAME intent the `media_open` tool uses
 * ([MediaViewer]).
 *
 * Placed on the assistant side (left), matching [MessageBubble]'s assistant styling. When the
 * payload is missing or cannot be parsed the block renders nothing, so a corrupt row never leaves an
 * empty bubble behind.
 */
@Composable
internal fun DeviceSearchPreviewBubble(
    payload: String?,
    modifier: Modifier = Modifier
) {
    val cards = remember(payload) { DeviceSearchPayload.decode(payload) }
    if (cards.isEmpty()) return
    val context = LocalContext.current

    val bubbleShape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomEnd = 18.dp,
        bottomStart = 6.dp
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.955f)
                .background(color = MaterialTheme.colorScheme.surfaceVariant, shape = bubbleShape)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.chat_search_results_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            cards.forEach { card ->
                DeviceSearchPreviewRow(
                    card = card,
                    onOpen = { openCard(context, card) }
                )
            }
        }
    }
}

/** One result row: thumbnail/icon + name (+ snippet). The whole row is the tap target. */
@Composable
private fun DeviceSearchPreviewRow(
    card: DeviceSearchCard,
    onOpen: () -> Unit
) {
    val openLabel = stringResource(R.string.chat_search_result_open)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onOpen)
            .padding(4.dp)
            .semantics { contentDescription = "$openLabel: ${card.displayName}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        when (card.kind) {
            DeviceSearchCardKind.IMAGE -> SearchThumbnail(uri = card.uri, name = card.displayName)
            DeviceSearchCardKind.DOCUMENT -> ResultGlyph(
                drawableId = R.drawable.ic_document,
                description = stringResource(R.string.chat_search_result_document)
            )
            DeviceSearchCardKind.UNKNOWN -> ResultGlyph(
                drawableId = R.drawable.ic_attach_file,
                description = stringResource(R.string.chat_search_result_file)
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = card.displayName.ifBlank { card.uri },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val snippet = remember(card.snippet) { collapseSnippet(card.snippet) }
            if (snippet.isNotEmpty()) {
                Text(
                    text = snippet,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * The image thumbnail. Decoding runs on [Dispatchers.IO] through [ImageThumbnailDecoder] and the
 * result is remembered per URI; while it loads (or if it cannot be decoded) a calm placeholder box
 * is shown, never a blocked frame.
 */
@Composable
private fun SearchThumbnail(uri: String, name: String) {
    val context = LocalContext.current
    // The decoder is stateless (it only holds the context), so a per-composition instance mirrors a
    // singleton cheaply and avoids threading a Hilt dependency into the transcript composable.
    val decoder = remember(context) { ImageThumbnailDecoder(context.applicationContext) }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { decoder.decodeBitmap(uri) }.getOrNull()?.asImageBitmap()
        }
    }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(52.dp)
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_attach_file),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** The shared non-image glyph tile (document / unknown file). */
@Composable
private fun ResultGlyph(drawableId: Int, description: String) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(drawableId),
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(26.dp)
        )
    }
}

/** Opens [card] with the system viewer, reusing the `media_open` intent path (`MediaViewer`). */
private fun openCard(context: Context, card: DeviceSearchCard) {
    val uri = runCatching { Uri.parse(card.uri) }.getOrNull() ?: return
    val detected = runCatching { context.contentResolver.getType(uri) }.getOrNull()
    val mime = MediaActionPlanner.resolveMime(card.mimeType, detected)
    MediaViewer.open(context, uri, mime)
}

/** Collapses whitespace and trims a matched snippet so a result row stays a single compact block. */
private fun collapseSnippet(raw: String): String {
    val flattened = collapseWhitespace(raw)
    if (flattened.length <= SNIPPET_CHARS) return flattened
    return flattened.take(SNIPPET_CHARS - 1).trimEnd() + '…'
}

private const val SNIPPET_CHARS = 90
