package com.andharness.ru.domain.person

import java.util.Locale

/**
 * Pure, deterministic person resolution (update.md §4.5, «какой Серёга»).
 *
 * The resolver answers one question: given a free-text [query] (a name, an alias, a phone number, an
 * email or a messenger handle) and an optional channel hint, **which** people of the personal base
 * could be meant, and is the answer unambiguous? It is deliberately pure and Android-free so the
 * whole algorithm is unit-testable on the JVM and the agent can never "guess silently" when the
 * answer is not clear:
 *
 * 1. **Normalization + exact identity match** — the query is run through [IdentityNormalizer] for
 *    phone/email/messenger and compared against the person's identities. An exact identity is a
 *    hard, dominating signal ([IDENTITY_FLOOR] = 1.0 > every name-only score).
 * 2. **Fuzzy name/alias match** — names and aliases are canonicalized (lower-case, Cyrillic → Latin
 *    transliteration, punctuation collapsed) and scored with a mix of common-prefix, containment,
 *    edit-distance (typos), consonant-skeleton (diminutives/transliteration, e.g. «Серёга» ≈
 *    «Сергей») and token-overlap components. All rules are fixed constants — no randomness.
 * 3. **Candidate scoring** — the fuzzy score is scaled by [NAME_WEIGHT] and receives additive bonuses
 *    for the requested [InteractionChannel] (an SMS request prefers a `PHONE` identity, a messenger
 *    one prefers `MESSENGER`), for the recency of the last interaction and for interaction frequency.
 *    Because the bonuses sum to at most [MAX_BONUS], a name-only match can never outrank an exact
 *    identity match.
 * 4. **Ambiguity** — [ResolveResult.outcome] is [ResolveOutcome.RESOLVED] only when a clear winner
 *    exists (an exact identity, or a strong score with a clear margin over the runner-up); otherwise
 *    it is [ResolveOutcome.AMBIGUOUS] and the caller must ask the user instead of picking one.
 *
 * Official references:
 * - `String.trim` / `Locale` casing: https://developer.android.com/reference/java/lang/String
 * - Levenshtein distance (definition): https://en.wikipedia.org/wiki/Levenshtein_distance
 */
object PersonResolver {

    /** Weight of the fuzzy name/alias similarity in the final score (0..1 → 0..[NAME_WEIGHT]). */
    const val NAME_WEIGHT = 0.65

    /** Score of an exact identity (phone/email/messenger) match; name-only scores stay below it. */
    const val IDENTITY_FLOOR = 1.0

    /** Minimum score of the winner for [ResolveOutcome.RESOLVED] when it is not an identity match. */
    const val STRONG_SCORE = 0.50

    /** Minimum score gap between the winner and the runner-up for [ResolveOutcome.RESOLVED]. */
    const val CLEAR_MARGIN = 0.15

    /** Candidates scoring below this are dropped as noise (never reported, never resolved). */
    const val MIN_MATCH_SCORE = 0.30

    private const val IDENTITY_PHONE_EXACT = 1.0
    private const val IDENTITY_PHONE_KEY = 0.97
    private const val IDENTITY_EMAIL = 1.0
    private const val IDENTITY_MESSENGER = 1.0
    private const val IDENTITY_OTHER = 0.90

    private const val CHANNEL_BONUS = 0.16
    private const val RECENCY_BONUS_FRESH = 0.09
    private const val RECENCY_BONUS_WEEK = 0.06
    private const val RECENCY_BONUS_MONTH = 0.03
    private const val FREQUENCY_BONUS_HIGH = 0.08
    private const val FREQUENCY_BONUS_MID = 0.05
    private const val FREQUENCY_BONUS_LOW = 0.02

    /**
     * Max additive bonus (channel + recency + frequency); keeps a name-only match (≤
     * [NAME_WEIGHT] + [MAX_BONUS]) below the [IDENTITY_FLOOR] of an exact identity match.
     */
    const val MAX_BONUS = CHANNEL_BONUS + RECENCY_BONUS_FRESH + FREQUENCY_BONUS_HIGH

    private const val FREQUENCY_HIGH = 20
    private const val FREQUENCY_MID = 5

    private const val HOUR_MS = 60L * 60L * 1000L
    private const val DAY_MS = 24L * HOUR_MS
    private const val WEEK_MS = 7L * DAY_MS
    private const val MONTH_MS = 30L * DAY_MS

