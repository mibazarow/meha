package com.andharness.ru.data.clipboard

import kotlinx.serialization.encodeToString
import com.andharness.ru.domain.serialization.AppJson
import com.andharness.ru.domain.serialization.decodeOrNull

/**
 * Pure JSON encode/decode of the clipboard history. A corrupt, foreign or missing blob degrades to
 * an empty list, so a broken file can never crash a tool or the foreground capture path.
 * Reference: https://kotlinlang.org/api/kotlinx.serialization/
 */
object ClipboardHistoryCodec {

    private val json = AppJson.Lenient

    fun encode(entries: List<ClipboardEntry>): String =
        json.encodeToString(ClipboardHistoryRules.bound(entries))

    fun decode(raw: String?): List<ClipboardEntry> =
        json.decodeOrNull<List<ClipboardEntry>>(raw)
            ?.let { ClipboardHistoryRules.bound(it) }
            ?: emptyList()
}
