package com.andharness.ru.domain.chat


object AttachmentPolicy {

    /** At most this many files can hang off one message. */
    const val MAX_ATTACHMENTS = 5

    /** Largest single attachment accepted, in bytes (20 MB). */
    const val MAX_SIZE_BYTES = 20L * 1024 * 1024

    /** Decoded square preview size for images, in pixels (§19.2). */
    const val THUMBNAIL_PX = 100

    /**
     * Longest side an image is downscaled to before being sent inline to a multimodal model, so a
     * phone photo cannot blow up the request context (§19.3/§19.4).
     */
    const val MAX_IMAGE_DIMEN = 1024

    private val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif"
    )

    private val DOCUMENT_EXTENSIONS = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "odt", "ods", "odp", "rtf", "csv", "tsv",
        "txt", "md", "markdown", "json", "xml", "html", "htm", "log"
    )

    private val DOCUMENT_MIME_HINTS = setOf(
        "application/pdf",
        "application/msword",
        "application/vnd.ms-excel",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.oasis.opendocument.text",
        "application/vnd.oasis.opendocument.spreadsheet",
        "application/vnd.oasis.opendocument.presentation",
        "application/rtf",
        "text/rtf",
        "application/csv",
        "application/json",
        "application/xml",
        "application/xhtml+xml"
    )

    private const val MIME_IMAGE_PREFIX = "image/"
    private const val MIME_TEXT_PREFIX = "text/"

    /**
     * Classifies an attachment into the three UX buckets. Extension is checked as a fallback
     * because many content providers report `application/octet-stream` (or nothing) for files the
     * user knows are images/documents.
     */
    fun classify(mime: String, name: String): AttachmentKind {
        val normalizedMime = mime.trim().lowercase()
        val extension = extensionOf(name)
        return when {
            normalizedMime.startsWith(MIME_IMAGE_PREFIX) || extension in IMAGE_EXTENSIONS ->
                AttachmentKind.IMAGE
            normalizedMime.startsWith(MIME_TEXT_PREFIX) ||
                normalizedMime in DOCUMENT_MIME_HINTS ||
                extension in DOCUMENT_EXTENSIONS ->
                AttachmentKind.DOCUMENT
            else -> AttachmentKind.OTHER
        }
    }

    /** Checks the count and size limits before an attachment is accepted. */
    fun validate(currentCount: Int, sizeBytes: Long): AttachmentLimit = when {
        currentCount >= MAX_ATTACHMENTS -> AttachmentLimit.TooMany(MAX_ATTACHMENTS)
        sizeBytes > MAX_SIZE_BYTES -> AttachmentLimit.TooLarge(MAX_SIZE_BYTES)
        else -> AttachmentLimit.Ok
    }

    /** Human-readable byte size for attachment tiles (Russian, §19.6). */
    fun humanSize(bytes: Long): String {
        if (bytes < 0) return "размер неизвестен"
        if (bytes < 1024) return "$bytes Б"
        val kb = bytes / 1024.0
        if (kb < 1024) return formatOneDecimal(kb) + " КБ"
        val mb = kb / 1024.0
        if (mb < 1024) return formatOneDecimal(mb) + " МБ"
        return formatOneDecimal(mb / 1024.0) + " ГБ"
    }

    /**
     * Power-of-two [android.graphics.BitmapFactory.Options.inSampleSize] that keeps the longest
     * decoded side at or above `target` while skipping as many bytes as possible. Decoding with
     * this value (instead of the full bitmap) is what keeps thumbnail generation cheap (§19.2).
     */
    fun sampleSize(width: Int, height: Int, target: Int): Int {
        if (width <= 0 || height <= 0 || target <= 0) return 1
        val longest = maxOf(width, height)
        var sample = 1
        while (longest / (sample * 2) >= target) {
            sample *= 2
        }
        return sample
    }

    /** Scales `width`×`height` down proportionally so the longest side is at most `max`. */
    fun scaledSize(width: Int, height: Int, max: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0 || max <= 0) return width to height
        val longest = maxOf(width, height)
        if (longest <= max) return width to height
        val ratio = max.toDouble() / longest
        return (width * ratio).toInt().coerceAtLeast(1) to
            (height * ratio).toInt().coerceAtLeast(1)
    }

    /** Short Russian label used in attachment tiles and in the message handed to the model. */
    fun kindLabel(kind: AttachmentKind): String = when (kind) {
        AttachmentKind.IMAGE -> "изображение"
        AttachmentKind.DOCUMENT -> "документ"
        AttachmentKind.OTHER -> "файл"
    }

    /**
     * Builds the user message that is persisted and sent to the model. The saved absolute path of
     * every attachment is included explicitly so the agent knows which file to open with the
     * document/file tools — including images, where the tool path is the OCR fallback (§19.3).
     */
    fun buildUserText(text: String, attachments: List<ChatAttachment>): String {
        if (attachments.isEmpty()) return text
        val lines = attachments.mapIndexed { index, attachment ->
            val mime = attachment.mime.ifBlank { "тип неизвестен" }
            "${index + 1}. ${attachment.name} (${kindLabel(attachment.kind)}, $mime)\n" +
                "   файл: ${attachment.path}"
        }
        val block = buildString {
            append("[Вложения: ${attachments.size}]\n")
            append(lines.joinToString("\n"))
            append(
                "\nСодержимое вложений можно прочитать инструментами doc_read / doc_info / " +
                    "files_read, а текст на изображении — ocr_image по указанному пути."
            )
        }
        return if (text.isBlank()) block else "$text\n\n$block"
    }

    /** One attachment reference recovered from a persisted user message (see [parseUserText]). */
    data class ParsedUserAttachment(
        val name: String,
        val kind: AttachmentKind,
        val mime: String,
        val path: String
    )

    /** A persisted user message split into the user's own text and its attachments. */
    data class ParsedUserMessage(
        val text: String,
        val attachments: List<ParsedUserAttachment>
    )

    // Header line produced by [buildUserText]: «[Вложения: N]» (N digits) on its own line.
    private val ATTACHMENT_HEADER = Regex("(?m)^\\[Вложения: \\d+\\][^\\n]*$")

    // Entry line: «1. name (label, mime)». The name is greedy so it may itself contain «(» / «)».
    private val ATTACHMENT_ENTRY = Regex("^\\d+\\. (.*) \\(([^(),]*), ([^()]*)\\)$")

    private const val FILE_PATH_PREFIX = "   файл: "

    /**
     * The inverse of [buildUserText]: splits a persisted user message into the user's own text and
     * the list of attachments. Powers the chat UI's inline image/file previews, so the raw
     * `[Вложения: …]` block is not shown in the user's own bubble (it is still what the model sees).
     *
     * A message without the block is returned unchanged ([ParsedUserMessage.text] == [content] and an
     * empty list), so callers can fall back to rendering the plain content exactly as before.
     */
    fun parseUserText(content: String): ParsedUserMessage {
        val header = ATTACHMENT_HEADER.find(content)
            ?: return ParsedUserMessage(content, emptyList())
        val text = content.substring(0, header.range.first).trim()
        val lines = content.substring(header.range.last + 1).split('\n')
        val attachments = ArrayList<ParsedUserAttachment>()
        var i = 0
        while (i < lines.size) {
            val match = ATTACHMENT_ENTRY.matchEntire(lines[i].trimEnd())
            if (match == null) {
                i++
                continue
            }
            val name = match.groupValues[1]
            val label = match.groupValues[2]
            val mime = match.groupValues[3]
            val pathLine = lines.getOrNull(i + 1)?.trimEnd().orEmpty()
            val path = if (pathLine.startsWith(FILE_PATH_PREFIX)) {
                pathLine.removePrefix(FILE_PATH_PREFIX).trim()
            } else {
                ""
            }
            attachments += ParsedUserAttachment(
                name = name,
                kind = kindFromLabel(label) ?: classify(mime, name),
                mime = mime,
                path = path
            )
            i += 2
        }
        return ParsedUserMessage(text, attachments)
    }

    /** Maps the short Russian label written by [kindLabel] back to an [AttachmentKind]. */
    private fun kindFromLabel(label: String): AttachmentKind? = when (label.trim()) {
        "изображение" -> AttachmentKind.IMAGE
        "документ" -> AttachmentKind.DOCUMENT
        "файл" -> AttachmentKind.OTHER
        else -> null
    }

    private fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }

    private fun formatOneDecimal(value: Double): String {
        val rounded = Math.round(value * 10.0) / 10.0
        return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
    }
}
