package com.andharness.ru.data.intercept

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import com.andharness.ru.domain.serialization.AppJson
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persisted `normalized number → chat` map plus the fallback call-log scan cursor (update.md
 * §2.2/§2.6).
 *
 * Deliberately OUTSIDE Room: the personal-base Room schema/migration numbering belongs to Phase 4
 * (update.md §4/§6), and this phase must not consume migration numbers. [SharedPreferences] is
 * enough for a small map and is readable synchronously from a broadcast receiver.
 */
@Singleton
class NumberChatStore @Inject constructor(
    @ApplicationContext context: Context
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val json = AppJson.LenientEncodeDefaults

    fun get(normalizedNumber: String): TrackedNumber? {
        val raw = prefs.getString(key(normalizedNumber), null) ?: return null
        return try {
            json.decodeFromString(TrackedNumber.serializer(), raw)
        } catch (e: Exception) {
            Log.w(TAG, "corrupt tracked-number entry for $normalizedNumber; dropping", e)
            prefs.edit().remove(key(normalizedNumber)).apply()
            null
        }
    }

    fun put(tracked: TrackedNumber) {
        runCatching {
            prefs.edit()
                .putString(key(tracked.number), json.encodeToString(TrackedNumber.serializer(), tracked))
                .apply()
        }.onFailure { Log.e(TAG, "storing tracked number failed", it) }
    }

    fun all(): List<TrackedNumber> = prefs.all.keys
        .filter { it.startsWith(KEY_PREFIX) }
        .mapNotNull { k -> prefs.getString(k, null)?.let { decode(it) } }
        .sortedByDescending { it.lastEventAt }

    fun count(): Int = prefs.all.keys.count { it.startsWith(KEY_PREFIX) }

    fun remove(normalizedNumber: String) {
        prefs.edit().remove(key(normalizedNumber)).apply()
    }

    fun clear(): Int {
        val keys = prefs.all.keys.filter { it.startsWith(KEY_PREFIX) }
        prefs.edit().apply { keys.forEach { remove(it) } }.apply()
        return keys.size
    }

    /** Cursor for the call-log fallback watcher; `0` means "never scanned yet". */
    fun lastScanAt(): Long = prefs.getLong(KEY_LAST_SCAN, 0L)

    fun setLastScanAt(millis: Long) {
        prefs.edit().putLong(KEY_LAST_SCAN, millis).apply()
    }

    private fun decode(raw: String): TrackedNumber? = try {
        json.decodeFromString(TrackedNumber.serializer(), raw)
    } catch (e: Exception) {
        null
    }

    private fun key(number: String): String = KEY_PREFIX + number

    companion object {
        private const val TAG = "Andharness"
        private const val PREFS_FILE = "andharness_intercept"
        private const val KEY_PREFIX = "num_"
        private const val KEY_LAST_SCAN = "call_log_last_scan_at"
    }
}
