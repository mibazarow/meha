package com.andharness.ru.data.tools.communications

import com.andharness.ru.data.tools.boolOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/** Typed accessors for the JSON tool arguments shared by the Communications tools. */
internal class CommunicationsToolArgs {

    /** Non-blank, trimmed [key] value, or `null`. */
    fun string(arguments: JsonObject, key: String): String? =
        (arguments[key] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }

    /** [string] parsed as [Long], or `null` when missing or not a number. */
    fun long(arguments: JsonObject, key: String): Long? =
        string(arguments, key)?.toLongOrNull()

    /** [string] parsed as [Int], falling back to [default]. */
    fun int(arguments: JsonObject, key: String, default: Int): Int =
        string(arguments, key)?.toIntOrNull() ?: default

    /**
     * [key] parsed as a boolean, or `null` when absent or not a recognised truthy/falsy token. The
     * `null` distinguishes "not supplied" from an explicit `false`, which `speaker_name_assign`
     * relies on to decide whether to touch the `isSelf` flag.
     *
     * Thin delegate to the shared [com.andharness.ru.data.tools.boolOrNull] extension, which holds
     * the single canonical token table; the name/signature here stays so call sites compile
     * unchanged. Extension-function resolution and `lowercase(Locale)` case folding:
     * https://kotlinlang.org/docs/extensions.html ,
     * https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/lowercase.html
     */
    fun boolOrNull(arguments: JsonObject, key: String): Boolean? = arguments.boolOrNull(key)
}

/**
 * Compact `h:mm:ss` / `mm:ss` timecode for a segment offset in millis; negative values clamp to `0`.
 * [Locale.ROOT] keeps the digits stable on every device locale. Pure and JVM-testable.
 */
internal fun formatTimecode(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}
