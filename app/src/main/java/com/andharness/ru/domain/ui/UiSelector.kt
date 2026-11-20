package com.andharness.ru.domain.ui


/**
 * Semantic selector for a screen node. All non-blank criteria must match (logical AND); a blank or
 * `null` field is ignored, so an empty selector matches nothing.
 *
 * Matching is case-insensitive everywhere. `text` / [textContains] match the node's *label* — its
 * visible `text`, or its `contentDescription` when there is no text — because `ui_tree`/`ui_find`
 * print whatever label the node actually has, and a replayed step must find it either way. An
 * explicit `contentDescription` criterion restricts matching to the description only; `className`
 * prefers an exact match but also accepts a substring; `resourceId` accepts either the short id or a
 * fully-qualified one (everything before the last `/` is dropped). [index] is a 1-based ordinal
 * applied to the ranked matches, matching the numbering `ui_find` already prints; `null` means "the
 * best-ranked match".
 */
data class UiSelector(
    val text: String? = null,
    val textContains: String? = null,
    val resourceId: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val index: Int? = null,
) {
    /** True when no matching criterion is present (an index alone selects nothing). */
    val isEmpty: Boolean
        get() = text.isNullOrBlank() && textContains.isNullOrBlank() && resourceId.isNullOrBlank() &&
            contentDescription.isNullOrBlank() && className.isNullOrBlank()

    /** Human-readable criteria list for honest "tried X, Y" error messages. */
    val description: String
        get() = listOfNotNull(
            text?.takeIf { it.isNotBlank() }?.let { "text=«$it»" },
            textContains?.takeIf { it.isNotBlank() }?.let { "textContains=«$it»" },
            resourceId?.takeIf { it.isNotBlank() }?.let { "resourceId=«$it»" },
            contentDescription?.takeIf { it.isNotBlank() }?.let { "contentDescription=«$it»" },
            className?.takeIf { it.isNotBlank() }?.let { "className=«$it»" },
            index?.let { "index=$it" },
        ).joinToString(", ")
}
