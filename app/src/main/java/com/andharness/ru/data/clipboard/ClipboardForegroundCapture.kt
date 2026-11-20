package com.andharness.ru.data.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Best-effort foreground capture of the system clipboard into [ClipboardHistoryStore].
 *
 * Android 10+ blocks background clipboard reads, so the app may only observe the clipboard at a
 * moment it legitimately has it — the default IME, or holding input focus:
 * - "Unless your app is the default input method editor (IME) or is the app that currently has
 *   focus, your app cannot access clipboard data on Android 10 or higher."
 *   https://developer.android.com/about/versions/10/privacy/changes#clipboard-data
 * - `ClipboardManager.hasPrimaryClip()` returns false ("If the application is not the default IME or
 *   does not have input focus"), and `Activity.onWindowFocusChanged(true)` is the point at which the
 *   activity gains that focus:
 *   https://developer.android.com/reference/android/content/ClipboardManager
 *   https://developer.android.com/reference/android/app/Activity#onWindowFocusChanged(boolean)
 *
 * [captureIfAllowed] is intentionally silent and non-throwing: a null/foreign clip, a denied read or
 * a failed store write must never crash or spam. Consecutive duplicates are dropped by the store, so
 * calling this on both `onStart` and window focus is safe.
 */
@Singleton
class ClipboardForegroundCapture @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: ClipboardHistoryStore,
) {

    /** Reads the clipboard if the platform allows it and records the text. Never throws. */
    fun captureIfAllowed() {
        try {
            val cm = context.getSystemService(ClipboardManager::class.java) ?: return
            // Returns false (and getPrimaryClip() returns null) without input focus / IME role, so
            // this check is exactly the honest gate for the Android 10+ restriction.
            if (!cm.hasPrimaryClip()) return
            val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: return
            // Android exposes no public source-package API for the clipboard, so the origin is
            // honestly unknown here (unlike clipboard_set, which records its own package).
            store.record(text, sourcePackage = null)
        } catch (e: Exception) {
            Log.w(TAG, "Clipboard foreground capture skipped: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "ClipboardCapture"
    }
}
