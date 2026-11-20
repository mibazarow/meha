package com.andharness.ru.data.tools.documents

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * `office_convert`: converts between the formats the hand-rolled writers support, by extracting the
 * source's content and re-writing it in the target format via [OfficeConvert]. No layout engine is
 * involved, so only the content (text or cells) is converted — never the visual formatting.
 *
 * Refusing honestly: an office→PDF target requires a layout engine this project does not embed, so
 * the tool reports it as unsupported and points at `pdf_create`/`pdf_from_markdown`/`pdf_from_template`
 * instead of faking a result.
 *
 * References:
 * - OOXML packages — https://learn.microsoft.com/en-us/office/open-xml/
 * - OpenDocument packages — https://docs.oasis-open.org/office/v1.2/os/OpenDocument-v1.2-os-part3.html
 */
internal class OfficeConvertToolHandler(
    private val io: DocumentToolIo,
    private val inspector: DocumentInspector,
) : ToolHandler {
    override val name = "office_convert"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        var destination: File? = null
        var backupOwned = false
        return try {
            val raw = io.stringArgument(arguments, "path")
                ?: return ToolResult(false, "Путь к исходному документу не указан (path).")
            val source = try {
                io.resolve(raw)
            } catch (e: IllegalArgumentException) {
                return ToolResult(false, e.message ?: "Некорректный путь: «$raw».")
            }
            if (!source.isFile) return ToolResult(false, "Файл не найден: ${source.absolutePath}")

            val format = DocumentFormatDetector.detect(source)
            val kind = OfficeConvert.sourceKind(format)
                ?: return ToolResult(
                    false,
                    "Формат ${format.name} не поддерживается для конвертации (нужен текст, таблица " +
                        "или презентация из поддерживаемых форматов)."
                )

            val targetRaw = io.stringArgument(arguments, "target")
                ?: return ToolResult(false, "Не указан целевой формат (target).")
            if (targetRaw.trim().removePrefix(".").equals("pdf", ignoreCase = true)) {
                return ToolResult(
                    false,
                    "Конвертация office→PDF не поддерживается: для точной вёрстки нужен движок " +
                        "разметки. Используйте pdf_create / pdf_from_markdown (из текста) или " +
                        "pdf_from_template."
                )
            }
            val target = OfficeConvert.Target.from(targetRaw)
                ?: return ToolResult(
                    false,
                    "Неизвестный целевой формат «$targetRaw». Для ${format.name} доступны: " +
                        supportedTargets(kind).joinToString(", ") { it.extension } + "."
                )
            if (!OfficeConvert.supports(target, kind)) {
                return ToolResult(
                    false,
                    "Из ${format.name} нельзя получить ${target.format.name}. Доступны: " +
                        supportedTargets(kind).joinToString(", ") { it.extension } + "."
                )
            }

            val text: String
            val rows: List<List<String>>
            when (kind) {
                OfficeConvert.SourceKind.TABLE -> {
                    text = ""
                    rows = inspector.extractTableRows(source, format, io.stringArgument(arguments, "sheet"))
                        ?: return ToolResult(false, "Не удалось извлечь таблицу из ${source.name}.")
                }
                else -> {
                    val extraction = inspector.extract(source, format, page = null, sheet = null)
                    if (extraction is Extraction.Failure) return ToolResult(false, extraction.message)
                    text = (extraction as Extraction.Success).text
                    rows = emptyList()
                }
            }

            val title = io.stringArgument(arguments, "title") ?: source.nameWithoutExtension
            val bytes = try {
                OfficeConvert.convert(target, kind, text, rows, title)
            } catch (e: IllegalArgumentException) {
                return ToolResult(false, e.message ?: "Не удалось выполнить конвертацию.")
            }
            if (bytes.isEmpty()) return ToolResult(false, "Результат конвертации пуст.")

            val out = when (val resolved = io.resolveDestination(
                io.stringArgument(arguments, "destination")
                    ?: return ToolResult(false, "Не указан путь к результату (destination)."),
                io.booleanArgument(arguments, "overwrite"),
            )) {
                is Destination.Failure -> return ToolResult(false, resolved.message)
                is Destination.Ok -> resolved.file
            }
            destination = out

            val writeError = io.atomicWrite(out, bytes)
            if (writeError != null) return ToolResult(false, writeError)
            backupOwned = io.backupFile(out).isFile

            val verifyError = verifyConverted(out, target)
            if (verifyError != null) {
                val restored = io.restoreBackup(out)
                if (!restored) runCatching { out.delete() }
                backupOwned = false
                val suffix = if (restored) " Файл восстановлен из резервной копии." else ""
                return ToolResult(false, "Проверка после записи не пройдена: $verifyError$suffix")
            }

            val backupNote = io.cleanupBackup(out)
            backupOwned = false
            ToolResult(
                true,
                "Конвертирован документ: ${source.absolutePath}\n" +
                    "В формат: ${target.format.name} (.${target.extension})\n" +
                    "Путь: ${out.absolutePath}\n" +
                    "Примечание: переносится содержимое (текст/ячейки), оформление не сохраняется.\n" +
                    "Размер: ${DocumentSupport.formatBytes(out.length())}" +
                    if (backupNote != null) "\n$backupNote" else ""
            )
        } catch (e: Exception) {
            val restored = if (backupOwned && destination != null) io.restoreBackup(destination!!) else false
            val suffix = if (restored) " Файл восстановлен из резервной копии." else ""
            ToolResult(false, "Ошибка при конвертации: ${e.message}$suffix")
        }
    }

    private fun supportedTargets(kind: OfficeConvert.SourceKind): List<OfficeConvert.Target> =
        OfficeConvert.Target.entries.filter { OfficeConvert.supports(it, kind) }

    private fun verifyConverted(file: File, target: OfficeConvert.Target): String? {
        if (!file.isFile) return "файл недоступен после записи."
        if (file.length() == 0L) return "файл пуст после записи."
        val detected = DocumentFormatDetector.detect(file)
        if (detected != target.format) {
            return "формат результата определён как ${detected.name}, ожидался ${target.format.name}."
        }
        return null
    }
}
