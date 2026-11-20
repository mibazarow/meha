package com.andharness.ru.data.llm

import com.andharness.ru.domain.common.Hashing
import com.andharness.ru.domain.llm.LlmAuthConfig
import com.andharness.ru.domain.llm.ModelsCatalog
import com.andharness.ru.domain.llm.llamaPropsUrls
import com.andharness.ru.domain.llm.normalizeEndpointBase
import com.andharness.ru.domain.llm.openAiModelsUrl
import com.andharness.ru.domain.settings.LlmSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Discovers the models offered by an OpenAI-compatible endpoint and the context window of each.
 *
 * Flow:
 * 1. `GET {baseUrl}/models` → ids + per-model context (`context_length` / `max_model_len` /
 *    `meta.n_ctx` / `top_provider.context_length`, see [ModelsCatalogParser]).
 * 2. For models still missing a context, `GET /props` once (llama.cpp
 *    `default_generation_settings.n_ctx`) as the endpoint-wide fallback.
 *
 * Models whose context is still unknown are left WITHOUT an entry (the app then falls back to a
 * fixed default / the optional manual override), so the app never guesses a provider's window.
 *
 * Results are cached in memory per endpoint (trimmed of a trailing slash), per API-key fingerprint
 * AND per auth-config fingerprint, because many providers return a different (or auth-gated)
 * catalog depending on the key and how it is attached: a catalog fetched without a key must never
 * be served after the user enters one (or switches to a custom header/query), and vice versa. The
 * key fingerprint is a short non-reversible hash, so the raw key never lives in the cache map.
 * Failures are NOT cached so «Обновить список» can retry; a valid-but-empty list is returned
 * honestly instead of throwing.
 *
 * The auth scheme itself is applied through [LlmAuth] (Bearer / custom header / query param / none)
 * so discovery matches exactly how the chat request authenticates.
 *
 * References:
 * - OpenAI list models: https://platform.openai.com/docs/api-reference/models/list
 * - OkHttp requests/timeouts: https://square.github.io/okhttp/4/okhttp/okhttp3/OkHttpClient.Builder/
 * - llama.cpp server `/props`: https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md
 * - HTTP 401 (auth required): https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/401
 * - HTTP 403 (forbidden): https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/403
 */
