package com.andharness.ru.data.communications

import android.content.Context
import android.content.SharedPreferences
import com.andharness.ru.domain.communications.CommunicationsConfig
import com.andharness.ru.domain.communications.CommunicationsMode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted configuration of the Communications subsystem (call recording → transcription → index).
 *
 * Backed by plain [SharedPreferences] (file [PREFS_FILE]) because these are non-secret flags that
 * background workers must be able to read synchronously, exactly like
 * `com.andharness.ru.data.core.WatchdogConfigStore`. Every value is exposed twice:
 * a [StateFlow] the Settings screen mirrors, and a synchronous `current…()` getter / `set…()` setter
 * a worker can use without a coroutine. Integer setters clamp through the pure
 * [CommunicationsConfig] helpers, so an out-of-range value can never be persisted.
 *
 * Reference: https://developer.android.com/training/data-storage/shared-preferences
 */
@Singleton
class CommunicationsConfigStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(
        CommunicationsMode.fromId(prefs.getString(KEY_MODE, null))
    )
    val mode: StateFlow<CommunicationsMode> = _mode.asStateFlow()

    private val _keepAudioDays = MutableStateFlow(
        CommunicationsConfig.clampKeepAudioDays(
            prefs.getInt(KEY_KEEP_AUDIO_DAYS, CommunicationsConfig.DEFAULT_KEEP_AUDIO_DAYS)
        )
    )
    val keepAudioDays: StateFlow<Int> = _keepAudioDays.asStateFlow()

    private val _audioCacheLimitMb = MutableStateFlow(
        CommunicationsConfig.clampAudioCacheLimitMb(
            prefs.getInt(
                KEY_AUDIO_CACHE_LIMIT_MB,
                CommunicationsConfig.DEFAULT_AUDIO_CACHE_LIMIT_MB,
            )
        )
    )
    val audioCacheLimitMb: StateFlow<Int> = _audioCacheLimitMb.asStateFlow()

    private val _maxProcessMinutesPerDay = MutableStateFlow(
        CommunicationsConfig.clampMaxProcessMinutesPerDay(
            prefs.getInt(
                KEY_MAX_PROCESS_MINUTES_PER_DAY,
                CommunicationsConfig.DEFAULT_MAX_PROCESS_MINUTES_PER_DAY,
            )
        )
    )
    val maxProcessMinutesPerDay: StateFlow<Int> = _maxProcessMinutesPerDay.asStateFlow()

    private val _whisperModelId = MutableStateFlow(
        CommunicationsConfig.normalizedWhisperModelId(prefs.getString(KEY_WHISPER_MODEL_ID, null))
    )
    val whisperModelId: StateFlow<String> = _whisperModelId.asStateFlow()

    private val _whisperLanguage = MutableStateFlow(
        prefs.getString(KEY_WHISPER_LANGUAGE, CommunicationsConfig.DEFAULT_WHISPER_LANGUAGE)
            ?: CommunicationsConfig.DEFAULT_WHISPER_LANGUAGE
    )
    val whisperLanguage: StateFlow<String> = _whisperLanguage.asStateFlow()

    private val _sendTranscriptsToCloud = MutableStateFlow(
        prefs.getBoolean(
            KEY_SEND_TRANSCRIPTS_TO_CLOUD,
            CommunicationsConfig.DEFAULT_SEND_TRANSCRIPTS_TO_CLOUD,
        )
    )
    val sendTranscriptsToCloud: StateFlow<Boolean> = _sendTranscriptsToCloud.asStateFlow()

    private val _diarizationEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_DIARIZATION_ENABLED, CommunicationsConfig.DEFAULT_DIARIZATION_ENABLED)
    )
    val diarizationEnabled: StateFlow<Boolean> = _diarizationEnabled.asStateFlow()

    private val _recordingsFolders = MutableStateFlow(readFolders())

    // --- synchronous getters (worker-friendly, no coroutine) --------------------------------

    fun currentMode(): CommunicationsMode = _mode.value

    fun currentKeepAudioDays(): Int = _keepAudioDays.value

    fun currentAudioCacheLimitMb(): Int = _audioCacheLimitMb.value

    fun currentMaxProcessMinutesPerDay(): Int = _maxProcessMinutesPerDay.value

    fun currentWhisperModelId(): String = _whisperModelId.value

    fun currentWhisperLanguage(): String = _whisperLanguage.value

    fun isSendTranscriptsToCloud(): Boolean = _sendTranscriptsToCloud.value

    fun isDiarizationEnabled(): Boolean = _diarizationEnabled.value

    fun currentRecordingsFolders(): Set<String> = _recordingsFolders.value

    // --- setters: persist + publish ---------------------------------------------------------

    fun setMode(value: CommunicationsMode) {
        prefs.edit().putString(KEY_MODE, value.id).apply()
        _mode.value = value
    }

    fun setKeepAudioDays(value: Int) {
        val clamped = CommunicationsConfig.clampKeepAudioDays(value)
        prefs.edit().putInt(KEY_KEEP_AUDIO_DAYS, clamped).apply()
        _keepAudioDays.value = clamped
    }

    fun setAudioCacheLimitMb(value: Int) {
        val clamped = CommunicationsConfig.clampAudioCacheLimitMb(value)
        prefs.edit().putInt(KEY_AUDIO_CACHE_LIMIT_MB, clamped).apply()
        _audioCacheLimitMb.value = clamped
    }

    fun setMaxProcessMinutesPerDay(value: Int) {
        val clamped = CommunicationsConfig.clampMaxProcessMinutesPerDay(value)
        prefs.edit().putInt(KEY_MAX_PROCESS_MINUTES_PER_DAY, clamped).apply()
        _maxProcessMinutesPerDay.value = clamped
    }

    fun setWhisperModelId(value: String) {
        val normalized = CommunicationsConfig.normalizedWhisperModelId(value)
        prefs.edit().putString(KEY_WHISPER_MODEL_ID, normalized).apply()
        _whisperModelId.value = normalized
    }

    fun setSendTranscriptsToCloud(value: Boolean) {
        prefs.edit().putBoolean(KEY_SEND_TRANSCRIPTS_TO_CLOUD, value).apply()
        _sendTranscriptsToCloud.value = value
    }

    /** Reads the stored folder set as an immutable copy; `getStringSet` returns a live instance. */
    private fun readFolders(): Set<String> =
        prefs.getStringSet(KEY_RECORDINGS_FOLDERS, emptySet())?.toSet()
            ?: CommunicationsConfig.DEFAULT_RECORDINGS_FOLDERS

    // --- daily transcription budget (worker-friendly, no coroutine) --------------------------

    /**
     * Milliseconds already spent transcribing today, according to the persisted date stamp. A stale
     * stamp (a previous day) reports `0` without writing anything, so the budget rolls over at the
     * local-midnight boundary on the next read or write.
     */
    fun currentProcessBudgetUsedMs(): Long {
        val today = LocalDate.now().toString()
        return if (prefs.getString(KEY_BUDGET_DATE, null) == today) {
            prefs.getLong(KEY_BUDGET_USED_MS, 0L).coerceAtLeast(0L)
        } else {
            0L
        }
    }

    /** Remaining milliseconds of today's transcription budget; never negative. */
    fun currentProcessBudgetRemainingMs(): Long {
        val budget = currentMaxProcessMinutesPerDay().toLong() * MILLIS_PER_MINUTE
        return (budget - currentProcessBudgetUsedMs()).coerceAtLeast(0L)
    }

    /** Adds [elapsedMs] to today's used time (rolling the date over first); ignores non-positive. */
    fun addProcessBudgetUsedMs(elapsedMs: Long) {
        if (elapsedMs <= 0L) return
        val today = LocalDate.now().toString()
        val base = if (prefs.getString(KEY_BUDGET_DATE, null) == today) {
            prefs.getLong(KEY_BUDGET_USED_MS, 0L).coerceAtLeast(0L)
        } else {
            0L
        }
        prefs.edit()
            .putString(KEY_BUDGET_DATE, today)
            .putLong(KEY_BUDGET_USED_MS, base + elapsedMs)
            .apply()
    }

    companion object {
        const val PREFS_FILE = "andharness_communications"

        private const val KEY_MODE = "mode"
        private const val KEY_KEEP_AUDIO_DAYS = "keep_audio_days"
        private const val KEY_AUDIO_CACHE_LIMIT_MB = "audio_cache_limit_mb"
        private const val KEY_MAX_PROCESS_MINUTES_PER_DAY = "max_process_minutes_per_day"
        private const val KEY_BUDGET_DATE = "process_budget_date"
        private const val KEY_BUDGET_USED_MS = "process_budget_used_ms"
        private const val KEY_WHISPER_MODEL_ID = "whisper_model_id"
        private const val KEY_WHISPER_LANGUAGE = "whisper_language"
        private const val KEY_SEND_TRANSCRIPTS_TO_CLOUD = "send_transcripts_to_cloud"
        private const val KEY_DIARIZATION_ENABLED = "diarization_enabled"
        private const val KEY_RECORDINGS_FOLDERS = "recordings_folders"

        private const val MILLIS_PER_MINUTE = 60_000L
    }
}
