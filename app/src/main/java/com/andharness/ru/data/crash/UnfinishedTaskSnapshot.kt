package com.andharness.ru.data.crash

import android.content.SharedPreferences

/**
 * Minimal snapshot of the agent task that was in flight when the process died (update.md §1.4):
 * which chat, the last user message, and a pending flag. The core reads it on the next start to
 * offer/perform continuation; it lives until the user explicitly declines or the task is done.
 *
 * Stored in plain [SharedPreferences] (no secrets are involved) so writing it on the crashing
 * thread stays allocation-cheap and cannot fail on crypto/key-store initialisation.
 */
data class UnfinishedTaskSnapshot(
    val chatId: Long?,
    val lastUserMessage: String?,
    val pending: Boolean,
    val at: Long
)
