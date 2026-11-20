package com.andharness.ru.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andharness.ru.R
import com.andharness.ru.data.db.entity.MessageEntity
import com.andharness.ru.domain.chat.ChatSearchResult
import com.andharness.ru.domain.formatting.collapseWhitespace
import com.andharness.ru.ui.theme.ChatRowZebraLight
import com.andharness.ru.ui.theme.ChatRowZebraPlain

/**
 * Cross-chat search results. Each row reuses the chat-row look (initials avatar + bold title) but
 * shows the matched [ChatSearchResult.snippet] as its secondary line. While a search is still in
 * flight and there is nothing to show, the area stays empty; once it settles with no matches, the
 * honest «Ничего не найдено» placeholder is shown.
 */
@Composable
internal fun ChatSearchResults(
    results: List<ChatSearchResult>,
    isSearching: Boolean,
    onChatClick: (Long) -> Unit
) {
    when {
        results.isNotEmpty() -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            itemsIndexed(items = results, key = { _, result -> result.chatId }) { index, result ->
                ChatSearchResultRow(
                    result = result,
                    // Same zebra treatment as the main list, for visual consistency.
                    isZebra = index % 2 == 1,
                    onClick = { onChatClick(result.chatId) }
                )
            }
        }

        !isSearching -> Box(modifier = Modifier.fillMaxSize()) {
            Text(
                text = stringResource(R.string.chat_search_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

/**
 * One search-result row: the same avatar-less layout as [ChatListRow] (a bold title over a secondary
 * line), but the secondary line is the matched [ChatSearchResult.snippet] (falling back to a short
 * note for title-only hits where the snippet is blank). Tapping the row opens that chat.
 */
@Composable
private fun ChatSearchResultRow(
    result: ChatSearchResult,
    isZebra: Boolean,
    onClick: () -> Unit
) {
    val titleMatch = stringResource(R.string.chat_search_title_match)
    // Zebra follows the APP scheme (resolved `surface` luminance), not the OS setting:
    // https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/package-summary#(androidx.compose.ui.graphics.Color).luminance()
    // In LIGHT the background is the same cream as the band (#FFFBF1), so every row is given an
    // explicit fill: plain rows white (#FFFFFF "чат"), banded rows cream (#FFFBF1 "чат"). In DARK
    // the behaviour is unchanged: plain rows have no background, banded rows get the white overlay.
    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val zebraColor = if (darkTheme) {
        if (isZebra) RowZebraDark else null
    } else {
        if (isZebra) ChatRowZebraLight else ChatRowZebraPlain
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Zebra tint applied BEFORE `padding` (and after `fillMaxWidth`) so the stripe spans the
            // row's full width, edge to edge, including its own padding area.
            .then(if (zebraColor != null) Modifier.background(zebraColor) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("chat_search_result_row"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = result.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = result.snippet.ifBlank { titleMatch },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Telegram-style chat row (redesign §4 «Список чатов», phase 7):
 *
 * - a bold, single-line title and a single-line muted preview of the last message («Вы: » prefix when
 *   the user wrote it) filling the row from a clean 16dp left margin (the old leading initials
 *   avatar was dropped so the title/preview get the full width);
 * - a trailing column with the relative time on top and the row actions below it: an always-visible
 *   pin toggle (filled + accent when pinned, outlined + muted otherwise) and a trash button;
 * - tapping anywhere else on the row opens the chat. The old long-press Pin/Delete menu (and its
 *   merged accessibility node) is gone: the actions are now first-class, individually focusable
 *   buttons, so TalkBack reaches them directly.
 */
@Composable
internal fun ChatListRow(
    row: ChatListRow,
    now: Long,
    isZebra: Boolean,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onRequestDelete: () -> Unit
) {
    val chat = row.chat
    val isPinned = chat.pinnedAt > 0L

    // Telegram uses the last message's time; fall back to the chat's own timestamp for an empty chat.
    val lastActivityAt = row.lastMessage?.createdAt ?: chat.updatedAt

    // Zebra tint depends on the APP scheme, not the OS: a soft warm #FFFBF1 in light, a
    // visible white overlay in dark. The resolved `surface` luminance is the app-theme signal (D4
    // keeps a fixed palette), so the stripe stays correct when the app theme differs from the OS:
    // https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/package-summary#(androidx.compose.ui.graphics.Color).luminance()
    // In LIGHT the background is the same cream as the band (#FFFBF1), so every row is given an
    // explicit fill: plain rows white (#FFFFFF "чат"), banded rows cream (#FFFBF1 "чат"). In DARK
    // the behaviour is unchanged: plain rows have no background, banded rows get the white overlay.
    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val zebraColor = if (darkTheme) {
        if (isZebra) RowZebraDark else null
    } else {
        if (isZebra) ChatRowZebraLight else ChatRowZebraPlain
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Zebra tint applied BEFORE `padding` (and after `fillMaxWidth`) so the stripe spans the
            // row's full width, edge to edge, including its own padding area:
            // https://developer.android.com/reference/kotlin/androidx/compose/foundation/package-summary#(androidx.compose.ui.Modifier).background(androidx.compose.ui.graphics.Color,androidx.compose.ui.graphics.Shape)
            .then(if (zebraColor != null) Modifier.background(zebraColor) else Modifier)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
            .testTag("chat_list_row"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = chat.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = chatPreviewText(row.lastMessage),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = formatChatListTime(lastActivityAt, now),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RowIconAction(
                    painter = painterResource(
                        if (isPinned) R.drawable.ic_pin else R.drawable.ic_pin_outline
                    ),
                    contentDescription = if (isPinned) {
                        stringResource(R.string.chat_unpin_cd)
                    } else {
                        stringResource(R.string.chat_pin_cd)
                    },
                    tint = if (isPinned) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    onClick = onPin
                )
                RowIconAction(
                    painter = rememberVectorPainter(Icons.Filled.Delete),
                    contentDescription = stringResource(R.string.chat_delete_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onRequestDelete
                )
            }
        }
    }
}

/**
 * A compact, always-visible row action: a ~20dp glyph in a small (32dp) rounded click target whose
 * nested click consumes the tap, so it never falls through to the row's own `onClick` (the row is
 * what opens the chat). [contentDescription] keeps each action reachable by TalkBack.
 *
 * Docs: `Icon(painter = …)` —
 * https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary#Icon(androidx.compose.ui.graphics.painter.Painter,kotlin.String,androidx.compose.ui.Modifier,androidx.compose.ui.graphics.Color)
 * `Modifier.clickable` —
 * https://developer.android.com/reference/kotlin/androidx/compose/foundation/package-summary#(androidx.compose.ui.Modifier).clickable(kotlin.Boolean,kotlin.String,androidx.compose.ui.semantics.Role,kotlin.Function0)
 */
@Composable
private fun RowIconAction(
    painter: Painter,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * One-line preview of [lastMessage]: newlines/whitespace are collapsed so the row stays a single
 * line, the «Вы: » prefix marks the user's own last message, and an empty/absent message shows a
 * subtle placeholder.
 */
@Composable
internal fun chatPreviewText(lastMessage: MessageEntity?): String {
    val noMessages = stringResource(R.string.chat_no_messages)
    if (lastMessage == null) return noMessages
    val content = collapseWhitespace(lastMessage.content)
    if (content.isEmpty()) return noMessages
    return if (lastMessage.role == "user") {
        stringResource(R.string.chat_preview_you_prefix, content)
    } else {
        content
    }
}

/**
 * Zebra tint for DARK theme: a ~8% white overlay, clearly lighter than the screen background so the
 * alternation is easy to see, while still reading as a subtle band rather than a strong stripe.
 */
private val RowZebraDark = Color.White.copy(alpha = 0.08f)

// The light-theme zebra pair ([ChatRowZebraLight] cream band / [ChatRowZebraPlain] white row) and
// the fixed bottom-bar colours ([AlwaysDarkSurface] fill, [AlwaysDarkBarInk] nav ink,
// [AlwaysDarkBarAccent] / [AlwaysDarkBarAccentContent] accent pill) are theme-independent tokens
// in ui/theme/Color.kt: the bar is ALWAYS dark, identical in both themes, mirroring the DARK
// colour-scheme roles so it never flips to the light `surfaceContainer`/dark ink in the light theme.
// `Color.luminance()` docs (the app-theme signal used for the zebra):
// https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/package-summary#(androidx.compose.ui.graphics.Color).luminance()
