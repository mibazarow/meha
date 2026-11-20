package com.andharness.ru.domain.documents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure `{{key}}` placeholder substitution for `pdf_from_template` (and the DOCX template path).
 *
 * Both spellings `{{key}}` and `{{ key }}` are accepted (surrounding whitespace is not part of the
 * key). A placeholder whose key has no value is **left verbatim** and reported in [Result.missing] —
 * the renderer never silently drops or blanks unknown placeholders, so the caller can warn honestly.
 *
 * Pure Kotlin / kotlinx.serialization: no Android or filesystem access, so it is unit-tested on the
 * JVM.
 *
 * References:
 * - kotlinx.serialization `JsonElement`/`JsonObject` —
 *   https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/
 */
object TemplateRenderer {

    /** First-seen order of every `{{ … }}` placeholder. */
    private val PLACEHOLDER = Regex("""\{\{\s*([^{}\n]+?)\s*\}\}""")

    /** Outcome of [render]: the substituted text plus an honest list of unresolved placeholders. */
    data class Result(
        val text: String,
        /** Keys that had no value and were therefore left verbatim, in first-seen order. */
        val missing: List<String>,
        /** Keys that were substituted, in first-seen order. */
        val applied: List<String>,
    )

    /**
     * Replaces every `{{key}}`/`{{ key }}` in [template] with `values[key]`. Unknown keys are left
     * as-is and collected into [Result.missing]; values are inserted literally (no `$`/regex
     * interpretation).
     */
    fun render(template: String, values: Map<String, String>): Result {
        val missing = LinkedHashSet<String>()
        val applied = LinkedHashSet<String>()
        val text = PLACEHOLDER.replace(template) { match ->
            val key = match.groupValues[1]
            val value = values[key]
            if (value != null) {
                applied += key
                value
            } else {
                missing += key
                match.value
            }
        }
        return Result(text, missing.toList(), applied.toList())
    }

    /**
     * Parses a JSON object of `key -> value`; every value is stringified with [stringify] so numeric,
     * boolean, array and object values all become sensible text. Throws [IllegalArgumentException]
     * when [json] is not a JSON object.
     */
    fun parseValues(json: String): Map<String, String> {
        val element = try {
            Json.parseToJsonElement(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("некорректный JSON (${e.message}).")
        }
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException(
                "ожидался JSON-объект вида {\"ключ\": \"значение\"}."
            )
        return obj.entries.associate { (key, value) -> key to stringify(value) }
    }

    /**
     * Parses a JSON object of replacements. When [templateStyle] is true the keys are wrapped as
     * `{{key}}` placeholders (so the object matches the `pdf_from_template` `values` shape); otherwise
     * the keys are used literally. Values are stringified with [stringify].
     */
    fun parseReplacements(json: String, templateStyle: Boolean): Map<String, String> {
        val base = parseValues(json)
        return if (templateStyle) base.mapKeys { (key, _) -> "{{$key}}" } else base
    }

    /**
     * Renders one JSON value as text: strings and primitives verbatim, `null` as the empty string,
     * arrays as comma-separated items and objects as `key=value; …`, both recursively.
     */
    fun stringify(element: JsonElement): String = when (element) {
        is JsonNull -> ""
        is JsonPrimitive -> element.content
        is JsonArray -> element.joinToString(", ") { stringify(it) }
        is JsonObject -> element.entries.joinToString("; ") { (key, value) -> "$key=${stringify(value)}" }
    }
}
