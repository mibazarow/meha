package com.andharness.ru.data.embedding

import android.content.Context
import android.content.SharedPreferences
import com.andharness.ru.domain.embedding.EmbedderModelSpec
import com.andharness.ru.domain.localserver.LocalModelReadiness
import com.andharness.ru.domain.localserver.LocalModelStateResolver
import com.andharness.ru.domain.localserver.ModelDownloadState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for the downloaded EmbeddingGemma 2 embedder artifacts
 * ([EmbedderModelSpec.FILES]: the embedder weights and the mmproj companion).
 *
 * Mirrors `ModelDownloadStore` but is fully decoupled from it: the embedder lives in its own
 * directory (`<filesDir>/models/embedder/`) and its own prefs, so touching this pipeline can never
 * affect the chat/local LLM download or the speech-model download. The pure checks
 * ([LocalModelReadiness], [LocalModelStateResolver], [ModelDownloadState]) are shared.
 *
 * File layout: `<filesDir>/models/embedder/<fileName>` for each verified file and the same path +
 * `.part` for the in-flight download.
 */
@Singleton
class EmbedderDownloadStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val initial = load()
    private val _state = MutableStateFlow(initial)
    private var lastPersistedToken: String = token(initial)

    /** Live state of the current/last embedder download; collect it to show progress. */
    val state: StateFlow<ModelDownloadState> = _state.asStateFlow()

    /** Absolute path of one verified artifact, e.g. `<filesDir>/models/embedder/<fileName>`. */
    fun targetFile(file: EmbedderModelSpec.ModelFile): File = File(modelsDir(), file.fileName)

    /** Temporary file the worker streams into; kept on failure and cancellation so it can resume. */
    fun partialFile(file: EmbedderModelSpec.ModelFile): File =
        File(modelsDir(), file.fileName + PART_SUFFIX)

    /** Absolute path of the verified embedder weights. */
    fun targetFile(): File = targetFile(EmbedderModelSpec.MODEL)

    /** Temporary file the worker streams the embedder weights into. */
    fun partialFile(): File = partialFile(EmbedderModelSpec.MODEL)

    /** The downloaded weights as a [File] when a COMPLETE file (exact expected size) exists, else `null`. */
    fun resolveModel(): File? =
        targetFile(EmbedderModelSpec.MODEL)
            .takeIf { LocalModelReadiness.isComplete(it, EmbedderModelSpec.MODEL.sizeBytes) }

    /** The downloaded mmproj companion as a [File] when a COMPLETE file exists, else `null`. */
    fun resolveMmproj(): File? =
        targetFile(EmbedderModelSpec.MMPROJ)
            .takeIf { LocalModelReadiness.isComplete(it, EmbedderModelSpec.MMPROJ.sizeBytes) }

    /** True only when BOTH required artifacts are present at their exact expected sizes. */
    fun isReady(): Boolean = LocalModelReadiness.allComplete(requiredFiles())

    private fun requiredFiles(): List<Pair<File, Long>> =
        EmbedderModelSpec.FILES.map { targetFile(it) to it.sizeBytes }

    private fun modelsDir(): File = File(context.filesDir, MODELS_DIR)

    /** Publishes a new state and persists it (cheaply: identical persisted tokens are skipped). */
    @Synchronized
    fun setState(newState: ModelDownloadState) {
        _state.value = newState
        val newToken = token(newState)
        if (newToken == lastPersistedToken) return
        lastPersistedToken = newToken
        prefs.edit().apply {
            when (newState) {
                is ModelDownloadState.Done -> {
                    putString(KEY_STATUS, LocalModelStateResolver.STATUS_DONE)
                    putString(KEY_PATH, newState.path)
                    remove(KEY_REASON)
                }

                is ModelDownloadState.Failed -> {
                    putString(KEY_STATUS, LocalModelStateResolver.STATUS_FAILED)
                    putString(KEY_REASON, newState.reason)
                }

                ModelDownloadState.NotDownloaded, ModelDownloadState.Idle ->
                    remove(KEY_STATUS).remove(KEY_REASON).remove(KEY_PATH)

                is ModelDownloadState.Downloading, ModelDownloadState.Verifying ->
                    putString(KEY_STATUS, STATUS_ACTIVE).remove(KEY_REASON)
            }
        }.apply()
    }

    /** Convenience for the worker: sets [ModelDownloadState.Downloading] with explicit counters. */
    fun setDownloading(downloadedBytes: Long, totalBytes: Long) =
        setState(ModelDownloadState.Downloading(downloadedBytes, totalBytes))

    /** Convenience for the worker: records an honest failure [reason]. */
    fun setFailed(reason: String) = setState(ModelDownloadState.Failed(reason))

    /**
     * Re-derives the honest state from the FILES on disk. An in-flight download/verify is left
     * untouched so a running worker is never disturbed.
     */
    @Synchronized
    fun reconcile() {
        when (_state.value) {
            is ModelDownloadState.Downloading, ModelDownloadState.Verifying -> return
            else -> Unit
        }
        val honest = load()
        if (honest != _state.value) setState(honest)
    }

    private fun load(): ModelDownloadState = LocalModelStateResolver.resolve(
        filesComplete = isReady(),
        storedStatus = prefs.getString(KEY_STATUS, null),
        storedReason = prefs.getString(KEY_REASON, null),
        donePath = targetFile().absolutePath,
    )

    private fun token(state: ModelDownloadState): String = when (state) {
        ModelDownloadState.Idle -> "idle"
        ModelDownloadState.NotDownloaded -> "not_downloaded"
        is ModelDownloadState.Downloading -> "downloading"
        ModelDownloadState.Verifying -> "verifying"
        is ModelDownloadState.Done -> "done:${state.path}"
        is ModelDownloadState.Failed -> "failed:${state.reason}"
    }

    companion object {
        const val PREFS_FILE = "andharness_embedder_model_download"

        /** App-private subdirectory holding the embedder artifacts. */
        const val MODELS_DIR = "models/embedder"
        const val PART_SUFFIX = ".part"

        private const val KEY_STATUS = "embedder_download_status"
        private const val KEY_PATH = "embedder_download_path"
        private const val KEY_REASON = "embedder_download_reason"

        private const val STATUS_ACTIVE = "active"
    }
}
