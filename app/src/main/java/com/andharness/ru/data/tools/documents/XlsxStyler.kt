package com.andharness.ru.data.tools.documents

import java.util.Locale

/**
 * Basic SpreadsheetML cell formatting for `xlsx_style`: bold, a solid fill colour and/or a custom
 * number format applied to a cell range. Best-effort OOXML — the needed `<font>`/`<fill>`/`<numFmt>`
 * entries are appended to `xl/styles.xml`, a new `<xf>` cell format is added to `<cellXfs>`, and the
 * range's cells are pointed at it with `s="N"`. Existing values and untouched parts stay intact.
 *
 * This is deliberately not a full styles engine: only these three attributes are supported and the
 * operation fails honestly when the stylesheet shape is unexpected.
 *
 * References:
 * - SpreadsheetML styles (`<fonts>`, `<fills>`, `<numFmts>`, `<cellXfs>`) —
 *   https://learn.microsoft.com/en-us/office/open-xml/spreadsheet/spreadsheetml
 * - Cell `s` attribute (cellXfs index) —
 *   https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.spreadsheet.cellformat
 */
internal object XlsxStyler {

    /** Requested formatting; [isEmpty] means "nothing to apply". */
    data class Style(
        val bold: Boolean = false,
        val fillColor: String? = null,
        val numberFormat: String? = null,
    ) {
        val isEmpty: Boolean get() = !bold && fillColor == null && numberFormat == null
    }

    /** The rewritten parts plus the cell-format index every styled cell now points at. */
    data class Styled(
        val sheetXml: String,
        val stylesXml: String,
        val styleIndex: Int,
        val cellCount: Int,
    )

    /** Excel's first custom number-format id (built-ins occupy 0–163). */
    private const val FIRST_CUSTOM_NUMFMT_ID = 164

    /** Upper bound for one style call, so a huge range cannot blow up the stylesheet. */
    private const val MAX_CELLS = 20_000

    /**
     * Applies [style] to every cell of [range] (e.g. `B2` or `A1:C3`, comma-separated ranges allowed).
     * Throws [IllegalArgumentException] for a malformed range, a bad colour, or an empty style.
     */
    fun style(sheetXml: String, stylesXml: String, range: String, style: Style): Styled {
        require(!style.isEmpty) {
            "Не задан ни один параметр стиля: bold, fill_color или number_format."
        }
        val refs = parseRange(range)
            ?: throw IllegalArgumentException(
                "Некорректный диапазон: «$range». Ожидается вид A1 или A1:C3."
            )
        if (refs.isEmpty()) throw IllegalArgumentException("Диапазон «$range» пуст.")

        val built = buildStyles(stylesXml, style)
        var sheet = sheetXml
        for (ref in refs) sheet = XlsxWriter.setXlsxCellStyle(sheet, ref, built.index)
        return Styled(sheet, built.xml, built.index, refs.size)
    }

    /**
     * Expands [range] into the list of `A1`-style refs it covers (one `A1` or a `A1:C3` block per
     * comma-separated token). Returns `null` for any malformed token, and stops growing the list past
     * [MAX_CELLS] (the caller reports the honest refusal).
     */
    fun parseRange(range: String): List<String>? {
        val refs = LinkedHashSet<String>()
        for (token in range.split(',')) {
            val trimmed = token.trim()
            if (trimmed.isEmpty()) continue
            val parts = trimmed.split(':')
            val start = DocumentWriter.parseCellReference(parts[0]) ?: return null
            if (parts.size == 1) {
                if (!addRef(refs, start.column, start.row)) return null
                continue
            }
            if (parts.size != 2) return null
            val end = DocumentWriter.parseCellReference(parts[1]) ?: return null
            val minColumn = minOf(start.column, end.column)
            val maxColumn = maxOf(start.column, end.column)
            val minRow = minOf(start.row, end.row)
            val maxRow = maxOf(start.row, end.row)
            for (row in minRow..maxRow) {
                for (column in minColumn..maxColumn) {
                    if (!addRef(refs, column, row)) return null
                }
            }
        }
        return refs.toList()
    }

    private fun addRef(refs: LinkedHashSet<String>, column: Int, row: Int): Boolean {
        if (refs.size >= MAX_CELLS) {
            throw IllegalArgumentException("Диапазон слишком большой (максимум $MAX_CELLS ячеек).")
        }
        refs += "${XlsxWriter.columnName(column)}$row"
        return true
    }

    private data class BuiltStyles(val xml: String, val index: Int)

