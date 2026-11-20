package com.andharness.ru.bg

import android.app.Activity
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.andharness.ru.R
import com.andharness.ru.data.notifications.NotificationHelper
import com.andharness.ru.data.tools.media.AudioCaptureState
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Foreground service that captures the audio played by OTHER apps (Android 10+, API 29+) with
 * `AudioPlaybackCaptureConfiguration` + [AudioRecord], writes a 16-bit PCM WAV file and reports the
 * measured level. It does NOT create a virtual display — screen content is never recorded.
 *
 * API ORDER (mandatory on Android 14+, API 34+):
 * 1. The Activity obtained user consent through
 *    `MediaProjectionManager.createScreenCaptureIntent()`; resultCode/data are passed here.
 * 2. The service calls [ServiceCompat.startForeground] with
 *    [ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION] BEFORE touching the projection.
 * 3. Only then `MediaProjectionManager.getMediaProjection(resultCode, data)`; without a running
 *    `mediaProjection` foreground service that call throws `SecurityException`.
 * 4. A source app may forbid capture (`allowAudioPlaybackCapture`, `setAllowedCapturePolicy`); the
 *    captured stream is then silence — that is reported honestly through [AudioCaptureState].
 *
 * References:
 * - https://developer.android.com/media/platform/av-capture
 * - https://developer.android.com/reference/android/media/AudioPlaybackCaptureConfiguration
 * - https://developer.android.com/reference/android/media/AudioRecord
 * - https://developer.android.com/reference/android/media/projection/MediaProjectionManager
 * - https://developer.android.com/about/versions/14/changes/fgs-types-required
 */
@AndroidEntryPoint
class AudioCaptureService : Service() {

    @Inject
    lateinit var notifications: NotificationHelper

    @Volatile
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null

