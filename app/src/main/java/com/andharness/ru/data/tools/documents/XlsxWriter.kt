package com.andharness.ru.data.tools.documents

import java.util.Locale

/**
 * Minimal SpreadsheetML (XLSX) package generation plus a small spreadsheet-XML editor: append rows,
 * parse a cell reference (`B2`) and write a single cell in place.
 *
 * References:
 * - SpreadsheetML — https://learn.microsoft.com/en-us/office/open-xml/spreadsheet/spreadsheetml
 * - `java.util.zip.ZipOutputStream` —
 *   https://developer.android.com/reference/java/util/zip/ZipOutputStream
 */
internal object XlsxWriter {

    private const val SHEET_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val PACKAGE_RELS_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val OFFICE_DOC_RELS_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val CONTENT_TYPES_NS = "http://schemas.openxmlformats.org/package/2006/content-types"

    private const val XLSX_WORKBOOK_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
    private const val XLSX_WORKSHEET_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"
    private const val XLSX_STYLES_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"

    /**
     * A minimal SpreadsheetML package with a single worksheet. Cell values are written as inline
     * strings (`t="inlineStr"`), so no shared-string table is required.
     */
    fun xlsx(rows: List<List<String>>, sheetName: String = "Sheet1"): ByteArray = ZipPackageWriter.zip(
        "[Content_Types].xml" to (
            DocumentXml.HEADER +
                "<Types xmlns=\"$CONTENT_TYPES_NS\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/xl/workbook.xml\" ContentType=\"$XLSX_WORKBOOK_CONTENT_TYPE\"/>" +
                "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"$XLSX_WORKSHEET_CONTENT_TYPE\"/>" +
                "<Override PartName=\"/xl/styles.xml\" ContentType=\"$XLSX_STYLES_CONTENT_TYPE\"/>" +
                "</Types>"
            ),
        "_rels/.rels" to (
            DocumentXml.HEADER +
                "<Relationships xmlns=\"$PACKAGE_RELS_NS\">" +
                "<Relationship Id=\"rId1\" Type=\"$OFFICE_DOC_RELS_NS/officeDocument\" Target=\"xl/workbook.xml\"/>" +
                "</Relationships>"
            ),
        "xl/workbook.xml" to (
            DocumentXml.HEADER +
                "<workbook xmlns=\"$SHEET_NS\" xmlns:r=\"$OFFICE_DOC_RELS_NS\">" +
                "<sheets><sheet name=\"${DocumentXml.xmlEscape(DocumentXml.xmlSafe(sheetName))}\" sheetId=\"1\" r:id=\"rId1\"/></sheets>" +
                "</workbook>"
            ),
        "xl/_rels/workbook.xml.rels" to (
            DocumentXml.HEADER +
                "<Relationships xmlns=\"$PACKAGE_RELS_NS\">" +
                "<Relationship Id=\"rId1\" Type=\"$OFFICE_DOC_RELS_NS/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
                "<Relationship Id=\"rId2\" Type=\"$OFFICE_DOC_RELS_NS/styles\" Target=\"styles.xml\"/>" +
                "</Relationships>"
            ),
        "xl/worksheets/sheet1.xml" to xlsxSheet(rows),
        "xl/styles.xml" to (
            DocumentXml.HEADER +
                "<styleSheet xmlns=\"$SHEET_NS\">" +
                "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
                "<fills count=\"1\"><fill><patternFill patternType=\"none\"/></fill></fills>" +
                "<borders count=\"1\"><border/></borders>" +
                "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
                "<cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/></cellXfs>" +
                "</styleSheet>"
            ),
    )

    private fun xlsxSheet(rows: List<List<String>>): String =
        DocumentXml.HEADER +
            "<worksheet xmlns=\"$SHEET_NS\"><sheetData>${xlsxRows(rows, startRow = 1)}</sheetData></worksheet>"

    /** One `<row>` per list of cell values, numbered from [startRow]; values become inline strings. */
    private fun xlsxRows(rows: List<List<String>>, startRow: Int): String {
        val body = StringBuilder()
        rows.forEachIndexed { rowIndex, cells ->
            val rowNumber = startRow + rowIndex
            body.append("<row r=\"").append(rowNumber).append("\">")
            cells.forEachIndexed { columnIndex, value ->
                body.append("<c r=\"").append(columnName(columnIndex)).append(rowNumber)
                    .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                    .append(DocumentXml.xmlEscape(DocumentXml.xmlSafe(value)))
                    .append("</t></is></c>")
            }
            body.append("</row>")
        }
        return body.toString()
    }

