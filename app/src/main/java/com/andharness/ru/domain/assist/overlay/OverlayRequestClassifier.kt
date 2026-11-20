package com.andharness.ru.domain.assist.overlay

/**
 * Pure, Android-free classifier for a floating-assistant request: does it ask about the CURRENT
 * SCREEN (what is on screen, read/translate the visible text, take a screenshot, …)?
 *
 * The overlay uses this to decide where a submit goes and whether the screen may be read at all:
 *  - a screen request lands in the one persistent «Распознавание экрана» chat and may read the
 *    current screen;
 *  - every other request gets a fresh chat and must NOT read or receive any screen content.
 *
 * [isScreenRequest] is intentionally CONSERVATIVE and defaults to `false`: a request is only treated
 * as screen-related when it carries an explicit cue. Guessing "screen" for an unrelated request
 * would leak the screen into a normal chat, so the failure mode is deliberately "not a screen
 * request".
 *
 * Deterministic: plain string matching on the lower-cased text (with `ё` folded to `е`), no
 * `Context`, no `AccessibilityService`, no locale-dependent casing (`String.lowercase()` is
 * locale-invariant), so the same input always yields the same answer and the rule is unit-testable
 * on the JVM. Cyrillic cues are matched as substrings (so «на экране» matches «экран»); English cues
 * are matched on word boundaries so e.g. `screensaver` does not match `screen`.
 */
object OverlayRequestClassifier {

    /**
     * Russian cues, matched as substrings of the normalized text.
     *
     * Deliberately narrow: bare «переведи» is NOT a cue (it would fire on «переведи деньги» — a
     * money transfer), only «переведи это/надпись/текст». Likewise no bare «открой»/«напиши».
     */
    private val RU_CUES = listOf(
        // The screen itself.
        "экран",
        "скриншот",
        "снимок экрана",
        // "What is here / what is written".
        "что тут",
        "что здесь",
        "что написано",
        // Read the visible text.
        "прочитай",
        "прочти",
        // Translate the visible text/inscription.
        "переведи это",
        "переведи надпись",
        "переведи текст",
        "надпись",
    )

    /** English cues, matched on word boundaries in the normalized text. */
    private val EN_CUES = listOf(
        "screen",
        "screenshot",
        "read this",
        "read that",
        "translate this",
        "translate that",
    )

    private val EN_CUE_PATTERNS: List<Regex> =
        EN_CUES.map { Regex("\\b" + Regex.escape(it) + "\\b") }

    /**
     * True only when [text] carries an explicit on-screen cue. Blank text, and any request without
     * a cue, is `false` (the default).
     */
    fun isScreenRequest(text: String): Boolean {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return false
        if (RU_CUES.any { normalized.contains(it) }) return true
        return EN_CUE_PATTERNS.any { it.containsMatchIn(normalized) }
    }

    /** Lower-case (locale-invariant) with `ё` folded to `е`, then trimmed; `null`-safe. */
    private fun normalize(text: String): String =
        text.lowercase().replace('ё', 'е').trim()
}
