package com.andharness.ru.data.tools.documents

/** Ordering of `ppt/slides/slideN.xml` archive entries by slide number. */
internal object PptxSlideEntries {

    /**
     * The `ppt/slides/slideN.xml` entries of [entryNames], ordered by slide number (a lexical sort
     * would put `slide10` before `slide2`). Other entries are ignored.
     */
    fun sortedSlideEntries(entryNames: List<String>): List<String> =
        entryNames.mapNotNull { name ->
            SLIDE_ENTRY.find(name)?.let { match ->
                match.groupValues[1].toIntOrNull()?.let { number -> number to name }
            }
        }.sortedBy { it.first }.map { it.second }

    private val SLIDE_ENTRY = Regex("""^ppt/slides/slide(\d+)\.xml$""")
}
