package com.andharness.ru.data.tools.documents

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Parser construction and namespace-prefix-insensitive name helpers shared by every XML reader in
 * the documents tooling.
 *
 * References:
 * - `XmlPullParser` — https://developer.android.com/reference/org/xmlpull/v1/XmlPullParser
 * - `XmlPullParserFactory` — https://developer.android.com/reference/org/xmlpull/v1/XmlPullParserFactory
 */
internal object XmlPullSupport {

    /**
     * Creates an [XmlPullParser] through the platform factory. Android always provides one; a bare JVM
     * (unit tests) may expose a no-op stub, in which case the bundled kxml2 implementation is used.
     */
    fun newPullParser(): XmlPullParser {
        val fromFactory = try {
            XmlPullParserFactory.newInstance().newPullParser()
        } catch (t: Throwable) {
            null
        }
        if (fromFactory != null) return fromFactory
        @Suppress("UNCHECKED_CAST")
        return Class.forName("org.kxml2.io.KXmlParser").getDeclaredConstructor().newInstance() as XmlPullParser
    }

    /** Local name without a namespace prefix, so `w:p`, `text:p` and `p` all match `p`. */
    fun localName(parser: XmlPullParser): String {
        val name = parser.name ?: return ""
        val colon = name.lastIndexOf(':')
        return if (colon >= 0) name.substring(colon + 1) else name
    }

    /** First attribute whose local name equals [attribute] (fallback after a verbatim lookup). */
    fun attributeByLocalName(parser: XmlPullParser, attribute: String): String? {
        for (index in 0 until parser.attributeCount) {
            val name = parser.getAttributeName(index) ?: continue
            val local = name.substringAfterLast(':')
            if (local == attribute) return parser.getAttributeValue(index)
        }
        return null
    }
}
