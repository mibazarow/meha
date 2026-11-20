package com.andharness.ru.data.vision

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.andharness.ru.data.llm.LlmAuth
import com.andharness.ru.domain.llm.LlmAuthConfig
import com.andharness.ru.domain.settings.LlmSettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects whether the currently configured LLM accepts images (update.md §3).
 *
 * Order of resolution: allowlist → fresh cache → one bounded probe (≈15 s) → honest UNKNOWN.
 * The probe result is cached outside Room (in [SharedPreferences], like the OSINT cache, so the
 * Phase 4 Room migration numbering stays untouched) keyed by `(model, endpoint)` with a timestamp.
 * A model/endpoint change or the `vision_check` tool forces a fresh probe; an unreachable endpoint
 * leaves the state UNKNOWN and is deliberately **not** cached, so it will be tried again.
 */

@Singleton
class VisionCapability @Inject constructor(
    @ApplicationContext context: Context,
    private val settingsStore: LlmSettingsStore,
    private val okHttpClient: OkHttpClient,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** Convenience for the attachment path: true only when Vision is definitively supported. */
    suspend fun supportsVision(): Boolean = resolve().supportsVision

    /**
     * Resolves the current model's Vision support. When [forceProbe] is true (the `vision_check`
     * tool) the cache and allowlist short-circuits are bypassed and a real probe is attempted.
     */
    suspend fun resolve(forceProbe: Boolean = false): VisionState = withContext(Dispatchers.IO) {
        val settings = settingsStore.config.first()
        val model = settings.model.trim()
        val endpoint = settings.endpoint.trim()

        val allowlisted = VisionPolicy.allowlistHit(model)
        if (!forceProbe && allowlisted != null) {
            return@withContext VisionState(
                support = VisionSupport.SUPPORTED,
                source = VisionSource.ALLOWLIST,
                model = model,
                endpoint = endpoint,
                detail = "модель «$model» совпала с известной vision-моделью «$allowlisted».",
            )
        }

        val now = System.currentTimeMillis()
        val cached = if (forceProbe) null else cachedEntry(model, endpoint)
        if (cached != null && VisionPolicy.isFresh(cached.checkedAt, now)) {
            return@withContext VisionState(
                support = if (cached.supported) VisionSupport.SUPPORTED else VisionSupport.UNSUPPORTED,
                source = VisionSource.CACHE,
                model = model,
                endpoint = endpoint,
                detail = "сохранённый результат пробы (${describeAge(now - cached.checkedAt)} назад).",
            )
        }

        if (model.isBlank()) {
            return@withContext VisionState(
                VisionSupport.UNKNOWN, VisionSource.UNKNOWN, model, endpoint,
                "не выбрана модель — определить поддержку изображений нельзя.",
            )
        }
        if (endpoint.isBlank()) {
            return@withContext VisionState(
                VisionSupport.UNKNOWN, VisionSource.UNKNOWN, model, endpoint,
                "не задан адрес эндпоинта — проверить модель нельзя.",
            )
        }

        // A stale cache entry never short-circuits: fall through to a fresh probe.
        val outcome = probe(model, endpoint)
        return@withContext when (outcome) {
            VisionSupport.SUPPORTED, VisionSupport.UNSUPPORTED -> {
                putCachedEntry(model, endpoint, CachedEntry(outcome == VisionSupport.SUPPORTED, now))
                VisionState(
                    support = outcome,
                    source = VisionSource.PROBE,
                    model = model,
                    endpoint = endpoint,
                    detail = if (outcome == VisionSupport.SUPPORTED) {
                        "провайдер принял тестовое изображение — Vision поддерживается."
                    } else {
                        "провайдер отклонил тестовое изображение — Vision не поддерживается."
                    },
                )
            }
            VisionSupport.UNKNOWN -> {
                if (allowlisted != null) {
                    // A forced probe failed, but the id itself is a known vision model.
                    VisionState(
                        support = VisionSupport.SUPPORTED,
                        source = VisionSource.ALLOWLIST,
                        model = model,
                        endpoint = endpoint,
                        detail = "проба не удалась, но модель «$model» известна как vision-модель «$allowlisted».",
                    )
                } else {
                    VisionState(
                        support = VisionSupport.UNKNOWN,
                        source = VisionSource.UNKNOWN,
                        model = model,
                        endpoint = endpoint,
                        detail = "эндпоинт недоступен или ответ неоднозначен — поддержка изображений неизвестна.",
                    )
                }
            }
        }
    }

    /** One bounded probe; any transport failure is an honest UNKNOWN, never a fake verdict. */
    private suspend fun probe(model: String, endpoint: String): VisionSupport =
        withContext(Dispatchers.IO) {
            val apiKey = runCatching { settingsStore.getApiKey() }.getOrNull()
            // Same auth scheme as the chat request (Bearer / custom header / query / none); a blank
            // key still adds nothing, so a keyless local server keeps working.
            val authConfig = runCatching { settingsStore.getAuthConfig() }
                .getOrDefault(LlmAuthConfig.DEFAULT)
            val client = okHttpClient.newBuilder()
                .callTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
            val request = try {
                val builder = Request.Builder()
                    .url(LlmAuth.urlWithAuth(VisionPolicy.probeUrl(endpoint), apiKey, authConfig))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .post(VisionPolicy.probeRequestBody(model, PROBE_PNG_BASE64).toRequestBody(JSON))
                LlmAuth.applyHeader(builder, apiKey, authConfig)
                builder.build()
            } catch (e: Exception) {
                Log.w(TAG, "Vision probe request invalid: ${e.message}")
                return@withContext VisionSupport.UNKNOWN
            }
            try {
                client.newCall(request).execute().use { response ->
                    VisionPolicy.classifyProbeResponse(response.code, response.body?.string().orEmpty())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Vision probe failed: ${e.message}")
                VisionSupport.UNKNOWN
            }
        }

    private data class CachedEntry(val supported: Boolean, val checkedAt: Long)

    private fun cachedEntry(model: String, endpoint: String): CachedEntry? {
        if (model.isBlank() || endpoint.isBlank()) return null
        val raw = prefs.getString(key(model, endpoint), null) ?: return null
        val parts = raw.split('|', limit = 2)
        if (parts.size != 2) return null
        val supported = parts[0].toBooleanStrictOrNull() ?: return null
        val checkedAt = parts[1].toLongOrNull() ?: return null
        return CachedEntry(supported, checkedAt)
    }

    private fun putCachedEntry(model: String, endpoint: String, entry: CachedEntry) {
        runCatching {
            prefs.edit()
                .putString(key(model, endpoint), "${entry.supported}|${entry.checkedAt}")
                .apply()
        }.onFailure { Log.e(TAG, "storing vision cache failed", it) }
    }

    private fun key(model: String, endpoint: String): String =
        KEY_PREFIX + VisionPolicy.cacheKey(model, endpoint)

    private fun describeAge(ageMs: Long): String = when {
        ageMs < 60_000 -> "меньше минуты"
        ageMs < 60 * 60_000 -> "${ageMs / 60_000} мин"
        ageMs < 24 * 60 * 60_000 -> "${ageMs / (60 * 60_000)} ч"
        else -> "${ageMs / (24 * 60 * 60_000)} дн"
    }

    companion object {
        private const val TAG = "Andharness"
        private const val PREFS_FILE = "andharness_vision"
        private const val KEY_PREFIX = "vision_cache_"
        private const val PROBE_TIMEOUT_SECONDS = 15L

        private val JSON = "application/json".toMediaType()

        /**
         * A valid 1×1 PNG (70 bytes) generated once and reused for every probe; the base64 form is
         * what the `image_url` data URI carries.
         */
        private val PROBE_PNG_BASE64: String = Base64.getEncoder().encodeToString(
            Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
            )
        )
    }
}
