package com.andharness.ru.data.tools.documents

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * `pdf_fill_form`: fills the AcroForm fields of a PDF from a JSON object (`fields`, name → value)
 * and writes a NEW PDF. Both top-level and fully-qualified field names are accepted (PdfBox
 * `PDAcroForm.getField` resolves the tree). `needAppearances` is set so conforming viewers regenerate
 * the field appearances, and an optional `flatten` bakes the values into the page content.
 *
 * The reply lists exactly which fields were filled, which were not found, and which failed to accept
 * the value, so a partial fill is never reported as a full one. The source is only read; the result
 * is written to a temp file, re-opened to prove the page count, and only then swapped in.
 *
 * References:
 * - PdfBox-Android `PDAcroForm` / `PDField.setValue` —
 *   https://github.com/TomRoush/PdfBox-Android
 * - Apache PDFBox `PDAcroForm` —
 *   https://pdfbox.apache.org/docs/2.0.27/javadocs/org/apache/pdfbox/pdmodel/interactive/form/PDAcroForm.html
 */
internal class PdfFillFormToolHandler(
    private val io: DocumentToolIo,
) : ToolHandler {
    override val name = "pdf_fill_form"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        return try {
            val rawSource = io.stringArgument(arguments, "source")
                ?: return ToolResult(false, "Не указан исходный PDF (source).")
            val rawDestination = io.stringArgument(arguments, "destination")
                ?: return ToolResult(false, "Путь к создаваемому файлу не указан (destination).")
            val rawFields = io.stringArgument(arguments, "fields")
                ?: return ToolResult(false, "Не указаны поля (fields) — JSON-объект имя→значение.")
            val values = try {
                parseFields(rawFields)
            } catch (e: Exception) {
                return ToolResult(false, "fields: ${e.message}")
            }
            if (values.isEmpty()) {
                return ToolResult(false, "fields пуст: нечего заполнять.")
            }
            val flatten = io.booleanArgument(arguments, "flatten")
            val overwrite = io.booleanArgument(arguments, "overwrite")

            val source = when (val resolved = io.resolvePdfSource(rawSource)) {
                is PdfSource.Failure -> return ToolResult(false, resolved.message)
                is PdfSource.Ok -> resolved
            }
            val file = when (val resolved = io.resolveDestination(rawDestination, overwrite)) {
                is Destination.Failure -> return ToolResult(false, resolved.message)
                is Destination.Ok -> resolved.file
            }
            if (source.file.canonicalFile == file.canonicalFile) {
                return ToolResult(false, "Файл назначения совпадает с исходным PDF: ${file.absolutePath}")
            }

            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp-${System.nanoTime()}")
            val filled = mutableListOf<String>()
            val notFound = mutableListOf<String>()
            val failed = mutableListOf<String>()
            try {
                PDDocument.load(source.file).use { document ->
                    val form = document.documentCatalog.acroForm
                        ?: return ToolResult(
                            false,
                            "В PDF нет формы (AcroForm): заполнять нечего. " +
                                "Файл не создан, исходный PDF не изменён."
                        )
                    for ((name, value) in values) {
                        val field = try {
                            form.getField(name)
                        } catch (e: Exception) {
                            null
                        }
                        if (field == null) {
                            notFound += name
                            continue
                        }
                        try {
                            field.setValue(value)
                            filled += name
                        } catch (e: Exception) {
                            failed += "$name (${e.message})"
                        }
                    }
                    if (filled.isNotEmpty()) {
                        form.setNeedAppearances(true)
                        if (flatten) form.flatten()
                    }
                    document.save(temp)
                }
            } catch (e: Exception) {
                temp.delete()
                return ToolResult(false, "Не удалось заполнить форму: ${e.message}")
            }

            val actualPages = try {
                PDDocument.load(temp).use { it.numberOfPages }
            } catch (e: Exception) {
                temp.delete()
                return ToolResult(false, "Не удалось открыть созданный PDF для проверки: ${e.message}")
            }
            if (actualPages != source.pages) {
                temp.delete()
                return ToolResult(
                    false,
                    "Проверка после записи не пройдена: страниц $actualPages, ожидалось " +
                        "${source.pages}. Файл не создан, исходный PDF не изменён."
                )
            }

            val commitError = io.commitTemp(temp, file)
            if (commitError != null) return ToolResult(false, commitError)
            val backupNote = io.cleanupBackup(file)
            val message = buildString {
                append("Форма заполнена: ${file.absolutePath}\n")
                append("Заполнено полей: ${filled.size}")
                if (filled.isNotEmpty()) append(" (${filled.joinToString(", ")})")
                append("\nНе найдено полей: ${notFound.size}")
                if (notFound.isNotEmpty()) append(" (${notFound.joinToString(", ")})")
                if (failed.isNotEmpty()) {
                    append("\nОшибки при заполнении: ${failed.joinToString("; ")}")
                }
                if (flatten) append("\nЗначения вписаны в страницы (flatten).")
                if (backupNote != null) append("\n$backupNote")
            }
            ToolResult(true, message)
        } catch (e: Exception) {
            ToolResult(false, "Ошибка при заполнении формы: ${e.message}")
        }
    }

    /** Parses `fields` JSON into ordered name→string pairs; numbers/booleans become their text form. */
    private fun parseFields(raw: String): List<Pair<String, String>> {
        val element = Json.parseToJsonElement(raw)
        val obj = element.jsonObject
        return obj.entries.map { (key, value) ->
            val text = when (value) {
                is JsonPrimitive -> value.content
                else -> value.toString()
            }
            key to text
        }
    }
}