    /**
     * Appends [rows] after the last existing row of a worksheet part, just before `</sheetData>`.
     * Row numbers continue from the highest `r="N"` already present, so existing rows stay untouched.
     */
    fun appendXlsxRows(sheetXml: String, rows: List<List<String>>): String {
        if (rows.isEmpty()) return sheetXml
        val marker = sheetXml.indexOf("</sheetData>")
        require(marker >= 0) { "XML листа не содержит </sheetData>." }
        val lastRow = XLSX_ROW_INDEX.findAll(sheetXml)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull() ?: 0
        val body = xlsxRows(rows, startRow = lastRow + 1)
        return sheetXml.substring(0, marker) + body + sheetXml.substring(marker)
    }

    private val XLSX_ROW_INDEX = Regex("""<row\b[^>]*\br="(\d+)"""")

    /**
     * Parses a SpreadsheetML cell reference like `B2` or `AA10` into a [DocumentWriter.CellAddress],
     * or `null` when it is malformed or outside Excel's grid (`A1`…`XFD1048576`).
     */
    fun parseCellReference(cellRef: String): DocumentWriter.CellAddress? {
        val match = XLSX_CELL_REFERENCE.matchEntire(cellRef.trim()) ?: return null
        val row = match.groupValues[2].toIntOrNull() ?: return null
        var column = 0
        for (ch in match.groupValues[1].uppercase(Locale.ROOT)) {
            column = column * 26 + (ch - 'A' + 1)
        }
        val zeroBased = column - 1
        if (row !in 1..XLSX_MAX_ROW || zeroBased !in 0..XLSX_MAX_COLUMN) return null
        return DocumentWriter.CellAddress(zeroBased, row)
    }

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
    fun setXlsxCell(sheetXml: String, cellRef: String, value: String): String {
        val address = parseCellReference(cellRef)
            ?: throw IllegalArgumentException("Некорректный адрес ячейки: «$cellRef». Ожидается вид B2.")
        val ref = "${columnName(address.column)}${address.row}"
        val cell = buildXlsxCell(ref, value)

        val existing = XLSX_CELL_ELEMENT.findAll(sheetXml)
            .firstOrNull { cellRefOf(it.value)?.equals(ref, ignoreCase = true) == true }
        if (existing != null) return sheetXml.replaceRange(existing.range, cell)

        val row = XLSX_ROW_ELEMENT.findAll(sheetXml)
            .firstOrNull { rowNumberOf(it.value) == address.row }
        if (row != null) {
            return sheetXml.replaceRange(row.range, insertCellIntoRow(row.value, address.column, cell))
        }

        return insertRow(sheetXml, address.row, "<row r=\"${address.row}\">$cell</row>")
    }

    /**
     * Applies the cell-format index [styleIndex] (`s="N"`) to the cell [cellRef] of a worksheet part,
     * returning the rewritten XML. An existing cell keeps its value/type and only gains the `s`
     * attribute; a missing cell becomes an empty styled `<c r="…" s="N"/>`, inserted in the same
     * row/column order [setXlsxCell] uses.
     *
     * Throws [IllegalArgumentException] for a malformed [cellRef].
     */
    fun setXlsxCellStyle(sheetXml: String, cellRef: String, styleIndex: Int): String {
        val address = parseCellReference(cellRef)
            ?: throw IllegalArgumentException("Некорректный адрес ячейки: «$cellRef». Ожидается вид B2.")
        val ref = "${columnName(address.column)}${address.row}"

        val existing = XLSX_CELL_ELEMENT.findAll(sheetXml)
            .firstOrNull { cellRefOf(it.value)?.equals(ref, ignoreCase = true) == true }
        if (existing != null) {
            return sheetXml.replaceRange(existing.range, withStyleAttribute(existing.value, styleIndex))
        }

        val cell = "<c r=\"$ref\" s=\"$styleIndex\"/>"
        val row = XLSX_ROW_ELEMENT.findAll(sheetXml)
            .firstOrNull { rowNumberOf(it.value) == address.row }
        if (row != null) {
            return sheetXml.replaceRange(row.range, insertCellIntoRow(row.value, address.column, cell))
        }
        return insertRow(sheetXml, address.row, "<row r=\"${address.row}\">$cell</row>")
    }

    /** Sets/replaces the `s` attribute of a `<c>` element, keeping any child content intact. */
    private fun withStyleAttribute(cellElement: String, styleIndex: Int): String {
        val openEnd = cellElement.indexOf('>')
        if (openEnd < 0) return cellElement
        val tag = cellElement.substring(0, openEnd)
        val rest = cellElement.substring(openEnd)
        val updated = if (XLSX_STYLE_ATTRIBUTE.containsMatchIn(tag)) {
            XLSX_STYLE_ATTRIBUTE.replace(tag, "s=\"$styleIndex\"")
        } else if (tag.endsWith("/")) {
            tag.dropLast(1) + " s=\"$styleIndex\"/"
        } else {
            "$tag s=\"$styleIndex\""
        }
        return updated + rest
    }

    /** A numeric-looking cell (no shared-string indirection, `t` attribute omitted). */
    private fun buildXlsxCell(ref: String, value: String): String =
        if (XLSX_NUMBER_LITERAL.matches(value)) {
            "<c r=\"$ref\"><v>$value</v></c>"
        } else {
            "<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">" +
                "${DocumentXml.xmlEscape(DocumentXml.xmlSafe(value))}</t></is></c>"
        }

    /** The `r` reference of a `<c>` element, read from its opening tag only. */
    private fun cellRefOf(cellElement: String): String? =
        XLSX_REFERENCE_ATTRIBUTE.find(cellElement.substringBefore('>'))?.groupValues?.get(1)

    /** The `r` row number of a `<row>` element, read from its opening tag only. */
    private fun rowNumberOf(rowElement: String): Int? =
        XLSX_NUMBER_ATTRIBUTE.find(rowElement.substringBefore('>'))?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Inserts [cell] into the body of [rowElement], before the first existing cell whose column is
     * greater than [column], so cells stay ordered left-to-right.
     */
    private fun insertCellIntoRow(rowElement: String, column: Int, cell: String): String {
        if (rowElement.endsWith("/>")) {
            return rowElement.dropLast(2) + ">" + cell + "</row>"
        }
        val openEnd = rowElement.indexOf('>') + 1
        val openTag = rowElement.substring(0, openEnd)
        val inner = rowElement.substring(openEnd, rowElement.length - "</row>".length)
        var insertAt = inner.length
        var nextColumn = 0
        for (match in XLSX_CELL_ELEMENT.findAll(inner)) {
            val cellColumn = cellRefOf(match.value)?.let { parseCellReference(it)?.column } ?: nextColumn
            if (cellColumn > column) {
                insertAt = match.range.first
                break
            }
            nextColumn = cellColumn + 1
        }
        return openTag + inner.substring(0, insertAt) + cell + inner.substring(insertAt) + "</row>"
    }

    /** Adds a whole new `<row>` in ascending row order, creating `<sheetData>` if it was self-closed. */
    private fun insertRow(sheetXml: String, rowNumber: Int, rowXml: String): String {
        val selfClosing = XLSX_SHEET_DATA_SELF_CLOSING.find(sheetXml)
        if (selfClosing != null) {
            return sheetXml.replaceRange(
                selfClosing.range,
                selfClosing.value.dropLast(2) + ">" + rowXml + "</sheetData>"
            )
        }
        val open = sheetXml.indexOf("<sheetData")
        require(open >= 0) { "XML листа не содержит <sheetData>." }
        val close = sheetXml.indexOf("</sheetData>", open)
        require(close >= 0) { "XML листа не содержит </sheetData>." }
        val before = XLSX_ROW_ELEMENT.findAll(sheetXml)
            .firstOrNull { (rowNumberOf(it.value) ?: 0) > rowNumber }
        val insertAt = before?.range?.first ?: close
        return sheetXml.substring(0, insertAt) + rowXml + sheetXml.substring(insertAt)
    }

    private val XLSX_CELL_REFERENCE = Regex("""^([A-Za-z]{1,3})([1-9]\d*)$""")
    private val XLSX_NUMBER_LITERAL = Regex("""-?(?:\d+(?:\.\d+)?|\.\d+)(?:[eE][+-]?\d+)?""")
    private val XLSX_STYLE_ATTRIBUTE = Regex("""\bs="[^"]*"""")
    private val XLSX_REFERENCE_ATTRIBUTE = Regex("""\br="([^"]*)"""")
    private val XLSX_NUMBER_ATTRIBUTE = Regex("""\br="(\d+)"""")
    private val XLSX_CELL_ELEMENT = Regex("(?s)<c\\b[^>]*(?:/>|>.*?</c>)")
    private val XLSX_ROW_ELEMENT = Regex("(?s)<row\\b[^>]*(?:/>|>.*?</row>)")
    private val XLSX_SHEET_DATA_SELF_CLOSING = Regex("(?s)<sheetData\\b[^>]*/>")

    /** Excel's last usable row/column (`XFD1048576`), so a bad reference cannot exceed the grid. */
    private const val XLSX_MAX_ROW = 1_048_576
    private const val XLSX_MAX_COLUMN = 16_383

    /** Zero-based column index → spreadsheet column label (`0` → `A`, `26` → `AA`). */
    fun columnName(index: Int): String {
        var value = index.coerceAtLeast(0)
        val builder = StringBuilder()
        do {
            builder.append(('A' + value % 26))
            value = value / 26 - 1
        } while (value >= 0)
        return builder.reverse().toString()
    }
}
