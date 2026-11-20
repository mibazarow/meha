package com.andharness.ru.data.tools.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.andharness.ru.data.tools.effectiveToolRoot
import com.andharness.ru.data.tools.resolveSafePath
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Path resolution and MediaStore lookup helpers shared by the photos/media tools.
 *
 * Every path argument resolves against [Context.effectiveToolRoot] via [resolveSafePath], so an
 * absolute path outside the allowed storage root is rejected.
 */
internal class MediaPathIo(private val context: Context) {

    /**
     * Resolves one `paths` entry against [Context.effectiveToolRoot] via [resolveSafePath]; throws
     * [IllegalArgumentException] when the path escapes the root.
     */
    fun resolveMediaPath(raw: String): File = resolveSafePath(context.effectiveToolRoot(), raw)

    /**
     * Resolves an arbitrary string path argument against [Context.effectiveToolRoot] via
     * [resolveSafePath].
     */
    fun pathFile(arguments: JsonObject, key: String): File {
        val raw = (arguments[key] as? JsonPrimitive)?.content.orEmpty()
        return resolveSafePath(context.effectiveToolRoot(), raw)
    }

    /** Resolves the `path` argument against [Context.effectiveToolRoot] (image tools). */
    fun imageFile(arguments: JsonObject): File = pathFile(arguments, "path")

    /**
     * Validates a `paths` argument shared by `media_trash`, `media_request_write` and
     * `media_favorite_set`: splits on `,`/`;`, trims, drops blanks and resolves every entry against
     * the tool root up front, so any invalid/missing path aborts the whole request.
     */
    fun resolveMediaPaths(raw: String): MediaPathsResult {
        val entries = raw.split(',', ';').map { it.trim() }.filter { it.isNotBlank() }
        if (entries.isEmpty()) {
            return MediaPathsResult(emptyList(), "Укажите paths — пути к медиафайлам через запятую.")
        }
        val bad = ArrayList<String>()
        val files = ArrayList<File>()
        for (entry in entries) {
            val file = try {
                resolveMediaPath(entry)
            } catch (e: IllegalArgumentException) {
                bad.add("«$entry» (${e.message})")
                continue
            }
            if (!file.exists() || !file.isFile) {
                bad.add("«$entry»")
                continue
            }
            files.add(file)
        }
        if (bad.isNotEmpty()) {
            return MediaPathsResult(
                emptyList(),
                "Файлы не найдены или некорректны: ${bad.joinToString(", ")}.",
            )
        }
        return MediaPathsResult(files, null)
    }

    /**
     * Maps [files] to their media collection Uris via [mediaStoreMediaUri], returning the mapped
     * Uris together with the display names skipped because they are not indexed as media.
     */
    fun mediaUrisFor(files: List<File>): Pair<List<Uri>, List<String>> {
        val uris = ArrayList<Uri>()
        val skipped = ArrayList<String>()
        for (file in files) {
            val uri = mediaStoreMediaUri(file)
            if (uri == null) skipped.add("«${file.name}»") else uris.add(uri)
        }
        return uris to skipped
    }

    /**
     * Looks [file] up in the media library by its absolute path and returns the Uri of the matching
     * media collection (images/video/audio), or `null` when the file is not indexed as media.
     *
     * References: `MediaStore.Files` / `MediaStore.Files.FileColumns` (`_ID`, `DATA`, `MEDIA_TYPE`,
     * `MEDIA_TYPE_IMAGE` / `MEDIA_TYPE_VIDEO` / `MEDIA_TYPE_AUDIO`) —
     * https://developer.android.com/reference/android/provider/MediaStore.Files.FileColumns
     * and `ContentUris.withAppendedId(Uri, long)` —
     * https://developer.android.com/reference/android/content/ContentUris#withAppendedId(android.net.Uri,%20long)
     */
    fun mediaStoreMediaUri(file: File): Uri? {
        val filesCollection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
        val selection = MediaStore.Files.FileColumns.DATA + "=?"
        val selectionArgs = arrayOf(file.absolutePath)
        return context.contentResolver.query(filesCollection, projection, selection, selectionArgs, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val id = cursor.getLong(
                    cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID),
                )
                val mediaType = cursor.getInt(
                    cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE),
                )
                val collection = when (mediaType) {
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE ->
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO ->
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO ->
                        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    else -> null
                }
                collection?.let { ContentUris.withAppendedId(it, id) }
            }
    }

    /** Outcome of [resolveMediaPaths]: either [files] or a non-null [error] message. */
    class MediaPathsResult(val files: List<File>, val error: String?)
}
