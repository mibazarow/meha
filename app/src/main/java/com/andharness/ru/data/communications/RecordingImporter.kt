package com.andharness.ru.data.communications

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.andharness.ru.domain.communications.Recording
import com.andharness.ru.domain.communications.RecordingSourceType
import com.andharness.ru.domain.communications.RecordingsRepository
import com.andharness.ru.domain.communications.TranscriptStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Honest outcome of one import: the `recordings` row that now tracks the audio, whether it was
 * transcribed in the same call, how many segments the transcript produced (`null` when not
 * transcribed) and the display name that was registered.
 */
data class ImportOutcome(
    val recordingId: Long,
    val transcribed: Boolean,
    val segmentCount: Long?,
    val displayName: String?,
)

/**
 * Registers one specific audio file (or `content://` URI) the user or the agent pointed at.
 *
 * Accepts either an absolute file path or a SAF/media `content://` URI. A URI is materialised once
 * into the app's own storage (`<externalFilesDir>/recordings`) through the content resolver, so the
 * normal file-based transcription pipeline can read it and the bytes survive the provider grant.
 * Reading is bounded ([MAX_IMPORT_BYTES]) and the source is validated before a row is created, so a
 * failure (missing file, revoked grant, unreadable stream, oversized file) is reported honestly and
 * never faked.
 *
 * When [import]'s `transcribe` is `true` the audio goes straight through
 * [TranscriptionService.importAndTranscribe] (which creates the row and runs whisper.cpp); otherwise
 * the importer registers a `PENDING` row itself and leaves transcription to a later call.
 *
 * References (developer.android.com):
 * - SAF / `content://` access on a stream —
 *   https://developer.android.com/guide/topics/providers/document-provider
 * - `ContentResolver.openInputStream` —
 *   https://developer.android.com/reference/android/content/ContentResolver#openInputStream(android.net.Uri)
 * - `OpenableColumns.DISPLAY_NAME` —
 *   https://developer.android.com/reference/android/provider/OpenableColumns#DISPLAY_NAME
 * - `Context.getExternalFilesDir` —
 *   https://developer.android.com/reference/android/content/Context#getExternalFilesDir(java.lang.String)
 */
