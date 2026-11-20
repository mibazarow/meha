package com.andharness.ru.ui.chats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andharness.ru.R
import com.andharness.ru.domain.chat.ChatMessageMatch
import com.andharness.ru.ui.theme.PillShape

/**
 * In-chat search UI (redesign §4 «Экран чата», phase 9): a Telegram-like search bar shown directly
 * under the chat header plus the list of message matches for the current query. Search is scoped to
 * THIS chat; the query is debounced and cancellable in [ChatViewModel], so these composables only
 * render already-loaded state and forward events.
 */

/**
 * Editable in-chat search field: a fully rounded ([PillShape]) pill on the `surfaceVariant`
 * container, a leading magnifier, the «Поиск по чату» hint and a trailing ✕ that exits search mode
 * (restoring the normal chat view). Every keystroke is forwarded to [onQueryChange]; the search
 * itself is debounced in the ViewModel.
 *
 * It mirrors the chat-list search pill (`ChatSearchField` in `ChatListScreen.kt`) so both fields
 * look and behave identically: the SAME compact ~42dp height and the SAME undecorated look (NO
 * bottom underline/indicator). It is therefore built on [BasicTextField] rather than Material3
 * `TextField`: the latter enforces a 56dp minimum height and a filled underline indicator, so
 * pinning it to a compact pill either clipped the hint/text or left a visible underline.
 * [BasicTextField] has no built-in decoration, no minimum height and no indicator line; the pill is
 * drawn by the surrounding [Surface] (`shape = [PillShape]`) and the single line is vertically
 * centred by the decoration box's [Row] (`verticalAlignment = CenterVertically`). The
 * value/`onValueChange` overload is used to keep the existing VM-hoisted `String` state.
 *
 * Docs:
 * - `BasicTextField` (value/`onValueChange` overload, `singleLine`, `textStyle`, `cursorBrush`,
 *   `decorationBox`; "Interactive text input field without decorations"; `innerTextField` must be
 *   called exactly once) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/foundation/text/BasicTextField.composable
 * - `Surface` (draws and clips the rounded pill; `shape`/`color`) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary#Surface(androidx.compose.ui.Modifier,androidx.compose.ui.graphics.Shape,androidx.compose.ui.graphics.Color,androidx.compose.ui.graphics.Color,androidx.compose.ui.unit.Dp,androidx.compose.ui.unit.Dp,androidx.compose.foundation.BorderStroke,kotlin.Function0)
 * - Text fields guide — https://developer.android.com/develop/ui/compose/text/user-input
 * - `IconButton` (trailing close) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary#IconButton(kotlin.Function0,androidx.compose.ui.Modifier,kotlin.Boolean,androidx.compose.foundation.layout.PaddingValues,androidx.compose.foundation.interaction.MutableInteractionSource,kotlin.Function0)
 */
@Composable
internal fun ChatInChatSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        // Compact ~42dp pill, same as the chat-list search field (no underline for either).
        modifier = modifier.height(42.dp),
        shape = PillShape,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxSize()
                .testTag("chat_search_field"),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.chat_topbar_search_cd),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // innerTextField must be invoked exactly once (BasicTextField contract).
                        innerTextField()
                    }
                    // ✕ exits search mode entirely (clears the query and restores the normal chat view).
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.chat_search_close_cd),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        )
    }
}

/**
 * Result list for the in-chat search: one row per matched message showing its [ChatMessageMatch]
 * snippet and relative time. While a search is still in flight and there is nothing to show the
 * area stays empty; once it settles with no matches the honest «Ничего не найдено» placeholder is
 * shown. Tapping a row invokes [onMatchClick] so the screen can scroll to that message and exit
 * search mode.
 */
@Composable
internal fun ChatInChatSearchResults(
    matches: List<ChatMessageMatch>,
    isSearching: Boolean,
    now: Long,
    onMatchClick: (ChatMessageMatch) -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        matches.isNotEmpty() -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(items = matches, key = { it.messageId }) { match ->
                ChatInChatSearchResultRow(
                    match = match,
                    now = now,
                    onClick = { onMatchClick(match) }
                )
            }
        }

        !isSearching -> Box(modifier = modifier.fillMaxSize()) {
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
 * One in-chat search result row: the matched message snippet (up to two lines) with the message's
 * relative time at the top-right. Tapping opens/scrolls to that message.
 */
@Composable
private fun ChatInChatSearchResultRow(
    match: ChatMessageMatch,
    now: Long,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("chat_search_match_row"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = match.snippet,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatChatListTime(match.timestamp, now),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
