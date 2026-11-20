package com.andharness.ru.data.index

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One document enumerated from `MediaStore`, with exactly the file-level metadata the indexer needs:
 * its stable `content://` URI, a display name, a MIME type, its size and its modified time.
 * [lastModified] is in MILLISECONDS (MediaStore's `DATE_MODIFIED` is seconds; the indexer multiplies
 * it so every stored `lastModified` is a real millisecond timestamp).
 */
data class DeviceDocumentEntry(
    val sourceUri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

/** Outcome of one MediaStore document enumeration. */
sealed interface DeviceDocumentScan {
    /** [documents] is bounded by the requested limit; [truncated] is true when more exist. */
    data class Success(
        val documents: List<DeviceDocumentEntry>,
        val truncated: Boolean,
    ) : DeviceDocumentScan

    /** Shared-storage read access is not granted; the caller skips this pass (never a crash). */
    data object PermissionDenied : DeviceDocumentScan

    /** The provider query failed; [reason] is logged and the caller skips this pass. */
    data class Failed(val reason: String) : DeviceDocumentScan
}

/**
 * Enumerates documents (PDF/Word/plain text/Excel, …) from the device via `MediaStore.Files` with a
 * single BOUNDED query. `MediaStore.Files` is the provider table that indexes non-media files and is
 * the documented surface for "applications that work with non-media file types (text, HTML, PDF,
 * etc)" — https://developer.android.com/reference/android/provider/MediaStore.Files
 *
 * Only file-level columns are read (id, name, mime, size, modified) — no bytes — so a large library
 * costs little memory; [DocumentTextSource] then reads and extracts each document lazily, one at a
 * time.
 *
 * Permission: reading files another app created from shared storage requires **All Files Access**
 * (`MANAGE_EXTERNAL_STORAGE`, checked with `Environment.isExternalStorageManager()`), the same
 * approach the app's file/`PhotosTools` handlers take. Declaring the permission is not enough and the
 * user grants it through the system settings screen; until then the scan reports
 * [DeviceDocumentScan.PermissionDenied] so the caller can skip gracefully and retry later. On
 * Android 12–12L (API 31–32) the legacy `READ_EXTERNAL_STORAGE` grant is equally accepted for
 * documents. References:
 * - `Environment.isExternalStorageManager()` (All Files Access; added in API 30) —
 *   https://developer.android.com/reference/android/os/Environment#isExternalStorageManager()
 * - `Environment` (fetched reference used for the above) —
 *   https://developer.android.com/reference/android/os/Environment
 * - Querying a content provider with a projection + sort, and `close()` via `use` —
 *   https://developer.android.com/guide/topics/providers/content-provider-basics
 * - `ContentUris.withAppendedId` (build a per-item content URI) —
 *   https://developer.android.com/reference/android/content/ContentUris#withAppendedId(android.net.Uri,%20long)
 * - `READ_EXTERNAL_STORAGE` on API ≤ 32 —
 *   https://developer.android.com/training/data-storage/shared/media#storage-permission
 */
@Singleton
class MediaStoreDocumentSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * True when the app can read documents from shared storage: All Files Access on API 30+, or the
     * legacy `READ_EXTERNAL_STORAGE` grant on API 31–32 (there is no document runtime permission from
     * Android 13 onward).
     */
    fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            return true
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Reads up to [limit] documents (newest `DATE_MODIFIED` first) off the main thread. Never throws:
     * a missing permission is [DeviceDocumentScan.PermissionDenied] and any failure is
     * [DeviceDocumentScan.Failed].
     */
    suspend fun query(limit: Int): DeviceDocumentScan = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext DeviceDocumentScan.Success(emptyList(), truncated = false)
        if (!hasPermission()) return@withContext DeviceDocumentScan.PermissionDenied

        try {
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
            )
            val (selection, selectionArgs) = buildSelection()
            val sortOrder = MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC"
            val documents = ArrayList<DeviceDocumentEntry>()
            var truncated = false

            context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)
                ?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                    val modifiedCol =
                        cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                    while (cursor.moveToNext()) {
                        if (documents.size >= limit) {
                            truncated = true
                            break
                        }
                        val name = cursor.getString(nameCol).orEmpty()
                        val mime = cursor.getString(mimeCol).orEmpty()
                        // The SQL selection already filters; this guard rejects any odd provider row.
                        if (!DocumentTypes.accepts(mime, name)) continue
                        val id = cursor.getLong(idCol)
                        val uri = ContentUris.withAppendedId(collection, id)
                        documents += DeviceDocumentEntry(
                            sourceUri = uri.toString(),
                            displayName = name,
                            mimeType = mime,
                            sizeBytes = cursor.getLong(sizeCol),
                            // DATE_MODIFIED is seconds since the epoch; normalise to milliseconds.
                            lastModified = cursor.getLong(modifiedCol) * 1000L,
                        )
                    }
                }
            DeviceDocumentScan.Success(documents, truncated)
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore document query failed", e)
            DeviceDocumentScan.Failed(e.message ?: "document query failed")
        }
    }

    /**
     * Builds the `MediaStore.Files` selection: a MIME-type set AND a filename-extension fallback
     * (some providers report `text/plain` or an empty type for Markdown, and OOXML files are matched
     * robustly by suffix). Column/selection-arg syntax per the content-provider guide; `LIKE` is
     * case-insensitive for ASCII in SQLite, so uppercase extensions match too.
     */
    private fun buildSelection(): Pair<String, Array<String>> {
        val mimes = DocumentTypes.MIME_TYPES.toList()
        val mimePlaceholders = mimes.joinToString(", ") { "?" }
        val nameClauses = DocumentTypes.EXTENSIONS.joinToString(" OR ") {
            "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE ?"
        }
        val selection =
            "(${MediaStore.Files.FileColumns.MIME_TYPE} IN ($mimePlaceholders) OR ($nameClauses))"
        val args: Array<String> = mimes.toTypedArray() + DocumentTypes.EXTENSIONS.map { "%." + it }
        return selection to args
    }

    private companion object {
        const val TAG = "Andharness"
    }
}
