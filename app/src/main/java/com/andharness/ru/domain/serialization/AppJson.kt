package com.andharness.ru.domain.serialization

import kotlinx.serialization.json.Json

/**
 * Shared, immutable [Json] configurations for the whole app.
 *
 * kotlinx.serialization's `Json` instances are thread-safe and cheap to reuse, so every call site
 * that used to build its own `Json { ... }` now points at one of the variants below. This keeps the
 * exact same serialization semantics at each site (no data-format change) while removing the
 * duplicated configuration.
 *
 * Only variants that are actually used live here; a call site with options that do not match any
 * variant keeps its own local instance.
 *
 * References:
 * - Json configurations guide: https://kotlinlang.org/docs/json.html#json-configurations
 * - `JsonBuilder` (all options below): https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/kotlinx.serialization.json/-json-builder/
 */
object AppJson {

    /** Tolerant decode: unknown keys are ignored. The common case for persisted/remote JSON. */
    val Lenient: Json = Json {
        ignoreUnknownKeys = true
    }

    /** [Lenient] plus always encoding default values (round-trips every field). */
    val LenientEncodeDefaults: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** [LenientEncodeDefaults] with pretty printing, for human/agent-readable tool output. */
    val LenientEncodeDefaultsPretty: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** [Lenient] with a relaxed lexer, for loosely-formatted third-party payloads. */
    val LenientIsLenient: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Pretty printing only, for standalone human/agent-facing exports that need no leniency. */
    val Pretty: Json = Json {
        prettyPrint = true
    }
}
