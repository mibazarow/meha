package com.andharness.ru.data.tools.documents

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Minimal-but-valid WordprocessingML (DOCX) package generation: one paragraph per line, plus
 * insertion of new paragraphs into an existing `word/document.xml`.
 *
 * References:
 * - OOXML package structure —
 *   https://learn.microsoft.com/en-us/office/open-xml/word/wordprocessingml
 * - `java.util.zip.ZipOutputStream` —
 *   https://developer.android.com/reference/java/util/zip/ZipOutputStream
 */
internal object DocxWriter {

    private const val WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val PACKAGE_RELS_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val OFFICE_DOC_RELS_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val CONTENT_TYPES_NS = "http://schemas.openxmlformats.org/package/2006/content-types"
    private const val CORE_PROPS_NS =
        "http://schemas.openxmlformats.org/package/2006/metadata/core-properties"

    private const val DOCX_DOCUMENT_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"

    /** A minimal WordprocessingML package; one paragraph per line of [text]. */
    fun docx(text: String, title: String? = null): ByteArray = ZipPackageWriter.zip(
        "[Content_Types].xml" to (
            DocumentXml.HEADER +
                "<Types xmlns=\"$CONTENT_TYPES_NS\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"$DOCX_DOCUMENT_CONTENT_TYPE\"/>" +
                "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
                "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>" +
                "</Types>"
            ),
        "_rels/.rels" to (
            DocumentXml.HEADER +
                "<Relationships xmlns=\"$PACKAGE_RELS_NS\">" +
                "<Relationship Id=\"rId1\" Type=\"$OFFICE_DOC_RELS_NS/officeDocument\" Target=\"word/document.xml\"/>" +
                "<Relationship Id=\"rId2\" Type=\"$CORE_PROPS_NS\" Target=\"docProps/core.xml\"/>" +
                "<Relationship Id=\"rId3\" Type=\"$OFFICE_DOC_RELS_NS/extended-properties\" Target=\"docProps/app.xml\"/>" +
                "</Relationships>"
            ),
        "word/document.xml" to docxDocument(text),
        "word/_rels/document.xml.rels" to (DocumentXml.HEADER + "<Relationships xmlns=\"$PACKAGE_RELS_NS\"/>"),
        "docProps/core.xml" to coreProperties(title),
        "docProps/app.xml" to (
            DocumentXml.HEADER +
                "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\">" +
                "<Application>Меха</Application>" +
                "</Properties>"
            ),
    )

    /** `word/document.xml`: `<w:p><w:r><w:t xml:space="preserve">…</w:t></w:r></w:p>` per line. */
    private fun docxDocument(text: String): String =
        DocumentXml.HEADER +
            "<w:document xmlns:w=\"$WORD_NS\"><w:body>" +
            docxParagraphs(text) +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
            "<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\"/></w:sectPr>" +
            "</w:body></w:document>"

    /** One `<w:p><w:r><w:t xml:space="preserve">…</w:t></w:r></w:p>` per line of [text]. */
    private fun docxParagraphs(text: String): String {
        val body = StringBuilder()
        for (line in text.split('\n')) {
            body.append("<w:p><w:r><w:t xml:space=\"preserve\">")
                .append(DocumentXml.xmlEscape(DocumentXml.xmlSafe(line).removeSuffix("\r")))
                .append("</w:t></w:r></w:p>")
        }
        return body.toString()
    }

    /**
     * Inserts [text] as new paragraphs into `word/document.xml`, keeping the rest of the part intact.
     * A document-level `<w:sectPr>` must remain the LAST child of `<w:body>` (OOXML `CT_Body`), so
     * the paragraphs are inserted just before it; without one they go straight before `</w:body>`.
     */
    fun appendDocxText(documentXml: String, text: String): String {
        val bodyClose = documentXml.indexOf("</w:body>")
        require(bodyClose >= 0) { "word/document.xml не содержит </w:body>." }
        val bodySectPr = documentXml.lastIndexOf("<w:sectPr", bodyClose)
        val marker = if (bodySectPr >= 0) bodySectPr else bodyClose
        return documentXml.substring(0, marker) + docxParagraphs(text) + documentXml.substring(marker)
    }

    private fun coreProperties(title: String?): String {
        val timestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
        val titleXml = title?.let { "<dc:title>${DocumentXml.xmlEscape(DocumentXml.xmlSafe(it))}</dc:title>" } ?: ""
        return DocumentXml.HEADER +
            "<cp:coreProperties xmlns:cp=\"$CORE_PROPS_NS\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
            titleXml +
            "<dc:creator>Меха</dc:creator>" +
            "<cp:lastModifiedBy>Меха</cp:lastModifiedBy>" +
            "<dcterms:created xsi:type=\"dcterms:W3CDTF\">$timestamp</dcterms:created>" +
            "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">$timestamp</dcterms:modified>" +
            "</cp:coreProperties>"
    }
}
