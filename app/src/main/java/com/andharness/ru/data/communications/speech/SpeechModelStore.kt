package com.andharness.ru.data.communications.speech

import android.content.Context
import android.content.SharedPreferences
import com.andharness.ru.domain.communications.speech.SpeechModel
import com.andharness.ru.domain.communications.speech.SpeechModelDownloadState
import com.andharness.ru.domain.communications.speech.SpeechModelFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for the downloaded speech models ([SpeechModel]).
 *
 * Persists only the *terminal-ish* status per model id in plain [SharedPreferences] (the app's usual,
 * migration-free pattern) and exposes the live state of the current/last download as a [StateFlow].
 * Live `Downloading`/`Verifying` values are kept in memory only: after a process restart a stale
 * "downloading" is meaningless, so it loads as [SpeechModelDownloadState.NotDownloaded] while the
 * `.part` file remains on disk for the worker to resume.
 *
 * File layout: `<filesDir>/speech_models/<fileName>` for the verified model and the same path +
 * `.part` for the in-flight download.
 */
@Singleton
class SpeechModelStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<SpeechModelDownloadState>(
        SpeechModelDownloadState.NotDownloaded,
    )

    /** Live state of the current/last speech-model download; collect it to show progress. */
    val state: StateFlow<SpeechModelDownloadState> = _state.asStateFlow()

    /** Per-model persisted-status token, so a progress tick never causes a disk write. */
    private val lastPersistedTokens = mutableMapOf<String, String>()

    /** Absolute path of the verified model, e.g. `<filesDir>/speech_models/<fileName>`. */
    fun targetFile(model: SpeechModel): File = File(modelsDir(), model.fileName)

    /** Temporary file the worker streams into; kept on failure and cancellation so it can resume. */
    fun partFile(model: SpeechModel): File = File(modelsDir(), model.fileName + PART_SUFFIX)

    /**
     * True when the verified file is present and has exactly the expected size. Size, not just
     * existence, so a truncated leftover can never be mistaken for a complete download.
     */
    fun isDownloaded(model: SpeechModel): Boolean {
        val target = targetFile(model)
        return target.isFile && target.length() == model.approxBytes
    }

    /** Convenience for the worker: sets [SpeechModelDownloadState.Downloading] with counters. */
    fun setDownloading(model: SpeechModel, downloadedBytes: Long, totalBytes: Long) =
        setState(SpeechModelDownloadState.Downloading(model.id, downloadedBytes, totalBytes))

    /** Convenience for the worker: records an honest failure [reason] for [model]. */
    fun setFailed(model: SpeechModel, reason: String) =
        setState(SpeechModelDownloadState.Failed(model.id, reason))

    /** Publishes a new state and persists it (cheaply: identical persisted tokens are skipped). */
    @Synchronized
    fun setState(newState: SpeechModelDownloadState) {
        _state.value = newState
        val modelId = newState.modelId ?: return
        val newToken = token(newState)
        if (lastPersistedTokens[modelId] == newToken) return
        lastPersistedTokens[modelId] = newToken
        prefs.edit().apply {
            when (newState) {
                is SpeechModelDownloadState.Done -> {
                    putString(statusKey(modelId), STATUS_DONE)
                    putString(pathKey(modelId), newState.path)
                    remove(reasonKey(modelId))
                }

                is SpeechModelDownloadState.Failed -> {
                    putString(statusKey(modelId), STATUS_FAILED)
                    putString(reasonKey(modelId), newState.reason)
                }

                SpeechModelDownloadState.Idle, SpeechModelDownloadState.NotDownloaded ->
                    remove(statusKey(modelId)).remove(reasonKey(modelId)).remove(pathKey(modelId))

                is SpeechModelDownloadState.Downloading, is SpeechModelDownloadState.Verifying ->
                    // Only that an operation is/was active; byte counters are not persisted (stale
                    // after a restart) so a progress tick never causes a disk write.
                    putString(statusKey(modelId), STATUS_ACTIVE).remove(reasonKey(modelId))
            }
        }.apply()
    }

    /**
     * Deletes the downloaded [model] and its partial file, returning the number of bytes actually
     * freed (only files whose `File.delete()` returned `true` are counted). The persisted status for
     * this model is reset; if a file survives the delete, the state is honestly recorded as
     * [SpeechModelDownloadState.Failed] instead.
     *
     * The caller is responsible for cancelling any in-flight download before deleting, so the worker
     * cannot recreate the `.part` file it is still writing to.
     * `File.delete()` — https://developer.android.com/reference/java/io/File#delete()
     */
    @Synchronized
    fun delete(model: SpeechModel): Long {
        val freed = SpeechModelFiles.removeFiles(targetFile(model), partFile(model))
        if (targetFile(model).isFile || partFile(model).isFile) {
            setState(SpeechModelDownloadState.Failed(model.id, "не удалось удалить файл модели"))
        } else {
            prefs.edit()
                .remove(statusKey(model.id))
                .remove(reasonKey(model.id))
                .remove(pathKey(model.id))
                .apply()
            lastPersistedTokens.remove(model.id)
            if (_state.value.modelId == model.id) {
                _state.value = SpeechModelDownloadState.NotDownloaded
            }
        }
        return freed
    }

    private fun modelsDir(): File = File(context.filesDir, MODELS_DIR)

    private fun token(state: SpeechModelDownloadState): String = when (state) {
        SpeechModelDownloadState.Idle -> "idle"
        SpeechModelDownloadState.NotDownloaded -> "not_downloaded"
        is SpeechModelDownloadState.Downloading -> "downloading"
        is SpeechModelDownloadState.Verifying -> "verifying"
        is SpeechModelDownloadState.Done -> "done:${state.path}"
        is SpeechModelDownloadState.Failed -> "failed:${state.reason}"
    }

    companion object {
        const val PREFS_FILE = "andharness_speech_models"
        const val MODELS_DIR = "speech_models"
        const val PART_SUFFIX = ".part"

        private const val STATUS_ACTIVE = "active"
        private const val STATUS_DONE = "done"
        private const val STATUS_FAILED = "failed"

        private fun statusKey(modelId: String) = "status_$modelId"

        private fun pathKey(modelId: String) = "path_$modelId"

        private fun reasonKey(modelId: String) = "reason_$modelId"
    }
}

/** The [SpeechModel.id] a state belongs to, or `null` for the global [SpeechModelDownloadState.Idle]/[SpeechModelDownloadState.NotDownloaded]. */
private val SpeechModelDownloadState.modelId: String?
    get() = when (this) {
        SpeechModelDownloadState.Idle, SpeechModelDownloadState.NotDownloaded -> null
        is SpeechModelDownloadState.Downloading -> modelId
        is SpeechModelDownloadState.Verifying -> modelId
        is SpeechModelDownloadState.Done -> modelId
        is SpeechModelDownloadState.Failed -> modelId
    }
