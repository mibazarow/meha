package com.andharness.ru.data.tools.documents

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.Locale

    /**
     * Writes one value into a spreadsheet cell of an existing XLSX workbook. The worksheet part is
     * located exactly as `doc_read` locates it, edited in memory by the pure
     * [DocumentWriter.setXlsxCell] helper, repackaged with [DocumentSupport.rewriteZip] and written
     * atomically with a `.bak` backup, mirroring `doc_edit`. The write is only reported as a success
     * after the cell is re-read through [DocumentSupport.xlsxCellValue] and matches the requested
     * value; a mismatch restores the backup and fails honestly.
     *
     * References:
     * - SpreadsheetML cell/`<c>` structure —
     *   https://learn.microsoft.com/en-us/office/open-xml/spreadsheet/spreadsheetml
     * - `java.util.zip.ZipFile` — https://developer.android.com/reference/java/util/zip/ZipFile
     */
internal class XlsxSetCellToolHandler(
    private val io: DocumentToolIo,
    private val inspector: DocumentInspector,
) : ToolHandler {
    override val name = "xlsx_set_cell"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        var editedFile: File? = null
        var backupOwned = false
        return try {
            val raw = (arguments["path"] as? JsonPrimitive)?.content?.trim()
            if (raw.isNullOrEmpty()) {
                return ToolResult(false, "Путь не указан.")
            }
            val file = try {
                io.resolve(raw)
            } catch (e: IllegalArgumentException) {
                return ToolResult(false, e.message ?: "Некорректный путь: «$raw».")
            }
            editedFile = file
            if (!file.isFile) {
                return ToolResult(false, "Файл не найден: ${file.absolutePath}")
            }
            val cellRef = (arguments["cell"] as? JsonPrimitive)?.content?.trim()
            if (cellRef.isNullOrEmpty()) {
                return ToolResult(false, "Адрес ячейки не указан (cell).")
            }
            val value = (arguments["value"] as? JsonPrimitive)?.content
                ?: return ToolResult(false, "Значение не указано (value).")
            val sheet = io.stringArgument(arguments, "sheet")

            if (DocumentFormatDetector.detect(file) != DocumentFormat.XLSX ||
                DocumentSupport.zipEntryNames(file).none { it.startsWith("xl/") }
            ) {
                return ToolResult(
                    false,
                    "Файл не является XLSX (внутри архива нет каталога xl/): ${file.absolutePath}"
                )
            }

            val target = when (val resolved = inspector.resolveSheetTarget(file, sheet)) {
                is SheetTarget.Failure -> return ToolResult(false, resolved.message)
                is SheetTarget.Ok -> resolved
            }
            val names = target.names
            val index = target.index
            val entry = target.entry
            val xml = target.xml

            val updated = try {
                DocumentWriter.setXlsxCell(xml, cellRef, value)
            } catch (e: IllegalArgumentException) {
                return ToolResult(false, e.message ?: "Некорректный адрес ячейки: «$cellRef».")
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
            // [atomicWrite] clears any pre-existing backup before renaming, so a `.bak` seen
            // here is the one this call just made and is ours to clean up.
            backupOwned = io.backupFile(file).isFile

            val verifyError = inspector.verifyXlsxCell(file, entry, cellRef, value)
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
                    "Ячейка ${cellRef.uppercase(Locale.ROOT)} = $value\n" +
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
            ToolResult(false, "Ошибка при записи ячейки: ${e.message}$suffix")
        }
    }
}
