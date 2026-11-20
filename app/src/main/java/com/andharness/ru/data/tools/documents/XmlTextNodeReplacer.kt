package com.andharness.ru.data.tools.documents

/**
 * Text-node-level find/replace inside selected XML elements. Deliberately a text transform (not a
 * DOM rewrite) so unknown parts of a foreign document survive untouched instead of being
 * re-serialized.
 */
internal object XmlTextNodeReplacer {

    /**
     * Replaces the literal [find] with [replace] only inside the character data of elements whose
     * local name is one of [tags] (namespace prefixes ignored, so `w:t`, `text:p` and `t`/`p` all
     * match). Markup, attributes and non-matching elements are left byte-for-byte intact; [xml] is
     * returned unchanged when nothing matched, which lets the caller report an honest "not found".
     */
    fun replaceInTextNodes(xml: String, find: String, replace: String, tags: List<String>): String {
        if (find.isEmpty()) return xml
        var result = xml
        for (tag in tags) {
            val escaped = Regex.escape(tag)
            val pattern = Regex(
                "(?is)(<(?:[A-Za-z0-9]+:)?$escaped\\b[^>]*>)(.*?)(</(?:[A-Za-z0-9]+:)?$escaped>)"
            )
            result = pattern.replace(result) { match ->
                val inner = match.groupValues[2]
                if (inner.contains(find)) {
                    match.groupValues[1] + inner.replace(find, replace) + match.groupValues[3]
                } else {
                    match.value
                }
            }
        }
        return result
    }
}
