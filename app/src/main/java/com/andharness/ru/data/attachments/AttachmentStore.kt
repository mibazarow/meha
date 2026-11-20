package com.andharness.ru.data.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.andharness.ru.data.tools.toolRootDir
import com.andharness.ru.domain.agent.LlmContentPart
import com.andharness.ru.domain.chat.AttachmentKind
import com.andharness.ru.domain.chat.AttachmentPolicy
import com.andharness.ru.domain.chat.ChatAttachment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads picked attachments and stores them in a stable, tool-readable location (§19).
 *
 * Files are copied into `<toolRoot>/attachments/` — the app's `meha` working directory inside the
 * app-private external files dir (`getExternalFilesDir`, falling back to internal storage) — which
 * is also inside the root the document/file tools resolve against (`Context.effectiveToolRoot()`),
 * so `doc_read`/`doc_info`/`ocr_image` can open the saved copy by path. The picked source URI
 * itself may be temporary/permission-scoped, so it is never handed to the agent.
 *
 * References:
 * - `ContentResolver.openInputStream` / `getType` —
 *   https://developer.android.com/reference/android/content/ContentResolver
 * - `OpenableColumns.DISPLAY_NAME` / `SIZE` —
 *   https://developer.android.com/reference/android/provider/OpenableColumns
 * - `BitmapFactory.Options.inSampleSize` / `inJustDecodeBounds` —
 *   https://developer.android.com/reference/android/graphics/BitmapFactory.Options#inSampleSize
 * - `Base64.encodeToString` —
 *   https://developer.android.com/reference/android/util/Base64
 */
