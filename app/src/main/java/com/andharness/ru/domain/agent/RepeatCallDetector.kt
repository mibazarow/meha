package com.andharness.ru.domain.agent

/**
 * Detects a call that the model repeats verbatim after it already produced the exact same result
 * (TOOLS_TASKS §3).
 *
 * `ToolErrorHints` appends one actionable hint per failed call, which is right the first time: the
 * hint is a signal to try differently. When the model repeats the SAME tool with the SAME arguments
 * and gets the SAME output, a second copy of the hint reads like a fresh success and just occupies
 * context — the model already had that advice and chose to ignore it.
 *
 * This is not a blocker: legitimate repetition whose RESULT changes (blink on↔off, a counter, a
 * retry-until-condition loop) is progress and is never flagged, because the output differs. The
 * caller only replaces the long output+hint with a short honest notice.
 *
 * Pure and Android-free. One instance per run.
 */
class RepeatCallDetector {

    /** Last output seen for `tool + arguments`, keyed by that exact pair. */
    private val lastOutputByCall = HashMap<String, String>()

    /**
     * Records one completed call and reports whether it was an exact repeat.
     *
     * @return `true` when the same tool with the same arguments already returned exactly [output] in
     *    this run. `false` on the first occurrence and whenever the output changed.
     */
    fun isExactRepeat(tool: String, arguments: String, output: String): Boolean {
        val key = tool + KEY_SEPARATOR + arguments
        val previous = lastOutputByCall.put(key, output)
        return previous == output
    }

    /** Forgets everything (a new turn starts). */
    fun reset() = lastOutputByCall.clear()

    private companion object {
        /** Cannot occur in a tool name, so `tool + args` keys never collide across pairs. */
        const val KEY_SEPARATOR = '\u0000'
    }
}
