package com.andharness.ru.data.tools.documents

import java.io.StringWriter
import org.apache.commons.csv.CSVFormat

/** RFC 4180-delimited text generation (quoting/escaping handled by Commons CSV). */
internal object CsvWriter {

    /** RFC 4180-delimited text (quoting/escaping handled by Commons CSV) with [delimiter]. */
    fun csv(rows: List<List<String>>, delimiter: Char = ','): ByteArray {
        val format = CSVFormat.Builder.create(CSVFormat.DEFAULT)
            .setDelimiter(delimiter)
            .get()
        val writer = StringWriter()
        format.print(writer).use { printer ->
            for (row in rows) {
                printer.printRecord(row)
            }
        }
        return writer.toString().toByteArray(Charsets.UTF_8)
    }
}
