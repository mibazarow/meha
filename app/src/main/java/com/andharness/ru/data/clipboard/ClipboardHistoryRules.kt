package com.andharness.ru.data.clipboard


/**
 * Pure, Android-free rules for the clipboard history (bounds, length cap and consecutive dedupe), so
 * they are exercised on the JVM by `ClipboardHistoryStoreTest`.
 *
 * ## Why capture is foreground-only (official docs)
 * Since Android 10 an app can read the clipboard only while it is the default IME or holds input
 * focus, so a background service cannot observe what the user copies elsewhere:
 * - "Unless your app is the default input method editor (IME) or is the app that currently has
 *   focus, your app cannot access clipboard data on Android 10 or higher."
 *   https://developer.android.com/about/versions/10/privacy/changes#clipboard-data
 * - `ClipboardManager.getPrimaryClip()` "return[s] null" and `hasPrimaryClip()` returns false
 *   "if the application is not the default IME or does not have input focus".
 *   https://developer.android.com/reference/android/content/ClipboardManager
 *
 * The history is therefore honest by construction: it only ever contains text this app copied
 * (`clipboard_set`) or read at a moment the app legitimately had the clipboard (foregrounding).
 */
object ClipboardHistoryRules {

    /** Hard cap on stored entries; the oldest are dropped first. */
    const val MAX_ENTRIES = 50

    /** Cap on a single stored item, so one huge copy cannot bloat the file or the prompt context. */
    const val MAX_TEXT_CHARS = 4_000

    /**
     * Trims and bounds a raw clipboard text. Returns null for blank input (a blank copy is never
     * remembered); otherwise the text, truncated to [MAX_TEXT_CHARS].
     */
    fun normalizeText(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return if (trimmed.length > MAX_TEXT_CHARS) trimmed.take(MAX_TEXT_CHARS) else trimmed
    }

    /** True when [text] equals the newest stored entry — consecutive duplicates are not re-stored. */
    fun isDuplicateOfLast(entries: List<ClipboardEntry>, text: String): Boolean =
        entries.lastOrNull()?.text == text

    /** Keeps only the newest [MAX_ENTRIES] entries (input is oldest-first). */
    fun bound(entries: List<ClipboardEntry>): List<ClipboardEntry> = entries.takeLast(MAX_ENTRIES)
}
