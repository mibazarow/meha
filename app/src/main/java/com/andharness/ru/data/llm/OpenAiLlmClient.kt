package com.andharness.ru.data.llm

import android.util.Log
import com.andharness.ru.domain.serialization.AppJson
import com.andharness.ru.domain.agent.LlmClient
import com.andharness.ru.domain.agent.LlmContentPart
import com.andharness.ru.domain.agent.LlmEvent
import com.andharness.ru.domain.agent.LlmMessage
import com.andharness.ru.domain.agent.LlmRequest
import com.andharness.ru.domain.agent.LlmTool
import com.andharness.ru.domain.agent.LlmToolCall
import com.andharness.ru.domain.llm.LlmAuthConfig
import com.andharness.ru.domain.llm.openAiChatCompletionsUrl
import com.andharness.ru.domain.settings.LlmSettings
import com.andharness.ru.domain.settings.LlmSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenAI-compatible streaming [LlmClient].
 *
 * Sends a chat-completions request with `stream: true` and parses the
 * `text/event-stream` response line by line: `data:` chunks carrying
 * `choices[0].delta.{content,tool_calls}` and a `finish_reason`, terminated by the
 * `[DONE]` sentinel.
 *
 * References:
 * - Chat completions API / streaming: https://platform.openai.com/docs/api-reference/chat/create
 * - Streaming chunks / delta / finish_reason / `[DONE]`: https://platform.openai.com/docs/api-reference/chat/streaming
 * - OkHttp `Request` / `OkHttpClient.Builder`: https://square.github.io/okhttp/4/okhttp/okhttp3/
 * - kotlinx.serialization: https://kotlinlang.org/docs/serialization.html
 */
