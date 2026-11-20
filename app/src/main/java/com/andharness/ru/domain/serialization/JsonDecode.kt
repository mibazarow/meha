package com.andharness.ru.domain.serialization

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Shared tolerant decode used by the many `decode(raw: String?)` codecs in the app.
 *
 * Returns `null` when [raw] is `null` or when the JSON is corrupt/foreign (any [Exception] thrown
 * by decoding is swallowed), so each codec keeps its own non-null fallback at the call site:
 *
 * ```
 * fun decode(raw: String?): List<Foo> =
 *     json.decodeOrNull<List<Foo>>(raw)?.let { sanitize(it) } ?: emptyList()
 * ```
 *
 * The `Json` receiver is preserved so every codec keeps using its own configuration from [AppJson]
 * (no serialized-format change), and `reified T` lets kotlinx.serialization resolve the serializer
 * for the concrete type at the call site.
 *
 * References:
 * - `decodeFromString` (reified, on `StringFormat`):
 *   https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-core/kotlinx.serialization/decode-from-string.html
 * - Reified/inline type parameters:
 *   https://kotlinlang.org/docs/inline-functions.html#reified-type-parameters
 */
inline fun <reified T> Json.decodeOrNull(raw: String?): T? = try {
    raw?.let { decodeFromString<T>(it) }
} catch (_: Exception) {
    null
}
