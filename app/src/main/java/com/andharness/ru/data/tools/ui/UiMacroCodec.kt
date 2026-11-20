package com.andharness.ru.data.tools.ui

import kotlinx.serialization.encodeToString
import com.andharness.ru.domain.serialization.AppJson
import com.andharness.ru.domain.serialization.decodeOrNull

/**
 * Pure encode/decode/cap logic for the UI-macro store. It only depends on kotlinx.serialization, so
 * it is exercised on the JVM by `UiMacroCodecTest` (the Android-bound [UiMacroStore] is not).
 *
 * Capping is enforced here (not in the UI) so a hand-edited or oversized store can never grow the
 * replay beyond [MAX_STEPS] steps or [MAX_MACROS] macros; the limits match the recorder so an
 * in-progress recording and a persisted macro behave identically.
 *
 * Reference: https://kotlinlang.org/api/kotlinx.serialization/
 */
object UiMacroCodec {

    /** Hard cap on replayable steps per macro (the recorder stops appending past this). */
    const val MAX_STEPS = 100

    /** Hard cap on saved macros; the oldest is dropped when a new one is added. */
    const val MAX_MACROS = 50

    private val json = AppJson.Lenient

    fun encode(records: List<UiMacroRecord>): String = json.encodeToString(records)

    /**
     * Decodes the persisted macro list. Any parse failure (corrupt/foreign JSON) degrades to an
     * empty list, exactly like `GeofenceStore.list`. Records without an id/name are dropped and
     * oversized data is clamped, so a broken store can never surface in `ui_macro_run`.
     */
    fun decode(raw: String?): List<UiMacroRecord> =
        json.decodeOrNull<List<UiMacroRecord>>(raw)
            ?.filter { it.id.isNotBlank() && it.name.isNotBlank() }
            ?.map { it.copy(steps = it.steps.take(MAX_STEPS)) }
            ?.takeLast(MAX_MACROS)
            ?: emptyList()

    /** Appends [step] unless the macro already holds [MAX_STEPS] steps. */
    fun append(record: UiMacroRecord, step: UiMacroStep): UiMacroRecord =
        if (record.steps.size >= MAX_STEPS) record else record.copy(steps = record.steps + step)
}
