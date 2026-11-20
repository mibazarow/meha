package com.andharness.ru.domain.communications

import com.andharness.ru.domain.formatting.collapseWhitespace
import java.util.Locale

/**
 * One sentence that carries a commitment/agreement cue ("я сделаю", "давай", "I will", …) together
 * with where in the transcript it was heard.
 *
 * [confidence] is a bounded heuristic score in `[0, 1]`, NOT a probability: more distinct cue
 * phrases (and a first-person subject) raise it. See [CommitmentExtractor] for the exact rule.
 */
data class CommitmentCandidate(
    val text: String,
    val recordingId: Long,
    val startMs: Long,
    val endMs: Long,
    val cues: List<String>,
    val confidence: Double,
)

/**
 * PURE, Android-free heuristic scan of transcript segments for commitment/agreement-like statements.
 *
 * **This is a keyword heuristic, not an LLM guarantee.** It sentence-splits each segment, keeps the
 * sentences that contain a known RU/EN cue phrase and scores them by cue count. It deliberately does
 * NOT try to understand grammar, resolve references or decide whether a promise was kept — an
 * "обещал" (past-tense "promised") counts exactly like "обещаю" (present). Callers must present the
 * result as candidates to review, never as facts, and must only persist them on clear cues
 * (`create=true` uses exactly this list — it never invents items).
 *
 * Determinism and bounds: the scan is bounded by [MAX_SENTENCES_PER_SEGMENT] sentences per segment,
 * [MAX_TEXT_CHARS] characters per candidate and [MAX_CANDIDATES] candidates overall, and identical
 * normalized sentences are de-duplicated (the higher-confidence occurrence wins). No Android APIs,
 * so it is unit-testable on the JVM. References:
 * - regular expressions — https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.text/-regex/
 * - `String.lowercase(Locale.ROOT)` — https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.text/lowercase.html
 */
object CommitmentExtractor {

    /** Default look-back window used by the `commitments_extract` tool. */
    const val DEFAULT_WINDOW = "7d"

    /** Largest accepted look-back window, in days. */
    const val MAX_WINDOW_DAYS = 365

    /** Hard cap on returned candidates, so the tool output can never grow unbounded. */
    const val MAX_CANDIDATES = 50

    /** Hard cap on sentences examined inside one segment. */
    const val MAX_SENTENCES_PER_SEGMENT = 20

    /** Hard cap on the stored/echoed length of one candidate sentence. */
    const val MAX_TEXT_CHARS = 400

    private const val DAY_MS = 86_400_000L

    private val SENTENCE_DELIMITERS = Regex("[.!?…\\n\\r]+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val FIRST_PERSON = Regex("\\b(я|мы|i|we)\\b")
    private val WINDOW_DAYS = Regex("^(\\d{1,4})d$")

    /* Cue phrases are matched case-insensitively as substrings of a sentence. Kept short and
     * concrete on purpose: a vague verb like "буду" is not enough on its own. */
    private val RU_CUES = listOf(
        "обещал", "обещала", "обещаю", "обещали",
        "сделаю", "сделаем", "сделать",
        "напомни", "напомню", "напоминаю",
        "договорились", "договоримся",
        "давай", "давайте",
        "я позвоню", "позвоню",
        "напишу", "пришлю", "скину",
        "встретимся", "до встречи",
        "заберу", "привезу",
        "сходим", "пойдем", "пойдём", "пойду",
        "проверю", "уточню", "решу", "подготовлю",
        "не забудь", "не забуду",
    )

    private val EN_CUES = listOf(
        "i will", "i'll", "i am going to", "i'm going to", "im going to", "gonna",
        "let's", "lets",
        "remind me", "remind", "i promise", "promise",
        "call you", "send you", "meet you", "see you",
    )

    private val CUES: List<String> = RU_CUES + EN_CUES

    /**
     * Returns the commitment candidates found in [segments], highest confidence first. An empty or
     * cue-less input yields an empty list; the result is never larger than [MAX_CANDIDATES].
     */
    fun extract(segments: List<TranscriptSegment>): List<CommitmentCandidate> {
        val byText = LinkedHashMap<String, CommitmentCandidate>()
        for (segment in segments) {
            if (segment.text.isBlank()) continue
            val sentences = SENTENCE_DELIMITERS.split(segment.text)
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(MAX_SENTENCES_PER_SEGMENT)
            for (sentence in sentences) {
                val lower = sentence.lowercase(Locale.ROOT)
                val cues = CUES.filter { lower.contains(it) }
                if (cues.isEmpty()) continue
                val confidence = confidenceOf(lower, cues.size)
                val key = normalize(sentence)
                if (key.isEmpty()) continue
                val existing = byText[key]
                if (existing == null || confidence > existing.confidence) {
                    byText[key] = CommitmentCandidate(
                        text = sentence.take(MAX_TEXT_CHARS),
                        recordingId = segment.recordingId,
                        startMs = segment.startMs,
                        endMs = segment.endMs,
                        cues = cues.distinct(),
                        confidence = confidence,
                    )
                }
                if (byText.size >= MAX_CANDIDATES) {
                    return byText.values.sortedWith(CANDIDATE_ORDER)
                }
            }
        }
        return byText.values.sortedWith(CANDIDATE_ORDER)
    }

    /**
     * Start of the look-back window as epoch millis, or `null` when [window] is not recognised.
     *
     * Accepts `today` (start of the local day, using [zoneOffsetMs]) or `<N>d` with `N` in
     * `1..[MAX_WINDOW_DAYS]`. A blank/null [window] falls back to [DEFAULT_WINDOW]. Pure, so the
     * "today" boundary is deterministic in tests.
     */
    fun windowStartMillis(window: String?, nowMs: Long, zoneOffsetMs: Long): Long? {
        val raw = window?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val effective = raw.ifEmpty { DEFAULT_WINDOW }
        if (effective == "today") {
            val local = nowMs + zoneOffsetMs
            val dayStartLocal = local - Math.floorMod(local, DAY_MS)
            return dayStartLocal - zoneOffsetMs
        }
        val match = WINDOW_DAYS.matchEntire(effective) ?: return null
        val days = match.groupValues[1].toIntOrNull() ?: return null
        if (days < 1 || days > MAX_WINDOW_DAYS) return null
        return nowMs - days * DAY_MS
    }

    /** Lowercased, punctuation-stripped, whitespace-collapsed sentence used as the dedup key. */
    fun normalize(text: String): String =
        collapseWhitespace(
            text.lowercase(Locale.ROOT)
                .replace(NON_WORD, " ")
        )

    private fun confidenceOf(lower: String, distinctCues: Int): Double {
        var score = 0.4 + 0.2 * distinctCues
        if (FIRST_PERSON.containsMatchIn(lower)) score += 0.1
        return score.coerceIn(0.0, 1.0)
    }

    private val CANDIDATE_ORDER =
        compareByDescending<CommitmentCandidate> { it.confidence }
            .thenBy { it.recordingId }
            .thenBy { it.startMs }
}
