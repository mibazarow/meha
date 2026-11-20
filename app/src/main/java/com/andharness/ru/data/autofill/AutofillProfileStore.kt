package com.andharness.ru.data.autofill

import android.content.Context

/**
 * Lightweight persistent store for the owner autofill profile, backed by
 * [android.content.SharedPreferences] + kotlinx.serialization — the same pattern as `GeofenceStore`
 * (no Room table, so no schema migration is needed).
 *
 * Reference: https://developer.android.com/reference/android/content/SharedPreferences
 */
class AutofillProfileStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The saved profile; never throws (corrupt data degrades to an empty profile). */
    fun load(): AutofillProfile = AutofillProfileCodec.decode(prefs.getString(KEY_PROFILE, null))

    fun save(profile: AutofillProfile) {
        prefs.edit().putString(KEY_PROFILE, AutofillProfileCodec.encode(profile)).apply()
    }

    /** Removes the profile entirely (autofill then offers nothing). */
    fun clear() {
        prefs.edit().remove(KEY_PROFILE).apply()
    }

    companion object {
        private const val PREFS_NAME = "andharness_autofill"
        private const val KEY_PROFILE = "owner_profile"
    }
}
