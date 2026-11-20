package com.andharness.ru.data.communications

import android.util.Log
import com.andharness.ru.data.communications.audio.WavReader
import com.andharness.ru.data.communications.audio.sliceMs
import com.andharness.ru.data.communications.speech.SpeakerEmbedder
import com.andharness.ru.data.communications.speech.SpeechModelStore
import com.andharness.ru.domain.communications.Speaker
import com.andharness.ru.domain.communications.SpeakerRepository
import com.andharness.ru.domain.communications.SpeakerVoiceEmbedding
import com.andharness.ru.domain.communications.TranscriptRepository
import com.andharness.ru.domain.communications.diarization.DiarizationPlanner
import com.andharness.ru.domain.communications.diarization.VoiceClusterer
import com.andharness.ru.domain.communications.speech.SpeechModel
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Post-transcription speaker diarisation: labels a recording's [TranscriptSegment]s with speakers
 * using per-segment voice embeddings, clustering and the persistent speaker registry, and updates
 * that registry (centroids + raw samples) as it goes.
 *
 * Pipeline (all on [Dispatchers.IO]):
 * 1. honest no-op (`Result.success(0)`) when `diarizationEnabled` is off, the
 *    [SpeechModel.SPEAKER_EMB] model is not downloaded, the WAV is missing, or fewer than
 *    [MIN_EMBEDDINGS] segments yielded a usable embedding;
 * 2. read the temporary WAV ([WavReader]) and embed each segment's PCM slice
 *    ([SpeakerEmbedder.embed]) — blank text and slices shorter than ~[MIN_SEGMENT_MS] ms are
 *    skipped, and a single failed embedding is skipped too (never aborts the pass);
 * 3. [VoiceClusterer.cluster] the embeddings, [VoiceClusterer.matchToRegistry] them against the
 *    registry's centroids, and let [DiarizationPlanner] decide each cluster's update;
 * 4. persist: matched speakers get a count-weighted centroid update ([SpeakerRepository.setCentroidAndCount]),
 *    new clusters become unnamed `isSelf=false` speakers, every cluster's embeddings are stored
 *    ([SpeakerRepository.addEmbedding]) and its segments are attributed
 *    ([TranscriptRepository.assignSpeaker]).
 *
 * Best-effort contract: this never throws for ordinary failures (coroutine cancellation is
 * rethrown). A per-cluster/per-segment failure is logged and skipped, and any unexpected error comes
 * back as a `Result.failure`, so a diarisation problem can never change the transcription result —
 * segments simply keep `speakerId=null`.
 *
 * References (developer.android.com):
 * - `Dispatchers.IO` for the file/DB work — https://developer.android.com/kotlin/coroutines
 * - Room suspend DAO access — https://developer.android.com/training/data-storage/room/accessing-data
 */
