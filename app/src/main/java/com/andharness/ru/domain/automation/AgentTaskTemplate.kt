package com.andharness.ru.domain.automation

/**
 * Pure renderer for the `agent_task` action's prompt TEMPLATE (update.md §11.6, backlog «Событийные
 * задачи агента»).
 *
 * The `agent_task` action runs an agent turn just like `start_chat`, but its `action_config` is a
 * template that is filled from the [AutomationEvent] that fired the rule, so the prompt can react to
 * WHAT happened (e.g. «Пришло уведомление: {payload} — разберись») instead of a fixed string.
 *
 * Supported (literal, case-sensitive) placeholders:
 * - `{trigger}` — [AutomationEvent.triggerType], the canonical trigger name (`notification`, `time`…);
 * - `{value}` — [AutomationEvent.value] (calendar title, BLE address…);
 * - `{payload}` — [AutomationEvent.payload] (free-form event payload);
 * - `{name}` — the rule name ([AutomationRule.name]) handed in by the engine.
 *
 * A known placeholder whose event field is absent or blank renders as an EMPTY string, so a prompt
 * never leaks a literal `{payload}` when the trigger carried none. Unknown placeholders are left
 * exactly as written (their text may be intentional for the model), which also keeps the renderer
 * forward-compatible with placeholders a later task may add.
 *
 * Android-free so it can be unit-tested on the JVM. Kotlin literal substring replacement:
 * https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/replace.html — `String.replace(old, new)`
 * replaces literal occurrences (not a regex), so `$` / `\` inside event values are safe.
 */
object AgentTaskTemplate {

    const val PLACEHOLDER_TRIGGER = "{trigger}"
    const val PLACEHOLDER_VALUE = "{value}"
    const val PLACEHOLDER_PAYLOAD = "{payload}"
    const val PLACEHOLDER_NAME = "{name}"

    /**
     * Fills [template] using [event] and [ruleName]. Returns the template unchanged when it is empty
     * (the engine already validates that an `agent_task` config is non-blank).
     */
    fun render(template: String, event: AutomationEvent, ruleName: String): String {
        if (template.isEmpty()) return template
        return template
            .replace(PLACEHOLDER_TRIGGER, event.triggerType)
            .replace(PLACEHOLDER_VALUE, event.value.renderedOrEmpty())
            .replace(PLACEHOLDER_PAYLOAD, event.payload.renderedOrEmpty())
            .replace(PLACEHOLDER_NAME, ruleName)
    }

    /** Absent or blank event fields render as an empty string, never as a leaked literal placeholder. */
    private fun String?.renderedOrEmpty(): String = if (isNullOrBlank()) "" else this
}
