package com.andharness.ru.data.tools.documents

import com.rtfparserkit.converter.text.StringTextConverter
import com.rtfparserkit.parser.RtfStreamSource
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileInputStream
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser

/**
 * Android-free text extraction for the local RAG index (update.md idea #4).
 *
 * It reuses the same container-agnostic helpers as the Documents tooling ([DocumentSupport],
 * [DocumentFormatDetector], PdfBox, commons-csv, rtfparserkit) so `rag_index` reads exactly the
 * formats `doc_read` supports. Unlike the interactive tool it caps the extracted text
 * ([MAX_EXTRACT_CHARS]) and reports truncation honestly instead of failing, because an index is a
 * best-effort summary — not a full copy of the file.
 *
 * Official references:
 * - PdfBox (Android port) `PDFTextStripper` — https://github.com/TomRoush/PdfBox-Android
 * - commons-csv `CSVParser` — https://commons.apache.org/proper/commons-csv/
 * - Android `java.io.File` — https://developer.android.com/reference/java/io/File
 */
object DocumentTextExtractor {

    /** Characters kept per file; a longer document is truncated and flagged, never silently stored. */
    const val MAX_EXTRACT_CHARS = 2_000_000

    /** Outcome of an extraction: either the text (possibly truncated) or an honest reason. */
    sealed interface Extracted {
        data class Success(
            val format: DocumentFormat,
            val text: String,
            val truncated: Boolean
        ) : Extracted

        data class Failure(val format: DocumentFormat, val message: String) : Extracted
    }

    /** Formats that carry text worth indexing; legacy/binary containers are skipped by `rag_index`. */
    fun isIndexable(format: DocumentFormat): Boolean = when (format) {
        DocumentFormat.LEGACY_DOC, DocumentFormat.LEGACY_XLS, DocumentFormat.LEGACY_PPT,
        DocumentFormat.BINARY_UNKNOWN, DocumentFormat.ZIP_UNKNOWN -> false
        else -> true
    }

    /**
     * Extracts the text of [file] (optionally a single PDF page / PPTX slide / XLSX-ODS sheet).
     * The result is capped at [maxChars]; when the text is longer, [Extracted.Success.truncated] is
     * true and only the first [maxChars] characters are returned.
     *
     * **Never throws.** A corrupt or odd file can make an individual extractor raise (a malformed
     * `word/document.xml` → `XmlPullParserException`, a truncated/encrypted PDF → `IOException` from
     * PdfBox's `PDDocument.load`, a broken delimited file → `UncheckedIOException` from commons-csv,
     * …); such a document must not abort the caller's pass, so the failure is reported as an honest
     * [Extracted.Failure] carrying the format and the exception reason.
     */
    fun extract(
        file: File,
        page: Int? = null,
        sheet: String? = null,
        maxChars: Int = MAX_EXTRACT_CHARS
    ): Extracted {
        val format = DocumentFormatDetector.detect(file)
        val cap = maxChars.coerceAtLeast(1)
        val raw = try {
            extractFormat(file, format, page, sheet)
        } catch (e: Exception) {
            return Extracted.Failure(format, describeThrowable(e))
        }
        return when (raw) {
            is Extracted.Failure -> raw
            is Extracted.Success -> {
                val text = raw.text
                if (text.length > cap) {
                    Extracted.Success(format, text.substring(0, cap), truncated = true)
                } else {
                    Extracted.Success(format, text, truncated = false)
                }
            }
        }
    }

    /** One-line description of a throwable for an honest failure message (`Type: message`). */
    private fun describeThrowable(e: Throwable): String {
        val message = e.message?.takeIf { it.isNotBlank() }
        return if (message == null) e.javaClass.name else "${e.javaClass.name}: $message"
    }

