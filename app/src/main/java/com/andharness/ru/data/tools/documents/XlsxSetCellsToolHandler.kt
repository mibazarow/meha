package com.andharness.ru.data.tools.documents

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * `xlsx_set_cells`: writes several values into one worksheet of an existing XLSX workbook in a single
 * call. The worksheet part is located exactly as `doc_read`/`xlsx_set_cell` locate it and edited in
 * memory by the same pure [DocumentWriter.setXlsxCell] helper, then repackaged with
 * [DocumentSupport.rewriteZip] and written atomically with a `.bak` backup.
 *
 * The `cells` argument is a JSON string in either of the two shapes understood by [CellMapParser]
 * (`{"A1": "v", "B2": 42}` or `[{"cell": "A1", "value": "v"}]`). Every reference is validated before
 * anything is written, so an invalid address cannot leave a partially edited workbook.
 *
 * `overwrite` keeps the existing XLSX behaviour of replacing cell content (default `true`); passing
 * `overwrite=false` turns it into a guard that refuses to replace cells which already hold a
 * non-empty value, listing them. Each written cell is re-read through [DocumentInspector.verifyXlsxCell]
 * before success is reported; a mismatch restores the backup and fails honestly.
 *
 * References:
 * - SpreadsheetML cell/`<c>` structure —
 *   https://learn.microsoft.com/en-us/office/open-xml/spreadsheet/spreadsheetml
 * - `java.util.zip.ZipFile` — https://developer.android.com/reference/java/util/zip/ZipFile
 */
internal class XlsxSetCellsToolHandler(
    private val io: DocumentToolIo,
    private val inspector: DocumentInspector,
) : ToolHandler {
    override val name = "xlsx_set_cells"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        var editedFile: File? = null
        var backupOwned = false
        return try {
            val raw = io.stringArgument(arguments, "path")
                ?: return ToolResult(false, "Путь не указан.")
            val file = try {
                io.resolve(raw)
            } catch (e: IllegalArgumentException) {
                return ToolResult(false, e.message ?: "Некорректный путь: «$raw».")
            }
            editedFile = file
            if (!file.isFile) {
                return ToolResult(false, "Файл не найден: ${file.absolutePath}")
            }

            val cellsJson = (arguments["cells"] as? JsonPrimitive)?.content
                ?: return ToolResult(false, "Не указаны ячейки (cells).")
            val cells = try {
                CellMapParser.parse(cellsJson)
            } catch (e: IllegalArgumentException) {
                return ToolResult(false, e.message ?: "Некорректный список ячеек.")
            }

            val sheet = io.stringArgument(arguments, "sheet")
            val overwrite = (arguments["overwrite"] as? JsonPrimitive)
                ?.content?.toBooleanStrictOrNull() ?: true

            if (DocumentFormatDetector.detect(file) != DocumentFormat.XLSX ||
                DocumentSupport.zipEntryNames(file).none { it.startsWith("xl/") }
            ) {
                return ToolResult(
                    false,
                    "Файл не является XLSX (внутри архива нет каталога xl/): ${file.absolutePath}"
                )
            }

            val workbook = DocumentSupport.readZipEntryText(file, "xl/workbook.xml")
                ?: return ToolResult(false, "Внутренний документ xl/workbook.xml не найден.")
            val names = DocumentSupport.xlsxSheetNames(workbook)
            if (names.isEmpty()) {
                return ToolResult(false, "В книге не найдено ни одного листа.")
            }
            val index = inspector.resolveSheetIndex(names, sheet)
                ?: return ToolResult(
                    false,
                    "Лист «$sheet» не найден. Доступные листы: ${names.joinToString(", ")}."
                )
            val entry = inspector.xlsxSheetEntry(file, workbook, index)
                ?: return ToolResult(false, "Не найден XML листа «${names[index]}».")
            val xml = DocumentSupport.readZipEntryText(file, entry)
                ?: return ToolResult(false, "Не удалось прочитать XML листа ($entry).")
            val shared = DocumentSupport.readZipEntryText(file, "xl/sharedStrings.xml")
                ?.let { DocumentSupport.xlsxSharedStrings(it) }
                ?: emptyList()

            if (!overwrite) {
                val occupied = cells.filter { cell ->
                    !DocumentSupport.xlsxCellValue(xml, shared, cell.ref).isNullOrEmpty()
                }
                if (occupied.isNotEmpty()) {
                    return ToolResult(
                        false,
                        "В ячейках уже есть значения: ${
                            occupied.joinToString(", ") { it.ref }
                        }. Перезапись отключена (overwrite=false)."
                    )
                }
            }

            var updated = xml
            for (cell in cells) {
                updated = DocumentWriter.setXlsxCell(updated, cell.ref, cell.value)
            }
            val bytes = DocumentSupport.rewriteZip(
                file,
                mapOf(entry to updated.toByteArray(Charsets.UTF_8))
            ) ?: return ToolResult(false, "Не удалось перепаковать контейнер (${file.name}).")
            if (bytes.isEmpty()) {
                return ToolResult(false, "Изменённый документ получился пустым.")
            }

            val writeError = io.atomicWrite(file, bytes)
            if (writeError != null) {
                return ToolResult(false, writeError)
            }
            backupOwned = io.backupFile(file).isFile

            for (cell in cells) {
                val verifyError = inspector.verifyXlsxCell(file, entry, cell.ref, cell.value)
                if (verifyError != null) {
                    val restored = io.restoreBackup(file)
                    backupOwned = false
                    val suffix = if (restored) " Файл восстановлен из резервной копии." else ""
                    return ToolResult(
                        false,
                        "Проверка после записи не пройдена: $verifyError$suffix"
                    )
                }
            }

            val backupNote = io.cleanupBackup(file)
            backupOwned = false
            ToolResult(
                true,
                "Изменён документ: ${file.absolutePath}\n" +
                    "Формат: XLSX\n" +
                    "Лист: ${names[index]}\n" +
                    "Ячеек записано: ${cells.size} (${cells.joinToString(", ") { it.ref }})\n" +
                    "Размер: ${DocumentSupport.formatBytes(file.length())}" +
                    if (backupNote != null) "\n$backupNote" else ""
            )
        } catch (e: Exception) {
            val restored = if (backupOwned && editedFile != null) {
                io.restoreBackup(editedFile!!)
            } else {
                false
            }
            val suffix = if (restored) " Файл восстановлен из резервной копии." else ""
            ToolResult(false, "Ошибка при записи ячеек: ${e.message}$suffix")
        }
    }
}
