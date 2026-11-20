package com.andharness.ru.data.osint

/**
 * Locally derivable description of a phone number (update.md §2.6 point 1).
 *
 * Only the **prefix → region/country** heuristic is reported, and the line type is reported as
 * "not derivable" because a normal app cannot query the carrier's line type for an arbitrary remote
 * number without extra data (no `libphonenumber`, no carrier API, no key). Nothing is invented:
 * every field that cannot be derived says so explicitly.
 *
 * References:
 * - `PhoneNumberUtils`-style formatting was avoided to keep this JVM-pure and dependency-free; the
 *   heuristic prefix table lives in [NumberNormalizer].
 */
object NumberProfile {

    /** A short, honest, human-readable profile line for [normalized] (must already be normalized). */
    fun describe(normalized: String): String {
        val region = NumberNormalizer.regionFor(normalized)
        val regionText = region ?: "неизвестный префикс (страна не определена)"
        return buildString {
            append("страна/регион (эвристика по префиксу): $regionText")
            append("; тип линии: не выводимо без данных оператора")
        }
    }
}
