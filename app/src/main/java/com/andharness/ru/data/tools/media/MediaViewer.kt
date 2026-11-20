package com.andharness.ru.data.tools.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The single "open this item with the system viewer" implementation shared by the `media_open` tool
 * and the chat's rich `device_search` previews (tapping a photo/document card). Keeping one place
 * that builds the [Intent.ACTION_VIEW] means the tool and the UI cannot drift apart.
 *
 * References (official Android docs):
 * - `Intent.ACTION_VIEW` (open a URI/document with the system viewer) —
 *   https://developer.android.com/reference/android/content/Intent#ACTION_VIEW
 * - `Intent#setDataAndType` (set the data URI and MIME type together) —
 *   https://developer.android.com/reference/android/content/Intent#setDataAndType(android.net.Uri,%20java.lang.String)
 * - `Intent.FLAG_GRANT_READ_URI_PERMISSION` (grant the receiver read access to the URI) —
 *   https://developer.android.com/reference/android/content/Intent#FLAG_GRANT_READ_URI_PERMISSION
 * - `Intent.FLAG_ACTIVITY_NEW_TASK` (start from a non-Activity context) —
 *   https://developer.android.com/reference/android/content/Intent#FLAG_ACTIVITY_NEW_TASK
 * - `ActivityNotFoundException` (thrown by `startActivity` when nothing handles the intent) —
 *   https://developer.android.com/reference/android/content/ActivityNotFoundException
 * - `FileProvider` (content URIs for app-owned/local files) —
 *   https://developer.android.com/reference/androidx/core/content/FileProvider
 */
object MediaViewer {

    /**
     * Builds the viewer intent for [uri] with [mime]. `NEW_TASK` lets it start from a possibly
     * non-Activity context and `GRANT_READ_URI_PERMISSION` hands the receiver read access, so no
     * extra runtime permission is needed to view the item.
     */
    fun viewIntent(uri: Uri, mime: String): Intent = Intent(Intent.ACTION_VIEW).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        setDataAndType(uri, mime)
    }

    /**
     * Opens [uri] with the system viewer. Returns `true` when an app accepted the intent and
     * `false` when nothing can open it (an honest "no viewer" result rather than a crash).
     */
    fun open(context: Context, uri: Uri, mime: String): Boolean = try {
        context.startActivity(viewIntent(uri, mime))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
