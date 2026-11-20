package com.andharness.ru.data.tools.documents

import com.andharness.ru.data.tools.formatBytes as canonicalFormatBytes
import java.io.File

/**
 * Shared, container-agnostic helpers for the Documents tooling: ZIP entry access, XML text/element
 * extraction and byte/text formatting.
 *
 * Deliberately built on plain JVM APIs (`java.io.File`, `java.util.zip`, `org.xmlpull.v1`) with no
 * Android dependency, so every helper is unit-testable on the JVM.
 *
 * This is the façade; the work lives in [ZipEntrySupport], [BoundedStreamReader], [XmlQuerySupport],
 * [XmlPullSupport], [DocumentTextCodec], [DocxTextExtractor], [XlsxTextExtractor], [OdfTextExtractor]
 * and [PptxSlideEntries].
 *
 * References:
 * - `java.io.File` — https://developer.android.com/reference/java/io/File
 * - `java.util.zip.ZipFile` — https://developer.android.com/reference/java/util/zip/ZipFile
 * - `XmlPullParser` — https://developer.android.com/reference/org/xmlpull/v1/XmlPullParser
 * - `XmlPullParserFactory` — https://developer.android.com/reference/org/xmlpull/v1/XmlPullParserFactory
 */
object DocumentSupport {

    /** Returns the UTF-8 text of [entry] inside [zip], or `null` when the entry is absent/unreadable. */
    fun readZipEntryText(zip: File, entry: String): String? = ZipEntrySupport.readZipEntryText(zip, entry)

    /** Lists the entry names of [zip] in archive order; an empty list when the archive is unreadable. */
    fun zipEntryNames(zip: File): List<String> = ZipEntrySupport.zipEntryNames(zip)

    /**
     * Rewrites [file] as a new ZIP archive with every entry from [replacements] substituted and all
     * other entries copied verbatim, preserving their original order and compression method. A
     * stored (uncompressed) entry stays stored — ODF requires `mimetype` to be the first, STORED
     * entry — so repackaging an OpenDocument/OOXML container keeps it valid. [additions] are appended
     * at the end when their names are not already present.
     *
     * Returns the new archive bytes, or `null` when the source cannot be read/rewritten.
     */
    fun rewriteZip(
        file: File,
        replacements: Map<String, ByteArray>,
        additions: Map<String, ByteArray> = emptyMap(),
    ): ByteArray? = ZipEntrySupport.rewriteZip(file, replacements, additions)

    /**
     * Concatenates the text content of [xml]. When [tag] is given, only text inside elements whose
     * local name equals [tag] is collected; otherwise all text is collected. Entities are decoded by
     * the parser (`&amp;`, `&#1055;`, …). Namespace prefixes are ignored (`w:p` matches `p`).
     */
    fun xmlTextContent(xml: String, tag: String? = null): String =
        XmlQuerySupport.xmlTextContent(xml, tag)

    /** Counts elements whose local name equals [tag] (namespace prefixes ignored). */
    fun countXmlElements(xml: String, tag: String): Int = XmlQuerySupport.countXmlElements(xml, tag)

    /**
     * Collects the values of [attribute] on every element whose local name equals [tag], in document
     * order. The attribute is matched by local name first and by [attribute] verbatim second, so both
     * `name` and `r:id` (asked as `id`) resolve.
     */
    fun xmlAttributeValues(xml: String, tag: String, attribute: String): List<String> =
        XmlQuerySupport.xmlAttributeValues(xml, tag, attribute)

    /** Reads a whole (bounded) file as text, honouring a UTF-8/UTF-16 LE/BE byte-order mark. */
    fun readFileText(file: File): String? = DocumentTextCodec.readFileText(file)

    /** Decodes [bytes] as UTF-8, stripping a UTF-8/UTF-16 LE/BE byte-order mark when present. */
    fun decodeText(bytes: ByteArray): String = DocumentTextCodec.decodeText(bytes)

    /** Human-readable size (shared canonical implementation, see `data/tools/ToolUtils.kt`). */
    fun formatBytes(bytes: Long): String = canonicalFormatBytes(bytes)

    /**
     * Extracts the text of a WordprocessingML `word/document.xml`: every `<w:t>` run is concatenated
     * and each `<w:p>` closes the paragraph with a newline. Namespace prefixes are ignored, so `w:t`
     * and `t` both match; `<w:br/>` becomes a newline and `<w:tab/>` a tab.
     */
    fun docxText(documentXml: String): String = DocxTextExtractor.docxText(documentXml)

