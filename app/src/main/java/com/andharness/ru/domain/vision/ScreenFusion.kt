package com.andharness.ru.domain.vision

import com.andharness.ru.domain.formatting.collapseWhitespace

/**
 * Pure, JVM-testable fusion of the accessibility node tree text with OCR text of the same screen
 * (update.md idea #14, «понимание экрана»).
 *
 * The accessibility tree is rich but blind to WebViews, canvases, games and any custom-drawn
 * content; OCR sees those pixels but loses element roles, ids and coordinates. This object merges
 * both: the tree keeps its structure (roles, ids, bounds, reading order) and OCR contributes the
 * lines the tree could not read, with the OCR duplicates of tree text removed.
 *
 * No Android APIs are referenced here, so the whole algorithm is unit-testable from fixtures.
 *
 * References:
 * - `AccessibilityNodeInfo` (text / contentDescription / viewIdResourceName / isClickable):
 *   https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo
 * - ML Kit Text Recognition v2 (used by the Android layer to produce the OCR text):
 *   https://developers.google.com/ml-kit/vision/text-recognition/v2/android
 */
object ScreenFusion {

    /** Default character budget of the fused answer. */
    const val DEFAULT_MAX_CHARS = 4_000

    /** Lower bound accepted for `max_chars`. */
    const val MIN_MAX_CHARS = 500

    /** Upper bound accepted for `max_chars`. */
    const val MAX_MAX_CHARS = 20_000

    /**
     * Text shorter than this (after [normalize]) is never used for deduplication: a 1–2 character
     * string is a substring of almost anything and would delete real OCR-only content.
     */
    private const val MIN_DEDUP_CHARS = 3

    /** One element parsed out of the `ui_tree` text. */
    data class Element(
        /** Nesting level (2 spaces per level in the tree dump). */
        val depth: Int,
        /** Visible text, or the content description, or a class-name fallback. */
        val label: String,
        /** Short view id (already trimmed to the part after '/'), or null. */
        val id: String?,
        /** Element roles in rendering order: `click`, `edit`, `scroll`. */
        val roles: List<String>,
        /** `left,top-right,bottom` bounds, or null when the line had none. */
        val bounds: String?,
    )

    /** The fused screen model: tree elements plus the OCR-only lines. */
    data class Fused(
        val elements: List<Element>,
        val ocrLines: List<String>,
        val ocrOnly: List<String>,
    ) {
        val elementCount: Int get() = elements.size
        val ocrCount: Int get() = ocrLines.size
        val ocrOnlyCount: Int get() = ocrOnly.size
    }

    /**
     * Whether the screenshot should be made available to the model as an image. [attach] is true
     * only when the caller requested it AND the configured model is known to accept images; the
     * [reason] is always honest and user-readable (it explains a refusal too).
     */
    data class AttachmentPlan(val attach: Boolean, val reason: String)

    /**
     * Decides whether the screenshot may be handed to the model. Vision support must be a
     * definitive "yes" — an UNKNOWN verdict is never treated as support (see the VisionPolicy).
     */
    fun shouldAttachImage(attachRequested: Boolean, visionSupported: Boolean): AttachmentPlan = when {
        !attachRequested -> AttachmentPlan(false, "скриншот не запрошен (attach_image=false)")
        !visionSupported ->
            AttachmentPlan(false, "модель не поддерживает изображения (Vision недоступен или неизвестен)")
        else -> AttachmentPlan(true, "модель поддерживает изображения")
    }

    /** Lower-cased, punctuation/whitespace-free form used for overlap comparison. */
    fun normalize(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Parses the `ui_tree` text. Each non-blank line becomes one [Element]; a line without any
     * label, id or role is ignored. The parser is tolerant: it strips the trailing ` @bounds`
     * suffix, then the `[scroll] [edit] [click]` role markers (in that reverse order of rendering),
     * then the ` #id` suffix, and takes whatever remains as the label.
     */
    fun parseTree(treeText: String): List<Element> =
        treeText.lineSequence().mapNotNull { parseTreeLine(it) }.toList()

    private fun parseTreeLine(rawLine: String): Element? {
        if (rawLine.isBlank()) return null
        val depth = rawLine.takeWhile { it == ' ' }.length / 2
        var rest = rawLine.trim()

        var bounds: String? = null
        val at = rest.lastIndexOf(" @")
        if (at >= 0) {
            bounds = rest.substring(at + 2).trim().takeIf { it.isNotEmpty() }
            rest = rest.substring(0, at)
        }

        // Role markers are rendered at the end; repeat until none is left so any input order is
        // accepted, then normalise to the canonical click → edit → scroll order.
        val foundRoles = HashSet<String>(3)
        var changed = true
        while (changed) {
            changed = false
            for (role in ROLE_MARKERS) {
                val marker = " [$role]"
                if (rest.endsWith(marker)) {
                    foundRoles += role
                    rest = rest.dropLast(marker.length)
                    changed = true
                }
            }
        }
        val roles = ROLE_ORDER.filter { it in foundRoles }

        var id: String? = null
        val hash = rest.lastIndexOf(" #")
        if (hash >= 0) {
            id = rest.substring(hash + 2).trim().takeIf { it.isNotEmpty() }
            rest = rest.substring(0, hash)
        }

        val label = rest.trim()
        if (label.isEmpty() && id == null && roles.isEmpty()) return null
        return Element(depth = depth, label = label, id = id, roles = roles, bounds = bounds)
    }

    /**
     * Splits OCR text into whitespace-normalized lines, dropping blanks and case-insensitive
     * duplicates while preserving the order the recognizer returned.
     */
    fun parseOcr(ocrText: String): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (line in ocrText.lineSequence()) {
            val normalized = collapseWhitespace(line)
            if (normalized.isEmpty()) continue
            if (!seen.add(normalize(normalized))) continue
            out += normalized
        }
        return out
    }

