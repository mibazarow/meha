package com.andharness.ru.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.andharness.ru.domain.ui.UiConsentPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Result receiver for the `shortcut_pin` tool (update.md §1).
 *
 * `ShortcutManager.requestPinShortcut` returns immediately; the system sends the result only when
 * the user actually approves pinning the shortcut (a denial produces no callback at all). Android's
 * documentation recommends an unexported, manifest-declared receiver for that callback, which is
 * exactly what this class is.
 *
 * Reference:
 * https://developer.android.com/reference/android/content/pm/ShortcutManager#requestPinShortcut(android.content.pm.ShortcutInfo,%20android.content.IntentSender)
 */
class ShortcutPinReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        // The callback is delivered only on success, so any delivery means "pinned by the user".
        complete(true)
    }

    companion object {
        /** Explicit action on the callback intent (the broadcast is addressed to this component). */
        const val ACTION = "com.andharness.ru.action.SHORTCUT_PIN_RESULT"

        /** Stable request code for the callback [android.app.PendingIntent]. */
        const val REQUEST_CODE = 4711

        /**
         * Bounded wait for the user to answer the system pin dialog. Bounded by the shared
         * [UiConsentPolicy.TIMEOUT_MS] so a debug-bridge `goAsync()` caller is released inside
         * Android's shortest broadcast ANR window instead of ANRing (BUG-014/BUG-016) —
         * https://developer.android.com/reference/android/content/BroadcastReceiver#goAsync()
         */
        const val TIMEOUT_MS = UiConsentPolicy.TIMEOUT_MS

        private val lock = Any()

        @Volatile
        private var pending: CompletableDeferred<Boolean>? = null

        /** Arms the receiver before `requestPinShortcut()` so an immediate callback is not lost. */
        fun arm() {
            synchronized(lock) { pending = CompletableDeferred() }
        }

        /** Drops the waiter (e.g. when the pin request itself was rejected by the system). */
        fun disarm() {
            synchronized(lock) { pending = null }
        }

        /** Waits up to [TIMEOUT_MS] for the pin callback; `false` means the user did not confirm. */
        suspend fun awaitPinned(): Boolean {
            val deferred = synchronized(lock) { pending } ?: return false
            val result = withTimeoutOrNull(TIMEOUT_MS) { deferred.await() } ?: false
            synchronized(lock) { if (pending === deferred) pending = null }
            return result
        }

        private fun complete(pinned: Boolean) {
            synchronized(lock) { pending }?.complete(pinned)
        }
    }
}
