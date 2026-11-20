package com.andharness.ru.domain.skill

/**
 * Pure `{parameter}` substitution for skill step templates (`textTemplate`, `urlTemplate`, the
 * `expected` text of an assertion). Android-free and side-effect free, so it is fully JVM-tested.
 *
 * Placeholders use the same syntax as the SPEC examples (`{адрес}`, `{сумма}`): an opening brace, one
 * or more letters/digits/underscore, a closing brace. Unicode letters are intentionally allowed so
 * Cyrillic parameter names work. A literal brace is not escapable today; documented, not needed yet.
 */
object SkillTemplate {

    /**
     * Placeholder grammar: `{name}` where name is `[A-Za-z0-9_]+` or a run of non-brace characters.
     *
     * The braces are matched with one-character classes (`[{]`/`[}]`) instead of the escapes
     * `\{`/`\}` on purpose: Android's `java.util.regex.Pattern` rejects an escaped brace with a
     * `PatternSyntaxException`, while the JDK's Pattern accepts it — so `\{...}` works in JVM unit
     * tests but throws `ExceptionInInitializerError` (via this object's `<clinit>`) on device. A brace
     * inside a character class is a plain literal on both engines.
     */
    private val PLACEHOLDER = Regex("[{]([^{}]+)[}]")

    /** Distinct placeholder names referenced by [template], in first-appearance order. */
    fun placeholders(template: String): List<String> =
        PLACEHOLDER.findAll(template).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.distinct().toList()

    /** Names referenced by [template] but absent from [values] (honest list, not a message). */
    fun missingParameters(template: String, values: Map<String, String>): List<String> =
        placeholders(template).filterNot { values.containsKey(it) }

    /**
     * Renders [template] by replacing every `{name}` with `values[name]`.
     *
     * Returns `null` when a referenced parameter is missing — an honest failure instead of silently
     * emitting an empty string, so a later engine step can report exactly which parameter was not
     * supplied (see [missingParameters]). A value that is present but empty is substituted as-is
     * (the caller decides whether that is acceptable).
     */
    fun renderTemplate(template: String, values: Map<String, String>): String? {
        if (missingParameters(template, values).isNotEmpty()) return null
        return PLACEHOLDER.replace(template) { match -> values[match.groupValues[1].trim()].orEmpty() }
    }

    /** Renders [template] with [values], or returns [fallback] when a parameter is missing. */
    fun renderTemplate(template: String, values: Map<String, String>, fallback: String): String =
        renderTemplate(template, values) ?: fallback

    /**
     * True when [values] supply every placeholder referenced by [template]. Used by the save tool to
     * warn (not reject) when defaults cannot satisfy a step.
     */
    fun isSatisfied(template: String, values: Map<String, String>): Boolean =
        missingParameters(template, values).isEmpty()
}