    /** Set from [MediaProjection.Callback.onStop] when the user revokes the projection. */
    @Volatile
    private var revoked = false

    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            handleStart(intent, startId)
        } else {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent, startId: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            AudioCaptureState.markFinished(
                null, 0L, -100.0, false,
                "Захват звука других приложений требует Android 10 (API 29) или новее.",
            )
            stopSelf(startId)
            return
        }

        notifications.ensureChannels()
        val foreground = try {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.FGS_ID_AUDIO_CAPTURE,
                capturingNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            false
        }
        if (!foreground) {
            AudioCaptureState.markFinished(
                null, 0L, -100.0, false,
                "Не удалось перевести службу захвата звука в foreground.",
            )
            stopSelf(startId)
            return
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        val path = intent.getStringExtra(EXTRA_OUTPUT_PATH)
        if (resultCode != Activity.RESULT_OK || resultData == null || path.isNullOrEmpty()) {
            finish(null, 0L, -100.0, false, "Не получено согласие MediaProjection для захвата звука.")
            return
        }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        val projection = try {
            manager?.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e)
            null
        }
        if (projection == null) {
            finish(null, 0L, -100.0, false, "Система не выдала проекцию (согласие отклонено или истекло).")
            return
        }
        mediaProjection = projection
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection.onStop()")
                revoked = true
            }
        }
        projectionCallback = callback
        try {
            projection.registerCallback(callback, null)
        } catch (e: Exception) {
            Log.e(TAG, "registerCallback failed", e)
        }

        val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, DEFAULT_DURATION_MS)
            .coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
        val file = File(path)
        file.parentFile?.mkdirs()
        worker = Thread(
            { captureInto(projection, file, durationMs) },
            "andharness-audio-capture",
        ).also { it.start() }
    }

    /** Records [durationMs] of playback capture into a WAV file; always finishes the state. */
    private fun captureInto(projection: MediaProjection, file: File, durationMs: Long) {
        val sampleRate = SAMPLE_RATE
        val channelMask = AudioFormat.CHANNEL_IN_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val requestedChannels = 2
        val bytesPerSample = 2

        var record: AudioRecord? = null
        var raf: RandomAccessFile? = null
        var samples = 0L
        var sumSq = 0.0
        var silentSamples = 0L

        try {
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
            if (minBuffer <= 0) {
                finish(
                    null, 0L, -100.0, false,
                    "Захват звука не поддерживается устройством (getMinBufferSize=$minBuffer).",
                )
                return
            }

            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val bufferSize = maxOf(minBuffer * 2, sampleRate * requestedChannels * bytesPerSample / 2)
            val rec = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
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
                finish(
                    null, 0L, -100.0, false,
                    "Не удалось инициализировать захват воспроизводимого звука (источник недоступен).",
                )
                return
            }
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                finish(
                    null, 0L, -100.0, false,
                    "Запись системного звука не началась (возможно, источник запретил захват).",
                )
                return
            }

            // Channel count the recorder actually configured: the WAV header must declare exactly
            // this count. `AudioRecord.read(short[], ...)` returns SHORTS (all channels interleaved),
            // so a stereo stream yields 2 shorts per frame — the read count must NOT be treated as a
            // frame count (BUG-020).
            val channels = rec.channelCount.coerceAtLeast(1)

            val out = RandomAccessFile(file, "rw")
            raf = out
            out.setLength(0L)
            out.write(ByteArray(WavHeader.SIZE))

            val totalSamples = sampleRate.toLong() * durationMs / 1000L * channels
            val chunk = ShortArray(maxOf(minBuffer / bytesPerSample, 1024))
            var stopReason: String? = null
            while (samples < totalSamples) {
                if (revoked) {
                    stopReason = "Сессия захвата остановлена пользователем (проецирование отозвано)."
                    break
                }
                val want = minOf(chunk.size.toLong(), totalSamples - samples).toInt()
                if (want <= 0) break
                val read = try {
                    rec.read(chunk, 0, want)
                } catch (e: Exception) {
                    Log.e(TAG, "AudioRecord.read failed", e)
                    -1
                }
                if (read <= 0) {
                    stopReason = "Чтение аудиопотока прервано (код $read)."
                    break
                }
                val bytesChunk = ByteArray(read * bytesPerSample)
                var i = 0
                for (s in 0 until read) {
                    val v = chunk[s].toInt()
                    bytesChunk[i++] = (v and 0xFF).toByte()
                    bytesChunk[i++] = ((v shr 8) and 0xFF).toByte()
                    val abs = if (v < 0) -v else v
                    if (abs < SILENCE_LEVEL) silentSamples++
                    sumSq += v.toDouble() * v
                }
                out.write(bytesChunk)
                samples += read
            }

            // BUG-020: payload is exactly `samples` shorts (all channels interleaved) * 2 bytes;
            // the header is built from that same count, so header and payload always agree.
            val dataBytes = samples * bytesPerSample
            out.seek(0L)
            out.write(WavHeader.build(sampleRate, channels, bytesPerSample * 8, dataBytes.toInt()))
            out.close()
            raf = null

            val rms = if (samples > 0) sqrt(sumSq / samples) else 0.0
            val dbfs = if (rms > 0.0) 20.0 * log10(rms / 32768.0) else -100.0
            val silent = samples > 0 && silentSamples.toDouble() / samples >= SILENCE_RATIO
            finish(file.absolutePath, dataBytes, dbfs, silent, stopReason)
        } catch (e: Exception) {
            Log.e(TAG, "audio capture failed", e)
            finish(null, 0L, -100.0, false, "Не удалось записать звук приложений: ${e.message}")
        } finally {
            try {
                record?.stop()
            } catch (_: Exception) {
            }
            try {
                record?.release()
            } catch (_: Exception) {
            }
            try {
                raf?.close()
            } catch (_: Exception) {
            }
        }
    }

    /** Publishes the outcome, releases the projection and stops the service exactly once. */
    private fun finish(
        path: String?,
        bytes: Long,
        rmsDbfs: Double,
        silent: Boolean,
        error: String? = null,
    ) {
        AudioCaptureState.markFinished(path, bytes, rmsDbfs, silent, error)
        releaseProjection()
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    private fun releaseProjection() {
        val projection = mediaProjection
        mediaProjection = null
        val callback = projectionCallback
        projectionCallback = null
        try {
            if (projection != null && callback != null) projection.unregisterCallback(callback)
        } catch (_: Exception) {
        }
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        releaseProjection()
        super.onDestroy()
    }

    private fun capturingNotification(): Notification =
        NotificationCompat.Builder(this, NotificationHelper.CHANNEL_TASKS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Меха")
            .setContentText("Записываю звук приложений…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    companion object {
        private const val TAG = "AudioCaptureService"

        /** Capture format: 44.1 kHz stereo 16-bit PCM (the mix is resampled by the platform). */
        private const val SAMPLE_RATE = 44_100

        /** Samples below this magnitude (of 32768) count as silence. */
        private const val SILENCE_LEVEL = 500

        /** A window counts as silent when this share of its samples is below [SILENCE_LEVEL]. */
        private const val SILENCE_RATIO = 0.95

        const val DEFAULT_DURATION_MS = 5_000L
        const val MIN_DURATION_MS = 500L
        const val MAX_DURATION_MS = 60_000L

        const val ACTION_START = "com.andharness.ru.action.AUDIO_CAPTURE_START"
        const val EXTRA_RESULT_CODE = "audio_capture_result_code"
        const val EXTRA_RESULT_DATA = "audio_capture_result_data"
        const val EXTRA_OUTPUT_PATH = "audio_capture_output_path"
        const val EXTRA_DURATION_MS = "audio_capture_duration_ms"

        /** Starts the capture foreground service; returns false when the OS refuses to start it. */
        fun start(
            context: Context,
            resultCode: Int,
            data: Intent,
            outputPath: String,
            durationMs: Long,
        ): Boolean {
            val intent = Intent(context, AudioCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_OUTPUT_PATH, outputPath)
                .putExtra(EXTRA_DURATION_MS, durationMs)
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService failed", e)
                false
            }
        }
    }
}
