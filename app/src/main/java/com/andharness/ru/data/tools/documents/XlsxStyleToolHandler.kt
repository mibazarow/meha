package com.andharness.ru.data.tools.documents

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * `xlsx_style`: applies basic formatting (bold, solid fill colour, custom number format) to a cell
 * range of an existing XLSX, via the pure [XlsxStyler] transform. The worksheet part and
 * `xl/styles.xml` are edited together and repackaged with [DocumentSupport.rewriteZip], written
 * atomically with a `.bak` backup, and verified by re-reading the range's cell values (they must be
 * unchanged) and confirming the new cell-format index is present.
 *
 * Honest limits: only these three attributes are supported; the stylesheet must have the usual
 * `<fonts>/<fills>/<cellXfs>` shape or the call fails with a clear message.
 *
 * References:
 * - SpreadsheetML styles — https://learn.microsoft.com/en-us/office/open-xml/spreadsheet/spreadsheetml
 * - `java.util.zip.ZipFile` — https://developer.android.com/reference/java/util/zip/ZipFile
 */
internal class XlsxStyleToolHandler(
    private val io: DocumentToolIo,
    private val inspector: DocumentInspector,
) : ToolHandler {
    override val name = "xlsx_style"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        var editedFile: File? = null
        var backupOwned = false
        return try {
            val raw = io.stringArgument(arguments, "path")
                ?: return ToolResult(false, "Путь не указан (path).")
            val file = try {
                io.resolve(raw)
            } catch (e: IllegalArgumentException) {
                return ToolResult(false, e.message ?: "Некорректный путь: «$raw».")
            }
            editedFile = file
            if (!file.isFile) return ToolResult(false, "Файл не найден: ${file.absolutePath}")
            if (DocumentFormatDetector.detect(file) != DocumentFormat.XLSX ||
                DocumentSupport.zipEntryNames(file).none { it.startsWith("xl/") }
            ) {
                return ToolResult(false, "Файл не является XLSX: ${file.absolutePath}")
            }
            val range = io.stringArgument(arguments, "range")
                ?: return ToolResult(false, "Не указан диапазон ячеек (range), напр. A1 или A1:C3.")

            val bold = io.booleanArgument(arguments, "bold")
            val fillColor = io.stringArgument(arguments, "fill_color")
            val numberFormat = io.stringArgument(arguments, "number_format")
            val style = XlsxStyler.Style(bold = bold, fillColor = fillColor, numberFormat = numberFormat)
            if (style.isEmpty) {
                return ToolResult(
                    false,
                    "Не задан ни один параметр стиля: укажите bold, fill_color (#RRGGBB) или number_format."
                )
            }

            val sheet = io.stringArgument(arguments, "sheet")
            val workbook = DocumentSupport.readZipEntryText(file, "xl/workbook.xml")
                ?: return ToolResult(false, "Внутренний документ xl/workbook.xml не найден.")
            val names = DocumentSupport.xlsxSheetNames(workbook)
            if (names.isEmpty()) return ToolResult(false, "В книге не найдено ни одного листа.")
            val index = inspector.resolveSheetIndex(names, sheet)
                ?: return ToolResult(
                    false,
                    "Лист «$sheet» не найден. Доступные листы: ${names.joinToString(", ")}."
                )
            val entry = inspector.xlsxSheetEntry(file, workbook, index)
                ?: return ToolResult(false, "Не найден XML листа «${names[index]}».")
            val xml = DocumentSupport.readZipEntryText(file, entry)
                ?: return ToolResult(false, "Не удалось прочитать XML листа ($entry).")
            val stylesXml = DocumentSupport.readZipEntryText(file, "xl/styles.xml")
                ?: return ToolResult(false, "В книге нет xl/styles.xml — применить стиль нельзя.")

            val shared = DocumentSupport.readZipEntryText(file, "xl/sharedStrings.xml")
                ?.let { DocumentSupport.xlsxSharedStrings(it) } ?: emptyList()
            val beforeRows = DocumentSupport.xlsxSheetRows(xml, shared)

            val result = try {
                XlsxStyler.style(xml, stylesXml, range, style)
            } catch (e: IllegalArgumentException) {
                return ToolResult(false, e.message ?: "Не удалось применить стиль к диапазону «$range».")
            }

            val bytes = DocumentSupport.rewriteZip(
                file,
                mapOf(
                    entry to result.sheetXml.toByteArray(Charsets.UTF_8),
                    "xl/styles.xml" to result.stylesXml.toByteArray(Charsets.UTF_8),
                )
            ) ?: return ToolResult(false, "Не удалось перепаковать контейнер (${file.name}).")
            if (bytes.isEmpty()) return ToolResult(false, "Изменённый документ получился пустым.")

            val writeError = io.atomicWrite(file, bytes)
            if (writeError != null) return ToolResult(false, writeError)
            backupOwned = io.backupFile(file).isFile

            val verifyError = verifyStyle(file, entry, result.styleIndex, beforeRows, shared)
            if (verifyError != null) {
                val restored = io.restoreBackup(file)
                backupOwned = false
                val suffix = if (restored) " Файл восстановлен из резервной копии." else ""
                return ToolResult(false, "Проверка после записи не пройдена: $verifyError$suffix")
            }

            val backupNote = io.cleanupBackup(file)
            backupOwned = false
            ToolResult(
                true,
                "Изменён документ: ${file.absolutePath}\n" +
                    "Формат: XLSX\n" +
                    "Лист: ${names[index]}\n" +
                    "Диапазон: $range (ячеек: ${result.cellCount})\n" +
                    "Применено: " + describeStyle(style) + "\n" +
                    "Размер: ${DocumentSupport.formatBytes(file.length())}" +
                    if (backupNote != null) "\n$backupNote" else ""
            )
        } catch (e: Exception) {
            val restored = if (backupOwned && editedFile != null) io.restoreBackup(editedFile!!) else false
            val suffix = if (restored) " Файл восстановлен из резервной копии." else ""
            ToolResult(false, "Ошибка при применении стиля: ${e.message}$suffix")
        }
    }

    private fun describeStyle(style: XlsxStyler.Style): String {
        val parts = mutableListOf<String>()
        if (style.bold) parts += "полужирный"
        if (style.fillColor != null) parts += "заливка ${style.fillColor}"
        if (style.numberFormat != null) parts += "формат «${style.numberFormat}»"
        return parts.joinToString(", ")
    }

    /** Values must be unchanged and the new `s="index"` must be present in the sheet. */
    private fun verifyStyle(
        file: File,
        entry: String,
        styleIndex: Int,
        beforeRows: List<List<String>>,
        shared: List<String>,
    ): String? {
        if (!file.isFile) return "файл недоступен после записи."
        if (file.length() == 0L) return "файл пуст после записи."
        if (DocumentFormatDetector.detect(file) != DocumentFormat.XLSX) return "формат файла не XLSX."
        val xml = DocumentSupport.readZipEntryText(file, entry)
            ?: return "не удалось повторно прочитать XML листа ($entry)."
        if (!xml.contains("s=\"$styleIndex\"")) {
            return "в листе нет ячеек со стилем s=\"$styleIndex\"."
        }
        val afterRows = DocumentSupport.xlsxSheetRows(xml, shared)
        return if (afterRows == beforeRows) null else "значения ячеек изменились после применения стиля."
    }
}
