package com.andharness.ru.data.person

import android.content.Context
import android.content.SharedPreferences
import com.andharness.ru.domain.person.MessagingIndexPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persisted configuration of the personal-base ingestion (update.md §4.4, tool
 * `messaging_index_config`).
 *
 * - [isEnabled] is the **explicit opt-in** for indexing messenger/e-mail notifications; while it is
 *   `false` the incremental ingester does nothing.
 * - [packages] is the user-editable package allow-list that further narrows the opt-in.
 *
 * Backed by plain [SharedPreferences] (non-secret flags that must be readable synchronously from a
 * notification listener) and kept outside Room on purpose: no schema/migration number is consumed.
 */
@Singleton
class MessagingIndexConfigStore @Inject constructor(
    @ApplicationContext context: Context
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** Whether the user opted in to messenger/mail indexing. Default: off (honest, do-nothing). */
    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** The configured package allow-list, or [MessagingIndexPolicy.DEFAULT_PACKAGES] when unset. */
    fun packages(): Set<String> {
        val stored = prefs.getStringSet(KEY_PACKAGES, null) ?: return MessagingIndexPolicy.DEFAULT_PACKAGES
        // `getStringSet` may return a live set; copy it before touching it.
        val cleaned = stored.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return cleaned.ifEmpty { MessagingIndexPolicy.DEFAULT_PACKAGES }
    }

    fun setPackages(packages: Collection<String>) {
        val cleaned = packages.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        prefs.edit().putStringSet(KEY_PACKAGES, cleaned).apply()
    }

    /** Drops the stored list so [packages] falls back to the defaults again. */
    fun resetPackages() {
        prefs.edit().remove(KEY_PACKAGES).apply()
    }

    companion object {
        const val PREFS_FILE = "andharness_person_index"
        private const val KEY_ENABLED = "messaging_index_enabled"
        private const val KEY_PACKAGES = "messaging_index_packages"
    }
}
