package com.andharness.ru.data.jobs

import android.content.Context
import com.andharness.ru.domain.serialization.AppJson

/** SharedPreferences-backed store of [JobSpec]s, keyed by JobScheduler job id. */
class JobSpecStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(spec: JobSpec) {
        prefs.edit()
            .putString(KEY_PREFIX + spec.id, json.encodeToString(JobSpec.serializer(), spec))
            .apply()
    }

    fun get(id: Int): JobSpec? {
        val raw = prefs.getString(KEY_PREFIX + id, null) ?: return null
        return runCatching { json.decodeFromString(JobSpec.serializer(), raw) }.getOrNull()
    }

    fun all(): List<JobSpec> = prefs.all.keys
        .asSequence()
        .filter { it.startsWith(KEY_PREFIX) }
        .mapNotNull { key -> key.removePrefix(KEY_PREFIX).toIntOrNull()?.let { get(it) } }
        .sortedBy { it.id }
        .toList()

    fun remove(id: Int) {
        prefs.edit().remove(KEY_PREFIX + id).apply()
    }

    /** Monotonic job id allocator (never reused inside one install). */
    @Synchronized
    fun nextId(): Int {
        val next = prefs.getInt(KEY_NEXT_ID, FIRST_ID).coerceAtLeast(FIRST_ID)
        prefs.edit().putInt(KEY_NEXT_ID, next + 1).apply()
        return next
    }

    companion object {
        private const val PREFS_NAME = "andharness_jobs"
        private const val KEY_PREFIX = "job_"
        private const val KEY_NEXT_ID = "next_id"

        /** JobScheduler requires a positive id; `0` is reserved for "no job". */
        const val FIRST_ID = 1000

        private val json = AppJson.Lenient
    }
}
