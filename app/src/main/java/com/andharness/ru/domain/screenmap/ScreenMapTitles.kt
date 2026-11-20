package com.andharness.ru.domain.screenmap

/**
 * Best-effort human title for a screen, used by the map rendering. Pure and Android-free.
 *
 * The first non-trivial label is used (many apps put the screen/toolbar title first in reading
 * order); when there is no usable label the Activity short name stands in. Never returns an empty
 * string — `null` means "no guess at all".
 */
object ScreenMapTitles {

    /** Cap on a guessed title, so a long paragraph label never becomes the screen title. */
    const val MAX_TITLE_CHARS = 60

    /** Minimum length of a plausible title; skips one-character noise such as a back arrow. */
    const val MIN_TITLE_CHARS = 2

    fun guess(labels: List<String>, activityClass: String?): String? {
        val fromLabels = labels.asSequence()
            .map { it.trim() }
            .firstOrNull { candidate ->
                candidate.length in MIN_TITLE_CHARS..MAX_TITLE_CHARS &&
                    candidate.any { it.isLetterOrDigit() }
            }
        if (fromLabels != null) return fromLabels.take(MAX_TITLE_CHARS)
        return ScreenSignature.shortClassName(activityClass)
            .takeIf { it != ScreenSignature.UNKNOWN }
            ?.take(MAX_TITLE_CHARS)
    }
}
