package com.andharness.ru.data.index

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One image enumerated from `MediaStore`, with exactly the file-level metadata the indexer needs:
 * its stable `content://` URI, a display name, a MIME type, its size and its modified time.
 * [lastModified] is in MILLISECONDS (MediaStore's `DATE_MODIFIED` is seconds; the indexer multiplies
 * it so every stored `lastModified` is a real millisecond timestamp).
 */
data class DeviceImageEntry(
    val sourceUri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

/** Outcome of one MediaStore enumeration. */
sealed interface DeviceImageScan {
    /** [images] is bounded by the requested limit; [truncated] is true when more images exist. */
    data class Success(val images: List<DeviceImageEntry>, val truncated: Boolean) : DeviceImageScan

    /** The media-read permission is not granted; the caller skips this pass (never a crash). */
    data object PermissionDenied : DeviceImageScan

    /** The provider query failed; [reason] is logged and the caller skips this pass. */
    data class Failed(val reason: String) : DeviceImageScan
}

/**
 * Enumerates still images from the device gallery via `MediaStore` with a single BOUNDED query.
 *
 * Only file-level columns are read (id, name, mime, size, modified) — no bytes and no bitmaps — so a
 * large library costs little memory; the indexer then decodes each image lazily, one at a time.
 *
 * Permission: images are readable through `READ_MEDIA_IMAGES` on Android 13+ (API 33) and through
 * `READ_EXTERNAL_STORAGE` on Android 12–12L (API 31–32); the app targets minSdk 31. When the relevant
 * permission is not granted the scan reports [DeviceImageScan.PermissionDenied] so the caller can skip
 * gracefully and retry once the user grants it. The same permission pair is used by `PhotosTools` /
 * `photos_list`.
 *
 * References:
 * - MediaStore image columns + `EXTERNAL_CONTENT_URI` + `DATE_MODIFIED` (seconds since epoch) —
 *   https://developer.android.com/reference/android/provider/MediaStore.Images.Media
 * - Querying a content provider with a projection + sort, and `close()` via `use` —
 *   https://developer.android.com/guide/topics/providers/content-provider-basics
 * - `ContentUris.withAppendedId` (build a per-item content URI) —
 *   https://developer.android.com/reference/android/content/ContentUris#withAppendedId(android.net.Uri,%20long)
 * - `READ_MEDIA_IMAGES` (API 33+) / `READ_EXTERNAL_STORAGE` (≤ API 32) —
 *   https://developer.android.com/training/data-storage/shared/media#storage-permission
 */
@Singleton
class MediaStoreImageSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** True when the platform media-read permission for images is granted. */
    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        requiredPermission(),
    ) == PackageManager.PERMISSION_GRANTED

    /** The permission to request/check for image reads on this API level. */
    fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    /**
     * Reads up to [limit] images (newest `DATE_MODIFIED` first) off the main thread. Never throws:
     * a missing permission is [DeviceImageScan.PermissionDenied] and any failure is
     * [DeviceImageScan.Failed].
     */
    suspend fun query(limit: Int): DeviceImageScan = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext DeviceImageScan.Success(emptyList(), truncated = false)
        if (!hasPermission()) return@withContext DeviceImageScan.PermissionDenied

        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_MODIFIED,
            )
            val sortOrder = MediaStore.Images.Media.DATE_MODIFIED + " DESC"
            val images = ArrayList<DeviceImageEntry>()
            var truncated = false

            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    if (images.size >= limit) {
                        truncated = true
                        break
                    }
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id,
                    )
                    images += DeviceImageEntry(
                        sourceUri = uri.toString(),
                        displayName = cursor.getString(nameCol).orEmpty(),
                        mimeType = cursor.getString(mimeCol) ?: "image/*",
                        sizeBytes = cursor.getLong(sizeCol),
                        // DATE_MODIFIED is seconds since the epoch; normalise to milliseconds.
                        lastModified = cursor.getLong(modifiedCol) * 1000L,
                    )
                }
            }
            DeviceImageScan.Success(images, truncated)
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore image query failed", e)
            DeviceImageScan.Failed(e.message ?: "media query failed")
        }
    }

    private companion object {
        const val TAG = "Andharness"
    }
}