    private fun extractFormat(
        file: File,
        format: DocumentFormat,
        page: Int?,
        sheet: String?
    ): Extracted = when (format) {
        DocumentFormat.PDF -> extractPdf(file, format, page)
        DocumentFormat.DOCX -> extractDocx(file, format)
        DocumentFormat.XLSX -> extractXlsx(file, format, sheet)
        DocumentFormat.PPTX -> extractPptx(file, format, page)
        DocumentFormat.ODT, DocumentFormat.ODP -> {
            val content = DocumentSupport.readZipEntryText(file, "content.xml")
                ?: return Extracted.Failure(format, "Внутренний документ content.xml не найден.")
            Extracted.Success(format, DocumentSupport.odfText(content), truncated = false)
        }
        DocumentFormat.ODS -> extractOds(file, format, sheet)
        DocumentFormat.RTF -> extractRtf(file, format)
        DocumentFormat.CSV -> Extracted.Success(format, extractDelimited(file, ','), truncated = false)
        DocumentFormat.TSV -> Extracted.Success(format, extractDelimited(file, '\t'), truncated = false)
        DocumentFormat.JSON, DocumentFormat.XML, DocumentFormat.HTML,
        DocumentFormat.MARKDOWN, DocumentFormat.TEXT -> {
            val text = DocumentSupport.readFileText(file)
                ?: return Extracted.Failure(format, "Не удалось прочитать файл как текст.")
            Extracted.Success(format, text, truncated = false)
        }
        else -> Extracted.Failure(
            format,
            "Формат ${format.name} не поддерживается для извлечения текста."
        )
    }

    private fun extractPdf(file: File, format: DocumentFormat, page: Int?): Extracted =
        PDDocument.load(file).use { document ->
            val total = document.numberOfPages
            if (page != null && (page < 1 || page > total)) {
                Extracted.Failure(format, "Страница $page вне диапазона: в документе $total стр.")
            } else {
                val stripper = PDFTextStripper()
                if (page != null) {
                    stripper.startPage = page
                    stripper.endPage = page
                }
                Extracted.Success(format, stripper.getText(document), truncated = false)
            }
        }

    private fun extractDocx(file: File, format: DocumentFormat): Extracted {
        val xml = DocumentSupport.readZipEntryText(file, "word/document.xml")
            ?: return Extracted.Failure(format, "Внутренний документ word/document.xml не найден.")
        return Extracted.Success(format, DocumentSupport.docxText(xml), truncated = false)
    }

    private fun extractXlsx(file: File, format: DocumentFormat, sheet: String?): Extracted {
        val workbook = DocumentSupport.readZipEntryText(file, "xl/workbook.xml")
            ?: return Extracted.Failure(format, "Внутренний документ xl/workbook.xml не найден.")
        val names = DocumentSupport.xlsxSheetNames(workbook)
        if (names.isEmpty()) return Extracted.Failure(format, "В книге не найдено ни одного листа.")
        val index = resolveSheetIndex(names, sheet)
            ?: return Extracted.Failure(
                format,
                "Лист «$sheet» не найден. Доступные листы: ${names.joinToString(", ")}."
            )
        val shared = DocumentSupport.readZipEntryText(file, "xl/sharedStrings.xml")
            ?.let { DocumentSupport.xlsxSharedStrings(it) }
            ?: emptyList()
        val entry = xlsxSheetEntry(file, workbook, index)
            ?: return Extracted.Failure(
                format,
                "Не найден XML листа «${names[index]}» (ожидался xl/worksheets/sheet${index + 1}.xml)."
            )
        val sheetXml = DocumentSupport.readZipEntryText(file, entry)
            ?: return Extracted.Failure(format, "Не найден XML листа «${names[index]}» ($entry).")
        return Extracted.Success(format, DocumentSupport.xlsxSheetText(sheetXml, shared), false)
    }

