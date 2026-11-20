package com.andharness.ru.data.tools.documents

/**
 * Pure byte-level generation of the document formats that can be produced without a heavyweight
 * library: minimal-but-valid OOXML (DOCX/XLSX), OpenDocument (ODT/ODS), RTF and CSV.
 *
 * Everything here is plain `java.io` / `java.util.zip` / Apache Commons CSV — no Android APIs and no
 * filesystem access — so the whole module is unit-testable on the JVM. Callers (the tool handler)
 * own path resolution, overwrite policy, atomic replacement and post-write verification.
 *
 * This is the façade; the per-format work lives in [DocxWriter], [XlsxWriter], [OdfWriter],
 * [RtfWriter], [CsvWriter], [XmlTextNodeReplacer], [TableTextParser] and the shared [DocumentXml] /
 * [ZipPackageWriter] helpers.
 *
 * References:
 * - OOXML package structure — https://learn.microsoft.com/en-us/office/open-xml/word/wordprocessingml
 * - SpreadsheetML — https://learn.microsoft.com/en-us/office/open-xml/spreadsheet/spreadsheetml
 * - OpenDocument (`mimetype` must be the first, STORED entry) —
 *   https://docs.oasis-open.org/office/v1.2/os/OpenDocument-v1.2-os-part3.html
 * - RTF 1.9 specification — https://learn.microsoft.com/en-us/openspecs/office_protocols/ms-rtf/
 * - `java.util.zip.ZipOutputStream` —
 *   https://developer.android.com/reference/java/util/zip/ZipOutputStream
 * - Apache Commons CSV `CSVPrinter` (RFC 4180) — https://commons.apache.org/proper/commons-csv/
 */
object DocumentWriter {

    /** ODF media types; `DocumentFormatDetector` recognises exactly these strings. */
    const val ODF_MIMETYPE_TEXT = OdfWriter.ODF_MIMETYPE_TEXT
    const val ODF_MIMETYPE_SPREADSHEET = OdfWriter.ODF_MIMETYPE_SPREADSHEET

    /** A minimal WordprocessingML package; one paragraph per line of [text]. */
    fun docx(text: String, title: String? = null): ByteArray = DocxWriter.docx(text, title)

    /**
     * Inserts [text] as new paragraphs into `word/document.xml`, keeping the rest of the part intact.
     * A document-level `<w:sectPr>` must remain the LAST child of `<w:body>` (OOXML `CT_Body`), so
     * the paragraphs are inserted just before it; without one they go straight before `</w:body>`.
     */
    fun appendDocxText(documentXml: String, text: String): String =
        DocxWriter.appendDocxText(documentXml, text)

    /**
     * A minimal SpreadsheetML package with a single worksheet. Cell values are written as inline
     * strings (`t="inlineStr"`), so no shared-string table is required.
     */
    fun xlsx(rows: List<List<String>>, sheetName: String = "Sheet1"): ByteArray =
        XlsxWriter.xlsx(rows, sheetName)

    /**
     * Appends [rows] after the last existing row of a worksheet part, just before `</sheetData>`.
     * Row numbers continue from the highest `r="N"` already present, so existing rows stay untouched.
     */
    fun appendXlsxRows(sheetXml: String, rows: List<List<String>>): String =
        XlsxWriter.appendXlsxRows(sheetXml, rows)

    /** A parsed SpreadsheetML cell address: zero-based [column] plus 1-based [row]. */
    data class CellAddress(val column: Int, val row: Int)

    /**
     * Parses a SpreadsheetML cell reference like `B2` or `AA10` into a [CellAddress], or `null` when
     * it is malformed or outside Excel's grid (`A1`…`XFD1048576`).
     */
    fun parseCellReference(cellRef: String): CellAddress? = XlsxWriter.parseCellReference(cellRef)

