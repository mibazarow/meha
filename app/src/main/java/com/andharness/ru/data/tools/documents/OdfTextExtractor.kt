package com.andharness.ru.data.tools.documents

import java.io.StringReader
import org.xmlpull.v1.XmlPullParser

/** Visible-text extraction from OpenDocument `content.xml` (text documents and spreadsheets). */
internal object OdfTextExtractor {

    /**
     * Extracts the visible text of an OpenDocument `content.xml`: text nodes are concatenated and
     * each `<text:p>`/`<text:h>` closes a paragraph with a newline. `<text:tab/>` → tab,
     * `<text:line-break/>` → newline and `<text:s text:c="N"/>` → N spaces.
     */
    fun odfText(contentXml: String): String {
        if (contentXml.isBlank()) return ""
        val builder = StringBuilder()
        val parser = XmlPullSupport.newPullParser()
        parser.setInput(StringReader(contentXml))
        var inParagraph = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (XmlPullSupport.localName(parser)) {
                    "p", "h" -> inParagraph++
                    "tab" -> if (inParagraph > 0) builder.append('\t')
                    "line-break" -> if (inParagraph > 0) builder.append('\n')
                    "s" -> if (inParagraph > 0) builder.append(" ".repeat(spaceCount(parser)))
                }
                XmlPullParser.END_TAG -> when (XmlPullSupport.localName(parser)) {
                    "p", "h" -> if (inParagraph > 0) {
                        inParagraph--
                        builder.append('\n')
                    }
                }
                // Only text inside a paragraph counts: pretty-printed ODF indents its block elements.
                XmlPullParser.TEXT -> if (inParagraph > 0) builder.append(parser.text ?: "")
                else -> Unit
            }
            event = parser.next()
        }
        return builder.toString().trimEnd('\n')
    }

    /** Table (sheet) names of an OpenDocument `content.xml`, in document order. */
    fun odsSheetNames(contentXml: String): List<String> =
        XmlQuerySupport.xmlAttributeValues(contentXml, "table", "name")

    /**
     * Renders the requested OpenDocument spreadsheet table as rows of tab-separated values, rows
     * separated by newlines. [sheet] is a table name (case-insensitive) or a 1-based index; `null`
     * selects the first table. Returns `null` when the requested table does not exist.
     */
    fun odsSheetText(contentXml: String, sheet: String?): String? =
        odsSheetRows(contentXml, sheet)?.joinToString("\n") { row -> row.joinToString("\t") }

    /**
     * Extracts the requested OpenDocument spreadsheet table as structured rows of cells. [sheet] is a
     * table name (case-insensitive) or a 1-based index; `null` selects the first table. Returns `null`
     * when the requested table does not exist. Merged cells are treated as normal cells.
     */
    fun odsSheetRows(contentXml: String, sheet: String?): List<List<String>>? {
        if (contentXml.isBlank()) return null
        val names = odsSheetNames(contentXml)
        if (names.isEmpty()) return null
        val requested = sheet?.trim()?.takeIf { it.isNotEmpty() }
        val target = when {
            requested == null -> 0
            requested.toIntOrNull() != null -> requested.toInt() - 1
            else -> names.indexOfFirst { it.equals(requested, ignoreCase = true) }
        }
        if (target < 0 || target >= names.size) return null

        val rows = mutableListOf<List<String>>()
        val parser = XmlPullSupport.newPullParser()
        parser.setInput(StringReader(contentXml))
        var event = parser.eventType
        var tableIndex = -1
        var inTarget = false
        var inRow = false
        var inCell = false
        val cellText = StringBuilder()
        var rowCells = mutableListOf<String>()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (XmlPullSupport.localName(parser)) {
                    "table" -> {
                        tableIndex++
                        inTarget = tableIndex == target
                    }
                    "table-row" -> if (inTarget) {
                        inRow = true
                        rowCells = mutableListOf()
                    }
                    "table-cell", "covered-table-cell" -> if (inTarget && inRow) {
                        inCell = true
                        cellText.setLength(0)
                    }
                    "p" -> if (inCell && cellText.isNotEmpty()) cellText.append(' ')
                    "tab" -> if (inCell) cellText.append('\t')
                    "s" -> if (inCell) cellText.append(" ".repeat(spaceCount(parser)))
                    "line-break" -> if (inCell) cellText.append('\n')
                }
                XmlPullParser.END_TAG -> when (XmlPullSupport.localName(parser)) {
                    "table-cell", "covered-table-cell" -> if (inCell) {
                        rowCells.add(cellText.toString().trim())
                        inCell = false
                    }
                    "table-row" -> if (inRow) {
                        rows.add(rowCells.toList())
                        inRow = false
                    }
                    "table" -> if (inTarget) return rows
                }
                XmlPullParser.TEXT -> if (inCell) cellText.append(parser.text ?: "")
                else -> Unit
            }
            event = parser.next()
        }
        return rows
    }

    /**
     * Extracts an OpenDocument presentation `content.xml` as one list of paragraph strings per
     * `<draw:page>`, so a caller can treat each page as a slide (first paragraph = title).
     */
    fun odpSlides(contentXml: String): List<List<String>> {
        if (contentXml.isBlank()) return emptyList()
        val pages = mutableListOf<List<String>>()
        val parser = XmlPullSupport.newPullParser()
        parser.setInput(StringReader(contentXml))
        var event = parser.eventType
        var inPage = false
        var inParagraph = false
        val paragraph = StringBuilder()
        var pageParagraphs = mutableListOf<String>()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (XmlPullSupport.localName(parser)) {
                    "page" -> {
                        inPage = true
                        pageParagraphs = mutableListOf()
                    }
                    "p" -> if (inPage) {
                        inParagraph = true
                        paragraph.setLength(0)
                    }
                }
                XmlPullParser.END_TAG -> when (XmlPullSupport.localName(parser)) {
                    "p" -> if (inParagraph) {
                        pageParagraphs.add(paragraph.toString().trim())
                        inParagraph = false
                    }
                    "page" -> if (inPage) {
                        pages.add(pageParagraphs.toList())
                        inPage = false
                    }
                }
                XmlPullParser.TEXT -> if (inParagraph) paragraph.append(parser.text ?: "")
                else -> Unit
            }
            event = parser.next()
        }
        return pages
    }

    /** Repeat count of a `<text:s text:c="N"/>` space element (clamped, defaulting to 1). */
    private fun spaceCount(parser: XmlPullParser): Int {        val raw = parser.getAttributeValue(null, "c")
            ?: XmlPullSupport.attributeByLocalName(parser, "c")
            ?: return 1
        return raw.toIntOrNull()?.coerceIn(0, 10_000) ?: 1
    }
}
