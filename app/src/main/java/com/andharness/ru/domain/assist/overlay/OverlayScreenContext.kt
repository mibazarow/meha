package com.andharness.ru.domain.assist.overlay

import com.andharness.ru.domain.formatting.collapseWhitespace

/**
 * Formats a [ScreenContext] into a compact Russian block and composes the seeded chat message.
 *
 * Honest limits: an empty context yields `null` from [format] (the caller omits it), and the block
 * length is bounded by [MAX_BLOCK_CHARS] with an explicit "сокращено" marker — the agent is never
 * handed silently truncated or fabricated screen content.
 *
 * Official references:
 * - `AccessibilityService.getRootInActiveWindow` (needs `canRetrieveWindowContent`) —
 *   https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#getRootInActiveWindow()
 * - `AccessibilityNodeInfo.getPackageName` / `getText` / `getContentDescription` —
 *   https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo
 */
object OverlayScreenContext {

    /** Hard cap on reported labels, so a huge screen cannot blow up the message. */
    const val MAX_LABELS = 12

    /** Per-label character cap (matching `AccessibilityTree.format`). */
    const val MAX_LABEL_CHARS = 80

    /** Hard cap on the whole context block, including its header. */
    const val MAX_BLOCK_CHARS = 900

    /** Header of the block; also used by the honest "unavailable" notes. */
    const val HEADER = "=== Контекст текущего экрана ==="

    /**
     * Honest note used when the accessibility service is off. Requirement: no context is guessed —
     * the agent is told that the screen was simply not observable.
     */
    const val UNAVAILABLE_ACCESSIBILITY =
        "$HEADER\nНедоступен: служба спецвозможностей выключена."

    /** Honest note used when the service is on but the active screen produced no readable nodes. */
    const val UNAVAILABLE_UNREADABLE =
        "$HEADER\nНедоступен: экран не удалось прочитать."

    /**
     * Renders the block, or `null` when there is nothing to report (empty context ⇒ omitted).
     */
    fun format(context: ScreenContext): String? {
        if (context.isEmpty) return null
        val labels = context.labels
            .map { collapseWhitespace(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_LABELS)
        val body = buildString {
            appendLine(HEADER)
            context.packageName?.takeIf { it.isNotBlank() }?.let { appendLine("Приложение: $it") }
            context.activityName?.takeIf { it.isNotBlank() }?.let { appendLine("Активность: $it") }
            if (labels.isEmpty()) {
                appendLine("Видимые элементы: не прочитаны.")
            } else {
                appendLine("Видимые элементы:")
                for (label in labels) {
                    append("• ").appendLine(label.take(MAX_LABEL_CHARS))
                }
            }
        }.trimEnd()
        return truncate(body, MAX_BLOCK_CHARS)
    }

    /**
     * Builds the exact text parked in [com.andharness.ru.inbound.InboundChatSeed] for the created
     * chat: the user's question followed by the (already formatted) context block or one of the
     * honest "unavailable" notes. A blank/absent block leaves the message untouched — this is the
     * "empty ⇒ omitted" path.
     */
    fun seedMessage(userText: String, contextBlock: String?): String {
        val question = userText.trim()
        if (contextBlock.isNullOrBlank()) return question
        return "$question\n\n${contextBlock.trim()}"
    }

    /** Bounded truncation that states how much was cut instead of dropping content silently. */
    private fun truncate(text: String, maxChars: Int): String {
        if (maxChars <= 0 || text.length <= maxChars) return text
        val cut = text.length - maxChars
        return text.take(maxChars) + "\n… (контекст сокращён на $cut симв.)"
    }
}