@Singleton
class RecordingImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recordingsRepository: RecordingsRepository,
    private val transcriptionService: TranscriptionService,
) {

    /**
     * Imports [pathOrUri] (absolute path or `content://` URI) as a `Recording`.
     *
     * @param transcribe when `true`, immediately runs the whisper.cpp pipeline (the speech model
     *   must already be downloaded — the pipeline never downloads one).
     * @param language recognition language code, or `auto` for detection.
     * @param sourceType how the recording should be tagged; callers capturing another app's audio
     *   pass [RecordingSourceType.MESSENGER_VOICE], the generic import keeps the default
     *   [RecordingSourceType.IMPORTED].
     */
    suspend fun import(
        pathOrUri: String,
        transcribe: Boolean,
        language: String,
        sourceType: RecordingSourceType = RecordingSourceType.IMPORTED,
    ): Result<ImportOutcome> = withContext(Dispatchers.IO) {
        val source = pathOrUri.trim()
        if (source.isEmpty()) {
            return@withContext failure("Путь или URI не указан.")
        }
        val resolved = try {
            if (isContentUri(source)) materializeContentUri(source) else resolveFile(source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext failure(
                e.message?.takeIf { it.isNotBlank() } ?: "Не удалось открыть аудиофайл."
            )
        }

        if (transcribe) {
            transcriptionService.importAndTranscribe(
                file = resolved.file,
                sourceType = sourceType,
                displayName = resolved.displayName,
                personId = null,
                language = normalizedLanguage(language),
            ).fold(
                onSuccess = { outcome ->
                    Result.success(
                        ImportOutcome(
                            recordingId = outcome.recordingId,
                            transcribed = true,
                            segmentCount = outcome.segmentCount,
                            displayName = resolved.displayName,
                        )
                    )
                },
                onFailure = { error -> Result.failure(error) },
            )
        } else {
            val now = System.currentTimeMillis()
            val recording = Recording(
                id = 0,
                sourceType = sourceType,
                filePath = resolved.file.absolutePath,
                displayName = resolved.displayName,
                durationMs = 0L,
                sizeBytes = resolved.file.length(),
                sourcePackage = null,
                transcriptStatus = TranscriptStatus.PENDING,
                keepFlag = false,
                personId = null,
                errorText = null,
                createdAt = now,
                updatedAt = now,
            )
            val id = recordingsRepository.insert(recording)
            Result.success(
                ImportOutcome(
                    recordingId = id,
                    transcribed = false,
                    segmentCount = null,
                    displayName = resolved.displayName,
                )
            )
        }
    }

    /** A readable absolute file path becomes the recording's source as-is. */
    private fun resolveFile(path: String): ResolvedAudio {
        val file = File(path)
        if (!file.isFile || !file.canRead()) {
            throw IllegalArgumentException("Аудиофайл не найден или недоступен для чтения: $path")
        }
        return ResolvedAudio(file = file, displayName = file.name.takeIf { it.isNotBlank() })
    }

    /** Copies a `content://` URI into app storage so the file-based pipeline can read it later. */
    private fun materializeContentUri(uriString: String): ResolvedAudio {
        val uri = Uri.parse(uriString)
        val displayName = queryDisplayName(uri)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Не удалось открыть URI: $uriString")
        val target = uniqueTarget(importDir(), displayName ?: "import-${System.currentTimeMillis()}.bin")
        try {
            val bytes = copyBounded(input, target)
            if (bytes <= 0L) {
                throw IllegalArgumentException("Источник пустой: $uriString")
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        return ResolvedAudio(file = target, displayName = displayName ?: target.name)
    }

    /** Reads `OpenableColumns.DISPLAY_NAME` for [uri]; best-effort, `null` when unavailable. */
    private fun queryDisplayName(uri: Uri): String? = try {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                cursor.getString(index)?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Не удалось прочитать имя по URI", e)
        null
    }

    /** App-owned directory for materialised imports; never requires a storage permission. */
    private fun importDir(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, IMPORT_DIR).apply { mkdirs() }

    /** A non-colliding destination inside [dir] derived from [name]. */
    private fun uniqueTarget(dir: File, name: String): File {
        val base = safeImportFileName(name) ?: "import-${System.currentTimeMillis()}"
        val first = File(dir, base)
        if (!first.exists()) return first
        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0) base.substring(0, dot) else base
        val extension = if (dot > 0) base.substring(dot) else ""
        for (index in 1..MAX_UNIQUE_ATTEMPTS) {
            val candidate = File(dir, "$stem-$index$extension")
            if (!candidate.exists()) return candidate
        }
        return File(dir, "$stem-${System.currentTimeMillis()}$extension")
    }

    /**
     * Streams [input] into [target] and closes both, refusing to write more than [MAX_IMPORT_BYTES].
     * Returns the number of bytes written.
     */
    private fun copyBounded(input: InputStream, target: File): Long {
        var total = 0L
        input.use { source ->
            FileOutputStream(target).use { sink ->
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_IMPORT_BYTES) {
                        throw IOException(
                            "Файл слишком большой (максимум ${MAX_IMPORT_BYTES / MEGABYTE} МБ)."
                        )
                    }
                    sink.write(buffer, 0, read)
                }
                sink.flush()
            }
        }
        return total
    }

    private fun normalizedLanguage(language: String): String =
        language.trim().takeIf { it.isNotEmpty() } ?: LANGUAGE_AUTO

    private fun failure(message: String): Result<ImportOutcome> =
        Result.failure(IllegalArgumentException(message))

    /** A readable audio source plus the name to register. */
    private data class ResolvedAudio(val file: File, val displayName: String?)

    private companion object {
        const val TAG = "Andharness"
        const val IMPORT_DIR = "recordings"
        const val LANGUAGE_AUTO = "auto"
        const val COPY_BUFFER = 64 * 1024
        const val MAX_UNIQUE_ATTEMPTS = 100
        const val MEGABYTE = 1024L * 1024L

        /** Upper bound for a materialised import, so a huge URI cannot fill app storage. */
        const val MAX_IMPORT_BYTES = 2L * 1024L * MEGABYTE
    }
}

/**
 * Makes [name] safe to use as a file name (drops directory separators, control characters and
 * leading dots, caps the length); `null` when nothing usable remains. Pure and JVM-testable.
 */
fun safeImportFileName(name: String?): String? {
    val cleaned = name
        ?.trim()
        ?.replace('\\', '_')
        ?.replace('/', '_')
        ?.filter { !it.isISOControl() }
        ?.trimStart('.')
        ?.take(MAX_FILE_NAME_CHARS)
        ?.trim()
    return cleaned?.takeIf { it.isNotEmpty() }
}

private const val MAX_FILE_NAME_CHARS = 120
