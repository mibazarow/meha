package com.andharness.ru.data.tools.documents

import java.io.StringReader
import org.xmlpull.v1.XmlPullParser

/**
 * Generic XML queries (text content, element counts, attribute values) with namespace prefixes
 * ignored, so `w:p`, `text:p` and `p` all match `p`.
 */
internal object XmlQuerySupport {

    /**
     * Concatenates the text content of [xml]. When [tag] is given, only text inside elements whose
     * local name equals [tag] is collected; otherwise all text is collected. Entities are decoded by
     * the parser (`&amp;`, `&#1055;`, …).
     */
    fun xmlTextContent(xml: String, tag: String? = null): String {
        if (xml.isBlank()) return ""
        val builder = StringBuilder()
        val parser = XmlPullSupport.newPullParser()
        parser.setInput(StringReader(xml))
        var open = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG ->
                    if (tag != null && XmlPullSupport.localName(parser) == tag) open++
                XmlPullParser.END_TAG ->
                    if (tag != null && open > 0 && XmlPullSupport.localName(parser) == tag) open--
                XmlPullParser.TEXT -> {
                    val text = parser.text
                    if (text != null && (tag == null || open > 0)) builder.append(text)
                }
                else -> Unit
            }
            event = parser.next()
        }
        return builder.toString()
    }

    /** Counts elements whose local name equals [tag] (namespace prefixes ignored). */
    fun countXmlElements(xml: String, tag: String): Int {
        if (xml.isBlank()) return 0
        var count = 0
        val parser = XmlPullSupport.newPullParser()
        parser.setInput(StringReader(xml))
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && XmlPullSupport.localName(parser) == tag) count++
            event = parser.next()
        }
        return count
    }

    /**
     * Collects the values of [attribute] on every element whose local name equals [tag], in document
     * order. The attribute is matched by local name first and by [attribute] verbatim second, so both
     * `name` and `r:id` (asked as `id`) resolve.
     */
    fun xmlAttributeValues(xml: String, tag: String, attribute: String): List<String> {
        if (xml.isBlank()) return emptyList()
        val values = mutableListOf<String>()
        val parser = XmlPullSupport.newPullParser()
        parser.setInput(StringReader(xml))
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && XmlPullSupport.localName(parser) == tag) {
                val value = parser.getAttributeValue(null, attribute)
                    ?: XmlPullSupport.attributeByLocalName(parser, attribute)
                if (value != null) values += value
            }
            event = parser.next()
        }
        return values
    }
}
