package com.andharness.ru.data.tools.documents

/** Plain-text → rows/cells parsing shared by the spreadsheet writers and the editor. */
internal object TableTextParser {

    /**
     * Splits a plain-text table into rows and cells: rows by `\n`, cells by tab when present,
     * otherwise by `;`, otherwise by `,`.
     */
    fun parseTableText(content: String): List<List<String>> {
        if (content.isEmpty()) return emptyList()
        return content.split('\n').map { line ->
            val raw = line.removeSuffix("\r")
            when {
                raw.contains('\t') -> raw.split('\t')
                raw.contains(';') -> raw.split(';')
                else -> raw.split(',')
            }
        }
    }
}
