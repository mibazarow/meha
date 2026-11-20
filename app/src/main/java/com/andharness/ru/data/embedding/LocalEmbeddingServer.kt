package com.andharness.ru.data.embedding

import android.content.Context
import android.util.Log
import com.andharness.ru.domain.embedding.EmbeddingServer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runs the packaged llama.cpp server (`libllamaserver.so`) a SECOND time — a dedicated process for
 * EmbeddingGemma 2 embeddings — on its own loopback port, so it never interferes with the chat/local
 * LLM server (which owns port 8080).
 *
 * Weights are never bundled: the embedder + mmproj are resolved from [EmbedderDownloadStore]
 * (downloaded by [EmbedderDownloadWorker]). If either file is missing the server refuses to start.
 *
 * llama.cpp server flags (see https://github.com/ggml-org/llama.cpp/tree/master/tools/server):
 * - `--model <embedder.gguf>` / `--mmproj <mmproj.gguf>` — the two required artifacts;
 * - `--embeddings --pooling mean` — enable the embeddings endpoint and mean pooling (required for the
 *   OpenAI-compatible response; pooling type `none` is rejected by llama.cpp for `/v1/embeddings`);
 * - `--ctx-size 2048` — the embedder's working context (its per-input token budget);
 * - `--batch-size 2048 --ubatch-size 2048` — raise BOTH the logical and the physical batch to the
 *   context size. llama-server's defaults are `-b 2048` but `-ub 512`, and an embeddings request
 *   processes its WHOLE input in one physical batch, so the stock `-ub 512` rejected any text over
 *   512 tokens with `HTTP 500 … input (N tokens) is too large to process … batch size: 512` (the
 *   document chunks are ~500–950 tokens, so every PDF chunk failed and the PDF never indexed).
 *   Setting `-ub` to the 2048-token context lets any chunk up to the model's own limit embed;
 *   `-ub` may not exceed `-b`, which is why both are pinned to [CTX_SIZE];
 * - `--threads <n>` — capped at 6 so the UI and system keep CPU headroom;
 * - `--no-webui`, `-np 1` — no browser UI and a single server slot.
 *
 * Every failure path is caught and logged; nothing throws and nothing runs on the main thread.
 */
@Singleton
class LocalEmbeddingServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: EmbedderDownloadStore,
) : EmbeddingServer {

    private var process: Process? = null
    private val startMutex = Mutex()

    override val baseUrl: String get() = "http://127.0.0.1:$PORT/v1"

    /** Server root (no `/v1`), e.g. `http://127.0.0.1:8090`. */
    private val rootUrl: String get() = "http://127.0.0.1:$PORT"

    @Synchronized
    override fun isRunning(): Boolean = process?.isAlive == true

    override suspend fun ensureStarted(): Boolean = startMutex.withLock {
        withContext(Dispatchers.IO) {
            if (isRunning()) return@withContext true
            val model = store.resolveModel()
            if (model == null) {
                Log.w(TAG, "Embedder weights are not downloaded; refusing to start")
                return@withContext false
            }
            val mmproj = store.resolveMmproj()
            if (mmproj == null) {
                Log.w(TAG, "Embedder mmproj is not downloaded; refusing to start")
                return@withContext false
            }
            startProcess(model, mmproj)
        }
    }

    private fun startProcess(model: File, mmproj: File): Boolean {
        // The packaged server is an arm64 executable shipped as a native lib; its absolute path is
        // the app's native library directory (same mechanism as LocalLlmServer).
        val exe = File(context.applicationInfo.nativeLibraryDir, EXECUTABLE_NAME)
        if (!exe.isFile) {
            Log.e(TAG, "Embedder server executable missing: ${exe.absolutePath}")
            return false
        }
        return try {
            val cmd = listOf(
                exe.absolutePath,
                "--model", model.absolutePath,
                "--mmproj", mmproj.absolutePath,
                "--embeddings",
                "--pooling", "mean",
                "--host", "127.0.0.1",
                "--port", PORT.toString(),
                "--ctx-size", CTX_SIZE.toString(),
                "--batch-size", CTX_SIZE.toString(),
                "--ubatch-size", CTX_SIZE.toString(),
                "--threads", THREADS.toString(),
                "--no-webui",
                "-np", "1",
            )
            Log.i(TAG, "Starting embedding server: ${cmd.joinToString(" ")}")
            val proc = ProcessBuilder(cmd)
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .start()
            process = proc
            drain(proc)
            waitReady()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start embedding server", e)
            process = null
            false
        }
    }

    @Synchronized
    override fun stop() {
        process?.let {
            runCatching { it.destroy() }
            runCatching { it.waitFor() }
        }
        process = null
        // Stopping never deletes the downloaded model files (mirrors LocalLlmServer / BUG-049).
    }

    override suspend fun embedText(text: String): FloatArray? =
        postEmbedding(EmbeddingApi.textRequestJson(text))

    override suspend fun embedImage(bytes: ByteArray): FloatArray? =
        postEmbedding(EmbeddingApi.imageRequestJson(EmbeddingApi.dataUrl(bytes)))

    /** POSTs one embeddings request off the main thread and parses `data[0].embedding`. */
    private suspend fun postEmbedding(jsonBody: String): FloatArray? = withContext(Dispatchers.IO) {
        try {
            if (!ensureStarted()) {
                Log.w(TAG, "Embedding request skipped: server is not running")
                return@withContext null
            }
            val conn = (URL(EmbeddingApi.embeddingsUrl(rootUrl)).openConnection() as HttpURLConnection)
                .apply {
                    connectTimeout = HTTP_TIMEOUT_MS
                    readTimeout = HTTP_TIMEOUT_MS
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                }
            try {
                // HttpURLConnection requires the body to be written before reading the response:
                // https://developer.android.com/reference/java/net/HttpURLConnection
                conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }
                if (code !in 200..299) {
                    Log.w(TAG, "Embedding request failed: HTTP $code ${body?.take(BODY_LOG_CHARS)}")
                    return@withContext null
                }
                val embedding = EmbeddingApi.parseFirstEmbedding(body)
                if (embedding == null) Log.w(TAG, "Embedding response had no data[0].embedding")
                embedding
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Embedding request failed", e)
            null
        }
    }

    private fun drain(proc: Process) {
        Thread {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line -> Log.v(TAG, line) }
            }
        }.apply { isDaemon = true; name = "embed-server-log" }.start()
    }

    /** Polls the server health endpoint until it answers or the timeout elapses. */
    private fun waitReady(): Boolean {
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (process?.isAlive != true) return false
            if (ping()) return true
            Thread.sleep(1000)
        }
        Log.w(TAG, "Embedding server did not become ready in ${START_TIMEOUT_MS / 1000}s")
        return false
    }

    private fun ping(): Boolean = try {
        val conn = (URL("$rootUrl/health").openConnection() as HttpURLConnection).apply {
            connectTimeout = 1500
            readTimeout = 1500
            requestMethod = "GET"
        }
        val code = conn.responseCode
        conn.disconnect()
        code == 200
    } catch (e: Exception) {
        false
    }

    private companion object {
        const val TAG = "Andharness"
        const val EXECUTABLE_NAME = "libllamaserver.so"

        /** Dedicated port; the chat/local LLM server owns 8080, so the two never collide. */
        const val PORT = 8090
        const val CTX_SIZE = 2048

        /**
         * Worker threads capped at 6 for CPU headroom, matching LocalLlmServer. On an 8-core SoC,
         * saturating all cores starves the app (observed as an ANR); a slower embed is better than a
         * starved UI. `availableProcessors()` — https://developer.android.com/reference/java/lang/Runtime#availableProcessors()
         */
        val THREADS: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

        /** Model load + mmproj load can take a while on a cold start. */
        const val START_TIMEOUT_MS = 120_000L

        /** Bounded HTTP budget for one embeddings call. */
        const val HTTP_TIMEOUT_MS = 60_000

        /** How much of a failed response body to log. */
        const val BODY_LOG_CHARS = 200
    }
}
