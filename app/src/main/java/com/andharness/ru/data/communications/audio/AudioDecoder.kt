package com.andharness.ru.data.communications.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.andharness.ru.bg.WavHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Result of [AudioDecoder.decodeToWav16kMono]: the temporary 16 kHz mono PCM16 WAV file. */
data class DecodedAudio(
    val outputFile: File,
    val durationMs: Long,
    val sampleRate: Int = 16_000,
    val channels: Int = 1,
)

/**
 * Decodes an arbitrary audio file (m4a/aac/mp3/opus/ogg/flac/wav/3gp, i.e. anything Android's
 * demuxer + decoders support) into a temporary **16 kHz, mono, PCM16 WAV** for `whisper-cli`.
 *
 * Why pre-decode with `MediaCodec`: Whisper's bundled decoder does not reliably open every exotic
 * container/codec, whereas Android ships demuxers and hardware/software decoders for the supported
 * media formats. We demux with [MediaExtractor], decode to raw PCM with [MediaCodec], then downmix
 * and resample with [PcmAudio] and wrap the payload in a canonical [WavHeader].
 *
 * Robustness:
 * - selects the first audio track only, and fails honestly when the file has none;
 * - honours [MediaCodec.INFO_OUTPUT_FORMAT_CHANGED] to read the **actual** sample rate/channel count
 *   of the decoded PCM instead of guessing from the container;
 * - requests 16-bit PCM output and refuses float PCM honestly;
 * - bounds every step (dequeue timeouts + a wall-clock deadline) so it can never hang, and always
 *   releases the codec and extractor in `finally`;
 * - never throws to the caller besides coroutine cancellation: failures are returned as
 *   `Result.failure` with an honest, user-facing message.
 *
 * References (developer.android.com):
 * - MediaExtractor — https://developer.android.com/reference/android/media/MediaExtractor
 * - MediaCodec — https://developer.android.com/reference/android/media/MediaCodec
 *   (raw audio buffers: interleaved frames, 16-bit signed int or float, native byte order)
 * - MediaFormat (KEY_SAMPLE_RATE / KEY_CHANNEL_COUNT / KEY_PCM_ENCODING) —
 *   https://developer.android.com/reference/android/media/MediaFormat
 * - Supported media formats —
 *   https://developer.android.com/media/platform/supported-formats
 */
object AudioDecoder {

    /** Target sample rate for `whisper-cli`. */
    const val TARGET_SAMPLE_RATE = 16_000

    /** Target channel count (mono) for `whisper-cli`. */
    const val TARGET_CHANNELS = 1

    /** Bits per sample written to the WAV. */
    private const val TARGET_BITS_PER_SAMPLE = 16

    /**
     * `dequeueInputBuffer`/`dequeueOutputBuffer` timeout. Short enough that the wall-clock deadline
     * is checked often, long enough not to busy-spin.
     */
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /** Hard wall-clock budget for decoding a single file, so a pathological input cannot hang. */
    private const val MAX_DECODE_NANOS = 10L * 60L * 1_000_000_000L

