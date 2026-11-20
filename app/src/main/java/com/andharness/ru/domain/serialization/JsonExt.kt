package com.andharness.ru.domain.serialization

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Canonical, dependency-free readers for tool / recorded-skill arguments held in a [JsonObject].
 *
 * Lives in the domain layer (pure Kotlin + kotlinx.serialization only, no `data` dependency) so the
 * domain and the data layer can share ONE implementation instead of each carrying its own `str`/`arg`
 * copy. Behavior is preserved exactly: [JsonArgs.str] returns `null` for a missing key OR blank
 * content, while [JsonArgs.raw] exposes the un-filtered content (blank allowed) for callers that
 * treat blank and absent differently.
 *
 * References:
 * - `JsonObject` accessor + `JsonPrimitive` —
 *   https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/kotlinx.serialization.json/-json-object/
 * - `String.isNotBlank()` — https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/is-not-blank.html
 */
object JsonArgs {

    /** Raw textual content of [key], or `null` when it is absent or not a JSON primitive (may be blank). */
    fun raw(obj: JsonObject, key: String): String? = (obj[key] as? JsonPrimitive)?.content

    /** Nullable string value of [key]: the raw content, or `null` when absent or blank. */
    fun str(obj: JsonObject, key: String): String? = raw(obj, key)?.takeIf { it.isNotBlank() }
}

/** Alias of [JsonArgs.str] for recorded-skill arguments (`SkillRecordingConverter`). */
fun JsonObject.arg(key: String): String? = JsonArgs.str(this, key)
