package com.andharness.ru.data.llm

/**
 * Single source of truth for the LLM streaming data-wait budgets (seconds).
 *
 * OkHttp's readTimeout is a per-read INACTIVITY budget, not a total response cap: it applies to each
 * individual read IO operation (and the TCP socket), so as long as the server keeps delivering
 * streamed chunks the stream stays open. These values are used BOTH as the OkHttp readTimeout and in
 * the user-facing "модель не отвечает уже более N секунд" message, so the two can never drift apart
 * again.
 *
 * - [DATA_WAIT_TIMEOUT_SEC] — remote OpenAI-compatible providers (see `di/NetworkModule.kt`).
 *   1024 s (~17 min), raised from 120 s so a slow model is no longer cut off prematurely.
 * - [IDLE_PROGRESS_TIMEOUT_SEC] — the no-progress watchdog: the maximum time WITHOUT a real
 *   content/tool-call delta before the stream is aborted. This is the authoritative bound: the OkHttp
 *   readTimeout counts ANY byte (including SSE keep-alive comments and heartbeats), so a server that
 *   dribbles keep-alives can reset it forever and hang the turn. It is set to the same 1024 s as the
 *   response budget so the watchdog can never abort a model that is merely silent (up to ~17 min)
 *   before its read budget is exhausted. Only a real delta (content, tool calls, or reasoning)
 *   refreshes it.
 * - [MAX_COMPLETION_SEC] — overall wall-clock cap for one completion (60 min), so even a stream that
 *   keeps making tiny progress can never run forever. It STRICTLY exceeds both data-wait budgets
 *   (3600 > 1024), so the overall cap can never fire before the response budget is exhausted.
 *
 * Reference (readTimeout applies to individual read IO operations, default 10 s):
 * https://square.github.io/okhttp/5.x/okhttp/okhttp3/-ok-http-client/-builder/read-timeout.html
 */
object LlmTimeouts {
    const val DATA_WAIT_TIMEOUT_SEC = 1024L
    const val IDLE_PROGRESS_TIMEOUT_SEC = 1024L
    const val MAX_COMPLETION_SEC = 3600L
}
