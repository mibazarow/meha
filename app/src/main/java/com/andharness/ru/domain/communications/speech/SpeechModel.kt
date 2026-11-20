package com.andharness.ru.domain.communications.speech

import com.andharness.ru.domain.formatting.ByteFormat
import com.andharness.ru.domain.localserver.FreeSpaceDecision

/**
 * The downloadable speech-recognition models: whisper.cpp GGML weights plus the Silero VAD model.
 *
 * These are the files `whisper-cli` (see `WhisperTranscriber`) loads via `-m`/`--model` and
 * `-vm`/`--vad-model`. They are large (base ~141 MiB, small ~465 MiB), so they are downloaded on
 * demand rather than bundled in the APK.
 *
 * The [DEFAULT] model is [WHISPER_BASE]: the smallest multilingual whisper that still gives usable
 * quality on a phone, and the one the UI should preselect. [WHISPER_SMALL] is the optional,
 * higher-quality/larger alternative; [VAD_SILERO] is the ~1 MiB Voice Activity Detection model used
 * together with a whisper model; [SPEAKER_EMB] is the ~6.4 MiB WeSpeaker speaker-embedding ONNX
 * consumed by `SpeakerEmbedder` (not a whisper model, so it is never passed to `whisper-cli`).
 *
 * Verified against Hugging Face / whisper.cpp on 2026-09-24:
 * - URLs and sizes via `curl -sIL` (HTTP 302 -> CDN HTTP 200, `accept-ranges: bytes`,
 *   `content-length` as pinned below), so resumable partial downloads work.
 * - `sha256` is the LFS object id from the Hugging Face API
 *   (`/api/models/<repo>/tree/main?expand=true`), i.e. the SHA-256 of the exact served file, so it
 *   can be verified after download.
 * - The VAD URL is the one used by whisper.cpp at the pinned revision
 *   `927cfce34f31707e17f2bff35c349632fb9e2c3a` in `models/download-vad-model.sh`
 *   (`src=https://huggingface.co/ggml-org/whisper-vad`, `pfx=resolve/main/ggml`,
 *   `models="silero-v5.1.2 silero-v6.2.0"`).
 *
 * This enum is deliberately pure JVM (no Android imports) so the download policy is unit-testable.
 */