@Singleton
class DiarizationService @Inject constructor(
    private val config: CommunicationsConfigStore,
    private val speechModelStore: SpeechModelStore,
    private val speakerEmbedder: SpeakerEmbedder,
    private val speakerRepository: SpeakerRepository,
    private val transcriptRepository: TranscriptRepository,
) {

    /**
     * Diarises the transcript of [recordingId] from the decoded [wav] file and returns the number of
     * distinct speakers found (cluster count), or `0` on an honest no-op. Never fails the caller's
     * transcription: ordinary errors are logged and returned as `Result.failure`.
     */
    suspend fun diarize(recordingId: Long, wav: File): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (!config.isDiarizationEnabled()) {
                return@withContext Result.success(0)
            }
            if (!speechModelStore.isDownloaded(SpeechModel.SPEAKER_EMB)) {
                return@withContext Result.success(0)
            }
            if (!wav.isFile) {
                return@withContext Result.success(0)
            }

            val audio = WavReader.read(wav)
            val segments = transcriptRepository.segmentsFor(recordingId)

            val vectors = LinkedHashMap<Long, FloatArray>()
            for (segment in segments) {
                if (segment.text.isBlank()) continue
                if (segment.endMs - segment.startMs < MIN_SEGMENT_MS) continue
                val samples = sliceMs(audio.samples, audio.sampleRate, segment.startMs, segment.endMs)
                if (samples.size < MIN_SLICE_SAMPLES) continue
                val vector = speakerEmbedder.embed(samples).getOrNull() ?: continue
                if (vector.isEmpty()) continue
                vectors[segment.id] = vector
            }

            // One utterance is not enough to establish a voice: registering a speaker from a single
            // segment would pollute the registry, so this stays an honest no-op.
            if (vectors.size < MIN_EMBEDDINGS) {
                return@withContext Result.success(0)
            }

            val items = vectors.map { VoiceClusterer.SegmentEmbedding(it.key, it.value) }
            val clusters = VoiceClusterer.cluster(items)
            val registry = speakerRepository.list().mapNotNull { speaker ->
                speaker.centroid?.takeIf { it.isNotEmpty() }?.let { centroid ->
                    VoiceClusterer.RegistrySpeaker(speaker.id, centroid, speaker.sampleCount)
                }
            }
            val assignments = VoiceClusterer.matchToRegistry(clusters, registry)
            val updates = DiarizationPlanner.plan(clusters, assignments, registry)

            val model = speakerEmbedder.modelId()
            val now = System.currentTimeMillis()
            for (update in updates) {
                runCatching { applyUpdate(recordingId, update, vectors, model, now) }
                    .onFailure { Log.w(TAG, "Диаризация: не удалось применить кластер.", it) }
            }

            Result.success(clusters.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Диаризация не выполнена (транскрипт сохранён).", e)
            Result.failure(e)
        }
    }

    /** Creates/updates one speaker and attributes its cluster's segments to it. */
    private suspend fun applyUpdate(
        recordingId: Long,
        update: DiarizationPlanner.SpeakerUpdate,
        vectors: Map<Long, FloatArray>,
        model: String,
        now: Long,
    ) {
        val speakerId = update.speakerId ?: createSpeaker(update, model, now)
        if (update.speakerId != null) {
            speakerRepository.setCentroidAndCount(
                speakerId = speakerId,
                centroid = update.centroid,
                model = model,
                dim = update.centroid.size,
                sampleCount = update.sampleCount,
            )
        }
        for (segmentId in update.segmentIds) {
            runCatching { transcriptRepository.assignSpeaker(segmentId, speakerId) }
                .onFailure { Log.w(TAG, "Диаризация: сегмент $segmentId не привязан.", it) }
            val vector = vectors[segmentId] ?: continue
            runCatching {
                speakerRepository.addEmbedding(
                    SpeakerVoiceEmbedding(
                        id = 0,
                        speakerId = speakerId,
                        recordingId = recordingId,
                        dim = vector.size,
                        vector = vector,
                        model = model,
                        quality = null,
                        createdAt = now,
                    )
                )
            }.onFailure { Log.w(TAG, "Диаризация: голосовой сэмпл не сохранён.", it) }
        }
    }

    /** Inserts a new unnamed speaker seeded with the cluster's centroid/count and returns its id. */
    private suspend fun createSpeaker(
        update: DiarizationPlanner.SpeakerUpdate,
        model: String,
        now: Long,
    ): Long = speakerRepository.upsert(
        Speaker(
            id = 0,
            name = null,
            isSelf = false,
            personId = null,
            voiceModel = model,
            voiceDim = update.centroid.size,
            centroid = update.centroid,
            sampleCount = update.sampleCount,
            confidence = null,
            createdAt = now,
            updatedAt = now,
        )
    )

    companion object {
        private const val TAG = "Andharness"

        /** Segments shorter than this are skipped: too little audio for a stable voiceprint. */
        const val MIN_SEGMENT_MS = 500L

        /** Minimum samples a slice must have to be embedded (mirrors [MIN_SEGMENT_MS] at 16 kHz). */
        private const val MIN_SLICE_SAMPLES = 8_000

        /** At least two embedded segments are needed before any speaker can be established. */
        private const val MIN_EMBEDDINGS = 2
    }
}
