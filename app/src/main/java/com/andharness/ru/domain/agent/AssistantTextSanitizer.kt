package com.andharness.ru.domain.agent

/**
 * Pure, JVM-testable backstop that removes the model's INTERNAL reasoning/planning artifacts from a
 * finished assistant answer before it is stored in a chat or shown on screen.
 *
 * The system prompt instructs the model to emit only the user-facing answer, but some models still
 * leak their chain of thought. This sanitizer is deterministic, cheap and Android-free, so it can be
 * unit-tested on the JVM and invoked on every persisted assistant write.
 *
 * Removed (conservatively — only unambiguous artifacts):
 *  - complete tags: `<thinking>…</thinking>`, `<thought>…</thought>`, `<reasoning>…</reasoning>`,
 *    `<scratchpad>…</scratchpad>`, `<analysis>…</analysis>`, `<reflection>…</reflection>`
 *    (case-insensitive, multiline);
 *  - whole lines that begin with a labelled internal marker, e.g. «Размышляю:», «Мысль:»,
 *    «Plan:», «Chain of thought:» …;
 *  - whole lines that are a bare bracketed marker, e.g. `[thinking]`;
 *  - whole lines that are pure tool-call planning narration, e.g.
 *    «Мне нужно вызвать инструмент…», «I'll call the tool…».
 *  It then drops trailing spaces, collapses 3+ consecutive newlines to one blank line, and trims.
 *
 * Honest limits:
 *  - it is heuristic, not a parser. Free-form reasoning that is NOT wrapped in a tag and NOT prefixed
 *    with a known marker is left untouched on purpose (removing it could delete the real answer);
 *  - [sanitize] leaves an unclosed tag alone (it may be a partial stream); the chat pipeline uses
 *    [sanitizeRealtime], which drops an open reasoning block immediately so it never flashes on
 *    screen and then disappears when the closing tag arrives;
 *  - a line that legitimately starts with one of the markers (e.g. a plan the user explicitly asked
 *    for and which the model prefixed with «План:») is stripped too — this is the documented
 *    trade-off of a context-free filter, chosen because such leaks are far more common than a
 *    user-requested «План:» heading.
 */
object AssistantTextSanitizer {

    /** Complete `<tag>…</tag>` reasoning blocks (case-insensitive, across newlines). */
    private val REASONING_BLOCK = Regex(
        """<\s*(thinking|thought|reasoning|scratchpad|analysis|reflection)\s*>[\s\S]*?<\s*/\s*\1\s*>""",
        RegexOption.IGNORE_CASE
    )

    /**
     * A whole line opening with a labelled internal marker followed by a colon. Anchored to the line
     * start (allowing leading list/quote/markdown punctuation) so a marker word that merely appears
     * inside a normal sentence is never touched.
     */
    private val LABELLED_MARKER_LINE = Regex(
        """^[ \t]*(?:[>*-][ \t]*)*(?:""" +
            """Размышляю|Размышление|Размышления|Мысль|Мысли|Думаю|План|Планирую|Внутренне|""" +
            """Обдумываю|Рассуждение|Рассуждения|""" +
            """Thinking|Thought|Thoughts|Reasoning|Plan|Planning|Scratchpad|Analysis|Reflection|""" +
            """Internal|Chain[ -]of[ -]thought""" +
            """)[ \t]*:[ \t]*.*$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
    )

    /** A whole line that is a bare bracketed marker, e.g. `[thinking]` or `[reasoning]`. */
    private val BRACKET_MARKER_LINE = Regex(
        """^[ \t]*(?:[>*-][ \t]*)*\[(?:thinking|reasoning|scratchpad|thoughts?|analysis|reflection|internal)\][ \t]*.*$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
    )

    /**
     * A whole line that is pure tool-call planning narration. Requires the word "tool"/"инструмент"
     * right after the lead-in so ordinary sentences («Мне нужно знать…», "I need to understand…")
     * are never stripped.
     */
    private val TOOL_PLANNING_LINE = Regex(
        """^[ \t]*(?:[>*-][ \t]*)*(?:""" +
            """(?:Мне нужно (?:вызвать|использовать|применить|выполнить)|""" +
            """Сейчас (?:я )?(?:вызову|использую|применю|выполню)|""" +
            """Сначала (?:я )?(?:вызову|использую|проверю|узнаю))[ \t]+""" +
            """(?:подходящий[ \t]+|нужный[ \t]+)?(?:инструмент|функци[а-яё]*|команд[а-яё]*).*""" +
            """|""" +
            """(?:I(?:'ll| will| need to)|Let me|First,? I(?:'ll| will))[ \t]+""" +
            """(?:call|use|invoke|run|execute)[ \t]+(?:the[ \t]+|a[ \t]+)?(?:tool|function|command|instrument).*""" +
            """)$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
    )

    /**
     * An OPEN reasoning block whose closing tag has not arrived yet. Only [sanitizeRealtime] uses
     * it: during streaming a long chain of thought is still unclosed, so without this the internal
     * text would be shown to the user and then vanish once the block finally closed.
     */
    private val UNCLOSED_REASONING_BLOCK = Regex(
        """<\s*(thinking|thought|reasoning|scratchpad|analysis|reflection)\s*>[\s\S]*$""",
        RegexOption.IGNORE_CASE
    )

    private val TRAILING_SPACES = Regex("""[ \t]+\n""")
    private val BLANK_RUNS = Regex("""\n{3,}""")

    /**
     * Returns [raw] with internal-reasoning artifacts removed. Idempotent and safe for empty input.
     * Normal answer text (including multi-paragraph prose) is returned unchanged apart from trailing
     * whitespace, collapsed blank-line runs and the outer trim.
     */
    /**
     * Streaming-safe variant used for EVERY intermediate/final assistant write: an OPEN reasoning
     * block is removed even without its closing tag, so chain-of-thought never flashes on screen
     * and then disappears when the block closes. Normal answer text is unaffected.
     */
    fun sanitizeRealtime(raw: String): String {
        if (raw.isEmpty()) return raw
        // Complete blocks first: otherwise the "open block" pattern would swallow everything after
        // the opening tag, including the real answer that follows the closing tag.
        val withoutComplete = REASONING_BLOCK.replace(raw, "")
        return sanitize(UNCLOSED_REASONING_BLOCK.replace(withoutComplete, ""))
    }

    fun sanitize(raw: String): String {
        if (raw.isEmpty()) return raw
        var text = raw
        text = REASONING_BLOCK.replace(text, "")
        text = LABELLED_MARKER_LINE.replace(text, "")
        text = BRACKET_MARKER_LINE.replace(text, "")
        text = TOOL_PLANNING_LINE.replace(text, "")
        text = TRAILING_SPACES.replace(text, "\n")
        text = BLANK_RUNS.replace(text, "\n\n")
        return text.trim()
    }
}
