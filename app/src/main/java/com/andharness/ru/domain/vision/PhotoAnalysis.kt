package com.andharness.ru.domain.vision

import com.andharness.ru.domain.formatting.collapseWhitespace

/**
 * Pure, Android-free policy for the "take a photo and try to describe it" flow shared by the
 * `camera_capture` tool and the trusted-SMS photo report.
 *
 * The decision is deliberately honest: an image is handed to the model **only** when the configured
 * model is known to accept images (`VisionSupport.SUPPORTED`; an UNKNOWN verdict is never treated as
 * support). Otherwise the photo is NOT sent to the model and the caller falls back to on-device OCR,
 * stating that explicitly instead of pretending the model saw the picture.
 *
 * No Android APIs are referenced here, so the plan, the prompt and the wording are unit-tested on the
 * JVM.
 *
 * References:
 * - OpenAI vision (`image_url` content part): https://platform.openai.com/docs/guides/vision
 * - ML Kit text recognition (the on-device OCR fallback):
 *   https://developers.google.com/ml-kit/vision/text-recognition/v2/android
 */
object PhotoAnalysis {

    /** What `camera_capture` should do with the captured photo. */
    enum class Mode {
        /** `analyze=false`: the photo is saved only, nothing is described. */
        NONE,

        /** Vision is available: the photo is sent to the model and its description returned. */
        VISION,

        /** Vision is unavailable: the photo is NOT sent; on-device OCR is returned instead. */
        OCR,
    }

    /** The resolved plan plus an honest, user-readable reason. */
    data class Plan(val mode: Mode, val reason: String)

    /**
     * The exact phrase required whenever vision is unavailable: the user/agent must be told that the
     * image was not sent to the model and that the returned text is OCR, not a description.
     */
    const val VISION_UNAVAILABLE_NOTE = "изображение не отправлено модели, вот распознанный текст"

    /** Strict system framing for the single image-description completion. */
    const val DESCRIBE_SYSTEM_PROMPT: String =
        "Ты описываешь фотографию, которую сделал телефон. Отвечай ТОЛЬКО по-русски, кратко и " +
            "по делу: что видно на снимке, главные объекты, обстановка и читаемый текст. " +
            "Не выдумывай детали, которых не видно. Без markdown-оформления."

    /** The single user prompt sent together with the image part. */
    const val DESCRIBE_PROMPT: String =
        "Опиши эту фотографию: что на ней видно? Если на ней есть текст — приведи его."

    /**
     * Decides how to handle an already-captured photo. [analyzeRequested] is the tool's `analyze`
     * flag; [visionSupported] must be a definitive "yes" (see `VisionCapability`).
     */
    fun plan(analyzeRequested: Boolean, visionSupported: Boolean): Plan = when {
        !analyzeRequested ->
            Plan(Mode.NONE, "анализ не запрошен (analyze=false) — фото только сохранено")
        visionSupported ->
            Plan(Mode.VISION, "модель поддерживает изображения — фото отправлено модели")
        else ->
            Plan(Mode.OCR, "модель не поддерживает изображения (Vision недоступен или неизвестен)")
    }

    /** Lower-cased, whitespace-normalized text used to compare/clean a model answer. */
    fun normalize(raw: String): String = collapseWhitespace(raw)

    /** Strips surrounding quotes/markdown fences from a raw model answer; blank stays blank. */
    fun cleanDescription(raw: String): String {
        var text = raw.trim()
        CODE_FENCE.matchEntire(text)?.let { text = it.groupValues[1].trim() }
        var changed = true
        while (changed) {
            changed = false
            for ((open, close) in QUOTE_PAIRS) {
                if (text.length >= open.length + close.length &&
                    text.startsWith(open) && text.endsWith(close)
                ) {
                    text = text.substring(open.length, text.length - close.length).trim()
                    changed = true
                }
            }
        }
        return text
    }

    /** Renders the successful vision branch: the model's description, always plainly framed. */
    fun renderVision(description: String): String =
        "Описание изображения (модель поддерживает изображения):\n" + description.trim()

    /**
     * Renders the OCR fallback branch. The [VISION_UNAVAILABLE_NOTE] MUST be present so it is never
     * presented as a model description. [ocrStatusLine] is the recognizer's own honest status line.
     */
    fun renderOcr(ocrText: String, ocrStatusLine: String? = null): String = buildString {
        appendLine("Vision недоступен: $VISION_UNAVAILABLE_NOTE.")
        val body = ocrText.trim().ifEmpty { "Текст на изображении не найден." }
        append(body)
        ocrStatusLine?.trim()?.takeIf { it.isNotEmpty() }?.let { append("\n($it)") }
    }

    /** Renders the honest "vision unavailable and OCR itself could not run" branch. */
    fun renderOcrUnavailable(reason: String): String =
        "Vision недоступен: $VISION_UNAVAILABLE_NOTE. Распознать текст тоже не удалось: $reason"

    private val QUOTE_PAIRS = listOf(
        "\u00ab" to "\u00bb",
        "\u201e" to "\u201c",
        "\u201c" to "\u201d",
        "\"" to "\"",
        "'" to "'",
    )
    private val CODE_FENCE = Regex("^```[^\\n]*\\n([\\s\\S]*?)\\n?```$")
}
