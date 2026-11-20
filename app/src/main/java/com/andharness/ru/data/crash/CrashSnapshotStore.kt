package com.andharness.ru.data.crash

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CrashSnapshotStore @Inject constructor(
    @ApplicationContext context: Context
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun save(chatId: Long?, lastUserMessage: String?, at: Long) {
        prefs.edit()
            .putBoolean(KEY_PENDING, true)
            .putLong(KEY_AT, at)
            .apply {
                if (chatId != null) putLong(KEY_CHAT_ID, chatId) else remove(KEY_CHAT_ID)
                if (!lastUserMessage.isNullOrEmpty()) {
                    putString(KEY_LAST_MESSAGE, lastUserMessage)
                } else {
                    remove(KEY_LAST_MESSAGE)
                }
            }
            .apply()
    }

    fun get(): UnfinishedTaskSnapshot? {
        if (!prefs.getBoolean(KEY_PENDING, false)) return null
        return UnfinishedTaskSnapshot(
            chatId = if (prefs.contains(KEY_CHAT_ID)) prefs.getLong(KEY_CHAT_ID, 0L) else null,
            lastUserMessage = prefs.getString(KEY_LAST_MESSAGE, null),
            pending = true,
            at = prefs.getLong(KEY_AT, 0L)
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val PREFS_FILE = "andharness_crash_snapshot"
        private const val KEY_PENDING = "pending"
        private const val KEY_AT = "at"
        private const val KEY_CHAT_ID = "chat_id"
        private const val KEY_LAST_MESSAGE = "last_user_message"
    }
}
