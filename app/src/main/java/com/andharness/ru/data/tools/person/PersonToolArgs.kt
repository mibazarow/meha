package com.andharness.ru.data.tools.person

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Typed accessors for the JSON tool arguments shared by the person tools. */
internal class PersonToolArgs {

    /** Non-blank, trimmed [key] value, or `null`. */
    fun string(arguments: JsonObject, key: String): String? =
        (arguments[key] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }

    /** [string] parsed as [Long], or `null` when missing or not a number. */
    fun long(arguments: JsonObject, key: String): Long? =
        string(arguments, key)?.toLongOrNull()

    /** [string] parsed as [Int], falling back to [default]. */
    fun int(arguments: JsonObject, key: String, default: Int): Int =
        string(arguments, key)?.toIntOrNull() ?: default
}