    /**
     * Fuses the tree text and the OCR text. OCR lines that overlap a tree label (either direction,
     * after [normalize]) are dropped as duplicates; the rest is kept as [Fused.ocrOnly] in OCR order.
     * The tree order (top-to-bottom reading order) is preserved unchanged.
     */
    fun fuse(treeText: String, ocrText: String): Fused {
        val elements = parseTree(treeText)
        val ocrLines = parseOcr(ocrText)
        val treeLabels = elements.map { normalize(it.label) }.filter { it.length >= MIN_DEDUP_CHARS }
        val ocrOnly = ocrLines.filter { line ->
            val normalized = normalize(line)
            normalized.length < MIN_DEDUP_CHARS ||
                treeLabels.none { tree -> tree.contains(normalized) || normalized.contains(tree) }
        }
        return Fused(elements = elements, ocrLines = ocrLines, ocrOnly = ocrOnly)
    }

    /**
     * Renders the fused model as a concise Russian block. The main body is always bounded by
     * [maxChars]; with [verbose] a bounded raw dump of both sources is appended so the caller can
     * inspect exactly what the tree and OCR produced.
     */
    fun format(
        fused: Fused,
        maxChars: Int = DEFAULT_MAX_CHARS,
        verbose: Boolean = false,
        rawTree: String? = null,
        rawOcr: String? = null,
    ): String {
        val budget = maxChars.coerceIn(MIN_MAX_CHARS, MAX_MAX_CHARS)
        val body = buildString {
            appendLine(
                "Слияние: элементов дерева ${fused.elementCount}, строк OCR ${fused.ocrCount}, " +
                    "из них только OCR ${fused.ocrOnlyCount}."
            )
            if (fused.elements.isEmpty()) {
                appendLine("Дерево элементов пусто — структура экрана недоступна.")
            } else {
                appendLine("Элементы (дерево, порядок сверху вниз):")
                for (element in fused.elements) {
                    appendLine(formatElement(element))
                }
            }
            if (fused.ocrOnly.isEmpty()) {
                appendLine("Текст, видимый только на изображении (OCR): нет.")
            } else {
                appendLine("Текст, видимый только на изображении (OCR):")
                for (line in fused.ocrOnly) {
                    appendLine("• ${line.take(MAX_OCR_LINE_CHARS)}")
                }
            }
        }.trimEnd()

        val truncated = truncate(body, budget)
        if (!verbose || (rawTree.isNullOrBlank() && rawOcr.isNullOrBlank())) return truncated

        return buildString {
            appendLine(truncated)
            appendLine()
            appendLine("RAW дерево (bounded):")
            appendLine(truncate(rawTree.orEmpty().trimEnd(), budget))
            appendLine()
            appendLine("RAW OCR (bounded):")
            append(truncate(rawOcr.orEmpty().trimEnd(), budget))
        }
    }

    private fun formatElement(element: Element): String {
        val label = element.label.ifEmpty { "?" }
        return buildString {
            append("  ".repeat(element.depth.coerceAtMost(MAX_INDENT_DEPTH)))
            append(label.take(MAX_LABEL_CHARS))
            element.id?.let { append(" #").append(it.take(MAX_ID_CHARS)) }
            for (role in element.roles) append(" [").append(role).append(']')
            element.bounds?.let { append(" @").append(it) }
        }
    }

    /** Bounded truncation that never silently loses information: it says how much was cut. */
    fun truncate(text: String, maxChars: Int): String {
        if (maxChars <= 0 || text.length <= maxChars) return text
        val cut = text.length - maxChars
        return text.take(maxChars) + "\n… (сокращено на $cut симв.)"
    }

    private const val MAX_LABEL_CHARS = 120
    private const val MAX_ID_CHARS = 60
    private const val MAX_OCR_LINE_CHARS = 200
    private const val MAX_INDENT_DEPTH = 12

    /** Markers are discovered in reverse rendering order; the result is normalised by [ROLE_ORDER]. */
    private val ROLE_MARKERS = listOf("scroll", "edit", "click")
    private val ROLE_ORDER = listOf("click", "edit", "scroll")
}
