package com.andharness.ru.data.update

import com.andharness.ru.domain.update.UpdateManifest
import com.andharness.ru.domain.update.UpdatePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.andharness.ru.domain.serialization.AppJson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches and validates the update [UpdateManifest].
 *
 * Uses a bounded client derived from the shared [OkHttpClient] so a slow/unreachable update host can
 * never hang the caller (mirrors `ModelsCatalogClient`). The request is refused unless it is HTTPS,
 * and the parsed manifest must pass [UpdatePolicy.validate]; every failure is surfaced as an honest
 * [Result.failure] with a Russian message, never an exception.
 *
 * References:
 * - OkHttp `Request.Builder` / timeouts:
 *   https://square.github.io/okhttp/4/okhttp/okhttp3/OkHttpClient.Builder/
 * - kotlinx.serialization JSON parsing:
 *   https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/
 * - HTTPS-only transport (the app's network security config permits cleartext only for the
 *   loopback local LLM server, so external cleartext is refused by the platform):
 *   https://developer.android.com/privacy-and-security/security-config
 */
@Singleton
class UpdateClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {

    /** Bounded client: a manifest fetch must never hang the update check. */
    private val httpClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
    }

    private val json = AppJson.Lenient

    /**
     * GETs [url], parses it into an [UpdateManifest] and validates it. Returns [Result.failure] with a
     * Russian reason for a cleartext URL, a network error, a non-2xx status, unparseable JSON or a
     * manifest that fails [UpdatePolicy.validate].
     */
    suspend fun fetchManifest(
        url: String = UpdateConfig.MANIFEST_URL,
    ): Result<UpdateManifest> = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://")) {
            return@withContext Result.failure(
                IllegalArgumentException("Адрес манифеста обновления должен начинаться с https://.")
            )
        }
        return@withContext try {
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("Сервер обновлений ответил HTTP ${response.code}.")
                    )
                }
                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    return@withContext Result.failure(
                        IOException("Сервер обновлений вернул пустой ответ.")
                    )
                }
                val manifest = json.decodeFromString<UpdateManifest>(body)
                val problem = UpdatePolicy.validate(manifest)
                if (problem != null) {
                    Result.failure(IOException(problem))
                } else {
                    Result.success(manifest)
                }
            }
        } catch (e: Exception) {
            Result.failure(
                IOException(
                    e.message?.takeIf { it.isNotBlank() }
                        ?: "Не удалось получить манифест обновления."
                )
            )
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_SEC = 10L
        const val READ_TIMEOUT_SEC = 20L
        const val CALL_TIMEOUT_SEC = 25L
    }
}