    /**
     * Decodes [input] into [output] as a 16 kHz mono PCM16 WAV, off the main thread.
     * Returns `Result.failure` with a Russian, user-facing message on any problem (missing/empty
     * file, no audio track, unsupported codec, float PCM, timeout, …). The partially written
     * [output] is deleted on failure.
     */
    suspend fun decodeToWav16kMono(input: File, output: File): Result<DecodedAudio> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(decode(input, output))
            } catch (e: CancellationException) {
                output.delete()
                throw e
            } catch (e: Throwable) {
                output.delete()
                Result.failure(e)
            }
        }

    private fun decode(input: File, output: File): DecodedAudio {
        if (!input.isFile) throw IllegalStateException("Аудиофайл не найден: ${input.name}")
        if (input.length() <= 0L) throw IllegalStateException("Аудиофайл пуст: ${input.name}")

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(input.absolutePath)
            val trackIndex = selectAudioTrack(extractor)
            extractor.selectTrack(trackIndex)

            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.stringOrNull(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("Не удалось определить формат аудиодорожки: ${input.name}")

            // Ask the decoder for 16-bit PCM; verified against the real output format below.
            // KEY_PCM_ENCODING values are AudioFormat.ENCODING_PCM_* constants.
            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)

            val decoder = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: IOException) {
                throw IllegalStateException("Нет декодера для формата $mime.", e)
            }
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val outputBytes = decodePcm(extractor, decoder, inputFormat, output)
            if (outputBytes <= 0L) {
                throw IllegalStateException("Не удалось декодировать аудио: поток пуст (${input.name}).")
            }

            patchHeader(output, outputBytes)
            val durationMs = outputBytes / 2L * 1000L / TARGET_SAMPLE_RATE
            return DecodedAudio(output, durationMs)
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Runs the synchronous decode loop and streams the resulting 16 kHz mono samples into [output]
     * after a placeholder header. Returns the number of payload bytes written (before the header is
     * patched).
     */
    private fun decodePcm(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        inputFormat: MediaFormat,
        output: File,
    ): Long {
        val deadline = System.nanoTime() + MAX_DECODE_NANOS
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var sourceRate = inputFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, 0).coerceAtLeast(1)
        var sourceChannels = inputFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1).coerceAtLeast(1)
        var floatPcm = false
        var payloadBytes = 0L

        FileOutputStream(output).use { raw ->
            val sink = BufferedOutputStream(raw)
            sink.write(ByteArray(WavHeader.SIZE))
            try {
                while (!outputDone) {
                    if (System.nanoTime() > deadline) {
                        throw IllegalStateException("Декодирование превысило лимит времени (10 мин).")
                    }

                    if (!inputDone) {
                        val inIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                        if (inIndex >= 0) {
                            val inBuffer = decoder.getInputBuffer(inIndex)
                                ?: throw IllegalStateException("Декодер не выдал входной буфер.")
                            inBuffer.clear()
                            val size = extractor.readSampleData(inBuffer, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(
                                    inIndex, 0, size, extractor.sampleTime, 0,
                                )
                                extractor.advance()
                            }
                        }
                    }

                    when (val outIndex = decoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = decoder.outputFormat
                            sourceRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, sourceRate)
                                .coerceAtLeast(1)
                            sourceChannels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, sourceChannels)
                                .coerceAtLeast(1)
                            floatPcm = format.intOr(
                                MediaFormat.KEY_PCM_ENCODING,
                                AudioFormat.ENCODING_PCM_16BIT,
                            ) == AudioFormat.ENCODING_PCM_FLOAT
                        }

                        MediaCodec.INFO_TRY_AGAIN_LATER,
                        MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED,
                        -> Unit

                        else -> if (outIndex >= 0) {
                            try {
                                if (bufferInfo.size > 0) {
                                    if (floatPcm) {
                                        throw IllegalStateException(
                                            "Декодер выдал PCM с плавающей точкой; поддерживается только 16-битный PCM.",
                                        )
                                    }
                                    val outBuffer = decoder.getOutputBuffer(outIndex)
                                    if (outBuffer != null) {
                                        outBuffer.position(bufferInfo.offset)
                                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                        val samples = outBuffer.asShortArray()
                                        payloadBytes += writeMono16k(sink, samples, sourceChannels, sourceRate)
                                    }
                                }
                            } finally {
                                decoder.releaseOutputBuffer(outIndex, false)
                            }
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            } finally {
                sink.flush()
            }
        }
        return payloadBytes
    }

    /** Downmixes+resamples decoded [samples] and writes little-endian PCM16; returns bytes written. */
    private fun writeMono16k(
        sink: BufferedOutputStream,
        samples: ShortArray,
        channels: Int,
        sourceRate: Int,
    ): Long {
        val mono = PcmAudio.resample(
            PcmAudio.downmixToMono(samples, channels),
            sourceRate,
            TARGET_SAMPLE_RATE,
        )
        if (mono.isEmpty()) return 0L
        val bytes = ByteArray(mono.size * 2)
        var i = 0
        for (sample in mono) {
            val value = sample.toInt()
            bytes[i++] = (value and 0xFF).toByte()
            bytes[i++] = ((value shr 8) and 0xFF).toByte()
        }
        sink.write(bytes)
        return bytes.size.toLong()
    }

    /** Rewrites the placeholder [WavHeader] now that the exact payload size is known. */
    private fun patchHeader(output: File, payloadBytes: Long) {
        val header = WavHeader.build(
            sampleRate = TARGET_SAMPLE_RATE,
            channels = TARGET_CHANNELS,
            bitsPerSample = TARGET_BITS_PER_SAMPLE,
            dataByteCount = payloadBytes.toInt(),
        )
        RandomAccessFile(output, "rw").use { raf ->
            raf.seek(0L)
            raf.write(header)
        }
    }

    /** Index of the first audio track (MIME type starting with `audio/`). */
    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).stringOrNull(MediaFormat.KEY_MIME)
            if (mime != null && mime.startsWith("audio/")) return index
        }
        throw IllegalStateException("В файле нет аудиодорожки.")
    }

    private fun MediaFormat.stringOrNull(key: String): String? =
        if (containsKey(key)) getString(key) else null

    private fun MediaFormat.intOr(key: String, fallback: Int): Int =
        if (containsKey(key)) {
            try {
                getInteger(key)
            } catch (_: Exception) {
                fallback
            }
        } else {
            fallback
        }

    /** Copies the buffer's remaining bytes as little-endian shorts (raw audio is in native order). */
    private fun ByteBuffer.asShortArray(): ShortArray {
        val ordered = order(ByteOrder.nativeOrder())
        val shorts = ordered.asShortBuffer()
        val result = ShortArray(shorts.remaining())
        shorts.get(result)
        return result
    }
}
