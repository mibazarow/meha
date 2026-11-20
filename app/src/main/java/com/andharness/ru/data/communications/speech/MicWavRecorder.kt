package com.andharness.ru.data.communications.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android-bound microphone recorder for chat dictation. It captures 16 kHz mono PCM16 from
 * [MediaRecorder.AudioSource.MIC] and hands a canonical WAV file to the caller on [stop], ready for
 * the bundled on-device whisper engine ([WhisperTranscriber]). Unlike the platform
 * `SpeechRecognizer` (used by the overlay quick-ask panel and the `speech_to_text` tool), this path
 * does **not** leave the device.
 *
 * Threading: [start] configures the [AudioRecord] and spawns one daemon reader thread that drains
 * shorts into an in-memory buffer. [stop] is a `suspend` function that runs on [Dispatchers.IO]:
 * it signals the reader, joins it (bounded), releases the recorder and writes the WAV. [cancel]
 * discards everything; [release] releases resources without deleting the last returned file.
 *
 * Failure is always honest: a missing [Manifest.permission.RECORD_AUDIO], a `getMinBufferSize`
 * error, a recorder that fails to initialize/start, and a recording shorter than
 * [MIN_DURATION_MS] all return `Result.failure` (the last one as [TooShortException]).
 *
 * References (developer.android.com):
 * - `AudioRecord` / `AudioRecord.Builder` / `getMinBufferSize` / `read(short[], int, int)` /
 *   `STATE_INITIALIZED` / `RECORDSTATE_RECORDING` —
 *   https://developer.android.com/reference/android/media/AudioRecord
 * - `AudioFormat` (`CHANNEL_IN_MONO`, `ENCODING_PCM_16BIT`) —
 *   https://developer.android.com/reference/android/media/AudioFormat
 * - `MediaRecorder.AudioSource.MIC` —
 *   https://developer.android.com/reference/android/media/MediaRecorder.AudioSource#MIC
 * - `RECORD_AUDIO` runtime permission —
 *   https://developer.android.com/reference/android/Manifest.permission#RECORD_AUDIO
 */
class MicWavRecorder(private val context: Context) {

    /** Thrown by [stop] when the capture is too short to transcribe (< ~300 ms). */
    class TooShortException : IllegalStateException("Запись слишком короткая.")

    @Volatile
    private var stopRequested = false

    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private var minBufferBytes = 0

    /** Captured little-endian PCM16 bytes (all channels interleaved; mono here). */
    private var pcm = ByteArrayOutputStream()

    /** The most recent file returned by [stop], deleted by [cancel]. */
    private var lastFile: File? = null

    /** True while [start] has an active [AudioRecord] configured. */
    val isRecording: Boolean
        get() = synchronized(this) { record != null }