    /**
     * Writes [value] into the cell [cellRef] of one worksheet part and returns the rewritten XML.
     *
     * A value that is a plain number becomes a numeric cell (`<v>`, no `t` attribute); anything else
     * becomes an inline string (`t="inlineStr"`), so no shared-string table is touched. An existing
     * cell with the same reference is replaced in place; otherwise a new `<c>` is inserted into its
     * row (creating the `<row>`/`<sheetData>` structure when needed), keeping row children ordered by
     * column and rows in ascending order.
     *
     * Throws [IllegalArgumentException] for a malformed [cellRef]; [sheetXml] without `<sheetData>`
     * when a new row must be created.
     */
    fun setXlsxCell(sheetXml: String, cellRef: String, value: String): String =
        XlsxWriter.setXlsxCell(sheetXml, cellRef, value)

    /** Zero-based column index → spreadsheet column label (`0` → `A`, `26` → `AA`). */
    fun columnName(index: Int): String = XlsxWriter.columnName(index)

    /** A minimal OpenDocument text package: one `<text:p>` per line of [text]. */
    fun odt(text: String, title: String? = null): ByteArray = OdfWriter.odt(text, title)

    /** Inserts [text] as new paragraphs just before `</office:text>`, keeping the rest intact. */
    fun appendOdtText(contentXml: String, text: String): String = OdfWriter.appendOdtText(contentXml, text)

    /** A minimal OpenDocument spreadsheet package: one table ([sheetName]) of rows/cells. */
    fun ods(rows: List<List<String>>, sheetName: String = "Sheet1"): ByteArray =
        OdfWriter.ods(rows, sheetName)

    /** Appends [rows] to the first table of [contentXml], just before its `</table:table>`. */
    fun appendOdsRows(contentXml: String, rows: List<List<String>>): String =
        OdfWriter.appendOdsRows(contentXml, rows)

    /** Basic RTF 1.x document: control characters escaped, newlines turned into `\par`. */
    fun rtf(text: String): ByteArray = RtfWriter.rtf(text)

    /**
     * Appends [text] as a new paragraph (`\par`) to an existing RTF document, just before its final
     * closing brace. The text is escaped with the same rules as [rtf].
     */
    fun appendRtf(existing: String, text: String): String = RtfWriter.appendRtf(existing, text)

    /**
     * Replaces the literal [find] with [replace] only inside the character data of elements whose
     * local name is one of [tags] (namespace prefixes ignored, so `w:t`, `text:p` and `t`/`p` all
     * match). Markup, attributes and non-matching elements are left byte-for-byte intact; [xml] is
     * returned unchanged when nothing matched, which lets the caller report an honest "not found".
     *
     * This is deliberately a text-node-level transform (not a DOM rewrite) so unknown parts of a
     * foreign document survive untouched instead of being re-serialized.
     */
    fun replaceInTextNodes(xml: String, find: String, replace: String, tags: List<String>): String =
        XmlTextNodeReplacer.replaceInTextNodes(xml, find, replace, tags)

    /** RFC 4180-delimited text (quoting/escaping handled by Commons CSV) with [delimiter]. */
    fun csv(rows: List<List<String>>, delimiter: Char = ','): ByteArray = CsvWriter.csv(rows, delimiter)

    /**
     * Splits a plain-text table into rows and cells: rows by `\n`, cells by tab when present,
     * otherwise by `;`, otherwise by `,`.
     */
    fun parseTableText(content: String): List<List<String>> = TableTextParser.parseTableText(content)

    /** Escapes the XML metacharacters `& < > "` (and `'` for attribute safety). */
    fun xmlEscape(value: String): String = DocumentXml.xmlEscape(value)

    /**
     * Drops characters that are illegal in XML 1.0 (all control characters except tab/LF/CR, and
     * lone surrogates) so every generated part stays well-formed even for arbitrary model output.
     */
    fun xmlSafe(value: String): String = DocumentXml.xmlSafe(value)
}
