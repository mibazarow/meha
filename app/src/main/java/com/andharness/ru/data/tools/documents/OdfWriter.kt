package com.andharness.ru.data.tools.documents

/**
 * Minimal OpenDocument (ODT/ODS) package generation: one `<text:p>` per line / one table of rows and
 * cells, wrapped in a valid ODF package.
 *
 * References:
 * - OpenDocument (`mimetype` must be the first, STORED entry) —
 *   https://docs.oasis-open.org/office/v1.2/os/OpenDocument-v1.2-os-part3.html
 */
internal object OdfWriter {

    /** ODF media types; `DocumentFormatDetector` recognises exactly these strings. */
    const val ODF_MIMETYPE_TEXT = "application/vnd.oasis.opendocument.text"
    const val ODF_MIMETYPE_SPREADSHEET = "application/vnd.oasis.opendocument.spreadsheet"
    const val ODF_MIMETYPE_PRESENTATION = "application/vnd.oasis.opendocument.presentation"

    /** A minimal OpenDocument text package: one `<text:p>` per line of [text]. */
    fun odt(text: String, title: String? = null): ByteArray {
        val content = DocumentXml.ODF_HEADER +
            "<office:document-content " +
            "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" " +
            "xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" " +
            "xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\" " +
            "office:version=\"1.2\">" +
            "<office:body><office:text>${odtParagraphs(text)}</office:text></office:body>" +
            "</office:document-content>"
        return odfPackage(ODF_MIMETYPE_TEXT, content, title)
    }

    /** One `<text:p>` per line of [text]. */
    private fun odtParagraphs(text: String): String {
        val paragraphs = StringBuilder()
        for (line in text.split('\n')) {
            paragraphs.append("<text:p>")
                .append(DocumentXml.xmlEscape(DocumentXml.xmlSafe(line).removeSuffix("\r")))
                .append("</text:p>")
        }
        return paragraphs.toString()
    }

    /** Inserts [text] as new paragraphs just before `</office:text>`, keeping the rest intact. */
    fun appendOdtText(contentXml: String, text: String): String {
        val marker = contentXml.indexOf("</office:text>")
        require(marker >= 0) { "content.xml не содержит </office:text>." }
        return contentXml.substring(0, marker) + odtParagraphs(text) + contentXml.substring(marker)
    }

    /** A minimal OpenDocument spreadsheet package: one table ([sheetName]) of rows/cells. */
    fun ods(rows: List<List<String>>, sheetName: String = "Sheet1"): ByteArray {
        val content = DocumentXml.ODF_HEADER +
            "<office:document-content " +
            "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" " +
            "xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" " +
            "xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\" " +
            "office:version=\"1.2\">" +
            "<office:body><office:spreadsheet>" +
            "<table:table table:name=\"${DocumentXml.xmlEscape(DocumentXml.xmlSafe(sheetName))}\">${odsRows(rows)}</table:table>" +
            "</office:spreadsheet></office:body>" +
            "</office:document-content>"
        return odfPackage(ODF_MIMETYPE_SPREADSHEET, content, title = null)
    }

    /** One `<table:table-row>` per row, cells as string cells with a `<text:p>`. */
    private fun odsRows(rows: List<List<String>>): String {
        val body = StringBuilder()
        for (cells in rows) {
            body.append("<table:table-row>")
            for (value in cells) {
                body.append("<table:table-cell office:value-type=\"string\"><text:p>")
                    .append(DocumentXml.xmlEscape(DocumentXml.xmlSafe(value)))
                    .append("</text:p></table:table-cell>")
            }
            body.append("</table:table-row>")
        }
        return body.toString()
    }

    /** Appends [rows] to the first table of [contentXml], just before its `</table:table>`. */
    fun appendOdsRows(contentXml: String, rows: List<List<String>>): String {
        if (rows.isEmpty()) return contentXml
        val marker = contentXml.indexOf("</table:table>")
        require(marker >= 0) { "content.xml не содержит </table:table>." }
        return contentXml.substring(0, marker) + odsRows(rows) + contentXml.substring(marker)
    }

    /** A minimal OpenDocument presentation package: one `<draw:page>` per [slide]. */
    fun odp(slides: List<PptxWriter.Slide>): ByteArray {
        val content = DocumentXml.ODF_HEADER +
            "<office:document-content " +
            "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" " +
            "xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" " +
            "xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\" " +
            "xmlns:draw=\"urn:oasis:names:tc:opendocument:xmlns:drawing:1.0\" " +
            "xmlns:presentation=\"urn:oasis:names:tc:opendocument:xmlns:presentation:1.0\" " +
            "xmlns:svg=\"urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0\" " +
            "office:version=\"1.2\">" +
            "<office:body><office:presentation>${odpPages(slides)}</office:presentation></office:body>" +
            "</office:document-content>"
        return odfPackage(ODF_MIMETYPE_PRESENTATION, content, title = null)
    }

