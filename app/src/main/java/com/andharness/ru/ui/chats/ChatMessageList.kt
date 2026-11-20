package com.andharness.ru.ui.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.andharness.ru.data.db.entity.MessageEntity
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.enterAnimation
import com.andharness.ru.ui.theme.AgentActionTextDark
import com.andharness.ru.ui.theme.AgentActionTextLight
import io.noties.markwon.Markwon
import kotlinx.coroutines.flow.conflate

/**
 * The chat transcript: the single transient turn-status line (§17) followed by the message bubbles,
 * newest first (`reverseLayout = true`). It owns the list scroll state, the auto-scroll-to-bottom
 * behaviour and the deep-link highlight scroll (§20).
 *
 * [searchScrollMessageId] is the message the in-chat search (redesign §4 phase 9) wants to reveal;
 * when set, the list scrolls to it once and then calls [onSearchScrollConsumed] so a later message
 * arriving does not yank the user back to the old match.
 */
@Composable
internal fun ChatMessageList(
    state: ChatUiState,
    highlightMessageId: Long?,
    searchScrollMessageId: Long?,
    onSearchScrollConsumed: () -> Unit,
    markwon: Markwon,
    onSpeak: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // §18: an assistant bubble exists only once it holds real text. Before that the status line
    // stands in for it, so no empty bubble-with-cursor is ever rendered (no flicker: the gate is
    // driven by the message content itself, and once non-blank the bubble stays).
    // §17: `tool` messages are intentionally NOT rendered as bubbles EXCEPT a `device_search` row
    // that carries a structured payload, which becomes a rich preview block (photo thumbnails /
    // document icons / fallback links). redesign §2 phase 6: the message the current run is writing
    // (`streamingMessageId`) is hidden until the turn completes. The rules live in
    // [ChatTranscriptVisibility] so they are unit-tested on the JVM.
    val visibleMessages = ChatTranscriptVisibility.visible(state.messages, state.streamingMessageId)

    // §17: exactly ONE updating status line for the whole turn (no per-call chat messages). The
    // resolved kind drives the leading icon and its state colour (D10); emoji are never used.
    val turnStatus = chatStatus(
        contextCompaction = state.contextCompaction,
        toolActivity = state.toolActivity,
        isStreaming = state.isStreaming,
        providerThinking = state.providerThinking,
        providerSlow = state.providerSlow
    )
    val statusText: String? = turnStatus?.text

    val listState = rememberLazyListState()

    // Трекер «уже анимированных» сообщений. Вход в чат анимирует только контейнер списка, а
    // отдельные пузыри — лишь новые сообщения (по одному разу на id): потоковые обновления текста и
    // переиспользование элементов при скролле повторную анимацию не запускают (D1).
    val messageTracker = remember { EnterAnimationTracker() }

    // Smooth (typewriter) reveal of the in-flight assistant text. The raw [ChatUiState.streamingText]
    // is published on a throttle, so we buffer the latest target and print the displayed substring at
    // a steady cadence (see [rememberStreamingReveal]). Only the streaming bubble reads the animated
    // value, so the rest of the list is never re-rendered per revealed character.
    val reveal = rememberStreamingReveal(state.streamingText)
    val streamingTarget = state.streamingText.orEmpty()
    val showStreamingBubble = state.streamingMessageId != null && streamingTarget.isNotEmpty()

    // Auto-scroll to the newest content (bottom), but stop if the user scrolls up to read earlier
    // messages. `snapshotFlow` also re-emits as the reveal grows, so the list follows the printed
    // text; `conflate()` drops intermediate values so a burst of characters never queues a burst of
    // scroll animations — https://developer.android.com/reference/kotlin/androidx/compose/runtime/package-summary#snapshotFlow(kotlin.Function0)
    // Skipped while a search-result scroll is pending so the two scrolls do not fight.
    LaunchedEffect(state.messages.size, state.isStreaming, statusText, searchScrollMessageId) {
        if (searchScrollMessageId != null) return@LaunchedEffect
        snapshotFlow { reveal.value.toInt() }
            .conflate()
            .collect {
                // Reverse layout: index 0 is the newest (bottom). Auto-scroll only while the user is
                // at the very edge (index 1 = one bubble back), never fighting a manual scroll up.
                // https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/LazyListState#animateScrollToItem(kotlin.Int,kotlin.Int)
                if (listState.firstVisibleItemIndex == 1) {
                    listState.animateScrollToItem(0)
                }
            }
    }

    // §20: when the app was opened from a notification that carried a `message_id`, scroll to that
    // message once it is present in the loaded history.
    LaunchedEffect(highlightMessageId, visibleMessages.size) {
        val target = highlightMessageId ?: return@LaunchedEffect
        val index = visibleMessages.indexOfFirst { it.id == target }
        if (index < 0) return@LaunchedEffect
        // The list uses `reverseLayout = true`, so a message at [index] sits at (size - 1 - index)
        // counting from the bottom; the status line (when shown) occupies index 0.
        val statusOffset = if (statusText != null) 1 else 0
        listState.animateScrollToItem(visibleMessages.size - 1 - index + statusOffset)
    }

    // redesign §4 phase 9: the in-chat search result the user tapped. Reuses the same
    // reverse-layout index math as the deep-link highlight above, then clears the target so it is
    // scrolled to exactly once.
    LaunchedEffect(searchScrollMessageId, visibleMessages.size) {
        val target = searchScrollMessageId ?: return@LaunchedEffect
        val index = visibleMessages.indexOfFirst { it.id == target }
        if (index < 0) return@LaunchedEffect
        val statusOffset = if (statusText != null) 1 else 0
        listState.animateScrollToItem(visibleMessages.size - 1 - index + statusOffset)
        onSearchScrollConsumed()
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            // Мягкий вход списка при открытии чата (fade + подъём на --sp-2). Проигрывается
            // один раз за визит экрана, вне зависимости от рекомпозиций.
            .enterAnimation(),
        contentPadding = PaddingValues(
            // Tight side gutter (10dp → 5dp) so the wider bubbles get the reclaimed edge space;
            // `PaddingValues(horizontal, vertical)` —
            // https://developer.android.com/reference/kotlin/androidx/compose/foundation/layout/PaddingValues
            horizontal = 5.dp,
            vertical = 10.dp
        ),
        // Telegram-like rhythm: a small gap between consecutive bubbles, comfortable outer padding.
        verticalArrangement = Arrangement.spacedBy(4.dp),
        reverseLayout = true
    ) {
        if (statusText != null) {
            item(key = "status") {
                val kind = turnStatus?.kind
                // Theme-aware, calmer action ink. The line used to render in `primary`, which is the
                // gold #F6C94C in the light theme and read as hard-to-see yellow on the paper
                // background. It is now a grey-blue in light and a muted light-grey in dark
                // ([AgentActionTextLight]/[AgentActionTextDark]); the resolved `surface` luminance is
                // the app-theme signal (same pattern as `MessageBubble`/`ChatListScreen`).
                // https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/package-summary#(androidx.compose.ui.graphics.Color).luminance()
                val actionInk = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
                    AgentActionTextDark
                } else {
                    AgentActionTextLight
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (kind != null) {
                        // D10: the leading Material icon and the text colour encode the
                        // state (running/success/failed/compaction/streaming) — no emoji.
                        ChatStatusLeadingIcon(kind)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (kind) {
                            // Honest failure stays in the error role.
                            ChatStatusKind.TOOL_FAILED,
                            ChatStatusKind.PROVIDER_SLOW -> MaterialTheme.colorScheme.error
                            // Run/success/stream/compaction: the calm theme-aware action ink.
                            else -> actionInk
                        },
                        // Italic marks these lines as transient agent narration rather than message
                        // content; the error line stays upright for emphasis.
                        fontStyle = if (
                            kind == ChatStatusKind.TOOL_FAILED ||
                            kind == ChatStatusKind.PROVIDER_SLOW
                        ) {
                            FontStyle.Normal
                        } else {
                            FontStyle.Italic
                        }
                    )
                }
            }
        }
        // Streaming overlay bubble (smoothed typewriter). Declared right after the status line so,
        // in the reverse layout, it sits just above it — the newest content stays at the bottom. The
        // persisted in-flight row is filtered out of `visibleMessages`, so this is its ONLY
        // rendering; once the turn ends it disappears and the finished message shows from the
        // database, exact and un-animated.
        if (showStreamingBubble) {
            item(key = "streaming") {
                // Only the animated value is read here (never `streamingTarget`, which would be
                // captured stale): the substring below is the one place that clamps to the target.
                val revealedChars by remember(reveal) { derivedStateOf { reveal.value.toInt() } }
                val shownText = remember(streamingTarget, revealedChars) {
                    streamingTarget.substring(0, revealedChars.coerceIn(0, streamingTarget.length))
                }
                // Reuse the persisted row's timestamp once it exists (stable HH:mm); the content is
                // overridden by the smoothed substring.
                val streamingEntity = remember(state.streamingMessageId, streamingTarget) {
                    val persisted = state.messages.firstOrNull { it.id == state.streamingMessageId }
                    MessageEntity(
                        id = state.streamingMessageId ?: -1L,
                        chatId = persisted?.chatId ?: 0L,
                        role = ChatTranscriptVisibility.ROLE_ASSISTANT,
                        content = streamingTarget,
                        createdAt = persisted?.createdAt ?: System.currentTimeMillis()
                    )
                }
                MessageBubble(
                    message = streamingEntity,
                    markwon = markwon,
                    onSpeak = onSpeak,
                    modifier = Modifier.fillMaxWidth(),
                    displayedContent = shownText,
                    isStreaming = true
                )
            }
        }
        itemsIndexed(
            items = visibleMessages.reversed(),
            key = { _, message -> message.id }
        ) { index, message ->
            // redesign §2 phase 6: no streaming cursor. The in-flight message is filtered out of
            // `visibleMessages` above, so every bubble rendered here is a finished message.
            //
            // Входную анимацию получает только самое новое сообщение (index == 0 при
            // reverseLayout). Новое сообщение анимируется один раз по своему id; повторный
            // показ того же id (скролл, потоковые обновления) не анимируется вообще.
            val enterModifier = Modifier.enterAnimation(
                enabled = index == 0,
                tracker = messageTracker,
                itemKey = message.id
            )
            // A `device_search` tool row carries a structured payload → render it as a rich preview
            // block instead of a plain text bubble; everything else (user/assistant) is a bubble.
            if (message.role == ChatTranscriptVisibility.ROLE_TOOL) {
                DeviceSearchPreviewBubble(
                    payload = message.payload,
                    modifier = enterModifier
                )
            } else {
                MessageBubble(
                    message = message,
                    markwon = markwon,
                    onSpeak = onSpeak,
                    modifier = enterModifier
                )
            }
        }
    }
}
