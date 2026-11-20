package com.andharness.ru.data.trust

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent allowlist of trusted external sources, backed by
 * [android.content.SharedPreferences] + kotlinx.serialization — the same pattern as
 * `AutofillProfileStore` / `UiMacroStore` (no Room table, so no schema migration is needed).
 *
 * Every method is best-effort and non-throwing: a corrupt store degrades to an empty list.
 * Reference: https://developer.android.com/reference/android/content/SharedPreferences
 */

@Singleton
class TrustedSourceStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** All trusted sources, insertion order. Never throws. */
    fun list(): List<TrustedSource> = TrustedSourceCodec.decode(prefs.getString(KEY_SOURCES, null))

    /**
     * Adds a source after normalization. Returns false for a blank/invalid value or when the same
     * normalized source is already trusted (dedupe) — in that case nothing is written.
     */
    fun add(type: String, value: String): Boolean {
        val key = TrustedSourceRules.normalize(type, value) ?: return false
        val current = list()
        if (TrustedSourceRules.contains(current, key.first, key.second)) return false
        val next = current + TrustedSource(key.first, key.second, System.currentTimeMillis())
        save(next)
        return true
    }

    /** Removes a source (matched in normalized form); returns true only when something was removed. */
    fun remove(type: String, value: String): Boolean {
        val key = TrustedSourceRules.normalize(type, value) ?: return false
        val current = list()
        val next = current.filterNot {
            TrustedSourceRules.normalizeType(it.type) == key.first &&
                TrustedSourceRules.normalizeValue(it.type, it.value) == key.second
        }
        if (next.size == current.size) return false
        save(next)
        return true
    }

    /** True when the given source is on the allowlist (normalized comparison). */
    fun isTrusted(type: String, value: String): Boolean =
        TrustedSourceRules.contains(list(), type, value)

    /** The bounded prompt-context line for the current allowlist. Never throws. */
    fun renderBlock(): String = TrustedSourceBlock.render(list())

    private fun save(sources: List<TrustedSource>) {
        prefs.edit()
            .putString(KEY_SOURCES, TrustedSourceCodec.encode(sources))
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "andharness_trusted_sources"
        private const val KEY_SOURCES = "sources"
    }
}
