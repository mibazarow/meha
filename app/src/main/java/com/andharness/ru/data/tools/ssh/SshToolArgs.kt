package com.andharness.ru.data.tools.ssh

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Typed accessors for the JSON tool arguments shared by the SSH tools: a missing/blank value is
 * `null`, and every accessor trims string input so the per-tool handlers stay free of parsing noise.
 */
internal class SshToolArgs {

    /** Non-blank, trimmed [key] value, or `null`. */
    fun string(arguments: JsonObject, key: String): String? =
        (arguments[key] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }

    /** [string] parsed as [Long], or `null` when missing or not a number. */
    fun long(arguments: JsonObject, key: String): Long? =
        string(arguments, key)?.toLongOrNull()

    /** [string] parsed as [Int], falling back to [default]. */
    fun int(arguments: JsonObject, key: String, default: Int): Int =
        string(arguments, key)?.toIntOrNull() ?: default

    /** [string] parsed strictly as a boolean, or `null` when missing/not `true`/`false`. */
    fun boolean(arguments: JsonObject, key: String): Boolean? =
        string(arguments, key)?.toBooleanStrictOrNull()
}
