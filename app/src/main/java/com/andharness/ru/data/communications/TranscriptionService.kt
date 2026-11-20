package com.andharness.ru.data.communications

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import com.andharness.ru.data.communications.audio.AudioDecoder
import com.andharness.ru.data.communications.speech.SpeechModelStore
import com.andharness.ru.data.communications.speech.WhisperSegment
import com.andharness.ru.data.communications.speech.WhisperTranscriber
import com.andharness.ru.data.embedding.LocalEmbedder
import com.andharness.ru.domain.communications.Recording
import com.andharness.ru.domain.communications.RecordingSourceType
import com.andharness.ru.domain.communications.RecordingsRepository
import com.andharness.ru.domain.communications.TranscriptRepository
import com.andharness.ru.domain.communications.TranscriptSegment
import com.andharness.ru.domain.communications.TranscriptStatus
import com.andharness.ru.domain.communications.speech.SpeechModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Result of one end-to-end transcription run.
 *
 * @param recordingId the `recordings` row that was transcribed.
 * @param segmentCount number of persisted [TranscriptSegment]s (may be `0` for silent audio).
 * @param durationMs exact duration of the decoded 16 kHz mono audio.
 * @param language the requested language code, or `null` when the run asked for auto-detection
 *   (the recogniser's detected language is not surfaced by [WhisperTranscriber]).
 * @param speakerCount distinct speakers diarisation attributed to the segments (`0` when diarisation
 *   was disabled, skipped, or found nothing; it never affects the transcription result).
 */
data class TranscriptionOutcome(
    val recordingId: Long,
    val segmentCount: Long,
    val durationMs: Long,
    val language: String?,
    val speakerCount: Int = 0,
)

/**
 * Orchestrates the recording → transcript pipeline: decode the stored/imported audio to a temporary
 * 16 kHz mono WAV, run the bundled whisper.cpp engine, replace the recording's segments with the
 * normalised result, index each segment's text in the dense vector store, and attribute each segment
 * to a speaker via [DiarizationService] (best effort).
 *
 * The stages are the existing, independently tested building blocks and are only *composed* here:
 * [AudioDecoder] (demux/decode via `MediaCodec`), [WhisperTranscriber] (`whisper-cli`), the
 * `Communications` repositories, and [LocalEmbedder] (ONNX Runtime). Nothing in this class downloads
 * models — a missing speech model is reported honestly and left for the download flow to fix.
 *
 * Contract:
 * - every blocking stage runs on [Dispatchers.IO]; coroutine cancellation is rethrown;
 * - failures are honest: they are returned as [Result.failure] with a user-facing Russian message AND
 *   persisted on the row as [TranscriptStatus.FAILED] + `errorText` (best effort);
 * - embedding is best-effort: a segment-index failure is logged but never fails the transcription,
 *   which is still reported as [TranscriptStatus.DONE] (the segments are the durable output);
 * - diarisation ([DiarizationService]) is best-effort too: it runs while the temp WAV exists and any
 *   problem leaves `speakerId=null` without changing the [TranscriptStatus.DONE] result;
 * - the temporary WAV is always deleted in `finally`, so cache space cannot leak.
 *
 * References (developer.android.com):
 * - `Context.getCacheDir()` for the bounded temp WAV —
 *   https://developer.android.com/reference/android/content/Context#getCacheDir()
 * - `MediaMetadataRetriever` / `METADATA_KEY_DURATION` for the import pre-fill —
 *   https://developer.android.com/reference/android/media/MediaMetadataRetriever
 * - `Dispatchers.IO` — https://developer.android.com/kotlin/coroutines
 */
@Singleton
class TranscriptionService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recordingsRepository: RecordingsRepository,
    private val transcriptRepository: TranscriptRepository,
    private val speechModelStore: SpeechModelStore,
    private val transcriber: WhisperTranscriber,
    private val embedder: LocalEmbedder,
    private val diarizationService: DiarizationService,
) {

    /**
     * Transcribes the recording [recordingId] with [model] and returns its [TranscriptionOutcome].
     *
     * Steps: load the row and confirm the audio file exists → require the model file (no download) →
     * mark [TranscriptStatus.RUNNING] → decode to a unique temp WAV → run whisper (with the Silero VAD
     * if it is downloaded) → replace segments → embed their texts → diarise the segments (best effort)
     * → mark [TranscriptStatus.DONE].
     * Any failure sets [TranscriptStatus.FAILED] with an honest reason; the function never throws
     * (except coroutine cancellation).
     */
    suspend fun transcribeRecording(
        recordingId: Long,
        model: SpeechModel = SpeechModel.DEFAULT,
        language: String = "auto",
    ): Result<TranscriptionOutcome> = withContext(Dispatchers.IO) {
        var tempWav: File? = null
        try {
            val recording = recordingsRepository.getById(recordingId)
                ?: return@withContext fail(recordingId, "Запись не найдена: id=$recordingId")

            val input = File(recording.filePath)
            if (!input.isFile) {
                return@withContext fail(recordingId, "Аудиофайл не найден: ${recording.filePath}")
            }

            // The caller (worker/UI) is responsible for downloading; here we only require it.
            if (!speechModelStore.isDownloaded(model)) {
                return@withContext fail(recordingId, "модель не скачана: ${model.displayName}")
            }

            runCatching { recordingsRepository.updateStatus(recordingId, TranscriptStatus.RUNNING) }

            val workDir = File(context.cacheDir, WORK_DIR).apply { mkdirs() }
            val wav = File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, workDir)
            tempWav = wav

            val decoded = AudioDecoder.decodeToWav16kMono(input, wav).getOrElse { error ->
                throw IllegalStateException(
                    error.message?.takeIf { it.isNotBlank() } ?: "Не удалось декодировать аудио.",
                    error,
                )
            }

            val vadModel = speechModelStore.targetFile(SpeechModel.VAD_SILERO)
                .takeIf { speechModelStore.isDownloaded(SpeechModel.VAD_SILERO) }

            val rawSegments = transcriber.transcribe(
                wav = decoded.outputFile,
                model = speechModelStore.targetFile(model),
                vadModel = vadModel,
                language = language,
            ).getOrElse { error ->
                throw IllegalStateException(
                    error.message?.takeIf { it.isNotBlank() } ?: "Распознавание речи не удалось.",
                    error,
                )
            }

            val segments = normalizeSegments(rawSegments)
            transcriptRepository.deleteFor(recordingId)
            val ids = transcriptRepository.insertSegments(
                recordingId,
                segments.map { it.toTranscriptSegment(recordingId) },
            )
            embedSegments(ids, segments.map { it.text })

            // Diarisation runs while the temp WAV still exists (it is deleted in `finally`). It is
            // best-effort and cannot throw into the pipeline: a failure just leaves speakerId=null.
            val speakerCount = runCatching { diarizationService.diarize(recordingId, wav) }
                .getOrNull()
                ?.getOrNull()
                ?: 0

            recordingsRepository.updateStatus(recordingId, TranscriptStatus.DONE)
            Result.success(
                TranscriptionOutcome(
                    recordingId = recordingId,
                    segmentCount = ids.size.toLong(),
                    durationMs = decoded.durationMs,
                    language = requestedLanguage(language),
                    speakerCount = speakerCount,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val message = e.message?.takeIf { it.isNotBlank() } ?: "Не удалось распознать запись."
            fail(recordingId, message, e)
        } finally {
            tempWav?.delete()
        }
    }

    /**
     * Creates a [Recording] row for [file] and immediately transcribes it. Size is taken from the
     * file; duration is pre-filled best-effort via [MediaMetadataRetriever] (`0` when unreadable) and
     * the exact duration is available in the returned [TranscriptionOutcome].
     */
    suspend fun importAndTranscribe(
        file: File,
        sourceType: RecordingSourceType,
        displayName: String?,
        personId: Long?,
        model: SpeechModel = SpeechModel.DEFAULT,
        language: String = "auto",
    ): Result<TranscriptionOutcome> = withContext(Dispatchers.IO) {
        if (!file.isFile) {
            return@withContext Result.failure(
                IllegalStateException("Аудиофайл не найден: ${file.absolutePath}")
            )
        }
        val now = System.currentTimeMillis()
        val recording = Recording(
            id = 0,
            sourceType = sourceType,
            filePath = file.absolutePath,
            displayName = displayName,
            durationMs = probeDurationMs(file),
            sizeBytes = file.length(),
            sourcePackage = null,
            transcriptStatus = TranscriptStatus.PENDING,
            keepFlag = false,
            personId = personId,
            errorText = null,
            createdAt = now,
            updatedAt = now,
        )
        val recordingId = recordingsRepository.insert(recording)
        transcribeRecording(recordingId, model, language)
    }

    /**
     * Persists [errorText] on the row (best effort: an unknown id changes nothing) and returns the
     * honest failure to the caller.
     */
    private suspend fun fail(
        recordingId: Long,
        message: String,
        cause: Throwable? = null,
    ): Result<TranscriptionOutcome> {
        runCatching {
            recordingsRepository.updateStatus(recordingId, TranscriptStatus.FAILED, message)
        }
        return Result.failure(IllegalStateException(message, cause))
    }

    /**
     * Embeds the segment [texts] in bounded batches and stores one vector per segment id. Any failure
     * (missing ONNX asset, OOM, ORT error) is logged and swallowed: the transcript is already the
     * durable result, so indexing must never turn a successful transcription into a failure.
     */
    private suspend fun embedSegments(segmentIds: List<Long>, texts: List<String>) {
        if (segmentIds.isEmpty() || segmentIds.size != texts.size) return
        try {
            val idBatches = segmentIds.asBatches(EMBED_BATCH_SIZE)
            val textBatches = texts.asBatches(EMBED_BATCH_SIZE)
            for (index in idBatches.indices) {
                val vectors = embedder.embed(textBatches[index]).getOrElse { throw it }
                idBatches[index].forEachIndexed { offset, segmentId ->
                    val vector = vectors.getOrNull(offset) ?: return@forEachIndexed
                    transcriptRepository.putVector(segmentId, vector, LocalEmbedder.MODEL_ID)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Эмбеддинги сегментов не сохранены (транскрипт сохранён).", e)
        }
    }

    /** Best-effort media duration in milliseconds; `0` when it cannot be read. */
    private fun probeDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось определить длительность: ${file.name}", e)
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** The requested language code, or `null` for auto-detection (the detected one is unknown here). */
    private fun requestedLanguage(language: String): String? =
        language.trim().takeIf { it.isNotEmpty() && !it.equals(LANGUAGE_AUTO, ignoreCase = true) }

    private fun WhisperSegment.toTranscriptSegment(recordingId: Long): TranscriptSegment =
        TranscriptSegment(
            id = 0,
            recordingId = recordingId,
            startMs = startMs,
            endMs = endMs,
            text = text,
            speakerId = null,
            confidence = null,
            createdAt = System.currentTimeMillis(),
        )

    private companion object {
        const val TAG = "Andharness"
        const val WORK_DIR = "transcription"
        const val TEMP_PREFIX = "transcribe-"
        const val TEMP_SUFFIX = ".wav"
        const val LANGUAGE_AUTO = "auto"

        /** Segments embedded per [LocalEmbedder.embed] call, bounding peak vector memory. */
        const val EMBED_BATCH_SIZE = 32
    }
}

/**
 * Normalises raw recogniser [raw] segments into the shape persisted as [TranscriptSegment]s:
 * trims the text, drops blank/whitespace-only entries, clamps negative timestamps to `0`, and
 * enforces `endMs >= startMs`. Order is preserved (whisper emits segments in playback order).
 *
 * Pure and Android-free so it is unit-testable on the JVM.
 */
fun normalizeSegments(raw: List<WhisperSegment>): List<WhisperSegment> {
    val out = ArrayList<WhisperSegment>(raw.size)
    for (segment in raw) {
        val text = segment.text.trim()
        if (text.isEmpty()) continue
        val start = segment.startMs.coerceAtLeast(0L)
        val end = segment.endMs.coerceAtLeast(start)
        out += WhisperSegment(startMs = start, endMs = end, text = text)
    }
    return out
}

/**
 * Splits this list into consecutive batches of at most [batchSize] elements. An empty list yields no
 * batches; a non-positive [batchSize] yields a single batch with every element (so a caller can never
 * lose data to a bad size). Pure and JVM-testable, used to bound the embedding work per ORT run.
 */
fun <T> List<T>.asBatches(batchSize: Int): List<List<T>> {
    if (isEmpty()) return emptyList()
    if (batchSize <= 0) return listOf(this)
    return chunked(batchSize)
}
