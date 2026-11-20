package com.andharness.ru.ui.chats

import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.andharness.ru.R
import com.andharness.ru.data.db.entity.MessageEntity
import com.andharness.ru.domain.chat.AttachmentPolicy
import com.andharness.ru.ui.theme.AssistantBubbleTextDark
import com.andharness.ru.ui.theme.AssistantBubbleTextLight
import com.andharness.ru.ui.theme.LocalBubbleColors
import io.noties.markwon.Markwon
import kotlinx.coroutines.delay

/**
 * Bubbles never span the full width: the conversation keeps a visible left/right margin.
 * `0.955` halves the REMAINING side gap again: the previous `0.91` left 0.09 per side, so `0.955`
 * left 0.045 per side (a bit under 5% each) — bubbles read clearly wider while still remaining
 * bubbles, not edge-to-edge. Applied via `Modifier.fillMaxWidth(fraction)`, which sizes the content
 * to `Constraints.maxWidth * fraction` —
 * https://developer.android.com/reference/kotlin/androidx/compose/foundation/layout/package-summary
 */
private const val BubbleMaxWidthFraction = 0.955f

/**
 * Telegram-style bubble corners: three generously rounded corners (~18dp) with ONE small (~6dp)
 * «tail» corner marking the sender — bottom-end for the user (right-aligned), bottom-start for the
 * assistant (left-aligned). `RoundedCornerShape(topStart, topEnd, bottomEnd, bottomStart)` —
 * https://developer.android.com/reference/kotlin/androidx/compose/foundation/shape/RoundedCornerShape
 * (18dp chosen to match the Telegram bubble radius; kept as a separate token from the Material 3
 * shape scale — https://m3.material.io/styles/shape/shape-scale-tokens).
 */
private val BubbleCorner = 18.dp
private val BubbleTailCorner = 6.dp

