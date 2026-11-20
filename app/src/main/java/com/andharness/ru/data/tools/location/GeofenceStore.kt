package com.andharness.ru.data.tools.location

import android.content.Context
import kotlinx.serialization.encodeToString
import com.andharness.ru.domain.serialization.AppJson
import com.andharness.ru.domain.serialization.decodeOrNull

/**
 * Lightweight persistent store for geofences and their recorded transition events, backed by
 * [android.content.SharedPreferences] + kotlinx.serialization (no Room table, so no schema
 * migration is needed).
 *
 * It is intentionally separate from the OS-level registration: Play Services
 * ([com.google.android.gms.location.GeofencingClient]) keeps the actual geofences, while this store
 * lets `geofence_list` report what the app registered and lets the receiver record events.
 */
class GeofenceStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = AppJson.Lenient

    /** All tracked geofences, oldest first. Never throws (corrupt data degrades to an empty list). */
    fun list(): List<GeofenceRecord> =
        json.decodeOrNull<List<GeofenceRecord>>(prefs.getString(KEY_GEOFENCES, null)) ?: emptyList()

    fun exists(id: String): Boolean = list().any { it.id == id }

    fun count(): Int = list().size

    /** Adds or replaces a geofence by id. */
    fun add(record: GeofenceRecord) {
        val next = list().filterNot { it.id == record.id } + record
        save(KEY_GEOFENCES, json.encodeToString(next))
    }

    /** Removes a geofence by id; returns true when a record was actually removed. */
    fun remove(id: String): Boolean {
        val current = list()
        val next = current.filterNot { it.id == id }
        if (next.size == current.size) return false
        save(KEY_GEOFENCES, json.encodeToString(next))
        return true
    }

    /** Appends a transition event, keeping only the newest [MAX_EVENTS]. */
    fun addEvent(event: GeofenceEvent) {
        val next = (events() + event).takeLast(MAX_EVENTS)
        save(KEY_EVENTS, json.encodeToString(next))
    }

    /** Recorded transition events, oldest first. */
    fun events(): List<GeofenceEvent> =
        json.decodeOrNull<List<GeofenceEvent>>(prefs.getString(KEY_EVENTS, null)) ?: emptyList()

    private fun save(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    companion object {
        private const val PREFS_NAME = "andharness_geofences"
        private const val KEY_GEOFENCES = "geofences"
        private const val KEY_EVENTS = "events"
        private const val MAX_EVENTS = 100

        /** Documented per-app geofence limit used to fail honestly before the OS does. */
        const val MAX_GEOFENCES = 100
    }
}
