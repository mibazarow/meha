package com.andharness.ru.data.tools.ui

import android.content.Context

/**
 * Lightweight persistent store for UI macros, backed by [android.content.SharedPreferences] +
 * kotlinx.serialization — the same pattern as `GeofenceStore` (no Room table, so no schema
 * migration is needed).
 *
 * Reference: https://developer.android.com/reference/android/content/SharedPreferences
 */
class UiMacroStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** All saved macros, oldest first. Never throws (corrupt data degrades to an empty list). */
    fun list(): List<UiMacroRecord> = UiMacroCodec.decode(prefs.getString(KEY_MACROS, null))

    fun count(): Int = list().size

    /** Finds a macro by exact id first, then by exact name (case-insensitive fallback). */
    fun find(idOrName: String): UiMacroRecord? {
        val query = idOrName.trim()
        if (query.isEmpty()) return null
        val all = list()
        return all.firstOrNull { it.id == query }
            ?: all.firstOrNull { it.name == query }
            ?: all.firstOrNull { it.name.equals(query, ignoreCase = true) }
    }

    /**
     * Adds or replaces a macro by id, keeping the newest [UiMacroCodec.MAX_MACROS]. Returns false
     * when the name is blank (an anonymous macro must never be saved).
     */
    fun put(record: UiMacroRecord): Boolean {
        if (record.name.isBlank()) return false
        val next = (list().filterNot { it.id == record.id } + record)
            .takeLast(UiMacroCodec.MAX_MACROS)
        save(next)
        return true
    }

    /** Removes a macro by id or name; returns true when a record was actually removed. */
    fun delete(idOrName: String): Boolean {
        val record = find(idOrName) ?: return false
        save(list().filterNot { it.id == record.id })
        return true
    }

    private fun save(records: List<UiMacroRecord>) {
        prefs.edit().putString(KEY_MACROS, UiMacroCodec.encode(records)).apply()
    }

    companion object {
        private const val PREFS_NAME = "andharness_ui_macros"
        private const val KEY_MACROS = "macros"
    }
}
