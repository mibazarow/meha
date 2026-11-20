package com.andharness.ru.data.tools.documents

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.serialization.json.Json
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Pure byte-transform side of `doc_edit`/`xlsx_set_cell`: builds appended/replaced bytes for the
 * supported containers and verifies the change through the same extraction path `doc_read` uses.
 */
internal class DocumentEditor(
    private val inspector: DocumentInspector,
    private val writer: PdfWriter,
) {

    /** Builds the rewritten bytes for `mode=append`; unsupported formats fail with an honest error. */
    fun buildAppendedBytes(file: File, format: DocumentFormat, content: String): ByteArray =
        when (format) {
            DocumentFormat.TEXT, DocumentFormat.MARKDOWN, DocumentFormat.XML, DocumentFormat.HTML ->
                appendPlainText(file, content)
            DocumentFormat.JSON -> {
                val existing = DocumentSupport.readFileText(file)
                    ?: throw EditException("Не удалось прочитать файл как текст.")
                val result = appendWithSeparator(existing, content)
                try {
                    Json.parseToJsonElement(result)
                } catch (e: Exception) {
                    throw EditException("Результат не является корректным JSON (${e.message}).")
                }
                result.toByteArray(Charsets.UTF_8)
            }
            DocumentFormat.CSV, DocumentFormat.TSV -> {
                val delimiter = if (format == DocumentFormat.TSV) '\t' else ','
                val rows = DocumentWriter.parseTableText(content)
                if (rows.isEmpty()) throw EditException("Содержимое не содержит строк.")
                DocumentWriter.csv(readDelimitedRows(file, delimiter) + rows, delimiter)
            }
            DocumentFormat.RTF -> {
                val existing = DocumentSupport.readFileText(file)
                    ?: throw EditException("Не удалось прочитать RTF как текст.")
                DocumentWriter.appendRtf(existing, content).toByteArray(Charsets.UTF_8)
            }
            DocumentFormat.DOCX -> {
                val xml = DocumentSupport.readZipEntryText(file, "word/document.xml")
                    ?: throw EditException("Внутренний документ word/document.xml не найден.")
                repackage(file, "word/document.xml", DocumentWriter.appendDocxText(xml, content))
            }
            DocumentFormat.ODT -> {
                val xml = DocumentSupport.readZipEntryText(file, "content.xml")
                    ?: throw EditException("Внутренний документ content.xml не найден.")
                repackage(file, "content.xml", DocumentWriter.appendOdtText(xml, content))
            }
            DocumentFormat.XLSX -> {
                val workbook = DocumentSupport.readZipEntryText(file, "xl/workbook.xml")
                    ?: throw EditException("Внутренний документ xl/workbook.xml не найден.")
                val entry = inspector.xlsxSheetEntry(file, workbook, 0)
                    ?: throw EditException("Не найден лист для дописывания строк.")
                val xml = DocumentSupport.readZipEntryText(file, entry)
                    ?: throw EditException("Не удалось прочитать XML листа ($entry).")
                val rows = DocumentWriter.parseTableText(content)
                if (rows.isEmpty()) throw EditException("Содержимое не содержит строк.")
                repackage(file, entry, DocumentWriter.appendXlsxRows(xml, rows))
            }
            DocumentFormat.ODS -> {
                val xml = DocumentSupport.readZipEntryText(file, "content.xml")
                    ?: throw EditException("Внутренний документ content.xml не найден.")
                val rows = DocumentWriter.parseTableText(content)
                if (rows.isEmpty()) throw EditException("Содержимое не содержит строк.")
                repackage(file, "content.xml", DocumentWriter.appendOdsRows(xml, rows))
            }
            DocumentFormat.PDF -> appendPdfPage(file, content)
            DocumentFormat.PPTX, DocumentFormat.ODP,
            DocumentFormat.LEGACY_DOC, DocumentFormat.LEGACY_XLS, DocumentFormat.LEGACY_PPT,
            DocumentFormat.BINARY_UNKNOWN, DocumentFormat.ZIP_UNKNOWN ->
                throw EditException("Формат ${format.name} не поддерживается для дописывания.")
        }

    /** Builds the rewritten bytes for `mode=replace`; unsupported formats fail honestly. */
    fun buildReplacedBytes(
        file: File,
        format: DocumentFormat,
        find: String,
        content: String,
    ): ByteArray = when (format) {
        DocumentFormat.TEXT, DocumentFormat.MARKDOWN, DocumentFormat.XML, DocumentFormat.HTML,
        DocumentFormat.RTF -> {
            val existing = DocumentSupport.readFileText(file)
                ?: throw EditException("Не удалось прочитать файл как текст.")
            if (!existing.contains(find)) throw EditException("Искомый текст не найден: «$find».")
            existing.replace(find, content).toByteArray(Charsets.UTF_8)
        }
        DocumentFormat.JSON -> {
            val existing = DocumentSupport.readFileText(file)
                ?: throw EditException("Не удалось прочитать файл как текст.")
            if (!existing.contains(find)) throw EditException("Искомый текст не найден: «$find».")
            val result = existing.replace(find, content)
            try {
                Json.parseToJsonElement(result)
            } catch (e: Exception) {
                throw EditException("Результат не является корректным JSON (${e.message}).")
            }
            result.toByteArray(Charsets.UTF_8)
        }
        DocumentFormat.CSV, DocumentFormat.TSV -> {
            val delimiter = if (format == DocumentFormat.TSV) '\t' else ','
            var changed = false
            val rows = readDelimitedRows(file, delimiter).map { row ->
                row.map { cell ->
                    if (cell.contains(find)) {
                        changed = true
                        cell.replace(find, content)
                    } else {
                        cell
                    }
                }
            }
            if (!changed) throw EditException("Искомый текст не найден: «$find».")
            DocumentWriter.csv(rows, delimiter)
        }
        DocumentFormat.DOCX -> {
            val xml = DocumentSupport.readZipEntryText(file, "word/document.xml")
                ?: throw EditException("Внутренний документ word/document.xml не найден.")
            val updated = DocumentWriter.replaceInTextNodes(xml, find, content, listOf("t"))
            if (updated == xml) throw EditException("Искомый текст не найден в документе: «$find».")
            repackage(file, "word/document.xml", updated)
        }
        DocumentFormat.ODT -> {
            val xml = DocumentSupport.readZipEntryText(file, "content.xml")
                ?: throw EditException("Внутренний документ content.xml не найден.")
            val updated = DocumentWriter.replaceInTextNodes(xml, find, content, listOf("span", "p"))
            if (updated == xml) throw EditException("Искомый текст не найден в документе: «$find».")
            repackage(file, "content.xml", updated)
        }
        DocumentFormat.PDF, DocumentFormat.XLSX, DocumentFormat.ODS, DocumentFormat.PPTX ->
            throw EditException(
                "Формат ${format.name} не поддерживается для замены текста (ограничение реализации)."
            )
        else ->
            throw EditException("Формат ${format.name} не поддерживается для замены текста.")
    }

    /** Reads a text file and appends [content], inserting a separating newline when needed. */
    fun appendPlainText(file: File, content: String): ByteArray {
        val existing = DocumentSupport.readFileText(file)
            ?: throw EditException("Не удалось прочитать файл как текст.")
        return appendWithSeparator(existing, content).toByteArray(Charsets.UTF_8)
    }

    fun appendWithSeparator(existing: String, content: String): String {
        if (existing.isEmpty()) return content
        val separator = if (existing.endsWith("\n") || existing.endsWith("\r")) "" else "\n"
        return existing + separator + content
    }

    /** Replaces one part of a ZIP container and returns the repackaged bytes. */
    fun repackage(file: File, entry: String, xml: String): ByteArray =
        DocumentSupport.rewriteZip(file, mapOf(entry to xml.toByteArray(Charsets.UTF_8)))
            ?: throw EditException("Не удалось перепаковать контейнер (${file.name}).")

    fun readDelimitedRows(file: File, delimiter: Char): List<List<String>> {
        val format = CSVFormat.Builder.create(CSVFormat.DEFAULT)
            .setDelimiter(delimiter)
            .get()
        return CSVParser.parse(file, Charsets.UTF_8, format).use { parser ->
            parser.records.map { record -> record.toList() }
        }
    }

    /** Opens an existing PDF and appends one (or more) A4 page(s) carrying [text]. */
    fun appendPdfPage(file: File, text: String): ByteArray {
        val fontSize = 12f
        val leading = 16f
        val margin = 56f
        val maxWidth = PDRectangle.A4.width - 2 * margin
        val linesPerPage = ((PDRectangle.A4.height - 2 * margin) / leading).toInt().coerceAtLeast(1)

        val document = PDDocument.load(file)
        try {
            // The font must be loaded into THIS document (the embedded subset lives in it), so it is
            // resolved after the existing file was opened but before any text is written.
            val font = writer.pdfFont(document)
            val lines = mutableListOf<String>()
            for (raw in text.split('\n')) {
                lines += writer.wrapPdfLine(writer.sanitizePdfText(raw.removeSuffix("\r"), font), font, fontSize, maxWidth)
            }

            for (pageLines in lines.chunked(linesPerPage)) {
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(font, fontSize)
                    stream.newLineAtOffset(margin, PDRectangle.A4.height - margin)
                    stream.setLeading(leading)
                    var first = true
                    for (line in pageLines) {
                        if (!first) stream.newLineAtOffset(0f, -leading)
                        stream.showText(line)
                        first = false
                    }
                    stream.endText()
                }
            }
            val out = ByteArrayOutputStream()
            document.save(out)
            return out.toByteArray()
        } finally {
            document.close()
        }
    }

    /**
     * Re-reads the edited file through the `doc_read` extraction path and checks the change is
     * present (append) / the old text is gone and the new one is there (replace). Returns an error
     * message, or `null` when the verification passes.
     */
    fun verifyEdit(
        file: File,
        format: DocumentFormat,
        mode: String,
        content: String,
        find: String?,
        originalPages: Int,
    ): String? {
        if (!file.isFile) return "файл недоступен после записи."
        if (file.length() == 0L) return "файл пуст после записи."

        if (format == DocumentFormat.PDF) {
            val pages = try {
                PDDocument.load(file).use { it.numberOfPages }
            } catch (e: Exception) {
                return "не удалось повторно открыть PDF: ${e.message}"
            }
            if (pages < originalPages + 1) {
                return "новая страница не добавилась (было $originalPages, стало $pages)."
            }
            val extracted = try {
                inspector.extract(file, format, page = null, sheet = null)
            } catch (e: Exception) {
                return "не удалось повторно прочитать PDF: ${e.message}"
            }
            if (extracted is Extraction.Failure) return extracted.message
            val actual = (extracted as Extraction.Success).text.filterNot { it.isWhitespace() }
            // With the embedded Unicode font the text survives verbatim; if the asset was missing the
            // standard-14 fallback wrote `?` for non-Latin characters, so both forms are accepted.
            val unicodeExpected = content.filterNot { it.isWhitespace() }
            val winAnsiExpected = writer.sanitizePdfText(content, PDType1Font.HELVETICA).filterNot { it.isWhitespace() }
            return if (content.isBlank() || actual.contains(unicodeExpected) || actual.contains(winAnsiExpected)) {
                null
            } else {
                "дописанный текст не найден в PDF после записи."
            }
        }

        val extracted = try {
            inspector.extract(file, format, page = null, sheet = null)
        } catch (e: Exception) {
            return "не удалось повторно прочитать файл: ${e.message}"
        }
        if (extracted is Extraction.Failure) return extracted.message
        val text = (extracted as Extraction.Success).text

        if (format in DocumentInspector.TABULAR_FORMATS) return verifyTabularEdit(text, mode, content, find)

        val actual = inspector.normalizedText(text)
        if (mode == "append") {
            val expected = inspector.normalizedText(content).trim()
            if (expected.isNotEmpty() && !actual.contains(expected)) {
                return "дописанный текст не найден при повторном чтении."
            }
            return null
        }
        if (!find.isNullOrEmpty() && actual.contains(find)) {
            return "искомый текст всё ещё присутствует после замены."
        }
        val expected = inspector.normalizedText(content).trim()
        if (expected.isNotEmpty() && !actual.contains(expected)) {
            return "заменённый текст не найден при повторном чтении."
        }
        return null
    }

    fun verifyTabularEdit(text: String, mode: String, content: String, find: String?): String? {
        val actualRows = DocumentWriter.parseTableText(text)
        if (mode == "append") {
            val appended = DocumentWriter.parseTableText(content)
            if (appended.isEmpty()) return null
            val tail = actualRows.takeLast(appended.size)
            return if (tail == appended) null else "дописанные строки не совпадают с ожидаемыми."
        }
        val cells = actualRows.flatten()
        if (!find.isNullOrEmpty() && cells.any { it.contains(find) }) {
            return "искомый текст всё ещё присутствует после замены."
        }
        return if (cells.none { it.contains(content) }) {
            "заменённый текст не найден при повторном чтении."
        } else {
            null
        }
    }

}