    /** Resolves the sheet part through `xl/_rels/workbook.xml.rels`, falling back to the convention. */
    private fun xlsxSheetEntry(file: File, workbookXml: String, index: Int): String? {
        val relIds = DocumentSupport.xmlAttributeValues(workbookXml, "sheet", "id")
        val relId = relIds.getOrNull(index)
        if (relId != null) {
            val rels = DocumentSupport.readZipEntryText(file, "xl/_rels/workbook.xml.rels")
            if (rels != null) {
                val ids = DocumentSupport.xmlAttributeValues(rels, "Relationship", "Id")
                val targets = DocumentSupport.xmlAttributeValues(rels, "Relationship", "Target")
                val target = ids.indexOf(relId).takeIf { it >= 0 }?.let { targets.getOrNull(it) }
                if (!target.isNullOrBlank()) {
                    val normalized = target.removePrefix("/").removePrefix("./")
                    val entry = if (target.startsWith("/")) normalized else "xl/$normalized"
                    if (DocumentSupport.zipEntryNames(file).contains(entry)) return entry
                }
            }
        }
        val fallback = "xl/worksheets/sheet${index + 1}.xml"
        return fallback.takeIf { DocumentSupport.zipEntryNames(file).contains(it) }
    }

    private fun extractPptx(file: File, format: DocumentFormat, page: Int?): Extracted {
        val slides = DocumentSupport.sortedSlideEntries(DocumentSupport.zipEntryNames(file))
        if (slides.isEmpty()) {
            return Extracted.Failure(
                format,
                "В презентации не найдено ни одного слайда (ppt/slides/slideN.xml)."
            )
        }
        val selected = if (page == null) {
            slides
        } else {
            if (page < 1 || page > slides.size) {
                return Extracted.Failure(
                    format,
                    "Слайд $page вне диапазона: в презентации ${slides.size} сл."
                )
            }
            listOf(slides[page - 1])
        }
        val builder = StringBuilder()
        for (entry in selected) {
            val xml = DocumentSupport.readZipEntryText(file, entry)
                ?: return Extracted.Failure(format, "Не удалось прочитать слайд $entry.")
            if (selected.size > 1) {
                builder.append("--- Слайд ${slides.indexOf(entry) + 1} ---\n")
            }
            builder.append(DocumentSupport.extractRunText(xml, "t", "p"))
            builder.append('\n')
        }
        return Extracted.Success(format, builder.toString().trim(), truncated = false)
    }

    private fun extractOds(file: File, format: DocumentFormat, sheet: String?): Extracted {
        val content = DocumentSupport.readZipEntryText(file, "content.xml")
            ?: return Extracted.Failure(format, "Внутренний документ content.xml не найден.")
        val names = DocumentSupport.odsSheetNames(content)
        if (names.isEmpty()) return Extracted.Failure(format, "В таблице не найдено ни одного листа.")
        val index = resolveSheetIndex(names, sheet)
            ?: return Extracted.Failure(
                format,
                "Лист «$sheet» не найден. Доступные листы: ${names.joinToString(", ")}."
            )
        val text = DocumentSupport.odsSheetText(content, names[index])
            ?: return Extracted.Failure(format, "Не удалось прочитать лист «${names[index]}».")
        return Extracted.Success(format, text, truncated = false)
    }

    private fun extractRtf(file: File, format: DocumentFormat): Extracted {
        val text = FileInputStream(file).use { input ->
            val converter = StringTextConverter()
            converter.convert(RtfStreamSource(input))
            converter.text ?: ""
        }
        return Extracted.Success(format, text, truncated = false)
    }

    /** Renders a delimited text file as tab-separated rows. */
    private fun extractDelimited(file: File, delimiter: Char): String {
        val format = CSVFormat.Builder.create(CSVFormat.DEFAULT)
            .setDelimiter(delimiter)
            .get()
        return CSVParser.parse(file, Charsets.UTF_8, format).use { parser ->
            parser.records.joinToString("\n") { record -> record.joinToString("\t") }
        }
    }

    /** 1-based numeric or case-insensitive named sheet selection; `null` → the first entry. */
    private fun resolveSheetIndex(names: List<String>, requested: String?): Int? {
        if (names.isEmpty()) return null
        val trimmed = requested?.trim()?.takeIf { it.isNotEmpty() } ?: return 0
        trimmed.toIntOrNull()?.let { number -> return if (number in 1..names.size) number - 1 else null }
        return names.indexOfFirst { it.equals(trimmed, ignoreCase = true) }.takeIf { it >= 0 }
    }
}
