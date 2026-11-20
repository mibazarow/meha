package com.andharness.ru.domain.undo

import kotlinx.serialization.encodeToString
import com.andharness.ru.domain.serialization.AppJson
import com.andharness.ru.domain.serialization.decodeOrNull

/**
 * Pure JSON encode/decode for the undo journal. Any corrupt, foreign or missing value degrades to an
 * empty list, and the journal is always bounded to [MAX_ENTRIES] (oldest dropped) so the file can
 * never grow unchecked. Mirrors `SkillCodec` / `TrustedSourceCodec`.
 * Reference: https://kotlinlang.org/api/kotlinx.serialization/
 */
object UndoCodec {

    /** Hard cap on stored entries (the "last N" from the M3 slice 1 spec). */
    const val MAX_ENTRIES = 50

    private val json = AppJson.Lenient

    fun encode(entries: List<UndoEntry>): String =
        json.encodeToString(entries.takeLast(MAX_ENTRIES))

    fun decode(raw: String?): List<UndoEntry> =
        json.decodeOrNull<List<UndoEntry>>(raw)?.takeLast(MAX_ENTRIES) ?: emptyList()
}
