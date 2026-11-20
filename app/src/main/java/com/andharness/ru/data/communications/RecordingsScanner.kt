package com.andharness.ru.data.communications

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.andharness.ru.data.util.columnIndex
import com.andharness.ru.data.util.getLongOrNull
import com.andharness.ru.data.util.getStringOrNull
import com.andharness.ru.data.util.useRowsWhile
import com.andharness.ru.domain.communications.Recording
import com.andharness.ru.domain.communications.RecordingSourceType
import com.andharness.ru.domain.communications.RecordingsRepository
import com.andharness.ru.domain.communications.TranscriptStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Honest counters of one recordings scan: how many candidate audio rows were examined ([found]),
 * how many became new `recordings` rows ([added]) and how many were left alone because they were
 * already registered or unreadable ([skipped]). The invariant `found == added + skipped` always
 * holds, so a caller can see at a glance that nothing was silently dropped.
 */
data class ScanResult(val found: Int, val added: Int, val skipped: Int)

/**
 * Discovers audio files the device already holds and registers them as `Recording`s.
 *
 * Every row comes from [MediaStore.Audio.Media] (external audio volume), which requires the runtime
 * [Manifest.permission.READ_MEDIA_AUDIO] permission on Android 13+ (`READ_EXTERNAL_STORAGE` on older
 * releases; the platform throws/returns nothing without it). The scan:
 * - is **bounded**: at most [scan]'s `limit` rows per run, never the whole media library;
 * - is **honest**: a missing permission is a failure, an unreadable row is skipped and the returned
 *   [ScanResult] adds up (`found == added + skipped`);
 * - **never crashes** on a bad row — per-row parsing is guarded and a malformed entry is skipped;
 * - **deduplicates** against already-registered recordings by a stable key (media content URI +
 *   size, with the absolute path as a fallback) so repeated scans do not duplicate rows;
 * - honours the configured [CommunicationsConfigStore.currentRecordingsFolders]: when the set is
 *   non-empty, only files inside those folders are considered, otherwise every audio row is.
 *
 * References (developer.android.com):
 * - `MediaStore.Audio` — https://developer.android.com/reference/android/provider/MediaStore.Audio
 * - `MediaStore.Audio.Media` (columns `_ID`, `DISPLAY_NAME`, `DURATION`, `SIZE`, `MIME_TYPE`,
 *   `DATA`) — https://developer.android.com/reference/android/provider/MediaStore.Audio.Media
 * - `ContentResolver.query` —
 *   https://developer.android.com/reference/android/content/ContentResolver#query(android.net.Uri,%20java.lang.String%5B%5D,%20java.lang.String,%20java.lang.String%5B%5D,%20java.lang.String)
 * - Media permissions (`READ_MEDIA_AUDIO`) —
 *   https://developer.android.com/training/data-storage/shared/media#media_permissions
 */
