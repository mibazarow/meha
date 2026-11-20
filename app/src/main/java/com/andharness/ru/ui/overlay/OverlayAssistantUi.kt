package com.andharness.ru.ui.overlay

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.andharness.ru.R
import com.andharness.ru.domain.assist.overlay.OverlayQuickAsk
import com.andharness.ru.domain.assist.overlay.OverlayQuickAskState
import com.andharness.ru.ui.components.PrimaryGradientButton
import com.andharness.ru.ui.theme.BrandGold
import com.andharness.ru.ui.theme.BrandGoldBright
import com.andharness.ru.ui.theme.BrandGoldDeep
import com.andharness.ru.ui.theme.BrandGreen
import com.andharness.ru.ui.theme.BrandGreenBright
import com.andharness.ru.ui.theme.BrandGreenDeep
import com.andharness.ru.ui.theme.OnBrandGold
import com.andharness.ru.ui.theme.OnPrimaryGradient

// Allocation strategy: the bubble stops are fixed brand tokens from `ui/theme/Color.kt` (no theme
// or state inputs), so each gradient brush is allocated once at file scope instead of on every
// recomposition; `Brush` instances are immutable and safe to share.
// https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/Brush#linearGradient(kotlin.Pair(kotlin.Float,androidx.compose.ui.graphics.Color),kotlin.Array((kotlin.Pair(kotlin.Float,androidx.compose.ui.graphics.Color))))
private val GreenBubbleBrush = Brush.linearGradient(
    0f to BrandGreenBright,
    0.55f to BrandGreen,
    1f to BrandGreenDeep
)

private val GoldBubbleBrush = Brush.linearGradient(
    0f to BrandGoldBright,
    0.55f to BrandGold,
    1f to BrandGoldDeep
)

/**
 * The floating bubble itself: a 56 dp brand-green circle that is dragged with
 * [detectDragGestures] and tapped with [combinedClickable].
 *
 * The drag modifier is deliberately placed BEFORE the click modifier: `detectDragGestures` only
 * consumes the pointer once the touch slop has been passed, so a plain tap still reaches the click
 * handler while a real drag moves the window (see `OverlayAssistantService.moveBubble`).
 *
 * @param onTap opens the quick-ask panel.
 * @param onLongPress closes the bubble (the honest "get rid of it" affordance).
 * @param onDrag reports the raw drag delta in pixels; the service clamps and applies it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OverlayBubble(
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bubbleDescription = stringResource(R.string.overlay_bubble_cd)
    // Theme signal: gold bubble in the light theme, green in the dark — resolved `surface`
    // luminance, the same pattern as `AccentPanel`.
    // https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/package-summary#(androidx.compose.ui.graphics.Color).luminance()
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val bubbleGradient = if (dark) GreenBubbleBrush else GoldBubbleBrush
    Box(
        modifier = modifier
            .size(56.dp)
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .background(bubbleGradient)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .semantics { contentDescription = bubbleDescription },
        contentAlignment = Alignment.Center
    ) {
        // Text glyph instead of an icon vector: the bubble has to render outside the app's own
        // window, and a letter needs no extra icon dependency while matching the brand colour pair.
        Text(
            text = stringResource(R.string.overlay_bubble_glyph),
            color = if (dark) OnPrimaryGradient else OnBrandGold,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * The in-overlay quick-ask panel: one text field and «Отправить».
 *
 * It is a separate, focusable overlay window (the bubble's window is `FLAG_NOT_FOCUSABLE`), which
 * is what lets the soft keyboard attach to it. Sending hands the text to the existing agent loop
 * (see `OverlayAssistantService.submit`) and closes the panel. The microphone entry point was
 * removed in redesign §2 phase 5.
 *
 * @param onSubmit sends the current draft (with the captured screen context) to the agent.
 */
@Composable
fun OverlayQuickAskPanel(
    state: OverlayQuickAskState,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.overlay_quick_ask_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.overlay_quick_ask_close_cd)
                    )
                }
            }
            OutlinedTextField(
                value = state.input,
                onValueChange = onInputChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                label = { Text(stringResource(R.string.overlay_quick_ask_label)) },
                minLines = 2,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSubmit() })
            )
            if (state.error != null) {
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            PrimaryGradientButton(
                text = if (state.isSending) {
                    stringResource(R.string.overlay_quick_ask_sending)
                } else {
                    stringResource(R.string.overlay_quick_ask_send)
                },
                onClick = onSubmit,
                enabled = OverlayQuickAsk.canSubmit(state),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
