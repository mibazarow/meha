package com.andharness.ru.data.tools.documents

import java.util.Locale

/**
 * Pure conversion mapping for `office_convert`: given the source's content kind (text, table or
 * presentation) and a target format, produce the target bytes with the same hand-rolled writers the
 * rest of the Documents tooling uses. No layout engine is involved, so the operation is honest about
 * what it preserves: it converts the *content* (text or cells), not formatting.
 *
 * Unsupported combinations (e.g. anything → PDF, which needs a real layout engine) refuse with an
 * [IllegalArgumentException] the caller turns into an honest tool message.
 *
 * References:
 * - OOXML SpreadsheetML / WordprocessingML — https://learn.microsoft.com/en-us/office/open-xml/
 * - OpenDocument packages — https://docs.oasis-open.org/office/v1.2/os/OpenDocument-v1.2-os-part3.html
 */
internal object OfficeConvert {

    /** What the source document holds, which decides the sensible target formats. */
    enum class SourceKind { TEXT, TABLE, PRESENTATION }

    /** A target the writers can actually produce, with its file extension. */
    enum class Target(val format: DocumentFormat, val extension: String) {
        TXT(DocumentFormat.TEXT, "txt"),
        MD(DocumentFormat.MARKDOWN, "md"),
        HTML(DocumentFormat.HTML, "html"),
        CSV(DocumentFormat.CSV, "csv"),
        TSV(DocumentFormat.TSV, "tsv"),
        JSON(DocumentFormat.JSON, "json"),
        XLSX(DocumentFormat.XLSX, "xlsx"),
        ODS(DocumentFormat.ODS, "ods"),
        DOCX(DocumentFormat.DOCX, "docx"),
        ODT(DocumentFormat.ODT, "odt"),
        RTF(DocumentFormat.RTF, "rtf"),
        PPTX(DocumentFormat.PPTX, "pptx"),
        ODP(DocumentFormat.ODP, "odp");

        companion object {
            /** Common name aliases accepted on top of the enum name and extension. */
            private val ALIASES = mapOf(
                "markdown" to Target.MD,
                "htm" to Target.HTML,
                "text" to Target.TXT,
            )

            /** Resolves a target from a format name or extension (with or without a leading dot). */
            fun from(raw: String?): Target? {
                val key = raw?.trim()?.removePrefix(".")?.lowercase(Locale.ROOT) ?: return null
                if (key.isEmpty()) return null
                ALIASES[key]?.let { return it }
                return entries.firstOrNull {
                    it.name.equals(key, ignoreCase = true) || it.extension == key
                }
            }
        }
    }

    /** The content kind of [format], or `null` when the format cannot be converted. */
    fun sourceKind(format: DocumentFormat): SourceKind? = when (format) {
        DocumentFormat.DOCX, DocumentFormat.ODT, DocumentFormat.RTF,
        DocumentFormat.TEXT, DocumentFormat.MARKDOWN, DocumentFormat.HTML,
        DocumentFormat.JSON, DocumentFormat.XML -> SourceKind.TEXT
        DocumentFormat.XLSX, DocumentFormat.ODS, DocumentFormat.CSV, DocumentFormat.TSV -> SourceKind.TABLE
        DocumentFormat.PPTX, DocumentFormat.ODP -> SourceKind.PRESENTATION
        else -> null
    }

    /** Whether [target] can be produced from a source of [kind]. */
    fun supports(target: Target, kind: SourceKind): Boolean = when (kind) {
        SourceKind.TEXT -> target in setOf(
            Target.TXT, Target.MD, Target.HTML, Target.DOCX, Target.ODT, Target.RTF
        )
        SourceKind.TABLE -> target in setOf(
            Target.CSV, Target.TSV, Target.JSON, Target.TXT, Target.MD, Target.XLSX, Target.ODS
        )
        SourceKind.PRESENTATION -> target in setOf(Target.TXT, Target.MD)
    }