    private fun buildStyles(stylesXml: String, style: Style): BuiltStyles {
        var xml = stylesXml

        var fontId = 0
        if (style.bold) {
            fontId = childCount(xml, "fonts", "font")
            xml = appendChild(xml, "fonts", "font", BOLD_FONT)
        }

        var fillId = 0
        if (style.fillColor != null) {
            val rgb = normalizeColor(style.fillColor)
                ?: throw IllegalArgumentException(
                    "Некорректный цвет «${style.fillColor}». Ожидается #RRGGBB."
                )
            fillId = childCount(xml, "fills", "fill")
            xml = appendChild(xml, "fills", "fill", solidFill(rgb))
        }

        var numFmtId = 0
        if (style.numberFormat != null) {
            numFmtId = nextNumFmtId(xml)
            xml = addNumFmt(xml, numFmtId, style.numberFormat)
        }

        val xfIndex = childCount(xml, "cellXfs", "xf")
        xml = appendChild(xml, "cellXfs", "xf", xf(numFmtId, fontId, fillId, style))
        return BuiltStyles(xml, xfIndex)
    }

    private fun xf(numFmtId: Int, fontId: Int, fillId: Int, style: Style): String {
        val attrs = StringBuilder()
        attrs.append("numFmtId=\"").append(numFmtId)
            .append("\" fontId=\"").append(fontId)
            .append("\" fillId=\"").append(fillId)
            .append("\" borderId=\"0\" xfId=\"0\"")
        if (style.numberFormat != null && numFmtId != 0) attrs.append(" applyNumberFormat=\"1\"")
        if (style.bold) attrs.append(" applyFont=\"1\"")
        if (style.fillColor != null) attrs.append(" applyFill=\"1\"")
        return "<xf $attrs/>"
    }

    private fun solidFill(rgb: String): String =
        "<fill><patternFill patternType=\"solid\">" +
            "<fgColor rgb=\"$rgb\"/><bgColor indexed=\"64\"/>" +
            "</patternFill></fill>"

    /** `#RRGGBB`/`RRGGBB` (alpha added) or `AARRGGBB`, upper-cased; `null` when not 6/8 hex digits. */
    private fun normalizeColor(raw: String): String? {
        val hex = raw.trim().removePrefix("#")
        val digits = when (hex.length) {
            6 -> "FF$hex"
            8 -> hex
            else -> return null
        }
        return if (digits.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            digits.uppercase(Locale.ROOT)
        } else {
            null
        }
    }

    private fun nextNumFmtId(xml: String): Int {
        val existing = NUM_FMT_ID.findAll(xml).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
        return maxOf(FIRST_CUSTOM_NUMFMT_ID, (existing ?: 0) + 1)
    }

    private fun addNumFmt(xml: String, id: Int, code: String): String {
        val numFmt = "<numFmt numFmtId=\"$id\" formatCode=\"" +
            "${DocumentXml.xmlEscape(DocumentXml.xmlSafe(code))}\"/>"
        if (findContainer(xml, "numFmts") != null) {
            return appendChild(xml, "numFmts", "numFmt", numFmt)
        }
        // CT_Stylesheet requires <numFmts> before <fonts>.
        val fontsIndex = xml.indexOf("<fonts")
        require(fontsIndex >= 0) { "xl/styles.xml не содержит <fonts>." }
        return xml.substring(0, fontsIndex) +
            "<numFmts count=\"1\">$numFmt</numFmts>" +
            xml.substring(fontsIndex)
    }

    private fun childCount(xml: String, containerTag: String, childTag: String): Int {
        val container = findContainer(xml, containerTag)?.value ?: return 0
        return Regex("<$childTag\\b").findAll(container).count()
    }

    private fun appendChild(
        xml: String,
        containerTag: String,
        childTag: String,
        childXml: String,
    ): String {
        val match = findContainer(xml, containerTag)
            ?: return createContainer(xml, containerTag, childXml)
        val element = match.value
        val count = Regex("<$childTag\\b").findAll(element).count() + 1
        val updated = if (element.endsWith("/>")) {
            val open = element.dropLast(2)
            setCount("$open>", count) + childXml + "</$containerTag>"
        } else {
            val open = element.substring(0, element.length - "</$containerTag>".length)
            setCount(open, count) + childXml + "</$containerTag>"
        }
        return xml.replaceRange(match.range, updated)
    }

    private fun createContainer(xml: String, containerTag: String, childXml: String): String {
        val close = xml.indexOf("</styleSheet>")
        require(close >= 0) { "xl/styles.xml не содержит </styleSheet>." }
        val container = "<$containerTag count=\"1\">$childXml</$containerTag>"
        return xml.substring(0, close) + container + xml.substring(close)
    }

    private fun setCount(openTag: String, count: Int): String =
        if (COUNT_ATTRIBUTE.containsMatchIn(openTag)) {
            COUNT_ATTRIBUTE.replace(openTag, "count=\"$count\"")
        } else {
            openTag.dropLast(1) + " count=\"$count\">"
        }

    private fun findContainer(xml: String, tag: String): MatchResult? =
        Regex("(?s)<$tag(?:\\s[^>]*)?(?:/>|>.*?</$tag>)").find(xml)

    private val COUNT_ATTRIBUTE = Regex("""\bcount="\d+"""")
    private val NUM_FMT_ID = Regex("""<numFmt\b[^>]*\bnumFmtId="(\d+)"""")

    private const val BOLD_FONT = "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font>"
}
