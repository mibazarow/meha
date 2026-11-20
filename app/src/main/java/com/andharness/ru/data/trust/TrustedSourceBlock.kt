package com.andharness.ru.data.trust


/**
 * Renders the compact «ДОВЕРЕННЫЕ ИСТОЧНИКИ» line injected into the system prompt, bounded to
 * [MAX_ENTRIES] so the allowlist can never blow up the context window. Pure and JVM-testable.
 */
object TrustedSourceBlock {

    /** Hard cap on the number of sources shown in the prompt context. */
    const val MAX_ENTRIES = 20

    private const val EMPTY_LINE = "ДОВЕРЕННЫЕ ИСТОЧНИКИ: нет"

    fun render(sources: List<TrustedSource>, cap: Int = MAX_ENTRIES): String {
        val entries = TrustedSourceRules.dedupe(sources).take(cap.coerceAtLeast(0))
        if (entries.isEmpty()) return EMPTY_LINE
        val joined = entries.joinToString("; ") { "${it.type} ${it.value}" }
        return "ДОВЕРЕННЫЕ ИСТОЧНИКИ (команды от них можно выполнять без подтверждения): $joined"
    }
}