@Singleton
class RecordingsScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recordingsRepository: RecordingsRepository,
    private val configStore: CommunicationsConfigStore,
) {

    /**
     * Enumerates at most [limit] eligible audio rows and inserts the ones that are not registered
     * yet. Returns [Result.failure] with a Russian, user-facing reason when the audio permission is
     * absent or the provider could not be read; per-row problems never fail the whole scan.
     */
    suspend fun scan(limit: Int = DEFAULT_SCAN_LIMIT): Result<ScanResult> =
        withContext(Dispatchers.IO) {
            if (!hasAudioPermission()) {
                return@withContext Result.failure(
                    SecurityException(
                        "Нет разрешения READ_MEDIA_AUDIO: доступ к аудиофайлам устройства не " +
                            "предоставлен."
                    )
                )
            }
            try {
                val max = limit.coerceIn(1, MAX_SCAN_LIMIT)
                val folders = configStore.currentRecordingsFolders()
                val candidates = queryAudioCandidates(folders, max)
                val registered = registeredKeys()

                val now = System.currentTimeMillis()
                var added = 0
                var skipped = 0
                for (candidate in candidates) {
                    if (candidate.dedupKeys.any { it in registered }) {
                        skipped++
                        continue
                    }
                    try {
                        recordingsRepository.insert(candidate.toRecording(now))
                        added++
                    } catch (e: Exception) {
                        Log.w(TAG, "Не удалось зарегистрировать аудио «${candidate.filePath}»", e)
                        skipped++
                    }
                }
                Result.success(ScanResult(found = candidates.size, added = added, skipped = skipped))
            } catch (e: Exception) {
                Log.e(TAG, "Скан записей не удался", e)
                Result.failure(e)
            }
        }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Reads at most [max] audio rows (folder-filtered) from [MediaStore.Audio.Media]. */
    private fun queryAudioCandidates(folders: Set<String>, max: Int): List<AudioCandidate> {
        val out = ArrayList<AudioCandidate>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATA,
        )
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC",
        )?.useRowsWhile { cursor ->
            if (out.size >= max) return@useRowsWhile false
            val idIndex = cursor.columnIndex(MediaStore.Audio.Media._ID)
            try {
                if (idIndex < 0) return@useRowsWhile true
                val id = cursor.getLong(idIndex)
                val name = cursor.getStringOrNull(cursor.columnIndex(MediaStore.Audio.Media.DISPLAY_NAME))
                val mime = cursor.getStringOrNull(cursor.columnIndex(MediaStore.Audio.Media.MIME_TYPE))
                if (!looksLikeAudio(name, mime)) return@useRowsWhile true
                val data = cursor.getStringOrNull(cursor.columnIndex(MediaStore.Audio.Media.DATA))
                if (!matchesRecordingsFolder(data, folders)) return@useRowsWhile true
                val size = cursor.getLongOrNull(cursor.columnIndex(MediaStore.Audio.Media.SIZE)) ?: 0L
                val duration = cursor.getLongOrNull(cursor.columnIndex(MediaStore.Audio.Media.DURATION)) ?: 0L
                val uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    id,
                ).toString()
                out += AudioCandidate(
                    filePath = uri,
                    displayName = name?.takeIf { it.isNotBlank() },
                    durationMs = duration.coerceAtLeast(0L),
                    sizeBytes = size.coerceAtLeast(0L),
                    dedupKeys = candidateKeys(uri, data, size),
                )
            } catch (e: Exception) {
                // A single malformed row must never abort the scan; it is simply left out.
                Log.w(TAG, "Пропущена некорректная строка MediaStore.Audio", e)
            }
            true
        }
        return out
    }

    /** Stable keys of every already-registered recording, so a rescan cannot duplicate a row. */
    private suspend fun registeredKeys(): Set<String> {
        val keys = HashSet<String>()
        for (recording in recordingsRepository.list(limit = EXISTING_LIMIT)) {
            keys += recordingDedupKey(recording.filePath, recording.sizeBytes)
        }
        return keys
    }

    private data class AudioCandidate(
        val filePath: String,
        val displayName: String?,
        val durationMs: Long,
        val sizeBytes: Long,
        val dedupKeys: Set<String>,
    ) {
        fun toRecording(now: Long): Recording = Recording(
            id = 0,
            sourceType = RecordingSourceType.IMPORTED,
            filePath = filePath,
            displayName = displayName,
            durationMs = durationMs,
            sizeBytes = sizeBytes,
            sourcePackage = null,
            transcriptStatus = TranscriptStatus.PENDING,
            keepFlag = false,
            personId = null,
            errorText = null,
            createdAt = now,
            updatedAt = now,
        )
    }

    private companion object {
        const val TAG = "Andharness"

        /** Rows examined per scan when the caller does not specify a limit. */
        const val DEFAULT_SCAN_LIMIT = 200

        /** Hard cap so one scan can never walk an unbounded media library. */
        const val MAX_SCAN_LIMIT = 1000

        /** How many registered rows are loaded to build the dedup set. */
        const val EXISTING_LIMIT = 5000
    }
}

