package com.andharness.ru.data.chat

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent `topic → chatId` map used by [TopicChatRouter] so any producer that has no chat of its
 * own (notification streams, the floating assistant) reuses one stable chat per topic instead of
 * spawning a new chat on every event.
 *
 * This is the neutral generalisation of the former `NotificationChatStore`: the storage format and
 * the preference file are unchanged, so the topic→chat ids already persisted for notifications are
 * reused verbatim across the upgrade.
 *
 * Backed by plain [SharedPreferences] — it is a non-secret index that must be readable synchronously
 * from a worker/receiver; no Room schema or migration number is consumed.
 * https://developer.android.com/reference/android/content/Context#getSharedPreferences(java.lang.String,int)
 */
@Singleton
class TopicChatStore @Inject constructor(
    @ApplicationContext context: Context
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** The chat remembered for [topic], or null when nothing was stored (or it was cleared). */
    fun chatId(topic: String): Long? =
        prefs.getLong(keyFor(topic), NO_CHAT_ID).takeIf { it != NO_CHAT_ID }

    /** Remembers [chatId] for [topic] so later events reuse that same chat. */
    fun setChatId(topic: String, chatId: Long) {
        prefs.edit().putLong(keyFor(topic), chatId).apply()
    }

    /** Forgets [topic]; the next route then creates a fresh chat (e.g. after the old one was deleted). */
    fun clear(topic: String) {
        prefs.edit().remove(keyFor(topic)).apply()
    }

    companion object {
        const val PREFS_FILE = "andharness_notif_chats"
        private const val NO_CHAT_ID = -1L

        /** The preference key a [topic]'s chat id is stored under; public for honest diagnostics. */
        fun keyFor(topic: String): String = "chat_$topic"
    }
}
