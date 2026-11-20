package com.andharness.ru.data.osint

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persisted OSINT cache, keyed by the **normalized** number (update.md §2.5: «результат кэшируется
 * по номеру и не перезапрашивается, пока свежий»).
 *
 * Deliberately kept OUTSIDE Room: the personal-base Room schema/migration numbering is owned by
 * Phase 4 (update.md §4/§6), so this phase stores the cache in [SharedPreferences] to avoid
 * consuming migration numbers. Values are small JSON blobs written through
 * [OsintParsing.json].
 */
@Singleton
class OsintCacheStore @Inject constructor(
    @ApplicationContext context: Context
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** The cached record for [normalizedNumber], or `null` when absent/corrupt. */
    fun get(normalizedNumber: String): NumberOsint? {
        val raw = prefs.getString(key(normalizedNumber), null) ?: return null
        return try {
            OsintParsing.json.decodeFromString(NumberOsint.serializer(), raw)
        } catch (e: Exception) {
            Log.w(TAG, "corrupt OSINT cache entry for $normalizedNumber; dropping", e)
            prefs.edit().remove(key(normalizedNumber)).apply()
            null
        }
    }

    /** Stores (overwrites) the record for its number. */
    fun put(record: NumberOsint) {
        runCatching {
            prefs.edit()
                .putString(key(record.number), OsintParsing.json.encodeToString(NumberOsint.serializer(), record))
                .apply()
        }.onFailure { Log.e(TAG, "storing OSINT cache failed", it) }
    }

    /** True when the record exists and is younger than [TTL_MS]. */
    fun isFresh(record: NumberOsint?, now: Long = System.currentTimeMillis()): Boolean =
        record != null && record.updatedAt > 0 && (now - record.updatedAt) < TTL_MS

    /** Every cached record (used by tooling/status), newest first. Corrupt entries are skipped. */
    fun all(): List<NumberOsint> = prefs.all.keys
        .filter { it.startsWith(KEY_PREFIX) }
        .mapNotNull { k -> (prefs.getString(k, null))?.let { decode(it) } }
        .sortedByDescending { it.updatedAt }

    /** Removes every cached record and returns how many were dropped. */
    fun clear(): Int {
        val keys = prefs.all.keys.filter { it.startsWith(KEY_PREFIX) }
        prefs.edit().apply { keys.forEach { remove(it) } }.apply()
        return keys.size
    }

    /** Removes one cached record. */
    fun remove(normalizedNumber: String) {
        prefs.edit().remove(key(normalizedNumber)).apply()
    }

    private fun decode(raw: String): NumberOsint? = try {
        OsintParsing.json.decodeFromString(NumberOsint.serializer(), raw)
    } catch (e: Exception) {
        null
    }

    private fun key(number: String): String = KEY_PREFIX + number

    companion object {
        private const val TAG = "Andharness"
        private const val PREFS_FILE = "andharness_osint"
        private const val KEY_PREFIX = "osint_"

        /** How long a result stays "fresh" before a new web pass is allowed. */
        const val TTL_MS: Long = 7L * 24 * 60 * 60 * 1000
    }
}