    /**
     * Extracts the text of an OOXML part whose runs live in elements named [runTag] and whose
     * paragraphs end with [paragraphTag] (DOCX `word/document.xml`, PPTX `ppt/slides/slideN.xml`).
     * Prefix-insensitive; `<br>`/`<tab>` map to newline/tab, trailing paragraph breaks are dropped.
     */
    fun extractRunText(xml: String, runTag: String, paragraphTag: String): String =
        DocxTextExtractor.extractRunText(xml, runTag, paragraphTag)

    /** Sheet names of a workbook, in `xl/workbook.xml` order. */
    fun xlsxSheetNames(workbookXml: String): List<String> = XlsxTextExtractor.xlsxSheetNames(workbookXml)

    /**
     * Values of `xl/sharedStrings.xml` in index order. Text is taken from the `<t>` runs of every
     * `<si>`; phonetic runs (`<rPh>`) are skipped so they do not duplicate the visible text.
     */
    fun xlsxSharedStrings(sharedStringsXml: String): List<String> =
        XlsxTextExtractor.xlsxSharedStrings(sharedStringsXml)

    /**
     * Renders one worksheet XML as rows of tab-separated cell values, rows separated by newlines.
     * Shared-string cells (`t="s"`) are resolved through [sharedStrings]; inline strings
     * (`t="inlineStr"`) and literals are used verbatim, booleans become TRUE/FALSE. A cell's column is
     * taken from its `r` reference (`C7` → column 2) so gaps stay aligned.
     */
    fun xlsxSheetText(sheetXml: String, sharedStrings: List<String>): String =
        XlsxTextExtractor.xlsxSheetText(sheetXml, sharedStrings)

    /**
     * Extracts one worksheet XML as structured rows of resolved cell values (same value semantics as
     * [xlsxSheetText]): the outer list is the rows, the inner list the cells.
     */
    fun xlsxSheetRows(sheetXml: String, sharedStrings: List<String>): List<List<String>> =
        XlsxTextExtractor.xlsxSheetRows(sheetXml, sharedStrings)

    /**
     * Reads a single cell's value from a worksheet XML by its reference (`B2`), resolving shared
     * strings through [sharedStrings]. Returns `null` when no cell with that reference exists, so the
     * caller can report an honest verification failure.
     */
    fun xlsxCellValue(sheetXml: String, sharedStrings: List<String>, cellRef: String): String? =
        XlsxTextExtractor.xlsxCellValue(sheetXml, sharedStrings, cellRef)

    /**
     * Extracts the visible text of an OpenDocument `content.xml`: text nodes are concatenated and
     * each `<text:p>`/`<text:h>` closes a paragraph with a newline. `<text:tab/>` → tab,
     * `<text:line-break/>` → newline and `<text:s text:c="N"/>` → N spaces.
     */
    fun odfText(contentXml: String): String = OdfTextExtractor.odfText(contentXml)

    /** Table (sheet) names of an OpenDocument `content.xml`, in document order. */
    fun odsSheetNames(contentXml: String): List<String> = OdfTextExtractor.odsSheetNames(contentXml)

    /** Paragraphs of every `<draw:page>` of an OpenDocument presentation `content.xml`. */
    fun odpSlides(contentXml: String): List<List<String>> = OdfTextExtractor.odpSlides(contentXml)

    /**
     * Renders the requested OpenDocument spreadsheet table as rows of tab-separated values, rows
     * separated by newlines. [sheet] is a table name (case-insensitive) or a 1-based index; `null`
     * selects the first table. Returns `null` when the requested table does not exist.
     */
    fun odsSheetText(contentXml: String, sheet: String?): String? =
        OdfTextExtractor.odsSheetText(contentXml, sheet)

    /**
     * Extracts the requested OpenDocument spreadsheet table as structured rows of cells. [sheet] is a
     * table name (case-insensitive) or a 1-based index; `null` selects the first table.
     */
    fun odsSheetRows(contentXml: String, sheet: String?): List<List<String>>? =
        OdfTextExtractor.odsSheetRows(contentXml, sheet)

    /**
     * The `ppt/slides/slideN.xml` entries of [entryNames], ordered by slide number (a lexical sort
     * would put `slide10` before `slide2`). Other entries are ignored.
     */
    fun sortedSlideEntries(entryNames: List<String>): List<String> =
        PptxSlideEntries.sortedSlideEntries(entryNames)
}
