package com.andharness.ru.data.tools

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.andharness.ru.domain.common.Hashing
import java.io.File
import java.io.IOException

/**
 * Builds FileProvider content URIs while keeping the provider itself scoped to app-owned directories
 * (see `res/xml/file_paths.xml`, audit S2).
 *
 * `FileProvider.getUriForFile` throws `IllegalArgumentException` for a file that is not under a path
 * declared in `file_paths.xml`. The files_* / intent / mms tools can address ANY file under the
 * effective tool root (the whole of shared storage once MANAGE_EXTERNAL_STORAGE is granted), so a
 * file outside the provider roots is first copied into [STAGING_DIR] inside the app cache — a
 * directory the provider already exposes through `<cache-path>` — and the copy's URI is returned.
 * The original file is never exposed by widening the provider configuration.
 *
 * References:
 * - `androidx.core.content.FileProvider` (`getUriForFile` throws `IllegalArgumentException` when the
 *   file is not under a configured root; `<cache-path>` maps to `Context.getCacheDir()`):
 *   https://developer.android.com/reference/androidx/core/content/FileProvider
 * - `Context.getCacheDir`:
 *   https://developer.android.com/reference/android/content/Context#getCacheDir()
 */
internal object SharedFileContent {

    /** Cache sub-directory that holds staged copies of files outside the provider roots. */
    const val STAGING_DIR = "shared_content"

    /** The FileProvider authority declared in AndroidManifest.xml. */
    fun authority(context: Context): String = "${context.packageName}.fileprovider"

    /**
     * A content URI for [file]. A file already inside a provider root is exposed directly; any other
     * file is copied into the cache staging area first. Throws only if the copy cannot be made.
     */
    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, authority(context), providerAccessibleFile(context, file))

    /**
     * The same [file] when the provider can expose it directly, otherwise a staged cache copy of it.
     * Use this for hand-offs (e.g. the package installer) that take a [File] and build the URI
     * themselves.
     */
    fun providerAccessibleFile(context: Context, file: File): File =
        if (directUriOrNull(context, file) != null) file else stageCopy(context, file)

    /** A direct URI when [file] (or a directory) is inside a configured root, otherwise `null`. */
    fun directUriOrNull(context: Context, file: File): Uri? = try {
        FileProvider.getUriForFile(context, authority(context), file)
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * Copies [source] into a per-source sub-directory of the cache staging area and returns the copy.
     * The bucket name is a SHA-256 prefix of the source's absolute path, so the receiver still sees
     * the original file name while two same-named files from different folders never collide. The
     * cache is app-private and system-managed, so staged copies are transient by design.
     */
    fun stageCopy(context: Context, source: File): File {
        val root = File(context.cacheDir, STAGING_DIR)
        if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) {
            throw IOException("Не удалось создать каталог для файла: ${root.absolutePath}")
        }
        val bucket = File(root, Hashing.sha256Hex(source.absolutePath).take(16))
        bucket.deleteRecursively()
        if (!bucket.mkdirs() && !bucket.isDirectory) {
            throw IOException("Не удалось создать каталог для файла: ${bucket.absolutePath}")
        }
        val target = File(bucket, source.name.ifEmpty { "file" })
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }
}
