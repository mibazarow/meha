package com.andharness.ru.data.usage

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers what the user already did with each suggestion (update.md idea #16): accepted ones have
 * a rule now and must not be offered again, dismissed ones must stay hidden for good.
 *
 * Backed by plain [SharedPreferences] — suggestion ids are stable strings derived from the pattern
 * (`app:<package>:<hour>` / `night:<hour>`), so no Room schema or migration is involved.
 */
@Singleton
class SuggestionDecisionStore @Inject constructor(
    @ApplicationContext context: Context
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** True when the suggestion was either accepted (rule created) or dismissed by the user. */
    fun isHandled(id: String): Boolean = id in acceptedIds() || id in dismissedIds()

    fun acceptedIds(): Set<String> = prefs.getStringSet(KEY_ACCEPTED, emptySet()).orEmpty()

    fun dismissedIds(): Set<String> = prefs.getStringSet(KEY_DISMISSED, emptySet()).orEmpty()

    @Synchronized
    fun markAccepted(id: String) = add(KEY_ACCEPTED, id)

    @Synchronized
    fun markDismissed(id: String) = add(KEY_DISMISSED, id)

    /**
     * A `StringSet` returned by [SharedPreferences] must not be mutated in place, so every write
     * copies it and stores a fresh set.
     */
    private fun add(key: String, id: String) {
        if (id.isBlank()) return
        val current = prefs.getStringSet(key, emptySet()).orEmpty()
        prefs.edit().putStringSet(key, HashSet(current).apply { add(id) }).apply()
    }

    companion object {
        const val PREFS_FILE = "andharness_suggestions"
        private const val KEY_ACCEPTED = "accepted_ids"
        private const val KEY_DISMISSED = "dismissed_ids"
    }
}
