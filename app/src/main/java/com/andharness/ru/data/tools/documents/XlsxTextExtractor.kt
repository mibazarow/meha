package com.andharness.ru.data.tools.documents

import java.io.StringReader
import org.xmlpull.v1.XmlPullParser

/** Visible-text extraction from SpreadsheetML parts (workbook, shared strings, worksheets). */
internal object XlsxTextExtractor {

    /** Sheet names of a workbook, in `xl/workbook.xml` order. */
    fun xlsxSheetNames(workbookXml: String): List<String> =
        XmlQuerySupport.xmlAttributeValues(workbookXml, "sheet", "name")

    /**
     * Values of `xl/sharedStrings.xml` in index order. Text is taken from the `<t>` runs of every
     * `<si>`; phonetic runs (`<rPh>`) are skipped so they do not duplicate the visible text.
     */
    fun xlsxSharedStrings(sharedStringsXml: String): List<String> {
        if (sharedStringsXml.isBlank()) return emptyList()
        val values = mutableListOf<String>()
        val parser = XmlPullSupport.newPullParser()
        parser.setInput(StringReader(sharedStringsXml))
        var inItem = 0
        var inText = 0
        var inPhonetic = 0
        var current: StringBuilder? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (XmlPullSupport.localName(parser)) {
                    "si" -> {
                        inItem++
                        current = StringBuilder()
                    }
                    "rPh" -> inPhonetic++
                    "t" -> inText++
                }
                XmlPullParser.END_TAG -> when (XmlPullSupport.localName(parser)) {
                    "t" -> if (inText > 0) inText--
                    "rPh" -> if (inPhonetic > 0) inPhonetic--
                    "si" -> {
                        if (inItem > 0) inItem--
                        values += current?.toString() ?: ""
                        current = null
                    }
                }
                XmlPullParser.TEXT ->
                    if (inItem > 0 && inText > 0 && inPhonetic == 0) current?.append(parser.text ?: "")
                else -> Unit
            }
            event = parser.next()
        }
        return values
    }

    /**
     * Renders one worksheet XML as rows of tab-separated cell values, rows separated by newlines.
     * Shared-string cells (`t="s"`) are resolved through [sharedStrings]; inline strings
     * (`t="inlineStr"`) and literals are used verbatim, booleans become TRUE/FALSE. A cell's column is
     * taken from its `r` reference (`C7` → column 2) so gaps stay aligned.
     */
    fun xlsxSheetText(sheetXml: String, sharedStrings: List<String>): String =
        xlsxSheetRows(sheetXml, sharedStrings).joinToString("\n") { row -> row.joinToString("\t") }

    /**
     * Extracts one worksheet XML as structured rows of resolved cell values (same value semantics as
     * [xlsxSheetText]): the outer list is the rows, the inner list the cells, with gaps padded by the
     * `r` references so a cell's column position is preserved.
     */
    fun xlsxSheetRows(sheetXml: String, sharedStrings: List<String>): List<List<String>> {
        if (sheetXml.isBlank()) return emptyList()
        val rows = mutableListOf<List<String>>()
        val parser = XmlPullSupport.newPullParser()
        parser.setInput(StringReader(sheetXml))
        var event = parser.eventType
        var inCell = false
        var inValue = false
        var cellType: String? = null
        var cellRef: String? = null
        val cellText = StringBuilder()
        var rowCells = mutableListOf<String>()
        var rowWidth = 0
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (XmlPullSupport.localName(parser)) {
                    "row" -> {
                        rowCells = mutableListOf()
                        rowWidth = 0
                    }
                    "c" -> {
                        inCell = true
                        inValue = false
                        cellType = parser.getAttributeValue(null, "t")
                        cellRef = parser.getAttributeValue(null, "r")
                        cellText.setLength(0)
                    }
                    "v", "t" -> if (inCell) inValue = true
                }
                XmlPullParser.END_TAG -> when (XmlPullSupport.localName(parser)) {
                    "v", "t" -> inValue = false
                    "c" -> {
                        if (inCell) {
                            val column = columnIndexFromRef(cellRef) ?: rowWidth
                            while (rowCells.size < column) rowCells.add("")
                            val value = resolveCellValue(cellType, cellText.toString(), sharedStrings)
                            if (rowCells.size == column) rowCells.add(value) else rowCells[column] = value
                            rowWidth = maxOf(rowWidth, column + 1)
                            inCell = false
                        }
                    }
                    "row" -> {
                        rows.add(rowCells.toList())
                        rowCells = mutableListOf()
                        rowWidth = 0
                    }
                }
                XmlPullParser.TEXT -> if (inCell && inValue) cellText.append(parser.text ?: "")
                else -> Unit
            }
            event = parser.next()
        }
        return rows
    }

    /**
     * Reads a single cell's value from a worksheet XML by its reference (`B2`), resolving shared
     * strings through [sharedStrings]. Returns `null` when no cell with that reference exists, so the
     * caller can report an honest verification failure.
     */
    fun xlsxCellValue(sheetXml: String, sharedStrings: List<String>, cellRef: String): String? {
        if (sheetXml.isBlank()) return null
        val target = cellRef.trim()
        val parser = XmlPullSupport.newPullParser()
        parser.setInput(StringReader(sheetXml))
        var event = parser.eventType
        var inCell = false
        var inValue = false
        var cellType: String? = null
        var currentRef: String? = null
        val cellText = StringBuilder()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (XmlPullSupport.localName(parser)) {
                    "c" -> {
                        inCell = true
                        inValue = false
                        cellType = parser.getAttributeValue(null, "t")
                        currentRef = parser.getAttributeValue(null, "r")
                        cellText.setLength(0)
                    }
                    "v", "t" -> if (inCell) inValue = true
                }
                XmlPullParser.END_TAG -> when (XmlPullSupport.localName(parser)) {
                    "v", "t" -> inValue = false
                    "c" -> if (inCell) {
                        val matches = currentRef != null && currentRef.equals(target, ignoreCase = true)
                        val value = resolveCellValue(cellType, cellText.toString(), sharedStrings)
                        inCell = false
                        if (matches) return value
                    }
                }
                XmlPullParser.TEXT -> if (inCell && inValue) cellText.append(parser.text ?: "")
                else -> Unit
            }
            event = parser.next()
        }
        return null
    }

    private fun resolveCellValue(type: String?, raw: String, sharedStrings: List<String>): String =
        when (type) {
            "s" -> raw.trim().toIntOrNull()?.let { sharedStrings.getOrNull(it) } ?: raw
            "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
            else -> raw
        }

    /** Zero-based column index of a cell reference like `B12`; `null` when it cannot be parsed. */
    private fun columnIndexFromRef(ref: String?): Int? {
        if (ref.isNullOrEmpty()) return null
        var index = 0
        var found = false
        for (ch in ref) {
            if (ch.isLetter()) {
                index = index * 26 + (ch.uppercaseChar() - 'A' + 1)
                found = true
            } else {
                break
            }
        }
        return if (found) index - 1 else null
    }
}
