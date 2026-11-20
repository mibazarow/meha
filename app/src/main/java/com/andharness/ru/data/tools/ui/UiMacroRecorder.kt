package com.andharness.ru.data.tools.ui

import kotlinx.serialization.json.JsonObject

/**
 * Bounded, process-wide UI-macro recorder owned by [UiAutomationToolsHandler]. While active, each
 * recorded action handler calls [record] with the tool name and the raw argument JSON it received;
 * the handler never records the `ui_macro_*` tools themselves.
 *
 * Recording is OFF by default and the buffer is capped at [UiMacroCodec.MAX_STEPS] steps. Once the
 * cap is reached further actions are ignored and [Snapshot.overflow] is set so `ui_macro_stop` can
 * report the truncation honestly. All access is synchronized (record/start/stop arrive from the
 * Main dispatcher today, but the lock keeps the state safe if that ever changes).
 */
class UiMacroRecorder {

    /** The state handed over by [stop]: the recorded name, its steps and whether the cap was hit. */
    data class Snapshot(
        val name: String,
        val steps: List<UiMacroStep>,
        val overflow: Boolean,
    )

    private val lock = Any()
    private var active = false
    private var name: String? = null
    private var steps = ArrayList<UiMacroStep>()
    private var overflow = false

    fun isRecording(): Boolean = synchronized(lock) { active }

    /** Starts a fresh recording, discarding any previous in-progress one. */
    fun start(name: String) = synchronized(lock) {
        this.name = name
        steps = ArrayList()
        overflow = false
        active = true
    }

    fun record(tool: String, arguments: JsonObject) {
        synchronized(lock) {
            if (!active) return
            if (steps.size >= UiMacroCodec.MAX_STEPS) {
                overflow = true
                return
            }
            steps += UiMacroStep(tool, arguments.toString())
        }
    }

    /** Stops recording; returns the snapshot, or null when no recording was active. */
    fun stop(): Snapshot? = synchronized(lock) {
        if (!active) return null
        active = false
        Snapshot(name.orEmpty(), steps.toList(), overflow)
    }
}
