package com.andharness.ru.data.tools.documents

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class DocInfoToolHandler(
    private val io: DocumentToolIo,
    private val inspector: DocumentInspector,
) : ToolHandler {
    override val name = "doc_info"

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
            val format = DocumentFormatDetector.detect(file)
            val header = buildString {
                append("Формат: ${format.name}")
                append("\nРазмер: ${DocumentSupport.formatBytes(file.length())}")
                append("\nПуть: ${file.absolutePath}")
            }
            val details = try {
                inspector.describe(file, format)
            } catch (e: Exception) {
                "Не удалось разобрать структуру документа: ${e.message}"
            }
            ToolResult(true, if (details.isEmpty()) header else "$header\n$details")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка при разборе документа: ${e.message}")
        }
    }
}
