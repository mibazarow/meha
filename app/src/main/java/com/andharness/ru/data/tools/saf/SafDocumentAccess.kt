package com.andharness.ru.data.tools.saf

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.andharness.ru.domain.saf.SafGrantRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Document-tree access, grant health and path resolution shared by the SAF tools: `DocumentFile`
 * views of persisted tree grants, safe relative-path walking and best-effort backup/copy helpers.
 *
 * References:
 * - `DocumentFile.fromTreeUri(Context, Uri)` / `findFile(String)` / `createFile(String, String)` /
 *   `createDirectory(String)` / `delete()`:
 *   https://developer.android.com/reference/androidx/documentfile/provider/DocumentFile
 * - `ContentResolver.openInputStream(Uri)` / `openOutputStream(Uri, String)`:
 *   https://developer.android.com/reference/android/content/ContentResolver
 */
internal class SafDocumentAccess(
    private val context: Context,
    private val safGrantRepository: SafGrantRepository,
) {

    /**
     * Live availability check of a persisted tree grant. A grant is available when *either* signal
     * holds, so the check matches the grants that actually work:
     *
     * 1. the tree root opens right now as a readable directory through
     *    `DocumentFile.fromTreeUri(...)` — the very same path the read/write tools use; or
     * 2. the persisted runtime permission is still listed read+write for this exact tree URI
     *    (`ContentResolver.getPersistedUriPermissions()`), matched through [SafUriNormalizer] so a
     *    differently percent-encoded or trailing-slash URI still compares equal.
     */
    fun isGrantAlive(uri: Uri): Boolean =
        canOpenTree(uri) || hasPersistedReadWrite(uri)

    /**
     * True when `getPersistedUriPermissions()` still lists read+write access for [uri]. Both sides
     * are normalized ([SafUriNormalizer]) so encoding differences cannot cause a false negative.
     */
    private fun hasPersistedReadWrite(uri: Uri): Boolean = try {
        val target = uri.toString()
        context.contentResolver.persistedUriPermissions.any { permission ->
            SafUriNormalizer.matches(permission.uri.toString(), target) &&
                permission.isReadPermission &&
                permission.isWritePermission
        }
    } catch (e: Exception) {
        false
    }

    /**
     * True when the tree root opens as a readable directory right now. This is a real access check
     * (an empty folder still succeeds) and mirrors exactly what the read/write tools do, so a
     * positive result means those tools will work too.
     */
    private fun canOpenTree(uri: Uri): Boolean {
        return try {
            val tree = DocumentFile.fromTreeUri(context, uri) ?: return false
            tree.isDirectory && tree.canRead()
        } catch (e: Exception) {
            false
        }
    }

    /** Persisted tree URI of a grant, or null when there is no such grant. */
    suspend fun grantUri(grantId: Long): Uri? =
        safGrantRepository.get(grantId)?.uri?.let(Uri::parse)

    /**
     * DocumentFile view of a grant's tree root, or null when the grant does not exist.
     */
    suspend fun treeRoot(grantId: Long): DocumentFile? =
        grantUri(grantId)?.let { DocumentFile.fromTreeUri(context, it) }

    /**
     * Splits a tool-supplied relative path into safe segments. Returns null when the path is
     * rejected (absolute, `..`, blank segment or NUL byte). A blank path yields the empty list,
     * i.e. the tree root. Backslashes are normalized to `/`.
     */
    fun pathSegments(path: String?): List<String>? {
        val raw = path?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        if (raw.contains('\u0000')) return null
        val normalized = raw.replace('\\', '/')
        if (normalized.startsWith("/")) return null
        val segments = normalized.split('/')
        if (segments.any { it.isBlank() || it == ".." }) return null
        return segments
    }

    fun invalidPathMessage(path: String?): String =
        "Некорректный путь: «${path?.trim().orEmpty()}». Запрещены абсолютные пути, «..» и пустые сегменты."

    fun grantIdArg(arguments: JsonObject): Long? =
        (arguments["grant_id"] as? JsonPrimitive)?.content?.trim()?.toLongOrNull()

    /**
     * Walks a relative path from the tree root, one `findFile(name)` per segment. A blank path
     * resolves to the root. Returns null when the grant is missing, the path is rejected or any
     * segment is absent.
     */
    suspend fun resolveDocument(grantId: Long, path: String?): DocumentFile? {
        val root = treeRoot(grantId) ?: return null
        val segments = pathSegments(path) ?: return null
        var current = root
        for (segment in segments) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    /**
     * Creates any missing directory along [segments] and returns the final directory. Returns null
     * when the grant is missing, a non-directory blocks the way or `createDirectory` fails.
     */
    suspend fun ensureDirectory(grantId: Long, segments: List<String>): DocumentFile? {
        val root = treeRoot(grantId) ?: return null
        var current = root
        for (segment in segments) {
            val existing = current.findFile(segment)
            current = when {
                existing == null -> current.createDirectory(segment) ?: return null
                existing.isDirectory -> existing
                else -> return null
            }
        }
        return current
    }

    /**
     * Creates a sibling backup document for [target] inside [parent] and streams the current
     * content of [target] into it. A directory target yields a directory backup (there is no
     * content to copy); a file target yields a file backup with the same MIME type. Returns null
     * when no usable backup could be produced, deleting a half-written backup again so it is
     * never mistaken for a recoverable one.
     */
    fun createBackup(
        parent: DocumentFile,
        target: DocumentFile,
        name: String,
        mimeType: String,
    ): DocumentFile? {
        val backup = if (target.isDirectory) {
            parent.createDirectory("$name.bak")
        } else {
            parent.createFile(mimeType, "$name.bak")
        } ?: return null
        if (target.isDirectory) return backup
        if (copyDocumentContent(target.uri, backup.uri)) return backup
        runCatching { backup.delete() }
        return null
    }

    /**
     * Streams the whole document at [from] into the document at [to], truncating the destination
     * ("wt"). Returns true only when the copy ran through without an error.
     */
    fun copyDocumentContent(from: Uri, to: Uri): Boolean = try {
        val input = context.contentResolver.openInputStream(from) ?: return false
        input.use { source ->
            val output = context.contentResolver.openOutputStream(to, "wt")
                ?: return false
            output.use { sink ->
                source.copyTo(sink)
                sink.flush()
            }
        }
        true
    } catch (e: Exception) {
        false
    }
}
