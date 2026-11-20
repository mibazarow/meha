package com.andharness.ru.data.communications.audio

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException

/**
 * Decoded content of a canonical PCM WAV file.
 *
 * @param samples signed 16-bit little-endian PCM samples, always **mono** (a multi-channel file is
 *   downmixed with [PcmAudio.downmixToMono] while reading).
 * @param sampleRate sample rate in Hz taken from the `fmt ` chunk.
 * @param channels channel count of [samples] (`1` after downmixing).
 */
data class WavAudio(
    val samples: ShortArray,
    val sampleRate: Int,
    val channels: Int,
)

/**
 * Pure-JVM reader for the temporary WAV that the transcription pipeline writes with
 * [com.andharness.ru.bg.WavHeader] ([AudioDecoder.decodeToWav16kMono]). It parses the RIFF chunk list
 * (not just the fixed 44-byte prefix) so an extra `LIST`/`fact` chunk cannot shift the payload, and
 * returns the PCM payload as a [ShortArray] for per-segment slicing.
 *
 * Only **PCM** (`AudioFormat` = 1) with **16 bits per sample** is accepted; anything else fails with
 * an honest [IllegalArgumentException] rather than silently mis-reading samples. The file is read
 * back little-endian because RIFF is little-endian by specification, regardless of the machine's
 * native byte order.
 *
 * References:
 * - Canonical WAVE layout (chunks, little-endian fields) — http://soundfile.sapp.org/doc/WaveFormat/
 * - WAVE media type — https://www.rfc-editor.org/rfc/rfc2361
 * - `DataInputStream.readFully` — https://developer.android.com/reference/java/io/DataInputStream
 */
object WavReader {

    private const val RIFF = "RIFF"
    private const val WAVE = "WAVE"
    private const val FMT = "fmt "
    private const val DATA = "data"
    private const val PCM_FORMAT = 1
    private const val BITS_PER_SAMPLE_16 = 16
    private const val MIN_FMT_SIZE = 16

    /**
     * Reads [file] and returns its samples plus the `fmt ` metadata. Throws [IOException] for a
     * truncated/unreadable file and [IllegalArgumentException] for a non-PCM / non-16-bit file.
     */
    fun read(file: File): WavAudio {
        DataInputStream(file.inputStream().buffered()).use { input ->
            require(readAscii(input, 4) == RIFF) { "Не WAV-файл (нет сигнатуры RIFF): ${file.name}" }
            readLe32(input) // RIFF chunk size: not trusted, the data chunk size is authoritative.
            require(readAscii(input, 4) == WAVE) { "Не WAV-файл (нет формата WAVE): ${file.name}" }

            var format = 0
            var channels = 0
            var sampleRate = 0
            var bitsPerSample = 0
            var haveFmt = false

            while (true) {
                val id = readAsciiOrNull(input, 4)
                    ?: throw EOFException("WAV без чанка data: ${file.name}")
                val size = readLe32(input)

                when (id) {
                    FMT -> {
                        if (size < MIN_FMT_SIZE) {
                            throw IllegalArgumentException("Некорректный чанк fmt ($size Б): ${file.name}")
                        }
                        val body = ByteArray(size)
                        input.readFully(body)
                        format = le16(body, 0)
                        channels = le16(body, 2)
                        sampleRate = le32(body, 4)
                        bitsPerSample = le16(body, 14)
                        haveFmt = true
                        skipPadding(input, size)
                    }

                    DATA -> {
                        if (!haveFmt) {
                            throw IllegalArgumentException(
                                "WAV-чанк data до fmt — порядок не поддержан: ${file.name}"
                            )
                        }
                        if (format != PCM_FORMAT) {
                            throw IllegalArgumentException(
                                "Поддерживается только PCM (1), а не формат $format: ${file.name}"
                            )
                        }
                        if (bitsPerSample != BITS_PER_SAMPLE_16) {
                            throw IllegalArgumentException(
                                "Поддерживается только 16-битный PCM, а не $bitsPerSample бит: ${file.name}"
                            )
                        }
                        if (channels < 1 || sampleRate <= 0) {
                            throw IllegalArgumentException(
                                "Некорректные параметры WAV (каналы=$channels, частота=$sampleRate): ${file.name}"
                            )
                        }
                        val raw = readPcm(input, size)
                        val mono = if (channels == 1) raw else PcmAudio.downmixToMono(raw, channels)
                        return WavAudio(samples = mono, sampleRate = sampleRate, channels = 1)
                    }

                    else -> skipFully(input, size)
                }
            }
        }
    }

