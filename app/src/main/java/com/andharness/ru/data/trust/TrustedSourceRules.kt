package com.andharness.ru.data.trust

import java.util.Locale

/**
 * Pure normalization and matching rules for the trusted-source allowlist. Depends on nothing but the
 * Kotlin standard library, so it is exercised on the JVM by `TrustedSourceRulesTest` (the
 * Android-bound [TrustedSourceStore] is kept thin on purpose).
 */
object TrustedSourceRules {

    const val TYPE_SMS = "sms"
    const val TYPE_EMAIL = "email"
    const val TYPE_NOTIFICATION = "notification"
    const val TYPE_WEB = "web"
    const val TYPE_OTHER = "other"

    /** Accepted, closed set of source types. */
    val TYPES = listOf(TYPE_SMS, TYPE_EMAIL, TYPE_NOTIFICATION, TYPE_WEB, TYPE_OTHER)

    /** Any unknown/blank type degrades to [TYPE_OTHER] instead of rejecting the entry. */
    fun normalizeType(raw: String?): String {
        val type = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return if (type in TYPES) type else TYPE_OTHER
    }

    /**
     * Normalizes a source value for comparison and storage:
     * - `sms` — digits only, preserving a single leading `+` (so `+7 (900) 123-45-67` → `+79001234567`);
     * - `email` / `web` / `notification` — trimmed and lowercased;
     * - `other` — trimmed, case preserved.
     * Returns null when the value is blank or, for `sms`, contains no digits at all.
     */
    fun normalizeValue(type: String, raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return when (normalizeType(type)) {
            TYPE_SMS -> normalizePhone(trimmed)
            TYPE_EMAIL, TYPE_WEB, TYPE_NOTIFICATION -> trimmed.lowercase(Locale.ROOT)
            else -> trimmed
        }
    }

    /** Normalized `(type, value)` pair, or null when the value does not survive normalization. */
    fun normalize(type: String?, value: String?): Pair<String, String>? {
        val normalizedType = normalizeType(type)
        val normalizedValue = normalizeValue(normalizedType, value) ?: return null
        return normalizedType to normalizedValue
    }

    /** True when [value] of [type] is present in [sources], compared in normalized form. */
    fun contains(sources: List<TrustedSource>, type: String?, value: String?): Boolean {
        val key = normalize(type, value) ?: return false
        return sources.any {
            normalizeType(it.type) == key.first &&
                normalizeValue(it.type, it.value) == key.second
        }
    }

    /** Drops blank entries and duplicates (same normalized type+value), keeping the first. */
    fun dedupe(sources: List<TrustedSource>): List<TrustedSource> {
        val seen = LinkedHashSet<String>()
        val result = ArrayList<TrustedSource>(sources.size)
        for (source in sources) {
            val key = normalize(source.type, source.value) ?: continue
            if (seen.add(key.first + '\u0000' + key.second)) {
                result += source.copy(type = key.first, value = key.second)
            }
        }
        return result
    }

    private fun normalizePhone(raw: String): String? {
        val hasPlus = raw.startsWith("+")
        val digits = buildString { raw.forEach { if (it.isDigit()) append(it) } }
        if (digits.isEmpty()) return null
        return if (hasPlus) "+$digits" else digits
    }
}
