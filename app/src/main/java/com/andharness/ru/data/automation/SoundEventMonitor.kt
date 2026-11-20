package com.andharness.ru.data.automation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.andharness.ru.domain.automation.AutomationEvent
import com.andharness.ru.domain.automation.AutomationRule
import com.andharness.ru.domain.automation.AutomationRuleRepository
import com.andharness.ru.domain.automation.AutomationSpec
import com.andharness.ru.domain.automation.RuleEngine
import com.andharness.ru.domain.automation.TriggerConfigs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * In-process source for the `sound_event` automation trigger (update.md idea #18).
 *
 * While at least one enabled `sound_event` rule exists it opens the microphone with [AudioRecord]
 * (the same bounded-window technique as `audio_stream_analysis`) and computes the RMS level of each
 * read window in dBFS. A rule fires when its configured level is reached and held for its
 * `sustain_ms` — a brief spike shorter than that is ignored.
 *
 * Honest limits:
 * - it needs RECORD_AUDIO and only runs while the app process is alive (it is not a foreground
 *   service, so Android may stop the microphone in the background);
 * - an always-on level monitor costs battery and, while the microphone is open, other apps cannot
 *   record — sessions are therefore bounded and followed by a pause;
 * - the level is a rough loudness estimate, not speech recognition.
 *
 * References:
 * - `AudioRecord` (Builder, getMinBufferSize, read, release):
 *   https://developer.android.com/reference/android/media/AudioRecord
 * - Recording a raw audio stream: https://developer.android.com/media/platform/audio-recording
 */
@Singleton
class SoundEventMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: RuleEngine,
    private val repository: AutomationRuleRepository
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val lastFiredAt = ConcurrentHashMap<Long, Long>()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { loop() }
    }

    private suspend fun loop() {
        while (scope.isActive) {
            val rules = enabledRules()
            if (rules.isEmpty() || !granted(Manifest.permission.RECORD_AUDIO)) {
                delay(IDLE_REFRESH_MS)
                continue
            }
            runCatching { session(rules) }
                .onFailure { Log.w(TAG, "Sound event monitor session failed: ${it.message}") }
            delay(SESSION_PAUSE_MS)
        }
    }

    private suspend fun enabledRules(): List<AutomationRule> = runCatching {
        repository.getAll().filter {
            it.enabled && it.triggerType == AutomationSpec.TRIGGER_SOUND_EVENT
        }
    }.getOrDefault(emptyList())

    private suspend fun session(rules: List<AutomationRule>) {
        val channelMask = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val bytesPerSample = 2
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelMask, encoding)
        if (minBuffer <= 0) return

        var record: AudioRecord? = null
        val aboveMs = HashMap<Long, Long>()
        try {
            val rec = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer * 2, SAMPLE_RATE * bytesPerSample / 2))
                .build()
            record = rec
            if (rec.state != AudioRecord.STATE_INITIALIZED) return
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) return

            val chunk = ShortArray(maxOf(minBuffer / bytesPerSample, 1024))
            val sessionStart = System.currentTimeMillis()
            while (scope.isActive && System.currentTimeMillis() - sessionStart < SESSION_MS) {
                val read = rec.read(chunk, 0, chunk.size)
                if (read <= 0) break
                var sumSq = 0.0
                for (i in 0 until read) {
                    val value = chunk[i].toDouble()
                    sumSq += value * value
                }
                val rms = sqrt(sumSq / read)
                val levelDbfs = if (rms > 0.0) 20.0 * log10(rms / 32768.0) else MIN_DBFS
                val chunkMs = read * 1000L / SAMPLE_RATE
                val now = System.currentTimeMillis()

                rules.forEach { rule ->
                    val spec = TriggerConfigs.parseSoundEvent(rule.triggerConfig) ?: return@forEach
                    if (!spec.triggers(levelDbfs)) {
                        aboveMs[rule.id] = 0L
                        return@forEach
                    }
                    val accumulated = (aboveMs[rule.id] ?: 0L) + chunkMs
                    val previous = lastFiredAt[rule.id]
                    if (accumulated >= spec.sustainMs &&
                        (previous == null || now - previous >= DEBOUNCE_MS)
                    ) {
                        lastFiredAt[rule.id] = now
                        aboveMs[rule.id] = 0L
                        engine.dispatch(
                            AutomationEvent(
                                triggerType = AutomationSpec.TRIGGER_SOUND_EVENT,
                                ruleId = rule.id,
                                level = levelDbfs
                            )
                        )
                    } else {
                        aboveMs[rule.id] = accumulated
                    }
                }
            }
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
        }
    }

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "Andharness"

        private const val SAMPLE_RATE = 16_000

        /** A watchdog against a runaway mic session; re-armed after a pause. */
        private const val SESSION_MS = 120_000L
        private const val SESSION_PAUSE_MS = 10_000L
        private const val IDLE_REFRESH_MS = 30_000L

        /** An already fired rule does not fire again within this window. */
        private const val DEBOUNCE_MS = 30_000L

        private const val MIN_DBFS = -100.0
    }
}
