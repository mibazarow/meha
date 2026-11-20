package com.andharness.ru.data.tools.files

import android.content.Context

/**
 * Remembers, per MIME type, the package that `files_open` last opened successfully, so the next file
 * of the same type goes straight to that app without the system chooser. A single small
 * `SharedPreferences` file (not Room): this is derivation bookkeeping and avoids a DB migration.
 *
 * Keys are namespaced (`mime:<type>`) so the file can hold other keys later. MIME strings contain
 * `/` and `;`, which are valid inside a SharedPreferences key.
 * - https://developer.android.com/reference/android/content/Context#getSharedPreferences(java.lang.String,int)
 */
internal class FileOpenChoiceStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** The remembered package for [mime], or null when none was stored. */
    fun packageFor(mime: String): String? =
        prefs.getString(key(mime), null)?.takeIf { it.isNotBlank() }

    /** Remembers [pkg] as the pick for [mime]. */
    fun remember(mime: String, pkg: String) {
        prefs.edit().putString(key(mime), pkg).apply()
    }

    private fun key(mime: String): String = PREFIX + mime.trim().lowercase()

    internal companion object {
        const val PREFS_FILE = "andharness_file_open_choices"
        private const val PREFIX = "mime:"
    }
}
