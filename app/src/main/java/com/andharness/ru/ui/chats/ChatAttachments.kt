package com.andharness.ru.ui.chats

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andharness.ru.R
import com.andharness.ru.domain.chat.AttachmentKind
import com.andharness.ru.domain.chat.AttachmentPolicy

/** Horizontal strip of staged attachment tiles shown above the input (§19.2). */
@Composable
internal fun AttachmentTiles(
    attachments: List<PendingAttachment>,
    enabled: Boolean,
    onRemove: (Uri) -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(items = attachments, key = { it.uri.toString() }) { attachment ->
            AttachmentTile(
                attachment = attachment,
                enabled = enabled,
                onRemove = onRemove
            )
        }
    }
}

/**
 * One attachment tile: images show a real 100x100 preview decoded with `inSampleSize`; every other
 * file shows the single generic document glyph plus its name and size (§19.2). A remove (x) button
 * drops the attachment before sending. An image whose preview could not be decoded shows an honest
 * failure label instead of pretending it loaded.
 */
@Composable
private fun AttachmentTile(
    attachment: PendingAttachment,
    enabled: Boolean,
    onRemove: (Uri) -> Unit
) {
    Box(modifier = Modifier.size(width = 120.dp, height = 100.dp)) {
        val thumb = attachment.thumb
        if (attachment.kind == AttachmentKind.IMAGE && thumb != null) {
            // `asImageBitmap()` wraps the Bitmap; remember it so it is not re-allocated per frame.
            val imageBitmap = remember(thumb) { thumb.asImageBitmap() }
            Image(
                bitmap = imageBitmap,
                contentDescription = attachment.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(100.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        } else {
            Column(
                modifier = Modifier
                    .size(100.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_document),
                    contentDescription = stringResource(R.string.chat_search_result_document),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(34.dp)
                )
                Text(
                    text = attachment.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (attachment.size > 0) {
                        AttachmentPolicy.humanSize(attachment.size)
                    } else {
                        stringResource(R.string.chat_attachment_size_unknown)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (attachment.kind == AttachmentKind.IMAGE && thumb == null) {
                    Text(
                        text = stringResource(R.string.chat_attachment_load_failed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (enabled) {
            // 48dp interactive target; the 22dp red circle stays visually anchored to the tile's
            // top-end corner (inner Box is aligned TopEnd of the larger hit area).
            val removeCd = stringResource(R.string.chat_attachment_remove_cd)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(48.dp)
                    .semantics { contentDescription = removeCd }
                    .clickable { onRemove(attachment.uri) },
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(22.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "×",
                        color = MaterialTheme.colorScheme.onError,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
