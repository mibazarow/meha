package com.andharness.ru.domain.documents


object PdfTextLayout {

    /** Message returned (and reported by the tools) when nothing renderable is left. */
    const val BLANK_ERROR = "Текст пуст: нечего записывать в PDF."

    /** Font size, line advance and left indent for [style]. */
    fun metricsFor(style: PdfLineStyle, options: PdfLayoutOptions): PdfStyleMetrics = when (style) {
        PdfLineStyle.BODY -> PdfStyleMetrics(options.bodyFontSize, options.bodyLeading, 0f)
        PdfLineStyle.HEADING1 ->
            PdfStyleMetrics(options.heading1FontSize, options.heading1Leading, 0f)
        PdfLineStyle.HEADING2 ->
            PdfStyleMetrics(options.heading2FontSize, options.heading2Leading, 0f)
        PdfLineStyle.BULLET ->
            PdfStyleMetrics(options.bodyFontSize, options.bodyLeading, options.bulletIndent)
    }

    /** Plain text split into unstyled logical lines; `\r\n`/`\r` are normalised away. */
    fun plainLines(text: String): List<PdfLogicalLine> =
        text.replace("\r\n", "\n").replace('\r', '\n')
            .split('\n')
            .map { PdfLogicalLine(listOf(PdfSpan(it))) }

    /** The honest error for a blank document, or `null` when at least one line has content. */
    fun blankError(lines: List<PdfLogicalLine>): String? =
        if (lines.isEmpty() || lines.all { it.isBlank }) BLANK_ERROR else null

    /**
     * Wraps [spans] to [maxWidth] at [fontSize] using the injected [measure] `(text, fontSize) ->
     * width`. Always returns at least one line, so an empty logical line still consumes a row.
     */
    fun wrapSpans(
        spans: List<PdfSpan>,
        fontSize: Float,
        maxWidth: Float,
        measure: (String, Float) -> Float,
    ): List<List<PdfSpan>> {
        if (maxWidth <= 0f) return listOf(mergeSpans(spans))
        val characters = spans.flatMap { span ->
            span.text.map { PdfSpan(it.toString(), span.bold) }
        }
        if (characters.isEmpty()) return listOf(listOf(PdfSpan("")))
        val lines = mutableListOf<List<PdfSpan>>()
        var current = mutableListOf<PdfSpan>()
        var width = 0f
        for (character in characters) {
            val characterWidth = measure(character.text, fontSize)
            if (current.isNotEmpty() && width + characterWidth > maxWidth) {
                lines += mergeSpans(current)
                current = mutableListOf()
                width = 0f
            }
            current += character
            width += characterWidth
        }
        if (current.isNotEmpty()) lines += mergeSpans(current)
        return lines
    }

    /**
     * Wraps and paginates [lines]. Returns one list of [PdfPhysicalLine] per page, or an empty list
     * when every logical line is blank (the caller turns that into [BLANK_ERROR]).
     */
    fun paginate(
        lines: List<PdfLogicalLine>,
        options: PdfLayoutOptions,
        measure: (String, Float) -> Float,
    ): List<List<PdfPhysicalLine>> {
        if (lines.isEmpty() || lines.all { it.isBlank }) return emptyList()

        val pages = mutableListOf<List<PdfPhysicalLine>>()
        var current = mutableListOf<PdfPhysicalLine>()
        var used = 0f
        for (line in lines) {
            val metrics = metricsFor(line.style, options)
            val spans = if (line.style == PdfLineStyle.BULLET) {
                listOf(PdfSpan("• ")) + line.spans
            } else {
                line.spans
            }
            val wrapped = wrapSpans(
                spans = spans,
                fontSize = metrics.fontSize,
                maxWidth = options.contentWidth - metrics.xOffset,
                measure = measure,
            )
            for (wrappedSpans in wrapped) {
                if (current.isNotEmpty() && used + metrics.advance > options.contentHeight) {
                    pages += current
                    current = mutableListOf()
                    used = 0f
                }
                current += PdfPhysicalLine(
                    spans = wrappedSpans,
                    fontSize = metrics.fontSize,
                    advance = metrics.advance,
                    xOffset = metrics.xOffset,
                    style = line.style,
                )
                used += metrics.advance
            }
        }
        if (current.isNotEmpty()) pages += current
        return pages
    }

    /** Coalesces neighbouring runs that share an emphasis flag, so drawing stays compact. */
    private fun mergeSpans(spans: List<PdfSpan>): List<PdfSpan> {
        val merged = mutableListOf<PdfSpan>()
        for (span in spans) {
            val last = merged.lastOrNull()
            if (last != null && last.bold == span.bold) {
                merged[merged.size - 1] = last.copy(text = last.text + span.text)
            } else {
                merged += span
            }
        }
        return merged
    }
}