@Singleton
class AttachmentStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** Resolves display name, mime type, size and kind for a picked URI. */
    suspend fun describe(uri: Uri): AttachmentInfo = withContext(Dispatchers.IO) {
        var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex >= 0) {
                        cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let { name = it }
                    }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        }
        if (name.isBlank()) name = "attachment"
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
        AttachmentInfo(name = name, mime = mime, size = size, kind = AttachmentPolicy.classify(mime, name))
    }

    /**
     * Decodes a small square-ish preview for an image URI using [BitmapFactory.Options.inSampleSize]
     * so the full-resolution bitmap is never loaded into memory. Returns null on any failure, which
     * the UI renders as an honest "could not load" status rather than a fake success.
     */
    suspend fun loadThumbnail(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            val sample = AttachmentPolicy.sampleSize(
                bounds.outWidth,
                bounds.outHeight,
                AttachmentPolicy.THUMBNAIL_PX
            )
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: return@runCatching null

            val (targetWidth, targetHeight) = AttachmentPolicy.scaledSize(
                decoded.width,
                decoded.height,
                AttachmentPolicy.THUMBNAIL_PX
            )
            if (targetWidth == decoded.width && targetHeight == decoded.height) {
                decoded
            } else {
                val scaled = Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
                if (scaled !== decoded) decoded.recycle()
                scaled
            }
        }.getOrNull()
    }

    /**
     * Copies the picked file into the app-private `attachments` directory under a collision-free
     * name that still preserves the original one. Returns null (honest failure) when the copy fails.
     */
    suspend fun save(uri: Uri, name: String, mime: String, kind: AttachmentKind): ChatAttachment? =
        withContext(Dispatchers.IO) {
            runCatching {
                val directory = File(context.toolRootDir(), ATTACHMENTS_DIR)
                if (!directory.exists() && !directory.mkdirs()) return@runCatching null
                val safeName = sanitizeName(name)
                val target = File(directory, "${System.currentTimeMillis()}_$safeName")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: return@runCatching null
                if (!target.isFile || target.length() == 0L) {
                    target.delete()
                    return@runCatching null
                }
                ChatAttachment(path = target.absolutePath, name = name, mime = mime, kind = kind)
            }.getOrNull()
        }

    /**
     * Copies a local file into the app-private `attachments` directory, sharing [save]'s
     * collision-free naming and the `attachments/` location that the document/file tools resolve
     * against. Used by the composer camera flow (§19.2): the system camera app writes a temp file
     * into the app cache, and it is imported here before the temp file is deleted.
     *
     * Returns null (honest failure) when the source is missing/empty or the copy fails; the caller
     * then reports an honest notice instead of staging a broken attachment.
     */
    suspend fun importCapture(
        file: File,
        name: String,
        mime: String,
        kind: AttachmentKind
    ): ChatAttachment? = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.isFile || file.length() == 0L) return@runCatching null
            val directory = File(context.toolRootDir(), ATTACHMENTS_DIR)
            if (!directory.exists() && !directory.mkdirs()) return@runCatching null
            val safeName = sanitizeName(name)
            val target = File(directory, "${System.currentTimeMillis()}_$safeName")
            file.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (!target.isFile || target.length() == 0L) {
                target.delete()
                return@runCatching null
            }
            ChatAttachment(path = target.absolutePath, name = name, mime = mime, kind = kind)
        }.getOrNull()
    }

    /**
     * File-based counterpart of [loadThumbnail]: decodes the same small preview straight from a
     * local file with `inSampleSize`, without going through a content URI. Used for a captured
     * photo that has just been imported into `attachments/`.
     */
    suspend fun loadThumbnail(file: File): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            val sample = AttachmentPolicy.sampleSize(
                bounds.outWidth,
                bounds.outHeight,
                AttachmentPolicy.THUMBNAIL_PX
            )
            val decoded = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return@runCatching null

            val (targetWidth, targetHeight) = AttachmentPolicy.scaledSize(
                decoded.width,
                decoded.height,
                AttachmentPolicy.THUMBNAIL_PX
            )
            if (targetWidth == decoded.width && targetHeight == decoded.height) {
                decoded
            } else {
                val scaled = Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
                if (scaled !== decoded) decoded.recycle()
                scaled
            }
        }.getOrNull()
    }

    /**
     * Encodes an image file as an inline `data:` URI for a multimodal message. The image is
     * downscaled so its longest side is at most [AttachmentPolicy.MAX_IMAGE_DIMEN] and re-encoded as
     * JPEG, keeping the request context bounded (§19.3/§19.4).
     */
    suspend fun imageContentPart(file: File): LlmContentPart.ImageUrl? =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!file.isFile) return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

                val sample = AttachmentPolicy.sampleSize(
                    bounds.outWidth,
                    bounds.outHeight,
                    AttachmentPolicy.MAX_IMAGE_DIMEN
                )
                val decoded = BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                ) ?: return@runCatching null
                try {
                    encodeImagePart(decoded)
                } finally {
                    // The bitmap decoded here is owned by this method, unlike the one passed to the
                    // Bitmap overload below.
                    decoded.recycle()
                }
            }.getOrNull()
        }

    /**
     * Encodes an already-decoded [bitmap] as an inline `data:` URI for a multimodal message, reusing
     * the same downscale ([AttachmentPolicy.MAX_IMAGE_DIMEN]) and JPEG quality ([JPEG_QUALITY])
     * policy as the file-based overload. Returns null on failure.
     *
     * The caller owns [bitmap]: it is neither recycled nor closed here. Only an intermediate bitmap
     * created by this method is recycled.
     */
    fun imageContentPart(bitmap: Bitmap): LlmContentPart.ImageUrl? =
        runCatching { encodeImagePart(bitmap) }.getOrNull()

    /**
     * Shared downscale + JPEG encode used by both [imageContentPart] overloads. [source] is never
     * recycled; an intermediate scaled copy is recycled before returning.
     */
    private fun encodeImagePart(source: Bitmap): LlmContentPart.ImageUrl? {
        val (targetWidth, targetHeight) = AttachmentPolicy.scaledSize(
            source.width,
            source.height,
            AttachmentPolicy.MAX_IMAGE_DIMEN
        )
        val scaled = if (targetWidth == source.width && targetHeight == source.height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        }
        try {
            val bytes = ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
            if (bytes.isEmpty()) return null
            val dataUri = "data:image/jpeg;base64," +
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            return LlmContentPart.ImageUrl(dataUri)
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }

    /** Strips path separators and control characters so the original name can be used on disk. */
    private fun sanitizeName(name: String): String {
        val cleaned = name.map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }
            .joinToString("")
            .trim()
            .trimStart('.')
        return cleaned.ifBlank { "attachment" }.take(MAX_NAME_LENGTH)
    }

    companion object {
        private const val ATTACHMENTS_DIR = "attachments"
        private const val MAX_NAME_LENGTH = 120
        private const val JPEG_QUALITY = 80
    }
}
