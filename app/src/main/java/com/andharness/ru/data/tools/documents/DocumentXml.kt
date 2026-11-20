package com.andharness.ru.data.tools.documents

/**
 * XML text hygiene shared by every document writer: the two declaration headers and the
 * escape/sanitize helpers that keep generated parts well-formed for arbitrary model output.
 */
internal object DocumentXml {

    /** `<?xml ... standalone="yes"?>` header used by the OOXML (DOCX/XLSX) parts. */
    const val HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"

    /** `<?xml ...?>` header used by the OpenDocument (ODT/ODS) parts. */
    const val ODF_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"

    /** Escapes the XML metacharacters `& < > "` (and `'` for attribute safety). */
    fun xmlEscape(value: String): String {
        val builder = StringBuilder(value.length + 16)
        for (ch in value) {
            when (ch) {
                '&' -> builder.append("&amp;")
                '<' -> builder.append("&lt;")
                '>' -> builder.append("&gt;")
                '"' -> builder.append("&quot;")
                '\'' -> builder.append("&apos;")
                else -> builder.append(ch)
            }
        }
        return builder.toString()
    }

    /**
     * Drops characters that are illegal in XML 1.0 (all control characters except tab/LF/CR, and
     * lone surrogates) so every generated part stays well-formed even for arbitrary model output.
     */
    fun xmlSafe(value: String): String {
        val builder = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            if (isValidXmlChar(codePoint)) builder.appendCodePoint(codePoint)
            index += Character.charCount(codePoint)
        }
        return builder.toString()
    }

    private fun isValidXmlChar(codePoint: Int): Boolean =
        codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD ||
            (codePoint in 0x20..0xD7FF) ||
            (codePoint in 0xE000..0xFFFD) ||
            (codePoint in 0x10000..0x10FFFF)
}
