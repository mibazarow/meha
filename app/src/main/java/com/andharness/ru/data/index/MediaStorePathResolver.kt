package com.andharness.ru.data.index

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

/**
 * Best-effort resolver from a MediaStore `content://` URI to its on-disk absolute path, used by
 * `device_search` so a hit can be shown with a plain path alongside its URI. The path is a
 * convenience only: when the platform does not expose it (scoped storage, a provider that omits the
 * column, or a permission gap) this returns `null` and the caller simply omits it — it never throws.
 *
 * Reading the legacy `_data` column (`MediaStore.MediaColumns.DATA`) is deprecated since API 29 and
 * is not guaranteed; the modern, permission-correct handle for shared media is the `content://` URI
 * itself (which `media_open` / `media_share` use). References:
 * - `MediaStore.MediaColumns.DATA` (deprecated; `_data`) —
 *   https://developer.android.com/reference/android/provider/MediaStore.MediaColumns#DATA
 * - Querying a content provider and closing the cursor with `use` —
 *   https://developer.android.com/guide/topics/providers/content-provider-basics
 * - Scoped storage / shared-media access —
 *   https://developer.android.com/training/data-storage/shared/media
 */
internal class MediaStorePathResolver(private val context: Context) {

    /** The absolute path behind [uri], or `null` when it is not derivable. Never throws. */
    fun absolutePath(uri: String): String? {
        if (!uri.trim().startsWith("content://", ignoreCase = true)) return null
        return try {
            @Suppress("DEPRECATION")
            val projection = arrayOf(MediaStore.MediaColumns.DATA)
            context.contentResolver.query(Uri.parse(uri), projection, null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } catch (e: Exception) {
            Log.i(TAG, "MediaStore path resolution failed for $uri", e)
            null
        }
    }

    private companion object {
        const val TAG = "Andharness"
    }
}
