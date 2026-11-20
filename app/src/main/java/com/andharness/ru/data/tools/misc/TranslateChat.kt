package com.andharness.ru.data.tools.misc

/**
 * Android-free helpers behind `translate_chat` (checklist §3): picking the last N messages of a chat,
 * building the SINGLE batch-translation prompt and parsing/formatting the model's answer.
 *
 * Kept pure (no Android APIs) so message selection/limit/ordering, the prompt and the response
 * cleanup are unit-tested on the JVM (see `TranslateChatTest`). The handler only does the I/O.
 */

/** Default number of most-recent messages translated by `translate_chat`. */
internal const val DEFAULT_CHAT_TRANSLATE_LIMIT = 20

/** Hard upper bound of messages translated in one `translate_chat` call. */
internal const val MAX_CHAT_TRANSLATE_LIMIT = 50

/** Upper bound of the joined source text sent in one batch request (keeps the call bounded). */
internal const val MAX_CHAT_TRANSLATE_CHARS = 12_000

/**
 * Human label for a message role; an unknown role keeps its raw name instead of inventing one, and a
 * blank role becomes a neutral «Сообщение». Pure and unit-tested.
 */
internal fun chatAuthorLabel(role: String): String = when (role.trim().lowercase()) {
    "user" -> "Пользователь"
    "assistant" -> "Меха"
    "system" -> "Система"
    "tool" -> "Инструмент"
    else -> role.trim().ifEmpty { "Сообщение" }
}

/** Clamps a requested message limit: default 20, always within 1..[MAX_CHAT_TRANSLATE_LIMIT]. */
internal fun clampChatLimit(raw: Int?): Int =
    (raw ?: DEFAULT_CHAT_TRANSLATE_LIMIT).coerceIn(1, MAX_CHAT_TRANSLATE_LIMIT)

/** The last [limit] messages, in the ORIGINAL chronological order. Pure and unit-tested. */
internal fun <T> lastMessagesInOrder(messages: List<T>, limit: Int): List<T> =
    if (messages.size <= limit) messages else messages.subList(messages.size - limit, messages.size)

/**
 * Selects the messages to translate: the last [limit] messages, minus empty/attachment-only ones
 * (counted in [TranslateChatSelection.skippedEmpty]), then trimmed from the OLDEST side until the
 * joined text fits [maxChars] (counted in [TranslateChatSelection.droppedForLength]). Message order
 * is always preserved. Pure and unit-tested.
 */
internal fun selectChatLines(
    messages: List<TranslateChatLine>,
    limit: Int,
    maxChars: Int = MAX_CHAT_TRANSLATE_CHARS,
): TranslateChatSelection {
    val recent = lastMessagesInOrder(messages, clampChatLimit(limit))
    val skippedEmpty = recent.count { it.text.isBlank() }
    val nonBlank = recent.filter { it.text.isNotBlank() }
    val kept = ArrayDeque<TranslateChatLine>()
    var budget = 0
    for (line in nonBlank.asReversed()) {
        if (budget + line.text.length > maxChars) break
        budget += line.text.length
        kept.addFirst(line)
    }
    return TranslateChatSelection(kept.toList(), skippedEmpty, nonBlank.size - kept.size)
}

/**
 * Builds the single batch prompt: one numbered block of messages in, one numbered line per message
 * out. Author names are deliberately NOT sent — the model translates only the text, while the author
 * is re-attached locally for the answer ([formatChatTranslation]). Inner line breaks are flattened so
 * one message stays one numbered line. Pure and unit-tested.
 */
internal fun buildChatTranslatePrompt(
    lines: List<TranslateChatLine>,
    to: String,
    from: String?,
): String = buildString {
    appendLine("Целевой язык: $to")
    appendLine(if (from != null) "Исходный язык: $from" else "Исходный язык: определи автоматически")
    appendLine("Ниже пронумерованные сообщения из переписки. Переведи КАЖДОЕ сообщение отдельно.")
    appendLine(
        "Верни ровно ${lines.size} строк(и) в формате «[номер] перевод», сохраняя порядок и " +
            "нумерацию. Номера не переводи, исходный текст и пояснения не добавляй."
    )
    appendLine("Сообщения:")
    lines.forEachIndexed { index, line ->
        appendLine("[${index + 1}] ${line.text.replace('\n', ' ').trim()}")
    }
}

/** The numbered prefixes a model commonly returns: `[12] …`, `12. …`, `12) …`, `12 - …`. */
private val NUMBERED_LINE = Regex("""^\s*\[?(\d+)\]?\s*[.):\-]?\s*(.*)$""")

/**
 * Parses a numbered answer into [expected] translations in 1-based index order, or `null` when the
 * answer is not a complete numbered list (the caller then falls back to ONE merged translation).
 * Out-of-range and duplicate numbers are ignored instead of misaligning the result. Pure and tested.
 */
internal fun parseChatTranslations(raw: String, expected: Int): List<String>? {
    if (expected <= 0) return null
    val found = LinkedHashMap<Int, String>()
    for (rawLine in raw.lineSequence()) {
        val match = NUMBERED_LINE.matchEntire(rawLine.trim()) ?: continue
        val index = match.groupValues[1].toIntOrNull() ?: continue
        if (index !in 1..expected || found.containsKey(index)) continue
        val text = match.groupValues[2].trim()
        if (text.isNotEmpty()) found[index] = text
    }
    if (found.size != expected) return null
    return (1..expected).map { found.getValue(it) }
}

/**
 * Renders the final answer. With [parsed] the result is a compact per-message list (author +
 * translation, message order preserved); without it [mergedTranslation] is returned as one block
 * with an honest note that per-message alignment failed. Skipped/over-long messages are always
 * reported, never silently dropped. Pure and unit-tested.
 */
internal fun formatChatTranslation(
    selection: TranslateChatSelection,
    parsed: List<String>?,
    mergedTranslation: String,
    to: String,
): String = buildString {
    if (parsed != null && parsed.size == selection.lines.size) {
        appendLine("Перевод переписки на «$to» (сообщений: ${selection.lines.size}):")
        selection.lines.forEachIndexed { index, line ->
            appendLine("[${index + 1}] ${line.author}: ${parsed[index]}")
        }
    } else {
        appendLine(
            "Перевод переписки на «$to». Не удалось построчно сопоставить ответ модели, " +
                "перевод сведён в один блок:"
        )
        appendLine(mergedTranslation)
    }
    val notes = buildList {
        if (selection.skippedEmpty > 0) add("пропущено сообщений без текста: ${selection.skippedEmpty}")
        if (selection.droppedForLength > 0) add("не поместилось по длине: ${selection.droppedForLength}")
    }
    if (notes.isNotEmpty()) append("(${notes.joinToString("; ")})")
}

/** Honest unsupported-language message shared by the translation tools. Pure. */
internal fun unsupportedLanguageMessage(what: String, raw: String, allowAuto: Boolean): String =
    "$what «$raw» не поддерживается. Укажи код или название из списка, например: " +
        "${TranslateLanguages.EXAMPLES}" +
        if (allowAuto) " — либо auto, чтобы определить язык автоматически." else "."