/** `true` when [source] is a `content://` URI (so it must be opened through the provider). */
fun isContentUri(source: String): Boolean =
    source.trim().replace('\\', '/').startsWith("content://", ignoreCase = true)

/**
 * Stable dedup key for a recording source. A `content://` URI identifies a media row, so the key
 * also carries [sizeBytes] (an `_ID` alone can be reused after a media re-index); a plain file path
 * is itself the identity, so it is used alone as the fallback. Normalises separators and case so the
 * same file discovered through another route still matches. Pure and JVM-testable.
 */
fun recordingDedupKey(source: String, sizeBytes: Long): String {
    val normalized = source.trim().replace('\\', '/').lowercase(Locale.ROOT)
    return if (normalized.startsWith("content://")) {
        "uri:$normalized|${sizeBytes.coerceAtLeast(0L)}"
    } else {
        "path:$normalized"
    }
}

/** Keys a MediaStore row can be matched by: its content URI (with size) and its path (fallback). */
private fun candidateKeys(uri: String, path: String?, sizeBytes: Long): Set<String> {
    val keys = LinkedHashSet<String>(2)
    keys += recordingDedupKey(uri, sizeBytes)
    if (!path.isNullOrBlank()) keys += recordingDedupKey(path, sizeBytes)
    return keys
}

/** Extensions the scanner treats as audio when a row carries no usable MIME type. */
private val AUDIO_EXTENSIONS = setOf(
    "mp3", "m4a", "m4b", "aac", "wav", "wave", "ogg", "oga", "opus", "flac",
    "amr", "3gp", "3gpp", "wma", "mka", "aiff", "aif", "aifc", "caf", "mp4a",
)

/** `true` when [mimeType] names an audio stream (`audio/...`). Pure and JVM-testable. */
fun isAudioMimeType(mimeType: String?): Boolean =
    mimeType?.trim()?.lowercase(Locale.ROOT)?.startsWith("audio/") == true

/** Lower-case extension of [name] without the dot, or `null` when there is none. JVM-testable. */
fun audioExtensionOf(name: String?): String? {
    val trimmed = name?.trim() ?: return null
    val dot = trimmed.lastIndexOf('.')
    if (dot < 0 || dot == trimmed.lastIndex) return null
    return trimmed.substring(dot + 1).lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }
}

/**
 * `true` when a MediaStore row should be treated as audio: an `audio/…` MIME type, or (in case the
 * MIME column is empty) a known audio extension. Pure and JVM-testable.
 */
fun looksLikeAudio(displayName: String?, mimeType: String?): Boolean {
    if (isAudioMimeType(mimeType)) return true
    val extension = audioExtensionOf(displayName) ?: return false
    return AUDIO_EXTENSIONS.contains(extension)
}

/**
 * `true` when [path] lies in one of the configured recordings [folders]. An empty set means "no
 * folder filter" and accepts everything. A configured folder matches when it equals the file's
 * parent directory or is a trailing path segment of it, so both `Recordings` and
 * `/storage/emulated/0/Recordings` work. A missing path cannot be checked against a non-empty
 * filter and is therefore rejected. Pure and JVM-testable.
 */
fun matchesRecordingsFolder(path: String?, folders: Set<String>): Boolean {
    if (folders.isEmpty()) return true
    val parent = path
        ?.trim()
        ?.replace('\\', '/')
        ?.substringBeforeLast('/', "")
        ?.trim('/')
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it.isNotEmpty() }
        ?: return false
    return folders.any { raw ->
        val folder = raw.trim().replace('\\', '/').trim('/').lowercase(Locale.ROOT)
        if (folder.isEmpty()) return@any false
        parent == folder ||
            parent.startsWith("$folder/") ||
            parent.endsWith("/$folder") ||
            parent.contains("/$folder/")
    }
}
