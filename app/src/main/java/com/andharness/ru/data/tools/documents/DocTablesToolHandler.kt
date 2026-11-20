package com.andharness.ru.data.tools.documents

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `doc_tables`: extracts a spreadsheet/CSV as STRUCTURED data — a JSON array of rows, each row an
 * array of cell strings — instead of the tab-separated text `doc_read` renders. Covers XLSX, ODS,
 * CSV and TSV; other formats are refused honestly.
 *
 * The output is a JSON object: `{"format": ..., "rowCount": N, "truncated": bool, "rows": [[...]]}`
 * so a call that exceeds `max_rows` is honest about the cut instead of emitting invalid JSON.
 *
 * References:
 * - SpreadsheetML cells — https://learn.microsoft.com/en-us/office/open-xml/spreadsheet/spreadsheetml
 * - OpenDocument tables — https://docs.oasis-open.org/office/v1.2/os/OpenDocument-v1.2-os-part3.html
 */
internal class DocTablesToolHandler(
    private val io: DocumentToolIo,
    private val inspector: DocumentInspector,
) : ToolHandler {
    override val name = "doc_tables"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        return try {
            val raw = io.stringArgument(arguments, "path")
                ?: return ToolResult(false, "Путь к таблице не указан (path).")
            val file = try {
                io.resolve(raw)
            } catch (e: IllegalArgumentException) {
                return ToolResult(false, e.message ?: "Некорректный путь: «$raw».")
            }
            if (!file.isFile) return ToolResult(false, "Файл не найден: ${file.absolutePath}")

            val format = DocumentFormatDetector.detect(file)
            if (format != DocumentFormat.XLSX && format != DocumentFormat.ODS &&
                format != DocumentFormat.CSV && format != DocumentFormat.TSV
            ) {
                return ToolResult(
                    false,
                    "Формат ${format.name} не является таблицей. Поддерживаются XLSX, ODS, CSV, TSV."
                )
            }

            val rows = inspector.extractTableRows(file, format, io.stringArgument(arguments, "sheet"))
                ?: return ToolResult(false, "Не удалось извлечь таблицу из ${file.name}.")
            val maxRows = (io.intArgument(arguments, "max_rows") ?: DEFAULT_MAX_ROWS).coerceIn(1, MAX_ROWS)
            val truncated = rows.size > maxRows
            val shown = if (truncated) rows.take(maxRows) else rows

            val envelope = buildJsonObject {
                put("format", format.name)
                put("rowCount", rows.size)
                put("truncated", truncated)
                put("rows", buildJsonArray {
                    shown.forEach { row ->
                        add(buildJsonArray { row.forEach { cell -> add(JsonPrimitive(cell)) } })
                    }
                })
            }
            ToolResult(true, envelope.toString())
        } catch (e: Exception) {
            ToolResult(false, "Ошибка при извлечении таблицы: ${e.message}")
        }
    }

    private companion object {
        const val DEFAULT_MAX_ROWS = 1_000
        const val MAX_ROWS = 50_000
    }
}
