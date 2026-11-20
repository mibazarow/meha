package com.andharness.ru.data.tools.documents

import com.rtfparserkit.converter.text.StringTextConverter
import com.rtfparserkit.parser.RtfStreamSource
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import java.io.File
import java.io.FileInputStream

/**
 * Read-only document inspection: format-specific text extraction and structural descriptions used
 * by `doc_info`, `doc_read` and the post-write verification of the write tools.
 */
internal class DocumentInspector(
) {

    /**
     * Re-reads the written workbook and returns the value of [cellRef] through
     * [DocumentSupport.xlsxCellValue]. Returns an error message on any mismatch, or `null` when the
     * stored value equals [value].
     */
    fun verifyXlsxCell(file: File, entry: String, cellRef: String, value: String): String? {
        if (!file.isFile) return "файл недоступен после записи."
        if (file.length() == 0L) return "файл пуст после записи."
        val xml = DocumentSupport.readZipEntryText(file, entry)
            ?: return "не удалось повторно прочитать XML листа ($entry)."
        val shared = DocumentSupport.readZipEntryText(file, "xl/sharedStrings.xml")
            ?.let { DocumentSupport.xlsxSharedStrings(it) }
            ?: emptyList()
        val actual = DocumentSupport.xlsxCellValue(xml, shared, cellRef)
            ?: return "ячейка $cellRef не найдена после записи."
        return if (actual == value) {
            null
        } else {
            "значение ячейки $cellRef после записи: «$actual», ожидалось «$value»."
        }
    }

    /** Re-reads the freshly created file through the `doc_read` path; returns an error or `null`. */
    fun verifyCreated(file: File, format: DocumentFormat, content: String): String? {
        if (!file.isFile) return "созданный файл недоступен."
        if (file.length() == 0L) return "созданный файл пуст."

        val detected = DocumentFormatDetector.detect(file)
        if (detected != format) {
            return "формат созданного файла определён как ${detected.name}, ожидался ${format.name}."
        }

        if (format == DocumentFormat.PDF) {
            val pages = PDDocument.load(file).use { it.numberOfPages }
            return if (pages > 0) null else "в созданном PDF нет ни одной страницы."
        }

        val extracted = try {
            extract(file, format, page = null, sheet = null)
        } catch (e: Exception) {
            return "не удалось повторно прочитать созданный файл: ${e.message}"
        }
        if (extracted is Extraction.Failure) return extracted.message
        val text = (extracted as Extraction.Success).text
        if (text.isBlank()) {
            return if (content.isBlank()) null else "созданный файл не содержит текста."
        }

        val expected = normalizedText(content)
        val actual = normalizedText(text)
        return if (format in TABULAR_FORMATS) {
            if (DocumentWriter.parseTableText(actual) == DocumentWriter.parseTableText(expected)) {
                null
            } else {
                "содержимое таблицы не совпадает с исходным."
            }
        } else if (actual.trim() == expected.trim()) {
            null
        } else {
            "текст созданного файла не совпадает с исходным."
        }
    }

    /** Line-ending/indentation-insensitive form used when comparing input with extracted text. */
    fun normalizedText(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')
            .split('\n')
            .joinToString("\n") { it.trimEnd() }
            .trimEnd('\n')

    /** Format-specific text extraction; every unsupported/missing part is an honest failure. */
    fun extract(file: File, format: DocumentFormat, page: Int?, sheet: String?): Extraction {
        return when (format) {
            DocumentFormat.PDF -> extractPdf(file, page)
            DocumentFormat.DOCX -> extractDocx(file)
            DocumentFormat.XLSX -> extractXlsx(file, sheet)
            DocumentFormat.PPTX -> extractPptx(file, page)
            DocumentFormat.ODT, DocumentFormat.ODP -> {
                val content = DocumentSupport.readZipEntryText(file, "content.xml")
                    ?: return Extraction.Failure("Внутренний документ content.xml не найден.")
                Extraction.Success(DocumentSupport.odfText(content))
            }
            DocumentFormat.ODS -> extractOds(file, sheet)
            DocumentFormat.RTF -> extractRtf(file)
            DocumentFormat.CSV -> Extraction.Success(extractDelimited(file, ','))
            DocumentFormat.TSV -> Extraction.Success(extractDelimited(file, '\t'))
            DocumentFormat.JSON, DocumentFormat.XML, DocumentFormat.HTML,
            DocumentFormat.MARKDOWN, DocumentFormat.TEXT -> {
                val text = DocumentSupport.readFileText(file)
                    ?: return Extraction.Failure("Не удалось прочитать файл как текст.")
                Extraction.Success(text)
            }
            else -> Extraction.Failure("Формат ${format.name} не поддерживается для чтения текста.")
        }
    }

    fun extractPdf(file: File, page: Int?): Extraction {
        return PDDocument.load(file).use { document ->
            val total = document.numberOfPages
            if (page != null && (page < 1 || page > total)) {
                Extraction.Failure("Страница $page вне диапазона: в документе $total стр.")
            } else {
                val stripper = PDFTextStripper()
                if (page != null) {
                    stripper.startPage = page
                    stripper.endPage = page
                }
                Extraction.Success(stripper.getText(document))
            }
        }
    }

    fun extractDocx(file: File): Extraction {
        val xml = DocumentSupport.readZipEntryText(file, "word/document.xml")
            ?: return Extraction.Failure("Внутренний документ word/document.xml не найден.")
        return Extraction.Success(DocumentSupport.docxText(xml))
    }

    fun extractXlsx(file: File, sheet: String?): Extraction {
        val workbook = DocumentSupport.readZipEntryText(file, "xl/workbook.xml")
            ?: return Extraction.Failure("Внутренний документ xl/workbook.xml не найден.")
        val names = DocumentSupport.xlsxSheetNames(workbook)
        if (names.isEmpty()) {
            return Extraction.Failure("В книге не найдено ни одного листа.")
        }
        val index = resolveSheetIndex(names, sheet)
            ?: return Extraction.Failure(
                "Лист «$sheet» не найден. Доступные листы: ${names.joinToString(", ")}."
            )
        val shared = DocumentSupport.readZipEntryText(file, "xl/sharedStrings.xml")
            ?.let { DocumentSupport.xlsxSharedStrings(it) }
            ?: emptyList()
        val entry = xlsxSheetEntry(file, workbook, index)
            ?: return Extraction.Failure(
                "Не найден XML листа «${names[index]}» (ожидался xl/worksheets/sheet${index + 1}.xml)."
            )
        val sheetXml = DocumentSupport.readZipEntryText(file, entry)
            ?: return Extraction.Failure("Не найден XML листа «${names[index]}» ($entry).")
        return Extraction.Success(DocumentSupport.xlsxSheetText(sheetXml, shared))
    }

    /** Resolves the sheet part through `xl/_rels/workbook.xml.rels`, falling back to the convention. */
    fun xlsxSheetEntry(file: File, workbookXml: String, index: Int): String? {
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

    fun extractPptx(file: File, page: Int?): Extraction {
        val slides = DocumentSupport.sortedSlideEntries(DocumentSupport.zipEntryNames(file))
        if (slides.isEmpty()) {
            return Extraction.Failure("В презентации не найдено ни одного слайда (ppt/slides/slideN.xml).")
        }
        val selected = if (page == null) {
            slides
        } else {
            if (page < 1 || page > slides.size) {
                return Extraction.Failure("Слайд $page вне диапазона: в презентации ${slides.size} сл.")
            }
            listOf(slides[page - 1])
        }
        val builder = StringBuilder()
        for (entry in selected) {
            val xml = DocumentSupport.readZipEntryText(file, entry)
                ?: return Extraction.Failure("Не удалось прочитать слайд $entry.")
            if (selected.size > 1) {
                builder.append("--- Слайд ${slides.indexOf(entry) + 1} ---\n")
            }
            builder.append(DocumentSupport.extractRunText(xml, "t", "p"))
            builder.append('\n')
        }
        return Extraction.Success(builder.toString().trim())
    }

    fun extractOds(file: File, sheet: String?): Extraction {
        val content = DocumentSupport.readZipEntryText(file, "content.xml")
            ?: return Extraction.Failure("Внутренний документ content.xml не найден.")
        val names = DocumentSupport.odsSheetNames(content)
        if (names.isEmpty()) {
            return Extraction.Failure("В таблице не найдено ни одного листа.")
        }
        val index = resolveSheetIndex(names, sheet)
            ?: return Extraction.Failure(
                "Лист «$sheet» не найден. Доступные листы: ${names.joinToString(", ")}."
            )
        val text = DocumentSupport.odsSheetText(content, names[index])
            ?: return Extraction.Failure("Не удалось прочитать лист «${names[index]}».")
        return Extraction.Success(text)
    }

    fun extractRtf(file: File): Extraction {
        val text = FileInputStream(file).use { input ->
            val converter = StringTextConverter()
            converter.convert(RtfStreamSource(input))
            converter.text ?: ""
        }
        return Extraction.Success(text)
    }

    /** Renders a delimited text file as tab-separated rows (the input delimiter is [delimiter]). */
    fun extractDelimited(file: File, delimiter: Char): String {
        val format = CSVFormat.Builder.create(CSVFormat.DEFAULT)
            .setDelimiter(delimiter)
            .get()
        return CSVParser.parse(file, Charsets.UTF_8, format).use { parser ->
            parser.records.joinToString("\n") { record -> record.joinToString("\t") }
        }
    }

    /** Parses a delimited text file into structured rows of cells (commons-csv, RFC 4180). */
    fun extractDelimitedRows(file: File, delimiter: Char): List<List<String>> {
        val format = CSVFormat.Builder.create(CSVFormat.DEFAULT)
            .setDelimiter(delimiter)
            .get()
        return CSVParser.parse(file, Charsets.UTF_8, format).use { parser ->
            parser.records.map { record -> record.toList() }
        }
    }

    /**
     * Structured cells of a tabular file (XLSX/ODS sheet, or CSV/TSV), or `null` when the format is
     * not tabular or the requested sheet is missing. Never throws.
     */
    fun extractTableRows(file: File, format: DocumentFormat, sheet: String?): List<List<String>>? = try {
        when (format) {
            DocumentFormat.XLSX -> {
                val workbook = DocumentSupport.readZipEntryText(file, "xl/workbook.xml")
                val index = workbook?.let { resolveSheetIndex(DocumentSupport.xlsxSheetNames(it), sheet) }
                val entry = if (workbook != null && index != null) xlsxSheetEntry(file, workbook, index) else null
                val xml = entry?.let { DocumentSupport.readZipEntryText(file, it) }
                if (xml == null) {
                    null
                } else {
                    val shared = DocumentSupport.readZipEntryText(file, "xl/sharedStrings.xml")
                        ?.let { DocumentSupport.xlsxSharedStrings(it) } ?: emptyList()
                    DocumentSupport.xlsxSheetRows(xml, shared)
                }
            }
            DocumentFormat.ODS -> DocumentSupport.readZipEntryText(file, "content.xml")
                ?.let { DocumentSupport.odsSheetRows(it, sheet) }
            DocumentFormat.CSV -> extractDelimitedRows(file, ',')
            DocumentFormat.TSV -> extractDelimitedRows(file, '\t')
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    /** Every sheet of a workbook as `(name, rows)`, in workbook order; empty when unsupported. */
    fun extractAllSheets(file: File, format: DocumentFormat): List<Pair<String, List<List<String>>>> = try {
        when (format) {
            DocumentFormat.XLSX -> {
                val workbook = DocumentSupport.readZipEntryText(file, "xl/workbook.xml") ?: return emptyList()
                val names = DocumentSupport.xlsxSheetNames(workbook)
                val shared = DocumentSupport.readZipEntryText(file, "xl/sharedStrings.xml")
                    ?.let { DocumentSupport.xlsxSharedStrings(it) } ?: emptyList()
                names.mapIndexedNotNull { index, name ->
                    val entry = xlsxSheetEntry(file, workbook, index) ?: return@mapIndexedNotNull null
                    val xml = DocumentSupport.readZipEntryText(file, entry) ?: return@mapIndexedNotNull null
                    name to DocumentSupport.xlsxSheetRows(xml, shared)
                }
            }
            DocumentFormat.ODS -> {
                val content = DocumentSupport.readZipEntryText(file, "content.xml") ?: return emptyList()
                DocumentSupport.odsSheetNames(content).mapNotNull { name ->
                    DocumentSupport.odsSheetRows(content, name)?.let { name to it }
                }
            }
            else -> emptyList()
        }
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Slide content of a PPTX/ODP as [PptxWriter.Slide] models (first paragraph = title), used by the
     * presentation merge/split tools. Empty when the format is not a presentation or is unreadable.
     */
    fun extractSlideModels(file: File, format: DocumentFormat): List<PptxWriter.Slide> = try {
        when (format) {
            DocumentFormat.PPTX -> DocumentSupport.sortedSlideEntries(DocumentSupport.zipEntryNames(file))
                .mapNotNull { entry ->
                    val xml = DocumentSupport.readZipEntryText(file, entry) ?: return@mapNotNull null
                    toSlide(DocumentSupport.extractRunText(xml, "t", "p").split('\n'))
                }
            DocumentFormat.ODP -> DocumentSupport.readZipEntryText(file, "content.xml")
                ?.let { DocumentSupport.odpSlides(it) }
                ?.map { toSlide(it) } ?: emptyList()
            else -> emptyList()
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun toSlide(paragraphs: List<String>): PptxWriter.Slide {
        val cleaned = paragraphs.map { it.trim() }.filter { it.isNotEmpty() }
        return PptxWriter.Slide(
            title = cleaned.firstOrNull(),
            lines = cleaned.drop(1),
        )
    }

    /**
     * Resolves the worksheet the caller asked for ([requested]; `null`/empty means the first sheet)
     * to its XML entry and text. Every failing step returns [SheetTarget.Failure] with the message
     * the worksheet-editing tools surface, so all of them resolve a sheet the same way.
     */
    fun resolveSheetTarget(file: File, requested: String?): SheetTarget {
        val workbook = DocumentSupport.readZipEntryText(file, "xl/workbook.xml")
            ?: return SheetTarget.Failure("Внутренний документ xl/workbook.xml не найден.")
        val names = DocumentSupport.xlsxSheetNames(workbook)
        if (names.isEmpty()) return SheetTarget.Failure("В книге не найдено ни одного листа.")
        val index = resolveSheetIndex(names, requested)
            ?: return SheetTarget.Failure(
                "Лист «$requested» не найден. Доступные листы: ${names.joinToString(", ")}."
            )
        val entry = xlsxSheetEntry(file, workbook, index)
            ?: return SheetTarget.Failure("Не найден XML листа «${names[index]}».")
        val xml = DocumentSupport.readZipEntryText(file, entry)
            ?: return SheetTarget.Failure("Не удалось прочитать XML листа ($entry).")
        return SheetTarget.Ok(names = names, index = index, entry = entry, xml = xml)
    }

    /** 1-based numeric or case-insensitive named sheet selection; `null` → the first entry. */
    fun resolveSheetIndex(names: List<String>, requested: String?): Int? {
        if (names.isEmpty()) return null
        val trimmed = requested?.trim()?.takeIf { it.isNotEmpty() } ?: return 0
        trimmed.toIntOrNull()?.let { number -> return if (number in 1..names.size) number - 1 else null }
        return names.indexOfFirst { it.equals(trimmed, ignoreCase = true) }.takeIf { it >= 0 }
    }

    /** Format-specific structural description; empty string when nothing more can be said. */
    fun describe(file: File, format: DocumentFormat): String = when (format) {
        DocumentFormat.PDF -> describePdf(file)
        DocumentFormat.DOCX -> describeDocx(file)
        DocumentFormat.XLSX -> describeXlsx(file)
        DocumentFormat.PPTX -> describePptx(file)
        DocumentFormat.ODT -> describeOdf(file, "text", "p")
        DocumentFormat.ODS -> describeOdf(file, "table", "table-row")
        DocumentFormat.ODP -> describeOdf(file, "draw", "page")
        DocumentFormat.RTF -> describeRtf(file)
        DocumentFormat.CSV -> describeDelimited(file, tsv = false)
        DocumentFormat.TSV -> describeDelimited(file, true)
        DocumentFormat.JSON, DocumentFormat.XML, DocumentFormat.HTML,
        DocumentFormat.MARKDOWN, DocumentFormat.TEXT -> describePlainText(file)
        DocumentFormat.LEGACY_DOC, DocumentFormat.LEGACY_XLS, DocumentFormat.LEGACY_PPT ->
            "Старый бинарный формат (OLE2). На устройстве надёжно разобрать его нельзя — " +
                "пересохраните файл в современном формате (DOCX/XLSX/PPTX)."
        DocumentFormat.BINARY_UNKNOWN ->
            "Бинарный файл неизвестного типа: структура не распознана, показан только размер."
        DocumentFormat.ZIP_UNKNOWN ->
            "ZIP-контейнер без распознанного документа внутри (не OOXML и не OpenDocument)."
    }

    fun describePdf(file: File): String =
        PDDocument.load(file).use { document ->
            val info = document.documentInformation
            buildString {
                append("Тип: PDF")
                append("\nСтраниц: ${document.numberOfPages}")
                val title = info?.title?.trim()?.takeIf { it.isNotEmpty() }
                val author = info?.author?.trim()?.takeIf { it.isNotEmpty() }
                if (title != null) append("\nНазвание: $title")
                if (author != null) append("\nАвтор: $author")
            }
        }

    fun describeDocx(file: File): String {
        val documentXml = DocumentSupport.readZipEntryText(file, "word/document.xml")
            ?: return "Тип: DOCX. Внутренний документ word/document.xml не найден."
        val paragraphs = DocumentSupport.countXmlElements(documentXml, "p")
        val textLength = DocumentSupport.xmlTextContent(documentXml).trim().length
        val core = DocumentSupport.readZipEntryText(file, "docProps/core.xml")
        val title = core?.let { DocumentSupport.xmlTextContent(it, "title") }?.trim()?.takeIf { it.isNotEmpty() }
        val creator = core?.let { DocumentSupport.xmlTextContent(it, "creator") }?.trim()?.takeIf { it.isNotEmpty() }
        return buildString {
            append("Тип: DOCX")
            append("\nАбзацев: $paragraphs")
            append("\nСимволов текста: $textLength")
            if (title != null) append("\nНазвание: $title")
            if (creator != null) append("\nАвтор: $creator")
        }
    }

    fun describeXlsx(file: File): String {
        val workbook = DocumentSupport.readZipEntryText(file, "xl/workbook.xml")
            ?: return "Тип: XLSX. Внутренний документ xl/workbook.xml не найден."
        val sheetNames = DocumentSupport.xmlAttributeValues(workbook, "sheet", "name")
        return buildString {
            append("Тип: XLSX")
            append("\nЛистов: ${sheetNames.size}")
            if (sheetNames.isNotEmpty()) append("\nЛисты: ${sheetNames.joinToString(", ")}")
        }
    }

    fun describePptx(file: File): String {
        val slides = DocumentSupport.zipEntryNames(file)
            .count { SLIDE_ENTRY.matches(it) }
        return "Тип: PPTX\nСлайдов: $slides"
    }

    fun describeOdf(file: File, kind: String, countTag: String): String {
        val content = DocumentSupport.readZipEntryText(file, "content.xml")
            ?: return "Тип: OpenDocument ($kind). Внутренний документ content.xml не найден."
        val meta = DocumentSupport.readZipEntryText(file, "meta.xml")
        val title = meta?.let { DocumentSupport.xmlTextContent(it, "title") }?.trim()?.takeIf { it.isNotEmpty() }
        return buildString {
            append("Тип: OpenDocument ($kind)")
            when (kind) {
                "text" -> append("\nАбзацев: ${DocumentSupport.countXmlElements(content, countTag)}")
                "table" -> {
                    append("\nСтрок: ${DocumentSupport.countXmlElements(content, countTag)}")
                    append("\nЯчеек: ${DocumentSupport.countXmlElements(content, "table-cell")}")
                }
                "draw" -> append("\nСтраниц: ${DocumentSupport.countXmlElements(content, countTag)}")
                else -> append("\nЭлементов «$countTag»: ${DocumentSupport.countXmlElements(content, countTag)}")
            }
            if (title != null) append("\nНазвание: $title")
        }
    }

    fun describeRtf(file: File): String {
        val text = FileInputStream(file).use { input ->
            val converter = StringTextConverter()
            converter.convert(RtfStreamSource(input))
            converter.text ?: ""
        }
        val paragraphs = text.split('\n').count { it.isNotBlank() }
        return buildString {
            append("Тип: RTF")
            append("\nАбзацев (непустых строк): $paragraphs")
            append("\nСимволов текста: ${text.trim().length}")
        }
    }

    fun describeDelimited(file: File, tsv: Boolean): String {
        val format = CSVFormat.Builder.create(CSVFormat.DEFAULT)
            .setDelimiter(if (tsv) '\t' else ',')
            .get()
        val records = CSVParser.parse(file, Charsets.UTF_8, format).use { it.records }
        if (records.isEmpty()) {
            return "Тип: ${if (tsv) "TSV" else "CSV"}\nСтрок: 0"
        }
        // The first row is treated as the header row (header-aware): it defines the column count
        // and is not counted as data.
        val columns = records[0].size()
        val dataRows = (records.size - 1).coerceAtLeast(0)
        return buildString {
            append("Тип: ${if (tsv) "TSV" else "CSV"}")
            append("\nСтрок данных (без заголовка): $dataRows")
            append("\nСтолбцов: $columns")
        }
    }

    fun describePlainText(file: File): String {
        val text = DocumentSupport.readFileText(file)
            ?: return "Не удалось прочитать файл как текст."
        val lines = text.split('\n').size
        return "Строк: $lines\nСимволов: ${text.length}"
    }

    companion object {
        val SLIDE_ENTRY = Regex("""^ppt/slides/slide\d+\.xml$""")

        /** Formats whose round-trip check compares cells rather than raw characters. */
        val TABULAR_FORMATS = setOf(
            DocumentFormat.XLSX, DocumentFormat.ODS, DocumentFormat.CSV, DocumentFormat.TSV
        )
    }
}