    /**
     * Builds the converted bytes. [text] is the source's extracted text (used for [SourceKind.TEXT]
     * and [SourceKind.PRESENTATION]), [rows] the source's cells (used for [SourceKind.TABLE]).
     *
     * Throws [IllegalArgumentException] for an unsupported target/kind combination, so the caller can
     * report an honest refusal instead of writing a wrong file.
     */
    fun convert(
        target: Target,
        kind: SourceKind,
        text: String,
        rows: List<List<String>>,
        title: String? = null,
    ): ByteArray {
        require(supports(target, kind)) {
            "Формат ${target.format.name} не поддерживается для этого типа документа."
        }
        return when (target) {
            Target.TXT -> rawText(kind, text, rows).toByteArray(Charsets.UTF_8)
            Target.MD -> when (kind) {
                SourceKind.TABLE -> markdownTable(rows).toByteArray(Charsets.UTF_8)
                else -> text.toByteArray(Charsets.UTF_8)
            }
            Target.HTML -> when (kind) {
                SourceKind.TABLE -> htmlTable(rows, title).toByteArray(Charsets.UTF_8)
                else -> htmlText(text, title).toByteArray(Charsets.UTF_8)
            }
            Target.CSV -> DocumentWriter.csv(rows, ',')
            Target.TSV -> DocumentWriter.csv(rows, '\t')
            Target.JSON -> rowsToJson(rows).toByteArray(Charsets.UTF_8)
            Target.XLSX -> DocumentWriter.xlsx(rows)
            Target.ODS -> DocumentWriter.ods(rows)
            Target.DOCX -> DocumentWriter.docx(text, title)
            Target.ODT -> DocumentWriter.odt(text, title)
            Target.RTF -> DocumentWriter.rtf(text)
            Target.PPTX, Target.ODP -> throw IllegalArgumentException(
                "Формат ${target.format.name} нельзя получить конвертацией одного документа — " +
                    "используйте pptx_create/odp_create или office_merge."
            )
        }
    }

    /** A plain-text rendering of the source for the TXT target (cells become tab-separated rows). */
    fun rawText(kind: SourceKind, text: String, rows: List<List<String>>): String = when (kind) {
        SourceKind.TABLE -> rows.joinToString("\n") { row -> row.joinToString("\t") }
        else -> text
    }

    /** Cells as a compact JSON array of arrays (values are always strings). */
    fun rowsToJson(rows: List<List<String>>): String =
        kotlinx.serialization.json.buildJsonArray {
            rows.forEach { row ->
                add(kotlinx.serialization.json.buildJsonArray {
                    row.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                })
            }
        }.toString()

    /** GitHub-style Markdown table; the first row is treated as the header row. */
    fun markdownTable(rows: List<List<String>>): String {
        if (rows.isEmpty()) return ""
        val columns = rows.maxOf { it.size }
        val builder = StringBuilder()
        rows.forEachIndexed { index, row ->
            builder.append('|')
            for (column in 0 until columns) {
                builder.append(' ').append(row.getOrElse(column) { "" }.replace("|", "\\|")).append(" |")
            }
            builder.append('\n')
            if (index == 0) {
                builder.append('|')
                repeat(columns) { builder.append(" --- |") }
                builder.append('\n')
            }
        }
        return builder.toString().trimEnd('\n')
    }

    private fun htmlText(text: String, title: String?): String =
        "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"UTF-8\">\n" +
            (title?.let { "<title>${DocumentXml.xmlEscape(it)}</title>\n" } ?: "") +
            "</head>\n<body>\n<pre>${DocumentXml.xmlEscape(text)}</pre>\n</body>\n</html>"

    private fun htmlTable(rows: List<List<String>>, title: String?): String {
        val builder = StringBuilder()
        builder.append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"UTF-8\">\n")
        if (title != null) builder.append("<title>").append(DocumentXml.xmlEscape(title)).append("</title>\n")
        builder.append("</head>\n<body>\n<table border=\"1\">\n")
        rows.forEachIndexed { index, row ->
            val tag = if (index == 0) "th" else "td"
            builder.append("<tr>")
            row.forEach { builder.append('<').append(tag).append('>').append(DocumentXml.xmlEscape(it)).append("</$tag>") }
            builder.append("</tr>\n")
        }
        builder.append("</table>\n</body>\n</html>")
        return builder.toString()
    }
}
