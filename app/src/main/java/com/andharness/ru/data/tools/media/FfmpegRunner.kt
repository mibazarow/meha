package com.andharness.ru.data.tools.media

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs one ffmpeg command through the bundled ffmpeg-kit build and reports an honest outcome.
 *
 * The command is passed as an **argument vector** (`FFmpegKit.executeWithArgumentsAsync`), not as a
 * shell string, so paths with spaces/quotes need no quoting. The call is bounded: the JNI work runs
 * asynchronously and this object waits on a latch with a hard timeout; if the deadline passes the
 * session is cancelled, so a pathological input can never hang the tool loop.
 *
 * References (ffmpeg-kit maintained fork, same `com.arthenica.ffmpegkit` API as upstream):
 * - `FFmpegKit.executeWithArgumentsAsync(String[], FFmpegSessionCompleteCallback)` and
 *   `FFmpegKit.cancel(long)` — https://github.com/ffmpegkit-maintained/ffmpeg
 * - `ReturnCode.isSuccess(ReturnCode)` — same API.
 */
internal object FfmpegRunner {

    /** Hard wall-clock budget for a single ffmpeg run. */
    const val TIMEOUT_SECONDS: Long = 900L

    /** Outcome of one ffmpeg invocation: [success] plus the tail of the log for the user. */
    data class Outcome(val success: Boolean, val message: String)

    /**
     * Runs `ffmpeg <arguments>` and blocks until it finishes, is cancelled on timeout, or the
     * session cannot be started. Never throws: any failure is returned as `Outcome(success=false, …)`.
     */
    fun run(arguments: List<String>, timeoutSeconds: Long = TIMEOUT_SECONDS): Outcome {
        if (arguments.isEmpty()) return Outcome(false, "Пустой набор аргументов ffmpeg.")
        val latch = CountDownLatch(1)
        val session = try {
            FFmpegKit.executeWithArgumentsAsync(arguments.toTypedArray()) { latch.countDown() }
        } catch (e: Throwable) {
            return Outcome(false, "ffmpeg недоступен: ${e.message ?: e::class.simpleName}")
        }
        val finished = try {
            latch.await(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            runCatching { FFmpegKit.cancel(session.sessionId) }
            Thread.currentThread().interrupt()
            return Outcome(false, "Обработка прервана.")
        }
        if (!finished) {
            runCatching { FFmpegKit.cancel(session.sessionId) }
            return Outcome(false, "ffmpeg превысил лимит времени (${timeoutSeconds}s) и был остановлен.")
        }
        val returnCode = session.returnCode
        if (ReturnCode.isSuccess(returnCode)) {
            return Outcome(true, session.output.orEmpty())
        }
        val log = (session.output ?: session.failStackTrace ?: "").trim()
        val tail = log.takeLast(600).ifBlank { "ffmpeg завершился с кодом $returnCode" }
        return Outcome(false, tail)
    }
}