    private const val MIN_PREFIX = 3
    private const val MIN_CONTAINMENT = 4
    private const val SKELETON_MIN = 2

    private val VOWELS = "aeiouy"

    /**
     * Deterministic Cyrillic → Latin transliteration, so «Серёга» and «Serega» land on the same
     * canonical string. A char that maps to `""` is dropped (`ь`, `ъ`).
     */
    private val TRANSLIT: Map<Char, String> = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e",
        'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
        'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch",
        'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
        'і' to "i", 'ї' to "i", 'є' to "e", 'ґ' to "g",
    )

    /** One person with the data the resolver needs; built by the caller from the repositories. */
    data class PersonCandidate(
        val person: Person,
        val identities: List<PersonIdentity> = emptyList(),
        val aliases: List<String> = emptyList(),
        val lastInteractionAt: Long? = null,
        val lastInteractionChannel: InteractionChannel? = null,
        val interactionCount: Int = 0,
    )

    /** An exact identity hit found in a candidate. */
    data class IdentityMatch(
        val type: IdentityType,
        val value: String,
        val score: Double,
    )

    /** One ranked candidate. */
    data class PersonMatch(
        val person: Person,
        val score: Double,
        val identityExact: Boolean,
        val identityMatched: String?,
        val nameSimilarity: Double,
        val lastInteractionAt: Long?,
        val lastInteractionChannel: InteractionChannel?,
        val interactionCount: Int,
        val identities: List<PersonIdentity>,
        val reason: String,
    )

    /** How confident the resolution is. */
    enum class ResolveOutcome { RESOLVED, AMBIGUOUS, NOT_FOUND }

    /** Ranked resolution result; [matches] is empty only for [ResolveOutcome.NOT_FOUND]. */
    data class ResolveResult(
        val outcome: ResolveOutcome,
        val matches: List<PersonMatch>,
    ) {
        val best: PersonMatch? get() = matches.firstOrNull()

        /** `true` only for a clear winner — the caller may act without asking the user. */
        val isUnambiguous: Boolean get() = outcome == ResolveOutcome.RESOLVED
    }

    /**
     * Resolves [query] against [candidates]. Deterministic for a fixed [now].
     *
     * @param channel optional channel hint used to prefer an identity type (update.md §4.5 step 3).
     * @param now epoch millis "current time" used for the recency bonus; injected so tests are stable.
     * @param limit maximum number of ranked candidates returned (default 10).
     */
    fun resolve(
        query: String,
        candidates: List<PersonCandidate>,
        channel: InteractionChannel? = null,
        now: Long,
        limit: Int = 10,
    ): ResolveResult {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || candidates.isEmpty()) {
            return ResolveResult(ResolveOutcome.NOT_FOUND, emptyList())
        }

        val phone = IdentityNormalizer.normalizePhone(trimmed)
        val phoneKey = IdentityNormalizer.phoneMatchKey(trimmed)
        val email = IdentityNormalizer.normalizeEmail(trimmed)
        val messenger = IdentityNormalizer.normalizeMessenger(trimmed)
        val canonicalQuery = canonicalName(trimmed)
        val preferred = preferredIdentityType(channel)

        val scored = candidates.mapNotNull { candidate ->
            val identity = identityExactness(candidate.identities, trimmed, phone, phoneKey, email, messenger)
            val nameSim = candidateNameSimilarity(canonicalQuery, candidate)
            val base = maxOf(identity?.score ?: 0.0, nameSim * NAME_WEIGHT)
            if (base <= 0.0) return@mapNotNull null

            val channelBonus = if (preferred != null && candidate.identities.any { it.type == preferred }) {
                CHANNEL_BONUS
            } else {
                0.0
            }
            val bonus = channelBonus +
                recencyBonus(candidate.lastInteractionAt, now) +
                frequencyBonus(candidate.interactionCount)
            val score = base + bonus
            if (score < MIN_MATCH_SCORE) return@mapNotNull null

            PersonMatch(
                person = candidate.person,
                score = score,
                identityExact = identity != null,
                identityMatched = identity?.let { "${it.type.name} ${it.value}" },
                nameSimilarity = nameSim,
                lastInteractionAt = candidate.lastInteractionAt,
                lastInteractionChannel = candidate.lastInteractionChannel,
                interactionCount = candidate.interactionCount,
                identities = candidate.identities,
                reason = describe(identity, nameSim, preferred),
            )
        }.sortedWith(
            compareByDescending<PersonMatch> { it.score }
                .thenByDescending { it.lastInteractionAt ?: Long.MIN_VALUE }
                .thenBy { it.person.id }
        ).take(limit)

        if (scored.isEmpty()) {
            return ResolveResult(ResolveOutcome.NOT_FOUND, emptyList())
        }

        val top = scored.first()
        val second = scored.getOrNull(1)
        val outcome = when {
            top.identityExact && (second == null || !second.identityExact) -> ResolveOutcome.RESOLVED
            // Two people claiming the same handle should be impossible (unique index) — be honest.
            top.identityExact -> ResolveOutcome.AMBIGUOUS
            top.score < STRONG_SCORE -> ResolveOutcome.AMBIGUOUS
            second == null -> ResolveOutcome.RESOLVED
            top.score - second.score >= CLEAR_MARGIN -> ResolveOutcome.RESOLVED
            else -> ResolveOutcome.AMBIGUOUS
        }
        return ResolveResult(outcome, scored)
    }

    /** The identity type a channel prefers, or `null` when the channel does not narrow anything. */
    fun preferredIdentityType(channel: InteractionChannel?): IdentityType? = when (channel) {
        InteractionChannel.SMS, InteractionChannel.CALL -> IdentityType.PHONE
        InteractionChannel.MESSENGER -> IdentityType.MESSENGER
        InteractionChannel.EMAIL -> IdentityType.EMAIL
        InteractionChannel.CHAT, null -> null
    }

    /**
     * Canonical matching form of a name: trimmed, lower-cased, Cyrillic transliterated to Latin,
     * runs of non-alphanumeric characters collapsed to single spaces.
     */
    fun canonicalName(raw: String?): String {
        val text = raw?.trim()?.lowercase(Locale.US).orEmpty()
        if (text.isEmpty()) return ""
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val mapped = TRANSLIT[ch]
            when {
                mapped != null -> sb.append(mapped)
                ch.isLetterOrDigit() -> sb.append(ch)
                else -> sb.append(' ')
            }
        }
        return sb.toString().split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** Similarity of two **canonical** names in `0..1` (see the class docs for the components). */
    fun nameSimilarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        return maxOf(pairScore(a, b), tokenOverlap(a, b))
    }

    /** Best [nameSimilarity] of the canonical [queryCanonical] over the candidate's name and aliases. */
    fun candidateNameSimilarity(queryCanonical: String, candidate: PersonCandidate): Double {
        if (queryCanonical.isEmpty()) return 0.0
        var best = nameSimilarity(queryCanonical, canonicalName(candidate.person.displayName))
        for (alias in candidate.aliases) {
            best = maxOf(best, nameSimilarity(queryCanonical, canonicalName(alias)))
        }
        return best
    }

    /**
     * The strongest exact identity hit of [identities] for [rawQuery], or `null` when the query is
     * not (or does not normalize to) any of the person's handles.
     */
    fun identityExactness(
        identities: List<PersonIdentity>,
        rawQuery: String,
        phone: String? = IdentityNormalizer.normalizePhone(rawQuery),
        phoneKey: String? = IdentityNormalizer.phoneMatchKey(rawQuery),
        email: String? = IdentityNormalizer.normalizeEmail(rawQuery),
        messenger: String? = IdentityNormalizer.normalizeMessenger(rawQuery),
    ): IdentityMatch? {
        val trimmed = rawQuery.trim()
        var best: IdentityMatch? = null
        for (identity in identities) {
            val match = when (identity.type) {
                IdentityType.PHONE -> when {
                    phone != null && identity.value == phone ->
                        IdentityMatch(IdentityType.PHONE, identity.value, IDENTITY_PHONE_EXACT)
                    phoneKey != null && IdentityNormalizer.phoneMatchKey(identity.value) == phoneKey ->
                        IdentityMatch(IdentityType.PHONE, identity.value, IDENTITY_PHONE_KEY)
                    else -> null
                }
                IdentityType.EMAIL ->
                    if (email != null && identity.value.equals(email, ignoreCase = true)) {
                        IdentityMatch(IdentityType.EMAIL, identity.value, IDENTITY_EMAIL)
                    } else {
                        null
                    }
                IdentityType.MESSENGER ->
                    if (messenger != null && identity.value.equals(messenger, ignoreCase = true)) {
                        IdentityMatch(IdentityType.MESSENGER, identity.value, IDENTITY_MESSENGER)
                    } else {
                        null
                    }
                IdentityType.OTHER ->
                    if (trimmed.isNotEmpty() && identity.value.equals(trimmed, ignoreCase = true)) {
                        IdentityMatch(IdentityType.OTHER, identity.value, IDENTITY_OTHER)
                    } else {
                        null
                    }
            }
            if (match != null && (best == null || match.score > best.score)) best = match
        }
        return best
    }

    // ---- pure scoring helpers ----

    private fun describe(
        identity: IdentityMatch?,
        nameSim: Double,
        preferred: IdentityType?,
    ): String = buildString {
        if (identity != null) {
            append("точная идентичность ${identity.type.name}")
        } else {
            append("имя ~")
            append(String.format(Locale.US, "%.2f", nameSim))
        }
        if (preferred != null) {
            append(", канал → ")
            append(preferred.name)
        }
    }

    private fun recencyBonus(lastInteractionAt: Long?, now: Long): Double {
        if (lastInteractionAt == null || lastInteractionAt <= 0L) return 0.0
        val age = now - lastInteractionAt
        // A future/clock-skewed timestamp is treated as fresh, never as "infinitely old".
        if (age <= 0L) return RECENCY_BONUS_FRESH
        return when {
            age <= DAY_MS -> RECENCY_BONUS_FRESH
            age <= WEEK_MS -> RECENCY_BONUS_WEEK
            age <= MONTH_MS -> RECENCY_BONUS_MONTH
            else -> 0.0
        }
    }

    private fun frequencyBonus(count: Int): Double = when {
        count >= FREQUENCY_HIGH -> FREQUENCY_BONUS_HIGH
        count >= FREQUENCY_MID -> FREQUENCY_BONUS_MID
        count >= 1 -> FREQUENCY_BONUS_LOW
        else -> 0.0
    }

    /** Similarity of two single canonical tokens; used as the building block of everything else. */
    private fun pairScore(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val maxLen = maxOf(a.length, b.length)
        val minLen = minOf(a.length, b.length)

        var best = 0.0

        val prefix = commonPrefixLength(a, b)
        if (prefix >= MIN_PREFIX && minLen > 0) {
            best = maxOf(best, prefix.toDouble() / minLen * 0.9)
        }

        val short = if (a.length <= b.length) a else b
        val long = if (a.length <= b.length) b else a
        if (short.length >= MIN_CONTAINMENT && long.contains(short)) {
            best = maxOf(best, 0.5 + 0.4 * short.length.toDouble() / long.length)
        }

        best = maxOf(best, 1.0 - levenshtein(a, b).toDouble() / maxLen)
        best = maxOf(best, skeletonScore(a, b))
        return best
    }

    /** Average best-token match; lets a first name match a two-word full name. */
    private fun tokenOverlap(a: String, b: String): Double {
        val aTokens = a.split(' ').filter { it.isNotEmpty() }
        val bTokens = b.split(' ').filter { it.isNotEmpty() }
        if (aTokens.isEmpty() || bTokens.isEmpty()) return 0.0
        return aTokens.map { q -> bTokens.maxOf { n -> pairScore(q, n) } }.average()
    }

    /**
     * Consonant-skeleton similarity: «serega» → `srg`, «sergey» → `srgy`, one a prefix of the other
     * → a strong match. This is what makes diminutives and different transliterations line up
     * without a dictionary. Only applied to single tokens (word order is handled by [tokenOverlap]).
     */
    private fun skeletonScore(a: String, b: String): Double {
        if (a.contains(' ') || b.contains(' ')) return 0.0
        val sa = skeleton(a)
        val sb = skeleton(b)
        if (sa.isEmpty() || sb.isEmpty()) return 0.0
        if (sa == sb) return 0.95
        return if (minOf(sa.length, sb.length) >= SKELETON_MIN &&
            (sa.startsWith(sb) || sb.startsWith(sa))
        ) {
            0.9
        } else {
            0.0
        }
    }

    private fun skeleton(word: String): String {
        val sb = StringBuilder(word.length)
        for (ch in word) {
            if (ch.isLetter() && VOWELS.indexOf(ch) < 0) sb.append(ch)
        }
        return sb.toString()
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val max = minOf(a.length, b.length)
        var i = 0
        while (i < max && a[i] == b[i]) i++
        return i
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
