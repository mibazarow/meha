package com.andharness.ru.ui.chats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlin.math.roundToInt

/**
 * Smooth (typewriter) reveal of the streaming assistant answer.
 *
 * The orchestrator publishes [ChatUiState.streamingText] on a throttle (~120 ms, see
 * `AgentOrchestratorImpl.PERSIST_INTERVAL_MS`), so rendering it straight to screen jumps a whole
 * chunk at once and reads as jerky. Instead the UI buffers the latest target string and reveals the
 * displayed substring at a steady cadence, catching up smoothly when a new chunk lands.
 *
 * The reveal is one [Animatable] driven by a `LaunchedEffect` keyed on the target text (Compose
 * value-based animation):
 * - https://developer.android.com/develop/ui/compose/animation/value-based
 * - https://developer.android.com/reference/kotlin/androidx/compose/animation/core/Animatable
 *
 * This object holds only the pure, unit-tested cadence math (no Compose, no Android).
 */
internal object StreamingReveal {

    /** Trailing cursor shown while the answer is still being written (subtle, optional). */
    const val CURSOR: String = "▌"

    /**
     * Steady reveal speed once the model streams comfortably: characters per second. Chosen so the
     * printed text keeps pace with typical token rates without feeling slow.
     */
    const val CHARS_PER_SECOND: Float = 90f

    /** Never schedule a sub-frame reveal step (a single character must still be visible). */
    const val MIN_DURATION_MS: Int = 40

    /**
     * Ceiling for a single catch-up step. A large backlog (slow frames, fast model) is revealed
     * within this window, so the printed text can never lag behind by more than ~[MAX_DURATION_MS].
     */
    const val MAX_DURATION_MS: Int = 700

    /**
     * Duration used to reveal [remainingChars] at the steady [CHARS_PER_SECOND] cadence, clamped to
     * [[MIN_DURATION_MS], [MAX_DURATION_MS]]. `0` for a non-positive backlog (nothing to reveal).
     */
    fun revealDurationMs(remainingChars: Int): Int {
        if (remainingChars <= 0) return 0
        val raw = (remainingChars / CHARS_PER_SECOND * 1000f).roundToInt()
        return raw.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
    }
}

/**
 * Remembers the reveal [Animatable] for [target] and keeps it animating toward the target length.
 * The caller reads `value` (revealed character count) to slice the visible substring. On a shorter
 * target (a new turn / reset) the value snaps back, so each answer prints from its own start.
 */
@Composable
internal fun rememberStreamingReveal(target: String?): Animatable<Float, AnimationVector1D> {
    val revealed = remember { Animatable(0f) }
    LaunchedEffect(target) {
        val length = target?.length ?: 0
        // Shorter text (reset / new turn): snap so the reveal restarts from the beginning.
        if (length.toFloat() < revealed.value) revealed.snapTo(length.toFloat())
        val backlog = length - revealed.value.toInt()
        if (backlog > 0) {
            // Restarting this effect cancels the previous animateTo, but the Animatable keeps its
            // current value — so every chunk resumes from where the cursor already is.
            revealed.animateTo(
                targetValue = length.toFloat(),
                animationSpec = tween(
                    durationMillis = StreamingReveal.revealDurationMs(backlog),
                    easing = LinearEasing
                )
            )
        }
    }
    return revealed
}
