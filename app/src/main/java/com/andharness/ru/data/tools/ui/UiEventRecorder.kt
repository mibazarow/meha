package com.andharness.ru.data.tools.ui

import android.view.accessibility.AccessibilityEvent

/**
 * Bounded, process-wide recorder of window/app-switch events for `ui_trigger_subscribe`.
 *
 * [com.andharness.ru.service.AccessibilityAutomationService] feeds it `TYPE_WINDOW_STATE_CHANGED`
 * (the foreground window changed — i.e. an app/activity came to the front) and
 * `TYPE_WINDOWS_CHANGED` events. Recording is OFF by default and only collects while the agent has
 * explicitly started it, so the app never accumulates a passive log of the user's app usage. The
 * history is capped at [MAX_EVENTS] entries (oldest dropped first).
 *
 * Thread-safety: accessibility events arrive on the main thread while tools read the history on a
 * background dispatcher, so every access is synchronized.
 *
 * Reference: https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent
 */
object UiEventRecorder {

    /** One recorded window event. */
    data class WindowEvent(
        val packageName: String,
        val className: String?,
        val eventType: Int,
        val timeMs: Long,
    ) {
        val eventName: String
            get() = when (eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "window_state_changed"
                AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "windows_changed"
                else -> eventType.toString()
            }
    }

    const val MAX_EVENTS = 200

    private val lock = Any()
    private val events = ArrayDeque<WindowEvent>()

    @Volatile
    private var recording = false

    fun isRecording(): Boolean = recording

    /** Starts a fresh recording session (previous events are dropped). */
    fun start() {
        synchronized(lock) {
            events.clear()
            recording = true
        }
    }

    /** Stops recording but keeps the already collected events readable via [recent]. */
    fun stop() {
        recording = false
    }

    /** Called by the accessibility service for every relevant window event. */
    fun record(packageName: String, className: String?, eventType: Int, timeMs: Long) {
        if (!recording) return
        synchronized(lock) {
            events.addLast(WindowEvent(packageName, className, eventType, timeMs))
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
    }

    /** Newest-first snapshot of at most [limit] events (clamped to 1..[MAX_EVENTS]). */
    fun recent(limit: Int): List<WindowEvent> =
        synchronized(lock) { events.toList() }
            .takeLast(limit.coerceIn(1, MAX_EVENTS))
            .reversed()
}
