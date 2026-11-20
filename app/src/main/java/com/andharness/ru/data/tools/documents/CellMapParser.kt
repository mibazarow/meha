package com.andharness.ru.data.tools.documents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * Parses the `cells` argument of `xlsx_set_cells` into an ordered list of validated cell writes.
 *
 * Two shapes are accepted (both JSON passed as a string):
 * - an object map: `{"A1": "v", "B2": 42}`
 * - an array of objects: `[{"cell": "A1", "value": "v"}, {"cell": "B2", "value": 42}]`
 *
 * Every reference is validated through [DocumentWriter.parseCellReference] and normalised to
 * `A1`-style uppercase before use; a bad reference is an honest [IllegalArgumentException] naming
 * the offending entries, so no partial write is attempted. Values are stringified recursively.
 *
 * Pure JVM (kotlinx.serialization + [XlsxWriter]), so it is unit-tested off-device.
 *
 * References:
 * - SpreadsheetML cell reference (`A1`…`XFD1048576`) —
 *   https://learn.microsoft.com/en-us/office/open-xml/spreadsheet/spreadsheetml
 */
object CellMapParser {

    /** One validated cell write: [ref] is normalised (`b2` → `B2`), [value] is already text. */
    data class Cell(val ref: String, val value: String)

    /**
     * Parses [json]. Throws [IllegalArgumentException] with a human-readable message when the JSON is
     * malformed, is neither an object nor an array, is empty, or contains an invalid cell reference.
     */
    fun parse(json: String): List<Cell> {
        val element = try {
            Json.parseToJsonElement(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("cells: некорректный JSON (${e.message}).")
        }
        val raw = when (element) {
            is JsonObject -> element.entries.map { (ref, value) -> ref to value }
            is JsonArray -> element.mapIndexed { index, item -> arrayEntry(index, item) }
            else -> throw IllegalArgumentException(
                "cells: ожидался JSON-объект {\"A1\": \"значение\"} или массив " +
                    "[{\"cell\": \"A1\", \"value\": \"…\"}]."
            )
        }
        if (raw.isEmpty()) {
            throw IllegalArgumentException("cells: список ячеек пуст.")
        }

        val cells = LinkedHashMap<String, String>()
        val bad = mutableListOf<String>()
        for ((ref, value) in raw) {
            val address = DocumentWriter.parseCellReference(ref)
            if (address == null) {
                bad += ref
                continue
            }
            val normalised = "${DocumentWriter.columnName(address.column)}${address.row}"
                .uppercase(Locale.ROOT)
            cells[normalised] = stringify(value)
        }
        if (bad.isNotEmpty()) {
            throw IllegalArgumentException(
                "cells: некорректные адреса ячеек: ${bad.joinToString(", ")}. Ожидается вид B2."
            )
        }
        return cells.map { (ref, value) -> Cell(ref, value) }
    }

    /** One `[{cell, value}]` entry; missing/blank `cell` is reported as a bad reference. */
    private fun arrayEntry(index: Int, item: JsonElement): Pair<String, JsonElement> {
        val obj = item as? JsonObject
            ?: throw IllegalArgumentException(
                "cells[$index]: ожидался объект {\"cell\": \"A1\", \"value\": \"…\"}."
            )
        val ref = (obj["cell"] as? JsonPrimitive)
            ?.takeIf { it !is JsonNull }
            ?.content
            ?.trim()
            .orEmpty()
        if (ref.isEmpty()) {
            throw IllegalArgumentException("cells[$index]: не указан адрес ячейки (cell).")
        }
        return ref to (obj["value"] ?: JsonNull)
    }

    /** Recursive JSON value → text: primitives verbatim, `null` empty, arrays/objects joined. */
    fun stringify(element: JsonElement): String = when (element) {
        is JsonNull -> ""
        is JsonPrimitive -> element.content
        is JsonArray -> element.joinToString(", ") { stringify(it) }
        is JsonObject -> element.entries.joinToString("; ") { (key, value) -> "$key=${stringify(value)}" }
    }
}
