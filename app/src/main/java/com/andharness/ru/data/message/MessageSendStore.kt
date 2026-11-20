package com.andharness.ru.data.message

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import com.andharness.ru.domain.serialization.AppJson
import com.andharness.ru.domain.serialization.decodeOrNull

/**
 * Plain-[SharedPreferences] store of pending deferred messages (update.md §4.8).
 *
 * A single JSON array under one key keeps the store small, synchronous and migration-free; jobs are
 * removed as soon as they fire, so a "missed" run is naturally reported by the worker's own lateness
 * check rather than by scanning a backlog.
 */
@Singleton
class MessageSendStore @Inject constructor(
    @ApplicationContext context: Context
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private val json = AppJson.Lenient

    fun nextId(): Long = prefs.getLong(KEY_NEXT_ID, 1L).also {
        prefs.edit().putLong(KEY_NEXT_ID, it + 1).apply()
    }

    fun add(job: DeferredMessage) {
        val all = list().filterNot { it.id == job.id } + job
        write(all)
    }

    fun get(id: Long): DeferredMessage? = list().firstOrNull { it.id == id }

    fun list(): List<DeferredMessage> =
        json.decodeOrNull<List<DeferredMessage>>(prefs.getString(KEY_JOBS, null)) ?: emptyList()

    /** Removes the job (called by the worker before sending, so the text is not kept around). */
    fun remove(id: Long) {
        write(list().filterNot { it.id == id })
    }

    fun clear() {
        prefs.edit().remove(KEY_JOBS).apply()
    }

    private fun write(jobs: List<DeferredMessage>) {
        prefs.edit().putString(KEY_JOBS, json.encodeToString(jobs)).apply()
    }

    companion object {
        const val PREFS_FILE = "andharness_message_send"

        private const val KEY_JOBS = "deferred_messages"
        private const val KEY_NEXT_ID = "deferred_next_id"
    }
}