    /** One `<draw:page>` per slide: a title frame plus a body frame with the bullet lines. */
    private fun odpPages(slides: List<PptxWriter.Slide>): String {
        val body = StringBuilder()
        slides.forEachIndexed { index, slide ->
            body.append("<draw:page draw:name=\"Слайд ${index + 1}\" draw:style-name=\"dp1\" " +
                "draw:master-page-name=\"Default\">")
            slide.title?.takeIf { it.isNotBlank() }?.let { title ->
                body.append(odpFrame("2cm", "1.5cm", title, bullet = false))
            }
            if (slide.lines.isNotEmpty()) {
                body.append(odpFrame("2cm", "5cm", slide.lines.joinToString("\n"), bullet = true))
            }
            body.append("</draw:page>")
        }
        return body.toString()
    }

    private fun odpFrame(x: String, y: String, text: String, bullet: Boolean): String {
        val paragraphs = StringBuilder()
        for (line in text.split('\n')) {
            val content = if (bullet && line.isNotEmpty()) "• $line" else line
            paragraphs.append("<text:p>")
                .append(DocumentXml.xmlEscape(DocumentXml.xmlSafe(content)))
                .append("</text:p>")
        }
        return "<draw:frame draw:style-name=\"fr1\" draw:layer=\"layout\" " +
            "svg:width=\"24cm\" svg:height=\"3cm\" svg:x=\"$x\" svg:y=\"$y\">" +
            "<draw:text-box>$paragraphs</draw:text-box></draw:frame>"
    }

    /** Appends one slide `<draw:page>` to [contentXml], just before `</office:presentation>`. */
    fun appendOdpPage(contentXml: String, slide: PptxWriter.Slide): String {
        val marker = contentXml.indexOf("</office:presentation>")
        require(marker >= 0) { "content.xml не содержит </office:presentation>." }
        return contentXml.substring(0, marker) + odpPages(listOf(slide)) + contentXml.substring(marker)
    }

    /**
     * Builds an ODF package. The `mimetype` entry must come first and be stored uncompressed
     * (no DEFLATE), otherwise `DocumentFormatDetector` and office suites cannot sniff the type.
     */
    private fun odfPackage(mimetype: String, contentXml: String, title: String?): ByteArray {
        val meta = DocumentXml.ODF_HEADER +
            "<office:document-meta " +
            "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" " +
            "xmlns:meta=\"urn:oasis:names:tc:opendocument:xmlns:meta:1.0\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "office:version=\"1.2\">" +
            "<office:meta>" +
            "<meta:generator>Меха</meta:generator>" +
            (title?.let { "<dc:title>${DocumentXml.xmlEscape(DocumentXml.xmlSafe(it))}</dc:title>" } ?: "") +
            "</office:meta></office:document-meta>"
        val styles = DocumentXml.ODF_HEADER +
            "<office:document-styles " +
            "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" " +
            "xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\" " +
            "xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" " +
            "xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\" " +
            "office:version=\"1.2\">" +
            "<office:styles/><office:automatic-styles/><office:master-styles/>" +
            "</office:document-styles>"
        val manifest = DocumentXml.ODF_HEADER +
            "<manifest:manifest " +
            "xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\" " +
            "manifest:version=\"1.2\">" +
            "<manifest:file-entry manifest:full-path=\"/\" manifest:media-type=\"$mimetype\"/>" +
            "<manifest:file-entry manifest:full-path=\"content.xml\" manifest:media-type=\"text/xml\"/>" +
            "<manifest:file-entry manifest:full-path=\"styles.xml\" manifest:media-type=\"text/xml\"/>" +
            "<manifest:file-entry manifest:full-path=\"meta.xml\" manifest:media-type=\"text/xml\"/>" +
            "</manifest:manifest>"
        return ZipPackageWriter.zipBytes(
            entries = listOf(
                "META-INF/manifest.xml" to manifest.toByteArray(Charsets.UTF_8),
                "content.xml" to contentXml.toByteArray(Charsets.UTF_8),
                "meta.xml" to meta.toByteArray(Charsets.UTF_8),
                "styles.xml" to styles.toByteArray(Charsets.UTF_8),
            ),
            stored = "mimetype" to mimetype.toByteArray(Charsets.UTF_8),
        )
    }
}