@Singleton
class OpenAiLlmClient @Inject constructor(
    private val settingsStore: LlmSettingsStore,
    private val okHttpClient: OkHttpClient,
) : LlmClient {

    private val json = AppJson.Lenient

    /** The in-flight HTTP call, cancelled when the collecting coroutine is cancelled. */
    @Volatile
    private var activeCall: Call? = null

    /**
     * Test seams: shorten the watchdog budgets so the abort path can be exercised without waiting
     * the real 1024 s idle budget / 60 min overall cap. Production always leaves them null and uses
     * [LlmTimeouts].
     */
    @Volatile
    internal var idleProgressTimeoutOverrideSec: Long? = null

    @Volatile
    internal var maxCompletionOverrideSec: Long? = null

    override fun complete(request: LlmRequest): Flow<LlmEvent> = flow {
        val settings = settingsStore.config.first()
        val apiKey = settingsStore.getApiKey()
        // Resolved once per run, like the key: the auth scheme (Bearer / custom header / query /
        // none) is applied to this chat request through LlmAuth, matching model discovery.
        val authConfig = settingsStore.getAuthConfig()

        if (settings.endpoint.isBlank()) {
            Log.w(TAG, "LLM error: Не задан адрес эндпоинта. (retryable=false)")
            emit(LlmEvent.Error("Не задан адрес эндпоинта.", false))
            return@flow
        }
        // The transport and the read-silence budget for the configured remote endpoint.
        val httpClient = okHttpClient
        // The actual per-read silence budget for the client, reported to the user verbatim so the
        // message can never drift from the OkHttp readTimeout (LlmTimeouts).
        val dataWaitTimeoutSec = LlmTimeouts.DATA_WAIT_TIMEOUT_SEC
        // apiKey and model are OPTIONAL: OpenAI-compatible servers (e.g. Ollama/llama.cpp/LM Studio)
        // often accept no Authorization header and default to the model currently loaded on the server.

        // The authoritative no-progress budget for this client: the OkHttp readTimeout above is
        // reset by ANY byte (keep-alive comments included), so a stalled stream that keeps sending
        // heartbeats can hang forever. This is the budget for a real content/tool-call delta.
        val idleProgressTimeoutSec = LlmTimeouts.IDLE_PROGRESS_TIMEOUT_SEC

        // The model that actually serves this call.
        val effectiveModel = settings.model

        var attempt = 0
        // If a provider does not support `reasoning_effort`, it answers 400; we then retry once
        // without the field so the mode setting can never break chatting.
        var skipReasoning = false
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            var shouldRetry = false
            // A retry is only safe while nothing was streamed yet: re-running after partial
            // content/tool-call output would duplicate an answer the user already saw.
            var emittedInAttempt = false
            // The UI needs ONE signal that reasoning started; the visible answer must never contain it.
            var reasoningSignalled = false
            // The external watchdog records the honest abort reason before cancelling the call, so
            // the IOException handler below can tell a stalled stream apart from a real network error.
            var watchdog: LlmCallWatchdog? = null
            try {
                val req = buildRequest(
                    settings = settings,
                    apiKey = apiKey,
                    authConfig = authConfig,
                    request = request,
                    includeReasoning = !skipReasoning,
                    model = effectiveModel
                )
                val call = httpClient.newCall(req)
                activeCall = call
                // Cancel the in-flight HTTP call when the collector is cancelled, so a blocking read
                // cannot keep draining the stream after the run was stopped.
                currentCoroutineContext().job.invokeOnCompletion { cause ->
                    if (cause is CancellationException) call.cancel()
                }

                try {
                    call.execute().use { response ->
                        if (response.isSuccessful) {
                            val source = response.body?.source()
                            if (source == null) {
                                Log.w(TAG, "LLM error: Пустой ответ сервера. (retryable=false)")
                                emit(LlmEvent.Error("Пустой ответ сервера.", false))
                                return@flow
                            }

                            val toolCallsAccumulator = LinkedHashMap<Int, ToolCallAccumulator>()
                            var doneEmitted = false

                            // No-progress watchdog. OkHttp's readTimeout counts ANY byte, so a
                            // server that keeps a stalled stream alive with SSE keep-alive
                            // comments/heartbeats would reset it forever and hang the turn. Only a
                            // REAL content/tool-call delta refreshes the idle budget, and a
                            // DEDICATED TIMER THREAD (not a coroutine in this scope) cancels the
                            // Call once that budget — or the overall wall-clock cap — is exhausted.
                            // Running it off-coroutine is what makes the abort independent of this
                            // collector: cancellation can no longer be defeated by a loop that
                            // never suspends or by `ensureActive()` never being reached.
                            val activeWatchdog = LlmCallWatchdog(
                                call = call,
                                idleProgressTimeoutSec =
                                    idleProgressTimeoutOverrideSec ?: idleProgressTimeoutSec,
                                maxCompletionSec =
                                    maxCompletionOverrideSec ?: LlmTimeouts.MAX_COMPLETION_SEC,
                            )
                            watchdog = activeWatchdog
                            activeWatchdog.start()

                            lines@ while (true) {
                                currentCoroutineContext().ensureActive()
                                val line = source.readUtf8Line() ?: break
                                if (line.isBlank()) continue
                                if (!line.startsWith("data:")) continue

                                val payload = line.removePrefix("data:").trim()
                                if (payload == "[DONE]") break

                                // A single malformed chunk must not abort an otherwise good stream.
                                val obj = runCatching { json.parseToJsonElement(payload) }
                                    .getOrNull() as? JsonObject ?: continue@lines

                                // Provider token accounting. It usually arrives as a usage-only chunk
                                // with `choices: []`, so it must be read BEFORE the choices guard
                                // below — otherwise the estimate/actual calibration never sees it.
                                parseUsage(obj)?.let { emit(it) }

                                val choices = obj["choices"]
                                if (choices !is JsonArray) continue
                                if (choices.isEmpty()) continue
                                val choice = choices[0]
                                if (choice !is JsonObject) continue

                                val delta = choice["delta"] as? JsonObject ?: JsonObject(emptyMap())
                                // Reasoning deltas (reasoning_content, DeepSeek-R1 style) are NOT shown
                                // as the assistant answer. They are, however, REAL progress: a model
                                // that thinks for a long time before the first visible token would
                                // otherwise be killed by the no-progress watchdog (~1024 s) mid-thought.
                                // A non-empty reasoning delta therefore refreshes the idle budget, while
                                // staying invisible to the user.
                                val reasoningDelta = delta["reasoning_content"] ?: delta["reasoning"]
                                if (reasoningDelta is JsonPrimitive && reasoningDelta.isString &&
                                    reasoningDelta.content.isNotEmpty()
                                ) {
                                    activeWatchdog.noteProgress()
                                    if (!reasoningSignalled) {
                                        reasoningSignalled = true
                                        emit(LlmEvent.Reasoning)
                                    }
                                }
                                delta["content"]?.let { content ->
                                    if (content is JsonPrimitive && content.isString) {
                                        activeWatchdog.noteProgress()
                                        emit(LlmEvent.Text(content.content))
                                        emittedInAttempt = true
                                    }
                                }

                                delta["tool_calls"]?.let { toolCalls ->
                                    if (toolCalls is JsonArray) {
                                        activeWatchdog.noteProgress()
                                        toolCallsLoop@ for (tc in toolCalls) {
                                            if (tc !is JsonObject) continue@toolCallsLoop
                                            val index = (tc["index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: continue@toolCallsLoop
                                            val acc = toolCallsAccumulator.getOrPut(index) { ToolCallAccumulator() }
                                            (tc["id"] as? JsonPrimitive)?.content?.let { acc.id = it }
                                            ((tc["function"] as? JsonObject)?.get("name") as? JsonPrimitive)?.content?.let { acc.name = it }
                                            ((tc["function"] as? JsonObject)?.get("arguments") as? JsonPrimitive)?.content?.let { acc.args.append(it) }
                                        }
                                    }
                                }

                                val finishReason = (choice["finish_reason"] as? JsonPrimitive)?.content
                                when (finishReason) {
                                    "tool_calls" -> {
                                        if (flushToolCalls(toolCallsAccumulator) { calls ->
                                                emit(LlmEvent.ToolCalls(calls))
                                            }
                                        ) {
                                            emittedInAttempt = true
                                        }
                                    }
                                    // Some OpenAI-compatible providers end a tool-call turn with
                                    // "stop" (or another terminal reason) instead of "tool_calls":
                                    // flush what was accumulated before finishing so nothing is lost.
                                    "stop", "length", "content_filter" -> {
                                        flushToolCalls(toolCallsAccumulator) { calls ->
                                            emit(LlmEvent.ToolCalls(calls))
                                            emittedInAttempt = true
                                        }
                                        emit(LlmEvent.Done)
                                        doneEmitted = true
                                    }
                                }
                            }

                            // Stream ended ([DONE] or EOF) without an explicit terminal reason: flush
                            // any accumulated tool calls and always signal Done, so the caller never
                            // has to treat a silent end as a successful completion.
                            flushToolCalls(toolCallsAccumulator) { calls ->
                                emit(LlmEvent.ToolCalls(calls))
                                emittedInAttempt = true
                            }
                            if (!doneEmitted) emit(LlmEvent.Done)
                            Log.d(TAG, "LLM stream OK")
                            return@flow
                        }

                        val code = response.code
                        val errorBody = response.body?.string().orEmpty().trim()
                        if (code >= 500) {
                            if (attempt < MAX_ATTEMPTS) {
                                delay(backoffMillis(attempt))
                                shouldRetry = true
                                return@use
                            } else {
                                Log.w(TAG, "LLM error: Ошибка API (${code}): $errorBody (retryable=true)")
                                emit(
                                    LlmEvent.Error(
                                        LlmErrorHints.withHint(
                                            "Ошибка API ($code): $errorBody",
                                            LlmErrorHints.forHttpStatus(code, errorBody),
                                        ),
                                        true,
                                    )
                                )
                                return@flow
                            }
                        } else if (code == 400 && !skipReasoning) {
                            // Most likely the provider rejected `reasoning_effort`. Retry without it.
                            skipReasoning = true
                            shouldRetry = true
                            Log.w(TAG, "LLM: провайдер отклонил reasoning_effort (400); повтор без параметра.")
                            return@use
                        } else {
                            Log.w(TAG, "LLM error: Ошибка API (${code}): $errorBody (retryable=false)")
                            emit(
                                LlmEvent.Error(
                                    LlmErrorHints.withHint(
                                        "Ошибка API ($code): $errorBody",
                                        LlmErrorHints.forHttpStatus(code, errorBody),
                                    ),
                                    false,
                                )
                            )
                            return@flow
                        }
                    }
                } finally {
                    watchdog?.stop()
                    if (activeCall === call) activeCall = null
                }
                if (shouldRetry) continue
            } catch (e: java.net.SocketTimeoutException) {
                val stalled = watchdog?.abortReason()
                if (stalled != null) {
                    // The watchdog cancelled the call first: report ITS reason, not the generic
                    // read-timeout text (the two can race when the call is cancelled mid-read).
                    Log.w(TAG, "LLM error: $stalled (watchdog, retryable=false)")
                    emit(LlmEvent.Error(LlmErrorHints.withHint(stalled, LlmErrorHints.TIMEOUT_HINT), false))
                    return@flow
                }
                // The stream produced no bytes for the read timeout (dataWaitTimeoutSec): the
                // model/server is silent or hung. Do NOT retry (each attempt would wait another
                // dataWaitTimeoutSec) — stop now and report honestly so the agent can tell the user
                // it did not succeed and why.
                Log.w(TAG, "LLM error: Модель не отвечает (таймаут ожидания данных, ${dataWaitTimeoutSec}s). (retryable=false)")
                emit(
                    LlmEvent.Error(
                        LlmErrorHints.withHint(
                            "Модель не отвечает уже более $dataWaitTimeoutSec секунд " +
                                "(таймаут ожидания данных). Задача остановлена.",
                            LlmErrorHints.TIMEOUT_HINT,
                        ),
                        false,
                    )
                )
                return@flow
            } catch (e: IOException) {
                val stalled = watchdog?.abortReason()
                if (stalled != null) {
                    // A stalled/over-long generation was aborted by the watchdog: it is an honest,
                    // non-retryable failure (the model made no real progress), never a network retry.
                    Log.w(TAG, "LLM error: $stalled (watchdog, retryable=false)")
                    emit(LlmEvent.Error(LlmErrorHints.withHint(stalled, LlmErrorHints.TIMEOUT_HINT), false))
                    return@flow
                }
                if (attempt < MAX_ATTEMPTS && !emittedInAttempt) {
                    delay(backoffMillis(attempt))
                    continue
                } else {
                    // Never re-stream after partial output: report the failure instead.
                    Log.w(TAG, "LLM error: Нет соединения с сервером: ${e.message} (retryable=${!emittedInAttempt})")
                    emit(
                        LlmEvent.Error(
                            LlmErrorHints.withHint(
                                "Нет соединения с сервером: ${e.message}",
                                LlmErrorHints.CONNECTION_HINT,
                            ),
                            !emittedInAttempt,
                        )
                    )
                    return@flow
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "LLM error: ${e.message ?: "Ошибка"} (retryable=false)")
                emit(LlmEvent.Error(e.message ?: "Ошибка", false))
                return@flow
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Emits the accumulated tool calls through [sink] and clears the accumulator. Returns true when
     * at least one complete call was emitted (a call missing an id or a name is dropped).
     */
    private suspend fun flushToolCalls(
        accumulator: LinkedHashMap<Int, ToolCallAccumulator>,
        sink: suspend (List<LlmToolCall>) -> Unit
    ): Boolean {
        val calls = accumulator.values.mapNotNull { it.toToolCall() }
        accumulator.clear()
        if (calls.isEmpty()) return false
        sink(calls)
        return true
    }

    private class ToolCallAccumulator {
        var id: String? = null
        var name: String? = null
        val args = StringBuilder()

        fun toToolCall(): LlmToolCall? {
            val id = id ?: return null
            val name = name ?: return null
            return LlmToolCall(id = id, name = name, arguments = args.toString())
        }
    }

    private suspend fun buildRequest(
        settings: LlmSettings,
        apiKey: String?,
        authConfig: LlmAuthConfig,
        request: LlmRequest,
        includeReasoning: Boolean,
        model: String,
    ): Request {
        val url = openAiChatCompletionsUrl(settings.endpoint)

        val body = buildJsonObject {
            if (model.isNotBlank()) {
                put("model", JsonPrimitive(model))
            }
            put("messages", buildJsonArray {
                request.messages.forEach { add(it.toJson()) }
            })
            if (request.tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    request.tools.forEach { add(it.toJson()) }
                })
            }
            if (includeReasoning) {
                // OpenAI-compatible reasoning control. "off" maps to "none" (widely used by
                // gateways to disable thinking); low/medium/high pass through. Providers that do
                // not support the field get a 400 and the caller retries without it.
                val effort = settings.reasoningEffort.ifBlank { "off" }
                put("reasoning_effort", JsonPrimitive(if (effort == "off") "none" else effort))
            }
            put("stream", true)
        }
        val requestBody = body.toString()
            .toRequestBody("application/json".toMediaType())

        val builder = Request.Builder()
            .url(LlmAuth.urlWithAuth(url, apiKey, authConfig))
            .header("Accept", "text/event-stream")
            .post(requestBody)
        LlmAuth.applyHeader(builder, apiKey, authConfig)
        return builder.build()
    }

    private fun LlmMessage.toJson(): JsonObject = buildJsonObject {
        put("role", JsonPrimitive(role))
        val parts = contentParts
        if (parts.isNullOrEmpty()) {
            put("content", JsonPrimitive(content.orEmpty()))
        } else {
            // Multimodal user message: `content` becomes an array of parts so an image attachment
            // reaches a vision model as inline content (§19.3).
            put("content", buildJsonArray {
                parts.forEach { part ->
                    when (part) {
                        is LlmContentPart.Text -> add(buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(part.text))
                        })
                        is LlmContentPart.ImageUrl -> add(buildJsonObject {
                            put("type", JsonPrimitive("image_url"))
                            putJsonObject("image_url") {
                                put("url", JsonPrimitive(part.dataUri))
                            }
                        })
                    }
                }
            })
        }
        toolCalls?.let { calls ->
            put("tool_calls", buildJsonArray {
                calls.forEach { call ->
                    add(buildJsonObject {
                        put("id", JsonPrimitive(call.id))
                        put("type", JsonPrimitive("function"))
                        putJsonObject("function") {
                            put("name", JsonPrimitive(call.name))
                            put("arguments", JsonPrimitive(call.arguments))
                        }
                    })
                }
            })
        }
        toolCallId?.let { put("tool_call_id", JsonPrimitive(it)) }
    }

    private fun LlmTool.toJson(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("function"))
        putJsonObject("function") {
            put("name", JsonPrimitive(name))
            put("description", JsonPrimitive(description))
            put("parameters", parameters)
        }
    }

    private fun backoffMillis(attempt: Int): Long = BASE_BACKOFF_MILLIS * (1L shl (attempt - 1))

    /**
     * Reads `usage.{prompt_tokens,completion_tokens}` from a stream chunk, or `null` when the chunk
     * carries none. Providers that report usage only with `stream_options.include_usage` simply never
     * produce it here — the calibration is then a no-op instead of an error.
     */
    private fun parseUsage(obj: JsonObject): LlmEvent.Usage? {
        val usage = obj["usage"] as? JsonObject ?: return null
        val prompt = (usage["prompt_tokens"] as? JsonPrimitive)
            ?.content?.toLongOrNull() ?: return null
        val completion = (usage["completion_tokens"] as? JsonPrimitive)
            ?.content?.toLongOrNull() ?: 0L
        return LlmEvent.Usage(promptTokens = prompt, completionTokens = completion)
    }


    companion object {
        private const val TAG = "Andharness"
        private const val MAX_ATTEMPTS = 4
        private const val BASE_BACKOFF_MILLIS = 500L
    }
}
