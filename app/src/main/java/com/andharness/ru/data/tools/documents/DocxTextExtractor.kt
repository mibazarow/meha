package com.andharness.ru.data.tools.documents

import java.io.StringReader
import org.xmlpull.v1.XmlPullParser

/** Visible-text extraction from WordprocessingML / OOXML run-and-paragraph parts. */
internal object DocxTextExtractor {

    /**
     * Extracts the text of a WordprocessingML `word/document.xml`: every `<w:t>` run is concatenated
     * and each `<w:p>` closes the paragraph with a newline. Namespace prefixes are ignored, so `w:t`
     * and `t` both match; `<w:br/>` becomes a newline and `<w:tab/>` a tab.
     */
    fun docxText(documentXml: String): String =
        extractRunText(documentXml, runTag = "t", paragraphTag = "p")

    /**
     * Extracts the text of an OOXML part whose runs live in elements named [runTag] and whose
     * paragraphs end with [paragraphTag] (DOCX `word/document.xml`, PPTX `ppt/slides/slideN.xml`).
     * Prefix-insensitive; `<br>`/`<tab>` map to newline/tab, trailing paragraph breaks are dropped.
     */
    fun extractRunText(xml: String, runTag: String, paragraphTag: String): String {
        if (xml.isBlank()) return ""
        val builder = StringBuilder()
        val parser = XmlPullSupport.newPullParser()
        parser.setInput(StringReader(xml))
        var openRuns = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (XmlPullSupport.localName(parser)) {
                    runTag -> openRuns++
                    "br" -> builder.append('\n')
                    "tab" -> builder.append('\t')
                }
                XmlPullParser.END_TAG -> when (XmlPullSupport.localName(parser)) {
                    runTag -> if (openRuns > 0) openRuns--
                    paragraphTag -> builder.append('\n')
                }
                XmlPullParser.TEXT -> if (openRuns > 0) builder.append(parser.text ?: "")
                else -> Unit
            }
            event = parser.next()
        }
        return builder.toString().trimEnd('\n')
    }
}
