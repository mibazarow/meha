package com.andharness.ru.data.tools.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.Tool
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * `audio_stream_analysis`: records a bounded window from the microphone and reports the level
 * (RMS/peak/silence ratio). Never writes a file.
 *
 * Reference: `AudioRecord` (`Builder`, `getMinBufferSize`, `read`, `startRecording`, `release`) —
 * https://developer.android.com/reference/android/media/AudioRecord
 */
internal class AudioStreamAnalysisToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "audio_stream_analysis"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext ToolResult(
                false,
                "${Tool.PERMISSION_REQUIRED}:${Manifest.permission.RECORD_AUDIO}",
            )
        }

        val durationRaw = (arguments["duration_ms"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val requested = if (durationRaw.isEmpty()) {
            DEFAULT_ANALYSIS_MS
        } else {
            durationRaw.toLongOrNull()
                ?: return@withContext ToolResult(false, "Некорректный duration_ms: «$durationRaw».")
        }
        if (requested < MIN_ANALYSIS_MS) {
            return@withContext ToolResult(
                false,
                "duration_ms должен быть не меньше $MIN_ANALYSIS_MS мс.",
            )
        }
        val durationMs = requested.coerceAtMost(MAX_ANALYSIS_MS)

        val sampleRate = ANALYSIS_SAMPLE_RATE
        val channelMask = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val bytesPerSample = 2

        var record: AudioRecord? = null
        try {
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
            if (minBuffer <= 0) {
                return@withContext ToolResult(
                    false,
                    "Микрофонный вход недоступен: getMinBufferSize вернул $minBuffer.",
                )
            }
            val bufferSize = maxOf(minBuffer * 2, sampleRate * bytesPerSample / 2)
            val rec = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
            record = rec
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                return@withContext ToolResult(
                    false,
                    "Не удалось инициализировать микрофон (возможно, он занят другим приложением).",
                )
            }
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return@withContext ToolResult(
                    false,
                    "Микрофон не начал запись (возможно, занят другим приложением).",
                )
            }

            val totalSamples = sampleRate.toLong() * durationMs / 1000L
            val chunk = ShortArray(maxOf(minBuffer / bytesPerSample, 1024))
            var read = 0L
            var sumSq = 0.0
            var peak = 0
            var silentSamples = 0L
            while (read < totalSamples) {
                val want = minOf(chunk.size.toLong(), totalSamples - read).toInt()
                if (want <= 0) break
                val n = rec.read(chunk, 0, want)
                if (n <= 0) break
                for (i in 0 until n) {
                    val value = chunk[i].toInt()
                    val magnitude = abs(value)
                    if (magnitude > peak) peak = magnitude
                    if (magnitude < SILENCE_LEVEL) silentSamples++
                    sumSq += value.toDouble() * value
                }
                read += n
            }
            if (read <= 0L) {
                return@withContext ToolResult(false, "Не удалось прочитать ни одного сэмпла с микрофона.")
            }

            val rms = sqrt(sumSq / read)
            val rmsDbfs = if (rms > 0.0) 20.0 * log10(rms / 32768.0) else -100.0
            val peakDbfs = if (peak > 0) 20.0 * log10(peak / 32768.0) else -100.0
            val silenceRatio = silentSamples.toDouble() / read * 100.0
            ToolResult(
                true,
                "Анализ звука с микрофона за ${read * 1000L / sampleRate}мс " +
                    "($sampleRate Гц, моно, PCM16):\n" +
                    "- RMS: ${AudioFormatting.formatDouble(rms)} (${AudioFormatting.formatDouble(rmsDbfs)} dBFS)\n" +
                    "- Пик: $peak (${AudioFormatting.formatDouble(peakDbfs)} dBFS)\n" +
                    "- Доля тишины (ниже $SILENCE_LEVEL): ${AudioFormatting.formatDouble(silenceRatio)}%",
            )
        } catch (e: Exception) {
            Log.e(AudioLog.TAG, "audio_stream_analysis failed", e)
            ToolResult(false, "Не удалось проанализировать звук: ${e.message}")
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
        }
    }

    private companion object {
        /** `audio_stream_analysis` window: default 3 s, cap 10 s (see the tool schema). */
        private const val DEFAULT_ANALYSIS_MS = 3_000L
        private const val MIN_ANALYSIS_MS = 200L
        private const val MAX_ANALYSIS_MS = 10_000L

        /** Analysis capture format: 16 kHz mono PCM16 as required by the tool contract. */
        private const val ANALYSIS_SAMPLE_RATE = 16_000

        /** Sample magnitudes below this value (of 32768) count as silence. */
        private const val SILENCE_LEVEL = 500
    }
}
