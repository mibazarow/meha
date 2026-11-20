package com.andharness.ru.domain.formatting

/**
 * Shared, pure whitespace normalization for the domain/data layers.
 *
 * Before this existed the same `private val WHITESPACE = Regex("\\s+")` and the same
 * collapse-then-trim expression were re-declared in ~20 files. [collapseWhitespace] is now the ONE
 * place that owns that rule, and it is deliberately identical to
 * `Regex("\\s+").replace(text, " ").trim()` so every migrated call site keeps its exact output.
 *
 * Pure Kotlin (no Android imports), so it is unit-testable on the JVM.
 *
 * References:
 * - `Regex` / `Regex.replace(input, replacement)` —
 *   https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/-regex/
 * - `String.replace(regex, replacement)` —
 *   https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/replace.html
 * - `String.trim()` (removes leading/trailing chars where `Char.isWhitespace()`) —
 *   https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/trim.html
 *
 * [WHITESPACE] is public so call sites that need to **split** on whitespace (rather than collapse
 * it) reuse exactly the same pattern instead of re-declaring it.
 */
val WHITESPACE = Regex("\\s+")

/**
 * Collapses every run of whitespace (`\s+`) to a single space and trims both ends. A blank input
 * stays blank. Exactly equivalent to `WHITESPACE.replace(text, " ").trim()`.
 */
fun collapseWhitespace(text: String): String = WHITESPACE.replace(text, " ").trim()