    /**
     * Configures and starts the microphone. Returns an honest failure when the permission is
     * missing, `getMinBufferSize` fails, the recorder cannot be built/initialized, or recording
     * does not actually start.
     */
    @Synchronized
    fun start(): Result<Unit> {
        if (record != null) {
            return Result.failure(IllegalStateException("Запись уже идёт."))
        }
        if (!hasPermission()) {
            return Result.failure(
                SecurityException("Нет разрешения на микрофон — диктовка невозможна."),
            )
        }
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING)
        if (minBuffer <= 0) {
            return Result.failure(
                IllegalStateException("Микрофонный вход недоступен (getMinBufferSize = $minBuffer)."),
            )
        }
        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_MASK)
                        .build(),
                )
                .setBufferSizeInBytes(max(minBuffer * 2, SAMPLE_RATE * BYTES_PER_SAMPLE / 2))
                .build()
        } catch (t: Throwable) {
            return Result.failure(
                IllegalStateException("Не удалось создать микрофон: ${t.message}", t),
            )
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            return Result.failure(
                IllegalStateException("Не удалось инициализировать микрофон (возможно, он занят)."),
            )
        }
        return try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { rec.release() }
                return Result.failure(
                    IllegalStateException("Микрофон не начал запись (возможно, занят другим приложением)."),
                )
            }
            record = rec
            minBufferBytes = minBuffer
            pcm = ByteArrayOutputStream()
            stopRequested = false
            worker = Thread({ readLoop(rec) }, THREAD_NAME).apply {
                isDaemon = true
                start()
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            runCatching { rec.release() }
            Result.failure(IllegalStateException("Не удалось начать запись: ${t.message}", t))
        }
    }

    /** Drains the recorder into [pcm] until [stopRequested] or the [MAX_PCM_BYTES] cap is reached. */
    private fun readLoop(rec: AudioRecord) {
        val chunk = ShortArray(max(minBufferBytes / BYTES_PER_SAMPLE, MIN_CHUNK_SHORTS))
        try {
            while (!stopRequested && pcm.size().toLong() < MAX_PCM_BYTES) {
                val read = try {
                    rec.read(chunk, 0, chunk.size)
                } catch (t: Throwable) {
                    Log.w(TAG, "mic read failed", t)
                    -1
                }
                if (read < 0) {
                    // Negative values are AudioRecord.ERROR_* codes, not end-of-stream noise.
                    Log.w(TAG, "mic read error code=$read")
                    break
                }
                if (read == 0) {
                    // `read` legitimately returns 0 when no data is available yet; treat it as
                    // "nothing to drain", never as end-of-stream, or the capture comes back empty.
                    Thread.sleep(EMPTY_READ_SLEEP_MS)
                    continue
                }
                val bytes = ByteArray(read * BYTES_PER_SAMPLE)
                var i = 0
                for (s in 0 until read) {
                    val v = chunk[s].toInt()
                    bytes[i++] = (v and 0xFF).toByte()
                    bytes[i++] = ((v shr 8) and 0xFF).toByte()
                }
                // Always write: data read after stop() was requested is the final chunk and must
                // not be discarded (the loop condition already bounds the capture).
                synchronized(this) { pcm.write(bytes) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "mic read loop failed", t)
        }
    }

    /**
     * Stops the reader, releases the recorder and writes a canonical 16 kHz mono PCM16 WAV into
     * `cacheDir/mic_dictation/`. Returns [TooShortException] for a capture shorter than
     * [MIN_DURATION_MS]. Runs on [Dispatchers.IO].
     */
    suspend fun stop(): Result<File> = withContext(Dispatchers.IO) {
        val rec = synchronized(this) {
            if (record == null) {
                return@withContext Result.failure(IllegalStateException("Запись не идёт."))
            }
            record = null
            record
        }
        // Stop the recorder FIRST: it unblocks a blocking `read`, letting the reader drain what is
        // already buffered. Only then ask the loop to exit, so the final chunk is kept.
        runCatching { rec?.stop() }
        stopRequested = true
        worker?.let { runCatching { it.join(STOP_JOIN_MS) } }
        synchronized(this) { worker = null }
        runCatching { rec?.release() }

        val data = synchronized(this) { pcm.toByteArray() }
        Log.i(TAG, "mic dictation captured ${data.size} bytes (~${data.size * 1000 / (SAMPLE_RATE * BYTES_PER_SAMPLE)} ms)")
        if (data.size < minBytes()) {
            pcm.reset()
            return@withContext Result.failure(TooShortException())
        }
        val dir = File(context.cacheDir, DIR_NAME).apply { mkdirs() }
        val file = File(dir, "mic-${System.currentTimeMillis()}$SUFFIX")
        try {
            file.outputStream().use { it.write(Pcm16WavWriter.build(data)) }
        } catch (t: Throwable) {
            return@withContext Result.failure(
                IllegalStateException("Не удалось сохранить запись: ${t.message}", t),
            )
        }
        synchronized(this) { lastFile = file }
        Result.success(file)
    }

    /** Stops any active recording, releases the recorder and deletes the last returned file. */
    fun cancel() {
        release()
        val file = synchronized(this) {
            val f = lastFile
            lastFile = null
            f
        }
        runCatching { file?.delete() }
    }

    /** Releases the recorder (idempotent). The file last returned by [stop] is left for the caller. */
    fun release() {
        val (rec, thread) = synchronized(this) {
            val r = record
            val t = worker
            record = null
            worker = null
            stopRequested = true
            r to t
        }
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
        thread?.let { runCatching { it.join(CANCEL_JOIN_MS) } }
        synchronized(this) { pcm.reset() }
    }

    private fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun minBytes(): Int = SAMPLE_RATE * BYTES_PER_SAMPLE * MIN_DURATION_MS / 1000

    companion object {
        /** whisper.cpp's expected sample rate. */
        const val SAMPLE_RATE = 16_000

        const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO

        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** PCM16: two bytes per sample. */
        const val BYTES_PER_SAMPLE = 2

        /** Captures shorter than this are reported as too short instead of transcribed. */
        const val MIN_DURATION_MS = 300

        /** Hard cap so a stuck session cannot record forever. */
        const val MAX_DURATION_MS = 120_000

        /**
         * Byte cap corresponding to [MAX_DURATION_MS] of 16 kHz mono PCM16 = 3_840_000 bytes.
         * Computed in `Long`: the intermediate product `16_000 * 2 * 120_000 = 3_840_000_000`
         * overflows Int32, and the wrapped negative value would make the reader-loop guard
         * (`pcm.size() < MAX_PCM_BYTES`) fail immediately, capturing zero bytes every time.
         */
        val MAX_PCM_BYTES = SAMPLE_RATE.toLong() * BYTES_PER_SAMPLE * MAX_DURATION_MS / 1000

        init {
            check(MAX_PCM_BYTES > 0) { "MAX_PCM_BYTES must be positive, got $MAX_PCM_BYTES" }
        }

        private const val MIN_CHUNK_SHORTS = 1024

        /** Backoff when `AudioRecord.read` returns 0 (no data yet) so the loop does not spin. */
        private const val EMPTY_READ_SLEEP_MS = 5L
        private const val DIR_NAME = "mic_dictation"
        private const val SUFFIX = ".wav"
        private const val THREAD_NAME = "mic-dictation"
        private const val STOP_JOIN_MS = 2_000L
        private const val CANCEL_JOIN_MS = 1_000L
        private const val TAG = "Andharness"
    }
}
