package com.andharness.ru.data.communications.speech

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the bundled whisper.cpp v1.9.4 `whisper-cli` (`libwhispercli.so`, an arm64 **executable**
 * shipped in `jniLibs` and extracted to `nativeLibraryDir`) to transcribe a 16 kHz mono PCM16 WAV
 * into timestamped [WhisperSegment]s.
 *
 * The binary is resolved from the app's extracted native libraries:
 * `File(context.applicationInfo.nativeLibraryDir, "libwhispercli.so")`.
 * `useLegacyPackaging = true` in `app/build.gradle.kts` forces native libs to be extracted so they
 * can actually be spawned (an APK-mapped library cannot be executed).
 *
 * Robustness / bounded execution:
 * - stdout and stderr are drained on daemon threads so the child can never block on a full pipe;
 * - `Process.waitFor(timeout, unit)` bounds the run; on timeout the process is `destroy()`ed, then
 *   `destroyForcibly()`ed after a grace period. The JSON output file is always removed in `finally`;
 * - the timeout is computed from the WAV duration (32 bytes/ms for 16 kHz mono PCM16), scaled by
 *   [TIMEOUT_FACTOR] to tolerate first-run model loading, and capped at [MAX_TIMEOUT_MS];
 * - worker threads are capped to `coerceIn(1, 4)` to leave CPU headroom for the rest of the device;
 * - failures are returned as `Result.failure` with an honest message plus a captured stderr tail;
 *   coroutine cancellation is rethrown.
 *
 * References (developer.android.com / official docs):
 * - `ProcessBuilder` / `Process.waitFor(long, TimeUnit)` / `Process.destroyForcibly()` /
 *   `Process.isAlive()` (all API 26+, minSdk 31) —
 *   https://developer.android.com/reference/java/lang/ProcessBuilder
 *   https://developer.android.com/reference/java/lang/Process
 * - `Context.getApplicationInfo().nativeLibraryDir` —
 *   https://developer.android.com/reference/android/content/pm/ApplicationInfo#nativeLibraryDir
 * - whisper.cpp CLI flags & JSON schema (pinned revision) —
 *   https://github.com/ggml-org/whisper.cpp/blob/927cfce34f31707e17f2bff35c349632fb9e2c3a/examples/cli/cli.cpp
 */
