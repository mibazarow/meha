package com.andharness.ru.data.tools.documents

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.Locale

internal class DocEditToolHandler(
    private val io: DocumentToolIo,
    private val editor: DocumentEditor,
) : ToolHandler {
    override val name = "doc_edit"

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
            val mode = io.stringArgument(arguments, "mode")?.lowercase(Locale.ROOT)
            if (mode != "append" && mode != "replace") {
                return ToolResult(
                    false,
                    "Укажите mode=append (дописать в конец) или mode=replace (заменить find)."
                )
            }
            val content = (arguments["content"] as? JsonPrimitive)?.content
                ?: return ToolResult(false, "Содержимое не указано (content).")
            val find = io.stringArgument(arguments, "find")
            if (mode == "replace" && find.isNullOrEmpty()) {
                return ToolResult(false, "Для режима replace обязателен параметр find.")
            }

            val format = DocumentFormatDetector.detect(file)
            val originalPages = if (format == DocumentFormat.PDF) {
                try {
                    PDDocument.load(file).use { it.numberOfPages }
                } catch (e: Exception) {
                    return ToolResult(false, "Не удалось открыть PDF: ${e.message}")
                }
            } else {
                0
            }

            val bytes = try {
                if (mode == "append") {
                    editor.buildAppendedBytes(file, format, content)
                } else {
                    editor.buildReplacedBytes(file, format, find!!, content)
                }
            } catch (e: EditException) {
                return ToolResult(false, e.message ?: "Операция не поддерживается.")
            } catch (e: Exception) {
                return ToolResult(
                    false,
                    "Не удалось изменить документ (${format.name}): ${e.message}"
                )
            }
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

            // Post-verify: re-read the rewritten file through the SAME extraction path as
            // `doc_read` and confirm the change is actually present. On mismatch the previous
            // version is restored from the `.bak` backup made by [atomicWrite] — never a
            // nominal success. A verified edit then drops the backup.
            val verifyError = editor.verifyEdit(file, format, mode, content, find, originalPages)
            if (verifyError != null) {
                val restored = io.restoreBackup(file)
                backupOwned = false
                val suffix = if (restored) {
                    " Файл восстановлен из резервной копии."
                } else {
                    ""
                }
                return ToolResult(false, "Проверка после записи не пройдена: $verifyError$suffix")
            }

            val backupNote = io.cleanupBackup(file)
            backupOwned = false
            val action = if (mode == "append") {
                "дописано в конец (${content.count { it == '\n' } + 1} стр.)"
            } else {
                "заменён текст «$find»"
            }
            ToolResult(
                true,
                "Изменён документ: ${file.absolutePath}\n" +
                    "Формат: ${format.name}\n" +
                    "Действие: $action\n" +
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
            ToolResult(false, "Ошибка при изменении документа: ${e.message}$suffix")
        }
    }
}