enum class SpeechModel(
    /** Stable identifier persisted in prefs / passed to the worker as input data. */
    val id: String,
    /** Human-readable Russian model label shown in the UI. */
    val displayName: String,
    /** Base name of the downloaded file inside `<filesDir>/speech_models/`. */
    val fileName: String,
    /** Hugging Face resolve URL; redirects to a Range-capable CDN object. */
    val downloadUrl: String,
    /** Exact served size in bytes (`content-length`). */
    val approxBytes: Long,
    /** SHA-256 of the served file (Hugging Face LFS object id), lowercase hex. */
    val sha256: String,
    /** Whether this is a whisper transcription model or the Silero VAD model. */
    val kind: SpeechModelKind,
) {
    /**
     * whisper.cpp `ggml-base.bin`, 147 951 465 bytes (~141 MiB). The DEFAULT, multilingual.
     * https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin
     */
    WHISPER_BASE(
        id = "whisper_base",
        displayName = "Whisper base (многоязычная)",
        fileName = "ggml-base.bin",
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
        approxBytes = 147_951_465L,
        sha256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
        kind = SpeechModelKind.WHISPER,
    ),

    /**
     * whisper.cpp `ggml-small.bin`, 487 601 967 bytes (~465 MiB). Higher quality, optional/larger.
     * https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin
     */
    WHISPER_SMALL(
        id = "whisper_small",
        displayName = "Whisper small (многоязычная)",
        fileName = "ggml-small.bin",
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin",
        approxBytes = 487_601_967L,
        sha256 = "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b",
        kind = SpeechModelKind.WHISPER,
    ),

    /**
     * Silero VAD v6.2.0, `ggml-silero-v6.2.0.bin`, 885 098 bytes (~0.8 MiB).
     * https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v6.2.0.bin
     */
    VAD_SILERO(
        id = "vad_silero",
        displayName = "Silero VAD v6.2.0",
        fileName = "ggml-silero-v6.2.0.bin",
        downloadUrl = "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v6.2.0.bin",
        approxBytes = 885_098L,
        sha256 = "2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987",
        kind = SpeechModelKind.VAD,
    ),

    /**
     * WeSpeaker ResNet34-LM speaker embedding (ONNX, int8), 6 685 123 bytes (~6.4 MiB).
     *
     * This is the int8 export of `Wespeaker/wespeaker-voxceleb-resnet34-LM`; the graph takes an
     * 80-dim log-mel fbank (`input_features`, `[B, T, 80]`) and returns a 256-dim speaker embedding
     * (`last_hidden_state`, `[B, 256]`). Consumed by `SpeakerEmbedder` (ONNX Runtime Mobile), never
     * by `whisper-cli` — see [SpeechModelKind.SPEAKER_EMB].
     *
     * Verified against Hugging Face on 2026-09-24: size and `sha256` via the LFS tree API
     * (`/api/models/onnx-community/wespeaker-voxceleb-resnet34-LM/tree/main/onnx?expand=true`,
     * LFS `oid`), then confirmed by downloading the exact file (size 6685123, sha256 matches).
     * https://huggingface.co/onnx-community/wespeaker-voxceleb-resnet34-LM
     */
    SPEAKER_EMB(
        id = "speaker_emb_wespeaker_resnet34",
        displayName = "WeSpeaker ResNet34 (голосовой отпечаток)",
        fileName = "model_int8.onnx",
        downloadUrl = "https://huggingface.co/onnx-community/wespeaker-voxceleb-resnet34-LM/resolve/main/onnx/model_int8.onnx",
        approxBytes = 6_685_123L,
        sha256 = "6416fae0599c33bdaf7188e99695edc5fafde190a102c511c881245a109de4c3",
        kind = SpeechModelKind.SPEAKER_EMB,
    );

    companion object {

        /** The model the UI preselects: the smallest usable multilingual whisper. */
        val DEFAULT: SpeechModel = WHISPER_BASE

        private val BY_ID: Map<String, SpeechModel> = SpeechModel.entries.associateBy { it.id }

        /** Looks up a model by its stable [SpeechModel.id]; `null` for an unknown id. */
        fun fromId(id: String): SpeechModel? = BY_ID[id]

        /** Recommended free-space headroom as a fraction: at least 1.25x the download. */
        private const val FREE_SPACE_MARGIN_NUMERATOR = 5L
        private const val FREE_SPACE_MARGIN_DENOMINATOR = 4L

        /**
         * Classifies the free space on the target filesystem. Pure arithmetic:
         * `INSUFFICIENT` when [freeBytes] < [requiredBytes], `LOW` when it is below 1.25x, else `OK`.
         * A non-positive [requiredBytes] is always `OK` (nothing to download).
         */
        fun freeSpaceDecision(freeBytes: Long, requiredBytes: Long): FreeSpaceDecision {
            if (requiredBytes <= 0L) return FreeSpaceDecision.OK
            if (freeBytes < requiredBytes) return FreeSpaceDecision.INSUFFICIENT
            if (freeBytes < requiredWithMargin(requiredBytes)) return FreeSpaceDecision.LOW
            return FreeSpaceDecision.OK
        }

        /** The size that must be free to avoid the [FreeSpaceDecision.LOW] warning (download x 1.25). */
        fun requiredWithMargin(requiredBytes: Long): Long =
            requiredBytes * FREE_SPACE_MARGIN_NUMERATOR / FREE_SPACE_MARGIN_DENOMINATOR

        /**
         * Honest Russian explanation of a [FreeSpaceDecision]. For `LOW`/`INSUFFICIENT` it says how
         * many bytes are still missing, so the user can act instead of seeing a generic error.
         */
        fun freeSpaceMessage(
            decision: FreeSpaceDecision,
            freeBytes: Long,
            requiredBytes: Long,
        ): String = when (decision) {
            FreeSpaceDecision.OK ->
                "Достаточно свободного места для загрузки модели."

            FreeSpaceDecision.LOW ->
                "Свободного места мало: загрузка может не завершиться. " +
                    "Рекомендуется освободить ещё ~${humanBytes(missingBytes(freeBytes, requiredWithMargin(requiredBytes)))}."

            FreeSpaceDecision.INSUFFICIENT ->
                "Недостаточно свободного места для загрузки модели: " +
                    "нужно ещё ~${humanBytes(missingBytes(freeBytes, requiredBytes))}."
        }

        /**
         * Download completion in whole percent, clamped to `0..100`. A non-positive [totalBytes] means
         * the total is unknown, which is reported honestly as `0` rather than a guessed value.
         */
        fun progressPercent(downloadedBytes: Long, totalBytes: Long): Int {
            if (downloadedBytes <= 0L || totalBytes <= 0L) return 0
            val percent = downloadedBytes * 100L / totalBytes
            return percent.coerceIn(0L, 100L).toInt()
        }

        private fun missingBytes(freeBytes: Long, neededBytes: Long): Long =
            (neededBytes - freeBytes).coerceAtLeast(0L)

        /**
         * Compact Russian byte size for UI copy, e.g. `1,0 ГБ` or `141 МБ`. Delegates to the single
         * canonical implementation [ByteFormat.humanBytes].
         */
        fun humanBytes(bytes: Long): String = ByteFormat.humanBytes(bytes)
    }
}

/**
 * What a [SpeechModel] file actually is: a whisper transcription model, the Silero VAD model, or a
 * speaker-embedding ONNX model. Only [WHISPER] files are valid `whisper-cli -m` inputs; [VAD] is a
 * `-vm` companion; [SPEAKER_EMB] is run by ONNX Runtime, not whisper.cpp.
 */
enum class SpeechModelKind { WHISPER, VAD, SPEAKER_EMB }