    /** Convenience for callers that only need the PCM samples. */
    fun readSamples(file: File): ShortArray = read(file).samples

    /**
     * Reads the `data` payload of [size] bytes (or to EOF when [size] is negative) as little-endian
     * 16-bit samples, dropping a trailing odd byte.
     */
    private fun readPcm(input: DataInputStream, size: Int): ShortArray {
        if (size >= 0) {
            val count = size / 2
            val out = ShortArray(count)
            for (i in 0 until count) out[i] = readLe16(input)
            skipPadding(input, size)
            return out
        }
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            bytes.write(buffer, 0, read)
        }
        val payload = bytes.toByteArray()
        val count = payload.size / 2
        val out = ShortArray(count)
        for (i in 0 until count) out[i] = le16(payload, i * 2).toShort()
        return out
    }

    /** Reads exactly [n] ASCII bytes, or `null` at clean EOF before the first byte. */
    private fun readAsciiOrNull(input: DataInputStream, n: Int): String? {
        val first = input.read()
        if (first < 0) return null
        val bytes = ByteArray(n)
        bytes[0] = first.toByte()
        input.readFully(bytes, 1, n - 1)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun readAscii(input: DataInputStream, n: Int): String {
        val bytes = ByteArray(n)
        input.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun readLe16(input: DataInputStream): Short {
        val low = input.read()
        val high = input.read()
        if (low < 0 || high < 0) throw EOFException("Неожиданный конец WAV-файла.")
        return le16(low, high)
    }

    private fun readLe32(input: DataInputStream): Int {
        val b0 = input.read()
        val b1 = input.read()
        val b2 = input.read()
        val b3 = input.read()
        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) throw EOFException("Неожиданный конец WAV-файла.")
        return (b0 and 0xFF) or ((b1 and 0xFF) shl 8) or ((b2 and 0xFF) shl 16) or ((b3 and 0xFF) shl 24)
    }

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun le16(low: Int, high: Int): Short =
        ((low and 0xFF) or ((high and 0xFF) shl 8)).toShort()

    private fun le32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    /** RIFF pads every chunk to an even length; skip the pad byte of an odd [size]. */
    private fun skipPadding(input: DataInputStream, size: Int) {
        if (size < 0) return
        if (size % 2 == 1) skipFully(input, 1)
    }

    private fun skipFully(input: DataInputStream, size: Int) {
        if (size <= 0) return
        var remaining = size + (size % 2) // a skipped chunk is padded to an even length
        while (remaining > 0) {
            val skipped = input.skipBytes(remaining)
            if (skipped <= 0) throw EOFException("Неожиданный конец WAV-файла.")
            remaining -= skipped
        }
    }

    private const val COPY_BUFFER_SIZE = 8 * 1024
}

/**
 * Cuts out the `[startMs, endMs)` window of [samples] (16-bit PCM at [sampleRate] Hz) as a new
 * [ShortArray]. Pure and allocation-safe: an empty input, a non-positive rate, a non-advancing window
 * (`endMs <= startMs`), or a window entirely outside the audio all yield an empty array; partial
 * overlaps are clamped to the available samples. Negative timestamps are clamped to `0`.
 *
 * The millisecond-to-sample conversion is done on a bounded millisecond range first, so an extreme
 * `startMs`/`endMs` (e.g. `Long.MAX_VALUE`) cannot overflow the multiply.
 */
fun sliceMs(samples: ShortArray, sampleRate: Int, startMs: Long, endMs: Long): ShortArray {
    if (samples.isEmpty() || sampleRate <= 0 || endMs <= startMs) return ShortArray(0)
    val maxMs = samples.size.toLong() * 1000L / sampleRate + 1L
    val start = startMs.coerceIn(0L, maxMs) * sampleRate / 1000L
    val end = endMs.coerceIn(0L, maxMs) * sampleRate / 1000L
    val from = start.coerceIn(0L, samples.size.toLong()).toInt()
    val to = end.coerceIn(from.toLong(), samples.size.toLong()).toInt()
    if (to <= from) return ShortArray(0)
    return samples.copyOfRange(from, to)
}