@Composable
internal fun MessageBubble(
    message: MessageEntity,
    markwon: Markwon,
    onSpeak: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Text to render instead of [MessageEntity.content]. The streaming overlay passes the smoothly
     * revealed substring here, so the bubble prints progressively while the persisted row already
     * holds the full (throttled) text. Defaults to the message's own content.
     */
    displayedContent: String = message.content,
    /** True while the answer is still being written: shows the trailing cursor, hides «Озвучить». */
    isStreaming: Boolean = false
) {
    val isUser = message.role == "user"
    // D9/D2: the user bubble uses the dedicated `--bubble-user-bg`/`--bubble-user-fg` pair (a calm
    // dark green in the dark theme, primary/onPrimary in the light one); the assistant bubble is
    // the neutral surface variant with `onSurfaceVariant` text.
    val bubbleColors = LocalBubbleColors.current
    val bubbleColor = if (isUser) {
        bubbleColors.userBackground
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    // Активная тема определяется по яркости `surface` (D4: палитра фиксированная) — тот же
    // приём, что в `rememberAccentColors` (`AccentPanel`). Текст ассистента — фиксированные
    // высококонтрастные чернила (явный выбор пользователя); текст пользователя не меняем.
    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val textColor = if (isUser) {
        bubbleColors.userForeground
    } else if (darkTheme) {
        AssistantBubbleTextDark
    } else {
        AssistantBubbleTextLight
    }
    // One reduced «tail» corner on the sender side (bottom-end for the user, bottom-start for the
    // assistant); the other three corners share the generous [BubbleCorner] radius.
    val bubbleShape = if (isUser) {
        RoundedCornerShape(
            topStart = BubbleCorner,
            topEnd = BubbleCorner,
            bottomEnd = BubbleTailCorner,
            bottomStart = BubbleCorner
        )
    } else {
        RoundedCornerShape(
            topStart = BubbleCorner,
            topEnd = BubbleCorner,
            bottomEnd = BubbleCorner,
            bottomStart = BubbleTailCorner
        )
    }
    // Inline wall-clock time inside the bubble; recomputed only if the message itself changes.
    val timeText = remember(message.createdAt) { formatBubbleTime(message.createdAt) }

    // User messages persist their attachments as a `[Вложения: …]` text block (see
    // `AttachmentPolicy.buildUserText`). Split it back out so the bubble shows the user's own text
    // plus inline image/file previews instead of the raw technical block. Assistant messages are
    // left untouched (`parsedUserMessage` is null, so `bubbleText` is the message content).
    val parsedUserMessage = if (isUser) {
        remember(displayedContent) { AttachmentPolicy.parseUserText(displayedContent) }
    } else {
        null
    }
    val bubbleText = parsedUserMessage?.text ?: displayedContent

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(BubbleMaxWidthFraction)
                .background(color = bubbleColor, shape = bubbleShape)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (bubbleText.isNotBlank()) {
                    // Re-parsing the whole Markdown on every recomposition (i.e. per streamed
                    // token) is the expensive part, so remember the last rendered source and only
                    // re-render when it actually changed. `AndroidView.update` still runs every
                    // recomposition.
                    val rendered = remember { MarkdownRenderState() }
                    AndroidView(
                        factory = { ctx ->
                            TextView(ctx).apply {
                                setTextColor(textColor.toArgb())
                                // 16sp body (Telegram-like): the previous 14sp read a touch small
                                // in the transcript. `TextView.setTextSize(Float)` interprets the
                                // value as scaled pixels (sp) —
                                // https://developer.android.com/reference/android/widget/TextView#setTextSize(float)
                                setTextSize(16f)
                                // Long-press text selection so the user can highlight part of a
                                // reply and use the system «Копировать» action —
                                // https://developer.android.com/reference/android/widget/TextView#setTextIsSelectable(boolean)
                                // ORDER MATTERS: `setTextIsSelectable(true)` installs
                                // `ArrowKeyMovementMethod` (see the TextView source), which would
                                // drop link taps, so `LinkMovementMethod` is re-applied AFTER it.
                                // That keeps both working: `LinkMovementMethod` consumes only
                                // touches that land on a `ClickableSpan` (Markwon renders every
                                // Markdown/auto-linked URL as one) and returns false for every
                                // other touch, so the TextView editor still runs its long-press /
                                // drag selection for the rest of the text —
                                // https://developer.android.com/reference/android/text/method/LinkMovementMethod
                                // https://developer.android.com/reference/android/text/style/ClickableSpan
                                setTextIsSelectable(true)
                                movementMethod = LinkMovementMethod.getInstance()
                                gravity = if (isUser) Gravity.END else Gravity.START
                            }
                        },
                        update = { tv ->
                            // Тему переключают прямо в топ-баре чата, не покидая экран: цвет
                            // переустанавливаем на каждом проходе, иначе TextView сохранит
                            // значение, заданное в factory. setTextColor —
                            // https://developer.android.com/reference/android/widget/TextView#setTextColor(int)
                            tv.setTextColor(textColor.toArgb())
                            if (rendered.content != bubbleText) {
                                rendered.content = bubbleText
                                markwon.setMarkdown(tv, bubbleText)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (parsedUserMessage != null && parsedUserMessage.attachments.isNotEmpty()) {
                    UserAttachmentPreviews(
                        attachments = parsedUserMessage.attachments,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (isStreaming) {
                    // Subtle typewriter cursor trailing the revealed text; the design tints it with
                    // the running accent (primary) and monospaces it so its width never shifts.
                    Text(
                        text = StreamingReveal.CURSOR,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace
                    )
                }
                // Bottom meta row: the inline HH:mm timestamp always sits at the bubble's end
                // (bottom-right), Telegram-style. The assistant-only «Озвучить» (TTS) and
                // «Скопировать ответ» actions are compact muted controls at the start of this same
                // row, so they stay out of the timestamp's way instead of owning a full line.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // «Озвучить» and «Скопировать ответ» are hidden while streaming: the text is
                    // still changing, so there is no finished reply to speak or copy yet.
                    if (!isUser && !isStreaming && displayedContent.isNotBlank()) {
                        // `Modifier.semantics { }` is not a composable lambda, so the localized
                        // description must be resolved here and captured by the semantics block.
                        val speakDescription = stringResource(R.string.chat_speak_message_cd)
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .semantics { contentDescription = speakDescription }
                                .clickable { onSpeak(displayedContent) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_volume_up),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        CopyReplyAction(
                            text = displayedContent,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.labelSmall,
                        // Muted variant of the bubble's own foreground, so the timestamp stays
                        // readable on both the accent user bubble and the neutral assistant one
                        // without introducing a hardcoded colour.
                        color = textColor.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

/**
 * Tiny non-observable holder for the last Markdown source rendered into a bubble's [TextView]; it
 * must not trigger recomposition, so it is a plain class rather than `mutableStateOf`.
 */
private class MarkdownRenderState {
    var content: String? = null
}

/**
 * Assistant-only «Скопировать ответ» control: a small copy glyph followed by a visible label, styled
 * with the same 16dp glyph / muted tint family as the neighbouring «Озвучить» action, so the two read
 * as one meta-control row inside the bubble.
 *
 * Tapping it copies the finished reply [text] to the system clipboard and flips the label (and glyph)
 * to the transient «Скопировано» confirmation for ~1.5 s, then restores it. The clipboard is written
 * through Compose's `LocalClipboardManager`, which wraps the platform
 * `android.content.ClipboardManager`; `setText` takes an `AnnotatedString` —
 * https://developer.android.com/reference/kotlin/androidx/compose/ui/platform/ClipboardManager
 * https://developer.android.com/reference/kotlin/androidx/compose/ui/text/AnnotatedString
 * `LaunchedEffect` + `kotlinx.coroutines.delay` drive the timed reset (keyed on [copied], so every
 * fresh copy restarts the window and leaving the screen cancels it) —
 * https://developer.android.com/develop/ui/compose/side-effects#launchedeffect
 * https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/delay.html
 */
@Composable
private fun CopyReplyAction(
    text: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    // Auto-reset the confirmation label after a short window.
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    val label = stringResource(
        if (copied) R.string.chat_copy_reply_copied else R.string.chat_copy_reply
    )
    Row(
        modifier = modifier
            // One merged accessibility node: the visible label is the button's name (no duplicate
            // announcement of the inner Text) —
            // https://developer.android.com/develop/ui/compose/accessibility/semantics
            .semantics(mergeDescendants = true) { contentDescription = label }
            .clickable {
                clipboard.setText(AnnotatedString(text))
                copied = true
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (copied) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_content_copy),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint
        )
    }
}