@Singleton
class ModelsCatalogClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val settingsStore: LlmSettingsStore,
) {

    private data class HttpResult(val code: Int, val body: String) {
        val isSuccessful: Boolean get() = code in 200..299
    }

    /**
     * Cache identity: the endpoint, a non-reversible [keyFingerprint] of the API key and the
     * [authFingerprint] of the configured scheme, so a catalog discovered with one key (or none) or
     * under a different auth style is never reused for another.
     */
    private data class CacheKey(
        val endpoint: String,
        val keyFingerprint: String,
        val authFingerprint: String,
    )

    /** Bounded client: discovery must never hang the Settings screen. */
    private val httpClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(DISCOVERY_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(DISCOVERY_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .callTimeout(DISCOVERY_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
    }

    private val cache = ConcurrentHashMap<CacheKey, ModelsCatalog>()

    /**
     * Fetches the model catalog for [baseUrl]. Returns a [ModelsCatalog] whose [ModelsCatalog.error]
     * is set on any honest failure (network, non-2xx, unparseable body, empty list).
     *
     * @param force bypass the in-memory cache (used by «Обновить список»).
     */
    suspend fun fetch(
        baseUrl: String,
        apiKey: String? = null,
        force: Boolean = false,
    ): ModelsCatalog = withContext(Dispatchers.IO) {
        val endpoint = normalizeEndpointBase(baseUrl)
        if (endpoint.isBlank()) {
            return@withContext ModelsCatalog(error = "Не задан адрес эндпоинта.")
        }
        val authConfig = runCatching { settingsStore.getAuthConfig() }
            .getOrDefault(LlmAuthConfig.DEFAULT)
        val cacheKey = CacheKey(endpoint, keyFingerprint(apiKey), authFingerprint(authConfig))
        if (!force) {
            cache[cacheKey]?.takeIf { it.models.isNotEmpty() }?.let { return@withContext it }
        }

        val listResult = runCatching { get(openAiModelsUrl(endpoint), apiKey, authConfig) }.getOrElse { e ->
            return@withContext ModelsCatalog(
                error = "Не удалось получить список моделей: ${e.readableMessage()}"
            )
        }
        if (!listResult.isSuccessful) {
            val message = when (listResult.code) {
                401 -> "Endpoint вернул HTTP 401 — API-ключ не задан или неверный. " +
                    "Проверьте ключ и повторите."
                403 -> "Endpoint вернул HTTP 403 — доступ запрещён. " +
                    "Проверьте API-ключ и права доступа."
                else -> "Endpoint вернул ошибку HTTP ${listResult.code} для /models."
            }
            return@withContext ModelsCatalog(error = message)
        }

        val parsed = ModelsCatalogParser.parseModels(listResult.body)
        if (parsed.malformed) {
            return@withContext ModelsCatalog(
                error = "Не удалось разобрать ответ /models (не JSON)."
            )
        }
        if (parsed.models.isEmpty()) {
            return@withContext ModelsCatalog(
                error = "Endpoint не вернул список моделей — введите имя вручную."
            )
        }

        // Only query `/props` when some model actually lacks a per-model context.
        val missing = parsed.models.any { parsed.contextById[it] == null }
        val endpointFallback = if (missing) fetchPropsFallback(endpoint, apiKey, authConfig) else null

        val contextById = parsed.models.mapNotNull { id ->
            val context = parsed.contextById[id] ?: endpointFallback
            context?.let { id to it }
        }.toMap()

        val catalog = ModelsCatalog(
            models = parsed.models,
            contextById = contextById,
            endpointContextFallback = endpointFallback,
        )
        cache[cacheKey] = catalog
        catalog
    }

    /**
     * Last successfully cached catalog for [baseUrl], if any, regardless of which key it was
     * fetched with. Best-effort lookup for callers that only know the endpoint; [fetch] itself
     * always matches on the full endpoint + key fingerprint identity.
     */
    fun cached(baseUrl: String): ModelsCatalog? {
        val endpoint = normalizeEndpointBase(baseUrl)
        return cache.entries
            .firstOrNull { it.key.endpoint == endpoint }
            ?.value
            ?.takeIf { it.models.isNotEmpty() }
    }

    private fun fetchPropsFallback(
        endpoint: String,
        apiKey: String?,
        authConfig: LlmAuthConfig,
    ): Long? {
        for (url in llamaPropsUrls(endpoint)) {
            val result = runCatching { get(url, apiKey, authConfig) }.getOrNull() ?: continue
            if (!result.isSuccessful) continue
            ModelsCatalogParser.parseProps(result.body)?.let { return it }
        }
        return null
    }

    /**
     * Performs a GET with the configured auth scheme. The URL is rewritten only for
     * [com.andharness.ru.domain.llm.LlmAuthStyle.QUERY]; a blank/absent key never adds any auth, so
     * keyless self-hosted servers keep working. See [LlmAuth].
     */
    private fun get(url: String, apiKey: String?, authConfig: LlmAuthConfig): HttpResult {
        val builder = Request.Builder()
            .url(LlmAuth.urlWithAuth(url, apiKey, authConfig))
            .get()
        LlmAuth.applyHeader(builder, apiKey, authConfig)
        httpClient.newCall(builder.build()).execute().use { response ->
            return HttpResult(response.code, response.body?.string().orEmpty())
        }
    }

    private fun Throwable.readableMessage(): String =
        (this as? IOException)?.message ?: (message ?: this::class.java.simpleName)

    /**
     * Short non-reversible fingerprint of [apiKey] (SHA-256, hex, truncated), shared with the
     * audit chain and the Settings screen. Never store or log the raw key. A blank/absent key maps
     * to [Hashing.NO_KEY_FINGERPRINT] so keyless local servers share one cache identity.
     */
    private fun keyFingerprint(apiKey: String?): String = Hashing.keyFingerprint(apiKey)

    /**
     * Stable fingerprint of the auth scheme: its style plus the configured header/query names. It
     * never contains the credential (only the names the user chose), so switching style or name
     * invalidates the cached catalog without putting the raw key in the cache map.
     */
    private fun authFingerprint(config: LlmAuthConfig): String =
        "${config.style.storageValue}:${config.headerName.trim()}:${config.queryParam.trim()}"

    companion object {
        const val DISCOVERY_CONNECT_TIMEOUT_SEC = 10L
        const val DISCOVERY_READ_TIMEOUT_SEC = 20L
        const val DISCOVERY_CALL_TIMEOUT_SEC = 25L
    }
}
