package com.andharness.ru.domain.person

/**
 * Expands a free-text `interaction_search` query into the distinct terms that are matched against
 * the stored interaction metadata and the owning person's identities.
 *
 * Identities are stored normalized (see [IdentityNormalizer]), so a phone typed in another shape
 * (`8 949 ...`, `+7 949 ...`, with separators) would otherwise never substring-match the stored
 * `+7...`. The raw trimmed query always stays first, so plain-text search is unaffected; the
 * normalized phone/email forms and the local phone match key are added only when they differ.
 *
 * Pure and dependency-free, so it is unit-testable on the JVM.
 */
object InteractionSearchTerms {

    /** Distinct, non-empty search terms for [raw], in priority order (raw query first). */
    fun expand(raw: String?): List<String> {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return emptyList()

        val terms = LinkedHashSet<String>()
        terms += trimmed
        IdentityNormalizer.normalizePhone(trimmed)?.let { terms += it }
        IdentityNormalizer.phoneMatchKey(trimmed)?.let { terms += it }
        IdentityNormalizer.normalizeEmail(trimmed)?.let { terms += it }
        return terms.toList()
    }
}
