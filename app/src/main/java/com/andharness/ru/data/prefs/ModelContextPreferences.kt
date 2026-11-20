package com.andharness.ru.data.prefs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small non-encrypted preference store for the discovered model context window.
 *
 * Deliberately NOT in Room: this is UI/derivation bookkeeping, and keeping it in prefs avoids a
 * database migration. The effective compression budget itself still lives in the existing
 * `settings.contextCompressionTokens` column, always derived as 80 % of the context.
 */
@Singleton
class ModelContextPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** Last known context window of the selected model, or null when unknown. */
    fun modelContextTokens(): Long? =
        if (prefs.contains(KEY_MODEL_CONTEXT)) {
            prefs.getLong(KEY_MODEL_CONTEXT, 0L).takeIf { it > 0 }
        } else {
            null
        }

    fun setModelContextTokens(value: Long?) {
        prefs.edit().apply {
            if (value == null || value <= 0) remove(KEY_MODEL_CONTEXT) else putLong(KEY_MODEL_CONTEXT, value)
        }.apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val PREFS_FILE = "andharness_model_context"
        private const val KEY_MODEL_CONTEXT = "model_context_tokens"
    }
}
