package com.andharness.ru.data.tools.documents

/**
 * Basic RTF 1.x generation, with the same escaping rules for creation and appending.
 *
 * References:
 * - RTF 1.9 specification — https://learn.microsoft.com/en-us/openspecs/office_protocols/ms-rtf/
 */
internal object RtfWriter {

    /** Basic RTF 1.x document: control characters escaped, newlines turned into `\par`. */
    fun rtf(text: String): ByteArray {
        val builder = StringBuilder("{\\rtf1\\ansi\\deff0")
        builder.append("{\\fonttbl{\\f0\\fnil\\fcharset0 Arial;}}")
        builder.append("\\f0\\fs22 ")
        builder.append(escapeRtf(text))
        builder.append("}")
        return builder.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Appends [text] as a new paragraph (`\par`) to an existing RTF document, just before its final
     * closing brace. The text is escaped with the same rules as [rtf].
     */
    fun appendRtf(existing: String, text: String): String {
        val marker = existing.lastIndexOf('}')
        require(marker >= 0) { "RTF-документ не содержит закрывающей скобки." }
        return existing.substring(0, marker) + "\\par\n" + escapeRtf(text) + existing.substring(marker)
    }

    private fun escapeRtf(text: String): String {
        val builder = StringBuilder(text.length + 16)
        for (ch in text) {
            when (ch) {
                '\\' -> builder.append("\\\\")
                '{' -> builder.append("\\{")
                '}' -> builder.append("\\}")
                '\n' -> builder.append("\\par\n")
                '\r' -> Unit
                // Plain ASCII stays literal; anything else goes through an RTF unicode escape so
                // non-Latin text (Cyrillic, …) survives the `\ansi` (Windows-1252) default codepage.
                else -> if (ch.code in 32..126) {
                    builder.append(ch)
                } else if (ch.code <= 0xFFFF) {
                    builder.append("\\u").append(ch.code).append('?')
                } else {
                    builder.append('?')
                }
            }
        }
        return builder.toString()
    }
}
