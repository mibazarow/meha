package com.andharness.ru.data.tools.documents

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class DocReadToolHandler(
    private val io: DocumentToolIo,
    private val inspector: DocumentInspector,
) : ToolHandler {
    override val name = "doc_read"

    override suspend fun execute(arguments: JsonObject): ToolResult {
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
            if (!file.isFile) {
                return ToolResult(false, "Файл не найден: ${file.absolutePath}")
            }
            val page = io.intArgument(arguments, "page")
            val sheet = io.stringArgument(arguments, "sheet")
            val maxChars = (io.intArgument(arguments, "max_chars") ?: DEFAULT_MAX_CHARS)
                .coerceIn(1, MAX_MAX_CHARS)

            val format = DocumentFormatDetector.detect(file)
            when (format) {
                DocumentFormat.LEGACY_DOC, DocumentFormat.LEGACY_XLS, DocumentFormat.LEGACY_PPT ->
                    return ToolResult(
                        false,
                        "Старый бинарный формат (${format.name}, OLE2) на устройстве надёжно не " +
                            "читается. Пересохраните файл как DOCX/XLSX/PPTX."
                    )
                DocumentFormat.BINARY_UNKNOWN ->
                    return ToolResult(
                        false,
                        "Формат не распознан: бинарный файл неизвестного типа " +
                            "(размер ${DocumentSupport.formatBytes(file.length())})."
                    )
                DocumentFormat.ZIP_UNKNOWN ->
                    return ToolResult(
                        false,
                        "ZIP-контейнер без распознанного документа внутри (ни OOXML, ни OpenDocument)."
                    )
                else -> Unit
            }

            val extracted = try {
                inspector.extract(file, format, page, sheet)
            } catch (e: Exception) {
                return ToolResult(false, "Не удалось извлечь текст: ${e.message}")
            }
            when (extracted) {
                is Extraction.Failure -> ToolResult(false, extracted.message)
                is Extraction.Success -> {
                    val text = extracted.text
                    if (text.isBlank()) {
                        ToolResult(
                            true,
                            "Формат: ${format.name}\nТекст не найден: документ не содержит " +
                                "текстового содержимого."
                        )
                    } else {
                        val truncated = text.length > maxChars
                        val body = if (truncated) text.substring(0, maxChars) else text
                        val note = if (truncated) {
                            "\n\n… Текст обрезан: показано $maxChars из ${text.length} символов " +
                                "(увеличьте max_chars)."
                        } else {
                            ""
                        }
                        ToolResult(
                            true,
                            "Формат: ${format.name}\nСимволов: ${text.length}\n\n$body$note"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            ToolResult(false, "Ошибка при чтении документа: ${e.message}")
        }
    }

    private companion object {
        const val DEFAULT_MAX_CHARS = 100_000
        const val MAX_MAX_CHARS = 5_000_000
    }
}
