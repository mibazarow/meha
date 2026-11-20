package com.andharness.ru.data.automation

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import com.andharness.ru.domain.serialization.AppJson

/**
 * Lightweight per-rule persistence for the `geo_distance` trigger, backed by
 * [android.content.SharedPreferences] + kotlinx.serialization (no Room table, so no schema
 * migration is needed) — the same pattern as
 * [com.andharness.ru.data.tools.location.GeofenceStore].
 *
 * [GeoDistanceMonitor] reads the baseline with [get] and writes it back with [put] once a rule first
 * sees a fix (baseline, [GeoDistancePoint.lastFiredAt] stays `0`) or actually fires (new point plus
 * the fire timestamp). Keeping it on disk means the baseline and debounce survive a process restart.
 */
class GeoDistanceStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = AppJson.Lenient

    /** All stored points, oldest first. Never throws (corrupt data degrades to an empty list). */
    fun all(): List<GeoDistancePoint> = try {
        prefs.getString(KEY_POINTS, null)
            ?.let { json.decodeFromString<List<GeoDistancePoint>>(it) }
            ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /** The stored point for [ruleId], or null when the rule has no baseline yet. */
    fun get(ruleId: Long): GeoDistancePoint? = all().firstOrNull { it.ruleId == ruleId }

    /** Adds or replaces the point of its rule id (upsert). */
    fun put(point: GeoDistancePoint) {
        val next = all().filterNot { it.ruleId == point.ruleId } + point
        prefs.edit().putString(KEY_POINTS, json.encodeToString(next)).apply()
    }

    /** Removes the stored point for [ruleId]; returns true when a record was actually removed. */
    fun clear(ruleId: Long): Boolean {
        val current = all()
        val next = current.filterNot { it.ruleId == ruleId }
        if (next.size == current.size) return false
        prefs.edit().putString(KEY_POINTS, json.encodeToString(next)).apply()
        return true
    }

    /** Removes every stored point (e.g. when the user wipes automation state). */
    fun clearAll() {
        prefs.edit().remove(KEY_POINTS).apply()
    }

    companion object {
        private const val PREFS_NAME = "andharness_geo_distance"
        private const val KEY_POINTS = "points"
    }
}
