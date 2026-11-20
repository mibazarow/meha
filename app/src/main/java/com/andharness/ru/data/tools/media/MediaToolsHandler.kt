package com.andharness.ru.data.tools.media

import android.content.Context
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.ui.UiInteractionBridge

/**
 * Builds [ToolHandler]s for the photos and media tools, in their original registration order:
 * [photos_list], [photos_delete], [media_audio_list], [media_video_list], [exif_read], [exif_write],
 * [image_thumbnail], [image_resize], [image_convert], [media_scan], [media_delete], [media_trash],
 * [media_request_write], [media_favorite_set], [media_downloads_list], [media_metadata],
 * [media_frame_extract].
 *
 * References:
 * - `MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)`,
 *   `MediaStore.Images.Media._ID`, `DISPLAY_NAME`, `SIZE`, `DATE_ADDED`:
 *   https://developer.android.com/reference/android/provider/MediaStore.Images.Media
 * - `ContentResolver.query(uri, projection, selection, selectionArgs, sortOrder)`:
 *   https://developer.android.com/training/data-storage/shared/media
 * - `ContentResolver.delete(uri, selection, selectionArgs)`:
 *   https://developer.android.com/training/data-storage/shared/media
 * - `READ_MEDIA_IMAGES` (API 33+), `READ_EXTERNAL_STORAGE` (API 33-):
 *   https://developer.android.com/training/data-storage/photos-videos#access-the-photos-you-need
 * - `MANAGE_EXTERNAL_STORAGE` + `Environment.isExternalStorageManager()`:
 *   https://developer.android.com/training/data-storage/manage-all-files
 * - `MediaScannerConnection.scanFile(...)` ([media_scan]) —
 *   https://developer.android.com/reference/android/media/MediaScannerConnection#scanFile(android.content.Context,%20java.lang.String%5B%5D,%20java.lang.String%5B%5D,%20android.media.MediaScannerConnection.OnScanCompletedListener)
 * - `androidx.exifinterface.media.ExifInterface` ([exif_read]/[exif_write]): constructor,
 *   `getAttribute(String)`, `setAttribute(String, String)`, `saveAttributes()` and the
 *   `TAG_*` constants —
 *   https://developer.android.com/reference/androidx/exifinterface/media/ExifInterface
 *   (artefact `androidx.exifinterface:exifinterface:1.3.7` —
 *   https://developer.android.com/jetpack/androidx/releases/exifinterface)
 * - `android.media.ThumbnailUtils` ([image_thumbnail]): `extractThumbnail(Bitmap, int, int)` and
 *   `createVideoThumbnail(File, Size, CancellationSignal)` (API 29+) /
 *   `createVideoThumbnail(String, int)` (deprecated, API 29-) —
 *   https://developer.android.com/reference/android/media/ThumbnailUtils
 * - `android.graphics.BitmapFactory` ([image_thumbnail]): `decodeFile(String, Options)`,
 *   `Options.inJustDecodeBounds`, `Options.outWidth`/`outHeight`, `Options.inSampleSize` —
 *   https://developer.android.com/reference/android/graphics/BitmapFactory
 * - `MediaStore.createDeleteRequest(ContentResolver, Collection<Uri>)`, the media-library lookup
 *   via `MediaStore.Files` (`_ID`, `DATA`, `MEDIA_TYPE`) and `IntentSender` ([media_delete]) —
 *   https://developer.android.com/reference/android/provider/MediaStore#createDeleteRequest(android.content.ContentResolver,%20java.util.Collection%3Candroid.net.Uri%3E)
 *   https://developer.android.com/reference/android/provider/MediaStore.Files.FileColumns
 *   https://developer.android.com/reference/android/content/IntentSender
 * - `MediaStore.createTrashRequest(ContentResolver, Collection<Uri>, boolean)` and
 *   `MediaStore.MediaColumns.IS_TRASHED` ([media_trash]) —
 *   https://developer.android.com/reference/android/provider/MediaStore#createTrashRequest(android.content.ContentResolver,%20java.util.Collection%3Candroid.net.Uri%3E,%20boolean)
 *   https://developer.android.com/reference/android/provider/MediaStore.MediaColumns#IS_TRASHED
 * - `MediaStore.createWriteRequest(ContentResolver, Collection<Uri>)` and
 *   `ContentResolver.openFileDescriptor(Uri, String)` ([media_request_write]) —
 *   https://developer.android.com/reference/android/provider/MediaStore#createWriteRequest(android.content.ContentResolver,%20java.util.Collection%3Candroid.net.Uri%3E)
 *   https://developer.android.com/reference/android/content/ContentResolver#openFileDescriptor(android.net.Uri,%20java.lang.String)
 * - `MediaStore.createFavoriteRequest(ContentResolver, Collection<Uri>, boolean)` and
 *   `MediaStore.MediaColumns.IS_FAVORITE` ([media_favorite_set]) —
 *   https://developer.android.com/training/data-storage/shared/media
 *   https://developer.android.com/reference/android/provider/MediaStore#createFavoriteRequest(android.content.ContentResolver,%20java.util.Collection%3Candroid.net.Uri%3E,%20boolean)
 *   https://developer.android.com/reference/android/provider/MediaStore.MediaColumns#IS_FAVORITE
 */
class MediaToolsHandler(
    private val context: Context,
    private val uiBridge: UiInteractionBridge,
) {

    /** The photos/media tools in their original registration order. */
    fun handlers(): List<ToolHandler> {
        val io = MediaPathIo(context)
        val consent = MediaConsentRequester(uiBridge)
        val transformer = MediaImageTransformer(io)
        return listOf(
            PhotosListToolHandler(context),
            PhotosDeleteToolHandler(context),
            MediaAudioListToolHandler(context),
            MediaVideoListToolHandler(context),
            ExifReadToolHandler(io),
            ExifWriteToolHandler(io),
            ImageThumbnailToolHandler(io, transformer),
            ImageResizeToolHandler(transformer),
            ImageConvertToolHandler(transformer),
            ImageCropToolHandler(io),
            ImageRotateToolHandler(io),
            ImageAdjustToolHandler(io),
            ImageWatermarkToolHandler(io),
            ImageOverlayTextToolHandler(io),
            ImageCollageToolHandler(io),
            ScreenAnnotateToolHandler(io),
            ImageSvgRenderToolHandler(context, io),
            ImageRemoveBackgroundToolHandler(io),
            ImageDetectObjectsToolHandler(io),
            MediaScanToolHandler(context, io),
            MediaDeleteToolHandler(context, io, uiBridge),
            MediaTrashToolHandler(context, io, consent),
            MediaRequestWriteToolHandler(context, io, consent),
            MediaFavoriteSetToolHandler(context, io, consent),
            MediaDownloadsListToolHandler(context),
            MediaMetadataToolHandler(io),
            MediaFrameExtractToolHandler(io),
        ) + AudioVideoEditToolsHandler(io).handlers()
    }
}