@Singleton
class WhisperTranscriber @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Transcribes [wav] (16 kHz mono PCM16) with [model], optionally using a Silero VAD [vadModel].
     * Runs on [Dispatchers.IO]. Returns the ordered segments, or a failure with a user-facing
     * Russian message (and the stderr tail for diagnostics). Never throws except on cancellation.
     */
    suspend fun transcribe(
        wav: File,
        model: File,
        vadModel: File? = null,
        language: String = "auto",
        onProgress: ((String) -> Unit)? = null,
    ): Result<List<WhisperSegment>> = withContext(Dispatchers.IO) {
        try {
            Result.success(run(wav, model, vadModel, language, onProgress))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    private fun run(
        wav: File,
        model: File,
        vadModel: File?,
        language: String,
        onProgress: ((String) -> Unit)?,
    ): List<WhisperSegment> {
        if (!wav.isFile || wav.length() <= 0L) {
            throw IllegalStateException("Аудиофайл не найден или пуст: ${wav.name}")
        }
        if (!model.isFile) {
            throw IllegalStateException("Модель распознавания не найдена: ${model.name}")
        }
        if (vadModel != null && !vadModel.isFile) {
            throw IllegalStateException("Модель VAD не найдена: ${vadModel.name}")
        }

        val exe = File(context.applicationInfo.nativeLibraryDir, EXECUTABLE_NAME)
        if (!exe.isFile) {
            throw IllegalStateException("Движок распознавания не найден: ${exe.absolutePath}")
        }
        if (!exe.canExecute() && !exe.setExecutable(true) && !exe.canExecute()) {
            throw IllegalStateException("Движок распознавания не исполняемый: ${exe.absolutePath}")
        }

        val workDir = File(context.cacheDir, WORK_DIR).apply { mkdirs() }
        val outPrefix = File(workDir, "whisper-${System.nanoTime()}").absolutePath
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(MIN_THREADS, MAX_THREADS)
        val args = WhisperArgs.buildArgs(
            modelPath = model.absolutePath,
            wavPath = wav.absolutePath,
            outPrefix = outPrefix,
            threads = threads,
            language = language,
            vadModelPath = vadModel?.absolutePath,
        )
        val cmd = listOf(exe.absolutePath) + args
        val timeoutMs = timeoutMsFor(wav)
        val stderrTail = Tail(STDERR_TAIL_LINES)

        Log.i(TAG, "Starting whisper-cli: ${cmd.joinToString(" ")}")
        var process: Process? = null
        try {
            process = ProcessBuilder(cmd)
                .directory(workDir)
                .redirectErrorStream(false)
                .start()

            val stdoutDrain = drain(process.inputStream) { line -> onProgress?.invoke(line) }
            val stderrDrain = drain(process.errorStream) { line ->
                stderrTail.add(line)
                onProgress?.invoke(line)
            }

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)
                }
                throw IllegalStateException(
                    "Распознавание превысило лимит времени (${timeoutMs / 1000} с).",
                )
            }

            stdoutDrain.join(DRAIN_JOIN_MS)
            stderrDrain.join(DRAIN_JOIN_MS)

            val exit = process.exitValue()
            if (exit != 0) {
                throw IllegalStateException(
                    "whisper-cli завершился с кодом $exit. ${stderrTail.render()}".trim(),
                )
            }

            val jsonFile = File("$outPrefix.json")
            if (!jsonFile.isFile) {
                throw IllegalStateException(
                    "whisper-cli не создал файл результата: ${jsonFile.name}. ${stderrTail.render()}"
                        .trim(),
                )
            }
            return WhisperJsonParser.parse(jsonFile.readText())
        } finally {
            process?.let { proc ->
                if (proc.isAlive) {
                    runCatching { proc.destroy() }
                    if (!runCatching { proc.waitFor(DESTROY_GRACE_MS, TimeUnit.MILLISECONDS) }
                            .getOrDefault(false)
                    ) {
                        runCatching { proc.destroyForcibly() }
                    }
                }
            }
            // whisper-cli with -oj writes <outPrefix>.json (and may leave a .txt sibling).
            runCatching { File("$outPrefix.json").delete() }
            runCatching { File("$outPrefix.txt").delete() }
        }
    }

    /**
     * Wall-clock budget: 16 kHz mono PCM16 is 32 bytes/ms, so the WAV length yields the media
     * duration. Whisper on a small SoC can run several times slower than real time, hence
     * [TIMEOUT_FACTOR]; the result is capped at [MAX_TIMEOUT_MS].
     */
    private fun timeoutMsFor(wav: File): Long {
        val durationMs = wav.length() / BYTES_PER_MS
        return (BASE_TIMEOUT_MS + durationMs * TIMEOUT_FACTOR).coerceIn(BASE_TIMEOUT_MS, MAX_TIMEOUT_MS)
    }

    /** Reads [stream] to EOF on a daemon thread (prevents the child's pipe from filling up). */
    private fun drain(stream: InputStream, sink: (String) -> Unit): Thread = Thread {
        runCatching {
            stream.bufferedReader().use { reader ->
                reader.forEachLine { line -> sink(line) }
            }
        }
    }.apply {
        isDaemon = true
        name = "whisper-cli-drain"
        start()
    }

    /** Bounded ring buffer of the child's stderr, so diagnostics never grow without bound. */
    private class Tail(private val maxLines: Int) {
        private val lines = ArrayDeque<String>()

        @Synchronized
        fun add(line: String) {
            lines.addLast(line)
            while (lines.size > maxLines) lines.removeFirst()
        }

        @Synchronized
        fun render(): String = lines.joinToString("\n")
    }

    private companion object {
        const val TAG = "Andharness"
        const val EXECUTABLE_NAME = "libwhispercli.so"
        const val WORK_DIR = "whisper"
        const val MIN_THREADS = 1
        const val MAX_THREADS = 4

        /** Bytes per millisecond of 16 kHz mono PCM16 audio (16_000 samples/s * 2 bytes / 1000). */
        const val BYTES_PER_MS = 32L
        const val BASE_TIMEOUT_MS = 30_000L
        const val TIMEOUT_FACTOR = 6L
        const val MAX_TIMEOUT_MS = 10 * 60_000L
        const val DESTROY_GRACE_MS = 2_000L
        const val DRAIN_JOIN_MS = 1_000L
        const val STDERR_TAIL_LINES = 40
    }
}
